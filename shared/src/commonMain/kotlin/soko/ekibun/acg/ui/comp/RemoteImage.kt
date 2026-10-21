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
import kotlinx.coroutines.CancellationException
import soko.ekibun.common.Http

/** 已解码图片缓存的预算，按解码后字节（`宽 × 高 × 4`）计。 */
private const val IMAGE_CACHE_BYTES: Int = 8 * 1024 * 1024

/** 进程级图片缓存，按 URL 存已解码的 [ImageBitmap]；[LruCache] 内部有锁。 */
private val imageCache: LruCache<String, ImageBitmap> =
  // sizeOf 具名传参，缓存大小按解码后字节计
  lruCache(IMAGE_CACHE_BYTES, sizeOf = { _, image -> image.width * image.height * 4 })

/** 网络图：按 [url] 拉字节并解码成 [ImageBitmap]；没拿到就画一块占位底色，不向外抛。 */
@Composable
fun RemoteImage(
  url: String?,
  contentDescription: String?,
  modifier: Modifier = Modifier,
  contentScale: ContentScale = ContentScale.Crop,
) {
  // 按 url 重置状态，换 url 即重取
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
      // 跟随重定向
      Http.request(mapOf("url" to url, "redirect" to "follow")).use { response ->
        if (response.status !in 200..299) {
          null
        } else {
          response.readAll().decodeToImageBitmap()
        }
      }
    } catch (cancel: CancellationException) {
      // 取消原样抛出
      throw cancel
    } catch (_: Exception) {
      null
    }
  // 只缓存成功的解码结果
  loaded?.let { imageCache.put(url, it) }
  return loaded
}
