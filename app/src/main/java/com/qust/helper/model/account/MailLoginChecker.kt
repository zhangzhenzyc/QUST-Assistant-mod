/*
 * 青科助手（修改版） —— 基于开源项目 QUST-Assistant 修改而成
 *
 * 原项目版权所有 (C) 2891954521  (https://github.com/2891954521/QUST-Assistant)
 * 原始开源许可: GNU General Public License v3.0 (GPL-3.0), 许可原文见 LICENSE 文件, 保持完整未改动。
 *
 * 修改版版权所有 (C) 2026 zhangzhenzyc  (https://github.com/zhangzhenzyc)
 * 本修改版同样以 GPL-3.0 发布; 附加版权声明见 NOTICE.md —— 两份版权声明相互独立、同时生效。
 */

package com.qust.helper.model.account

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.net.Uri
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * 学校邮箱「后台自动登录」的凭据校验。
 *
 * 用户要求(第三十二轮): "在账号管理中登录学校邮箱, 不要直接跳转网页, 要在后台自动登录,
 * 确保正确登录, 出现二次验证后再保存账号和密码, 保存后退出界面, 密码或者账号不正确要给出
 * 气泡提示。"
 *
 * 做法: 用一个**不可见的 WebView** 打开 mail.qust.edu.cn(学校邮箱是 Coremail), 用与网页登录
 * 同一套脚本填账号密码并提交, 然后轮询页面状态。
 *
 * ---
 * 【第三十五轮·修"学校邮箱还是登录不了"】上一版有三个硬伤, 任何一个都会让"明明能登却报失败":
 *
 * 1. **WebView 没有挂到窗口上**。上一版只 `WebView(activity)` + `layout(0,0,1,1)`, 从头到尾
 *    没有 `addView` —— 这个 WebView 的 `onAttachedToWindow` 永远不会被调用, 在 MIUI/部分
 *    ROM 上页面会被当成"不可见页面"节流: 请求能发出去, 但 JS 定时器被降频、`evaluateJavascript`
 *    迟迟不回调、表单永远填不上。表现就是"点登录, 转半天, 报暂时无法登录"。
 *    现在把它挂进 Activity 的 decorView(1×1、近乎全透明、不可点), 它是**真的在窗口里渲染**的,
 *    只是用户看不见 —— 这才是"Coremail 网页自己跑一遍登录"应该有的前提。
 *
 * 2. **成功判据全靠猜 DOM**。Coremail 登录成功后地址栏经常还是 `mail.qust.edu.cn/`,
 *    页面标题也不变, 上一版只能看"登录表单还在不在"。但登录页本身还有 main/iframe 结构、
 *    跳转途中会白屏几帧, 判据很容易落空。现在补上**唯一权威的判据: 会话 Cookie**。
 *    登录成功的标志是服务端下发了 `Coremail.sid`; 登录前后各读一次, 变了就是真登上了,
 *    跟页面长什么样完全无关。(这条也顺带解决了"上次已经登过、这次直接进邮箱"的情况。)
 *
 * 3. **表单可能在 iframe 里**。Coremail 有的版本把登录框放在同源 iframe 内,
 *    `document.querySelector('input[type=password]')` 在主文档里一张都找不到 ——
 *    填表脚本空转、探针一直报 BLANK, 最后超时。现在探针和填表都会**遍历同源 iframe**。
 *
 * 另外补了这些(都是真机上会遇到的):
 *  · `select#domain`(域名下拉): 账号写成 `学号@qust.edu.cn` 时, 拆成"学号" + 选域名后缀;
 *  · 提交按钮选择器: 上一版只认 `button.j-submit`, 但 Coremail 用的是 `<input class="j-submit">`,
 *    标签名一卡就点不到; 现在 `.j-submit` 不限标签, 再补 `#loginBtn` 等;
 *  · 提交方式: 优先 `click()` / `form.requestSubmit()`(会走页面自己的 onsubmit, 密码该加密的
 *    还是加密), 不再一上来就 `form.submit()` —— 那会**绕过** onsubmit, 把明文密码直接发出去,
 *    服务端必然不认;
 *  · `innerText` 之外再取 `textContent`: 不可见页面的 `innerText` 有时是空串;
 *  · 同一页面只注入一次填表脚本(`window.__qustFill` 去重), 避免多次跳转后累积定时器、
 *    反复提交触发风控;
 *  · 证书: 学校站点历史上用过自签/过期证书, 只在 host 属于 `*.qust.edu.cn` 时放行 SSL 错误
 *    (别的域名一律不放行)。
 *
 * 为什么不用 OkHttp 硬拼: Coremail 登录要先拿页面里的隐藏字段/rand, 还要 RSА/验证码,
 * 直接拼 HTTP 很容易"今天能登、明天失效"; 跑网页自己的登录流程最稳, 而且登完 WebView 的
 * Cookie 就是登录态 —— 之后打开「学校邮箱」页直接是已登录状态。
 */
