package soko.ekibun.acg

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import kotlinx.coroutines.runBlocking
import soko.ekibun.acg.data.loadRepositories
import soko.ekibun.common.Paths
import soko.ekibun.setHostApplicationContext

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)

    // 平台层（WebView、外部浏览器）要的进程级 Context 在这里注入一次 —— 它们的调用点跑在
    // JsEngine 的协程里、没有组合上下文，漏了就是第一次用的时候 lateinit 炸。
    setHostApplicationContext(this)

    // 数据根同样在这里注入一次。`filesDir` / `cacheDir` 已按 applicationId 隔离，不叠应用名。
    Paths.init(
      Paths(
        dataDir = filesDir.absolutePath,
        cacheDir = cacheDir.absolutePath,
      ),
    )

    // 首帧之前把设置与账号读出来：主题、头像都要跟着磁盘走，否则会先渲染一帧默认值再跳
    runBlocking { loadRepositories() }

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
