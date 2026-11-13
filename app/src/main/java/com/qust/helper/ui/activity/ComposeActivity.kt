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

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.navigation.compose.rememberNavController
import com.qust.helper.data.Data
import com.qust.helper.ui.theme.softGlassBackdrop
import com.qust.helper.ui.widget.AppWidgets.TopBar

/**
 * 单页宿主 Activity: 从"软件外"的通知、桌面小部件、其它应用的跳转链接进来时,
 * 直接打开某个页面(由 Intent 的 `page` 参数指定)。
 *
 * 【本期·"点通知进来有时候看不到教务通知, 要刷新才有"】
 * 以前这里只在 onCreate 时读一次 `page`, 且页面内容没有"重新合成"的概念:
 * 通知点进来时(尤其是 Activity 被系统复用、走 onNewIntent 而不是重建的情况),
 * 页面不会重新进入, 里面"进页面自动查询"的 LaunchedEffect 自然也不会再跑,
 * 用户看到的就是一个空列表, 只能手动下拉刷新。
 * 现在把当前页面 key 做成状态, 并监听新 Intent: 再次收到新 Intent 时递增 [contentRevision],
 * 让整块内容重新合成 —— 进入页面的自动查询会再执行一次。
 */
class ComposeActivity: BaseActivity() {

	companion object{
		/**
		 * 【本轮】给桌面小组件用的 Intent。
		 *
		 * 小组件里不能直接 `context.startActivity` —— 那个 context 是 Receiver 的, 系统在
		 * Android 10+ 会按"后台启动 Activity"拦掉, 表现就是**点了完全没反应**。
		 * 这里只负责拼出 Intent, 由 Glance 的 `actionStartActivity` 交给系统发起,
		 * 属于"用户点击"来源, 不受后台启动限制。
		 */
		fun intentFor(context: Context, page: String): Intent =
			Intent(context, ComposeActivity::class.java)
				.putExtra("page", page)
				.putExtra("day", -1)
				.putExtra("week", -1)
				.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

		/**
		 * 【第三十轮】拉起**主界面**并直接进入某个页面。
		 *
		 * 桌面小组件原来用的是 [intentFor] —— 那会另开一个"只有一页"的 ComposeActivity
		 * (顶部一个返回箭头), 用户觉得那不是"软件的学期课表页"。改成拉起 MainActivity
		 * 并带上 openPage, 由它内部 navigate 过去, 于是底部导航、侧滑、返回栈全都是正常的。
		 */
		fun mainIntentFor(context: Context, page: String): Intent =
			Intent(context, MainActivity::class.java)
				.putExtra("openPage", page)
				.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

		/**
		 * 【第三十一轮】这一页是不是"底部导航里的页"(学期课表 / 我)。
		 *
		 * 这类页面**必须**在主界面里显示(底部导航 + 侧滑 + 返回栈都是正常的), 不能另开单页 ——
		 * 用户看到的"点学期课表小组件跳到一个带返回箭头的单页"就是这个单页宿主造成的。
		 */
		fun isBottomPage(page: String): Boolean = Data.bottomPage.any { it.key == page }

		fun startActivity(context: Context, page: String){
			startActivity(context, page, -1)
		}

		fun startActivity(context: Context, page: String, day: Int){
			startActivity(context, page, day, -1)
		}

		/** @param day 星期几(0=周一); @param week 第几周(从0开始), -1表示未指定 */
		fun startActivity(context: Context, page: String, day: Int, week: Int){
			context.startActivity(
				Intent(context, ComposeActivity::class.java)
					.putExtra("page", page)
					.putExtra("day", day)
					.putExtra("week", week)
			)
		}

		/**
		 * 打开「数字青科」登录页, 登录成功后自动关闭并回到调用页
		 *
		 * 【本期】欢迎页(首次安装)已改为**直接内嵌**数字青科登录表单, 不再跳本页;
		 * 这里保留给"需要单独一页登录数字青科"的场合(例如后续从网页登录态失效时兜底)。
		 */
		fun startVpnLoginForGuide(context: Context){
			context.startActivity(
				Intent(context, ComposeActivity::class.java)
					.putExtra("page", com.qust.helper.data.Keys.Page.VpnLoginPage)
					.putExtra("finishOnLogin", true)
			)
		}

		/**
		 * 【本期】欢迎页的「登录教务系统」入口: 打开教务系统登录页, 登录成功后自动关闭回到欢迎页
		 *
		 * 欢迎页(onResume)会接着进入教务管理系统并查询课表。
		 */
		fun startEasLoginForGuide(context: Context){
			context.startActivity(
				Intent(context, ComposeActivity::class.java)
					.putExtra("page", com.qust.helper.data.Keys.Page.EasLogin)
					.putExtra("finishOnLogin", true)
			)
		}
	}

	/** 当前要显示的页面 key(随 Intent 变化) */
	private var pageKey by mutableStateOf("")

	/** 内容版本号: 收到新 Intent 时递增, 触发页面重新合成(进而重新执行"进页面自动查询") */
	private var contentRevision by mutableIntStateOf(0)

	/**
	 * 【第三十一轮】是否已转交给主界面。
	 *
	 * 桌面上残留的小组件实例还带着**老版本**的 Intent(指向本 Activity + 学期课表页),
	 * 覆盖安装不会刷新这些 PendingIntent —— 只要它们还在, 用户点一下照样看到那个单页。
	 * 所以这里再做一道: 凡是底部导航的页, 一律交给 MainActivity 显示, 本页立刻关掉。
	 */
	private var redirected by mutableStateOf(false)

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		pageKey = intent?.getStringExtra("page") ?: ""
		if(isBottomPage(pageKey)) {
			redirected = true
			startActivity(mainIntentFor(this, pageKey))
			finish()
			overridePendingTransition(0, 0)
			return
		}
		// Activity 被复用(launchMode/CLEAR_TOP)时不会重新 onCreate, 新 Intent 从这里进来
		addOnNewIntentListener { newIntent ->
			setIntent(newIntent)
			pageKey = newIntent.getStringExtra("page") ?: ""
			contentRevision++
		}
	}

	@Composable
	override fun Content() {
		// 已转交主界面: 本页不要再画任何东西(只等 finish)
		if(redirected) {
			Box(Modifier.fillMaxSize())
			return
		}
		// 【第二十九轮】兜底: 外部(桌面小组件/通知)传进来的 page key 万一写错或已下线,
		// 以前会直接给用户一片 "This page is empty."(小组件点一下就白屏, 就是这么来的)。
		// 现在退到「我」页 —— 至少是一个能用的界面, 用户能自己再点进课表/饮水码。
		val page = Data.Pages[pageKey] ?: Data.Pages[com.qust.helper.data.Keys.Page.MyPage]
		if(page == null) {
			Empty()
			return
		}

		val navController = rememberNavController()
		key(pageKey, contentRevision) {
			// 【第三十三轮·按要求】与主界面同一套"柔光玻璃": 底层铺一层极浅的光, 面板浮在上面
			Box(modifier = Modifier.fillMaxSize().background(softGlassBackdrop())) {
			Scaffold(
				containerColor = Color.Transparent,
				topBar = {
					TopBar(title = page.name, navigationIcon = Icons.AutoMirrored.Filled.ArrowBack){
						onBackPressedDispatcher.onBackPressed()
					}
				}
			) { contentPadding ->
				page.content(this@ComposeActivity, contentPadding, navController, Bundle.EMPTY)
			}
			}
		}
	}
	
	@Composable
	fun Empty(){
		Text(text = "This page is empty.")
	}
}
