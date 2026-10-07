package soko.ekibun.ffmpeg

/**
 * 单调时钟的毫秒读数 —— 只保证**两次读数的差**有意义，与墙钟无关（NTP 校时、
 * 手动改时间都不影响它）。给「测时间差」的场合用（播放主时钟、超时估算），
 * 别拿它表达日历时间。
 *
 * 与 `soko.ekibun.acg.common.openFileHandle` 同一条 expect/actual 路子：
 * `commonMain` 不出现平台符号，平台能力以最小原语交进来（根 AGENTS.md §4）。
 */
expect fun monoTimeMs(): Long
