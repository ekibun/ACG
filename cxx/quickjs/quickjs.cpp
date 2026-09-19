#include "quickjs/quickjs.h"

#include <jni.h>

#include <chrono>
#include <cstring>
#include <unordered_map>

extern "C" {
#include "quickjs/cutils.h"
#include "quickjs/libregexp.h"
#include "quickjs/libunicode.h"
#include "quickjs/quickjs-atom.h"
}

struct JSRuntimeOpaque {
  JavaVM* javaVm;
  jobject thiz;
  JSClassID javaClassID;
  // 0 表示不限制：memoryLimit 为 0 就不调 JS_SetMemoryLimit。
  int64_t memoryLimit;
  int64_t timeoutMs;
  std::chrono::steady_clock::time_point evalStart;
  bool interrupted;
};

// 两件**分开**的事，故意不合成一件：
//
//   jsReleaseValue  -> 放掉一票 JS 引用（JS_FreeValue）
//   jsDestroyHandle -> 释放堆上的包装（delete）
//
// 以前两者合成一个 jsFreeValue()，`definePropertyValue` 正是因此写不对：释放
// 包装会顺带把 JS 引用计数减一，于是只想放掉自己包装的调用点会**悄悄偷走**
// 别人的一票。拆开之后，同一个句柄可以在多次 JS 操作之间复用（每次配一次
// jsDupValue），而包装仍然只销毁一次。
void jsReleaseValue(jlong ctx, jlong obj) {
  if (obj == 0) return;
  JS_FreeValue((JSContext*)ctx, *((JSValue*)obj));
}

void jsDestroyHandle(jlong obj) { delete (JSValue*)obj; }

