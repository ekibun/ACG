package soko.ekibun.acg.player

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertIs

/** `IOHandler.open` 的分发：http(s) 建网络会话，其余一律当本地路径。 */
class IOHandlerTest {
  /** 按 scheme 分发；建 IO 不建连，所以地址连不上也不会卡住，只判交出了哪个实现。 */
  @Test(timeout = 60000)
  fun openDispatchesByScheme() {
    assertIs<HttpIO>(IOHandler().open("http://127.0.0.1:1/media"))
    assertIs<HttpIO>(IOHandler().open("HTTPS://127.0.0.1:1/media"))

    val path = Files.createTempFile("iohandler-test", ".bin")
    try {
      assertIs<FileIO>(IOHandler().open(path.toString())).close()
    } finally {
      Files.deleteIfExists(path)
    }
  }
}
