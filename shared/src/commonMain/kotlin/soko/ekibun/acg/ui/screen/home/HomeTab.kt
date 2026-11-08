package soko.ekibun.acg.ui.screen.home

import acg.shared.generated.resources.Res
import acg.shared.generated.resources.ic_bookmark
import acg.shared.generated.resources.ic_calendar_month
import acg.shared.generated.resources.ic_forum
import acg.shared.generated.resources.ic_search
import org.jetbrains.compose.resources.DrawableResource

/**
 * 主页里的分段，不是导航目的地。
 *
 * 切 tab 只是换主页正文，不进栈、不产生返回，也不影响已打开的独立页面。
 * 账号与设置不在这个枚举里 —— 它们不是页面，由导航条自己摆。
 */
enum class HomeTab(
  val title: String,
  val icon: DrawableResource,
) {
  Collection("收藏", Res.drawable.ic_bookmark),
  Search("搜索", Res.drawable.ic_search),
  Timetable("时间表", Res.drawable.ic_calendar_month),
  Community("社区", Res.drawable.ic_forum),
}