extern "C" JNIEXPORT jlong JNICALL Java_soko_ekibun_quickjs_QuickJS_initContext(
    JNIEnv* env, jclass, jobject ctx, jlong stack_size, jlong memory_limit,
    jlong timeout_ms) {
  JavaVM* javaVm;
  env->GetJavaVM(&javaVm);
  JSRuntime* rt = JS_NewRuntime();
  if (rt == nullptr) return 0;
  auto opaque = new JSRuntimeOpaque{
      javaVm,     env->NewWeakGlobalRef(ctx),       0,    memory_limit,
      timeout_ms, std::chrono::steady_clock::now(), false};
  // QuickJS 自己默认给 1MB 的栈预算（JS_DEFAULT_STACK_SIZE），和 JVM 线程栈
  // （约 1MB）同量级 —— 深递归真把**宿主**线程栈走穿，挂的是整个进程。
  // 预算必须远低于宿主线程栈：stack_limit = stack_top - stack_size，而
  // stack_top 已经落在 JNI 帧里了；256KB 是给上面那些 JNI/Java 帧留的余量。
  JS_SetMaxStackSize(rt, stack_size > 0 ? stack_size : 256 * 1024);
  if (memory_limit > 0) JS_SetMemoryLimit(rt, (size_t)memory_limit);
  // 协作式中断：JS_ExecutePendingJob / JS_Eval 会轮询这个回调，`while(true){}`
  // 就是靠它变成 "InternalError: interrupted" 的。
  JS_SetInterruptHandler(
      rt,
      [](JSRuntime* rt, void*) -> int {
        auto opaque = (JSRuntimeOpaque*)JS_GetRuntimeOpaque(rt);
        if (opaque == nullptr || opaque->timeoutMs <= 0) return 0;
        auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
                           std::chrono::steady_clock::now() - opaque->evalStart)
                           .count();
        if (elapsed <= opaque->timeoutMs) return 0;
        opaque->interrupted = true;
        return 1;
      },
      nullptr);
  JS_SetModuleLoaderFunc(
      rt, nullptr,
      [](JSContext* ctx, const char* module_name, void*) -> JSModuleDef* {
        auto rt = JS_GetRuntime(ctx);
        auto opaque = (JSRuntimeOpaque*)JS_GetRuntimeOpaque(rt);
        if (opaque == nullptr) return nullptr;
        JNIEnv* env;
        opaque->javaVm->GetEnv((void**)&env, JNI_VERSION_1_4);
        jclass clazz = env->GetObjectClass(opaque->thiz);
        jmethodID load = env->GetMethodID(
            clazz, "loadModule", "(Ljava/lang/String;)Ljava/lang/String;");
        auto javaStr = env->NewStringUTF(module_name);
        auto retJava =
            (jstring)env->CallObjectMethod(opaque->thiz, load, javaStr);
        env->DeleteLocalRef(javaStr);
        if (retJava == nullptr) {
          // QuickJS 要求 loader 返回 NULL 时**必须**挂着一个待处理异常
          // （见 js_host_resolve_imported_module）。光返回 NULL，就会由
          // JS_LoadModuleInternal 去报运行期里恰好残留的那个旧异常。
          JS_ThrowReferenceError(ctx, "could not load module '%s'",
                                 module_name);
          return nullptr;
        }
        auto str = env->GetStringUTFChars(retJava, nullptr);
        JSValue func_val =
            JS_Eval(ctx, str, strlen(str), module_name,
                    JS_EVAL_TYPE_MODULE | JS_EVAL_FLAG_COMPILE_ONLY);
        env->ReleaseStringUTFChars(retJava, str);
        env->DeleteLocalRef(retJava);
        if (JS_IsException(func_val)) return nullptr;
        // 模块已经被引用过了，这里把这个 JSValue 放掉。
        auto m = (JSModuleDef*)JS_VALUE_GET_PTR(func_val);
        JS_FreeValue(ctx, func_val);
        return m;
      },
      nullptr);
  JS_SetRuntimeOpaque(rt, opaque);
  JS_NewClassID(&(opaque->javaClassID));
  if (!JS_IsRegisteredClass(rt, opaque->javaClassID)) {
    JSClassDef def{
        "JavaObject",
        // 类析构回调：把挂在 JS 对象上的 Java 全局引用放掉
        [](JSRuntime* rt, JSValue obj) noexcept {
          auto opaque = (JSRuntimeOpaque*)JS_GetRuntimeOpaque(rt);
          if (opaque == nullptr) return;
          JNIEnv* env;
          opaque->javaVm->GetEnv((void**)&env, JNI_VERSION_1_4);
          env->DeleteGlobalRef((jobject)JS_GetOpaque(obj, opaque->javaClassID));
        }};
    int e = JS_NewClass(rt, opaque->javaClassID, &def);
    if (e < 0) {
      JS_FreeRuntime(rt);
      return 0;
    }
  }
  return (jlong)JS_NewContext(rt);
}
extern "C" JNIEXPORT void JNICALL
Java_soko_ekibun_quickjs_QuickJS_destroyContext(JNIEnv*, jclass, jlong ctx) {
  JSRuntime* rt = JS_GetRuntime((JSContext*)ctx);
  JS_FreeContext((JSContext*)ctx);
  JS_SetRuntimeOpaque(rt, nullptr);
  JS_FreeRuntime(rt);
}
extern "C" JNIEXPORT jlong JNICALL
Java_soko_ekibun_quickjs_QuickJS_jsNewError(JNIEnv*, jclass, jlong ctx) {
  return (jlong) new JSValue(JS_NewError((JSContext*)ctx));
}
extern "C" JNIEXPORT jlong JNICALL Java_soko_ekibun_quickjs_QuickJS_jsNewString(
    JNIEnv* env, jclass, jlong ctx, jstring obj) {
  auto jstr = env->GetStringUTFChars(obj, nullptr);
  auto ret = (jlong) new JSValue(JS_NewString((JSContext*)ctx, jstr));
  env->ReleaseStringUTFChars(obj, jstr);
  return ret;
}
extern "C" JNIEXPORT jlong JNICALL Java_soko_ekibun_quickjs_QuickJS_jsNewBool(
    JNIEnv*, jclass, jlong ctx, jboolean obj) {
  return (jlong) new JSValue(JS_NewBool((JSContext*)ctx, obj));
}
extern "C" JNIEXPORT jlong JNICALL Java_soko_ekibun_quickjs_QuickJS_jsNewInt64(
    JNIEnv*, jclass, jlong ctx, jlong obj) {
  return (jlong) new JSValue(JS_NewInt64((JSContext*)ctx, obj));
}
extern "C" JNIEXPORT jlong JNICALL
Java_soko_ekibun_quickjs_QuickJS_jsNewFloat64(JNIEnv*, jclass, jlong ctx,
                                              jdouble obj) {
  return (jlong) new JSValue(JS_NewFloat64((JSContext*)ctx, obj));
}
extern "C" JNIEXPORT jlong JNICALL
Java_soko_ekibun_quickjs_QuickJS_jsNewArrayBuffer(JNIEnv* env, jclass,
                                                  jlong ctx, jbyteArray obj) {
  // JS_NewArrayBufferCopy 会复制字节，所以返回前必须把 Java 数组放掉
  // （JNI_ABORT = 不回写）。留着不放的话，每构造一个 ArrayBuffer 就漏一个
  // local ref / pinned buffer。
  auto len = env->GetArrayLength(obj);
  auto elems = env->GetByteArrayElements(obj, nullptr);
  auto ret = (jlong) new JSValue(
      JS_NewArrayBufferCopy((JSContext*)ctx, (const uint8_t*)elems, len));
  env->ReleaseByteArrayElements(obj, elems, JNI_ABORT);
  return ret;
}
extern "C" JNIEXPORT jlong JNICALL
Java_soko_ekibun_quickjs_QuickJS_jsNewObject(JNIEnv*, jclass, jlong ctx) {
  return (jlong) new JSValue(JS_NewObject((JSContext*)ctx));
}
extern "C" JNIEXPORT jlong JNICALL
Java_soko_ekibun_quickjs_QuickJS_jsNewArray(JNIEnv*, jclass, jlong ctx) {
  return (jlong) new JSValue(JS_NewArray((JSContext*)ctx));
}
extern "C" JNIEXPORT jlong JNICALL
Java_soko_ekibun_quickjs_QuickJS_jsNULL(JNIEnv*, jclass) {
  return (jlong) new JSValue(JS_NULL);
}
extern "C" JNIEXPORT jint JNICALL
Java_soko_ekibun_quickjs_QuickJS_definePropertyValue(JNIEnv*, jclass, jlong ctx,
                                                     jlong obj, jlong k,
                                                     jlong v, jint flags) {
  auto atom = JS_ValueToAtom((JSContext*)ctx, *(JSValue*)k);
  auto ret = JS_DefinePropertyValue((JSContext*)ctx, *(JSValue*)obj, atom,
                                    *(JSValue*)v, flags);
  JS_FreeAtom((JSContext*)ctx, atom);
  // 这个函数收下 k / v **两个**句柄，并让它们彻底退场：
  //
  //   v —— JS_DefinePropertyValue 的函数体以 `JS_FreeValue(ctx, val)` 收尾，
  //        调用方那一票已经被被调方吃掉（属性自己另持一票）。这里再放一次就是
  //        双重释放 —— gc_decref_child 里那句 `js_rc(p)->ref_count > 0` 断言
  //        就是这么来的。
  //   k —— JS_ValueToAtom 只是借用，这一票得我们自己放。
  //
  // 两个包装也一并销毁：每个调用点都是一次性的「定义这个属性」，没人会再用那个
  // k/v 句柄。把「放引用」与「销毁包装」合在这里做，才不会让没被消费掉的
  // `new JSValue` 在引用账本上留下一笔。
  jsReleaseValue(ctx, k);
  jsDestroyHandle(k);
  jsDestroyHandle(v);
  return ret;
}
extern "C" JNIEXPORT jlong JNICALL Java_soko_ekibun_quickjs_QuickJS_jsDupValue(
    JNIEnv*, jclass, jlong ctx, jlong obj) {
  return (jlong) new JSValue(JS_DupValue((JSContext*)ctx, *(JSValue*)obj));
}
extern "C" JNIEXPORT jboolean JNICALL
Java_soko_ekibun_quickjs_QuickJS_isException(JNIEnv*, jclass, jlong obj) {
  return JS_IsException(*(JSValue*)obj);
}
extern "C" JNIEXPORT void JNICALL
Java_soko_ekibun_quickjs_QuickJS_jsReleaseValue(JNIEnv*, jclass, jlong ctx,
                                                jlong obj) {
  jsReleaseValue(ctx, obj);
}
extern "C" JNIEXPORT void JNICALL
Java_soko_ekibun_quickjs_QuickJS_jsDestroyHandle(JNIEnv*, jclass, jlong obj) {
  jsDestroyHandle(obj);
}
extern "C" JNIEXPORT jlong JNICALL Java_soko_ekibun_quickjs_QuickJS_evaluate(
    JNIEnv* env, jclass, jlong ctx, jstring cmd, jstring name, jint flag) {
  JS_UpdateStackTop(JS_GetRuntime((JSContext*)ctx));
  // 每次求值都重新起算超时窗口。
  auto opaque =
      (JSRuntimeOpaque*)JS_GetRuntimeOpaque(JS_GetRuntime((JSContext*)ctx));
  if (opaque != nullptr) {
    opaque->evalStart = std::chrono::steady_clock::now();
    opaque->interrupted = false;
  }
  // JS_Eval 不接管源码：用完两个字符串都要放掉，否则每次 evaluate() 都会漏
  // 一份 pinned 的脚本副本。
  auto cmdChars = env->GetStringUTFChars(cmd, nullptr);
  auto cmdLen = env->GetStringUTFLength(cmd);
  auto nameChars = env->GetStringUTFChars(name, nullptr);
  auto ret = (jlong) new JSValue(
      JS_Eval((JSContext*)ctx, cmdChars, cmdLen, nameChars, flag));
  env->ReleaseStringUTFChars(name, nameChars);
  env->ReleaseStringUTFChars(cmd, cmdChars);
  return ret;
}