object MailLoginChecker {

	private const val HOME = "https://mail.qust.edu.cn/"

	/** 探测间隔 */
	private const val POLL_MS = 350L

	/** 首次探测延时: 太早页面还没渲染, 探到的只是空白 */
	private const val FIRST_POLL_MS = 600L

	/** 整体超时 */
	private const val DEFAULT_TIMEOUT_MS = 18_000L

	/**
	 * 填表脚本每 300ms 跑一轮, 第 2 轮(≈600ms)就会点提交; 留 1.4 秒余量再认定"已经提交过",
	 * 避免把"还没提交"当成"提交后表单消失"。
	 */
	private const val SUBMIT_AFTER_MS = 1_400L

	enum class Outcome {
		/** 凭据正确(已到二次验证, 或直接进了邮箱) */
		OK,
		/** 账号或密码错误 */
		BAD_PASSWORD,
		/** 连不上学校邮箱(校外网络 / 服务维护) */
		NETWORK,
		/** 登录页要求填图形/短信验证码, 后台没法替人填 —— 只能请用户到邮箱页手动登录一次 */
		NEED_CAPTCHA,
		/** 连上了但没能在超时内确认结果 */
		UNAVAILABLE
	}

	/**
	 * 同源 iframe 一起遍历。
	 *
	 * Coremail 有版本把登录框放在 iframe 里; 只在主文档里找会一直找不到表单项。
	 * 跨域 iframe(比如统一认证)访问 `contentDocument` 会抛异常, 直接跳过。
	 */
	private const val DOCS_JS = """
		function __qustDocs(){
			var out=[document];
			try{
				var fs=document.querySelectorAll('iframe');
				for(var i=0;i<fs.length;i++){
					try{var d=fs[i].contentDocument; if(d&&d.body) out.push(d);}catch(e){}
				}
			}catch(e){}
			return out;
		}
	"""

	/**
	 * 探针: 返回页面当前处于哪一种状态。
	 *
	 * 只报"看到什么", 不做判断(判断放在 Kotlin 里, 因为要结合"到底提交过没有""Cookie 变了没"才算得准)。
	 *   SECOND_AUTH / BAD_PWD / FORM / MAIL / BLANK
	 */
	private const val PROBE_JS = """
		(function(){
			try{
				$DOCS_JS
				var ds=__qustDocs(),i,d,bl='';
				// ① 二次验证: 出现即说明账号密码已经被接受了。
				//    Coremail 自家的是 .j-second-auth-wrap; 跳到统一身份认证时是"身份验证/二次验证"
				//    这类整页提示, 两种都要认 —— 用户的诉求本来就是"出现二次验证后再保存账号密码"。
				for(i=0;i<ds.length;i++){
					d=ds[i];
					var sa=d.querySelector('.j-second-auth-wrap,.j-second-auth,.second-auth,.j-verify,.verify-wrap');
					if(sa){var cn=sa.className||''; if(cn.indexOf('f-dn')<0) return 'SECOND_AUTH';}
				}
				// ② 明确的错误提示(只收"凭据不对"这一类的, 网络/维护的失败不在此列)
				var bad=['密码错误','密码不正确','用户名或密码','账号或密码','用户不存在','认证失败',
					'验证码错误','账号已被锁定','密码已过期','用户名不存在','登录名或密码',
					'invalid user name or password','incorrect password','authentication failed'];
				for(i=0;i<ds.length;i++){
					d=ds[i];
					try{ bl+=' '+((d.body&&d.body.innerText)||'')+' '+((d.body&&d.body.textContent)||''); }catch(e){}
					var err=d.querySelector('.j-error,.u-error,#errorTips,.error-tips,.login-error,#errorText,.err-tip');
					if(err){
						var et=((err.innerText||'')+(err.textContent||''));
						if(et.replace(/[\s\u00a0]/g,'').length>0) return 'BAD_PWD';
					}
				}
				var low=bl.toLowerCase();
				for(i=0;i<bad.length;i++){ if(low.indexOf(bad[i].toLowerCase())>=0) return 'BAD_PWD'; }
				if(low.indexOf('二次验证')>=0||low.indexOf('身份验证')>=0) return 'SECOND_AUTH';
				// ③ 登录表单还在页面上(还没提交成功, 或者刚提交、页面还没跳走)
				var hasForm=false,hasCaptcha=false;
				for(i=0;i<ds.length;i++){
					d=ds[i];
					if(d.querySelector('input[type=password]')||d.getElementById('uid')
						||d.querySelector('input[name=uid],input[name=username],input#username')) hasForm=true;
					if(d.querySelector('input[name=captcha],input#captcha,img[src*=captcha],img[src*=verifyCode]')) hasCaptcha=true;
				}
				if(hasForm) return hasCaptcha?'CAPTCHA':'FORM';
				// ④ 已经进了邮箱主界面
				if(low.indexOf('收件箱')>=0||low.indexOf('写邮件')>=0||low.indexOf('退出')>=0||low.indexOf('inbox')>=0) return 'MAIL';
				for(i=0;i<ds.length;i++){
					if(ds[i].querySelector('#dvMain,#mailList,.cm-list,frameset,#mailListFrame,#frameMain')) return 'MAIL';
				}
				return 'BLANK';
			}catch(e){ return 'BLANK'; }
		})();
	"""

