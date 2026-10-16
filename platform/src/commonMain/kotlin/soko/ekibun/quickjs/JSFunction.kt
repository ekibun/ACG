package soko.ekibun.quickjs

/**
 * 一个 JS 函数的包装。
 *
 * 函数是**唯一**还以包装形式存在的 JS 对象 —— 普通对象由 `jsToJava` 整图展开成
 * `Map`（对齐 flutter_qjs 的 `_jsToDart`），因为函数是可调用的活对象，展开没有意义。
 *
 * [invoke] 的实现就在这里：调用只对函数有意义，所以它不属于 [JSRef]。
 */
class JSFunction(
  ptr: Long,
  ctx: QuickJS,
) : JSRef(ptr, ctx),
  JSInvokable {
  override fun invoke(
    vararg argv: Any?,
    thisVal: Any?,
  ): Any? =
    withPtrSync {
      ctx.toJava(ctx.jsCallImpl(this, *argv, thisVal = thisVal))
    }

  /**
   * [invoke] 的挂起版：把调用**投递**到 JS 线程，而不是在调用方线程上阻塞等。
   *
   * 从协程侧发起调用时用这一个 —— [invoke] 的 [withPtrSync] 会占住调用方线程一直等到
   * JS 线程空出来，而协程侧本来就有地方挂起。JNI 回调链（没有协程上下文）仍然只能走
   * [invoke]。
   */
  suspend fun invokeAsync(
    vararg argv: Any?,
    thisVal: Any? = null,
  ): Any? =
    withPtr {
      ctx.toJava(ctx.jsCallImpl(this, *argv, thisVal = thisVal))
    }
}
