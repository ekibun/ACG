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
}
