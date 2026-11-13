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

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.sharp.ArrowBack
import androidx.compose.material.icons.automirrored.sharp.ArrowForward
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.activity.viewModels
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.qust.helper.R
import com.qust.helper.data.Keys
import com.qust.helper.data.Page
import com.qust.helper.data.Setting
import com.qust.helper.model.account.Account
import com.qust.helper.model.account.EASAccount
import com.qust.helper.model.account.IPassAccount
import com.qust.helper.model.account.NeedLoginException
import com.qust.helper.ui.widget.AppWidgets
import com.qust.helper.ui.widget.Toast
import com.qust.helper.viewmodel.eas.BaseEasViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** 【第四十九轮】判断异常是否属于"站点暂时访问失败"(明确的 403/404/5xx), 与"未登录"区分:
 *  超时/网络无响应等模糊情况按"软失败"处理(网页照常打开, 慢一点也能进去), 避免误报。 */
private fun isSiteUnreachable(e: Throwable): Boolean {
	if(e is NeedLoginException) return false
	val m = e.message ?: return false
	return m.contains("接口地址不存在(404)") || m.contains("访问被拒绝(403)") || m.contains("暂时没有访问权限") ||
		m.contains("后端服务暂时不可用") || m.contains("服务正在维护或者过载") ||
		// 【本轮】纯网络问题(连不上/超时)也算"暂时进不去": 这种情况显示网页 + 一句提示即可,
		// 不该把人拉到登录页去重输一遍密码。
		m.contains("网络或者远端服务器无响应") || m.contains("请求超时")
}

/**
 * 【本期改造】把教务相对路径转成"经数字青科访问"的地址。
 *
 * 教务系统已改为统一经「数字青科 → 业务应用 → 教务管理系统」进入:
 * 直连 jwglxt 域名会被校方拒绝(403), 因此网页入口也改用 WebVPN 加密地址。
 * 加密失败时回退直连地址, 保证页面仍能打开(由网页内部提示登录)。
 */
private fun easVpnUrl(account: Account, path: String): String {
	val direct = "${account.scheme}://${account.host}/$path"
	// 【本轮】教务有两条通道: 走教务系统原网址(直连)时**不需要** WebVPN 编码,
	// 直接打开原网址即可(会话 Cookie 注入到教务自己的域名, 见 cookieHost)。
	if(account.easWebIsDirect) return direct
	return try {
		"https://" + com.qust.helper.data.api.QustApi.VPN_HOST + "/" +
			com.qust.helper.utils.VpnEncodeUtils.encryptUrl(direct)
	}catch(_: Exception) {
		direct
	}
}

/**
 * 【本轮】当前教务账号是否是"教务系统原网址(直连)"通道
 *
 * 只有教务账号(直连 / 双通道)有这个属性; 其它站点(邮箱/数字青科等)的 Account 不是 [EASAccount],
 * 这里返回 false, 走原来的 WebVPN 逻辑不变。
 */
private val Account.easWebIsDirect: Boolean
	get() = (this as? com.qust.helper.model.account.EASAccount)?.openWebDirectly == true

/**
 * 【本轮】当前教务账号是否**已经有会话**(不需要保存过的账号密码)
 *
 * 教务账号密码按要求不再保存, 所以"存过密码"不能再当成"能进网页"的唯一条件:
 * 用原网址登录过、会话还在, 就应当直接把会话注入网页, 否则会被反复拉去登录页。
 */
private val Account.easWebHasSession: Boolean
	get() = (this as? com.qust.helper.model.account.EASAccount)?.hasSession == true

/** JS 单引号字符串转义(账号/密码等嵌入注入脚本时使用) */
private fun jsString(s: String): String = s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "").replace("\r", "")

/**
 * 【第七十九轮·修"有时候登录不了, 停在统一身份认证(CAS)登录页"】
 *
 * 教务/数字青科的会话过期时, WebView 会被学校重定向到统一身份认证登录页,
 * 以前 App 在这个页面什么都不做 —— 用户只能手动输一遍密码。
 * 现在页面加载完若是 CAS 登录页, 且账号管理里存过**统一身份(数字青科)**的账号密码,
 * 就自动把账号密码填进输入框(密码的 RSA 加密由页面自己的提交 JS 完成)并点一次登录。
 * 每个网页实例只尝试一次: 密码不对不自动重试, 避免登录死循环。
 */
private fun isCasLoginPage(url: String): Boolean {
	val u = url.lowercase()
	// 教务系统自己的登录页(直连 jwglxt)不是 CAS, 不能往里填统一身份的密码;
	// 学校邮箱有自己的账密体系, 也不能填统一身份的密码。
	if(u.contains("jwglxt") || u.contains("mail.")) return false
	if(!u.contains("qust.edu.cn")) return false
	// 典型 CAS: /cas、ids.、authserver、wvpn 登录页; 其余校内页交给"页面上有没有密码框"
	// 的探测(casFillJs 返回 nopwd 的代价近乎为零), 不再漏掉各种花式跳转形态。
	return true
}

/** 把统一身份账号密码填进 CAS 登录表单(主文档 + iframe 都找), 填完顺手点一次登录。
 *  提交时的密码 RSA 加密由页面自身的 JS 负责, 这里只负责把明文放进输入框并触发提交。
 *  返回: filled=已填已点 / filled-nosubmit=已填没找到按钮 / done=早已填过 / nopwd=页面还没有密码框 */
private fun casFillJs(user: String, pwd: String): String = """
	(function(){
		try{
			if(window.__qust_cas_filled) return 'done';
			var docs = [document];
			try{
				var fs = document.querySelectorAll('iframe');
				for(var k = 0; k < fs.length; k++){
					try{ if(fs[k].contentDocument) docs.push(fs[k].contentDocument); }catch(e){}
				}
			}catch(e){}
			for(var d = 0; d < docs.length; d++){
				var doc = docs[d];
				var p = doc.querySelector('input[type="password"]');
				if(!p) continue;
				var u = doc.querySelector('input[name="username"]') ||
					doc.querySelector('input[name="account"]') ||
					doc.querySelector('input[name="loginname"]');
				if(!u){
					var cands = doc.querySelectorAll('input[type="text"], input[type="tel"], input:not([type])');
					for(var i = 0; i < cands.length; i++){
						var r = cands[i].getBoundingClientRect();
						if(r.width > 0 && r.height > 0){ u = cands[i]; break; }
					}
				}
				if(!u || u.value) return 'nouser';
				u.value = '${jsString(user)}';
				p.value = '${jsString(pwd)}';
				u.dispatchEvent(new Event('input', {bubbles: true}));
				p.dispatchEvent(new Event('input', {bubbles: true}));
				window.__qust_cas_filled = true;
				var b = doc.querySelector('button[type="submit"], input[type="submit"], #idchecklogin');
				if(!b){
					var els = doc.querySelectorAll('button, a, div[role="button"], input[type="button"]');
					for(var j = 0; j < els.length; j++){
						var t = (els[j].textContent || els[j].value || '').replace(/\s+/g, '');
						if(t === '登录' || t === '登 录'){ b = els[j]; break; }
					}
				}
				if(b){ b.click(); return 'filled'; }
				return 'filled-nosubmit';
			}
			return 'nopwd';
		}catch(e){ return 'err'; }
	})()
"""

/**
 * 【本期】在"数字青科"门户页里找「业务应用 → 教务管理系统」的链接。
 *
 * 走 WebVPN 打开教务后, 学校有时会把页面停在数字青科门户(业务应用列表),
 * 需要用户自己点一下「教务管理系统」才真正进教务。这里代替用户把这一步点掉:
 * 先找工作区(含 iframe)里文字为"教务管理系统"的链接, 找不到再退回任意含"教务"的链接,
 * 返回它的绝对地址; 找不到返回空串。
 */
private const val EAS_APP_LINK_JS = """
	(function(){
		function find(doc){
			var best=null, fallback=null;
			try{
				var all=doc.querySelectorAll('a');
				for(var i=0;i<all.length;i++){
					var t=(all[i].textContent||'').replace(/\s+/g,'');
					if(t.indexOf('教务管理系统')>=0){ best=all[i]; break; }
					if(!fallback && t.indexOf('教务')>=0) fallback=all[i];
				}
			}catch(e){}
			var el=best||fallback;
			if(!el) return '';
			var h=el.getAttribute('href')||el.href||'';
			if(!h || h==='#' || h.toLowerCase().indexOf('javascript:')===0) return '';
			return h;
		}
		var out=find(document);
		if(!out){
			var fs=document.querySelectorAll('iframe');
			for(var j=0;j<fs.length;j++){
				try{ out=find(fs[j].contentDocument); }catch(e){}
				if(out) break;
			}
		}
		return out;
	})()
"""

/** 【自动登录修复】邮箱登录表单是否已渲染: 用于等待AJAX/框架把输入框真正插入DOM */
private const val MAIL_LOGIN_FORM_JS =
	"(function(){try{return document.querySelector('input[type=password],input[name=uid]')?'1':'0'}catch(e){return '0'}})()"

