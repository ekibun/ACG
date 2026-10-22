package soko.ekibun.acg.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import soko.ekibun.acg.player.IOHandler
import soko.ekibun.ffmpeg.AVMediaType
import soko.ekibun.ffmpeg.AvFormat
import soko.ekibun.ffmpeg.AvStream
import soko.ekibun.ffmpeg.FFPlayer
import soko.ekibun.ffmpeg.SurfaceContext
import soko.ekibun.ffmpeg.VideoSurface

// 播放页签，带 @Preview；按误报关掉 preview-public-check。
@Suppress("ktlint:compose:preview-public-check")
@Preview(showBackground = true)
@Composable
fun PlayScreen() {
  val surfaceContext = remember { mutableStateOf<SurfaceContext?>(null) }
  val url =
    remember { mutableStateOf("http://127.0.0.1:8099/media") }
  val player = remember { mutableStateOf<FFPlayer?>(null) }
  val duration = remember { mutableFloatStateOf(0f) }
  val pts = remember { mutableFloatStateOf(0f) }
  val seeking = remember { mutableStateOf(false) }
  val playing = remember { mutableStateOf(false) }

  DisposableEffect(Unit) {
    onDispose {
      MainScope().launch {
        player.value?.closeAsync()
      }
    }
  }

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
                            if (p != null && !seeking.value) pts.floatValue = p.toFloat()
                          }
                          FFPlayer.Event.ReadTimeout, FFPlayer.Event.ReadTimeoutResume -> println(it)
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
      Row(
        modifier = Modifier.weight(1f),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        VideoSurface(
          modifier = Modifier.aspectRatio(surfaceContext.value?.aspectRatio ?: 1f),
          onSurfaceContext = { surfaceContext.value = it },
        )
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
        Slider(
          value = pts.floatValue,
          modifier = Modifier.weight(1f),
          valueRange = 0f..duration.floatValue,
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

fun formatTime(time: Long): String {
  val s = time / AvFormat.AV_TIME_BASE
  val m = s / 60
  val h = m / 60
  val ms = (m % 60).toString().padStart(2, '0') + ":" + (s % 60).toString().padStart(2, '0')
  return if (h == 0L) ms else "$h:$ms"
}
