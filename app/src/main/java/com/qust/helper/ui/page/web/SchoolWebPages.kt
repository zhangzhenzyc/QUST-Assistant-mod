/*
 * 青科助手（修改版） —— 基于开源项目 QUST-Assistant 修改而成
 *
 * 原项目版权所有 (C) 2891954521  (https://github.com/2891954521/QUST-Assistant)
 * 原始开源许可: GNU General Public License v3.0 (GPL-3.0), 许可原文见 LICENSE 文件, 保持完整未改动。
 *
 * 修改版版权所有 (C) 2026 zhangzhenzyc  (https://github.com/zhangzhenzyc)
 * 本修改版同样以 GPL-3.0 发布; 附加版权声明见 NOTICE.md —— 两份版权声明相互独立、同时生效。
 */

package com.qust.helper.ui.page.web

import android.annotation.SuppressLint
import android.net.http.SslError
import android.view.ViewGroup
import android.webkit.SslErrorHandler
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.sharp.ArrowBack
import androidx.compose.material.icons.automirrored.sharp.ArrowForward
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.qust.helper.R
import com.qust.helper.data.Keys
import com.qust.helper.data.Page

/**
 * 【第二十六/二十九/三十轮】「其他」栏目中的网页/说明入口(侧边栏同步):
 * - [OfficialSitePage] 科大官网: 打开学校官网(可双指缩放、右滑不触发侧边栏)
 */
object SchoolWebPages {

	/** 青岛科技大学官网 */
	private const val OFFICIAL_SITE_URL = "https://www.qust.edu.cn"

	/**
	 * 【第二十七/五十九轮】强制开启网页缩放:
	 * 科大官网的 viewport 为 user-scalable=0 + maximum-scale=1.0(网页自身禁用了双指缩放),
	 * 这里把 viewport 整体改写为可缩放(user-scalable=yes, maximum-scale=5.0),
	 * 并连续重跑数次 —— 防止网页自身脚本在加载完成后再次改写 viewport 导致缩放失效。
	 */
	private const val ENABLE_ZOOM_JS = """
		(function(){
			try{
				function fix(){
					var m = document.querySelector('meta[name=viewport]');
					if(!m){ m = document.createElement('meta'); m.setAttribute('name','viewport'); (document.head||document.documentElement).appendChild(m); }
					m.setAttribute('content','width=device-width, initial-scale=1.0, maximum-scale=5.0, user-scalable=yes');
				}
				fix();
				var n = 0, t = setInterval(function(){ n++; fix(); if(n >= 10) clearInterval(t); }, 700);
			}catch(e){}
		})();
	"""

	// 整页网页: 右滑不触发侧边栏(enableDrawer=false)
	val OfficialSitePage = Page(Keys.Page.OfficialSitePage, "科大官网", iconRes = R.drawable.ic_official_site, enableDrawer = false) { _, padding, _, _ ->
		SimpleWebScreen(padding = padding, url = OFFICIAL_SITE_URL)
	}

	/**
	 * 通用简单网页(WebView 基础设置 + 可注入的"加载完成"回调)
	 */
	@SuppressLint("SetJavaScriptEnabled")
	@Composable
	private fun SimpleWebScreen(
		padding: PaddingValues,
		url: String,
		topBar: (@Composable () -> Unit)? = null,
		onLoaded: ((WebView) -> Unit)? = null
	) {
		// 【第五十三轮】底部与其它网页一致的控制栏: [← 后退][→ 前进][⟳ 刷新];
		// 手机返回键"一步一步退"(先退网页历史, 退到底再退出本页), 任何时刻都可双指缩放。
		val webViewState = remember { mutableStateOf<WebView?>(null) }
		val canGoBack = remember { mutableStateOf(false) }
		val canGoForward = remember { mutableStateOf(false) }

		BackHandler(enabled = webViewState.value?.canGoBack() == true) {
			webViewState.value?.goBack()
		}

		Column(modifier = Modifier.padding(padding).fillMaxSize()) {
			topBar?.invoke()
			AndroidView(
				factory = { context ->
					WebView(context).apply {
						layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
						// 【第六十八轮】白底: 防止进入科大官网时网页首帧前黑屏一闪
						setBackgroundColor(android.graphics.Color.WHITE)
						settings.javaScriptEnabled = true
						settings.domStorageEnabled = true
						settings.loadWithOverviewMode = true
						settings.useWideViewPort = true
						// 允许双指放大/缩小(自带的 +/- 按钮隐藏, 只保留手势缩放)
						settings.setSupportZoom(true)
						settings.builtInZoomControls = true
						settings.displayZoomControls = false
						webViewState.value = this
						webViewClient = object : WebViewClient() {
							override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
								handler?.proceed()
							}
							override fun onPageCommitVisible(view: WebView?, url: String?) {
								// 【第六十轮】官网资源多、加载慢(onPageFinished 可能迟到好几秒), 首屏一可见就注入缩放修复,
								// 避免"刚进入时缩放失效、过一会才能缩放"。
								view?.evaluateJavascript(ENABLE_ZOOM_JS, null)
							}
							override fun onPageFinished(view: WebView?, url: String?) {
								view?.let {
									// 允许页面缩放: 部分页面用 user-scalable=no 禁用了双指缩放, 这里改写 viewport(保留原布局宽度)
									it.evaluateJavascript(ENABLE_ZOOM_JS, null)
									canGoBack.value = it.canGoBack()
									canGoForward.value = it.canGoForward()
									onLoaded?.invoke(it)
								}
							}
						}
						loadUrl(url)
					}
				},
				modifier = Modifier.weight(1f).fillMaxWidth()
			)
			// 底部控制栏(与数字青科/学校邮箱同款: 后退/前进/刷新)
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.height(50.dp)
					.background(MaterialTheme.colorScheme.surface),
				verticalAlignment = Alignment.CenterVertically
			) {
				IconButton(
					onClick = { webViewState.value?.let { if(it.canGoBack()) it.goBack() } },
					enabled = canGoBack.value,
					modifier = Modifier.weight(1f).fillMaxHeight()
				) { Icon(imageVector = Icons.AutoMirrored.Sharp.ArrowBack, contentDescription = "后退") }
				IconButton(
					onClick = { webViewState.value?.let { if(it.canGoForward()) it.goForward() } },
					enabled = canGoForward.value,
					modifier = Modifier.weight(1f).fillMaxHeight()
				) { Icon(imageVector = Icons.AutoMirrored.Sharp.ArrowForward, contentDescription = "前进") }
				IconButton(
					onClick = { webViewState.value?.reload() },
					modifier = Modifier.weight(1f).fillMaxHeight()
				) { Icon(imageVector = Icons.Rounded.Refresh, contentDescription = "刷新") }
			}
		}
	}
}
