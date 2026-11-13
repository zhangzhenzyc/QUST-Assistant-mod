/*
 * 青科助手（修改版） —— 基于开源项目 QUST-Assistant 修改而成
 *
 * 原项目版权所有 (C) 2891954521  (https://github.com/2891954521/QUST-Assistant)
 * 原始开源许可: GNU General Public License v3.0 (GPL-3.0), 许可原文见 LICENSE 文件, 保持完整未改动。
 *
 * 修改版版权所有 (C) 2026 zhangzhenzyc  (https://github.com/zhangzhenzyc)
 * 本修改版同样以 GPL-3.0 发布; 附加版权声明见 NOTICE.md —— 两份版权声明相互独立、同时生效。
 */

package com.qust.helper.ui.widget

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import com.qust.helper.data.Keys
import com.qust.helper.data.Page
import com.qust.helper.viewmodel.eas.BaseEasViewModel

object AppWidgets{

	@OptIn(ExperimentalMaterial3Api::class)
	@Composable
	fun TopBar(
		title: String,
		navigationIcon: ImageVector? = null,
		navigationClick: () -> Unit = { },
	) {
		if(navigationIcon == null){
			TopAppBar(
				title = { TitleText(title = title) }
			)
		}else{
			TopAppBar(
				title = { TitleText(title = title) },
				navigationIcon = {
					BackIcon(icon = navigationIcon) {
						navigationClick()
					}
				}
			)
		}
	}

	@Composable
	fun TitleText(title: String){
		Text(
			text = title,
			modifier = Modifier.padding(16.dp),
			style = MaterialTheme.typography.titleLarge,
			maxLines = 1,
		)
	}

