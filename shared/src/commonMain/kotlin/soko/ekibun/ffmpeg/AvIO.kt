package soko.ekibun.ffmpeg

interface AvIO {
  companion object {
    // POSIX lseek 的 whence 取值，各平台一致（Linux/macOS 0/1/2，Windows CRT 也是 0/1/2）。
    // 两个实现（HttpIO / FileIO）共用这一份，别各抄一遍 —— 抄漏一个分支就是静默走错位置。
    const val SEEK_SET = 0
    const val SEEK_CUR = 1
    const val SEEK_END = 2
  }

  fun getBufferSize(): Long

  interface Handler {
    fun open(url: String): AvIO

    /**
     * 叫醒「正在等数据的读」——**一次性事件**，投一次就够，不需要谁来撤销。
     *
     * 为什么需要从外面插一手：`read` 在数据没到时是在 IO 层**真的等待**（aviobuf 没有「稍后重试」的语义），
     * 而那次调用卡在 native 的 `av_read_frame` 里面 —— **协程取消对它无效**（取消是协作式的，阻塞的 JNI
     * 调用里没有挂起点）。于是「这一轮已经不要了」（[AvFormat.resetChannel] 作废读轮时触发）只能由这里
     * 从**另一条线程**叫醒它。
     *
     * **没有配套的清旗标动作**：事件挂在**正在等的那一次读**上，读返回即摘掉投递口 ⇒「没读在等时 abort」
     * 是 no-op、「下一次读」是全新信号。它**不掀会话**：连接的去留由等待的读自己决定。默认空实现：
     * 不阻塞的 IO（[FileIO]）没什么可叫醒的。推导见 `http-streaming.md`。
     */
    fun abort() {}
  }

  fun read(buf: ByteArray): Int

  fun seek(
    offset: Int,
    whence: Int,
  ): Int

  fun close()
}