jstring jsToString(JNIEnv* env, JSContext* ctx, JSValue val) {
  auto pstr = JS_ToCString(ctx, val);
  auto ret = env->NewStringUTF(pstr);
  JS_FreeCString(ctx, pstr);
  return ret;
}

jthrowable jsToThrowable(JNIEnv* env, JSContext* ctx, JSValue val) {
  jclass clazz = env->FindClass("soko/ekibun/quickjs/JSError");
  jmethodID init = env->GetMethodID(clazz, "<init>",
                                    "(Ljava/lang/String;Ljava/lang/String;)V");
  auto atomStack = JS_NewAtom(ctx, "stack");
  jstring stack = nullptr;
  if (JS_HasProperty(ctx, val, atomStack)) {
    auto jsStack = JS_GetProperty(ctx, val, atomStack);
    stack = jsToString(env, ctx, jsStack);
    JS_FreeValue(ctx, jsStack);
  }
  JS_FreeAtom(ctx, atomStack);
  return (jthrowable)env->NewObject(clazz, init, jsToString(env, ctx, val),
                                    stack);
}

extern "C" JNIEXPORT jthrowable JNICALL
Java_soko_ekibun_quickjs_QuickJS_getException(JNIEnv* env, jclass, jlong ctx) {
  auto err = JS_GetException((JSContext*)ctx);
  auto ret = jsToThrowable(env, (JSContext*)ctx, err);
  JS_FreeValue((JSContext*)ctx, err);
  return ret;
}

