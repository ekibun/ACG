package soko.ekibun.acg.player

import soko.ekibun.ffmpeg.AvFormat
import soko.ekibun.ffmpeg.AvIO
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** `FileIO` 的 read / seek 语义，素材是现造的临时文件（内容 `i % 251`）。 */
class FileIOTest {
  private companion object {
    const val FILE_SIZE = 1000

    /** 每个偏移上一个可反查的字节：`content[i] == (i % 251).toByte()`。 */
    fun content() = ByteArray(FILE_SIZE) { (it % 251).toByte() }
  }

  private val temps = mutableListOf<Path>()

  private fun fileIO(content: ByteArray = content()): FileIO {
    val path = Files.createTempFile("fileio-test", ".bin").also { temps.add(it) }
    Files.write(path, content)
    return FileIO(path.toString())
  }

  private fun FileIO.readBytes(count: Int): List<Byte> {
    val buf = ByteArray(count)
    val ret = read(buf, buf.size)
    return buf.take(ret)
  }

  @AfterTest
  fun deleteTemps() {
    temps.forEach { Files.deleteIfExists(it) }
  }

  @Test
  fun avseekSizeReturnsFileLength() {
    // AVSEEK_SIZE 返回总长
    val io = fileIO()
    assertEquals(FILE_SIZE, io.seek(0, AvFormat.AVSEEK_SIZE))
    io.close()
  }

  @Test
  fun seekSetPositionsAbsolutely() {
    val io = fileIO()
    assertEquals(600, io.seek(600, AvIO.SEEK_SET))
    assertEquals(listOf(600 % 251, 601 % 251, 602 % 251).map { it.toByte() }, io.readBytes(3))
    io.close()
  }

  @Test
  fun seekCurIsRelativeToCurrent() {
    val io = fileIO()
    io.seek(100, AvIO.SEEK_SET)
    assertEquals(150, io.seek(50, AvIO.SEEK_CUR))
    // 回退若干字节：SEEK_CUR + 负偏移
    assertEquals(50, io.seek(-100, AvIO.SEEK_CUR))
    assertEquals(listOf(50 % 251, 51 % 251).map { it.toByte() }, io.readBytes(2))
    io.close()
  }

  @Test
  fun seekCurNeverGoesNegative() {
    val io = fileIO()
    io.seek(10, AvIO.SEEK_SET)
    assertEquals(0, io.seek(-100, AvIO.SEEK_CUR))
    assertEquals(listOf(0.toByte(), 1.toByte()), io.readBytes(2))
    io.close()
  }

  @Test
  fun seekEndIsRelativeToFileLength() {
    val io = fileIO()
    assertEquals(FILE_SIZE, io.seek(0, AvIO.SEEK_END))
    assertEquals(FILE_SIZE - 10, io.seek(-10, AvIO.SEEK_END))
    assertEquals(listOf((FILE_SIZE - 10) % 251, (FILE_SIZE - 9) % 251).map { it.toByte() }, io.readBytes(2))
    io.close()
  }

  @Test
  fun unknownWhenceFails() {
    val io = fileIO()
    assertEquals(-1, io.seek(0, 0x999))
    io.close()
  }

  @Test
  fun readAtEofReturnsAvErrorEof() {
    // 文件尾返回 AVERROR_EOF
    val io = fileIO()
    io.seek(FILE_SIZE, AvIO.SEEK_SET)
    assertEquals(AvFormat.AVERROR_EOF, io.read(ByteArray(16), 16))
    io.close()
  }

  @Test
  fun readAcrossEofReturnsOnlyRemainder() {
    val io = fileIO()
    io.seek(FILE_SIZE - 10, AvIO.SEEK_SET)
    assertEquals(10, io.read(ByteArray(64), 64))
    assertEquals(AvFormat.AVERROR_EOF, io.read(ByteArray(64), 64))
    io.close()
  }

  @Test
  fun readsWholeFileInChunks() {
    val io = fileIO()
    val buf = ByteArray(64)
    val read = ArrayList<Byte>()
    while (true) {
      val ret = io.read(buf, buf.size)
      if (ret < 0) {
        assertEquals(AvFormat.AVERROR_EOF, ret)
        break
      }
      read.addAll(buf.take(ret))
    }
    assertEquals(content().toList(), read)
    io.close()
  }

  @Test
  fun missingFileFailsInsteadOfThrowing() {
    // 打不开返回 -1，不抛异常
    val io = FileIO("no-such-dir/no-such-file.mp4")
    assertEquals(-1, io.read(ByteArray(16), 16))
    assertEquals(-1, io.seek(0, AvIO.SEEK_SET))
    assertEquals(-1, io.seek(0, AvFormat.AVSEEK_SIZE))
    io.close()
  }

  @Test
  fun handlerAcceptsFileUrl() {
    // url 由 avformat 原样送进 io_open，两种写法都得认
    val path = Files.createTempFile("fileio-test", ".bin").also { temps.add(it) }
    Files.write(path, content())
    val url = "file:///" + path.toString().replace('\\', '/')
    val io = IOHandler().open(url)
    assertEquals(FILE_SIZE, io.seek(0, AvFormat.AVSEEK_SIZE))
    assertEquals(7, io.seek(7, AvIO.SEEK_SET))
    io.close()
  }

  @Test
  fun handlerAcceptsPlainPath() {
    val path = Files.createTempFile("fileio-test", ".bin").also { temps.add(it) }
    Files.write(path, content())
    val io = IOHandler().open(path.toString())
    assertEquals(FILE_SIZE, io.seek(0, AvFormat.AVSEEK_SIZE))
    io.close()
  }
}
