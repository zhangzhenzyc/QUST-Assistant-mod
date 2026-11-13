/*
 * 青科助手（修改版） —— 基于开源项目 QUST-Assistant 修改而成
 *
 * 原项目版权所有 (C) 2891954521  (https://github.com/2891954521/QUST-Assistant)
 * 原始开源许可: GNU General Public License v3.0 (GPL-3.0), 许可原文见 LICENSE 文件, 保持完整未改动。
 *
 * 修改版版权所有 (C) 2026 zhangzhenzyc  (https://github.com/zhangzhenzyc)
 * 本修改版同样以 GPL-3.0 发布; 附加版权声明见 NOTICE.md —— 两份版权声明相互独立、同时生效。
 */

package com.qust.helper.data

import android.os.Bundle
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavController
import com.qust.helper.ui.activity.BaseActivity

/**
 * App界面
 * @param key
 * @param name         显示的标题
 * @param iconRes      图标
 * @param image        图片类型的图标
 * @param enableDrawer 这个界面是否允许侧滑
 */
open class Page(
	val key: String,
	val name: String,
	val iconRes: Int = 0,
	val image: ImageVector? = null,
	val enableDrawer: Boolean = true,
	val arguments: List<NamedNavArgument> = emptyList(),
	val content: @Composable (BaseActivity, PaddingValues, NavController, Bundle?) -> Unit = { _, _, _, _ -> }
){
	companion object{
		/**
		 * 【本期】进入页面前的"改道"规则。
		 *
		 * 学校邮箱没有保存账号时, 直接把目标改成「学校邮箱登陆」。
		 * 以前是先进「学校邮箱」页、再由页内 effect 弹登录页 —— 等于**连续两次转场**:
		 * 邮箱页刚滑入就被登录页打断, 冷启动(App 刚起来、类还没 JIT 热)时表现就是
		 * "登录界面有时候不出现""动画一卡一卡的", 点十几次之后系统预热完了才恢复正常。
		 * 现在一次转场直达登录页, 登录成功后再进邮箱页。
		 */
		fun resolve(key: String): String {
			if(key == Keys.Page.MailPage) {
				val account = Setting.getString(Keys.MAIL_ACCOUNT)
				val password = Setting.getString(Keys.MAIL_PASSWORD)
				return if(account.isEmpty() || password.isEmpty()) Keys.Page.MailLogin else key
			}
			// 【第六十八轮·按要求】"点击『我』里面的饮水码, 未登录不要显示『请先登录』那个界面,
			// 直接显示饮水码登陆界面"。
			//
			// 以前是先进饮水码页(一张写着"请先登录"的大字 + 一个登录按钮), 再点按钮才到登录页 ——
			// 中间那一屏对用户没有任何信息量。现在和「学校邮箱」同一条路子:
			// **导航前用本地已保存的凭据判断一次**(不联网), 没登录就一次转场直达登录页。
			if(key == Keys.Page.DrinkPage && !hasDrinkAccount()) {
				return Keys.Page.DrinkLogin
			}
			// 【第三十一轮·按要求】「数字青科」栏目的 3 个功能, 没登录统一身份时**直接改道登录页**。
			//
			// 以前是先进功能页、再由页面里的 LaunchedEffect 等 WebView 检查出来"没登录"才导航,
			// 用户看到的就是"点了没反应 -> 过一会儿才弹登录页", 甚至检查没跑完就一直白屏。
			// 改成在导航前用**本地已保存的账号**判断(不联网), 和「学校邮箱」完全同一条路径: 一次转场直达。
			if(key in VPN_PAGES && !hasVpnAccount()) {
				LoginEntry.pendingVpnPage = key
				return Keys.Page.VpnLoginPage
			}
			return key
		}

		/**
		 * 【第六十八轮】饮水账号是不是已经登录过。
		 *
		 * 判据与「桌面小组件」页挡住"登录后添加"用的是同一套: 存过密码 **或** 手里已经有码。
		 * 两者任一成立就算登录过 —— 只看密码的话,"码还在、但密码被清掉"的用户会被反复扔去登录页。
		 */
		private fun hasDrinkAccount(): Boolean = runCatching {
			com.qust.helper.ui.page.account.AccountManagerPage.hasSavedPassword(Keys.DRINK_PASSWORD) ||
				com.qust.helper.model.account.DrinkAccount.getInstance().drinkCode.isNotEmpty()
		}.getOrDefault(false)

		/** 「数字青科」栏目下的 3 个功能页(数字青科 / 智慧体测 / 座位预约) */
		private val VPN_PAGES = setOf(
			Keys.Page.IpassWebPage,
			Keys.Page.SportTestPage,
			Keys.Page.SeatReservePage
		)

		/** 是否已经存过统一身份(数字青科)的账号密码 */
		private fun hasVpnAccount(): Boolean =
			runCatching { com.qust.helper.ui.page.account.AccountManagerPage.hasSavedPassword(Keys.IPASS_PASSWORD) }
				.getOrDefault(false)

		fun navigate(navController: NavController, key: String) {
			val target = resolve(key)
			if(navController.currentDestination?.route != target) {
				navController.navigate(target) {
					popUpTo(target) { inclusive = true }
					launchSingleTop = true
				}
			}
		}
	}

}