static jobject jsToJavaObject(JNIEnv* env, JSContext* ctx, JSValue obj,
                              std::unordered_map<void*, jobject>& cache);

/**
 * 标量（非对象）转换。对象由下面统一的 [jsToJava] 分派器交给 [jsToJavaObject]，
 * 所以这里根本不必知道对象的存在 —— 也就不会对着对象悄悄返回 NULL。
 */
static jobject jsToJavaScalar(JNIEnv* env, JSContext* ctx, JSValue obj) {
  int tag = JS_VALUE_GET_TAG(obj);
  if (JS_TAG_IS_FLOAT64(tag)) {
    double p;
    JS_ToFloat64(ctx, &p, obj);
    jclass jclazz = env->FindClass("java/lang/Double");
    jmethodID jmethod = env->GetMethodID(jclazz, "<init>", "(D)V");
    return env->NewObject(jclazz, jmethod, p);
  }
  switch (tag) {
    case JS_TAG_BOOL: {
      jclass jclazz = env->FindClass("java/lang/Boolean");
      jmethodID jmethod = env->GetMethodID(jclazz, "<init>", "(Z)V");
      return env->NewObject(jclazz, jmethod, JS_ToBool(ctx, obj));
    }
    case JS_TAG_INT: {
      int64_t p;
      JS_ToInt64(ctx, &p, obj);
      jclass jclazz = env->FindClass("java/lang/Long");
      jmethodID jmethod = env->GetMethodID(jclazz, "<init>", "(J)V");
      return env->NewObject(jclazz, jmethod, p);
    }
    case JS_TAG_STRING: {
      return jsToString(env, ctx, obj);
    }
    default:
      return nullptr;
  }
}

