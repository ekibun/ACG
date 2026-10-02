package soko.ekibun.ffmpeg

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import soko.ekibun.acg.player.FileIO
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 读包错误路径的回归：
 *
 * - **读出错必须出声**：`AvIO.read` 返回负数（AvIO 契约里的「出错」，EOF 是 0）
 *   最终会被 avio 毒化成 EOF 状态（aviobuf.c:551-558 把任何负返回值置
 *   `eof_reached`），`getPacket` 在 API 层分不开两者 —— 但不能静默：这里断言
 *   `getPacket` 仍以 null 收场（不挂死、不抛），且 stderr 上有那条 error 日志
 *   （native 侧另有两条 av_log，JVM 测试抓的是 Kotlin 这条）。
 * - **打不开的源要干净地失败**：`avformat_open_input` 失败走 initNative 的失败
 *   清理路径（GlobalRef / pb / URL 字符串都在那边收），返回 0、Kotlin 侧以
 *   [IllegalStateException] 收场 —— 泄漏本身无法从 JVM 直接观测，这条钉的是
 *   「失败路径至少能干净走完、不再崩 native」。
 */
class AvReadErrorFoldTest {
  /** 包一层「第 [failAfter] 次成功 read 之后就开始返回 -1」的 Handler。 */
  private class FailingAfterHandler(
    private val failAfter: Int,
  ) : AvIO.Handler {
    var reads = 0

    override fun open(url: String): AvIO {
      val delegate = FileIO.Handler().open(url)
      return object : AvIO by delegate {
        override fun read(buf: ByteArray): Int {
          if (++reads > failAfter) return -1
          return delegate.read(buf)
        }
      }
    }
  }

  @Test
  fun ioErrorFoldsToEofButIsLogged() {
    // 10 秒 ≈ 160 KB，要 5 次 32 KB 的 read 才读完；第 4 次起返回 -1，
    // 错误必然落在 getPacket 阶段（前 3 次足够 find_stream_info 用）。
    val wav = WavMedia.write(durationSec = 10)
    val err = ByteArrayOutputStream()
    val originalErr = System.err
    System.setErr(PrintStream(err, true, Charsets.UTF_8))
    try {
      runBlocking {
        withTimeout(30_000) {
          AvFormat(wav.toString(), FailingAfterHandler(failAfter = 3)).use { player ->
            player.getStreams()
            while (true) {
              // downloader 模式（streams 为空收所有包）：包拿到就还，
              // 所有权归调用方（见 AvPacket）。
              val packet = player.getPacket(emptyList()) ?: break
              packet.close()
            }
          }
        }
      }
    } finally {
      System.setErr(originalErr)
    }
    assertTrue(
      err.toString(Charsets.UTF_8).contains("[AvFormat] av_read_frame error"),
      "IO error must be logged instead of folding silently; stderr was: $err",
    )
  }

  @Test
  fun openFailureFailsCleanly() {
    assertFailsWith<IllegalStateException> {
      runBlocking {
        AvFormat("Z:/definitely/not/here/nope.mp4", FileIO.Handler()).getStreams()
      }
    }
  }
}
