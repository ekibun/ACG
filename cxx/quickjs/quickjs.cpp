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
      // 类没注册成：这个 runtime 起不来，opaque 连它的 weak global ref
      // 一起收掉， 别把失败路径漏成一份常驻内存。
      JS_SetRuntimeOpaque(rt, nullptr);
      JS_FreeRuntime(rt);
      env->DeleteWeakGlobalRef(opaque->thiz);
      delete opaque;
      return 0;
    }
  }
  return (jlong)JS_NewContext(rt);
}
extern "C" JNIEXPORT void JNICALL
Java_soko_ekibun_quickjs_QuickJS_destroyContext(JNIEnv* env, jclass,
                                                jlong ctx) {
  JSRuntime* rt = JS_GetRuntime((JSContext*)ctx);
  auto opaque = (JSRuntimeOpaque*)JS_GetRuntimeOpaque(rt);
  // 顺序有讲究：JS_FreeContext / JS_FreeRuntime 期间会跑 JavaObject
  // 的类析构回调， 它得读到 opaque（javaVm + javaClassID）才能把挂着的 Java
  // 全局引用删掉。 以前这里先把 opaque 置成 nullptr，那些 global ref
  // 就被析构回调里那句 `if (opaque == nullptr) return;` 直接放过了 —— 漏的是
  // jobject 全局引用表。
  JS_FreeContext((JSContext*)ctx);
  JS_FreeRuntime(rt);
  if (opaque != nullptr) {
    // thiz 是 initContext 里 NewWeakGlobalRef 建的，得自己删；opaque 本体同理。
    env->DeleteWeakGlobalRef(opaque->thiz);
    delete opaque;
  }
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
 *
 * `cache`
 * 走**指针**而不是值：整棵对象图必须共用同一张表。按值传的话递归那一层拿到的是副本，它新登记的条目回不到上层
 * —— 环（`a['a'] = a`）还能靠「登记早于复制」侥幸命中，但**共享子对象**（`a.b =
 * c; a.d = c`）会被转成两份不同的副本，身份当场断掉。空指针 =
 * 「最外层调用，自己开一张」。
 */
jobject jsToJava(JNIEnv* env, JSContext* ctx, JSValue obj,
                 std::unordered_map<void*, jobject>* cache = nullptr) {
  std::unordered_map<void*, jobject> local;
  if (cache == nullptr) cache = &local;
  if (JS_VALUE_GET_TAG(obj) == JS_TAG_OBJECT)
    return jsToJavaObject(env, ctx, obj, *cache);
  return jsToJavaScalar(env, ctx, obj);
}

/**
 * 这个子值是不是已经登记在 [cache] 里（也就是：本次遍历中转过的容器）。
 *
 * 唯一的用途是**决定能不能删它的 JNI local ref**。cache 里存的全是 local ref；
 * 递归命中 cache 时，交还给我们的就是那一个 —— 删掉它等于销毁调用方（甚至外层
 * 容器本身）还要用的引用。`a['a'] = a` 时 `jVal` 正是 `map` 自己，删完全局返回
 * null（**不报错**，只是值凭空消失）。
 *
 * 判据只能是「递归**之后**它在不在 cache 里」：容器在（新建的也在，因为现在
 * 全图共用一张表），标量 / 字符串不在 —— 后者照删，别让 local ref 白涨。
 */
static bool cachedRefExists(const std::unordered_map<void*, jobject>& cache,
                            JSValue v) {
  return JS_VALUE_GET_TAG(v) == JS_TAG_OBJECT &&
         cache.find(JS_VALUE_GET_PTR(v)) != cache.end();
}

/**
 * 为**函数**单独造一个不属于任何 `cache` 的包装。
 *
 * 给 promise 的 `then` 用。同一个 `Promise.prototype.then` 会被数组里每个
 * promise 取到，而 Kotlin 的 `wrapJSPromiseAsync` 拿到它、调用完就
 * `close()`。若走共享 cache，第一个 promise 一 `close`，后面命中 cache 的
 * promise 拿到的就是**已关闭**的包装 —— `jsCall` 读的是已析构的 `JSValue`，报
 * `TypeError: not a function`。实测（2026-09-20）：`[Promise.reject,
 * Promise.resolve, new Promise]` 重跑 3 次挂 2
 * 次；之所以偶发，是因为已析构内存里原本那份 `JSValue`
 * 还没被覆写时，看着仍像函数。
 *
 * 返回 `nullptr` 表示 `v` 不是函数（调用方按「没有可调用的 then」处理）。
 * 返回的包装持一票，由调用方（Kotlin）归还。
 */
static jobject jsToJavaOwnedFunction(JNIEnv* env, JSContext* ctx, JSValue v,
                                     JSRuntimeOpaque* opaque) {
  if (!JS_IsFunction(ctx, v)) return nullptr;
  jclass clazz = env->FindClass("soko/ekibun/quickjs/JSFunction");
  jmethodID init = env->GetMethodID(
      clazz, "<init>", "(JLsoko/ekibun/quickjs/QuickJS$Context;)V");
  return env->NewObject(clazz, init, (jlong) new JSValue(JS_DupValue(ctx, v)),
                        opaque->thiz);
}

/**
 * [jsToJava] 的对象 / 函数分支。单独拆出来的原因是上面那条标量快路径不该夹带
 * 任何 Java 上行调用，而只有这里才需要 `cache`。
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
  // 同一个 JS 对象在一次转换里只转一次：命中就复用。这也是循环引用
  // （`a['a'] = a`）能收敛的唯一原因。
  //
  // 与 flutter_qjs 的 `_jsToDart` 一致，`cache` 是**每次调用新建**的临时表（见
  // [jsToJava] 的形参默认值 +
  // [jsToJavaEntry]），只在**一次**转换遍历内有效。跨调用的对象身份由 Kotlin
  // 侧决定 —— 那里已经没有包装了，整图展开出来的 `Map`
  // 就是数据本身，不存在「同一个 JS 对象要给同一个包装」这回事。
  auto ptr = JS_VALUE_GET_PTR(obj);
  if (cache.find(ptr) != cache.end()) return cache[ptr];
  if (JS_IsFunction(ctx, obj)) {
    // 函数是全图里**唯一**保留的包装：它是可调用的活对象，展开成数据没有意义。
    // 这一票新引用由调用方（Kotlin 的 `JSFunction`）持有，`free()` 时归还。
    jclass clazz = env->FindClass("soko/ekibun/quickjs/JSFunction");
    jmethodID init = env->GetMethodID(
        clazz, "<init>", "(JLsoko/ekibun/quickjs/QuickJS$Context;)V");
    auto wrapper = env->NewObject(
        clazz, init, (jlong) new JSValue(JS_DupValue(ctx, obj)), opaque->thiz);
    cache[ptr] = wrapper;
    return wrapper;
  } else if (JS_IsError(ctx, obj)) {
    return jsToThrowable(env, ctx, obj);
  } else if (JS_IsPromise(ctx, obj)) {
    // promise 不展开成数据：它要等 `then` 回调，返回值本身是个 Deferred。这里只
    // 保证一次遍历内同一个 promise 给同一个 Deferred。
    jclass clazz = env->GetObjectClass(opaque->thiz);
    jmethodID wrap = env->GetMethodID(
        clazz, "wrapJSPromiseAsync",
        "(JLsoko/ekibun/quickjs/JSFunction;)Lkotlinx/coroutines/Deferred;");
    auto thenJs = JS_GetPropertyStr(ctx, obj, "then");
    // `then` 走**独占**的包装，不登记进共享 cache。同一个
    // `Promise.prototype.then` 会被数组里每个 promise 取到，而 Kotlin 的
    // `wrapJSPromiseAsync` 拿到它就当自己的、调用完立刻 `close()`；共用 cache
    // 的话第一个 promise 一 close，后面命中 cache 的 promise
    // 就拿到已关闭的包装。详见 [jsToJavaOwnedFunction]。
    auto thenJava = jsToJavaOwnedFunction(env, ctx, thenJs, opaque);
    JS_FreeValue(ctx, thenJs);
    auto ret = env->CallObjectMethod(opaque->thiz, wrap,
                                     (jlong) new JSValue(JS_DupValue(ctx, obj)),
                                     thenJava);
    // Kotlin 侧已经把它取用完了（`then` 只活在 `wrapJSPromiseAsync` 里），
    // local ref 可以当场收掉。
    if (thenJava != nullptr) env->DeleteLocalRef(thenJava);
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
      auto jval = jsToJava(env, ctx, jsprop, &cache);
      env->SetObjectArrayElement(list, i, jval);
      // 登记在 cache 里的 local ref **不能删**：`arr[0] = arr` 时它就是这个
      // list 自己。判定与理由见 [cachedRefExists]。
      if (!cachedRefExists(cache, jsprop)) env->DeleteLocalRef(jval);
      JS_FreeValue(ctx, jsprop);
    }
    return list;
  } else {
    // 普通对象：**整图展开**成 `java.util.LinkedHashMap`（对齐 flutter_qjs 的
    // `_jsToDart` 对象分支）。展开出来的是纯数据，与 JS 侧脱钩 —— 改它不会影响
    // JS，也就不需要包装 + 引用计数 + 常驻映射表那一整套。
    //
    // `cache[ptr] = map` 必须在**填之前**登记：递归回自身时命中
    // cache，拿到的正是那个还在填的 map，于是 `a['a'] === a`
    // 天然成立（上游同样是先 `cache[valptr] = ret` 再循环填）。
    jclass mapClazz = env->FindClass("java/util/LinkedHashMap");
    auto map =
        env->NewObject(mapClazz, env->GetMethodID(mapClazz, "<init>", "()V"));
    cache[ptr] = map;
    auto put = env->GetMethodID(
        mapClazz, "put",
        "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
    JSPropertyEnum* ptab;
    uint32_t plen;
    if (JS_GetOwnPropertyNames(ctx, &ptab, &plen, obj, -1) == 0) {
      for (uint32_t i = 0; i < plen; i++) {
        auto jsKey = JS_AtomToValue(ctx, ptab[i].atom);
        auto jsVal = JS_GetProperty(ctx, obj, ptab[i].atom);
        auto jKey = jsToJava(env, ctx, jsKey, &cache);
        auto jVal = jsToJava(env, ctx, jsVal, &cache);
        auto old = env->CallObjectMethod(map, put, jKey, jVal);
        if (old != nullptr) env->DeleteLocalRef(old);
        // 理由同数组那一支 —— `a['a'] = a` 时 `jVal` 就是 `map` 自己。
        if (!cachedRefExists(cache, jsKey)) env->DeleteLocalRef(jKey);
        if (!cachedRefExists(cache, jsVal)) env->DeleteLocalRef(jVal);
        JS_FreeValue(ctx, jsKey);
        JS_FreeValue(ctx, jsVal);
        JS_FreeAtom(ctx, ptab[i].atom);
      }
      js_free(ctx, ptab);
    }
    return map;
  }
}

jobject jsToJavaEntry(JNIEnv* env, JSContext* ctx, JSValue obj) {
  return jsToJava(env, ctx, obj);
}

extern "C" JNIEXPORT jobject JNICALL Java_soko_ekibun_quickjs_QuickJS_jsToJava(
    JNIEnv* env, jclass, jlong ctx, jlong obj) {
  auto ret = jsToJavaEntry(env, (JSContext*)ctx, *(JSValue*)obj);
  // jsToJava 会吃掉给它的那一票（JS_GetProperty / JS_Call 的返回值是**交出来**
  // 而不是借出），所以在这里放掉。函数包装若被造了出来，它持的是自己新 dup 的
  // 一票，不受影响 —— 生命周期之后归调用方显式管理。
  jsReleaseValue(ctx, obj);
  // 这个句柄也是本次调用的一次性产物：Kotlin 拿到转换结果之后不会再碰它
  // （包装的 ptr 是 jsToJavaObject 里另造的那一份）。只放引用不销毁包装，
  // 每转换一次就漏 16 字节 —— 「两步」里被漏掉的那一步。
  jsDestroyHandle(obj);
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