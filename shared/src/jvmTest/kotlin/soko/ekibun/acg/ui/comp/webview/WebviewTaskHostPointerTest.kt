package soko.ekibun.acg.ui.comp.webview

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import soko.ekibun.acg.engine.WebviewTask
import soko.ekibun.acg.engine.WebviewTasks
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 覆盖层与 App 是兄弟节点：前台时接住点击，后台时整块放行。
 *
 * 场景必须按兄弟摆——套成父子的话点击会冒泡给父节点，量到的就不是「穿透」。
 */
class WebviewTaskHostPointerTest {
  private fun clickAt(
    scene: ImageComposeScene,
    y: Float,
  ) {
    scene.sendPointerEvent(PointerEventType.Press, Offset(200f, y))
    scene.sendPointerEvent(PointerEventType.Release, Offset(200f, y))
  }

  @Test
  fun foregroundSwallowsTheClickButBackgroundDoesNot() {
    val task = WebviewTask("https://bgm.tv/login")
    WebviewTasks.register(task)
    try {
      var behind = 0
      val scene =
        ImageComposeScene(width = 420, height = 200) {
          MaterialTheme {
            // 与 App 同构：覆盖层和页面是兄弟，不是父子（父子会把点击冒泡给父节点）
            Box(Modifier.fillMaxSize().background(Color.Red).clickable { behind++ })
            WebviewTaskHost()
          }
        }
      scene.render(0)

      // 后台：只有右下角胶囊，其它地方应当点得到下面的 App
      clickAt(scene, y = 60f)
      scene.render(0)
      assertEquals(1, behind, "backgrounded overlay must not eat clicks")

      WebviewTasks.toForeground(task)
      scene.render(0)
      // 前台：顶部这行是覆盖层画的，点了不能漏到下面去
      clickAt(scene, y = 20f)
      scene.render(0)
      assertEquals(1, behind, "the foreground overlay must swallow clicks over the top bar")
    } finally {
      WebviewTasks.unregister(task)
    }
  }
}
