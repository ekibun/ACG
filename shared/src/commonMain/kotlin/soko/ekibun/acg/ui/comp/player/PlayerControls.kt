package soko.ekibun.acg.ui.comp.player

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import soko.ekibun.ffmpeg.AvFormat

/**
 * 播放控制条：播放 / 暂停、逐帧、进度、时间。
 *
 * 拖动中的手柄位置留在本地：拖动期间帧事件还在推 [PlayerController.positionUs]，直接用它手柄会被拽回去。
 */
@Composable
fun PlayerControls(
  controller: PlayerController,
  modifier: Modifier = Modifier,
) {
  val state by controller.state.collectAsState()
  val positionUs by controller.positionUs.collectAsState()
  val scope = rememberCoroutineScope()
  var dragging by remember { mutableStateOf<Float?>(null) }

  // 总时长未知（0）时 Slider 的区间退化成 0..0（分数会除零），给它一个空轨道兜底
  val totalUs = state.durationUs.takeIf { it > 0L }?.toFloat() ?: 1f
  val position = dragging ?: positionUs.toFloat()
  val ready = state.status == PlayerStatus.Ready || state.status == PlayerStatus.Ended

  Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
    TextButton(
      enabled = ready,
      onClick = { scope.launch { controller.togglePlay() } },
    ) {
      Text(if (state.playWhenReady) "pause" else "play")
    }
    TextButton(
      enabled = ready,
      onClick = { scope.launch { controller.stepBack() } },
    ) {
      Text("-1f")
    }
    TextButton(
      enabled = ready,
      onClick = { scope.launch { controller.stepForward() } },
    ) {
      Text("+1f")
    }
    PlayerSlider(
      value = position,
      // 缓冲进度暂未接入：demuxer 侧还没有可读的「已读到哪里」，先只画已播放段
      buffered = 0f,
      valueRange = 0f..totalUs,
      enabled = state.durationUs > 0L,
      stalled = state.buffering,
      modifier = Modifier.weight(1f).wrapContentHeight(),
      onValueChange = { dragging = it },
      onValueChangeFinished = {
        val target = dragging
        dragging = null
        if (target != null) scope.launch { controller.seekTo(target.toLong()) }
      },
    )
    Text(
      modifier = Modifier.padding(horizontal = 8.dp),
      text = formatTime(position.toLong()) + "/" + formatTime(state.durationUs),
    )
  }
}

/** 微秒时间轴按 `mm:ss.SSS` / `h:mm:ss.SSS` 画。 */
private fun formatTime(time: Long): String {
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