/**
 * 所有 JS → Java 转换的唯一入口。
 *
 * 对象分支放在**这里**而不是各个调用点，是有原因的：递归转换（数组元素、promise
 * 的 `then`）以前走的是只认标量的辅助函数，于是顺着它们碰到的对象一律悄悄
 * 变成 NULL。把分派收在一处，这种错就不可能再发生。
 */
jobject jsToJava(JNIEnv* env, JSContext* ctx, JSValue obj,
                 std::unordered_map<void*, jobject> cache =
                     std::unordered_map<void*, jobject>()) {
  if (JS_VALUE_GET_TAG(obj) == JS_TAG_OBJECT)
    return jsToJavaObject(env, ctx, obj, cache);
  return jsToJavaScalar(env, ctx, obj);
}

/**
 * [jsToJava] 的对象 / 函数分支。单独拆出来，因为只有它要查那张常驻包装表，
 * 而那张表会预先往 `cache` 里塞东西 —— 拆开之后，上面那条标量快路径不夹带
 * 任何 Java 上行调用。
 */
static jobject jsToJavaObject(JNIEnv* env, JSContext* ctx, JSValue obj,
                              std::unordered_map<void*, jobject>& cache) {
  {  // ArrayBuffer
    size_t size;
    uint8_t* buf = JS_GetArrayBuffer(ctx, &size, obj);
    if (!buf) {
      // 这里只是探个类型：非 ArrayBuffer 的对象，JS_GetArrayBuffer 已经抛了
      // TypeError（"ArrayBuffer object expected"）。把它留着不清理，后面每个
      // 「返回 NULL 却不抛异常」的 API 都会被这个旧异常污染。放掉它。
      JS_FreeValue(ctx, JS_GetException(ctx));
    } else {
      jbyteArray arr = env->NewByteArray((jsize)size);
      env->SetByteArrayRegion(arr, 0, (jsize)size, (int8_t*)buf);
      return arr;
    }
  }
  // javaObject
  auto opaque = (JSRuntimeOpaque*)JS_GetRuntimeOpaque(JS_GetRuntime(ctx));
  auto javaObj = JS_GetOpaque(obj, opaque->javaClassID);
  if (javaObj) return (jobject)javaObj;
  // 这个对象可能早在另一次转换里就造过包装。它登记在 Kotlin 侧
  // （见 `Context.wrapperCache`），优先级必须高于每次调用自带的那张 `cache`，
  // 否则「同一个对象给同一个包装」只会在**一次**遍历里成立。
  auto ptr = JS_VALUE_GET_PTR(obj);
  jclass ctxClazz = env->GetObjectClass(opaque->thiz);
  auto persistent = env->CallObjectMethod(
      opaque->thiz,
      env->GetMethodID(ctxClazz, "peekWrapper", "(J)Ljava/lang/Object;"),
      (jlong)ptr);
  if (persistent != nullptr) {
    // 复用：常驻表那一份已经持着包装原来那一票，这里再为调用方单独记一票
    // （见 `reuseWrapper` 里的 `dup()`），并把配对写进 `cache`，好让调用方
    // 那一层自己把账平掉。
    auto wrapper = env->CallObjectMethod(
        opaque->thiz,
        env->GetMethodID(ctxClazz, "reuseWrapper",
                         "(Ljava/lang/Object;J)Ljava/lang/Object;"),
        persistent, (jlong) new JSValue(JS_DupValue(ctx, obj)));
    cache[ptr] = wrapper;
    return wrapper;
  }
  if (cache.find(ptr) != cache.end()) return cache[ptr];
  auto remember = [&](jobject wrapper) -> jobject {
    cache[ptr] = wrapper;
    // 登记给后续转换用。新造的包装恰好持一票，所以这里登记的句柄就是调用方
    // 将来要退场的那一个。
    env->CallVoidMethod(
        opaque->thiz,
        env->GetMethodID(ctxClazz, "registerWrapper", "(JLjava/lang/Object;)V"),
        (jlong)ptr, wrapper);
    return wrapper;
  };
  if (JS_IsFunction(ctx, obj)) {
    jclass clazz = env->FindClass("soko/ekibun/quickjs/JSFunction");
    jmethodID init = env->GetMethodID(
        clazz, "<init>", "(JLsoko/ekibun/quickjs/QuickJS$Context;)V");
    return remember(env->NewObject(
        clazz, init, (jlong) new JSValue(JS_DupValue(ctx, obj)), opaque->thiz));
  } else if (JS_IsError(ctx, obj)) {
    return jsToThrowable(env, ctx, obj);
  } else if (JS_IsPromise(ctx, obj)) {
    // **不**登记进那张常驻包装表：promise 的包装由 `wrapJSPromiseAsync` 驱动，
    // 而它需要一个还活着的 `then` 回调。缓存条目会在那个回调（连同它的 `then`
    // 引用）退场**之后**才被重放 —— 就是 flutter_qjs deferred 测试里那个被复用
    // 的 Deferred 的下场。同一次遍历内仍由下面的 `cache` 保证「同一个对象给
    // 同一个包装」，因为那时候对象身份还有效。
    jclass clazz = env->GetObjectClass(opaque->thiz);
    jmethodID wrap = env->GetMethodID(
        clazz, "wrapJSPromiseAsync",
        "(JLsoko/ekibun/quickjs/JSFunction;)Lkotlinx/coroutines/Deferred;");
    auto thenJs = JS_GetPropertyStr(ctx, obj, "then");
    auto thenJava = jsToJava(env, ctx, thenJs, cache);
    JS_FreeValue(ctx, thenJs);
    auto ret = env->CallObjectMethod(opaque->thiz, wrap,
                                     (jlong) new JSValue(JS_DupValue(ctx, obj)),
                                     thenJava);
    env->DeleteLocalRef(thenJava);
    cache[ptr] = ret;
    return ret;
  } else if (JS_IsArray(ctx, obj)) {
    auto jsArrLen = JS_GetPropertyStr(ctx, obj, "length");
    int64_t arrLen;
    JS_ToInt64(ctx, &arrLen, jsArrLen);
    JS_FreeValue(ctx, jsArrLen);
    auto list = env->NewObjectArray(
        (int)arrLen, env->FindClass("java/lang/Object"), nullptr);
    cache[ptr] = list;
    for (int i = 0; i < arrLen; i++) {
      auto jsprop = JS_GetPropertyUint32(ctx, obj, i);
      // 只转一次：递归调用会把结果登记进 `cache`，同一个元素再转一次，拿到的
      // 会是下面已经删掉的那个 local ref。
      auto jval = jsToJava(env, ctx, jsprop, cache);
      env->SetObjectArrayElement(list, i, jval);
      env->DeleteLocalRef(jval);
      JS_FreeValue(ctx, jsprop);
    }
    return list;
  } else {
    jclass clazz = env->FindClass("soko/ekibun/quickjs/JSObject");
    jmethodID init = env->GetMethodID(
        clazz, "<init>", "(JLsoko/ekibun/quickjs/QuickJS$Context;)V");
    return remember(env->NewObject(
        clazz, init, (jlong) new JSValue(JS_DupValue(ctx, obj)), opaque->thiz));
  }
}