/** 【第六十一轮】在 WebView 上执行 JS 并等待返回(仅主线程调用); view 为空或异常时返回 "" */
private suspend fun evalJs(view: WebView?, js: String): String {
	if(view == null) return ""
	return try {
		suspendCancellableCoroutine { cont ->
			view.evaluateJavascript(js) { r -> cont.resume(r ?: "") }
		}
	}catch(_: Exception){ "" }
}

/**
 * 【第六十/六十一轮】学校邮箱(Coremail)自动登录脚本:
 * 先清空"记住的用户名"再填充账号密码, 然后**有限次重试点击登录**(约1.2s/4.8s/9.6s/18s 共4次);
 * 【登录逻辑】本次进入只填充一次表单, 后续只重试点击, 不重复改写用户看到的输入框;
 * 密码框不可见或页面加载中时不再点击(抑制"登录两次");
 * 出现: 页面跳走 / 可见的图片验证码 / 密码错误提示 / "二次验证"界面 —— 任一出现即停止重试。
 */
private fun mailFillJs(account: String, password: String): String = """
	(function(){try{
		var ACC='$account',PWD='$password',n=0,filled=false,t=setInterval(function(){n++;
			if(document.readyState!=='complete'){if(n>80){clearInterval(t)}return;}
			var vc=document.querySelector('input[name=verifyCode]');
			if(vc&&vc.offsetParent!==null){clearInterval(t);return;}
			var body=document.body?document.body.innerText:'';
			if(body.indexOf('密码错误')>=0||body.indexOf('用户名或密码')>=0||body.indexOf('账号或密码')>=0){clearInterval(t);return;}
			var sa=document.querySelector('.j-second-auth-wrap');
			if(sa&&sa.className&&sa.className.indexOf('f-dn')<0){clearInterval(t);return;}
			var u=document.getElementById('uid')||document.querySelector('input[name=uid]');
			var p=document.getElementById('password')||document.querySelector('input[name=password],input[type=password]');
			if(p&&p.offsetParent!==null){
				if(!filled){
					// 【第六十三轮】按"清空 -> 聚焦 -> 写入 -> 失焦"的顺序填写: 先删除记住的用户名, 再写入账号密码,
					// 并触发 input/change 事件 + 失焦, 保证网页自己的脚本能识别到值、把用户名称显示出来(否则登录按钮不生效)。
					if(u){u.value='';try{u.dispatchEvent(new Event('input',{bubbles:true}))}catch(e){}try{u.dispatchEvent(new Event('change',{bubbles:true}))}catch(e){}
						try{u.focus()}catch(e){}}
					p.value='';try{p.dispatchEvent(new Event('input',{bubbles:true}))}catch(e){}try{p.dispatchEvent(new Event('change',{bubbles:true}))}catch(e){}
					if(u){u.value=ACC;try{u.dispatchEvent(new Event('input',{bubbles:true}))}catch(e){}try{u.dispatchEvent(new Event('change',{bubbles:true}))}catch(e){}
						try{u.blur()}catch(e){}}
					try{p.focus()}catch(e){}
					p.value=PWD;try{p.dispatchEvent(new Event('input',{bubbles:true}))}catch(e){}try{p.dispatchEvent(new Event('change',{bubbles:true}))}catch(e){}
					try{p.blur()}catch(e){}
					// 兜底: 有些框架会异步回填/清空, 这里确认用户名确实显示为我们的账号
					if(u&&u.value!==ACC){u.value=ACC;try{u.dispatchEvent(new Event('input',{bubbles:true}))}catch(e){}try{u.dispatchEvent(new Event('change',{bubbles:true}))}catch(e){}}
					filled = true;
				}
				if(n===3||n===12||n===24||n===45){
					var b=document.querySelector('button.j-submit')||document.querySelector('.u-btn-primary')||document.querySelector('button[type=submit],input[type=submit]');
					if(b){try{b.click()}catch(e){}}else{try{p.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',keyCode:13,which:13,bubbles:true}))}catch(e){}}
				}
			}
			var cbs=document.querySelectorAll('input[type=checkbox]');
			for(var k=0;k<cbs.length;k++){var lb=cbs[k].closest?cbs[k].closest('label'):null;var tx=lb?lb.textContent:((cbs[k].parentElement&&cbs[k].parentElement.textContent)||'');
				if(tx&&(tx.indexOf('同设备')>=0||tx.indexOf('信任')>=0||tx.indexOf('不再')>=0)){
					try{if(!cbs[k].checked)cbs[k].click()}catch(e){}
					var btns2=document.querySelectorAll('button,a,.btn');
					for(var m2=0;m2<btns2.length;m2++){var t2=(btns2[m2].textContent||'');
						if(t2.indexOf('确定')>=0||t2.indexOf('确认')>=0||t2.indexOf('继续')>=0){btns2[m2].click();break}}
					clearInterval(t);break}}
			if(n>80){clearInterval(t)}
		},400);
	}catch(e){}})();
""".trimIndent()

object WebPage {

	/**
	 * 【本期】强制开启网页缩放 —— 与「科大官网」用的是同一套做法。
	 *
	 * 只开 `setSupportZoom(true)` 是不够的: 教务系统/数字青科这类校内网页在
	 * `<meta name="viewport">` 里写了 `user-scalable=no, maximum-scale=1.0`,
	 * 网页自己把双指缩放禁掉了, 于是"怎么捏都不放大"。科大官网以前也是同样的问题,
	 * 解决办法就是把 viewport 里的这两项改写掉。
	 *
	 * 这里**只改缩放相关的两项**, 保留原有的 width/initial-scale ——
	 * 不擅自把布局宽度改成 device-width, 免得老式定宽页面显示变形。
	 * 改写后连续重跑数次, 防止网页自身脚本在加载完成后又改回去。
	 */
	private const val ENABLE_ZOOM_JS = """
		(function(){
			try{
				function fix(){
					var m = document.querySelector('meta[name=viewport]');
					if(!m){return;}
					var c = (m.getAttribute('content') || '').toLowerCase();
					var out = c
						.replace(/user-scalable\s*=\s*(no|0|false)/g, 'user-scalable=yes')
						.replace(/maximum-scale\s*=\s*[0-9.]+/g, 'maximum-scale=5');
					if(out.indexOf('user-scalable') < 0){ out += ', user-scalable=yes'; }
					if(out.indexOf('maximum-scale') < 0){ out += ', maximum-scale=5'; }
					if(out !== c){ m.setAttribute('content', out); }
				}
				fix();
				var n = 0, t = setInterval(function(){ n++; fix(); if(n >= 8){ clearInterval(t); } }, 700);
			}catch(e){}
		})();
	"""

	val EasWebPage = Page(Keys.Page.EasWebPage, "教务系统", iconRes = R.drawable.ic_school_yellow, enableDrawer = false) { activity, padding, navController, _ ->
		val viewModel by activity.viewModels<EasWebViewModel>()
		AppWidgets.CheckEasLogin(viewModel = viewModel, navController = navController)

		Box(modifier = Modifier.padding(padding)){
			WebScreen(viewModel = viewModel, mainController = navController, toast = activity.toast)
		}
	}

	val IpassWebPage = Page(Keys.Page.IpassWebPage, "数字青科", iconRes = R.drawable.ic_ipass_blue, enableDrawer = false) { activity, padding, navController, _ ->
		val viewModel by activity.viewModels<IpassWebViewModel>()
		LaunchedEffect(viewModel.needLogin){
			if(viewModel.needLogin) {
				// 【本期】"同一个登录界面出现两次"修复: 统一走防重入口
				// (已经在登录页 / 刚导航过就不再压第二层, 也不重复设置 leavingForLogin)
				viewModel.needLogin = false
				viewModel.navToLoginOnce(navController, Keys.Page.VpnLoginPage)
			}
		}
		Box(modifier = Modifier.padding(padding)){
			WebScreen(viewModel = viewModel, mainController = navController, toast = activity.toast)
		}
	}

	val MailPage = Page(Keys.Page.MailPage, "学校邮箱", iconRes = R.drawable.ic_mail_envelope, enableDrawer = false) { activity, padding, navController, _ ->
		val viewModel by activity.viewModels<MailWebViewModel>()
		// 【本期】兜底: 正常入口已被 Page.resolve 改道到登录页; 万一还有入口直接进本页(且没存账号),
		// 这里也只显示空白占位并立刻跳登录页 —— 不组合 WebScreen, 免得又出现"两次转场 + WebView 初始化"的卡顿。
		val hasMailAccount = Setting.getString(Keys.MAIL_ACCOUNT).isNotEmpty() &&
			Setting.getString(Keys.MAIL_PASSWORD).isNotEmpty()
		if(!hasMailAccount){
			LaunchedEffect(Unit){
				if(viewModel.leavingForLogin){
					// 从登录页按返回键取消: 连本页一起退出(与原来一致)
					viewModel.leavingForLogin = false
					navController.popBackStack()
					return@LaunchedEffect
				}
				viewModel.leavingForLogin = true
				viewModel.navToLoginOnce(navController, Keys.Page.MailLogin)
			}
			Box(modifier = Modifier.padding(padding).fillMaxSize())
		}else{
			LaunchedEffect(viewModel.needLogin){
				if(viewModel.needLogin){
					// 【本期】"第一次登录学校邮箱时同一个登录界面出现两次"修复:
					// 以前这里是"时间闸门 + 路由判断 + 直接 navigate", 而 needLogin 会被两处同时置 true
					// (页面入口判断 + 登录态校验), 只要两次触发拉开到闸门之外, 就会连着压两层「学校邮箱登陆」
					// 进回退栈 —— 视觉上就是同一个界面出现两次、后者盖住前者。改为统一走防重入口。
					viewModel.needLogin = false
					viewModel.navToLoginOnce(navController, Keys.Page.MailLogin)
				}
			}
			Box(modifier = Modifier.padding(padding)){
				WebScreen(viewModel = viewModel, mainController = navController, toast = activity.toast)
			}
		}
	}

