package soko.ekibun.acg.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
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
import soko.ekibun.acg.player.HttpIO
import soko.ekibun.acg.player.Playback
import soko.ekibun.ffmpeg.AVMediaType
import soko.ekibun.ffmpeg.AvFormat
import soko.ekibun.ffmpeg.AvStream
import soko.ekibun.ffmpeg.FFPlayer

// 同 App()：挂着 @Preview，但它是被 App() 调用的真实页签，不能是 private。
@Suppress("ktlint:compose:preview-public-check")
@Preview(showBackground = true)
@Composable
fun PlayScreen() {
  val playback = remember { mutableStateOf<Playback?>(null) }
  val url =
    remember { mutableStateOf("https://media.w3.org/2010/05/sintel/trailer.mp4") }
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
    Column {
      TextField(
        maxLines = 1,
        trailingIcon = {
          TextButton(
            enabled = playback.value != null,
            onClick = {
              MainScope().launch {
                player.value?.closeAsync()
                val newPlayer =
                  FFPlayer(
                    url.value,
                    HttpIO.Handler(),
                    playback.value,
                  )
                player.value = newPlayer
                val streams = newPlayer.getStreams()
                val ptsStreams = HashMap<Int, AvStream>()
                for (type in arrayOf(AVMediaType.VIDEO, AVMediaType.AUDIO)) {
                  streams.firstOrNull { it.codecType == type }?.let {
                    ptsStreams[type] = it
                  }
                }
                duration.floatValue =
                  ptsStreams.values.maxOf { it.duration }.toFloat()
                newPlayer.play(ptsStreams, 0)
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
          modifier = Modifier.aspectRatio(playback.value?.aspectRatio ?: 1f),
          onPlayback = { playback.value = it },
          onFrame = { p ->
            playing.value = p != null
            if (p != null && !seeking.value) pts.floatValue = p.toFloat()
          },
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
              playback.value?.isMuteVoice = !(playback.value?.isMuteVoice ?: false)
            }
          },
        ) {
          Text(if (playback.value?.isMuteVoice == true) "mute" else "orig")
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