jobject jsToJavaEntry(JNIEnv* env, JSContext* ctx, JSValue obj) {
  std::unordered_map<void*, jobject> cache;
  return jsToJava(env, ctx, obj, cache);
}

extern "C" JNIEXPORT jobject JNICALL Java_soko_ekibun_quickjs_QuickJS_jsToJava(
    JNIEnv* env, jclass, jlong ctx, jlong obj) {
  auto ret = jsToJavaEntry(env, (JSContext*)ctx, *(JSValue*)obj);
  // jsToJava 会吃掉给它的那一票（JS_GetProperty / JS_Call 的返回值是**交出来**
  // 而不是借出），所以在这里放掉。包装本身不受影响 —— 调用方可能还握着它，它的
  // 生命周期之后归显式管理。
  jsReleaseValue(ctx, obj);
  return ret;
}
extern "C" JNIEXPORT jlong JNICALL
Java_soko_ekibun_quickjs_QuickJS_jsNewCFunction(JNIEnv*, jclass, jlong ctx,
                                                jlong obj) {
  return (jlong) new JSValue(JS_NewCFunctionData(
      (JSContext*)ctx,
      [](JSContext* ctx, JSValueConst this_val, int argc, JSValueConst* argv,
         int magic, JSValue* func_data) -> JSValue {
        auto rt = JS_GetRuntime(ctx);
        auto opaque = (JSRuntimeOpaque*)JS_GetRuntimeOpaque(rt);
        if (opaque == nullptr)
          return JS_ThrowInternalError(ctx, "Missing RuntimeOpaque");
        JNIEnv* env;
        opaque->javaVm->GetEnv((void**)&env, JNI_VERSION_1_4);
        auto obj = (jobject)JS_GetOpaque(func_data[0], opaque->javaClassID);
        auto clazz = env->GetObjectClass(opaque->thiz);
        jmethodID invoke =
            env->GetMethodID(clazz, "handleJSInvokable",
                             "(Lsoko/ekibun/quickjs/JSInvokable;[Ljava/lang/"
                             "Object;Ljava/lang/Object;)J");
        auto list = env->NewObjectArray(
            (int)argc, env->FindClass("java/lang/Object"), nullptr);
        for (int i = 0; i < argc; ++i) {
          auto v = jsToJava(env, ctx, argv[i]);
          env->SetObjectArrayElement(list, i, v);
          env->DeleteLocalRef(v);
        }
        auto thisJava = jsToJava(env, ctx, this_val);
        auto retPtr = (JSValue*)env->CallLongMethod(opaque->thiz, invoke, obj,
                                                    list, thisJava);
        env->DeleteLocalRef(list);
        env->DeleteLocalRef(thisJava);
        JSValue ret = *retPtr;
        delete retPtr;
        return ret;
      },
      0, 0, 1, (JSValue*)obj));
}
extern "C" JNIEXPORT jlong JNICALL
Java_soko_ekibun_quickjs_QuickJS_jsThrowError(JNIEnv*, jclass, jlong ctx,
                                              jlong err) {
  JS_Throw((JSContext*)ctx, JS_DupValue((JSContext*)ctx, *(JSValue*)err));
  // JS_Throw 自己用 JS_DupValue 记了一票，所以把我们这份放掉；这个句柄从
  // javaToJs 出来就是一次性的，跟着一起退场。
  jsReleaseValue(ctx, err);
  jsDestroyHandle(err);
  return (jlong) new JSValue(JS_EXCEPTION);
}
extern "C" JNIEXPORT jlong JNICALL
Java_soko_ekibun_quickjs_QuickJS_jsWrapObject(JNIEnv* env, jclass, jlong ctx,
                                              jobject obj) {
  auto rt = JS_GetRuntime((JSContext*)ctx);
  auto opaque = (JSRuntimeOpaque*)JS_GetRuntimeOpaque(rt);
  auto jsObj =
      new JSValue(JS_NewObjectClass((JSContext*)ctx, (int)opaque->javaClassID));
  if (!JS_IsException(*jsObj)) JS_SetOpaque(*jsObj, env->NewGlobalRef(obj));
  return (jlong)jsObj;
}
extern "C" JNIEXPORT jlong JNICALL Java_soko_ekibun_quickjs_QuickJS_jsCall(
    JNIEnv* env, jclass, jlong ctx, jlong obj, jlong this_val, jint argc,
    jlongArray argv) {
  JSRuntime* rt = JS_GetRuntime((JSContext*)ctx);
  JS_UpdateStackTop(rt);
  auto longArr = env->GetLongArrayElements(argv, nullptr);
  // JS_Call 只是借用 argv，那些 JSValue 结构仍归调用方（Kotlin 在调用返回后
  // 立刻把每个参数放掉）。这里用 js_malloc 临时铺一份、调完立刻 js_free。
  auto argvJs = (JSValue*)js_malloc((JSContext*)ctx,
                                    sizeof(JSValue) * (argc > 0 ? argc : 1));
  if (argvJs == nullptr) {
    env->ReleaseLongArrayElements(argv, longArr, JNI_ABORT);
    JS_ThrowOutOfMemory((JSContext*)ctx);
    return (jlong) new JSValue(JS_EXCEPTION);
  }
  for (int i = 0; i < argc; ++i) {
    argvJs[i] = *(JSValue*)longArr[i];
  }
  auto ret = new JSValue(JS_Call((JSContext*)ctx, *(JSValue*)obj,
                                 *(JSValue*)this_val, argc, argvJs));
  js_free((JSContext*)ctx, argvJs);
  env->ReleaseLongArrayElements(argv, longArr, JNI_ABORT);
  return (jlong)ret;
}
extern "C" JNIEXPORT jint JNICALL
Java_soko_ekibun_quickjs_QuickJS_executePendingJob(JNIEnv*, jclass, jlong ctx) {
  auto rt = JS_GetRuntime((JSContext*)ctx);
  JS_UpdateStackTop(rt);
  JSContext* pctx;
  return JS_ExecutePendingJob(rt, &pctx);
}
extern "C" JNIEXPORT jlongArray JNICALL
Java_soko_ekibun_quickjs_QuickJS_jsNewPromise(JNIEnv* env, jclass, jlong ctx) {
  // JS_NewPromiseCapability 往数组里写两个**新持有**的引用，所以各自造一个
  // 包装即可，不用再 dup。
  auto resolving_funcs = new JSValue[2];
  auto promise = (jlong) new JSValue(
      JS_NewPromiseCapability((JSContext*)ctx, resolving_funcs));
  // 把 resolve / reject 两个函数各自搬进单独的分配里，这样它们的包装可以分别
  // 被 jsDestroyHandle() 销毁。
  auto resolve = (jlong) new JSValue(resolving_funcs[0]);
  auto reject = (jlong) new JSValue(resolving_funcs[1]);
  delete[] resolving_funcs;
  auto arr = env->NewLongArray(3);
  env->SetLongArrayRegion(arr, 0, 1, &promise);
  env->SetLongArrayRegion(arr, 1, 1, &resolve);
  env->SetLongArrayRegion(arr, 2, 1, &reject);
  return arr;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_soko_ekibun_quickjs_Highlight_isIdentFirst(JNIEnv*, jobject, jchar c) {
  // 标识符首字符：字母/下划线/$（lre_is_id_start_byte）
  return (jboolean)lre_js_is_ident_first(c);
}
extern "C" JNIEXPORT jboolean JNICALL
Java_soko_ekibun_quickjs_Highlight_isIdentNext(JNIEnv*, jobject, jchar c) {
  // 标识符后续字符：首字符集 + 数字（lre_is_id_continue_byte）
  return (jboolean)lre_js_is_ident_next(c);
}
extern "C" JNIEXPORT jlong JNICALL
Java_soko_ekibun_quickjs_QuickJS_getPropertyValue(JNIEnv*, jclass, jlong ctx,
                                                  jlong obj, jlong k) {
  auto atom = JS_ValueToAtom((JSContext*)ctx, *(JSValue*)k);
  auto ret = JS_GetProperty((JSContext*)ctx, *(JSValue*)obj, atom);
  JS_FreeAtom((JSContext*)ctx, atom);
  // `k` 是调用方只为这次查表造的一次性键句柄：JS_ValueToAtom 只是借用，所以
  // 我们放掉它的引用、让包装退场。返回的 `ret` 才是交给调用方**新持有**的一票。
  jsReleaseValue(ctx, k);
  jsDestroyHandle(k);
  return (jlong) new JSValue(ret);
}
extern "C" JNIEXPORT jobjectArray JNICALL
Java_soko_ekibun_quickjs_QuickJS_getObjectKeys(JNIEnv* env, jclass, jlong ctx,
                                               jlong obj) {
  JSPropertyEnum* ptab;
  uint32_t plen;
  if (JS_GetOwnPropertyNames((JSContext*)ctx, &ptab, &plen, *(JSValue*)obj, -1))
    return nullptr;
  jobjectArray ret =
      env->NewObjectArray(plen, env->FindClass("java/lang/Object"), nullptr);
  for (uint32_t i = 0; i < plen; i++) {
    auto jskey = JS_AtomToValue((JSContext*)ctx, ptab[i].atom);
    auto key = jsToJava(env, (JSContext*)ctx, jskey);
    env->SetObjectArrayElement(ret, i, key);
    env->DeleteLocalRef(key);
    JS_FreeValue((JSContext*)ctx, jskey);
    JS_FreeAtom((JSContext*)ctx, ptab[i].atom);
  }
  js_free((JSContext*)ctx, ptab);
  return ret;
}