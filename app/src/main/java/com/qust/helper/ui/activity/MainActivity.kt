/*
 * 青科助手（修改版） —— 基于开源项目 QUST-Assistant 修改而成
 *
 * 原项目版权所有 (C) 2891954521  (https://github.com/2891954521/QUST-Assistant)
 * 原始开源许可: GNU General Public License v3.0 (GPL-3.0), 许可原文见 LICENSE 文件, 保持完整未改动。
 *
 * 修改版版权所有 (C) 2026 zhangzhenzyc  (https://github.com/zhangzhenzyc)
 * 本修改版同样以 GPL-3.0 发布; 附加版权声明见 NOTICE.md —— 两份版权声明相互独立、同时生效。
 */

package com.qust.helper.ui.activity

import android.content.Intent
import android.os.Bundle
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material3.DrawerDefaults
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.coerceAtMost
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.qust.helper.R
import com.qust.helper.data.Data
import com.qust.helper.data.DrawerPageGroup
import com.qust.helper.data.Keys
import com.qust.helper.data.Page
import com.qust.helper.data.Setting
import com.qust.helper.model.AutoQueryRepository
import com.qust.helper.model.LessonTableRepository
import com.qust.helper.ui.page.HomePage
import com.qust.helper.ui.theme.TEXT_COLORS
import com.qust.helper.ui.theme.colorSecondaryText
import com.qust.helper.ui.theme.softGlassBackdrop
import com.qust.helper.ui.widget.AppWidgets
import com.qust.helper.ui.widget.Dialogs
import com.qust.helper.utils.UmengUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class MainActivity : BaseActivity() {

	private var updateMessage by mutableStateOf("")

	private var mainDrawerEnable by mutableStateOf(true)

	/** 【修改版】主界面延后渲染: 首次使用只显示引导登录界面, 完成后回前台自动显示主界面 */
	private var mainReady by mutableStateOf(false)


	override fun onCreate(savedInstanceState: Bundle?) {
		// 【修改版】启动即主动申请通知权限(有新教务通知时在应用外提醒)
		com.qust.helper.utils.Notices.requestPermission(this, 40211)
		// 【第三十二轮·按要求】删掉老版本的「教务通知」通知类别(系统设置里那条
		// 「教务通知调课/补课/停课提醒」就是这样来的), 之后统一走「推送通知」。
		com.qust.helper.utils.Notices.removeLegacyChannel(this)
		super.onCreate(savedInstanceState)

		// 第一次使用跳转到引导页
		// 【本轮】只有第一次进入(未登录过)才固定显示登录界面; 已登录过的直接进入主界面
		if(Setting.getBoolean(Keys.IS_FIRST_USE, true)) {
			startActivity(Intent(this, GuideActivity::class.java))
			// 【修改版】首次进入无过渡动画: 安装后打开即直接显示引导登录页
			overridePendingTransition(0, 0)
			// 【本期】兜底: 引导页若因为系统限制没能盖上来(极少数机型/后台启动限制),
			// 主界面不能一直停在"空白"状态 —— 1.5 秒后若本页仍在前台, 就正常渲染主界面。
			window.decorView.postDelayed({
				if(!isFinishing && !isDestroyed &&
					lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
					mainReady = true
				}
			}, 1500L)
			return
		}
		mainReady = true

		lifecycleScope.launch {
			// 设置中的"更新"栏目已移除, 不再自动检查更新(避免拉起原仓库的更新提示)
			// 自动检查工具
			AutoQueryRepository.startAutoQuery(this@MainActivity.applicationContext)
		}

		// 【修改版】WebView 运行时预热: 后台线程初始化 Cookie, 首帧后再创建一次性 WebView 完成完整初始化
		Thread {
			try { android.webkit.CookieManager.getInstance() } catch(_: Exception){ }
		}.start()
		window.decorView.postDelayed({
			try {
				val w = android.webkit.WebView(this@MainActivity)
				w.layout(0, 0, 1, 1)
				w.destroy()
			} catch(_: Exception){ }
		}, 600L)

	}

	/**
	 * 【跨天后打开软件课表不对】
	 *
	 * 应用如果一直留在后台(没被杀掉), 第二天再切回来时 currentWeek / dayOfWeek 还是昨天的值,
	 * 于是"当日课表"、桌面小组件、课表默认定位的周次都是错的。这里每次回到前台都重新
	 * 按当前时间换算周次与星期, 变化时通知界面刷新。
	 */
	override fun onStart() {
		super.onStart()
		mainReady = !Setting.getBoolean(Keys.IS_FIRST_USE, true)
		if(Setting.getBoolean(Keys.IS_FIRST_USE, true)) return
		val beforeWeek = LessonTableRepository.currentWeek.intValue
		val beforeDay = LessonTableRepository.dayOfWeek.intValue
		LessonTableRepository.updateDate()
		if(beforeWeek != LessonTableRepository.currentWeek.intValue ||
			beforeDay != LessonTableRepository.dayOfWeek.intValue) {
			LessonTableRepository.notifyLessonTableChanged()
		}
	}

	@Composable
	override fun Content() {
		// 【本期·"空白课表一闪而过"根因】首次安装/首次登录时, 本页会在 onCreate 里立刻拉起
		// 引导登录页(GuideActivity), 但引导页的窗口还没盖上来之前, 主界面的课表已经先合成了一帧 ——
		// 用户看到的就是"刚打开软件, 空白课表一闪, 然后出现数字青科登录界面"。
		// 这里在主界面就绪(或首次登录完成)之前不参与绘制, 只留一个背景色块。
		if(!mainReady){
			Box(Modifier.fillMaxSize())
			return
		}

		// 【修改版】自启动/后台运行引导(Compose 弹窗, 稳定显示; 仅提示一次)
		var showAutostart by remember { mutableStateOf(!Setting.getBoolean(Keys.AUTOSTART_PROMPTED, true)) }
		if(showAutostart){
			remember { Setting.edit { it.putBoolean(Keys.AUTOSTART_PROMPTED, true) } }
			androidx.compose.material3.AlertDialog(
				onDismissRequest = { showAutostart = false },
				title = { androidx.compose.material3.Text(text = "开启自启动提醒") },
				text = { androidx.compose.material3.Text(text = "教务通知提醒依赖后台定时检查。请在系统设置中允许本应用“自启动”和“后台运行”（各品牌手机在“自启动管理/电池”里），否则通知可能延迟或不出现。") },
				confirmButton = {
					androidx.compose.material3.TextButton(onClick = {
						val pk = packageName
						val intents = listOf(
							Intent().setClassName("com.miui.securitycenter","com.miui.permission.AutoStartManagementActivity"),
							Intent().setClassName("com.huawei.systemmanager","com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
							Intent().setClassName("com.coloros.safecenter","com.coloros.safecenter.permission.startup.StartupAppListActivity"),
							Intent().setClassName("com.oppo.safe","com.oppo.safe.permission.startup.StartupAppListActivity"),
							Intent().setClassName("com.vivo.permissionmanager","com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
							Intent().setClassName("com.meizu.safe","com.meizu.safe.security.SHOW_APPSEC")
						)
						var done = false
						for(i in intents){
							try { i.putExtra("packageName", pk); startActivity(i); done = true; break } catch(_: Exception){ }
						}
						if(!done){
							try { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$pk"))) } catch(_: Exception){ }
						}
						showAutostart = false
					}) { androidx.compose.material3.Text(text = "去设置") }
				},
				dismissButton = {
					androidx.compose.material3.TextButton(onClick = { showAutostart = false }) { androidx.compose.material3.Text(text = "暂不") }
				}
			)
		}

		val scope = rememberCoroutineScope()
		val drawerState = rememberDrawerState(DrawerValue.Closed)
		val navController = rememberNavController().also {
			it.addOnDestinationChangedListener { _, destination, _ ->
				UmengUtils.route(this.applicationContext, destination.route)
				// 【侧边栏卡住 / 划不出来】以前只有"进入 Data.Pages 里的页面"时才会更新侧滑开关,
				// 而"教务系统 / 数字青科 / 智慧体测"这些页面是 enableDrawer = false,
				// 从它们返回首页时开关不会被恢复 —— 于是侧边栏再也划不出来, 看起来就是"没反应"。
				// 现在每次目的地变化都重新按目标页面判定, 首页之类默认允许侧滑。
				mainDrawerEnable = Data.Pages[destination.route?.substringBefore('?')]?.enableDrawer ?: true
			}
		}

		// 【第三十轮】桌面小组件点进来: 直接开到指定页面。
		// 以前小组件是另开一个只有一页的 ComposeActivity(带返回箭头), 用户要的是"进软件本身的学期课表页",
		// 所以改成: 带 openPage 参数拉起主界面, 在这里 navigate 过去(主界面本来就有这个路由)。
		// 【第三十一轮·修"点学期课表小组件还是跳到带返回箭头的单页"】
		// 学期课表 / 我 这两个页面**本身就是底部导航的页签**, 它们既在底部导航里、又在 NavHost 里注册了一个
		// "带返回箭头的子页"路由 —— navigate 过去拿到的自然是那个子页。用户说的"完全删除这个页面"就是它。
		// 现在: 目标是底部页签 -> 先把子页退掉, 再让 HomePage 切到这个页签; 其余页面照旧 navigate。
		var pendingPage by remember { mutableStateOf(intent?.getStringExtra("openPage").orEmpty()) }
		var pendingTab by remember { mutableIntStateOf(-1) }
		addOnNewIntentListener { newIntent ->
			pendingPage = newIntent.getStringExtra("openPage").orEmpty()
		}
		LaunchedEffect(pendingPage) {
			val target = pendingPage
			if(target.isNotEmpty()) {
				pendingPage = ""
				val tab = Data.bottomPage.indexOfFirst { it.key == target }
				if(tab >= 0) {
					// 可能在某个子页上: 先回主界面(主界面是 startDestination, 退到它即可)
					navController.popBackStack("home", inclusive = false)
					pendingTab = tab
				}else if(Data.Pages.containsKey(target)) {
					// 【第六十八轮·二·按要求】"如果当前页面就是这个页面, 再次点击**不要再次跳转**"。
					//
					// 小组件的 PendingIntent 带的是 SINGLE_TOP, 点第二次不会重建 Activity,
					// 只会把 openPage 再送一遍 -> 上一版每次都 navigate, 返回栈里就叠出 N 个同样的页,
					// 用户按一次返回看着像"没反应"。已经在目标路由上就什么都不做。
					val current = navController.currentDestination?.route?.substringBefore('?')
					if(current != target) navController.navigate(target)
				}
			}
		}

		ModalNavigationDrawer(
			drawerState = drawerState,
			gesturesEnabled = mainDrawerEnable,
			drawerContent = {
				ModalDrawerSheet(modifier = Modifier.requiredWidth((LocalConfiguration.current.screenWidthDp.dp * 0.75F).coerceAtMost(DrawerDefaults.MaximumDrawerWidth))) {
					MainAppDrawer(scope, drawerState, navController)
				}
			}
		) {
			// 【第三十三轮·按要求】"柔光玻璃"的那层光: 内容区铺一层极浅的冷暖过渡,
			// 白色的顶栏/卡片/按钮浮在上面 —— 悬浮感与纵深就来自这里(真正的实时模糊见 SoftGlass.kt 的说明)。
			Box(modifier = Modifier.fillMaxSize().background(softGlassBackdrop())) {
			AppWidgets.NavigationHost(navController = navController, startDestination = "home"){
				composable(route = "home") {
					HomePage.HomePage(
						this@MainActivity, scope, drawerState, navController,
						pendingTab = pendingTab,
						onTabConsumed = { pendingTab = -1 }
					)

					// 显示检查到更新Dialog
					if(updateMessage.isNotEmpty()){
						Dialogs.AskDialog(title = "更新", "检查到新版本，是否更新？\n$updateMessage", onConfirm = {
							navController.navigate(Keys.Page.UpdatePage); updateMessage = ""
						}, onDismiss = { updateMessage = "" })
					}
				}

				for(page in Data.Pages.values) {
					val route = if(page.arguments.isEmpty()){
						page.key
					}else{
						"${page.key}?" + page.arguments.joinToString("&") { "${it.name}={${it.name}}" }
					}
					composable(route = route, arguments = page.arguments) { backStackEntry ->
						setDrawerEnable(page.enableDrawer)
						Scaffold(
							// 【第三十三轮】透明底: 让下面那层"柔光玻璃背景光"透上来
							containerColor = Color.Transparent,
							topBar = {
								AppWidgets.TopBar(title = page.name, navigationIcon = Icons.AutoMirrored.Filled.ArrowBack) {
									navController.popBackStack()
								}
							},
						) { padding ->
							page.content(this@MainActivity, padding, navController, backStackEntry.arguments)
						}
					}
				}
			}
			}
		}
	}

	@Composable
	fun MainAppDrawer(scope: CoroutineScope, drawerState: DrawerState, navController: NavController){
		Column(modifier = Modifier.padding(16.dp)) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically
			) {
				Text(
					text = stringResource(R.string.app_name),
					modifier = Modifier.padding(16.dp).weight(1F),
					style = MaterialTheme.typography.titleLarge,
					maxLines = 1,
				)
				Image(
					painter = painterResource(id = R.mipmap.ic_launcher),
					contentDescription = null,
					modifier = Modifier.padding(16.dp)
				)
			}
			
			fun clickItem(key: String){
				scope.launch {
					// 【本期】先等侧边栏收完再跳页: 以前"收抽屉"和"页面滑入"两个动画同时跑,
					// 冷启动时同抢一帧, 观感就是"一点就卡一下"。
					if(drawerState.isOpen) drawerState.close()
					// 【本期】学校邮箱没存账号时直接进登录页(见 Page.resolve): 只做一次转场, 不再"先进邮箱页再弹登录页"
					val target = com.qust.helper.data.Page.resolve(key)
					if(navController.currentDestination?.route != target){
						navController.navigate(target){
							popUpTo(target) { inclusive = true }
							launchSingleTop = true
						}
					}
				}
			}

			Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
				Data.pageGroups.forEachIndexed { index, item ->
					when(item){
						is DrawerPageGroup.Page -> {
							DrawerItem(page = Data.Pages[item.name]!!, onClick = { clickItem(it) })
						}

						is DrawerPageGroup.PageGroup -> {
							Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).clickable { item.expand.value = !item.expand.value }){
								Row(modifier = Modifier.padding(16.dp)) {
									// 【第七十三轮】分组图标与"我"页面同步: 多色图标用自身颜色, 其余走循环调色板
									Icon(painter = if(item.image != null) rememberVectorPainter(item.image) else painterResource(id = item.iconRes), contentDescription = item.name, tint = if(item.iconRes in setOf(R.drawable.ic_lesson_search, R.drawable.ic_school_yellow, R.drawable.ic_ipass_blue, R.drawable.ic_grid_view_blue)) Color.Unspecified else Color(TEXT_COLORS[index % (TEXT_COLORS.size - 1) + 1]))
									Text(text = item.name, modifier = Modifier.padding(start = 16.dp).weight(1F))
									Icon(imageVector = Icons.Rounded.ArrowDropDown, contentDescription = null, tint = colorSecondaryText, modifier = Modifier.rotate( if(item.expand.value) 180F else 0F ))
								}
							}

							AnimatedVisibility(visible = item.expand.value) {
								Column {
									item.pages.forEachIndexed { index, page ->
										DrawerItem(modifier = Modifier.padding(start = 32.dp), page = Data.Pages[page]!!, onClick = { clickItem(it) })
									}
								}
							}
						}
					}
				}
			}
		}
	}

	@Composable
	fun DrawerItem(modifier: Modifier = Modifier, page: Page, onClick: (String) -> Unit) {
		NavigationDrawerItem(
			label = { Text(text = page.name) },
			selected = false,
			icon = {
				Icon(
					painter = if(page.image != null) rememberVectorPainter(page.image) else painterResource(id = page.iconRes),
					contentDescription = page.name,
					// 【第七十四轮·关键修复】侧边栏实际走的是这里: 着色口径与"我"页面完全一致 ——
					// 多色图标用自身颜色, 其余按页面 key 固定取色(此前只认校园跑, 其余全被调色板重着色)。
					tint = when {
						page.key in com.qust.helper.data.PageIconStyle.OWN_COLOR -> Color.Unspecified
						else -> com.qust.helper.data.PageIconStyle.colorOf(page.key, com.qust.helper.ui.theme.TEXT_COLORS)
					}
				)
			},
			onClick = { onClick(page.key) },
			modifier = modifier
		)
	}

	fun setDrawerEnable(boolean: Boolean){
		mainDrawerEnable = boolean
	}
}

