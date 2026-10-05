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
  // ArrayBuffer 的 class id：quickjs.h 不导出 class 枚举（长在 quickjs.c
  // 内部）， initContext 拿一个真 ArrayBuffer 问一次 JS_GetClassID
  // 自标定，submodule 升级也不会漂。
  JSClassID arrayBufferClassId;
  // `java/lang/AutoCloseable` 的全局引用（initContext 建、destroyContext 删）。
  // 挂在 JavaObject 上的对象若是它的实现者，类析构回调要替 JS 侧调一次 close()
  // —— JS 那边没有 finally，引用丢掉就是最后的机会，漏了只能等 runtime 销毁。
  // 拿不到（类找不到 / OOM）就退化成只放引用，行为与从前一致。
  jclass autoCloseableClass;
  // 0 表示不限制：memoryLimit 为 0 就不调 JS_SetMemoryLimit。
  int64_t memoryLimit;
  int64_t timeoutMs;
  std::chrono::steady_clock::time_point evalStart;
  bool interrupted;
};

// 「放引用」与「销毁包装」是两件**分开**的事，故意不合成一件：
//
//   jsReleaseValue  -> 放掉一票 JS 引用（JS_FreeValue）
//   jsDestroyHandle -> 释放堆上的包装（delete）
//
// 合成一个的后果：`definePropertyValue` 的 k / v 各只要其一（k 只放引用、v
// 只销毁 包装），合并版会**悄悄偷走**别人的一票 —— 释放包装会顺带把 JS
// 引用计数减一。 拆开还有第二个好处：同一个句柄可以在多次 JS
// 操作之间复用（每次配一次 jsDupValue），而包装仍然只销毁一次（坑的取证见
// ownership 手册规则 1）。
//
// 分层：这两个 helper 只在 C 内部按需取用（`definePropertyValue` 的 k / v）；
// Kotlin 侧的释放入口永远是「两件都要」，走下面把两步并成**一次 JNI 过界**的
// jsFreeValue 导出，细粒度操作不暴露给 Kotlin。
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
  // AutoCloseable 的类引用要在 JS_NewClass **之前**备好 ——
  // 类析构回调随时可能跑，那时再取就晚了。
  {
    jclass cls = env->FindClass("java/lang/AutoCloseable");
    if (cls != nullptr) {
      opaque->autoCloseableClass = (jclass)env->NewGlobalRef(cls);
      env->DeleteLocalRef(cls);
    }
    // 这里**只 Clear 不 Describe**（与下面 finalizer 那处相反）：
    // 这条路走不通是「类找不到」的正常降级，Describe 只会往 stderr
    // 打一条 NoClassDefFoundError 噪声，而那不是错误 ——
    // 退化后行为与从前一致（只放引用，不 close）。
    if (env->ExceptionCheck()) env->ExceptionClear();
  }
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
        // 类析构回调：JS 侧把这个包装丢掉时跑。
        //
        // 两件事，按顺序：
        // 1. 若原对象实现 `AutoCloseable` ⇒ 调一次 `close()`；
        // 2. 放掉 Java 全局引用。
        //
        // 顺序不能反：先放引用就再也拿不到 jobject 了，close 也就无从调起。
        //
        // 为什么 close：JS 那边没有 try-finally，插件把引用一丢，
        // 宿主侧就再没有「用完了」的信号了。
        // 而这些对象（Http.Response 之类）都**欠着一条连接或一个 native
        // 句柄**，不在这儿调，就只能等 `JS_FreeRuntime` 一次性兜底，
        // 中间那条连接一直悬着。
        //
        // 这套「GetMethodID(GetObjectClass(x), "close", "()V") + CallVoidMethod
        // + ExceptionDescribe/Clear」的形状与 `ffmpeg.cpp` 的 `closeIoContext`
        // 一致，是有意保持同形的（各自要在自己那侧的线程上跑，
        // 抄不出公共函数）—— 改动其中一份时记得看另一份。
        [](JSRuntime* rt, JSValue obj) noexcept {
          auto opaque = (JSRuntimeOpaque*)JS_GetRuntimeOpaque(rt);
          if (opaque == nullptr) return;
          JNIEnv* env;
          opaque->javaVm->GetEnv((void**)&env, JNI_VERSION_1_4);
          auto ref = (jobject)JS_GetOpaque(obj, opaque->javaClassID);
          if (ref == nullptr) return;
          if (opaque->autoCloseableClass != nullptr &&
              env->IsInstanceOf(ref, opaque->autoCloseableClass)) {
            // `GetMethodID` 问的是**具体类**，但签名 `()V` 来自接口
            // `AutoCloseable.close()` —— 它是抽象方法（不是 default 方法），
            // 所以每个实现类都**一定**有这个 public 方法，查得到。
            // `GetObjectClass` 返回的是 local ref，取完方法 id 就得删 ——
            // GC 时机上没别的机会释放它。
            jclass cls = env->GetObjectClass(ref);
            jmethodID close = cls == nullptr
                                  ? nullptr
                                  : env->GetMethodID(cls, "close", "()V");
            if (close != nullptr) {
              env->CallVoidMethod(ref, close);
              // 析构回调绝不能往外抛（它跑在 GC 时机上，没有调用者能接）——
              // 先 Describe 打到 stderr 再 Clear：静默清掉就丢了诊断线索。
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
      // 类没注册成：这个 runtime 起不来，opaque 连它的 weak global ref
      // 一起收掉，别把失败路径漏成一份常驻内存。
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
    // context 建不成（基本只有 OOM）：runtime 同样不能留 —— 与上面
    // JS_NewClass 失败是同一条清理链，别让 gc_obj_list 断言留到进程退出。
    JS_SetRuntimeOpaque(rt, nullptr);
    JS_FreeRuntime(rt);
    if (opaque->autoCloseableClass != nullptr)
      env->DeleteGlobalRef(opaque->autoCloseableClass);
    env->DeleteWeakGlobalRef(opaque->thiz);
    delete opaque;
    return 0;
  }
  // ArrayBuffer class id 的自标定（见 struct 里字段注释）。
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
  // 顺序有讲究：JS_FreeContext / JS_FreeRuntime 期间会跑 JavaObject
  // 的类析构回调，它得读到 opaque（javaVm + javaClassID）才能把挂着的 Java
  // 全局引用删掉。 以前这里先把 opaque 置成 nullptr，那些 global ref
  // 就被析构回调里那句 `if (opaque == nullptr) return;` 直接放过了 —— 漏的是
  // jobject 全局引用表。
  JS_FreeContext((JSContext*)ctx);
  JS_FreeRuntime(rt);
  if (opaque != nullptr) {
    // thiz 是 initContext 里 NewWeakGlobalRef 建的，得自己删；opaque 本体同理。
    // autoCloseableClass 同样要在**析构回调跑过之后**才删 —— 上面两次 Free
    // 里它还要用来判 `IsInstanceOf`。
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
                                                     jlong v) {
  // 属性旗标钉死在 native 侧、不再收 Kotlin 入参：Kotlin 侧原先有个 `JSProp`
  // 常量对象专门传它，而五个调用点要的全是同一个「可配置 + 可写 +
  // 可枚举」。真要别的旗标（比如 `JS_PROP_THROW`）再从参数位上把它加回来。
  auto atom = JS_ValueToAtom((JSContext*)ctx, *(JSValue*)k);
  auto ret = JS_DefinePropertyValue((JSContext*)ctx, *(JSValue*)obj, atom,
                                    *(JSValue*)v, JS_PROP_C_W_E);
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
extern "C" JNIEXPORT void JNICALL Java_soko_ekibun_quickjs_QuickJS_jsFreeValue(
    JNIEnv*, jclass, jlong ctx, jlong obj) {
  // 放引用 + 销毁包装一次过界 —— Kotlin 侧没有「只要其一」的调用点，细粒度
  // helper 不出 C（见文件头那段的分层说明）。
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

// ===== JS → Java 的透传原语 =====
//
// 类型分派、递归、防环 cache、释放策略全在 Kotlin 侧（`QuickJS.jsToJava`）——
// 分层对齐 flutter_qjs：native 只做 QuickJS C API 的一对一薄包装，不做任何转换
// 判断；tag / 属性 / 标量怎么读、句柄什么时候放，都由 Kotlin 决定。

// jsGetTag 返回的稳定 tag 码由 Kotlin 侧的 JS_TAG_* 常量解释（QuickJS.kt）：把
// 反向映射内联在 return 里，QuickJS 的原始 tag —— 包括随构建配置变形的
// JS_TAG_IS_FLOAT64 —— 不出 native，Kotlin 不镜像 ABI。五个名字之外的 tag
// （NULL / UNDEFINED / EXCEPTION / symbol / bigint）一律返回 0，Kotlin 侧不
// 命名它，由分派器的 else 接住转 null。
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
  return JS_IsPromise((JSContext*)ctx, *(JSValue*)obj);
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
    // 失败（OOM，或对象的 toString 抛错）会留下待处理异常；失败信号已由 null
    // 传达，把异常清掉，别污染后续调用（与 jsGetArrayBuffer 的探针同款卫生）。
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
  // 先按 class id 过滤（initContext 自标定）：JS_GetArrayBuffer 对非
  // ArrayBuffer 会往 runtime 里抛
  // TypeError，逐对象探针等于每节点白分配一个错误对象再吞掉 ——
  // 在同一个原语里问完再取，Kotlin 侧一次过界拿结果。
  auto opaque =
      (JSRuntimeOpaque*)JS_GetRuntimeOpaque(JS_GetRuntime((JSContext*)ctx));
  if (JS_GetClassID(*(JSValue*)obj) != opaque->arrayBufferClassId)
    return nullptr;
  size_t size;
  uint8_t* buf = JS_GetArrayBuffer((JSContext*)ctx, &size, *(JSValue*)obj);
  if (!buf) {
    // 有 class id 把门理论上不该失败；万一失败（比如 detached buffer），抛出的
    // TypeError 必须就地清掉，否则后面每个「返回 NULL 却不抛异常」的 API 都会被
    // 这个旧异常污染。放掉它。
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
  // 取的是 `jsWrapObject` 挂在 JavaObject 类上的 global ref；经 JNI 返回时
  // JVM 会另建一个 local ref 指向同一对象，global ref 本身的所有权不变。
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
  // (key, value) 交错铺进一个 LongArray：key 由 atom
  // 就地物化（JS_AtomToValue）， value 按同一个 atom 取（JS_GetProperty），atom
  // 随取随放 —— 它是 C 侧的 计数资源，不过 JNI
  // 界。整组打包成一次过界，省掉逐属性 4 次（atom 取值、 按 atom 取
  // value、FreeAtom，加 Kotlin 侧的配对开销）；四支句柄的消费都在 Kotlin 侧的
  // jsToJava。
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
        // 实参与 this 不在 C 里转换：dup 成句柄递给 Kotlin，转换连同句柄的消费
        // 都在 Kotlin 侧的 jsToJava 里做。递的是 jlong 不是 jobject，没有任何
        // local ref 要簿记；argv 是 JSValueConst（借用），dup 出的票由 Kotlin
        // 还。
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
