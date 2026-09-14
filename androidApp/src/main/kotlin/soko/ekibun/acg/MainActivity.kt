package soko.ekibun.acg

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import soko.ekibun.acg.web.BackgroundWebViewHost

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            App()
            // 插件 JS 的「后台 WebView」在 Android 上不需要宿主窗口，
            // 但建 WebView 要用 Context —— 这里顺手把它记下来。
            BackgroundWebViewHost()
        }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}