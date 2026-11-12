package soko.ekibun.acg.data

import kotlinx.coroutines.runBlocking
import soko.ekibun.acg.TestEnv
import soko.ekibun.common.settingsStore
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 账号仓库：读回来的状态、内存里的变化、以及 `add` 那一次写盘。
 *
 * 写都是 `suspend`、返回时已落盘，所以 `add` 回来就能读磁盘。`select` / `remove` 走同一条落盘路径，
 * 用例只看它们的内存效果，不重复验盘。
 */
class AccountRepositoryTest {
  @BeforeTest
  fun paths() {
    TestEnv.ensurePaths()
  }

  /** 落盘格式里带着 current；删掉当前账号后不该剩下任何账号。 */
  @Test(timeout = 60000)
  fun loadsAccountsWithCurrentAndDropsTheCurrentOnRemove() =
    runBlocking {
      settingsStore.set(ACCOUNTS_KEY, SAVED)

      AccountRepository.load()
      assertEquals("ekibun", AccountRepository.current.value?.name, "current points at a stored account")
      assertEquals(setOf("bgm:1"), AccountRepository.accounts.value.keys)
      assertEquals("1", AccountRepository.current.value?.id, "the stored field is the bare user id")
      assertEquals("bgm:1", AccountRepository.current.value?.key, "the table key is derived, not stored")

      AccountRepository.remove("bgm:1")
      assertNull(AccountRepository.current.value, "removing the current account leaves none")
      assertTrue(AccountRepository.accounts.value.isEmpty(), "the removed account is gone from the table")
    }

  /** 读坏当空表；多出来的未知字段只忽略，不能把整份账号读没。 */
  @Test(timeout = 60000)
  fun corruptValueLeavesNoneAndUnknownFieldsAreIgnored() =
    runBlocking {
      settingsStore.set(ACCOUNTS_KEY, "{ not json")
      AccountRepository.load()
      assertNull(AccountRepository.current.value, "a corrupt value means no accounts")

      settingsStore.set(ACCOUNTS_KEY, SAVED_UNKNOWN_FIELD)
      AccountRepository.load()
      assertEquals(setOf("bgm:9"), AccountRepository.accounts.value.keys, "unknown fields are ignored")
    }

  /** 同一个人重登：还是一条，会话换成新的，并且成为当前账号。 */
  @Test(timeout = 60000)
  fun addOverwritesTheSameUserAndPersists() =
    runBlocking {
      settingsStore.set(ACCOUNTS_KEY, null)
      AccountRepository.load()

      AccountRepository.add(Account(site = "bgm", id = "1", name = "ekibun", session = "old"))
      AccountRepository.add(Account(site = "bgm", id = "1", name = "ekibun", session = "new"))

      assertEquals(1, AccountRepository.accounts.value.size, "the same user is one account")
      val stored = AccountRepository.accounts.value.getValue("bgm:1")
      assertEquals("new", stored.session)
      assertEquals("bgm:1", AccountRepository.current.value?.key, "a fresh login becomes current")

      // add 等写完才返回，所以这里读到的就是它写完的结果
      val raw = settingsStore.get(ACCOUNTS_KEY)
      assertNotNull(raw, "the account is written back")
      assertTrue(raw.contains("\"new\""), "the newest session is on disk")
    }

  private companion object {
    const val ACCOUNTS_KEY = "accounts"

    const val SAVED =
      """{"current":"bgm:1","accounts":{"bgm:1":{"site":"bgm","id":"1","name":"ekibun","session":"{}"}}}"""

    const val SAVED_UNKNOWN_FIELD =
      """{"accounts":{"bgm:9":{"site":"bgm","id":"9","name":"n","session":"s","unknown":true}}}"""
  }
}
