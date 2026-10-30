package soko.ekibun.ffmpeg

import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ContentChangeMode
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin
import org.jetbrains.skiko.SkiaLayer
import java.awt.Container
import java.awt.Window
import java.lang.reflect.Field
import java.lang.reflect.Method

/** 从 Compose 窗口里找出承载画布的那层 [SkiaLayer]。 */
fun Window.findSkiaLayer(): SkiaLayer? {
  fun find(container: Container): SkiaLayer? {
    val children = container.components.asSequence()
    return children.filterIsInstance<SkiaLayer>().firstOrNull()
      ?: children.filterIsInstance<Container>().mapNotNull(::find).firstOrNull()
  }
  return find(this)
}

/**
 * 反射读 Skiko 的渲染器：它的 Skia `DirectContext` 与原生 `DirectXDevice` 指针。
 *
 * 两者都不在公开 API 里。`SkiaLayer` 的 redrawer 会在渲染后端回退、显示切换时被重建，
 * 所以每次使用都重新读，只在类级别缓存字段句柄。仅支持 Direct3D 后端
 * （`Direct3DRedrawer` / `Direct3DRenderer`）。
 */
internal class SkiaRenderAccess {
  private val redrawerGetter: Method? =
    SkiaLayer::class.java.methods.firstOrNull { it.name == "getRedrawer\$skiko" }

  private var cachedClass: Class<*>? = null
  private var deviceField: Field? = null
  private var handlerField: Field? = null
  private var contextField: Field? = null

  private fun redrawer(layer: SkiaLayer): Any? = redrawerGetter?.invoke(layer)

  /** Skiko 的 DirectXDevice（D3D12）指针；后端不是 Direct3D 时为 0。 */
  fun devicePtr(layer: SkiaLayer): Long {
    val redrawer = redrawer(layer) ?: return 0
    prepare(redrawer.javaClass) ?: return 0
    return runCatching { deviceField?.getLong(redrawer) ?: 0 }.getOrDefault(0)
  }

  /** 画布正在用的 Skia GPU 上下文；窗口画第一帧之前为 null。 */
  fun directContext(layer: SkiaLayer): DirectContext? {
    val redrawer = redrawer(layer) ?: return null
    prepare(redrawer.javaClass) ?: return null
    val handler = handlerField?.let { runCatching { it.get(redrawer) }.getOrNull() } ?: return null
    return contextField?.let { runCatching { it.get(handler) }.getOrNull() } as? DirectContext
  }

  private fun prepare(clazz: Class<*>): Unit? {
    if (cachedClass === clazz) return Unit
    if (clazz.name != "org.jetbrains.skiko.redrawer.Direct3DRedrawer" &&
      clazz.name != "org.jetbrains.skiko.renderer.Direct3DRenderer"
    ) {
      return null
    }
    val device = declaredField(clazz, "device") ?: return null
    if (device.type != java.lang.Long.TYPE) return null
    val handler = declaredField(clazz, "contextHandler")
    val context =
      if (handler != null) {
        declaredField(handler.type, "context")
      } else {
        declaredField(clazz, "context")
      }
    if (context == null || !DirectContext::class.java.isAssignableFrom(context.type)) return null
    cachedClass = clazz
    deviceField = device
    handlerField = handler
    contextField = context
    return Unit
  }

  private fun declaredField(
    clazz: Class<*>,
    name: String,
  ): Field? {
    var current: Class<*>? = clazz
    while (current != null) {
      runCatching { current.getDeclaredField(name) }.getOrNull()?.let {
        it.isAccessible = true
        return it
      }
      current = current.superclass
    }
    return null
  }
}

/**
 * 把 native 共享纹理画成 Skia GPU 图像：纹理是 Skia 设备上打开的 `ID3D12Resource`，
 * 包成渲染目标后先 blit 到自己的 GPU surface（Skia 不拥有外部纹理，不能直接快照），
 * 再快照成可画的 [Image]。全程只有 GPU 之间的一次拷贝，不经过 CPU。
 */
internal class HwFrameRenderer(
  private val layer: SkiaLayer,
) : AutoCloseable {
  private val access = SkiaRenderAccess()
  private val targets = arrayOfNulls<BackendRenderTarget>(HwVideoSink.BUFFER_COUNT)
  private val surfaces = arrayOfNulls<Surface>(HwVideoSink.BUFFER_COUNT)
  private var blit: Surface? = null
  private var wrappedGeneration = -1
  private var wrappedContext: DirectContext? = null
  private var cached: Image? = null
  private var cachedSerial = -1

  /** Skiko 的 DirectX 设备指针；渲染后端不是 Direct3D 时为 0。 */
  fun devicePtr(): Long = access.devicePtr(layer)

  /** 当前可画的图像；没有可共享的画布上下文或还没有帧时为 null。 */
  fun image(sink: HwVideoSink): Image? {
    val context = access.directContext(layer) ?: return null
    val state = sink.frameState()
    if (!state.hasFrame) return cached
    if (state.serial == cachedSerial && cached != null) return cached
    if (state.generation != wrappedGeneration ||
      wrappedContext !== context ||
      blit?.width != state.width ||
      blit?.height != state.height
    ) {
      if (!rewrap(sink, state, context)) return cached
    }
    val source = surfaces[state.index] ?: return cached
    val target = blit ?: return cached
    // 内容由 D3D11 一侧写入：不声明的话 Skia 会复用上一张快照，画面停在第一帧。
    source.notifyContentWillChange(ContentChangeMode.DISCARD)
    source.draw(target.canvas, 0, 0, null)
    cached?.close()
    cached = target.makeImageSnapshot()
    cachedSerial = state.serial
    return cached
  }

  private fun rewrap(
    sink: HwVideoSink,
    state: HwVideoSink.FrameState,
    context: DirectContext,
  ): Boolean {
    closeWraps()
    for (index in 0 until HwVideoSink.BUFFER_COUNT) {
      val texture = sink.bufferTexture(index)
      if (texture == 0L) {
        closeWraps()
        return false
      }
      val target =
        BackendRenderTarget.makeDirect3D(
          width = state.width,
          height = state.height,
          texturePtr = texture,
          format = HwVideoSink.DXGI_FORMAT_R8G8B8A8_UNORM,
          sampleCnt = 1,
          levelCnt = 1,
        )
      val surface =
        Surface.makeFromBackendRenderTarget(
          context = context,
          rt = target,
          origin = SurfaceOrigin.TOP_LEFT,
          colorFormat = SurfaceColorFormat.RGBA_8888,
          colorSpace = ColorSpace.sRGB,
        )
      if (surface == null) {
        target.close()
        closeWraps()
        return false
      }
      targets[index] = target
      surfaces[index] = surface
    }
    blit = Surface.makeRenderTarget(context, false, ImageInfo.makeN32Premul(state.width, state.height))
    wrappedGeneration = state.generation
    wrappedContext = context
    return true
  }

  private fun closeWraps() {
    surfaces.forEach { it?.close() }
    surfaces.fill(null)
    targets.forEach { it?.close() }
    targets.fill(null)
    blit?.close()
    blit = null
    wrappedGeneration = -1
    wrappedContext = null
  }

  override fun close() {
    cached?.close()
    cached = null
    cachedSerial = -1
    closeWraps()
  }
}
