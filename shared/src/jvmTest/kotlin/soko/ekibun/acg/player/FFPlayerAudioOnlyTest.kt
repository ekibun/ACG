package soko.ekibun.acg.player

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import soko.ekibun.TestMedia
import soko.ekibun.ffmpeg.AVMediaType
import soko.ekibun.ffmpeg.FFPlayer
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * **只喂音频流**时的 `play()` 真路径回归 —— 「纯音频文件」的等价形态。
 *
 * 防的是：续播判据若闸在「有无视频轨」上，纯音频输入 `play()` 不读任何包（无声）、
 * `seekTo()` 停住不续播（seek 的续播与 play 走同一个门）。
 *
 * 不必为这件事专门造一个纯音频文件：**打开一份普通素材、只把音频那条 `getStreams()`
 * 出来的流交给 `play()`**，`pts.streams` 里就只有音频，走的正是那条分支。取包侧同样只看
 * 传进去的流 —— `AvFormat.getPacket` 会把不属于这些流的包**就地丢掉**（见那边的注释），
 * 所以视频包根本不会流进来。
 *
 * 素材用随源码入库的 [TestMedia.BBB_640X360_12S_FASTSTART]（含音轨，AAC 44.1kHz 立体声）。
 * 假声卡（[TestSurface]）只数「音频帧到达平台侧」的次数，不排真声卡 —— 音频路径没有实时节流
 * （等时钟的 `delay` 只在视频分支），整轮会以解码速度跑完。
 */
class FFPlayerAudioOnlyTest {
  @Test(timeout = 120_000)
  fun audioOnlyPlayRunsToEof() {
    val events: MutableList<Long?> = Collections.synchronizedList(mutableListOf<Long?>())
    // 重采样目标取素材自己的规格（BBB = AAC 44.1kHz 立体声）；与判据无关，别的取值照样能跑。
    val playback = TestSurface(rate = 44_100)
    val media = TestMedia.path(TestMedia.BBB_640X360_12S_FASTSTART)
    runBlocking {
      val player =
        FFPlayer(media.toString(), FileIO.Handler(), playback) {
          if (it is FFPlayer.Event.Frame) {
            events.add(it.pts)
          }
        }
      try {
        val audio = player.getStreams().first { it.codecType == AVMediaType.AUDIO }
        // 只把音频流传进去 —— 这就是「纯音频文件」的等价输入。
        // play() 要 fire-and-forget：withContext 只等到 seek/resume 排程完，
        // 整轮挂在 playingJob 上 —— UI 里也是这么调的。
        launch { player.play(mapOf(AVMediaType.AUDIO to audio), 0) }
        withTimeout(30_000.milliseconds) {
          while (events.isEmpty() || events.last() != null) delay(10.milliseconds)
        }
        assertTrue(playback.audioFrameCount > 0, "audio frames must reach the platform side")
        assertTrue(events.contains(null), "EOF must be signaled via Event.Frame(null)")
      } finally {
        player.closeAsync()
      }
    }
  }
}
