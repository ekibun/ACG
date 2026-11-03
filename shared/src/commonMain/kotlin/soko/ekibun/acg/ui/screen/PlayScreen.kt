package soko.ekibun.acg.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import soko.ekibun.acg.player.IOHandler
import soko.ekibun.acg.ui.comp.PlayerSlider
import soko.ekibun.ffmpeg.AVMediaType
import soko.ekibun.ffmpeg.AvFormat
import soko.ekibun.ffmpeg.AvStream
import soko.ekibun.ffmpeg.FFPlayer
import soko.ekibun.ffmpeg.SurfaceContext
import soko.ekibun.ffmpeg.VideoSurface

/** 「已跳过广告」提示的停留时长。 */
private const val AD_SKIP_NOTICE_MS = 5_000L

// 播放页签，带 @Preview；按误报关掉 preview-public-check。
@Suppress("ktlint:compose:preview-public-check")
@Preview(showBackground = true)
@Composable
fun PlayScreen() {
  val surfaceContext = remember { mutableStateOf<SurfaceContext?>(null) }
  val url =
    remember { mutableStateOf("http://127.0.0.1:8099/media/hls-ad/playlist.m3u8") }
  val player = remember { mutableStateOf<FFPlayer?>(null) }
  val duration = remember { mutableFloatStateOf(0f) }
  val pts = remember { mutableFloatStateOf(0f) }
  val seeking = remember { mutableStateOf(false) }
  val playing = remember { mutableStateOf(false) }
  // 读包卡住：进度条下方扫一道浅色光
  val stalled = remember { mutableStateOf(false) }
  // 刚跳过的广告断点（声明轴微秒）；非 null 时左下角挂着回退 / 撤销提示
  val adNotice = remember { mutableStateOf<AdSkipNotice?>(null) }

  DisposableEffect(Unit) {
    onDispose {
      MainScope().launch {
        player.value?.closeAsync()
      }
    }
  }

  // 两态都只挂 5 s；翻态时键一变，效应重起、重新计时
  LaunchedEffect(adNotice.value) {
    val pending = adNotice.value ?: return@LaunchedEffect
    delay(AD_SKIP_NOTICE_MS)
    // 只收自己这一条：到期的续体可能已排在队列里，而翻态那次取消要等重组才落地
    if (adNotice.value === pending) adNotice.value = null
  }

  // 总时长未知（0）时 Slider 的区间退化成 0..0（分数会除零），给它一个空轨道兜底
  val totalUs = duration.floatValue.takeIf { it > 0f } ?: 1f

  Surface {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      TextField(
        modifier = Modifier.fillMaxWidth(),
        maxLines = 1,
        trailingIcon = {
          TextButton(
            enabled = surfaceContext.value != null,
            onClick = {
              MainScope().launch {
                try {
                  player.value?.closeAsync()
                  stalled.value = false
                  adNotice.value = null
                  val newPlayer =
                    FFPlayer(
                      url.value,
                      IOHandler(),
                      surfaceContext.value,
                      onEvent = {
                        when (it) {
                          is FFPlayer.Event.Frame -> {
                            val p = it.pts
                            playing.value = p != null
                            // 本轮收场（pts 为 null）时收掉扫光，别让卡住的提示留在界面上
                            if (p == null) stalled.value = false
                            if (p != null && !seeking.value) pts.floatValue = p.toFloat()
                          }
                          FFPlayer.Event.ReadTimeout -> stalled.value = true
                          FFPlayer.Event.ReadTimeoutResume -> stalled.value = false
                          is FFPlayer.Event.AdSkipped ->
                            adNotice.value = AdSkipNotice(from = it.from, to = it.to)
                        }
                      },
                    )
                  player.value = newPlayer
                  val streams = newPlayer.getStreams()
                  val ptsStreams = HashMap<Int, AvStream>()
                  for (type in arrayOf(AVMediaType.VIDEO, AVMediaType.AUDIO)) {
                    streams.firstOrNull { it.codecType == type }?.let {
                      ptsStreams[type] = it
                    }
                  }
                  // 总时长：优先取格式层，拿不到退回流时长
                  val totalDurationUs =
                    newPlayer.getDurationUs().takeIf { it > 0 }
                      ?: ptsStreams.values.maxOf { it.duration }
                  duration.floatValue = totalDurationUs.toFloat()
                  newPlayer.play(ptsStreams, 0)
                } catch (e: Exception) {
                  e.printStackTrace()
                }
              }
            },
          ) {
            Text("play>")
          }
        },
        value = url.value,
        onValueChange = {
          url.value = it
        },
      )
      Box(
        modifier = Modifier.weight(1f).fillMaxWidth(),
        contentAlignment = Alignment.Center,
      ) {
        // 提示挂在画面本身而不是外层留白上：letterbox 时它才贴在视频左下角
        Box(Modifier.aspectRatio(surfaceContext.value?.aspectRatio ?: 1f)) {
          VideoSurface(
            modifier = Modifier.matchParentSize(),
            onSurfaceContext = { surfaceContext.value = it },
          )
          AdSkipNoticeOverlay(
            notice = adNotice.value,
            onAction = {
              val notice = adNotice.value
              if (notice != null) {
                // 先翻状态再动播放器：慢源上 seek 要几百毫秒，按钮得立刻换向
                adNotice.value = notice.copy(reverted = !notice.reverted)
                MainScope().launch {
                  val player = player.value ?: return@launch
                  if (notice.reverted) player.seekTo(notice.to) else player.reverseAd(notice.from)
                }
              }
            },
            modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
          )
        }
      }
      Row(
        verticalAlignment = Alignment.CenterVertically,
      ) {
        TextButton(
          enabled = player.value != null,
          onClick = {
            MainScope().launch {
              playing.value = !playing.value
              if (playing.value) {
                player.value?.resume()
              } else {
                player.value?.pause()
              }
            }
          },
        ) {
          Text(if (playing.value) "pause" else "play")
        }
        TextButton(
          enabled = player.value != null,
          onClick = {
            MainScope().launch {
              // 后退一帧
              player.value?.stepBack()
            }
          },
        ) {
          Text("-1f")
        }
        TextButton(
          enabled = player.value != null,
          onClick = {
            MainScope().launch {
              player.value?.stepForward()
            }
          },
        ) {
          Text("+1f")
        }
        PlayerSlider(
          value = pts.floatValue,
          // 缓冲进度暂未接入：demuxer 侧还没有可读的“已读到哪里”，先只画已播放段
          buffered = 0f,
          valueRange = 0f..totalUs,
          enabled = duration.floatValue > 0f,
          stalled = stalled.value,
          modifier = Modifier.weight(1f).wrapContentHeight(),
          onValueChange = {
            seeking.value = true
            pts.floatValue = it
          },
          onValueChangeFinished = {
            MainScope().launch {
              seeking.value = false
              player.value?.seekTo(pts.floatValue.toLong())
            }
          },
        )
        Text(
          modifier = Modifier.padding(horizontal = 8.dp),
          text = formatTime(pts.floatValue.toLong()) + "/" + formatTime(duration.floatValue.toLong()),
        )
      }
    }
  }
}

