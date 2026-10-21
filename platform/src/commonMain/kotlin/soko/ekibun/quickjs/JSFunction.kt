package soko.ekibun.quickjs

/** 一个 JS 函数的包装；[invoke] 只在函数上有意义，所以不放在 [JSRef]。 */
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

  /** [invoke] 的挂起版：投递到 JS 线程执行，不阻塞调用方。 */
  suspend fun invokeAsync(
    vararg argv: Any?,
    thisVal: Any? = null,
  ): Any? =
    withPtr {
      ctx.toJava(ctx.jsCallImpl(this, *argv, thisVal = thisVal))
    }
}
