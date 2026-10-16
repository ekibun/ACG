package soko.ekibun.common

import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [SettingsStore] 的落盘行为。用 [createSettingsStore] 指到临时文件 —— 走 [settingsStore]
 * 那个单例就往开发机的家目录写了。
 *
 * 不为「同一个文件上开两个实例」写用例：DataStore 明确禁止那么用，验出来的也不是本类的事。
 */
class SettingsStoreTest {
  @Test
  fun readsEmptyBeforeAnyWrite() =
    runBlocking {
      assertEquals(emptyMap(), tempStore().read(), "文件还不存在时读回来是空表，不是错误")
    }

  @Test
  fun writesToDiskAndDeletesOnNull() =
    runBlocking {
      val dataDir = Files.createTempDirectory("acg-settings").resolve("nested")
      val path = dataDir.resolve("settings.preferences_pb")
      assertTrue(!Files.exists(dataDir), "前提：父目录还没建")
      val store = createSettingsStore(path.toString().toPath())

      store.write(mapOf("bgm.client_id" to "client-1", "bgm.client_secret" to "s3cret"))
      assertEquals(
        mapOf("bgm.client_id" to "client-1", "bgm.client_secret" to "s3cret"),
        store.read(),
      )
      assertTrue(
        Files.size(path) > 0,
        "写完文件得真的落盘 —— 父目录由 DataStore 自己建出来（首次运行的 ~/.acg 就是这种）",
      )

      store.write(mapOf("bgm.client_id" to null))
      assertEquals(mapOf("bgm.client_secret" to "s3cret"), store.read(), "值为 null 是删键")

      store.write(mapOf("bgm.client_secret" to null))
      assertEquals(emptyMap(), store.read())
    }

  private fun tempStore(): SettingsStore {
    val path = Files.createTempDirectory("acg-settings").resolve("settings.preferences_pb")
    return createSettingsStore(path.toString().toPath())
  }
}
