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

  /** 四种 whence 的定位语义，含「往回退越过文件头夹到 0」。 */
  @Test(timeout = 60000)
  fun seekPositionsByWhence() {
    val io = fileIO()
    // AVSEEK_SIZE 只回总长，不动游标
    assertEquals(FILE_SIZE, io.seek(0, AvFormat.AVSEEK_SIZE), "AVSEEK_SIZE must report the file length")
    // SEEK_SET 绝对定位
    assertEquals(600, io.seek(600, AvIO.SEEK_SET))
    assertEquals(listOf(600 % 251, 601 % 251, 602 % 251).map { it.toByte() }, io.readBytes(3))
    // SEEK_CUR 相对当前，可负
    io.seek(100, AvIO.SEEK_SET)
    assertEquals(150, io.seek(50, AvIO.SEEK_CUR))
    assertEquals(50, io.seek(-100, AvIO.SEEK_CUR))
    assertEquals(listOf(50 % 251, 51 % 251).map { it.toByte() }, io.readBytes(2))
    // 越过文件头夹到 0
    assertEquals(0, io.seek(-100, AvIO.SEEK_CUR))
    assertEquals(listOf(0.toByte(), 1.toByte()), io.readBytes(2))
    // SEEK_END 相对文件尾
    assertEquals(FILE_SIZE, io.seek(0, AvIO.SEEK_END))
    assertEquals(FILE_SIZE - 10, io.seek(-10, AvIO.SEEK_END))
    assertEquals(listOf((FILE_SIZE - 10) % 251, (FILE_SIZE - 9) % 251).map { it.toByte() }, io.readBytes(2))
    // 未知 whence
    assertEquals(-1, io.seek(0, 0x999), "unknown whence must report -1")
    io.close()
  }

  /** 文件尾的两种收场：正好落在尾上读、跨越尾读。 */
  @Test(timeout = 60000)
  fun readsAtAndAcrossEof() {
    val atEof = fileIO()
    atEof.seek(FILE_SIZE, AvIO.SEEK_SET)
    assertEquals(AvFormat.AVERROR_EOF, atEof.read(ByteArray(16), 16), "at EOF the read must report AVERROR_EOF")
    atEof.close()

    val acrossEof = fileIO()
    acrossEof.seek(FILE_SIZE - 10, AvIO.SEEK_SET)
    assertEquals(10, acrossEof.read(ByteArray(64), 64), "a read across EOF must return only the remainder")
    assertEquals(AvFormat.AVERROR_EOF, acrossEof.read(ByteArray(64), 64), "the read after that is EOF")
    acrossEof.close()
  }

  @Test(timeout = 60000)
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

  /** 打不开返回 -1，不抛（`open` 跑在 JNI 回调里，见 `playback-invariants` §1.5）。 */
  @Test(timeout = 60000)
  fun missingFileFailsInsteadOfThrowing() {
    val io = FileIO("no-such-dir/no-such-file.mp4")
    assertEquals(-1, io.read(ByteArray(16), 16))
    assertEquals(-1, io.seek(0, AvIO.SEEK_SET))
    assertEquals(-1, io.seek(0, AvFormat.AVSEEK_SIZE))
    io.close()
  }

  /** url 由 avformat 原样送进 `io_open`：`file:///` 与裸路径两种写法都得认。 */
  @Test(timeout = 60000)
  fun handlerAcceptsBothUrlForms() {
    val path = Files.createTempFile("fileio-test", ".bin").also { temps.add(it) }
    Files.write(path, content())
    val urlForm = IOHandler().open("file:///" + path.toString().replace('\\', '/'))
    try {
      assertEquals(FILE_SIZE, urlForm.seek(0, AvFormat.AVSEEK_SIZE), "file:///D:/… must resolve")
      assertEquals(7, urlForm.seek(7, AvIO.SEEK_SET))
    } finally {
      urlForm.close()
    }
    val plainForm = IOHandler().open(path.toString())
    try {
      assertEquals(FILE_SIZE, plainForm.seek(0, AvFormat.AVSEEK_SIZE), "a bare path must resolve")
    } finally {
      plainForm.close()
    }
  }
}