	/**
	 * 填表 + 提交。
	 *
	 * 用 `Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set` 赋值 ——
	 * 有些新版登录页用框架渲染, 直接 `el.value=x` 改不到框架的内部状态(框架只认它自己的 setter),
	 * 走原型上的原生 setter 再补发 input/change 事件, 两边都能认。
	 */
	private fun fillJs(account: String, password: String): String {
		val acc = jsEscape(account)
		val pwd = jsEscape(password)
		return """
			(function(){
				try{
					if(window.__qustFill) return;
					window.__qustFill=1;
					$DOCS_JS
					var ACC='$acc',PWD='$pwd',n=0,t=setInterval(function(){
						n++;
						if(n>25){clearInterval(t);return;}
						var ds=__qustDocs(),p=null,u=null,doc=null,k,d;
						for(k=0;k<ds.length;k++){
							d=ds[k];
							var pp=d.querySelector('input[type=password]');
							if(pp){
								p=pp; doc=d;
								u=d.getElementById('uid')||d.getElementById('username')||d.getElementById('userName')
									||d.querySelector('input[name=uid],input[name=username],input[name=user],input[name=account],input[name=loginName],input#username,input#userName');
								break;
							}
						}
						if(!p){return;}   // 已经没有密码框了: 可能已经登进去了, 交给探针/Cookie 判断
						var at=ACC.indexOf('@');
						var uidValue=ACC;
						// 域名下拉(select#domain): 账号带 @ 时拆成"学号" + 选后缀, 不带就原样填
						try{
							var sd=doc.querySelector('select#domain,select[name=domain],select#loginDomain');
							if(sd){
								var seg=at>=0?ACC.substring(at+1):'',sel=-1;
								for(var i=0;i<sd.options.length;i++){
									var v=(sd.options[i].value||'')+' '+(sd.options[i].text||'');
									if(seg&&v.indexOf(seg)>=0){sel=i;break;}
								}
								if(sel<0){for(var i=0;i<sd.options.length;i++){if((sd.options[i].value||'')!==''){sel=i;break;}}}
								if(sel>=0){sd.selectedIndex=sel;try{sd.dispatchEvent(new Event('change',{bubbles:true}))}catch(e){}}
								if(at>=0) uidValue=ACC.substring(0,at);
							}
						}catch(e){}
						var setV=function(el,val){
							try{
								var proto=(el.tagName==='TEXTAREA')?window.HTMLTextAreaElement.prototype:window.HTMLInputElement.prototype;
								var setter=Object.getOwnPropertyDescriptor(proto,'value').set;
								setter.call(el,val);
							}catch(e){ try{el.value=val}catch(e2){} }
							try{el.dispatchEvent(new Event('input',{bubbles:true}))}catch(e){}
							try{el.dispatchEvent(new Event('change',{bubbles:true}))}catch(e){}
						};
						if(u) setV(u,uidValue);
						setV(p,PWD);
						if(n===2||n===5){
							var btn=null;
							for(k=0;k<ds.length;k++){
								var d2=ds[k];
								btn=d2.querySelector('.j-submit')||d2.querySelector('#loginBtn')
									||d2.querySelector('.loginBtn,.j-login')
									||d2.querySelector('input[type=submit]')||d2.querySelector('button[type=submit]');
								if(btn) break;
							}
							// 统一身份认证那类页面, 按钮上没有可认的 class, 只能看文字:
							// <button>登录</button> / <a>立即登录</a> / <div role=button>登 录</div>
							if(!btn){
								for(k=0;k<ds.length&&!btn;k++){
									var cand=ds[k].querySelectorAll('button,a,input[type=button],div[role=button],span[role=button]');
									for(var j=0;j<cand.length;j++){
										var tx=((cand[j].innerText||'')+''+(cand[j].value||'')).replace(/[\s\u00a0]/g,'');
										if(tx==='登录'||tx==='登陆'||tx==='立即登录'||tx==='登录/Login'||tx.toLowerCase()==='login'||tx.toLowerCase()==='signin'){
											btn=cand[j]; break;
										}
									}
								}
							}
							if(btn){ try{btn.click();}catch(e){} }
							else{
								// 没有可点的按钮时才退到 submit。优先 requestSubmit: 它会走页面自己的
								// onsubmit(密码该加密的仍然加密); form.submit() 会绕过 onsubmit, 发出去的是明文。
								try{
									var f=p.form||(doc&&doc.forms&&doc.forms[0]);
									if(f){ if(f.requestSubmit) f.requestSubmit(); else f.submit(); }
								}catch(e){}
							}
						}
					},300);
				}catch(e){}
			})();
		"""
	}

