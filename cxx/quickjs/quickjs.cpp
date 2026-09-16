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
  // 0 means "no limit"; mirrors qjs' --memory-limit / --stack-size handling.
  int64_t memoryLimit;
  int64_t timeoutMs;
  std::chrono::steady_clock::time_point evalStart;
  bool interrupted;
};

// Two *separate* operations, deliberately not fused:
//
//   jsReleaseValue  -> drop one JS reference  (JS_FreeValue)
//   jsDestroyHandle -> free the heap wrapper  (delete)
//
// They were previously fused into one jsFreeValue(). That fusion is what made
// `definePropertyValue` impossible to get right: freeing the wrapper also
// decremented the JS refcount, so any call site that only wanted to release
// its wrapper would silently steal a reference. Splitting them lets a handle be
// reused across several JS operations (each pairing with jsDupValue) while the
// wrapper is still destroyed exactly once.
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
  // Without an explicit limit a deep JS recursion walks off the *host* thread
  // stack and takes the whole process down (a JVM thread stack is only ~1MB).
  // The budget must stay well below the hosting thread stack: stack_limit is
  // computed as stack_top - stack_size, and stack_top already sits inside the
  // JNI frame. 256KB leaves room for the JNI/Java frames above it.
  JS_SetMaxStackSize(rt, stack_size > 0 ? stack_size : 256 * 1024);
  if (memory_limit > 0) JS_SetMemoryLimit(rt, (size_t)memory_limit);
  // Cooperative interrupt: JS_ExecutePendingJob / JS_Eval poll this callback,
  // which is what turns `while(true){}` into "InternalError: interrupted".
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
          // quickjs expects the loader to return NULL *with* a pending
          // exception (see js_host_resolve_imported_module). Returning NULL
          // bare makes JS_LoadModuleInternal report whatever stale exception
          // happens to be sitting in the runtime instead.
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
        /* the module is already referenced, so we must free it */
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
        // destructor
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
  // JS_NewArrayBufferCopy copies the bytes, so the Java array must be released
  // (JNI_ABORT = no copy back) before returning. Leaving it pinned leaks a
  // local ref / pinned buffer on every ArrayBuffer construction.
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
  // This function takes ownership of BOTH handles and fully retires them:
  //
  //   v -- JS_DefinePropertyValue's body ends with `JS_FreeValue(ctx, val)`, so
  //        the caller's reference is consumed by the callee (the property keeps
  //        its own, separate reference). Releasing v again here would be a
  //        double free -- that was the exact cause of the
  //        `js_rc(p)->ref_count > 0` assertion in gc_decref_child.
  //   k -- JS_ValueToAtom only borrows it, so this reference is ours to drop.
  //
  // Both wrappers are destroyed as well: every call site is a one-shot "define
  // this property" operation, and no caller reuses the k/v handle afterwards.
  // Fusing release+destroy here removes the wrapper leak that an unconsumed
  // `new JSValue` would otherwise leave on the refs ledger.
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
  // Restart the timeout window for this evaluation.
  auto opaque =
      (JSRuntimeOpaque*)JS_GetRuntimeOpaque(JS_GetRuntime((JSContext*)ctx));
  if (opaque != nullptr) {
    opaque->evalStart = std::chrono::steady_clock::now();
    opaque->interrupted = false;
  }
  // JS_Eval does not take ownership of the source: release both strings after
  // use, otherwise every evaluate() leaks a pinned copy of the script.
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
 * Scalar (non-object) conversion. Objects are delegated to [jsToJavaObject] by
 * the unified [jsToJava] dispatcher below, so this function never has to know
 * about them -- and never returns a silent NULL for one.
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
 * Single entry point for every JS → Java conversion.
 *
 * The object case is handled *here* rather than at each call site. That
 * matters: the recursive conversions (array elements, a promise's `then`)
 * previously called a scalar-only helper, so any object reached through them
 * silently became NULL. Keeping the dispatch in one place makes that
 * impossible.
 */
jobject jsToJava(JNIEnv* env, JSContext* ctx, JSValue obj,
                 std::unordered_map<void*, jobject> cache =
                     std::unordered_map<void*, jobject>()) {
  if (JS_VALUE_GET_TAG(obj) == JS_TAG_OBJECT)
    return jsToJavaObject(env, ctx, obj, cache);
  return jsToJavaScalar(env, ctx, obj);
}

/**
 * Object/function branch of [jsToJava]. Split out because it is the only part
 * that has to consult the persistent wrapper registry, and because that
 * registry pre-seeds `cache` -- the split keeps the scalar fast path above free
 * of any Java upcall.
 */
static jobject jsToJavaObject(JNIEnv* env, JSContext* ctx, JSValue obj,
                              std::unordered_map<void*, jobject>& cache) {
  {  // ArrayBuffer
    size_t size;
    uint8_t* buf = JS_GetArrayBuffer(ctx, &size, obj);
    if (!buf) {
      // This is only a type probe: JS_GetArrayBuffer already threw a
      // TypeError ("ArrayBuffer object expected") for any other object,
      // and leaving it pending poisons every later API that returns NULL
      // without throwing. Drop it.
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
  // A wrapper for this very object may already exist from an earlier, separate
  // conversion. It is registered on the Kotlin side (see
  // `Context.wrapperCache`) and has to take precedence over the per-call
  // `cache`, otherwise identity would only hold *within* one traversal.
  auto ptr = JS_VALUE_GET_PTR(obj);
  jclass ctxClazz = env->GetObjectClass(opaque->thiz);
  auto persistent = env->CallObjectMethod(
      opaque->thiz,
      env->GetMethodID(ctxClazz, "peekWrapper", "(J)Ljava/lang/Object;"),
      (jlong)ptr);
  if (persistent != nullptr) {
    // Reuse it: the persistent entry already owns the wrapper's original
    // reference, so take one more for the caller and remember the pair so the
    // caller's own frame keeps the ledger balanced.
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
    // Publish for later conversions. A fresh wrapper owns exactly one
    // reference, so it is registered here with the same handle the caller will
    // later retire.
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
    // NOT registered in the persistent wrapper cache: the promise's wrapper is
    // driven by `wrapJSPromiseAsync`, which needs a live `then` callback. A
    // cached entry would be replayed *after* that callback (and its `then`
    // reference) were already retired, exactly like the reused Deferred in
    // flutter_qjs' deferred test. Same-value-every-time is preserved inside a
    // single traversal by `cache` below, where the object identity is still
    // valid.
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
      // Convert exactly once: the recursive call registers the result in
      // `cache`, so a second call for the same element would hand back a local
      // ref that has already been deleted below.
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
  // jsToJava consumes the reference it is given (JS_GetProperty / JS_Call
  // results are handed over, not borrowed), so drop it here. The wrapper itself
  // survives
  // -- callers may still hold it, and its lifetime is now managed explicitly.
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
  // JS_Throw took its own reference via JS_DupValue, so drop ours; the handle
  // itself is a one-shot from javaToJs and is retired with it.
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
  // JS_Call only borrows argv; the JSValue structs themselves stay owned by
  // the caller (Kotlin frees every argument right after the call returns).
  // Use stack storage to avoid leaking a heap array on every call.
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
  // JS_NewPromiseCapability writes two freshly owned references into the array,
  // so each gets its own wrapper and no dup is needed.
  auto resolving_funcs = new JSValue[2];
  auto promise = (jlong) new JSValue(
      JS_NewPromiseCapability((JSContext*)ctx, resolving_funcs));
  // Transfer the two resolving functions into their own single-value allocation
  // so each wrapper can be destroyed independently by jsDestroyHandle().
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
  // `k` is a one-shot key handle produced by the caller purely for this lookup:
  // JS_ValueToAtom only borrows it, so we drop its reference and retire the
  // wrapper. Returning `ret` hands the caller a *new* owned reference.
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