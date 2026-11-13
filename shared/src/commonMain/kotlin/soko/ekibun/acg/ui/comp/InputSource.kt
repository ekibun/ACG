package soko.ekibun.acg.ui.comp

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput

/**
 * 用户最近一次用的指针设备。全应用一份，由窗口根上的 [trackActiveInputSource] 写。
 *
 * 输入方式是运行时属性、不是平台属性：带触摸屏的 Windows 与接了鼠标的平板都在同一个人手里来回换，
 * 按平台写死只在一半场景里对。
 *
 * 只有 [latest] 一个字段，且**不进快照**：手势回调要的是「这一下」，快照得等重组才看得见，
 * 第一下就会被漏掉；要按它决定画什么，也只能读这个普通字段。
 */
class ActiveInputSource {
  /** 最近一次交互的设备类型；[PointerType.Stylus] / [PointerType.Eraser] 与手指一同记成 [PointerType.Touch]。 */
  var latest: PointerType = PointerType.Unknown
    private set

  internal fun note(type: PointerType) {
    latest =
      when (type) {
        PointerType.Stylus, PointerType.Eraser -> PointerType.Touch
        else -> type
      }
  }
}

/** 根上提供、根上跟踪；没有 provider 时（预览、测试）自己一份，等于「还没人碰过」，一律按不是手指算。 */
val LocalActiveInputSource = staticCompositionLocalOf { ActiveInputSource() }

/**
 * 观察指针类型，一个事件都不消费：挂在窗口根上，任何子组件拿到按下事件之前就能读到这一下是什么。
 *
 * 只认按下 / 移动 / 抬起 / 滚轮 —— 不认 Enter / Exit：手指按着时鼠标指针还停在窗口里，那两种事件
 * 照样带着 `Mouse` 到达，认它会把刚切过去的触摸又翻回鼠标。
 */
fun Modifier.trackActiveInputSource(source: ActiveInputSource): Modifier =
  pointerInput(source) {
    awaitPointerEventScope {
      while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        val interactive =
          event.type == PointerEventType.Press ||
            event.type == PointerEventType.Move ||
            event.type == PointerEventType.Release ||
            event.type == PointerEventType.Scroll
        if (interactive) {
          event.changes
            .firstOrNull()
            ?.type
            ?.let(source::note)
        }
      }
    }
  }
