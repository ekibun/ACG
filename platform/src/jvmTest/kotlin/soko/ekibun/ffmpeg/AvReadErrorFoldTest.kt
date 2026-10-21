package soko.ekibun.ffmpeg

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 读包错误路径：`AvIO.read` 返回负数后 `getPacket` 以 null 收场且有 error 日志；打不开的源抛 [IllegalStateException]。
 */
class AvReadErrorFoldTest {
  /** 包一层「第 [failAfter] 次成功 read 之后就开始返回 -1」的 Handler。 */
  private class FailingAfterHandler(
    private val failAfter: Int,
    private val inner: AvIO.Handler,
  ) : AvIO.Handler {
    var reads = 0

    override fun open(url: String): AvIO {
      val delegate = inner.open(url)
      return object : AvIO by delegate {
        override fun read(
          buf: ByteArray,
          len: Int,
        ): Int {
          if (++reads > failAfter) return -1
          return delegate.read(buf, len)
        }
      }
    }
  }

  @Test
  fun ioErrorFoldsToEofButIsLogged() {
    // 第 4 次 read 起返回 -1：前 3 次留给 find_stream_info，错误落在 getPacket 阶段
    val wav = WavMedia.bytes(durationSec = 10)
    val out = ByteArrayOutputStream()
    val originalOut = System.out
    System.setOut(PrintStream(out, true, Charsets.UTF_8))
    try {
      runBlocking {
        withTimeout(30_000) {
          AvFormat("memory:test.wav", FailingAfterHandler(failAfter = 3, inner = TestIo(wav))).use { player ->
            player.getStreams()
            while (true) {
              // downloader 模式：包拿到就还，所有权归调用方
              val packet = player.getPacket(emptyList()) ?: break
              packet.close()
            }
          }
        }
      }
    } finally {
      System.setOut(originalOut)
    }
    assertTrue(
      out.toString(Charsets.UTF_8).contains("[AvFormat] av_read_frame error"),
      "IO error must be logged instead of folding silently; stdout was: $out",
    )
  }

  @Test
  fun openFailureFailsCleanly() {
    assertFailsWith<IllegalStateException> {
      runBlocking {
        AvFormat(
          "Z:/definitely/not/here/nope.mp4",
          object : AvIO.Handler {
            // open 不抛，返回恒失败的 IO，由 avformat_open_input 自己失败
            override fun open(url: String): AvIO =
              object : AvIO {
                override fun read(
                  buf: ByteArray,
                  len: Int,
                ): Int = -1

                override fun seek(
                  offset: Int,
                  whence: Int,
                ): Int = -1

                override fun close() {}
              }
          },
        ).getStreams()
      }
    }
  }
}
