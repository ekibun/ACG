package soko.ekibun.acg

import com.materialkolor.hct.Hct
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import soko.ekibun.acg.ui.comp.BASE_TONE
import soko.ekibun.acg.ui.comp.maxChromaAt
import soko.ekibun.acg.ui.comp.vividTone
import java.io.File
import kotlin.test.Ignore
import kotlin.test.Test

/** 相邻色相的最优明度会跳档，取一圈滑动平均，免得图上一列亮一列暗。 */
private fun smoothTones(tones: List<Double>): List<Double> {
  val radius = 4
  return tones.indices.map { index ->
    (-radius..radius).map { offset -> tones[(index + offset).mod(tones.size)] }.average()
  }
}

/**
 * 手动跑：重新生成取色器那张色板。
 *
 * 图的坐标轴（色相 × 彩度）与参数都是常量，所以直接烘成一张 PNG 当资源用，
 * 运行时不必再算、也不必为写像素引平台原语。改了色域算法就重跑一次，产物提交进仓库。
 */
class PaletteImageTest {
  @Ignore("手动跑：只有改了色域算法才需要重新生成色板")
  @Test
  fun regenerate() {
    val cols = 120
    val rows = 56

    // 网格：横轴色相、纵轴彩度，明度从最鲜艳档退回中性灰
    val tones = smoothTones(List(cols) { vividTone(it * 360f / cols) })
    val grid = DoubleArray(cols * rows * 3)
    for (col in 0 until cols) {
      val hue = col * 360.0 / cols
      val tone = tones[col]
      val max = maxChromaAt(hue.toFloat(), tone)
      for (row in 0 until rows) {
        val ratio = 1.0 - row.toDouble() / (rows - 1)
        val argb = Hct.from(hue, max * ratio, tone + (BASE_TONE - tone) * (1.0 - ratio)).toInt()
        val at = (row * cols + col) * 3
        grid[at] = (argb shr 16 and 0xFF).toDouble()
        grid[at + 1] = (argb shr 8 and 0xFF).toDouble()
        grid[at + 2] = (argb and 0xFF).toDouble()
      }
    }

    // 不做插值：运行时 Image 放大时会双线性过滤，渐变图不需要预放大
    val outW = cols
    val outH = rows
    val bytes = ByteArray(outW * outH * 4)
    for (i in 0 until outW * outH) {
      val at = i * 4
      bytes[at] = grid[i * 3].toInt().toByte()
      bytes[at + 1] = grid[i * 3 + 1].toInt().toByte()
      bytes[at + 2] = grid[i * 3 + 2].toInt().toByte()
      bytes[at + 3] = 0xFF.toByte()
    }

    val bitmap = Bitmap()
    bitmap.allocPixels(ImageInfo(outW, outH, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL))
    bitmap.installPixels(bytes)
    val png = Image.makeFromBitmap(bitmap).encodeToData(EncodedImageFormat.PNG)!!.bytes

    val target =
      File("src/commonMain/composeResources/drawable/hue_chroma.png")
    target.writeBytes(png)
  }
}
