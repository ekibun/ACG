package soko.ekibun.ffmpeg

import soko.ekibun.jniLoadLibrary

class AvPacket {
  val ptr: Long = initNative()
  var streamIndex: Int = -1

  companion object {
    init {
      jniLoadLibrary("ffmpeg")
    }
  }

  private external fun initNative(): Long

  external fun closeNative(ptr: Long)

  protected fun finalize() {
    closeNative(ptr)
  }
}
