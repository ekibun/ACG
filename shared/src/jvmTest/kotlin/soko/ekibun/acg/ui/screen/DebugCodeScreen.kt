package soko.ekibun.acg.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch
import soko.ekibun.acg.engine.JsEngine
import soko.ekibun.acg.ui.comp.highlight
import soko.ekibun.acg.ui.comp.webview.WebviewTaskHost
import soko.ekibun.acg.ui.debugApplication
import soko.ekibun.quickjs.JSError

// 脚本控制台验证窗口，带 @Preview；按误报关掉 preview-public-check。
@Suppress("ktlint:compose:preview-public-check")
@Preview(showBackground = true)
@Composable
fun DebugCodeScreen() {
  var text by remember { mutableStateOf(TextFieldValue(highlight(SAMPLE_BACKGROUND))) }
  var evalval by remember { mutableStateOf("") }
  val scope = rememberCoroutineScope()

  Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
      TextField(
        value = text,
        onValueChange = {
          text = TextFieldValue(highlight(it.text), it.selection, it.composition)
        },
        modifier =
          Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .weight(1f),
      )
      Row {
        Button(onClick = {
          scope.launch {
            evalval =
              try {
                val ret = JsEngine.instance.evaluate(text.text)
                if (ret is Deferred<*>) ret.await() else ret
              } catch (e: JSError) {
                e
              }.toString()
          }
        }) { Text("Run>") }

        // 两个现成的例子：一个后台跑、一个直接铺到前台
        TextButton(onClick = { text = TextFieldValue(highlight(SAMPLE_BACKGROUND)) }) { Text("示例：后台") }
        TextButton(onClick = { text = TextFieldValue(highlight(SAMPLE_FOREGROUND)) }) { Text("示例：前台") }
      }
      Text(
        text = evalval,
        modifier =
          Modifier
            .padding(10.dp)
            .verticalScroll(rememberScrollState())
            .weight(1f),
      )
    }

    // 插件发起的 WebView 任务：缩成右下角胶囊，点开可切前台。与内容平级，后台时点击才穿得过去
    WebviewTaskHost()
  }
}

/** 后台抓一页：任务缩在右下角，不挡当前页。 */
private const val SAMPLE_BACKGROUND = """// 后台跑：任务缩成右下角一个「1 个任务在后台」的胶囊，不挡当前页面。
// 页面每次加载完成都执行一次脚本，返回非 null 就把结果交回来；任务结束后胶囊自己消失。
// 这里是求值，不能写顶层 await，所以包一层 async IIFE。
(async () => {
  const page = await webview(
    "https://bgm.tv/",
    {},
    "JSON.stringify({ title: document.title, links: document.querySelectorAll('a').length })"
  );
  return page;
})()"""

/** 直接铺到前台：适合登录、人机验证这类要人盯着点的页面。 */
private const val SAMPLE_FOREGROUND = """// 第五个参数为 true：创建即前台，整块界面换成这个页面。
// 没登录时停在登录页（脚本返回 null 就继续等），登录跳转后再跑一遍脚本才收工。
// 顶部「后台」= 收起并开始 30 秒倒计时，到点强制关闭；「关闭」= 立刻结束。
(async () => {
  const page = await webview(
    "https://bgm.tv/login",
    {},
    "JSON.stringify(document.querySelector('input[name=password]') ? null : { url: location.href })",
    null,
    true
  );
  return page;
})()"""

// 手动验证入口：人肉敲插件 JS 的控制台。
fun main() {
  debugApplication("脚本") { DebugCodeScreen() }
}