	@Composable
	fun BackIcon(icon: ImageVector, onClick: () -> Unit){
		Box(modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(32.dp)).clickable { onClick() }){
			Icon(
				imageVector = icon,
				contentDescription = "Back",
				modifier = Modifier.padding(8.dp)
			)
		}
	}

	@Composable
	fun DialogBar(dialogText: String){
		if(dialogText.isNotEmpty()) Dialogs.IndeterminateProgressDialog(dialogText)
	}

	@Composable
	fun CheckEasLogin(viewModel: BaseEasViewModel, navController: NavController){
		val context = LocalContext.current
		LaunchedEffect(viewModel.needLogin){
			if(viewModel.needLogin) {
				viewModel.leavingForLogin = true
				// 【第三十一轮·按要求】这里的调用方全部是「教务系统」栏目下的页面
				// (教务通知/成绩/学业/考试/课表查询/教务系统网页/自主选课)。
				//
				// 用户原话: "没登录教务系统和数字青科时, 点击教务系统为什么没有弹教务系统的登录界面,
				// 而是数字青科的登录界面"。上一版统一先弹「数字青科登陆」, 顺序是反的 ——
				// 现在点「教务系统」就弹「教务登陆」(学号 + 教务系统密码, 走教务系统原网址);
				// 那条路走不通时, 教务登录页仍会自动转去「数字青科登陆」(见 LoginPage.onNeedVpnLogin),
				// 两条通道都在, 只是不再抢在教务前面。
				//
				// 【一次只弹一个界面】needLogin 在多处会被置 true(页面入口判断、登录态校验、查询失败),
				// 同一瞬间触发两次就会连续压两个登录页进回退栈 —— 用户看到"出现两次登录界面"。
				// 这里加时间闸门 + 路由判断, 1.5 秒内且已经在登录页时不再重复导航。
				val cur = navController.currentBackStackEntry?.destination?.route
				val now = android.os.SystemClock.uptimeMillis()
				// 【本期】回退栈里已经有登录页时不再压第二层(同一次进入最多一个登录界面)
				val inStack = try {
					navController.currentBackStack.value.any {
						it.destination.route == Keys.Page.EasLogin || it.destination.route == Keys.Page.VpnLoginPage
					}
				} catch(_: Exception) { false }
				// 【第五十五轮·按要求】"教务系统无法正常进入时, 已经登录数字青科就自动走数字青科里的教务系统,
				// 没登录就显示账号管理的数字青科登录界面, 登录后自动进入里面的教务系统":
				//  · 已登录数字青科: 上面各页的 checkLogin() 已经自动走过"数字青科 → 教务管理系统"这条通道
				//    (它排在被拦掉的教务原网址之后), 走到这里说明那条也不通, 不再原地重复试;
				//  · 没登录数字青科: 直接把人带到「数字青科登陆」页(与账号管理里点"点击登录"是同一页),
				//    登录成功后会由 IPassAccountViewModel 自动把"数字青科里的教务系统"登进去。
				// 两个标记: autoFallbackToEas -> 登完继续进教务; fromVpnLogin = false -> 允许走数字青科通道。
				val ipassReady = runCatching {
					com.qust.helper.model.account.IPassAccount.getInstance().getCookie().isNotEmpty()
				}.getOrDefault(false) || (
					com.qust.helper.data.Setting.getString(com.qust.helper.data.Keys.IPASS_ACCOUNT).isNotEmpty() &&
						com.qust.helper.data.Setting.getString(com.qust.helper.data.Keys.IPASS_PASSWORD).isNotEmpty()
					)
				if(!inStack && cur != Keys.Page.VpnLoginPage && cur != Keys.Page.EasLogin &&
					now - viewModel.lastLoginNavAt > 1500L) {
					viewModel.lastLoginNavAt = now
					// 【第六十七轮·按要求】"不是静默登录教务系统, 有前提 —— 前提是教务系统因为外部
					// 原因无法正常登录"。
					//
					// 所以这里**不是**无条件自动登录, 而是先读"上一次教务为什么没成"(EasLoginState):
					//
					//  ① external = true(教务服务端故障/维护/403/网络不通/页面结构变化):
					//     · 已登录数字青科 -> 不弹任何登录界面, 直接用「数字青科 → 业务应用 → 教务管理系统」
					//       这条已经通的路把教务登进去, 成功后把本页查询重跑一遍;
					//     · 没登录数字青科 -> 气泡提醒, 再打开数字青科登录界面; 登录成功后由
					//       LoginEntry.autoFallbackToEas 自动接着进教务。
					//  ② external = false(只是没有会话/没有凭据): 走正常路径 —— 气泡 + 「教务登陆」
					//     (学号 + 教务系统密码), 因为这时输密码是真的能把教务登进去的。
					val external = com.qust.helper.model.account.EasLoginState.external
					if(!external) {
						viewModel.toastWarning("请先登录教务系统")
						com.qust.helper.data.LoginEntry.autoFallbackToEas = false
						com.qust.helper.data.LoginEntry.fromVpnLogin = false
						kotlinx.coroutines.delay(700L)
						openLoginPage(context, navController, Keys.Page.EasLogin)
						viewModel.needLogin = false
						return@LaunchedEffect
					}
					if(ipassReady) {
						// 【v3.6.0180·按要求】去掉气泡末尾的省略号("三个点")。
						viewModel.toastOK("教务系统暂时无法登录，正在用数字青科自动登录")
						// 自动登录期间不能让 needLogin 再把页面拉去登录页
						viewModel.needLogin = false
						if(viewModel.autoLoginEas()) {
							// 教务已经能用: 页面上的查询已被重跑, 这里什么都不做
							return@LaunchedEffect
						}
						viewModel.toastWarning("教务系统暂时无法登录，请重新登录数字青科")
					}else{
						viewModel.toastWarning("教务系统暂时无法登录，请先登录数字青科")
					}
					// 这里是"被教务功能页面拉来登录", 不是账号管理入口
					com.qust.helper.data.LoginEntry.autoFallbackToEas = true
					com.qust.helper.data.LoginEntry.fromVpnLogin = false
					kotlinx.coroutines.delay(700L)
					// 【v3.6.0180】上一版这里是一句 `try { navigate } catch(_: Exception){}` ——
					// 单页宿主里 navigate 必抛异常, 被吞掉 = 气泡之后什么都没有(用户原话:
					// "出现这个气泡提示后，没有任何后续反应，没有跳转任何页面")。改成会兜底的 [openLoginPage]。
					openLoginPage(context, navController, Keys.Page.VpnLoginPage)
				}
				viewModel.needLogin = false
			}
		}
	}

	/**
	 * 把用户送到登录页, **保证一定跳得过去**。
	 *
	 * 【v3.6.0180·修"点教务系统只弹一个气泡, 之后什么都没发生"】
	 *
	 * 这些教务页面有两个宿主:
	 *  · [com.qust.helper.ui.activity.MainActivity] —— 真正有 NavHost(路由全注册), navigate 正常;
	 *  · [com.qust.helper.ui.activity.ComposeActivity] —— 桌面小组件/通知跳进来时用的"单页宿主",
	 *    它只有一个 `rememberNavController()`, **根本没有 NavHost**。这时
	 *    `navController.navigate(...)` 必抛 IllegalArgumentException("Navigation graph has not been set")。
	 *
	 * 上一版写的是 `try { navigate } catch(_: Exception) { }` —— 异常被静默吞掉,
	 * 于是"气泡弹出来 -> 什么都不发生 -> 也不进教务系统", 正是用户截图里的现象。
	 *
	 * 现在: 先按正常路由跳; 跳不了(单页宿主)就用 MainActivity 打开同一个页面 ——
	 * 主界面有完整路由与返回栈, 登录完成后 [com.qust.helper.data.LoginEntry] 里的标记
	 * 会把人接着送回教务页面。
	 */
	private fun openLoginPage(context: android.content.Context, navController: NavController, route: String) {
		val navigated = runCatching {
			navController.navigate(route) { launchSingleTop = true }
		}.isSuccess
		if(navigated) return
		com.qust.helper.model.Logger.i("单页宿主无登录路由($route), 改用主界面打开")
		runCatching {
			context.startActivity(com.qust.helper.ui.activity.ComposeActivity.mainIntentFor(context, route))
		}.onFailure {
			com.qust.helper.model.Logger.e(it)
		}
	}

	@Composable
	fun NavigationHost(navController: NavHostController, startDestination: String, modifier: Modifier = Modifier, content: NavGraphBuilder.() -> Unit) {
		NavHost(
			navController = navController,
			startDestination = startDestination,
			modifier = modifier,
			enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Left, animationSpec = tween()) },
			exitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Left, animationSpec = tween()) },
			popEnterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Right, animationSpec = tween()) },
			popExitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right, animationSpec = tween()) },
			builder = { content() }
		)
	}
}