	// 【第二十四轮】各部署"自主选课"的 gnmkdm 不同, 从教务主菜单自动解析; 解析失败时用兜底地址
	const val XSXK_FALLBACK_PATH = "jwglxt/xsxk/zzxkyzb_cxZzxkYzbIndex.html?gnmkdm=N253512&layout=default"

	val XsxkWebPage = Page(Keys.Page.XsxkWebPage, "自主选课", iconRes = R.drawable.ic_xsxk, enableDrawer = false) { activity, padding, navController, _ ->
		val viewModel by activity.viewModels<XsxkWebViewModel>()
		AppWidgets.CheckEasLogin(viewModel = viewModel, navController = navController)

		Box(modifier = Modifier.padding(padding)){
			WebScreen(viewModel = viewModel, mainController = navController, toast = activity.toast)
		}
	}

	val TiyuWebPage = Page(Keys.Page.SportTestPage, "智慧体测", iconRes = R.drawable.ic_campus_run, enableDrawer = false) { activity, padding, navController, _ ->
		val viewModel by activity.viewModels<TiyuWebViewModel>()
		LaunchedEffect(viewModel.needLogin){
			if(viewModel.needLogin) {
				// 【本期】"同一个登录界面出现两次"修复: 统一走防重入口
				// (已经在登录页 / 刚导航过就不再压第二层, 也不重复设置 leavingForLogin)
				viewModel.needLogin = false
				viewModel.navToLoginOnce(navController, Keys.Page.VpnLoginPage)
			}
		}
		Box(modifier = Modifier.padding(padding)){
			WebScreen(viewModel = viewModel, mainController = navController, toast = activity.toast)
		}
	}

	val SeatReservePage = Page(Keys.Page.SeatReservePage, "座位预约", iconRes = R.drawable.ic_seat, enableDrawer = false) { activity, padding, navController, _ ->
		val viewModel by activity.viewModels<SeatWebViewModel>()
		LaunchedEffect(viewModel.needLogin){
			if(viewModel.needLogin) {
				// 【本期】"同一个登录界面出现两次"修复: 统一走防重入口
				// (已经在登录页 / 刚导航过就不再压第二层, 也不重复设置 leavingForLogin)
				viewModel.needLogin = false
				viewModel.navToLoginOnce(navController, Keys.Page.VpnLoginPage)
			}
		}
		Box(modifier = Modifier.padding(padding)){
			WebScreen(viewModel = viewModel, mainController = navController, toast = activity.toast)
		}
	}

