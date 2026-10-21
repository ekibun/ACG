package soko.ekibun.common

/** 用 [System.nanoTime] 读数换算成毫秒。 */
actual fun monoTimeMs(): Long = System.nanoTime() / 1_000_000