	/** 把字符串安全地塞进 JS 单引号字面量 */
	private fun jsEscape(s: String): String = s
		.replace("\\", "\\\\")
		.replace("'", "\\'")
		.replace("\r", "\\r")
		.replace("\n", "\\n")

	/**
	 * 会话指纹: 把几个"只在登录后才会出现/变化"的 Cookie 拼起来。
	 *
	 * 单看 `Coremail.sid` 不够: 登录页一打开就会下发一个**匿名** sid, 登录失败时也可能换一个。
	 * 所以只把它当"辅助加速判据" —— 必须同时满足"已经提交过 + 页面上没有错误提示", 指纹变了
	 * 才算登录成功(见 `check` 里的轮询)。
	 */
	private fun sessionFingerprint(cm: CookieManager?): String {
		if(cm == null) return ""
		val all = runCatching { cm.getCookie(HOME) }.getOrNull() ?: return ""
		val sb = StringBuilder()
		for(part in all.split(';')) {
			val kv = part.trim()
			for(name in arrayOf("Coremail.sid", "Coremail.user")) {
				if(!kv.startsWith("$name=", true)) continue
				val v = kv.substring(name.length + 1).trim()
				if(v.isNotEmpty()) sb.append(name).append('=').append(v).append(';')
			}
		}
		return sb.toString()
	}

	/**
	 * 把 1×1 的 WebView 挂进当前窗口。
	 *
	 * 为什么必须挂: 没 attach 过的 WebView 在很多 ROM 上会被当成"不可见页面"节流 ——
	 * JS 定时器降频、`evaluateJavascript` 迟迟不回调, 自动填表压根跑不起来。
	 * 挂在 decorView 上(1×1、alpha 0.02、不可点、不可聚焦)既保证它真的在渲染,
	 * 又完全不影响用户看到的界面和触摸。
	 */
	private fun attachHost(activity: Activity, webView: WebView): ViewGroup? = runCatching {
		val decor = activity.window?.decorView as? ViewGroup ?: return@runCatching null
		val host = FrameLayout(activity)
		host.addView(webView, FrameLayout.LayoutParams(1, 1))
		host.alpha = 0.02F
		host.isClickable = false
		host.isFocusable = false
		decor.addView(host, FrameLayout.LayoutParams(1, 1))
		host
	}.getOrNull()