sealed interface DrawerPageGroup {

	class Page(val name: String): DrawerPageGroup

	class PageGroup(val name: String, val iconRes: Int = 0, val image: ImageVector? = null, val pages: Array<String>): DrawerPageGroup {
		val expand = mutableStateOf(false)
	}
}

/**
 * 【第六十二轮】页面图标的着色口径(只影响颜色, 不影响功能):
 * - [OWN_COLOR] 里的页面图标是**多色矢量**, 使用自身颜色(不做 tint);
 * - [tintOf] 给个别单色图标指定固定颜色(教务查询设置=蓝, 设置=灰);
 * - 其余页面继续使用原来的循环调色板。
 */
object PageIconStyle {

	/** 图标自带颜色(多色)的页面 */
	val OWN_COLOR: Set<String> = setOf(
		Keys.Page.GetLessonPage,
		Keys.Page.EasWebPage,
		Keys.Page.GetMarksPage,
		Keys.Page.GetAcademicPage,
		Keys.Page.GetNoticePage,
		Keys.Page.GetExamsPage,
		Keys.Page.XsxkWebPage,
		Keys.Page.IpassWebPage,
		Keys.Page.SportTestPage,
		Keys.Page.SeatReservePage,
		Keys.Page.DrinkPage,
		Keys.Page.OfficialSitePage,
		Keys.Page.MailPage,
		// 【第六十八轮·二】「小组件」的淡蓝卡片图标是四层多色的, 不能被调色板 tint 成单色
		Keys.Page.WidgetPage
	)

	/** 单色图标的固定 tint 颜色 */
	fun tintOf(key: String): androidx.compose.ui.graphics.Color? = when(key){
		Keys.Page.EasSettingPage -> androidx.compose.ui.graphics.Color(0xFF1976D2)
		// 【修改版】教务通知/考试查询: 蓝色
		Keys.Page.GetNoticePage, Keys.Page.GetExamsPage -> androidx.compose.ui.graphics.Color(0xFF1976D2)
		// 【第五十四轮·按要求】设置图标改成蓝色(原为深蓝 0xFF0D47A1); colorOf 同时供侧边栏与"我"页面取色, 两处自动同步
		Keys.Page.SettingPage -> androidx.compose.ui.graphics.Color(0xFF1976D2)
		else -> null
	}

	/**
	 * 【第七十四轮】页面的**固定**图标颜色: 侧边栏与"我"页面都用它, 保证同一页面两处颜色完全一致。
	 * 之前两处各自按"所在位置"从调色板取色, 同一图标在侧边栏和"我"里颜色不一样。
	 */
	fun colorOf(key: String, palette: IntArray): androidx.compose.ui.graphics.Color {
		tintOf(key)?.let { return it }
		val n = palette.size - 1
		val idx = ((key.hashCode() % n) + n) % n + 1
		return androidx.compose.ui.graphics.Color(palette[idx])
	}
}