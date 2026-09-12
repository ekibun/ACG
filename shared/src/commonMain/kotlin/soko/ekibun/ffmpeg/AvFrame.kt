package soko.ekibun.ffmpeg

import soko.ekibun.jniLoadLibrary

class AvFrame(
  val ptr: Long,
  val timeStamp: Long,
  val width: Int,
  val height: Int
) {
  companion object {
    init {
      jniLoadLibrary("ffmpeg")
    }
  }
  var processing: FFPlayer.PTS? = null

  private external fun closeNative(ptr: Long)
  protected fun finalize() {
    if(ptr != 0L) closeNative(ptr)
  }
}