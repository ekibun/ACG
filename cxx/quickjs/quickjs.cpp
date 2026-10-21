#include "quickjs/quickjs.h"

#include <jni.h>

#include <chrono>
#include <cstring>

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
  // ArrayBuffer 的 class id，initContext 用一个真 ArrayBuffer 自标定。
  JSClassID arrayBufferClassId;
  // `java/lang/AutoCloseable` 的全局引用，initContext 建、destroyContext 删。
  jclass autoCloseableClass;
  // 0 表示不限制：memoryLimit 为 0 就不调 JS_SetMemoryLimit。
  int64_t memoryLimit;
  int64_t timeoutMs;
  std::chrono::steady_clock::time_point evalStart;
  bool interrupted;
};

// 「放引用」与「销毁包装」分为两件事：
//
//   jsReleaseValue  -> 放掉一票 JS 引用（JS_FreeValue）
//   jsDestroyHandle -> 释放堆上的包装（delete）
//
// 两个 helper 只在 C 内部按需取用；Kotlin 侧的释放入口是走一次 JNI 过界的
// jsFreeValue。
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
  auto opaque =
      new JSRuntimeOpaque{javaVm,     env->NewWeakGlobalRef(ctx),
                          0,          0,
                          nullptr,    memory_limit,
                          timeout_ms, std::chrono::steady_clock::now(),
                          false};
  // 在 JS_NewClass 之前取好 AutoCloseable 的类引用。
  {
    jclass cls = env->FindClass("java/lang/AutoCloseable");
    if (cls != nullptr) {
      opaque->autoCloseableClass = (jclass)env->NewGlobalRef(cls);
      env->DeleteLocalRef(cls);
    }
    // 取不到类引用时只清异常，按无 AutoCloseable 降级。
    if (env->ExceptionCheck()) env->ExceptionClear();
  }
  // 设置 JS 栈预算，默认 256KB。
  JS_SetMaxStackSize(rt, stack_size > 0 ? stack_size : 256 * 1024);
  if (memory_limit > 0) JS_SetMemoryLimit(rt, (size_t)memory_limit);
  // 中断处理器：求值超过 timeoutMs 时让 JS 以 interrupted 收场。
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
          // loader 返回 NULL 时必须挂一个待处理异常。
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
        // 放掉模块句柄的 JSValue。
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
        // 类析构回调。
        //
        // 对实现 AutoCloseable 的对象调一次 close()，再放掉 Java 全局引用。
        [](JSRuntime* rt, JSValue obj) noexcept {
          auto opaque = (JSRuntimeOpaque*)JS_GetRuntimeOpaque(rt);
          if (opaque == nullptr) return;
          JNIEnv* env;
          opaque->javaVm->GetEnv((void**)&env, JNI_VERSION_1_4);
          auto ref = (jobject)JS_GetOpaque(obj, opaque->javaClassID);
          if (ref == nullptr) return;
          if (opaque->autoCloseableClass != nullptr &&
              env->IsInstanceOf(ref, opaque->autoCloseableClass)) {
            // 取具体类的 close 方法；cls 是 local ref，取完即删。
            jclass cls = env->GetObjectClass(ref);
            jmethodID close = cls == nullptr
                                  ? nullptr
                                  : env->GetMethodID(cls, "close", "()V");
            if (close != nullptr) {
              env->CallVoidMethod(ref, close);
              // 记录并清除 close() 抛出的异常。
              if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
              }
            }
            if (cls != nullptr) env->DeleteLocalRef(cls);
          }
          env->DeleteGlobalRef(ref);
        }};
    int e = JS_NewClass(rt, opaque->javaClassID, &def);
    if (e < 0) {
      // 类注册失败：回收 runtime、opaque 与全局引用。
      JS_SetRuntimeOpaque(rt, nullptr);
      JS_FreeRuntime(rt);
      if (opaque->autoCloseableClass != nullptr)
        env->DeleteGlobalRef(opaque->autoCloseableClass);
      env->DeleteWeakGlobalRef(opaque->thiz);
      delete opaque;
      return 0;
    }
  }
  JSContext* jsc = JS_NewContext(rt);
  if (!jsc) {
    // context 创建失败：回收 runtime、opaque 与全局引用。
    JS_SetRuntimeOpaque(rt, nullptr);
    JS_FreeRuntime(rt);
    if (opaque->autoCloseableClass != nullptr)
      env->DeleteGlobalRef(opaque->autoCloseableClass);
    env->DeleteWeakGlobalRef(opaque->thiz);
    delete opaque;
    return 0;
  }
  // 自标定 ArrayBuffer 的 class id。
  auto abProbe = JS_NewArrayBufferCopy(jsc, (const uint8_t*)"", 0);
  opaque->arrayBufferClassId = JS_GetClassID(abProbe);
  JS_FreeValue(jsc, abProbe);
  return (jlong)jsc;
}
extern "C" JNIEXPORT void JNICALL
Java_soko_ekibun_quickjs_QuickJS_destroyContext(JNIEnv* env, jclass,
                                                jlong ctx) {
  JSRuntime* rt = JS_GetRuntime((JSContext*)ctx);
  auto opaque = (JSRuntimeOpaque*)JS_GetRuntimeOpaque(rt);
  // 先释放 context 与 runtime，再删 opaque：析构回调期间还要读 opaque。
  JS_FreeContext((JSContext*)ctx);
  JS_FreeRuntime(rt);
  if (opaque != nullptr) {
    // 释放 autoCloseableClass、thiz 的 weak global ref 与 opaque 本体。
    if (opaque->autoCloseableClass != nullptr)
      env->DeleteGlobalRef(opaque->autoCloseableClass);
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
  // 读完字节后放掉 Java 数组（JNI_ABORT，不回写）。
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
                                                     jlong v) {
  // 属性旗标固定为可配置 + 可写 + 可枚举。
  auto atom = JS_ValueToAtom((JSContext*)ctx, *(JSValue*)k);
  auto ret = JS_DefinePropertyValue((JSContext*)ctx, *(JSValue*)obj, atom,
                                    *(JSValue*)v, JS_PROP_C_W_E);
  JS_FreeAtom((JSContext*)ctx, atom);
  // 释放 k / v 两个句柄：v 的引用已由 JS_DefinePropertyValue 消费，只销毁包装；
  // k 的引用由这里释放。
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
extern "C" JNIEXPORT void JNICALL Java_soko_ekibun_quickjs_QuickJS_jsFreeValue(
    JNIEnv*, jclass, jlong ctx, jlong obj) {
  // 放引用与销毁包装一次过界。
  jsReleaseValue(ctx, obj);
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
  // JS_Eval 不接管源码，用完两个字符串都要放掉。
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

// ===== JS → Java 的透传原语 =====
//
// 类型分派、递归、防环 cache 与释放策略都在 Kotlin 侧（`QuickJS.jsToJava`）；
// native 只做 QuickJS C API 的一对一薄包装。

// jsGetTag 返回稳定 tag 码，由 Kotlin 侧的 JS_TAG_* 常量解释；五个名字之外的
// tag 一律返回 0。
extern "C" JNIEXPORT jint JNICALL
Java_soko_ekibun_quickjs_QuickJS_jsGetTag(JNIEnv*, jclass, jlong obj) {
  int tag = JS_VALUE_GET_TAG(*(JSValue*)obj);
  if (tag == JS_TAG_OBJECT) return 5;    // JS_TAG_OBJECT
  if (tag == JS_TAG_STRING) return 4;    // JS_TAG_STRING
  if (tag == JS_TAG_BOOL) return 1;      // JS_TAG_BOOL
  if (tag == JS_TAG_INT) return 2;       // JS_TAG_INT
  if (JS_TAG_IS_FLOAT64(tag)) return 3;  // JS_TAG_FLOAT64
  return 0;
}

extern "C" JNIEXPORT jlong JNICALL
Java_soko_ekibun_quickjs_QuickJS_jsValueGetPtr(JNIEnv*, jclass, jlong obj) {
  return (jlong)JS_VALUE_GET_PTR(*(JSValue*)obj);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_soko_ekibun_quickjs_QuickJS_jsIsFunction(JNIEnv*, jclass, jlong ctx,
                                              jlong obj) {
  return JS_IsFunction((JSContext*)ctx, *(JSValue*)obj);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_soko_ekibun_quickjs_QuickJS_jsIsError(JNIEnv*, jclass, jlong ctx,
                                           jlong obj) {
  return JS_IsError((JSContext*)ctx, *(JSValue*)obj);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_soko_ekibun_quickjs_QuickJS_jsIsPromise(JNIEnv*, jclass, jlong ctx,
                                             jlong obj) {
  // 上游没有 JS_IsPromise，用 JS_PromiseState 判断（非 Promise 返回 -1）。
  int state = JS_PromiseState((JSContext*)ctx, *(JSValue*)obj);
  return state != -1;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_soko_ekibun_quickjs_QuickJS_jsIsArray(JNIEnv*, jclass, jlong ctx,
                                           jlong obj) {
  return JS_IsArray((JSContext*)ctx, *(JSValue*)obj);
}

extern "C" JNIEXPORT jboolean JNICALL Java_soko_ekibun_quickjs_QuickJS_jsToBool(
    JNIEnv*, jclass, jlong ctx, jlong obj) {
  return JS_ToBool((JSContext*)ctx, *(JSValue*)obj);
}

extern "C" JNIEXPORT jlong JNICALL Java_soko_ekibun_quickjs_QuickJS_jsToInt64(
    JNIEnv*, jclass, jlong ctx, jlong obj) {
  int64_t p;
  JS_ToInt64((JSContext*)ctx, &p, *(JSValue*)obj);
  return p;
}

extern "C" JNIEXPORT jdouble JNICALL
Java_soko_ekibun_quickjs_QuickJS_jsToFloat64(JNIEnv*, jclass, jlong ctx,
                                             jlong obj) {
  double p;
  JS_ToFloat64((JSContext*)ctx, &p, *(JSValue*)obj);
  return p;
}

extern "C" JNIEXPORT jstring JNICALL
Java_soko_ekibun_quickjs_QuickJS_jsToCString(JNIEnv* env, jclass, jlong ctx,
                                             jlong obj) {
  auto pstr = JS_ToCString((JSContext*)ctx, *(JSValue*)obj);
  if (pstr == nullptr) {
    // 失败时清掉待处理异常并返回 null。
    JS_FreeValue((JSContext*)ctx, JS_GetException((JSContext*)ctx));
    return nullptr;
  }
  auto ret = env->NewStringUTF(pstr);
  JS_FreeCString((JSContext*)ctx, pstr);
  return ret;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_soko_ekibun_quickjs_QuickJS_jsGetArrayBuffer(JNIEnv* env, jclass,
                                                  jlong ctx, jlong obj) {
  // 先按 class id 过滤，再取 ArrayBuffer 内容。
  auto opaque =
      (JSRuntimeOpaque*)JS_GetRuntimeOpaque(JS_GetRuntime((JSContext*)ctx));
  if (JS_GetClassID(*(JSValue*)obj) != opaque->arrayBufferClassId)
    return nullptr;
  size_t size;
  uint8_t* buf = JS_GetArrayBuffer((JSContext*)ctx, &size, *(JSValue*)obj);
  if (!buf) {
    // 取失败时清掉抛出的异常并返回 null。
    JS_FreeValue((JSContext*)ctx, JS_GetException((JSContext*)ctx));
    return nullptr;
  }
  jbyteArray arr = env->NewByteArray((jsize)size);
  env->SetByteArrayRegion(arr, 0, (jsize)size, (int8_t*)buf);
  return arr;
}

extern "C" JNIEXPORT jobject JNICALL
Java_soko_ekibun_quickjs_QuickJS_jsGetOpaque(JNIEnv*, jclass, jlong ctx,
                                             jlong obj) {
  auto opaque =
      (JSRuntimeOpaque*)JS_GetRuntimeOpaque(JS_GetRuntime((JSContext*)ctx));
  // 取 JavaObject 上挂的 Java 全局引用。
  return (jobject)JS_GetOpaque(*(JSValue*)obj, opaque->javaClassID);
}

extern "C" JNIEXPORT jlong JNICALL
Java_soko_ekibun_quickjs_QuickJS_jsGetPropertyStr(JNIEnv* env, jclass,
                                                  jlong ctx, jlong obj,
                                                  jstring name) {
  auto pstr = env->GetStringUTFChars(name, nullptr);
  auto ret = (jlong) new JSValue(
      JS_GetPropertyStr((JSContext*)ctx, *(JSValue*)obj, pstr));
  env->ReleaseStringUTFChars(name, pstr);
  return ret;
}

extern "C" JNIEXPORT jlong JNICALL
Java_soko_ekibun_quickjs_QuickJS_jsGetPropertyUint32(JNIEnv*, jclass, jlong ctx,
                                                     jlong obj, jint index) {
  return (jlong) new JSValue(
      JS_GetPropertyUint32((JSContext*)ctx, *(JSValue*)obj, (uint32_t)index));
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_soko_ekibun_quickjs_QuickJS_jsGetOwnProperties(JNIEnv* env, jclass,
                                                    jlong ctx, jlong obj) {
  JSPropertyEnum* ptab;
  uint32_t plen;
  auto cctx = (JSContext*)ctx;
  // 旗标 -1 = 全部自有属性（含不可枚举与 symbol）。
  if (JS_GetOwnPropertyNames(cctx, &ptab, &plen, *(JSValue*)obj, -1) != 0)
    return nullptr;
  // (key, value) 交错铺进一个 LongArray，atom 随取随放。
  auto arr = env->NewLongArray((jsize)(plen * 2));
  auto handles = new jlong[plen * 2];
  for (uint32_t i = 0; i < plen; ++i) {
    handles[i * 2] = (jlong) new JSValue(JS_AtomToValue(cctx, ptab[i].atom));
    handles[i * 2 + 1] =
        (jlong) new JSValue(JS_GetProperty(cctx, *(JSValue*)obj, ptab[i].atom));
    JS_FreeAtom(cctx, ptab[i].atom);
  }
  env->SetLongArrayRegion(arr, 0, (jsize)(plen * 2), handles);
  delete[] handles;
  js_free(cctx, ptab);
  return arr;
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
                             "(Lsoko/ekibun/quickjs/JSInvokable;[JJ)J");
        // 实参与 this 各 dup 成句柄交给 Kotlin，转换与句柄释放都在 Kotlin 侧。
        auto argvHandles = env->NewLongArray(argc);
        auto handles = new jlong[argc];
        for (int i = 0; i < argc; ++i)
          handles[i] = (jlong) new JSValue(JS_DupValue(ctx, argv[i]));
        env->SetLongArrayRegion(argvHandles, 0, argc, handles);
        delete[] handles;
        auto thisHandle = (jlong) new JSValue(JS_DupValue(ctx, this_val));
        auto retPtr = (JSValue*)env->CallLongMethod(opaque->thiz, invoke, obj,
                                                    argvHandles, thisHandle);
        env->DeleteLocalRef(argvHandles);
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
  // JS_Throw 自己 dup 了一票，这里放掉调用方传入的 err 句柄。
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
  // argv 借用自调用方：用 js_malloc 临时铺一份，调完释放。
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
  // JS_NewPromiseCapability 返回两个新持有的引用，各建一个包装即可。
  auto resolving_funcs = new JSValue[2];
  auto promise = (jlong) new JSValue(
      JS_NewPromiseCapability((JSContext*)ctx, resolving_funcs));
  // resolve / reject 各搬进单独的分配，便于分别销毁。
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
