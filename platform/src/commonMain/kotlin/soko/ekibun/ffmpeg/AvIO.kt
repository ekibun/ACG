package soko.ekibun.ffmpeg

interface AvIO {
  companion object {
    // POSIX lseek 的 whence 取值，各平台一致（Linux/macOS 0/1/2，Windows CRT 也是 0/1/2）。
    // 两个实现（HttpIO / FileIO）共用这一份，别各抄一遍 —— 抄漏一个分支就是静默走错位置。
    const val SEEK_SET = 0
    const val SEEK_CUR = 1
    const val SEEK_END = 2
  }

  interface Handler {
    /**
     * 打开一个源。「打不开」**不许抛异常**：本函数跑在 native 的 `io_open` 回调里，Kotlin
     * 异常会 pending 在 JNI 上没人接，下一次 JNI 调用直接 abort 整个 JVM —— 返回一个
     * read/seek 恒失败的 [AvIO]（`FileIO` 对不存在的路径正是这个做法），让
     * `avformat_open_input` 以 I/O 错误干净收场。
     */
    fun open(url: String): AvIO

    /**
     * 叫醒「正在等数据的读」——**一次性事件**，投一次就够。
     *
     * 为什么需要从外面插一手：`read` 在数据没到时是在 IO 层**真的等待**，而那次调用卡在 native
     * 的 `av_read_frame` 里 —— **协程取消对它无效**（阻塞的 JNI 调用里没有挂起点）。于是
     * 「这一轮已经不要了」（[AvFormat.resetChannel] 作废读轮时触发）只能由这里从**另一条线程**叫醒。
     *
     * **没有配套的清旗标动作**：事件挂在**正在等的那一次读**上，读返回即摘掉投递口 ⇒「没读在等时
     * abort」是 no-op、「下一次读」是全新信号。它**不掀会话**：连接的去留由等待的读自己决定。
     * 默认空实现：不阻塞的 IO（[FileIO]）没什么可叫醒的。推导见 `playback-invariants.md` 第五节（私有作废信号）。
     */
    fun abort() {}
  }

  /**
   * 往 [buf] 的前 [len] 个字节里读（最多 [len] 个），返回实际读到的字节数
   * （`AVERROR_EOF` / 其余负数 = 错误）。
   *
   * **必须按 [len] 截断，不能按 `buf.size` 填满**：native 侧每个 pb 只持有一个**复用**数组，
   * [len] ≤ `buf.size`，但通常远小于它（`fill_buffer` 要的是「缓冲剩余容量」，MP4 源能被放大
   * 到 MB 级；数组只增不减）。按 `buf.size` 填满会报出比请求量还多的字节 —— native 只能把
   * 返回值 clamp 回去、并打一条 `AvIO read over-ran`，等于每次读都变成一条错误日志。
   */
  fun read(
    buf: ByteArray,
    len: Int,
  ): Int

  fun seek(
    offset: Int,
    whence: Int,
  ): Int

  fun close()
}
