package soko.ekibun.acg.ui.comp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 头像：有地址就画图，没有就用名字首字母；两种形态同尺寸，免得列表里高低不一。 */
@Composable
fun Avatar(
  name: String,
  url: String?,
  modifier: Modifier = Modifier,
  size: Dp = 24.dp,
) {
  if (url == null) {
    Box(
      modifier
        .size(size)
        .clip(CircleShape)
        .background(MaterialTheme.colorScheme.primaryContainer),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        name.take(1).uppercase(),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
      )
    }
  } else {
    RemoteImage(
      url = url,
      contentDescription = name,
      modifier = modifier.size(size).clip(CircleShape),
    )
  }
}
