package soko.ekibun.quickjs

interface JSInvokable {
  operator fun invoke(
    vararg argv: Any?,
    thisVal: Any? = null,
  ): Any?

  companion object {
    /** 用以 `thisVal` 为接收者的 lambda 造桥。block 收 `Array<out Any?>`，接收者可空。 */
    operator fun invoke(block: Any?.(Array<out Any?>) -> Any?): JSInvokable {
      return object : JSInvokable {
        override fun invoke(
          vararg argv: Any?,
          thisVal: Any?,
        ): Any? {
          // 直接调，不加 ?.。
          return thisVal.block(argv)
        }
      }
    }
  }
}
