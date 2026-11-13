/*
 * 青科助手（修改版） —— 基于开源项目 QUST-Assistant 修改而成
 *
 * 原项目版权所有 (C) 2891954521  (https://github.com/2891954521/QUST-Assistant)
 * 原始开源许可: GNU General Public License v3.0 (GPL-3.0), 许可原文见 LICENSE 文件, 保持完整未改动。
 *
 * 修改版版权所有 (C) 2026 zhangzhenzyc  (https://github.com/zhangzhenzyc)
 * 本修改版同样以 GPL-3.0 发布; 附加版权声明见 NOTICE.md —— 两份版权声明相互独立、同时生效。
 */

package com.qust.helper.ui.page

import android.os.Bundle
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material3.DrawerState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.qust.helper.data.Data
import com.qust.helper.ui.activity.MainActivity
import com.qust.helper.ui.theme.TEXT_COLORS
import com.qust.helper.ui.theme.rectPressFeedback
import com.qust.helper.ui.theme.colorSecondaryText
import com.qust.helper.ui.widget.AppWidgets
import com.qust.helper.ui.page.lesson.ScheduleAdjust
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

object HomePage {

	/** 底部导航栏高度: 与 Material2 `BottomNavigation` 的 56dp 保持一致(手动固定, 不能被内容撑高) */
	private val BOTTOM_BAR_HEIGHT = 56.dp

	@OptIn(ExperimentalFoundationApi::class)
	@Composable
	fun HomePage(
		activity: MainActivity,
		scope: CoroutineScope,
		drawerState: DrawerState,
		navController: NavController,
		/** 【第三十一轮】外部(桌面小组件)要求直接打开的底部页签下标, -1 表示没有请求 */
		pendingTab: Int = -1,
		/** 切完之后通知调用方把请求清掉, 这样下次点同一个页签还能再切一次 */
		onTabConsumed: () -> Unit = {}
	) {

		val pagerState = rememberPagerState(initialPage = 0, pageCount = { Data.bottomPage.size })

		// 【第三十一轮】点「学期课表」小组件 -> 直接落在这个页签上(而不是另开一个带返回箭头的单页)
		LaunchedEffect(pendingTab) {
			if(pendingTab in Data.bottomPage.indices) {
				pagerState.scrollToPage(pendingTab)
				onTabConsumed()
			}
		}

		Scaffold(
			// 【第三十三轮】透明底: 让外层那层"柔光玻璃背景光"透上来(底栏/顶栏仍是白面板)
			containerColor = Color.Transparent,
			topBar = {
				AppWidgets.TopBar(title = Data.bottomPage[pagerState.currentPage].name, navigationIcon = Icons.Rounded.Menu) {
					scope.launch { drawerState.open() }
				}
			},
			bottomBar = {
				Column(Modifier.fillMaxWidth()) {
					// 【第三十轮·修"底栏占满整屏/内容全空"】原来这里用的是 M2 的 `BottomNavigation`。
					// 反编译 material 1.6.5 确认: 它的 Row 用的是 **defaultMinSize(minHeight = 56dp)**
					// 而不是固定高度 —— 也就是说子项拿到的 maxHeight 是"整个 Scaffold 的剩余高度"。
					// 一旦子项里写 `fillMaxHeight()`(不管是 BottomNavigationItem 内部还是我们自己写),
					// 就会被撑成一整屏: 底栏吃掉全部高度 → 顶栏和内容都被压在下面/高度为 0,
					// 表现就是"整页空白, 只有两个图标挂在屏幕中间"。
					// 现在改成自己画这条栏: 高度显式写成 56dp, 子项再 fillMaxHeight 也只会在 56dp 内。
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.height(BOTTOM_BAR_HEIGHT)
							.background(MaterialTheme.colorScheme.surfaceVariant)
					) {
						Data.bottomPage.forEachIndexed { i, page ->
							// 【第三十轮·按要求】底部「学期课表」「我」两个白块不再用 BottomNavigationItem:
							// 它内部是有界 ripple —— 从手指处扩散的**圆形**, 铺在长方形区域里看着就是
							// "带倒角的一团光"。这里换成自绘的整块矩形按压层(见 rectPressFeedback),
							// 按下时整块均匀加深, 四个角是标准的直角。
							Box(
								modifier = Modifier
									.weight(1F)
									.fillMaxHeight()
									.rectPressFeedback {
										// 【修改版】保留翻页动画(短时长), 动画期间不接受手势 -> 丝滑且不会滑过头
										scope.launch {
											pagerState.animateScrollToPage(
												i,
												animationSpec = androidx.compose.animation.core.tween(durationMillis = 280)
											)
										}
									},
								contentAlignment = Alignment.Center
							) {
								Column(
									modifier = Modifier.padding(top = 4.dp),
									horizontalAlignment = Alignment.CenterHorizontally
								) {
									Icon(
										painter = if(page.image != null) rememberVectorPainter(page.image) else painterResource(id = page.iconRes),
										contentDescription = page.name,
										tint = Color(TEXT_COLORS[i % (TEXT_COLORS.size - 1) + 1])
									)
									Text(
										page.name,
										modifier = Modifier.padding(top = 4.dp),
										color = if(pagerState.currentPage == i) Color(TEXT_COLORS[i % (TEXT_COLORS.size - 1) + 1])
										else colorSecondaryText
									)
								}
							}
						}
					}
					Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
				}
			},
		) { padding ->
			HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { index ->
			Data.bottomPage[index].content(activity, padding, navController, Bundle.EMPTY)
		}
		ScheduleAdjust.EASLaunchPrompt(activity)
		// 【第二十二轮】考试前一天提醒: 明天有考试则弹窗(来源=教务通知)
		ScheduleAdjust.ExamReminderPrompt()
	}
}

}