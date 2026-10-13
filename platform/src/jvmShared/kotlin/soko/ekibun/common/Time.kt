package soko.ekibun.common

actual fun monoTimeMs(): Long = System.nanoTime() / 1_000_000
