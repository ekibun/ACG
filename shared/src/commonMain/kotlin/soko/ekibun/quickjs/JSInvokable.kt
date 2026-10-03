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
     * block 的入参类型是 `Array<out Any?>` 而不是 `Array<Any?>`：`invoke` 的
     * `vararg argv` 拿到的是 `Array<out Any?>`，Kotlin 数组不变，直接当实参传**编译不过**。
     * 用投影类型才能把 `argv` **原样**递给 block —— 桥看到的就是宿主侧那份实参，
     * 与自己实现 [invoke] 时看到的是同一个数组（`JsEngine` 靠这一点按下标取 `_binding` 的实参）。
     *
     * 接收者**可空**是刻意的：JS 里裸调 `f()` 时 `this === undefined`，经 `jsToJavaScalar`
     * 的 `default: return nullptr` 落到 Kotlin 就是 `null`，属正常语义。写成可空后，
     * `this.ptr` 之类会**编译不过**（`reference has a nullable type`），比留到运行期炸好。
     */
    operator fun invoke(block: Any?.(Array<out Any?>) -> Any?): JSInvokable {
      return object : JSInvokable {
        override fun invoke(
          vararg argv: Any?,
          thisVal: Any?,
        ): Any? {
          // 这里不能写 `thisVal?.block(argv)`：可空接收者的函数类型走 invoke 协议、
          // 编译器不插空检查，加 `?.` 反而让 block 整个不执行。
          return thisVal.block(argv)
        }
      }
    }
  }
}
