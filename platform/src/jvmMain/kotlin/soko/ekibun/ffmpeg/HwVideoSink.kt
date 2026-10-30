package soko.ekibun.ffmpeg

import soko.ekibun.loadLibrary

/**
 * 桌面零拷贝输出端（仅 Windows）：native 在同一块 GPU 上把硬解纹理转成 RGBA，写进 NT 共享
 * 纹理环；Skia 的 D3D12 设备打开同一块显存，于是视频成为一次普通 draw call，像素不落 CPU。
 *
 * 创建失败（Skiko 不是 Direct3D 后端、设备不可共享）返回 `null`，调用方退回逐帧拷贝的
 * [Image.makeRaster] 路径。
 */
class HwVideoSink private constructor(
  private val handle: Long,
) : AutoCloseable {
  companion object {
    init {
      loadLibrary("ffmpeg")
    }

    /**
     * 中转纹理的张数，与 native 的 `kBufferCount` 一致。
     *
     * 帧本身带着解码器的 surface，这里只是 Skia 能画的 RGBA 中转：blit 和 Skia 的拷贝碰同一块
     * 显存、跨 API 没有 fence，所以要两张以上轮流用（实测画布最多落后 blit 1 帧）。
     */
    const val BUFFER_COUNT = 3

    /** DXGI_FORMAT_R8G8B8A8_UNORM：native 侧的共享纹理格式。 */
    const val DXGI_FORMAT_R8G8B8A8_UNORM = 28

    private const val NO_INDEX = 0xF

    @JvmStatic
    private external fun createNative(skikoDevicePtr: Long): Long

    @JvmStatic
    private external fun closeNative(handle: Long)

    @JvmStatic
    private external fun bufferTextureNative(
      handle: Long,
      index: Int,
    ): Long

    @JvmStatic
    private external fun frameStateNative(handle: Long): Long

    /** [skikoDevicePtr] 是 Skiko 原生 DirectXDevice 的指针，见 `SkiaRenderAccess`。 */
    fun create(skikoDevicePtr: Long): HwVideoSink? {
      if (skikoDevicePtr == 0L) return null
      val handle = createNative(skikoDevicePtr)
      return if (handle == 0L) null else HwVideoSink(handle)
    }
  }

  /**
   * 一帧在环里的位置。[serial] 是「被定为显示帧」的次数，画布据此判断要不要重画。
   */
  class FrameState(
    val generation: Int,
    val index: Int,
    val width: Int,
    val height: Int,
    val serial: Int,
  ) {
    val hasFrame: Boolean get() = index != NO_INDEX && width > 0 && height > 0
  }

  /** 读出当前该显示哪一帧；位域见 native 的 `packState`。 */
  fun frameState(): FrameState {
    val state = frameStateNative(handle)
    return FrameState(
      generation = ((state ushr 56) and 0xFF).toInt(),
      index = ((state ushr 52) and 0xF).toInt(),
      width = ((state ushr 38) and 0x3FFF).toInt(),
      height = ((state ushr 24) and 0x3FFF).toInt(),
      serial = (state and 0xFFFFFF).toInt(),
    )
  }

  /** 环里第 [index] 张纹理的 `ID3D12Resource*`；未就绪为 0。 */
  fun bufferTexture(index: Int): Long = bufferTextureNative(handle, index)

  internal val nativeHandle: Long get() = handle

  override fun close() = closeNative(handle)
}
