package soko.ekibun.ffmpeg

import soko.ekibun.Pointer
import soko.ekibun.loadLibrary

class AvPacket : Pointer() {
  /** 现算句柄。 */
  override fun initPtr(): Long = initNative()

  var streamIndex: Int = -1

  companion object {
    init {
      loadLibrary("ffmpeg")
    }

    @JvmStatic
    private external fun initNative(): Long

    @JvmStatic
    external fun closeNative(ptr: Long)
  }

  /** 归还底层的 `AVPacket`；所有权显式，谁拿到谁还。 */
  override suspend fun releaseImpl(ptr: Long) = closeNative(ptr)
}