/** 跳过提示的状态：`已跳过广告 / 回退` ⇄ `已回到广告 / 撤销`，两个位置各对应一个方向的动作。 */
private data class AdSkipNotice(
  /** 断点前最后一帧（声明轴微秒）。 */
  val from: Long,
  /** 断点后第一帧（声明轴微秒）。 */
  val to: Long,
  /** 已经回到广告里，按钮是「撤销」。 */
  val reverted: Boolean = false,
)

/**
 * 左下角的跳过提示：告诉用户这里少了一段，并给出反方向的入口。
 *
 * 淡入淡出单独封一层：调用点在 `Column` 的 `Box` 里，直接写 `AnimatedVisibility` 会被
 * `ColumnScope` 那个同名扩展抢走（`@LayoutScopeMarker` 下用不了隐式接收者）。
 */
@Composable
private fun AdSkipNoticeOverlay(
  notice: AdSkipNotice?,
  onAction: () -> Unit,
  modifier: Modifier = Modifier,
) {
  // 退场时 notice 已是 null：靠这份快照留住最后一次的内容，标签就不在淡出时翻回上一种状态
  val shown = remember { mutableStateOf<AdSkipNotice?>(null) }
  SideEffect {
    if (notice != null) shown.value = notice
  }
  AnimatedVisibility(
    visible = notice != null,
    modifier = modifier,
    enter = fadeIn() + slideInVertically { it / 2 },
    exit = fadeOut(),
  ) {
    // 进场时快照还没写进来，用 notice；退场时它已是 null，才靠快照
    val current = notice ?: shown.value
    if (current != null) {
      Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        contentColor = MaterialTheme.colorScheme.onSurface,
      ) {
        Row(
          modifier = Modifier.padding(start = 12.dp, end = 4.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            if (current.reverted) "已回到广告" else "已跳过广告",
            style = MaterialTheme.typography.labelLarge,
          )
          TextButton(onClick = onAction) {
            Text(if (current.reverted) "撤销" else "回退")
          }
        }
      }
    }
  }
}

fun formatTime(time: Long): String {
  val ms = time * 1000 / AvFormat.AV_TIME_BASE
  val s = ms / 1000
  val m = s / 60
  val h = m / 60
  val mss =
    (m % 60).toString().padStart(2, '0') +
      ":" + (s % 60).toString().padStart(2, '0') +
      "." + (ms % 1000).toString().padStart(3, '0')
  return if (h == 0L) mss else "$h:$mss"
}
