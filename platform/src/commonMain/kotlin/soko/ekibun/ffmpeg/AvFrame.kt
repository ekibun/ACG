package soko.ekibun.ffmpeg

import soko.ekibun.Pointer
import soko.ekibun.loadLibrary

class AvFrame(
  nativePtr: Long,
  val timeStamp: Long,
  val width: Int,
  val height: Int,
) : Pointer(nativePtr) {
  companion object {
    init {
      loadLibrary("ffmpeg")
    }

    /** 归还底层 `AVFrame`；companion 里的 `external fun` 必须加 `@JvmStatic`（见 `cxx/AGENTS.md`）。 */
    @JvmStatic
    external fun closeNative(ptr: Long)
  }

  /** 归还底层 `AVFrame`。 */
  override suspend fun releaseImpl(ptr: Long) = closeNative(ptr)
}
