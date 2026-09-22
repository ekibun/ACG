package soko.ekibun.ffmpeg

import soko.ekibun.Pointer
import soko.ekibun.jniLoadLibrary

class AvFrame(
  nativePtr: Long,
  val timeStamp: Long,
  val width: Int,
  val height: Int,
  /**
   * 解码器为这一帧报的错，来自 `AVFrame.decode_error_flags`（libavutil/frame.h）。
   *
   * 位与含义：`1` 码流非法 / `2` **参考帧缺失** / `4` 解码器在遮错（concealment）/
   * `8` 切片解码失败。**非 0 就是「画面出现花屏」的直接证据** —— 那种成块的花屏
   * 正是解码器拿不到参考帧、只能就地遮错的结果，与协程调度无关。
   *
   * 它是 2026-09-22 为定性「还有花屏」而加的探针字段：`FFPlayer` 在视频帧送显前
   * 发现非 0 会打一行 `DECODE_ERROR …`。定性完可以撤，撤的时候连同
   * `cxx/ffmpeg/ffmpeg.cpp` 的 `newAvFrame` 一起改。
   */
  val decodeErrorFlags: Int,
) : Pointer(nativePtr) {
  /** 标记该帧正在被哪个 PTS 播放轮次消费，避免同一帧被重复取用。 */
  var processing: FFPlayer.PTS? = null

  companion object {
    init {
      jniLoadLibrary("ffmpeg")
    }

    /**
     * 归还底层的 `AVFrame`。
     *
     * ⚠️ **JNI 名只看原生方法挂在哪个 class 文件上，不看 Kotlin 的可见性。** companion 成员
     * 不加 `@JvmStatic` 时，原生方法只挂在 `AvFrame$Companion` 上，JNI 名会多一段
     * `_00024Companion`，与 `cxx/ffmpeg/ffmpeg.cpp` 导出的
     * `Java_soko_ekibun_ffmpeg_AvFrame_closeNative` 对不上 —— `UnsatisfiedLinkError`
     * 只在**真的调**的时候才炸。本类曾经就是这样（2026-09-21 修），于是 `av_frame_free`
     * 从来没跑成过（一直没显形是因为正常播放时没人关帧：`FFPlayer` 只在「轮次已切换」
     * 「drain 出空」这类边角分支上关，错名一路潜伏到归还点补全之后才炸出来）。
     *
     * ⇒ 本包约定：`external fun` 放 companion 里，**并加 `@JvmStatic`**。
     *
     * ⚠️ 但 `@JvmStatic` 还会把第二个 JNI 实参从**实例**换成**类对象**，native 里用 `thiz`
     * 的就会坏 —— 全包只有 [AvFormat.initNative] 是那种，它因此留在类体里当实例成员
     * （JNI 名同样是外层类名，两全）。改本包的原生绑定时，这两条要一起看。
     */
    @JvmStatic
    external fun closeNative(ptr: Long)
  }

  /**
   * 一次性出场守卫交给基类（[Pointer.markClosed]）—— 本类不再自带
   * `AtomicBoolean`。守卫必须是原子的这条要求没变：[FFPlayer] 在 `decoded` 与
   * `drained` 两处遍历关帧，同一帧可能被关两次，而 native 的 `closeNative` 不可重入。
   *
   * 本类**不设 GC 兜底**（原先用 `java.lang.ref.Cleaner`，已移除）：Android 上
   * `java.lang.ref.Cleaner` 是 **API 33** 才有的类，本工程 `minSdk = 24` 且没开
   * core library desugaring，而它原先挂在 companion 字段上 —— 类加载即
   * `NoClassDefFoundError`。代价是漏关就真漏一帧 native 内存，所以 [FFPlayer]
   * 的关闭路径必须逐个清点（它也确实是这么做的）。
   */
  override suspend fun releaseImpl(ptr: Long) = closeNative(ptr)
}