	/**
	 * 后台校验一次学校邮箱账号密码(必须在主线程创建 WebView, 这里已经切好线程)。
	 *
	 * @param context 传 Activity 时会挂到它的窗口上(推荐); 传 Application 也能跑, 只是少了那层保障
	 */
	@SuppressLint("SetJavaScriptEnabled")
	suspend fun check(
		context: Context,
		account: String,
		password: String,
		timeoutMs: Long = DEFAULT_TIMEOUT_MS
	): Outcome = withContext(Dispatchers.Main) {
		suspendCancellableCoroutine { cont ->
			val handler = Handler(Looper.getMainLooper())
			var done = false
			var webView: WebView? = null
			var host: ViewGroup? = null
			var landedUrl = HOME

			/** 页面上确实出现过登录表单(说明填表脚本有东西可填, 不是空白页) */
			var sawForm = false
			/** 登录页要验证码: 后台替人填不了, 超时时按这个给出准确提示 */
			var sawCaptcha = false
			/** 开始填表的时间(脚本会在 0.6 秒左右自己点提交) */
			var fillAt = 0L
			/** "登录表单消失"连续成立的次数: 连拍两次才算数, 防止跳转中途的白屏误判 */
			var noFormHits = 0
			/** 主文档加载失败(连不上 / 证书 / DNS): 用来把"网络不通"与"登不进去"分开报 */
			var loadFailed = false

			val cookieManager = runCatching {
				CookieManager.getInstance().also { it.setAcceptCookie(true) }
			}.getOrNull()
			/**
			 * 填表那一刻的会话 id。
			 *
			 * 注意这里**不能**用"check 开头读到的 Cookie 做基线": Coremail 的登录页一打开就会下发
			 * 一个**匿名** `Coremail.sid`, 如果拿"开头(还没有任何请求)拿到的 null"当基线, 页面一加载
			 * 就会立刻误判成"sid 变了 = 登录成功"。所以基线取在"填表开始"这一刻 ——
			 * 此后 sid 若再变, 才是登录换来的。
			 */
			var sidBeforeFill: String? = null
			/** Cookie 是跨进程查询, 别 350ms 查一次; 每 3 次轮询查一次就够 */
			var cookieTick = 0

			fun submitted(): Boolean =
				fillAt > 0L && SystemClock.elapsedRealtime() - fillAt >= SUBMIT_AFTER_MS

			/** 宿主和 WebView 必须拆干净: 只 destroy 不 detach 会留下一个空壳 View */
			fun destroy() {
				try {
					webView?.stopLoading()
					webView?.destroy()
				}catch(_: Throwable) { }
				webView = null
				try {
					(host?.parent as? ViewGroup)?.removeView(host)
				}catch(_: Throwable) { }
				host = null
			}

			fun finish(result: Outcome) {
				if(done) return
				done = true
				handler.removeCallbacksAndMessages(null)
				// 先让协程继续, 再延后销毁 WebView(立刻销毁会让正在回调的 WebView 崩)
				if(cont.isActive) cont.resume(result)
				handler.postDelayed({ destroy() }, 300L)
			}

			try {
				val wv = WebView(context)
				webView = wv
				wv.settings.apply {
					javaScriptEnabled = true
					domStorageEnabled = true
					databaseEnabled = true
					javaScriptCanOpenWindowsAutomatically = false
					// 学校站点有些资源仍是 http, 页面是 https —— 不放行的话表单脚本可能加载不出来
					mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
					useWideViewPort = true
					loadWithOverviewMode = true
				}
				if(context is Activity) host = attachHost(context, wv)
				// 即使没能挂进窗口, 也给一个 1×1 的布局尺寸: 页面会正常排版(否则 offset/文本都可能取不到)
				if(host == null) wv.layout(0, 0, 1, 1)

				wv.webViewClient = object : WebViewClient() {

					override fun onPageFinished(view: WebView, url: String) {
						if(done) return
						landedUrl = url
						// 在它落到的**任何**页面上填表都是预期行为: 这个 WebView 是一次性的、只用来跑登录,
						// 学校邮箱可能先跳统一认证/腾讯企业邮, 漏了这一步整次校验就只能干等超时。
						if(url.startsWith("http") || url.startsWith("file")) {
							// 第一次填表前记下匿名会话指纹(见 sidBeforeFill 的注释)
							if(sidBeforeFill == null) sidBeforeFill = sessionFingerprint(cookieManager)
							fillAt = SystemClock.elapsedRealtime()
							noFormHits = 0
							view.evaluateJavascript(fillJs(account, password), null)
						}
					}

					// API 23+ 的 onReceivedError(WebView, WebResourceRequest, WebResourceError) 默认实现
					// 会回头调用这个 4 参版本, 所以只重写它就够了(而且能在 API 21 上工作)。
					@Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
					override fun onReceivedError(view: WebView, errorCode: Int, description: String?, failingUrl: String?) {
						if(failingUrl == null || failingUrl == HOME || failingUrl == landedUrl) loadFailed = true
					}

					override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
						// 只在**本校域名**上放行: 学校站点历史上有过自签/过期证书, 卡在这里会让
						// "自动登录"永远停在白屏。别的域名一律不放行。
						val h = runCatching { Uri.parse(error.url).host }.getOrNull().orEmpty()
						if(h.endsWith("qust.edu.cn", true)) handler.proceed() else handler.cancel()
					}
				}

				// 【第三十五轮】先清一次可能残留的会话? —— 不清。保留上一次登进去的 Cookie,
				// 这样"上次登过、这次直接进邮箱"能被认出来(探针 MAIL / baseline 已有 sid)。
				wv.loadUrl(HOME)

				val poll = object : Runnable {
					override fun run() {
						if(done) return
						val view = webView ?: return
						view.evaluateJavascript(PROBE_JS) { raw ->
							if(done) return@evaluateJavascript
							val state = raw?.trim()?.trim('"') ?: ""
							if(state == "BAD_PWD") {
								finish(Outcome.BAD_PASSWORD)
								return@evaluateJavascript
							}
							// 【第三十五轮·辅助判据】会话指纹变了 = 服务端确实换了登录态。
							// 必须"提交过 + 页面上没有错误提示"才认, 否则登录失败后重建的匿名会话
							// 会被误当成成功。Cookie 是跨进程查询, 每两次轮询查一次就够。
							cookieTick++
							if(submitted() && state != "FORM" && cookieTick % 2 == 0) {
								val before = sidBeforeFill
								val now = sessionFingerprint(cookieManager)
								if(before != null && now.isNotEmpty() && now != before) {
									finish(Outcome.OK)
									return@evaluateJavascript
								}
							}
							when(state) {
								// 到了二次验证 / 已经进邮箱 -> 凭据一定是对的
								"SECOND_AUTH", "MAIL" -> finish(Outcome.OK)
								// 表单在 + 有验证码框: 后台替人填不了验证码, 先记下来, 超时时报"需要验证码"
								"CAPTCHA" -> {
									sawForm = true
									sawCaptcha = true
									noFormHits = 0
									handler.postDelayed(this, POLL_MS)
								}
								// 还停在登录页: 继续等(同时记下"确实见过表单")
								"FORM" -> {
									sawForm = true
									noFormHits = 0
									handler.postDelayed(this, POLL_MS)
								}
								// 既没有登录表单、也没有错误提示: 可能是登录成功后的主界面,
								// 也可能是页面跳转途中的空白。要求"填过表 + 已提交 + 连拍两次"才算成功。
								else -> {
									if(sawForm && submitted()) {
										noFormHits++
										if(noFormHits >= 2) {
											finish(Outcome.OK)
											return@evaluateJavascript
										}
									}
									// 兜底: 地址栏已经离开登录页(带 sid / 进了 coremail 主目录)
									if(submitted() && (landedUrl.contains("sid=")
												|| landedUrl.contains("/coremail/XT/")
												|| landedUrl.contains("main.jsp"))) {
										finish(Outcome.OK)
										return@evaluateJavascript
									}
									handler.postDelayed(this, POLL_MS)
								}
							}
						}
					}
				}
				handler.postDelayed(poll, FIRST_POLL_MS)
				handler.postDelayed({
					finish(
						when {
							// 从头到尾没见到表单, 又收到过加载错误 -> 是"根本连不上"(校外网络/服务维护),
							// 而不是"账号密码不对"。分开报, 用户才不至于反复改密码。
							loadFailed && !sawForm -> Outcome.NETWORK
							// 表单一直在、而且有验证码框 -> 后台没法替人过验证码
							sawCaptcha -> Outcome.NEED_CAPTCHA
							else -> Outcome.UNAVAILABLE
						}
					)
				}, timeoutMs)

				cont.invokeOnCancellation {
					done = true
					handler.removeCallbacksAndMessages(null)
					handler.post { destroy() }
				}
			}catch(_: Throwable) {
				finish(Outcome.UNAVAILABLE)
			}
		}
	}
}
