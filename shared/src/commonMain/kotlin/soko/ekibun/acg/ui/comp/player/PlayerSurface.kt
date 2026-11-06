package soko.ekibun.acg.ui.comp.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import soko.ekibun.ffmpeg.SurfaceContext
import soko.ekibun.ffmpeg.VideoSurface

/** 「已跳过广告」提示的停留时长。 */
private const val AD_SKIP_NOTICE_MS = 5_000L

/** 播放画面：只画视频与跳过提示，不含控制条。宽高比与居中自己管，占多大由 [modifier] 决定。 */
@Composable
fun PlayerSurface(
  controller: PlayerController,
  modifier: Modifier = Modifier,
) {
  val state by controller.state.collectAsState()
  val scope = rememberCoroutineScope()
  val surface = remember { mutableStateOf<SurfaceContext?>(null) }

  // surface 是 VideoSurface 在布局阶段回填的；到了才能建播放器
  LaunchedEffect(surface.value) {
    val context = surface.value ?: return@LaunchedEffect
    controller.attachSurface(context)
  }

  LaunchedEffect(state.adSkip) {
    val pending = state.adSkip ?: return@LaunchedEffect
    delay(AD_SKIP_NOTICE_MS)
    controller.clearAdSkip(pending)
  }

  Box(modifier, contentAlignment = Alignment.Center) {
    // 提示挂在画面本身而不是外层留白上：letterbox 时它才贴在视频左下角
    Box(Modifier.aspectRatio(surface.value?.aspectRatio ?: 1f)) {
      VideoSurface(
        modifier = Modifier.matchParentSize(),
        onSurfaceContext = { surface.value = it },
      )
      AdSkipNoticeOverlay(
        notice = state.adSkip,
        onAction = { scope.launch { controller.toggleAdSkip() } },
        modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
      )
    }
  }
}

/**
 * 左下角的跳过提示：告诉用户这里少了一段，并给出反方向的入口。
 *
 * 淡入淡出单独封一层：直接写 `AnimatedVisibility` 会被外层布局作用域那个同名扩展抢走
 * （`@LayoutScopeMarker` 下用不了隐式接收者）。
 */
@Composable
private fun AdSkipNoticeOverlay(
  notice: AdSkip?,
  onAction: () -> Unit,
  modifier: Modifier = Modifier,
) {
  // 退场时 notice 已是 null：靠这份快照留住最后一次的内容，标签就不在淡出时翻回上一种状态
  val shown = remember { mutableStateOf<AdSkip?>(null) }
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
