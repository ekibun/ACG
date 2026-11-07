package soko.ekibun.common

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [SettingsStore] 的落盘链路：空读 → 写盘 → 落到磁盘 → `null` 删键 → 删空。 */
class SettingsStoreTest {
  @Test(timeout = 60000)
  fun roundTripLandsOnDiskAndDeletesOnNull() =
    runBlocking {
      val dataDir = Files.createTempDirectory("acg-settings").resolve("nested")
      val path = dataDir.resolve("settings.preferences_pb")
      assertTrue(!Files.exists(dataDir), "precondition: the parent directory does not exist yet")
      val store = createSettingsStore(path.toString())

      // 还没写过：空 map，不是异常
      assertEquals(
        emptyMap(),
        store.read(),
        "reading a file that does not exist yet yields an empty map, not an error",
      )

      val settings = mapOf("bgm.client_id" to "client-1", "bgm.client_secret" to "s3cret")
      store.write(settings)
      assertEquals(settings, store.read())
      assertTrue(
        Files.size(path) > 0,
        "the write must land on disk -- DataStore creates the parent directory itself on a first run",
      )

      store.write(mapOf("bgm.client_id" to null))
      assertEquals(mapOf("bgm.client_secret" to "s3cret"), store.read(), "a null value deletes the key")

      store.write(mapOf("bgm.client_secret" to null))
      assertEquals(emptyMap(), store.read())
    }
}
