package soko.ekibun.acg.ui.comp

import androidx.collection.LruCache
import androidx.collection.lruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.layout.ContentScale
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import soko.ekibun.acg.common.Http

/**
 * 已解码的图缓存：**进程级**，按 URL 存 [ImageBitmap]。
 *
 * 预算按**解码后**的字节算（`宽 × 高 × 4`）而不是下载的字节数 —— 吃内存的是解码位图。
 * 8 MiB ≈ 150 张 100px 封面（bgm 的 `grid` 档约 100×140，`×4` 是每像素 4 字节 ≈ 56 KB），
 * 一个列表来回滚不重下，又不会随「切了 25 种筛选」累积成几百 MB。
 */
private const val IMAGE_CACHE_BYTES: Int = 8 * 1024 * 1024

/**
 * 加这层是因为 `LazyColumn` 滚出去的节点会被销毁、滚回来重建：没有缓存就是每滚一轮重下一遍。
 * [LruCache] 内部有锁，跨线程安全。
 */
private val imageCache: LruCache<String, ImageBitmap> =
  // sizeOf 必须**具名**传：`lruCache` 的末位参数是 onEntryRemoved，尾随 lambda 会绑错位置。
  lruCache(IMAGE_CACHE_BYTES, sizeOf = { _, image -> image.width * image.height * 4 })

/**
 * 网络图：按 [url] 拉字节再解码成 [ImageBitmap]。
 *
 * 走 [Http] 而不是各平台的图片库：共享层没有平台符号，而 `ByteArray.decodeToImageBitmap()`
 * 两端各有 `actual`（Android 侧 `BitmapFactory`、桌面侧 skiko），是本层唯一能用的解码入口。
 *
 * 没拿到图（[url] 为 null、下载失败、字节不是图）就画一块占位底色，**不**向外抛 ——
 * 列表里的一张封面不值得把整页打不开。
 */
@Composable
fun RemoteImage(
  url: String?,
  contentDescription: String?,
  modifier: Modifier = Modifier,
  contentScale: ContentScale = ContentScale.Crop,
) {
  // 用 `remember(url)` 而不是 `produceState`：后者的 `remember` **不带 key**，
  // 换 url 时上一条的图会留在原处显示，等新图到了才被替换 —— 列表里就是「张冠李戴」。
  var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
  LaunchedEffect(url) { bitmap = url?.let { fetchImageBitmap(it) } }

  val loaded = bitmap
  if (loaded == null) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))
  } else {
    Image(
      bitmap = loaded,
      contentDescription = contentDescription,
      modifier = modifier,
      contentScale = contentScale,
    )
  }
}

private suspend fun fetchImageBitmap(url: String): ImageBitmap? {
  imageCache[url]?.let { return it }
  val loaded =
    try {
      // 要 `redirect = "follow"`：[Http.request] 缺省用**不跟随**的那个客户端，
      // 而这里换到的是 API 给的图床地址、随时可能被跳到别处 —— 拿到 302 的响应体解码必失败。
      // （bgm 现在的图床是直连 200，见 `docs/design/bgm-plugin.md`；这条是防跳。）
      Http.request(mapOf("url" to url, "redirect" to "follow")).use { response ->
        if (!response.delegate.status.isSuccess()) {
          null
        } else {
          response.readAll().decodeToImageBitmap()
        }
      }
    } catch (cancel: CancellationException) {
      // 取消 ≠ 加载失败：吞掉它会让 `LaunchedEffect` 的取消失效，界面停在假的「已加载」上。
      throw cancel
    } catch (_: Exception) {
      null
    }
  // **只记成功的**：一次瞬时失败不该粘住，否则那张图在这一进程里永远画不出来。
  loaded?.let { imageCache.put(url, it) }
  return loaded
}