	@Composable
	fun WebScreen(viewModel: WebPageViewModel, mainController: NavController, toast: Toast, onBack: () -> Unit = { mainController.popBackStack() }) {
		var webView by remember { mutableStateOf<WebView?>(null) }
		val canGoBack = remember { mutableStateOf(false) }
		val canGoForward = remember { mutableStateOf(false) }
		// 【第六十五轮】同一地址 2 秒内不重复加载(防止一次跳转被加载两遍 -> 多次刷新/两次登录)
		var lastNavUrl by remember { mutableStateOf("") }
		var lastNavAt by remember { mutableStateOf(0L) }

		// 【第六十四轮】每次进入页面: 重置"一次性"登录标志, 退出后再次进入仍能自动登录
		// 【登录逻辑】从登录页返回时: 登录页点确定会清掉 leavingForLogin, 此时照常自动登录;
		// 按返回键取消登录时标记仍为 true, 直接把网页页一起退出, 防止再次被拉回登录页形成死循环。
		LaunchedEffect(Unit){
			if(viewModel.leavingForLogin){
				viewModel.leavingForLogin = false
				onBack()
			}else{
				viewModel.onPageOpened()
			}
		}
		LaunchedEffect(viewModel.reloadNonce){
			if(viewModel.reloadNonce > 0){
				try { viewModel.hasCheck = false } catch(_: Exception){ }
				viewModel.checkAccountLogin()
			}
		}
		LaunchedEffect(viewModel.hasCheck){
			if(!viewModel.hasCheck) viewModel.checkAccountLogin()
		}

		// 【第三十六轮】登录成功后按需注入 Cookie(shouldInjectCookies=false 的页面跳过, 保留网页自身会话)
		SideEffect {
			if(viewModel.shouldInjectCookies && viewModel.isLogin && !viewModel.injectedWithLogin){
				viewModel.injectCookies()
				viewModel.injectedWithLogin = true
			}
			// 【第五十九轮】页面加载完成 -> 同步底部栏"前进/后退"可用状态
			viewModel.onPageFinishedState = { v ->
				canGoBack.value = v.canGoBack()
				canGoForward.value = v.canGoForward()
			}
		}

		// 【第五十三轮】手机返回键"一步一步退": 先退网页历史, 退到底再退出本页
		BackHandler(enabled = webView?.canGoBack() == true) {
			webView?.goBack()
		}

		// 【第七十九轮·修"教务系统一直白屏卡在进度条"】
		// 网页加载进度 25 秒一动不动(学校网关偶发把请求吊死)时自动刷新一次;
		// 每个页面实例只兜一次底, 刷新后仍卡住就交给用户手动处理, 不做循环重试。
		var loadWatchdogUsed by remember { mutableStateOf(false) }
		LaunchedEffect(webView){
			if(webView == null || loadWatchdogUsed) return@LaunchedEffect
			// 【第八十九轮】教务页(教务系统/自主选课)收紧为 **10 秒**(用户要求"10 秒内没反应
			// 就自动走数字青科的教务系统"), 且不再原地 reload —— 走 markEasBlocked 统一出口
			// (已登录数字青科 -> 自动用数字青科通道重登教务; 没登录 -> 数字青科登录页 + 气泡);
			// 其余网页维持 25 秒刷新一次。"正在登录"弹窗挂着时不动(由登录流程自己的超时管)。
			val watchdogMs = if(viewModel.easNoCas) 10_000L else 25_000L
			var last = -1f
			var lastChange = System.currentTimeMillis()
			while(!loadWatchdogUsed){
				delay(2_000L)
				val p = viewModel.progress
				val now = System.currentTimeMillis()
				if(p != last){ last = p; lastChange = now }
				if(p < 100f && now - lastChange >= watchdogMs && viewModel.dialogText.isEmpty()){
					loadWatchdogUsed = true
					if(viewModel.easNoCas){
						viewModel.markEasBlocked()
					}else{
						webView?.reload()
					}
					break
				}
			}
		}

		// 【第五十九轮】网页与底部控制栏改为**上下结构**(不再用 BottomSheet 叠加在网页之上, 绝不遮挡网页);
		// 底栏与「科大官网」完全一致: [←后退][→前进][⟳刷新], 不可用时置灰。
		// 【修改版】每次进入判断账号: 为空才置 needLogin(由页面统一的导航 effect 弹一次登录界面)
		androidx.compose.runtime.LaunchedEffect(Unit){
			try {
				if(viewModel.requireAccountNameBeforeAutoLogin && viewModel.account.getAccount().isEmpty()){
					viewModel.hasCheck = true   // 防止 WebScreen 再发起一次登录检查(会重复弹登录页)
					viewModel.needLogin = true
				}
			}catch(_: Exception){ }
		}

		Column(modifier = Modifier.fillMaxSize()) {
			Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
				// 【修改版】WebView 创建后常驻: 页内跳转触发的登录复核不再销毁/重建 WebView
				var webAlive = remember { mutableStateOf(false) }
				androidx.compose.runtime.LaunchedEffect(viewModel.isLogin){ if(viewModel.isLogin) webAlive.value = true }
				// 账号为空/需登录时不显示旧网页, 直接显示登录界面(与学校邮箱一致)
				if((viewModel.isLogin || webAlive.value) && !viewModel.needLogin){
					AndroidView(
						factory = { context ->
							val myWebViewClient = viewModel.createWebViewClient(viewModel.safeStartUrl, { newUrl ->
								val wv = webView
								if(wv != null){
									val now = android.os.SystemClock.uptimeMillis()
									val dup = newUrl == lastNavUrl && now - lastNavAt < 2000L
									if(!dup){
										lastNavUrl = newUrl
										lastNavAt = now
										wv.loadUrl(newUrl)
									}
								}
							}, { })
							val wv = viewModel.createWebView(context, myWebViewClient)
							if(URLUtil.isValidUrl(viewModel.safeStartUrl) && !URLUtil.isValidUrl(wv.url)) {
								wv.loadUrl(viewModel.safeStartUrl)
							}
							webView = wv
							canGoBack.value = wv.canGoBack()
							canGoForward.value = wv.canGoForward()
							wv
						},
						modifier = Modifier.fillMaxSize()
					)
				}
				// 进度条悬浮在网页顶部(不挤占布局)
				LinearProgressIndicator(
					progress = { viewModel.progress },
					modifier = Modifier.fillMaxWidth().height(if (viewModel.progress == 100F) 0.dp else 5.dp).align(Alignment.TopCenter),
					color = MaterialTheme.colorScheme.primary,
				)
			}
			// 底部控制栏(与科大官网一致: 后退/前进/刷新)
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.height(50.dp)
					.background(MaterialTheme.colorScheme.surface),
				verticalAlignment = Alignment.CenterVertically
			) {
				IconButton(
					onClick = { webView?.let { if(it.canGoBack()) it.goBack() } },
					enabled = canGoBack.value,
					modifier = Modifier.weight(1f).fillMaxHeight()
				) { Icon(imageVector = Icons.AutoMirrored.Sharp.ArrowBack, contentDescription = "后退") }
				IconButton(
					onClick = { webView?.let { if(it.canGoForward()) it.goForward() } },
					enabled = canGoForward.value,
					modifier = Modifier.weight(1f).fillMaxHeight()
				) { Icon(imageVector = Icons.AutoMirrored.Sharp.ArrowForward, contentDescription = "前进") }
				IconButton(
					onClick = { if(!viewModel.isLogin) viewModel.checkAccountLogin() else webView?.reload() },
					modifier = Modifier.weight(1f).fillMaxHeight()
				) { Icon(imageVector = Icons.Rounded.Refresh, contentDescription = "刷新") }
			}
			AppWidgets.DialogBar(dialogText = viewModel.dialogText)
			toast.ToastContent(toastContent = viewModel.toastContent)
		}
	}

	class MyWebViewClient(
		val url: String,
		val onNewPage: (String) -> Unit,
		val onBlocked: (() -> Unit)? = null,
		val onPageFinishedCallback: ((WebView, String) -> Unit)? = null,
		/** 【第五十九轮】每次页面加载完成时回调(用于同步底部栏前进/后退状态) */
		val onPageState: ((WebView) -> Unit)? = null,
		/** 【第八十九轮】教务类网页(教务系统/自主选课)为 true: 任何情况不显示 CAS 网页登录界面 */
		val easNoCas: Boolean = false,
		/** 【第八十九轮】EAS 网页撞上 CAS 登录页时的统一出口(由 ViewModel 提供) */
		val onCasPage: (() -> Unit)? = null
	): WebViewClient() {

		// 【第七十九轮】CAS 登录页自动填充。
		// 【第八十七轮】"只试一次"改为"成功才停、最多 5 次": casFillDone=已经填过(或没凭据
		// 不填), casFillAttempts 是"还没填成功的尝试数" —— 密码填进去了才算成功。
		private var casFillDone = false
		private var casFillAttempts = 0
		/** 【第九十轮】教务页连续停在 CAS 登录页的次数: 只触发"藏页面+刷新重试自动填充",
		 *  重试多次仍不走数字青科分流 —— 分流只由"教务系统无法正常登录"的硬信号触发
		 *  (10 秒卡住 / 主框架 403 / 404 / 5xx / 加载失败, 见 onReceivedError 等)。 */
		private var casStall = 0
		/** 连续重试上限: 超过说明自动登录确实救不回来(等同无法正常登录), 此时才分流 */
		private val CAS_STALL_LIMIT = 5
		// 【修改版】恢复原版外部 scheme 处理: 微信/支付宝/QQ/电话等交给系统, 否则"点了没反应"
		private val EXTERNAL_SCHEMES = arrayOf(
			"weixin://", "alipay://", "alipays://", "mqq://", "mqqapi://", "qq://",
			"tbopen://", "tmall://", "intent://", "zhihu://", "baiduboxapp://",
			"tel:", "mailto:", "sms:", "geo:", "market:"
		)
		private var webViewContext: android.content.Context? = null

		private fun isExternalScheme(url: String): Boolean {
			val lower = url.lowercase()
			return EXTERNAL_SCHEMES.any { lower.startsWith(it) }
		}
		override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
			if(view == null || request == null) return false
			webViewContext = view.context

			val requestUrl = request.url.toString()

			// 【原版逻辑恢复】微信/支付宝/QQ/打电话 等: 交给系统处理, 否则会"点了没反应"
			if(isExternalScheme(requestUrl)){
				return try {
					val intent = Intent(Intent.ACTION_VIEW, Uri.parse(requestUrl)).addCategory(Intent.CATEGORY_BROWSABLE)
					intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
					view.context.startActivity(intent)
					true
				}catch(e: Exception){
					true
				}
			}

			if(!URLUtil.isValidUrl(requestUrl)) {
				try {
					view.context.startActivity(Intent(Intent.ACTION_VIEW, request.url))
				} catch(e: Exception) {
					// 【安全改造】不再把异常信息(可能含 URL/Cookie)写入日志
				}
				onBlocked?.invoke()
				return true
			}

			// 【修改版】学校升级后旧的自主选课地址已 404 -> 重写到新地址
			// 【修改版】学校门户里的“自主选课”链接已失效(404): 回到教务系统首页, 由用户在门户内重新进入
			// 【修改版】旧自主选课地址已 404 -> 改为原版的带参数地址
			if(requestUrl.contains("xsxkLogin_cxXsxkIndex.html")){
				val host = requestUrl.substringBefore("/jwglxt/").ifEmpty { "http://jwglxt.qust.edu.cn" }
				view.loadUrl("$host/jwglxt/xsxk/zzxkyzb_cxZzxkYzbIndex.html?gnmkdm=N253512&layout=default")
				return true
			}
			// 【修改版】不再拦截任何网页内导航: 一律由 WebView 原生加载, 保证页面内按钮/链接全部可用
			return false
		}

		override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) { handler?.proceed() }

		// 【第九十轮·按要求】"教务系统无法正常登录才走数字青科分流"的硬信号检测:
		// 10 秒卡住不动(看门狗)、主框架 403/404/5xx、主框架加载失败(连不上/域名解析失败/超时)。
		override fun onReceivedHttpError(view: WebView?, request: android.webkit.WebResourceRequest?, errorResponse: android.webkit.WebResourceResponse?) {
			super.onReceivedHttpError(view, request, errorResponse)
			try {
				if(easNoCas && request != null && android.os.Build.VERSION.SDK_INT >= 23 &&
					request.isForMainFrame && errorResponse != null){
					val code = errorResponse.statusCode
					if(code == 401 || code == 403 || code == 404 || code >= 500){
						onCasPage?.invoke()
					}
				}
			}catch(_: Exception){ }
		}

		override fun onReceivedError(view: WebView?, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?) {
			super.onReceivedError(view, request, error)
			try {
				if(easNoCas && request != null && android.os.Build.VERSION.SDK_INT >= 23 && request.isForMainFrame){
					onCasPage?.invoke()
				}
			}catch(_: Exception){ }
		}

		override fun onPageCommitVisible(view: WebView?, url: String?) {
			super.onPageCommitVisible(view, url)
			// 【本期】首屏一可见就注入缩放修复, 避免"刚进来捏不动、过一会才行"
			try { view?.evaluateJavascript(ENABLE_ZOOM_JS, null) } catch(_: Exception){ }
		}

		override fun onPageFinished(view: WebView?, url: String?) {
			// 【修改版】去掉网页链接的焦点框(黄色/橙色方框)
			try {
				view?.evaluateJavascript("(function(){var s=document.getElementById('qust_no_outline');if(!s){s=document.createElement('style');s.id='qust_no_outline';s.innerHTML='*:focus{outline:none !important;} a:focus{-webkit-tap-highlight-color:transparent;}';document.head&&document.head.appendChild(s);}})();", null)
			} catch(e: Exception){ }
			// 【本期】允许双指缩放(网页自身用 user-scalable=no 禁用了缩放, 这里改写 viewport)
			try { view?.evaluateJavascript(ENABLE_ZOOM_JS, null) } catch(_: Exception){ }
			// 【第九十轮·按用户反馈修正方向】教务系统网页**不该出现**统一身份(CAS)网页登录界面:
			//   · 真出现了 -> 先把页面内容藏掉(登录界面不露脸), **刷新重新尝试自动登录**
			//     (重载后下面的自动填充逻辑会再试一次), 最多重试 2 次;
			//   · 重试后仍停在这个页面 -> 才走数字青科统一出口(markEasBlocked: 已登录数字青科 ->
			//     自动用数字青科通道重登教务; 没登录 -> 气泡 + 数字青科登录页, 登录后自动接续);
			//   · 跳回非 CAS 页时恢复可见并清零计数。
			//   · 自动填充在教务页**不停用**(它是自动登录的关键), 与重试机制配合。
			try {
				if(view != null && url != null && easNoCas){
					if(isCasLoginPage(url)){
						casStall++
						view.evaluateJavascript("(function(){try{document.documentElement.style.visibility='hidden'}catch(e){}})();", null)
						if(casStall <= CAS_STALL_LIMIT){
							// 延时刷新: 给自动填充 JS 留出执行/提交的时间; 若页面已经跳走就不必刷了
							val casUrl = url
							android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
								try { if(view.url != null && view.url == casUrl) view.reload() } catch(_: Exception){ }
							}, 1200L)
						}else{
							// 重试多次仍停在 CAS: 自动登录确实救不回来, 等同"无法正常登录"
							onCasPage?.invoke()
						}
					}else if(casStall > 0){
						casStall = 0
						view.evaluateJavascript("(function(){try{document.documentElement.style.visibility='visible'}catch(e){}})();", null)
					}
				}
			}catch(_: Exception){ }
			// 【第七十九轮】统一身份认证(CAS)登录页自动填充+登录。
			// 【第八十七轮·修"1/3 概率停在 CAS 页不自动登录"】以前"每个网页实例只试一次":
			// CAS 页经几跳重定向才出来, 若第一次 onPageFinished 时表单还没渲染(nopwd),
			// 唯一的机会就被白白消耗, 后面真身来了反而不填 —— 正是"偶尔失败"的病根。
			// 现在改成"成功才停, 最多试 5 次": 失败(nopwd/err)不消耗资格, 下一页继续试;
			// 成功填充后标记 casFillDone, 密码错误导致的页面刷新也不会反复自动提交(防死循环)。
			try {
				if(view != null && url != null && !casFillDone && casFillAttempts < 5 && isCasLoginPage(url)){
					casFillAttempts++
					val user = Setting.getString(Keys.IPASS_ACCOUNT)
					val pwd = Setting.getString(Keys.IPASS_PASSWORD)
					if(user.isNotEmpty() && pwd.isNotEmpty()){
						view.evaluateJavascript(casFillJs(user, pwd)) { r ->
							if(r != null && (r.contains("filled") || r.contains("done"))){
								casFillDone = true
							}
						}
					}else{
						// 没存过统一身份凭据: 不填, 也不再重试(用户手动登一次即可)
						casFillDone = true
					}
				}
			} catch(_: Exception){ }
			super.onPageFinished(view, url)
			if(view != null && url != null) onPageFinishedCallback?.invoke(view, url)
			if(view != null) onPageState?.invoke(view)
		}
	}

	class XsxkWebViewModel : WebPageViewModel{

		override fun onPageOpened(){
			// 【修改版】每次进入/从登录页返回都重置, 立即重新自动登录(与学校邮箱一致)
			hasCheck = false
			injectedWithLogin = false
			isLogin = false
			needLogin = false
			leavingForLogin = false
			resetAutoEnterEas()
		}
		constructor(application: Application) : this(application, EASAccount.getInstance())
		private constructor(application: Application, account: Account) : super(application, account, easVpnUrl(account, XSXK_FALLBACK_PATH))

		/** 【第八十九轮】教务类网页: 任何情况不显示 CAS 网页登录界面, 撞上走数字青科统一出口 */
		override val easNoCas: Boolean get() = true

		/** 【本期】停在数字青科门户时自动进入教务管理系统 */
		override fun onPageLoaded(view: WebView, url: String) = autoEnterEasApp(view, url)

		/**
		 * 【本期】不再"账号名为空就直接弹登录页"。
		 *
		 * 教务已改为数字青科的一个业务应用, 进入页面时应当先自动尝试
		 * 「数字青科 → 业务应用 → 教务管理系统」; 只有在完全没有可用凭据时才让用户登录。
		 * 否则每次进页面都会被拉去账号登录页(用户看到"两次登录界面"), 且登录后仍进不来。
		 */
		override val requireAccountNameBeforeAutoLogin: Boolean get() = false

		override val pageSiteName: String get() = "教务系统"

		/** 【本轮】走哪条通道就注入哪个站点的 Cookie: 原网址直连 -> 教务域名; 数字青科 -> wvpn 主机 */
		override val cookieHost: String get() =
			if(account.easWebIsDirect) account.host else com.qust.helper.data.api.QustApi.VPN_HOST

		/** 【原版实现】从教务主菜单解析"自主选课"入口(菜单格式: goMenu('gnmkdm','url','自主选课') ) */
		private suspend fun resolveXsxkEntry(){
			try {
				account.getNoCheck(com.qust.helper.data.api.QustApi.EA_MAIN_MENU).use { resp ->
					val html = resp.body?.string() ?: return
					val m = "\\('([A-Za-z0-9]+)','([A-Za-z0-9/._\\-]*?)','自主选课".toRegex().find(html) ?: return
					val gnmkdm = m.groupValues[1]
					var path = m.groupValues[2].substringBefore('?').trim().removePrefix("/").removePrefix("jwglxt/")
					if(path.isEmpty()) path = "xsxk/zzxkyzb_cxZzxkYzbIndex.html"
					startUrl = easVpnUrl(account, "jwglxt/$path?gnmkdm=$gnmkdm&layout=default")
				}
			}catch(_: Exception){ }
		}

		override fun checkAccountLogin(){
			// 【修改版】本会话已登录过: 直接复用会话, 不再重复登录/注入(避免每次进入重复刷新、加载慢)
			if(injectedWithLogin && isLogin && (Setting.getString(Keys.IPASS_PASSWORD).isNotEmpty() || Setting.getString(Keys.EAS_PASSWORD).isNotEmpty())) return
			hasCheck = true
			// 【本期】教务已变成数字青科的一个业务应用: 这里只看"有没有可用凭据",
			// 不再看教务会话位(account.getAccount() 在"还没进过教务"时恒为空)。
			// 否则一进页面就被拉到账号登录页, 从登录页回来后仍然为空又拉一次
			// —— 这正是用户看到的"连着两次账号管理的登录界面、登录后也不行"。
			// 有凭据就交给 checkLogin() 自动走「数字青科 → 业务应用 → 教务管理系统」。
			val savedPass = Setting.getString(Keys.EAS_PASSWORD).ifEmpty { Setting.getString(Keys.IPASS_PASSWORD) }
			val hasAccount = Setting.getString(Keys.EAS_ACCOUNT).isNotEmpty() ||
				Setting.getString(Keys.IPASS_ACCOUNT).isNotEmpty()
			// 【本轮】教务账号密码按要求**不再保存**, 所以"存过密码"不能再当成唯一的准入条件:
			// 用教务系统原网址登录过、会话还在时, 直接把会话注入网页;
			// 否则一进页面就被拉去登录页、登录回来又因为"没存密码"被拉一次(来回死循环)。
			if(!account.easWebHasSession && (!hasAccount || savedPass.isEmpty())){
				injectedWithLogin = false
				isLogin = false
				needLogin = true
				return
			}
			viewModelScope.launch {
				showDialog("正在登录")
				var unreachable = false
				val autoLogin = withContext(Dispatchers.IO){
					try {
						// 【第七十八轮·按要求"8 秒没反应不要弹提示, 直接走数字青科里面的教务系统路线"】
						// 直连那条腿的 8 秒上限在 [VpnEASAccount.checkLogin] 内部(探测+补登共用 8 秒,
						// 到点立刻换道数字青科); 这里只兜一个**整体**上限, 防数字青科那条路把
						// "正在登录"无限挂住。超时不再像上一版那样"硬闯网页 + 弹提示",
						// 而是静默走 needLogin -> 「智慧青科大登陆」页自动接续 数字青科 → 教务;
						// 连统一身份凭据都没有时, 同一个登录页让用户登一次 —— 全程不弹气泡。
						val ok = withTimeoutOrNull(25_000L) {
							account.checkLogin()
							true
						}
						if(ok == null){ false }
						else{
							// 入口解析失败(页面结构变化等)不影响进入网页: 用兜底地址即可
							runCatching { resolveXsxkEntry() }
							true
						}
					}catch(e: NeedLoginException){ false }
					catch(e: Exception){ if(isSiteUnreachable(e)) unreachable = true; false }
				}
				if(autoLogin && account.isLogin){
					withContext(Dispatchers.IO){ injectCookies() }
					injectedWithLogin = true
				}else{
					injectedWithLogin = false
				}
				clearDialog()
				// 【第七十八轮】超时/没登上去 -> 一律静默转数字青科路线(needLogin), 不弹任何提示。
				// (上一版"timedOut 就硬闯网页 + toast"已删除 —— 用户点名要求去掉这个提示。)
				if(!autoLogin && !unreachable){
					injectedWithLogin = false
					isLogin = false
					needLogin = true
					return@launch
				}
				isLogin = true
				if(unreachable) toastWarning("「$pageSiteName」暂时访问失败，请稍后重试")
			}
		}

	}

	class EasWebViewModel : WebPageViewModel{

		override fun onPageOpened(){
			// 【修改版】每次进入/从登录页返回都重置, 立即重新自动登录(与学校邮箱一致)
			hasCheck = false
			injectedWithLogin = false
			isLogin = false
			needLogin = false
			leavingForLogin = false
			resetAutoEnterEas()
		}
		constructor(application: Application) : this(application, EASAccount.getInstance())
		private constructor(application: Application, account: Account) : super(application, account, easVpnUrl(account, "jwglxt/xtgl/index_initMenu.html"))

		/** 【第八十九轮】教务类网页: 任何情况不显示 CAS 网页登录界面, 撞上走数字青科统一出口 */
		override val easNoCas: Boolean get() = true

		/** 【本期】停在数字青科门户时自动进入教务管理系统 */
		override fun onPageLoaded(view: WebView, url: String) = autoEnterEasApp(view, url)

		/**
		 * 【本期】不再"账号名为空就直接弹登录页"。
		 *
		 * 教务已改为数字青科的一个业务应用, 进入页面时应当先自动尝试
		 * 「数字青科 → 业务应用 → 教务管理系统」; 只有在完全没有可用凭据时才让用户登录。
		 * 否则每次进页面都会被拉去账号登录页(用户看到"两次登录界面"), 且登录后仍进不来。
		 */
		override val requireAccountNameBeforeAutoLogin: Boolean get() = false

		/** 【第三十九轮】"暂时访问失败"提示中的站点名 */
		override val pageSiteName: String get() = "教务系统"

		/** 【本轮】走哪条通道就注入哪个站点的 Cookie: 原网址直连 -> 教务域名; 数字青科 -> wvpn 主机 */
		override val cookieHost: String get() =
			if(account.easWebIsDirect) account.host else com.qust.helper.data.api.QustApi.VPN_HOST

		override fun checkAccountLogin(){
			// 【修改版】本会话已登录过: 直接复用会话, 不再重复登录/注入(避免每次进入重复刷新、加载慢)
			if(injectedWithLogin && isLogin && (Setting.getString(Keys.IPASS_PASSWORD).isNotEmpty() || Setting.getString(Keys.EAS_PASSWORD).isNotEmpty())) return
			hasCheck = true
			// 【本期】教务已变成数字青科的一个业务应用: 这里只看"有没有可用凭据",
			// 不再看教务会话位(account.getAccount() 在"还没进过教务"时恒为空)。
			// 否则一进页面就被拉到账号登录页, 从登录页回来后仍然为空又拉一次
			// —— 这正是用户看到的"连着两次账号管理的登录界面、登录后也不行"。
			// 有凭据就交给 checkLogin() 自动走「数字青科 → 业务应用 → 教务管理系统」。
			val savedPass = Setting.getString(Keys.EAS_PASSWORD).ifEmpty { Setting.getString(Keys.IPASS_PASSWORD) }
			val hasAccount = Setting.getString(Keys.EAS_ACCOUNT).isNotEmpty() ||
				Setting.getString(Keys.IPASS_ACCOUNT).isNotEmpty()
			// 【本轮】教务账号密码按要求**不再保存**, 所以"存过密码"不能再当成唯一的准入条件:
			// 用教务系统原网址登录过、会话还在时, 直接把会话注入网页;
			// 否则一进页面就被拉去登录页、登录回来又因为"没存密码"被拉一次(来回死循环)。
			if(!account.easWebHasSession && (!hasAccount || savedPass.isEmpty())){
				injectedWithLogin = false
				isLogin = false
				needLogin = true
				return
			}
			viewModelScope.launch {
				showDialog("正在登录")
				var unreachable = false
				val autoLogin = withContext(Dispatchers.IO){
					try { account.checkLogin() }
					catch(e: NeedLoginException){ false }
					catch(e: Exception){ if(isSiteUnreachable(e)) unreachable = true; false }
				}
				if(autoLogin && account.isLogin){
					withContext(Dispatchers.IO){ injectCookies() }
					injectedWithLogin = true
				}else{
					injectedWithLogin = false
				}
				clearDialog()
				// 【第九十九轮·修"点教务系统只弹一个气泡, 页面没进"】有凭据但自动登录失败时
				// **不再直接置 needLogin 拉去登录页**(那样 CheckEasLogin 会立刻弹"暂时无法登录"
				// 并把人改道 —— 教务页面根本没显示出来)。改为照常进入教务网页让它自己加载:
				// 撞上 CAS 登录页或 10 秒无反应时, 由 markEasBlocked 统一出口接管 ——
				// "暂时无法登录"气泡从此只出现在**进入教务页面之后、长时间登录不上、
				// 尝试走数字青科路线之前**(与用户的期望位置一致)。
				if(!autoLogin && !unreachable){
					injectedWithLogin = false
					isLogin = true
					return@launch
				}
				isLogin = true
				if(unreachable) toastWarning("「$pageSiteName」暂时访问失败，请稍后重试")
			}
		}
	}

	class IpassWebViewModel : WebPageViewModel{

		override fun onPageOpened(){
			// 【修改版】每次进入/从登录页返回都重置, 立即重新自动登录(与学校邮箱一致)
			hasCheck = false
			injectedWithLogin = false
			isLogin = false
			needLogin = false
			leavingForLogin = false
		}
		constructor(application: Application) : this(application, IPassAccount.getInstance())
		private constructor(application: Application, account: Account) : super(application, account, "${account.scheme}://${account.host}/https/77726476706e69737468656265737421f9b95089342426557a1dc7af96/tp_up/view?m=up#act=portal/viewhome")

		override fun checkAccountLogin(){
			// 【修改版】本会话已登录过: 直接复用会话, 不再重复登录/注入(避免每次进入重复刷新、加载慢)
			if(injectedWithLogin && isLogin && (Setting.getString(Keys.IPASS_PASSWORD).isNotEmpty() || Setting.getString(Keys.EAS_PASSWORD).isNotEmpty())) return
			hasCheck = true
			// 【登录逻辑】账号或密码为空时先进入登录页
			if(account.getAccount().isEmpty() || Setting.getString(Keys.IPASS_PASSWORD).isEmpty()){
				injectedWithLogin = false
				isLogin = false
				needLogin = true
				return
			}
			viewModelScope.launch {
				showDialog("正在登录")
				var unreachable = false
				val autoLogin = withContext(Dispatchers.IO){
					try { account.checkLogin(); true }
					catch(e: NeedLoginException){ false }
					catch(e: Exception){ if(isSiteUnreachable(e)) unreachable = true; false }
				}
				clearDialog()
				if(autoLogin && account.isLogin){
					withContext(Dispatchers.IO){ injectCookies() }
					injectedWithLogin = true
					isLogin = true
				}else if(unreachable){
					// 【第三十九轮】站点暂时访问失败: 提示稍后再试, 不再引导手动登录
					toastWarning("「数字青科」暂时访问失败，请稍后重试")
				}else{
					injectedWithLogin = false
					needLogin = true
				}
			}
		}
	}

	/** 智慧体测: 登录行为逻辑与数字青科一致, 站点走 WebVPN */
	class TiyuWebViewModel : WebPageViewModel{

		override fun onPageOpened(){
			// 【修改版】每次进入/从登录页返回都重置, 立即重新自动登录(与学校邮箱一致)
			hasCheck = false
			injectedWithLogin = false
			isLogin = false
			needLogin = false
			leavingForLogin = false
		}
		constructor(application: Application) : this(application, IPassAccount.getInstance())
			private constructor(application: Application, account: Account) : super(
			application,
			account,
			"https://" + com.qust.helper.data.api.QustApi.VPN_HOST + "/" + com.qust.helper.utils.VpnEncodeUtils.encryptUrl("https://tiyu.qust.edu.cn/")
		)

		override fun checkAccountLogin(){
			// 【修改版】本会话已登录过: 直接复用会话, 不再重复登录/注入(避免每次进入重复刷新、加载慢)
			if(injectedWithLogin && isLogin && (Setting.getString(Keys.IPASS_PASSWORD).isNotEmpty() || Setting.getString(Keys.EAS_PASSWORD).isNotEmpty())) return
			hasCheck = true
			// 【登录逻辑】账号或密码为空时先进入登录页
			if(account.getAccount().isEmpty() || Setting.getString(Keys.IPASS_PASSWORD).isEmpty()){
				injectedWithLogin = false
				isLogin = false
				needLogin = true
				return
			}
			viewModelScope.launch {
				showDialog("正在登录")
				var unreachable = false
				val autoLogin = withContext(Dispatchers.IO){
					try { account.checkLogin(); true }
					catch(e: NeedLoginException){ false }
					catch(e: Exception){ if(isSiteUnreachable(e)) unreachable = true; false }
				}
				clearDialog()
				if(autoLogin && account.isLogin){
					withContext(Dispatchers.IO){ injectCookies() }
					injectedWithLogin = true
					isLogin = true
				}else if(unreachable){
					toastWarning("「智慧体测」暂时访问失败，请稍后重试")
				}else{
					injectedWithLogin = false
					needLogin = true
				}
			}
		}
	}

	/** 座位预约: 登录行为逻辑与智慧体测一致 */
	class SeatWebViewModel : WebPageViewModel{

		override fun onPageOpened(){
			// 【修改版】每次进入/从登录页返回都重置, 立即重新自动登录(与学校邮箱一致)
			hasCheck = false
			injectedWithLogin = false
			isLogin = false
			needLogin = false
			leavingForLogin = false
		}
		constructor(application: Application) : this(application, IPassAccount.getInstance())
		private constructor(application: Application, account: Account) : super(
			application,
			account,
			"https://" + com.qust.helper.data.api.QustApi.VPN_HOST + "/" + com.qust.helper.utils.VpnEncodeUtils.encryptUrl("https://zyyy.qust.edu.cn/")
		)

		override fun checkAccountLogin(){
			// 【修改版】本会话已登录过: 直接复用会话, 不再重复登录/注入(避免每次进入重复刷新、加载慢)
			if(injectedWithLogin && isLogin && (Setting.getString(Keys.IPASS_PASSWORD).isNotEmpty() || Setting.getString(Keys.EAS_PASSWORD).isNotEmpty())) return
			hasCheck = true
			// 【登录逻辑】账号或密码为空时先进入登录页
			if(account.getAccount().isEmpty() || Setting.getString(Keys.IPASS_PASSWORD).isEmpty()){
				injectedWithLogin = false
				isLogin = false
				needLogin = true
				return
			}
			viewModelScope.launch {
				showDialog("正在登录")
				var unreachable = false
				val autoLogin = withContext(Dispatchers.IO){
					try { account.checkLogin(); true }
					catch(e: NeedLoginException){ false }
					catch(e: Exception){ if(isSiteUnreachable(e)) unreachable = true; false }
				}
				clearDialog()
				if(autoLogin && account.isLogin){
					withContext(Dispatchers.IO){ injectCookies() }
					injectedWithLogin = true
					isLogin = true
				}else if(unreachable){
					toastWarning("「座位预约」暂时访问失败，请稍后重试")
				}else{
					injectedWithLogin = false
					needLogin = true
				}
			}
		}
	}

	/** 【第三十六轮起】学校邮箱: 独立账号, 会话由 WebView 持久管理(不做 Cookie 注入) */
	class MailWebViewModel : WebPageViewModel{
		constructor(application: Application) : super(application, MailAccount.getInstance(), "https://mail.qust.edu.cn/")
		override val shouldInjectCookies: Boolean = false
		private var autofillInjections = 0
		private var webViewRef: WebView? = null
		/** 【自动登录修复】正在等待登录表单渲染, 避免页面还没渲染完就只检查一次 */
		private var mailFormPolling = false

		override fun onPageOpened(){
			// 【第六十四轮】每次进入重置(退出后再次进入仍能自动登录)
			autofillInjections = 0
			mailFormPolling = false
			hasCheck = false
		}

		override fun checkAccountLogin(){
			// 【修改版】本会话已登录过: 直接复用会话, 不再重复登录/注入(避免每次进入重复刷新、加载慢)
			// 【本期·修复】这里以前判断的是「数字青科/教务」的密码位, 但学校邮箱是**独立账号**:
			// 判断错了会出现"登录过数字青科 -> 进入学校邮箱后既不注入也不弹登录页"的空白页。
			if(injectedWithLogin && isLogin && Setting.getString(Keys.MAIL_PASSWORD).isNotEmpty()) return
			hasCheck = true
			// 【登录逻辑】账号或密码任一为空都视为"账号管理里没有", 先弹登录页
			val saved = Setting.getString(Keys.MAIL_ACCOUNT)
			val savedPassword = Setting.getString(Keys.MAIL_PASSWORD)
			if(saved.isNullOrEmpty() || savedPassword.isNullOrEmpty()){
				injectedWithLogin = false
				isLogin = false
				needLogin = true
				return
			}
			autofillInjections = 0
			injectedWithLogin = true
			isLogin = true
		}

		private fun onMailPageFinished(view: WebView, url: String) {
			if(!url.contains("mail.qust.edu.cn")) return
			webViewRef = view
			// 【自动登录修复】页面加载完成时登录框可能还没被框架渲染出来。
			// 这里轮询等待表单出现, 出现后只注入一次填充脚本; 不再要求"刷新一次"。
			if(autofillInjections > 0 || mailFormPolling) return
			val account = Setting.getString(Keys.MAIL_ACCOUNT)
			val password = Setting.getString(Keys.MAIL_PASSWORD)
			if(account.isEmpty() || password.isEmpty()) return
			mailFormPolling = true
			viewModelScope.launch {
				var waited = 0L
				while(waited < 10000L && autofillInjections == 0){
					if(evalJs(webViewRef, MAIL_LOGIN_FORM_JS).trim('"') == "1"){
						autofillInjections = 1
						webViewRef?.evaluateJavascript(mailFillJs(jsString(account), jsString(password)), null)
						break
					}
					delay(400)
					waited += 400
				}
				mailFormPolling = false
			}
		}

		override fun createWebViewClient(url: String, onNewPage: (String) -> Unit, onBlocked: (() -> Unit)?): WebViewClient {
			return MyWebViewClient(url, onNewPage, onBlocked, onPageFinishedCallback = { view, u -> onMailPageFinished(view, u) }, onPageState = { v -> onPageFinishedState?.invoke(v) })
		}

		override fun createWebView(context: Context, webViewClient: WebViewClient, onNewPage: ((String) -> Unit)?): WebView {
			val view = super.createWebView(context, webViewClient, onNewPage)
			// 【修改版】关闭链接默认焦点高亮(黄色/橙色方框)
			try { if(android.os.Build.VERSION.SDK_INT >= 26) view.defaultFocusHighlightEnabled = false } catch(e: Exception){ }
			view.settings.builtInZoomControls = true
			view.settings.displayZoomControls = false
			webViewRef = view
			return view
		}
	}

	/** 【第三十六轮起】学校邮箱独立账号 */
	class MailAccount private constructor() : Account("mail.qust.edu.cn", Keys.MAIL_ACCOUNT, Keys.MAIL_PASSWORD, "https", "mail_web") {
		companion object {
			private var inst: MailAccount? = null
			fun getInstance(): MailAccount = inst ?: MailAccount().also { inst = it }
		}
		override suspend fun absCheckLogin(): Boolean = isLogin
		override suspend fun absLogin(account: String, password: String): Boolean = true
		override fun logout() {
			Setting.editSync { it.remove(Keys.MAIL_ACCOUNT).remove(Keys.MAIL_PASSWORD) }
			isLogin = false
		}
	}

	open class WebPageViewModel(application: Application, val account: Account, var startUrl: String): BaseEasViewModel(application){

		var isLogin by mutableStateOf(false)
		var hasCheck by mutableStateOf(false)
		/** 【修改版】递增即触发网页重新加载(登录页返回后自动登录/消除空白页) */
		var reloadNonce by mutableStateOf(0)
		var progress: Float by mutableFloatStateOf(0f)
		var injectedWithLogin by mutableStateOf(false)

		/** 【第五十九轮】页面加载完成回调(由 WebScreen 设置): 同步底部栏"前进/后退"可用状态 */
		var onPageFinishedState: ((WebView) -> Unit)? = null

		/** 【第六十四轮】每次进入页面时由 WebScreen 调用: 子类可在此重置"每次打开"的一次性状态
		 *  (否则退出登录后再次进入时, 一次性标志仍是 true, 自动登录就"失效"了)。 */
		open fun onPageOpened(){ }

		/**
		 * 【本期】跳登录页的统一入口(防"同一个登录界面弹两次")。
		 *
		 * `needLogin` 会在多处被置 true(页面入口判断、自动登录校验失败、401 等), 两处先后触发时
		 * 会**连着压两层同一个登录页**进回退栈: 用户看到的就是"登录界面出现两次、还互相覆盖"。
		 * 这里再加两道锁, 保证一次进入最多只出现一层登录界面:
		 * ① 回退栈里已经有这个登录页 -> 不再压;
		 * ② `launchSingleTop` -> 即使路由已存在也不会重复创建;
		 * ③ 1.2 秒时间闸门 -> 同一瞬间的多处触发只算一次。
		 */
		fun navToLoginOnce(navController: NavController, route: String, minIntervalMs: Long = 1200L) {
			val now = android.os.SystemClock.uptimeMillis()
			val inStack = try {
				navController.currentBackStack.value.any { it.destination.route == route }
			} catch(_: Exception) { false }
			if(inStack || now - lastLoginNavAt < minIntervalMs) return
			lastLoginNavAt = now
			// 标记"因登录而离开": 返回本页时 WebScreen 据此判断是继续自动登录还是退出页面
			leavingForLogin = true
			try {
				navController.navigate(route) { launchSingleTop = true }
			} catch(_: Exception) { }
		}

		/** 【第六十六轮】WebView 的 User-Agent: 探活请求必须带上同一个 UA ——
		 *  很多校内系统把会话与 UA 绑定, 用 OkHttp 默认 UA 去探活会被当成另一个客户端 -> 恒判未登录。 */
		var webViewUa: String = ""

		/** 【第三十九轮】"暂时访问失败"提示中的站点名; 子类可覆写 */
		open val pageSiteName: String get() = "该页面"

		/** 【本期】是否沿用"账号名为空就直接要求登录"的旧逻辑。
		 *  教务页(教务系统/自主选课)改为先尝试自动进入(数字青科 SSO), 覆写为 false。 */
		open val requireAccountNameBeforeAutoLogin: Boolean get() = true

		/** 【第八十九轮】教务类网页(教务系统/自主选课)为 true: 任何情况不显示 CAS 网页登录界面,
		 *  撞上就走 [markEasBlocked] 统一出口(数字青科路线); 其余网页保留 CAS 自动填充。 */
		open val easNoCas: Boolean get() = false

		/** 【第八十九轮】教务网页撞上 CAS 登录页/10 秒无反应的统一出口:
		 *  记"外部原因"(输密码也没用) -> needLogin, 由 CheckEasLogin 分流:
		 *  已登录数字青科 -> 气泡"正在用数字青科自动登录" + autoLoginEas() 重登教务并注入网页;
		 *  没登录 -> 气泡"教务系统暂时无法登录, 请先登录数字青科" + 数字青科登录页。
		 *  上限 3 次: 万一数字青科通道也反复被带回 CAS, 第 4 次起放行(宁显示不死循环)。 */
		private var easBlockTries = 0
		fun markEasBlocked(){
			if(needLogin) return
			easBlockTries++
			if(easBlockTries > 3) return
			com.qust.helper.model.account.EasLoginState.mark(
				com.qust.helper.model.account.EasLoginState.Reason.EXTERNAL,
				"教务网页撞上CAS登录页或10秒无反应")
			needLogin = true
		}

		/** 【第八十九轮】CheckEasLogin 的"已登录数字青科 -> 自动登录教务"接续点:
		 *  登录成功后把教务会话注入网页并置 isLogin —— needLogin 翻回 false 时被隐藏的
		 *  网页会以全新实例重建(AndroidView factory 重新 loadUrl), 带着新 Cookie 直接进教务。 */
		override suspend fun autoLoginEas(): Boolean {
			val ok = super.autoLoginEas()
			if(ok){
				withContext(Dispatchers.IO){ injectCookies() }
				injectedWithLogin = true
				isLogin = true
			}
			return ok
		}

		/** 【第三十九轮】经过校验的起始地址: 为空或非法时回退到账号主页地址 */
		val safeStartUrl: String
			get() = if(startUrl.isNotBlank() && URLUtil.isValidUrl(startUrl)) startUrl else "${account.scheme}://${account.host}"

		/** 【第三十六轮】是否在登录成功后把账号 Cookie 注入 WebView(学校邮箱为 false) */
		open val shouldInjectCookies: Boolean = true

		val webChromeClient = object : WebChromeClient() {
			override fun onProgressChanged(view: WebView, newProgress: Int) {
				super.onProgressChanged(view, newProgress)
				progress = (newProgress / 100f).coerceIn(0f, 1f)
			}
		}

		/** 各页面自行覆写: 未登录/不可达时的处理与提示 */
		open fun checkAccountLogin(){			hasCheck = true
			viewModelScope.launch {
				showDialog("正在登录")
				var unreachable = false
				var needLoginFail = false
				val autoLogin = withContext(Dispatchers.IO){
					try { account.checkLogin() }
					catch(e: NeedLoginException){ needLoginFail = true; false }
					catch(e: Exception){ if(isSiteUnreachable(e)) unreachable = true else needLoginFail = true; false }
				}
				if(autoLogin && account.isLogin){
					withContext(Dispatchers.IO){ injectCookies() }
					injectedWithLogin = true
				}else{
					injectedWithLogin = false
				}
				clearDialog()
				isLogin = true
				if(!autoLogin){
					if(unreachable) toastWarning("「$pageSiteName」暂时访问失败，请稍后重试")
					else if(needLoginFail) toastWarning("未能自动登录，请在网页中手动登录")
				}
			}
		}

		/** 【本期】注入/清理 WebView Cookie 时使用的站点。
		 *  教务系统改为经数字青科(WebVPN)访问后, 会话 Cookie 在 wvpn 主机上, 由子类覆写。 */
		protected open val cookieHost: String get() = account.host

		fun injectCookies(){
			val cookieManager = CookieManager.getInstance()
			cookieManager.setAcceptCookie(true)
			clearSiteCookies(cookieHost)
			val baseUrl = "${account.scheme}://$cookieHost"
			for(cookieString in account.getCookie()) {
				cookieManager.setCookie(baseUrl, cookieString.toString())
			}
			cookieManager.flush()
		}

		/** 清除某个站点在 WebView 里的全部 Cookie(用于"重新登录"与注入前清理) */
		protected fun clearSiteCookies(host: String){
			try {
				val cookieManager = CookieManager.getInstance()
				for(scheme in arrayOf("https", "http")){
					val url = "$scheme://$host"
					val cookies = cookieManager.getCookie(url) ?: continue
					for(cookie in cookies.split(";")){
						val name = cookie.substringBefore("=").trim()
						if(name.isEmpty()) continue
						cookieManager.setCookie(url, "$name=; Max-Age=0; path=/")
						cookieManager.setCookie(url, "$name=; Max-Age=0; path=/; domain=$host")
						cookieManager.setCookie(url, "$name=; Max-Age=0; path=/; domain=.$host")
					}
				}
				cookieManager.flush()
			}catch(_: Exception){ }
		}

		/** 【本期】"自动进入教务管理系统"本页已经尝试的次数(防止在门户页反复重试) */
		private var easAutoEnterTries = 0

		/** 【本期】页面加载完成后回调: 子类可在此做页面级处理(如从门户自动进入教务) */
		open fun onPageLoaded(view: WebView, url: String) { }

		/** 【本期】重置"自动进入教务"的尝试计数(每次进入页面时调用) */
		protected fun resetAutoEnterEas() { easAutoEnterTries = 0 }

		/**
		 * 【本期】教务网页停在「数字青科」门户(业务应用列表)时, 自动打开「教务管理系统」。
		 *
		 * 走 WebVPN 打开教务, 学校有时不直接进教务, 而是把页面停在数字青科门户,
		 * 需要用户自己点「业务应用 → 教务管理系统」。这里在页面加载完成后找出该链接并打开,
		 * 让用户"点一下教务系统就直接进教务"。
		 *
		 * 只在"经 WebVPN 访问、且当前不是教务页面"时尝试 —— 教务页面的路径里一定带 jwglxt,
		 * 因此真正进了教务之后不会再干预; 最多尝试 3 次, 避免门户页结构变化时来回跳。
		 */
		protected fun autoEnterEasApp(view: WebView, url: String) {
			if(url.contains("jwglxt")) { easAutoEnterTries = 0; return }
			if(!url.contains("/https/")) return
			if(easAutoEnterTries >= 3) return
			easAutoEnterTries++
			viewModelScope.launch {
				delay(600)
				val href = evalJs(view, EAS_APP_LINK_JS).trim().trim('"')
				if(href.startsWith("http") && href != url && href != view.url) {
					view.loadUrl(href)
				}
			}
		}

		open fun createWebViewClient(url: String, onNewPage: (String) -> Unit, onBlocked: (() -> Unit)? = null): WebViewClient =
			MyWebViewClient(
				url, onNewPage, onBlocked,
				onPageFinishedCallback = { v, u -> onPageLoaded(v, u) },
				onPageState = { v -> onPageFinishedState?.invoke(v) },
				easNoCas = easNoCas,
				onCasPage = { markEasBlocked() }
			)

		open fun createWebView(context: Context, webViewClient: WebViewClient, onNewPage: ((String) -> Unit)? = null): WebView{
			val webView = WebView(context)
			// 【第六十八轮】白色背景: 避免网页首帧渲染前出现"黑屏一闪"
			webView.setBackgroundColor(android.graphics.Color.WHITE)
			webView.isVerticalScrollBarEnabled = true
			// 【第六十六轮】记录 WebView 的 UA, 探活请求要带同一个 UA
			webViewUa = webView.settings.userAgentString ?: ""
			webView.settings.also { webSettings ->
				webSettings.allowFileAccess = true
				webSettings.cacheMode = WebSettings.LOAD_DEFAULT
				webSettings.domStorageEnabled = true
				webSettings.javaScriptEnabled = true
				webSettings.loadWithOverviewMode = true
				// 【本期】缩放与「科大官网」完全一致: 允许双指放大/缩小, 隐藏自带的 +/- 悬浮按钮
				webSettings.setSupportZoom(true)
				webSettings.builtInZoomControls = true
				webSettings.displayZoomControls = false
				webSettings.useWideViewPort = true
				webSettings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
				// 【修改版】关闭多窗口: 本版本没有 onCreateWindow 处理, 开启后 target=_blank/window.open 的点击会被静默丢弃
				webSettings.setSupportMultipleWindows(false)

			}
			CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

			webView.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
			webView.webChromeClient = webChromeClient
			webView.webViewClient = webViewClient
			webView.setDownloadListener { downloadUrl, _, _, _, _ ->
				try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl)).addCategory(Intent.CATEGORY_BROWSABLE)) } catch(_: Exception) { }
			}

			return webView
		}
	}
}
