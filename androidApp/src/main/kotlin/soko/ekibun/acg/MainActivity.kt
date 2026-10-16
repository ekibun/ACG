package soko.ekibun.acg

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import soko.ekibun.setHostApplicationContext

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)

    // 平台层（WebView、外部浏览器）要的进程级 Context 在这里注入一次 —— 它们的调用点跑在
    // JsEngine 的协程里、没有组合上下文，漏了就是第一次用的时候 lateinit 炸。
    setHostApplicationContext(this)

    setContent {
      App()
    }
  }
}

@Preview
@Composable
private fun AppAndroidPreview() {
  App()
}
