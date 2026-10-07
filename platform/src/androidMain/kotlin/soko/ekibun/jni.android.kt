package soko.ekibun

actual fun jniLoadLibrary(name: String) {
  System.loadLibrary(name)
}
