package soko.ekibun.quickjs

interface JSInvokable {
  operator fun invoke(
    vararg argv: Any?,
    thisVal: Any? = null,
  ): Any?

  companion object {
    /**
     * 用一个以 `thisVal` 为接收者的 lambda 造桥，省掉 `object : JSInvokable` 的样板。
     *
     * 接收者**可空**是刻意的：JS 里裸调 `f()` 时 `this === undefined`，经 `jsToJavaScalar`
     * 的 `default: return nullptr` 落到 Kotlin 就是 `null`，属正常语义。写成可空后，
     * `this.ptr` 之类会**编译不过**（`reference has a nullable type`），比留到运行期炸好。
     */
    operator fun invoke(block: Any?.(Array<Any?>) -> Any?): JSInvokable {
      return object : JSInvokable {
        override fun invoke(
          vararg argv: Any?,
          thisVal: Any?,
        ): Any? {
          // `block` 的唯一入参是数组，`argv` 整体作为它的单个元素送达。
          // ⚠️ 这里不能写 `thisVal?.block(...)`：可空接收者的函数类型走 invoke 协议、
          // 编译器不插空检查，加 `?.` 反而让 block 整个不执行。
          return thisVal.block(arrayOf(argv))
        }
      }
    }
  }
}
