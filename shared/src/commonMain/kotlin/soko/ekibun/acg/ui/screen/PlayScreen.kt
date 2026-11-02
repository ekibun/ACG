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
  // 刚跳过的广告断点（声明轴微秒）；非 null 时左下角挂着回退提示
  val skippedAdFrom = remember { mutableStateOf<Long?>(null) }

  DisposableEffect(Unit) {
    onDispose {
      MainScope().launch {
        player.value?.closeAsync()
      }
    }
  }

  // 提示只留 5 s；期间又跳了一段，键一变这个效应就重起、重新计时
  LaunchedEffect(skippedAdFrom.value) {
    if (skippedAdFrom.value == null) return@LaunchedEffect
    delay(AD_SKIP_NOTICE_MS)
    skippedAdFrom.value = null
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
                  skippedAdFrom.value = null
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
                          // 断点是声明轴上的一段：from 是跳过前最后一帧的位置
                          is FFPlayer.Event.AdSkipped -> skippedAdFrom.value = it.from
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
          AdSkippedOverlay(
            visible = skippedAdFrom.value != null,
            onRevert = {
              MainScope().launch {
                val from = skippedAdFrom.value ?: return@launch
                skippedAdFrom.value = null
                player.value?.reverseAd(from)
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

/**
 * 播放画面左下角的跳过提示：告诉用户这里少了一段，并给出回到跳过前的入口。
 *
 * 淡入淡出单独封一层：调用点在 `Column` 的 `Box` 里，直接写 `AnimatedVisibility` 会被
 * `ColumnScope` 那个同名扩展抢走（`@LayoutScopeMarker` 下用不了隐式接收者）。
 */
@Composable
private fun AdSkippedOverlay(
  visible: Boolean,
  onRevert: () -> Unit,
  modifier: Modifier = Modifier,
) {
  AnimatedVisibility(
    visible = visible,
    modifier = modifier,
    enter = fadeIn() + slideInVertically { it / 2 },
    exit = fadeOut(),
  ) {
    Surface(
      shape = RoundedCornerShape(8.dp),
      color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
      contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
      Row(
        modifier = Modifier.padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text("已跳过广告", style = MaterialTheme.typography.labelLarge)
        TextButton(onClick = onRevert) {
          Text("回退")
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
