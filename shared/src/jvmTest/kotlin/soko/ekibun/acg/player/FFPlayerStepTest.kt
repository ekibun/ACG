package soko.ekibun.acg.player

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import soko.ekibun.ffmpeg.AVMediaType
import soko.ekibun.ffmpeg.FFPlayer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 单帧步进登记的前后帧时间戳（[FFPlayer] 的 `lastFrameTs` / `prevFrameTs`）。
 *
 * 素材是仓库根下的 `.workbuddy/test.mp4` —— 那个目录整体 gitignore，所以**没有素材就跳过**
 * （与仓库里"环境不全就返回"的用例同一种做法，见 `NativeWebViewHostTest`）。
 *
 * 为什么值得留：这条路走错时的症状全是**不报错**的 —— 每按一次「前进一帧」跳过一帧、
 * 或退帧落到隔一帧的位置，光看画面看不出是代码错了。实测踩过一次（播放轮次结束时，
 * 队列把"已取到但没显示"的那一帧丢掉，于是只有一半的帧能靠步进走到），所以留个用例。
 *
 * 位置没法从公开 API 观察（进度是 [FFPlayer.play] 那条路里的 `Playback.onFrame` 给的），
 * 所以用反射读那两个私有字段 —— 至少不为了测试在生产代码上开钩子。
 */
class FFPlayerStepTest {
  private fun FFPlayer.field(name: String): Long? {
    val field = FFPlayer::class.java.getDeclaredField(name)
    field.isAccessible = true
    return field.get(this) as Long?
  }

  private fun FFPlayer.state(): Pair<Long?, Long?> = field("lastFrameTs") to field("prevFrameTs")

  /** 从测试的工作目录往上找仓库根下的素材；找不到就返回 null（用例跳过）。 */
  private fun findVideo(): Path? {
    var dir: Path? = Path.of("").toAbsolutePath()
    while (dir != null) {
      val candidate = dir.resolve(".workbuddy/test.mp4")
      if (Files.exists(candidate)) return candidate
      dir = dir.parent
    }
    return null
  }

  @Test
  fun stepForwardAndBackWalkFrames() {
    val url = findVideo()
    if (url == null) {
      println("跳过：找不到 .workbuddy/test.mp4（素材在 gitignore 里，不随仓库分发）")
      return
    }
    runBlocking {
      val player = FFPlayer(url.toString(), FileIO.Handler(), null)
      try {
        val video = player.getStreams().first { it.codecType == AVMediaType.VIDEO }
        // play() 要一直挂到整个播放轮次结束（withContext 会等自己的子协程，而轮次就是那个
        // 子协程），所以不能 await —— UI 里也是 fire-and-forget 地 launch。
        val playJob = launch { player.play(mapOf(AVMediaType.VIDEO to video), 0) }
        withTimeout(10_000) {
          while (player.state().first == null) delay(5)
        }
        player.pause()
        playJob.join()

        // 连续前进：每一步的「前一帧」都要接得上上一步的「当前帧」，且时间戳必须前进
        val forward =
          (0 until 5).map {
            player.stepForward()
            player.state()
          }
        forward.zipWithNext().forEach { (a, b) ->
          assertEquals(a.first, b.second, "前一帧应为上一次的当前帧: $a -> $b")
          assertTrue(b.first!! > a.first!!, "时间戳必须单调前进: $a -> $b")
        }

        // 连续后退：沿原路一帧一帧走回去，连「前一帧」也要对上
        forward.dropLast(1).asReversed().forEach { expect ->
          assertTrue(player.stepBack(), "退帧应成功")
          assertEquals(expect, player.state(), "退帧后应回到 $expect")
        }
      } finally {
        player.closeAsync()
      }
    }
  }
}
