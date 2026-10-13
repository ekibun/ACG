package soko.ekibun.acg

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import soko.ekibun.web.setWebviewApplicationContext

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)

    // WebView 的平台层要一个进程级 Context（后台任务跑在 JsEngine 里、没有组合上下文），
    // 必须在任何 WebView 建起来之前注入 —— 漏了就是第一次建 WebView 时 lateinit 炸。
    setWebviewApplicationContext(this)

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
