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

import com.qust.helper.data.PwdCrypto
import com.qust.helper.data.Setting
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import java.io.IOException
import java.net.HttpURLConnection
import kotlinx.coroutines.delay


class NeedLoginException : RuntimeException("需要登录")

/**
 * 【本轮·新增】教务系统原网址这条路进不去, 需要用户改用「数字青科」的账号密码再登一次。
 *
 * 触发场景: 用户在「教务登陆」页(学号 + 教务系统密码)提交后, 原网址直连失败。
 * 以前这里会**自动拿这份教务密码去登统一身份** —— 两套密码本来就不同, 必然是错的,
 * 多试几次还可能触发学校锁号。现在改为抛这个异常, 由界面:
 * ① 弹气泡「暂时无法登录教务系统」;
 * ② 打开「数字青科登陆」页, 让用户输统一身份账号密码;
 * ③ 登录成功后再自动走「业务应用 → 教务管理系统」。
 *
 * 继承 IOException: 万一有旧代码没显式 catch 它, 也会落到"网络/服务不可用"的分支,
 * 不会把异常抛到界面上。
 */
class NeedVpnLoginException : IOException("暂时无法登录教务系统")

/**
 * 教务服务端故障(5xx / 维护 / 返回报错页而非正常数据)。
 * 继承 IOException, 这样旧的网络错误处理仍能兜住它; 但登录/查询流程里可以优先 catch 它,
 * 给出"教务系统服务器异常/正在维护"的准确提示, 且不再对它做内部重试。
 */
class ServerException(message: String) : IOException(message)

/**
 * 【本期】响应正文是否是一张"拒绝访问"的拦截页。
 *
 * 校方/网关拦下请求时常常返回 **200 + 一张提示页**(而不是 403 状态码), 只按状态码判断会把它
 * 误当成"网络异常"。这里识别常见的拦截页特征, 命中即按"暂时没有访问权限"处理。
 */
internal fun isForbiddenBody(body: String?): Boolean {
	if(body.isNullOrEmpty()) return false
	return body.contains("Forbidden", ignoreCase = true) || body.contains("HTTP 403") ||
		body.contains("禁止访问") || body.contains("无权访问") || body.contains("没有访问权限")
}

/**
 * 【本轮·新增】响应正文是否**明确表示账号或密码不对**。
 *
 * 用途: 登录失败时区分两种情况, 避免把"学校服务暂时进不去"报成"密码错":
 * - 页面明确写"用户名或密码错误" -> 真的是凭据问题(提示「账号或密码错误」);
 * - 页面没有这类文字(维护页 / 网关错误页 / 空白页) -> 按「暂时无法登录」处理。
 *
 * 只认明确的凭据类措辞, 宁可判为"暂时无法登录"也不冤枉用户改了密码。
 */
internal fun isBadCredentialsBody(body: String?): Boolean {
	if(body.isNullOrEmpty()) return false
	val markers = arrayOf(
		"用户名或密码", "账号或密码", "帐号或密码",
		"密码错误", "密码不正确", "密码有误", "密码无效",
		"用户名不存在", "用户不存在", "账号不存在", "帐号不存在",
		"认证失败", "凭证无效", "用户名或密码不正确"
	)
	return markers.any { body.contains(it) }
}

/**
 * 【本期】响应正文是否是一张**登录页**。
 *
 * 数字青科(WebVPN) 在会话失效时并不总是返回 302, 有时直接 **HTTP 200 + 登录页**。
 * 只按状态码判断会把它当成"已登录", 于是一直拿过期 Cookie 去加载网页 ——
 * 用户看到的就是「教务系统没能自动登录数字青科, 页面停在登录框」。
 *
 * 判定: 同时存在"密码输入框"和登录页特征(用户名 / 忘记密码)。已登录的门户页不会有密码输入框。
 */
internal fun isLoginPageBody(body: String?): Boolean {
	if(body.isNullOrEmpty()) return false
	val hasPasswordInput = body.contains("type=\"password\"", ignoreCase = true) ||
		body.contains("type='password'", ignoreCase = true) ||
		body.contains("type=password", ignoreCase = true)
	if(!hasPasswordInput) return false
	return body.contains("用户名") || body.contains("忘记密码") ||
		body.contains("name=\"username\"", ignoreCase = true) ||
		body.contains("id=\"username\"", ignoreCase = true) ||
		body.contains("name=\"yhm\"", ignoreCase = true)
}

/**
 * 自动持久化处理Cookie
 * @param host 网站host
 * @param scheme 协议类型 http / https
 */
class AccountCookieJar(val scheme: String = "http", val host: String, val cookieName: String) : CookieJar {
	var cookiesList = emptyList<Cookie>()

	init {
		loadCookie(scheme, host)
	}

	fun loadCookie(scheme: String, host: String){
		val tmp = ArrayList<Cookie>()
		val url: HttpUrl = HttpUrl.Builder().host(host).scheme(scheme).build()
		for(cookieString in Setting.getStringSet(cookieName, HashSet())) {
			val cookie = Cookie.parse(url, cookieString)
			if(cookie != null) tmp.add(cookie)
		}
		cookiesList = tmp
	}

	/**
	 * 清空所有Cookie
	 *
	 * 【退出登录后还能查】必须**把清空结果写盘**: 以前只清了内存里的列表,
	 * 重启应用后 Cookie 又被读回来, 表现为"点了退出登录却还是已登录"。
	 */
	fun clearCookies() {
		cookiesList = emptyList()
		Setting.edit { it.putStringSet(cookieName, HashSet()) }
	}

	override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
		// 【两次登录 / 网页入口掉线】这里原来把"本次响应收到的 Cookie"直接**覆盖**写进本地存储:
		// 一次登录过程中会有多次响应, 后一次(常常没有任何 Set-Cookie)会把前一次的会话 Cookie
		// 整批抹掉 —— 于是"重启应用后会话就失效""刚登录要点两次登录""网页入口提示登录失效"。
		// 改为按 (name + domain + path) 去重合并后整体持久化, 并顺手清掉已过期的。
		val merged = LinkedHashMap<String, Cookie>()
		for(cookie in cookiesList + cookies) merged["${cookie.name}#${cookie.domain}#${cookie.path}"] = cookie
		cookiesList = merged.values.toList()
		val now = System.currentTimeMillis()
		Setting.edit {
			it.putStringSet(
				cookieName,
				// 【安全改造】会话 Cookie 加密后落盘(读取时由 Setting 自动解密)
				cookiesList.filter { cookie -> cookie.expiresAt > now }
					.map { cookie -> PwdCrypto.encrypt(cookie.toString()) }.toHashSet()
			)
		}
	}

	override fun loadForRequest(url: HttpUrl): List<Cookie> {
		return cookiesList
	}
}


interface IAccount {

	/**
	 * Cookie是否有效
	 */
	var isLogin: Boolean

	/**
	 * 登录，使用储存的用户名和密码
	 */
	@Throws(IOException::class, NeedLoginException::class)
	suspend fun login(): Boolean

	/**
	 * 登录
	 * @param account 账号，为null则使用储存的账号
	 * @param password 密码，为null则使用储存的密码
	 * @param saveData 是否保存数据
	 * @return 登录是否成功，失败的情况下为账号或密码错误
	 * @throws IOException 网络错误
	 */
	@Throws(IOException::class)
	suspend fun login(account: String?, password: String?, saveData: Boolean = true): Boolean

	/**
	 * 检查登陆状态，有账号信息时自动进行登录
	 */
	@Throws(IOException::class, NeedLoginException::class)
	suspend fun checkLogin(): Boolean

	/**
	 * GET请求
	 */
	@Throws(IOException::class, NeedLoginException::class)
	suspend fun get(url: String): Response

	/**
	 * POST请求
	 */
	@Throws(IOException::class, NeedLoginException::class)
	suspend fun post(url: String, body: RequestBody): Response

	/**
	 * GET请求，不检查登录状态
	 */
	@Throws(IOException::class)
	suspend fun getNoCheck(url: String): Response

	/**
	 * POST请求，不检查登录状态
	 */
	@Throws(IOException::class)
	suspend fun postNoCheck(url: String, requestBody: RequestBody): Response

	/**
	 * GET请求，不跟随重定向
	 */
	@Throws(IOException::class)
	suspend fun getNoRedirect(url: String): Response

	/**
	 * POST请求，不跟随重定向
	 */
	@Throws(IOException::class)
	suspend fun postNoRedirect(url: String, requestBody: RequestBody): Response

	/**
	 * 获取账号
	 */
	fun getAccount(): String?

	/**
	 * 获取Cookie
	 */
	fun getCookie(): List<Cookie>

	/**
	 * 退出登录
	 */
	fun logout()

}

/**
 * 自动处理账号信息的类
 */
abstract class Account(host: String, var accountName: String, var passwordName: String, val scheme: String = "http", val cookieName: String): IAccount {

	var host: String = host
		protected set(value){
			cookieJar.loadCookie(scheme, value)
			field = value
		}

	override var isLogin: Boolean = false

	/** 【第三十六轮】会话复核防重入守卫: 阻断 absCheckLogin ↔ login 的无限互调(StackOverflowError) */
	private var verifying = false

	/** 【第三十七轮】checkLogin 防递归深度: >0 表示正处于登录校验链内, 嵌套调用直接快速失败 */
	private var checkDepth = 0

	protected val cookieJar: AccountCookieJar = AccountCookieJar(scheme, host, cookieName)

	/**
	 * HTTP请求对象
	 */
	private val client: OkHttpClient = OkHttpClient.Builder().cookieJar(cookieJar).build()

	/**
	 * HTTP请求对象，不跟随重定向
	 */
	private val clientNoRedirect: OkHttpClient = OkHttpClient.Builder()
		.cookieJar(cookieJar)
		.followRedirects(false)
		.build()


	@Throws(IOException::class, NeedLoginException::class)
	override suspend fun login(): Boolean {
		return login(
			Setting.getString(accountName),
			Setting.getString(passwordName),
			saveData = false
		)
	}

	@Throws(IOException::class)
	override suspend fun login(account: String?, password: String?, saveData: Boolean): Boolean {
		if(account == null || password == null){
			isLogin = false
			return false
		}
		isLogin = absLogin(account, password)
		// 【要点两次登录】部分部署下登录接口虽然返回 302(判定为成功), 但会话其实并未真正生效,
		// 于是"刚登录完就去查课表"必然失败, 用户得再点一次登录才行。
		// 这里在登录成功后**立刻复核一次会话**, 不通过就自动再登录一次 —— 对外只需登录一次。
		//
		// 【第三十六轮·修复 StackOverflowError】复核(safeCheckLogin→absCheckLogin)在"仍未登录"时
		// 会再调 login(), 而 login() 又会复核 → 无限互调。当会话始终无效(如统一身份会话被服务端
		// 卡在重定向循环)时直接 StackOverflowError 退出软件。加防重入守卫: 复核期间 login() 不再
		// 发起第二次复核, 递归深度封顶。
		// 【第四十二轮·提速】登录响应本身已能证明会话有效时(见 [skipVerifyAfterLogin]),
		// 跳过这次复核 —— 数字青科 CAS 登录成功后重定向链已落在门户页, 复核纯属多花一两个往返。
		if(isLogin && !verifying && !skipVerifyAfterLogin()){
			verifying = true
			try{
				if(!safeCheckLogin()){
					isLogin = absLogin(account, password)
					if(isLogin && !safeCheckLogin()) isLogin = false
				}
			}finally{
				verifying = false
			}
		}
		// 【第三十九轮】账号密码写入改用同步提交(commit), 避免进程异常退出导致"已登录账号莫名丢失"
		// 【安全改造】密码加密后落盘(密钥绑定设备, 不硬编码), 明文不写入存储
		if(saveData && isLogin){
			// 【修改版】饮水码等非关键账号用异步写入, 避免 commit 同步落盘导致“保存慢”
			if(passwordName == com.qust.helper.data.Keys.DRINK_PASSWORD){
				Setting.edit {
					it.putString(accountName, account)
					it.putString(passwordName, PwdCrypto.encrypt(password))
				}
			}else{
				Setting.editSync {
					it.putString(accountName, account)
					it.putString(passwordName, PwdCrypto.encrypt(password))
				}
			}
		}
		return isLogin
	}

	/**
	 * 复核会话是否真的可用
	 *
	 * 校验本身因为网络抖动失败时按"通过"处理, 避免把网络问题误判成登录失败。
	 */
	/**
	 * 【第三十五轮】校内 Web 系统并不严格遵守 HTTP 标准: 同一个状态码可能是临时故障, 也可能是永久变更。
	 * 因此**禁止拿到单次返回结果就直接报错** —— 这里对数据类请求统一做有限重试 + 明确文案:
	 * - 404: 最多重试3次(间隔500ms, 常见为负载均衡分到故障节点的间歇性404), 仍失败才报
	 *   「接口地址不存在(404)，校方大概率修改了接口地址」;
	 * - 403: 只轻量重试1次(会话过期/外网风控), 报「暂时没有访问权限」;
	 * - 502/503/504: 各最多重试2次(间隔800ms), 分别给出维护/过载/超时文案;
	 * - 无状态码(连接超时/DNS失败/连接拒绝): 最多重试2次, 报「网络或者远端服务器无响应，请检查网络，也有可能服务器正在维护」。
	 * 重试均带时间间隔, 不做死循环轰炸; 请求本身的构建与发送逻辑完全不变, 仅包装异常与重试。
	 */
	private suspend fun executeWithRetry(request: Request): Response {
		var notFoundRetries = 0
		var forbiddenRetries = 0
		var serverRetries = 0
		var networkRetries = 0
		while(true){
			try {
				// 【第三十七轮·根因修复】这里必须是**真正发起请求**的原始调用。
				// (第三十五轮批量替换时曾误改成 executeWithRetry(request) 变成自调用,
				//  导致每次网络请求直接无限递归 StackOverflowError —— 登录必崩的根因)
				val response = client.newCall(request).execute()
				when(response.code){
					404 -> {
						response.close()
						if(notFoundRetries < 3){ notFoundRetries++; delay(500); continue }
						throw ServerException("接口地址不存在(404)，校方大概率修改了接口地址")
					}
					403 -> {
						response.close()
						if(forbiddenRetries < 1){ forbiddenRetries++; delay(500); continue }
						// 【本期】403 = 校方拒绝访问(直连教务/来源受限): 提示"暂时没有访问权限",
						// 不再描述成网络异常, 也不再引导用户去开 WebVPN 开关(该开关已删除)。
						throw ServerException("暂时没有访问权限")
					}
					502 -> {
						response.close()
						if(serverRetries < 2){ serverRetries++; delay(800); continue }
						throw ServerException("后端服务暂时不可用，可能维护，请稍后重试")
					}
					503 -> {
						response.close()
						if(serverRetries < 2){ serverRetries++; delay(800); continue }
						throw ServerException("服务正在维护或者过载，请稍后重试")
					}
					504 -> {
						response.close()
						if(serverRetries < 2){ serverRetries++; delay(800); continue }
						throw ServerException("请求超时，服务器暂时没有响应，稍后再试")
					}
					else -> return response
				}
			}catch(e: ServerException){
				throw e
			}catch(e: IOException){
				if(networkRetries < 2){ networkRetries++; delay(800); continue }
				throw ServerException("网络或者远端服务器无响应，请检查网络，也有可能服务器正在维护")
			}
		}
	}

	/**
	 * 【第四十二轮·提速】登录响应本身是否已足以证明会话有效(从而跳过登录后的复核往返)。
	 * 默认不跳过; 数字青科(IPassAccount)登录成功后重定向链落在门户页, 覆写为 true。
	 */
	protected open fun skipVerifyAfterLogin(): Boolean = false

	protected suspend fun safeCheckLogin(): Boolean {
		return try { absCheckLogin() } catch(_: Exception) { true }
	}

	@Throws(IOException::class, NeedLoginException::class)
	override suspend fun checkLogin(): Boolean{
		// 【第三十七轮】防 StackOverflowError 终极守卫: 任何**嵌套(递归)**进入 checkLogin 的调用
		// 一律快速失败(NeedLoginException), 由上层 safeCheckLogin 的 catch 兜底 —— 从根上杜绝
		// absCheckLogin↔login↔checkLogin/get 之间任何形状的无限互调(教务/统一身份通用)。
		// 正常流程里 checkLogin 都是顺序调用, 只有递归异常路径才会嵌套进入, 不影响正常登录。
		if(checkDepth > 0) throw NeedLoginException()
		checkDepth++
		try{
			if(absCheckLogin()){
				isLogin = true
				return true
			}
			// 少数部署下第一次登录虽然返回成功, 但会话并未真正生效(表现为"刚登录要点两次登录
			// 才能正常查询课表")。因此登录之后**再校验一次**, 最多登录两轮。
			var ok = false
			for(attempt in 0 until 2){
				if(!login()) break
				if(absCheckLogin()){ ok = true; break }
			}
			isLogin = ok
			if(!ok) throw NeedLoginException()
			return true
		}finally{
			checkDepth--
		}
	}

	@Throws(IOException::class, NeedLoginException::class)
	override suspend fun get(url: String): Response{
		if(!isLogin) throw NeedLoginException()

		val request = Request.Builder().url("${scheme}://${host}/${url}".toHttpUrl()).build()
		val result = executeWithRetry(request)
		val code = result.code
		if(code == HttpURLConnection.HTTP_MOVED_PERM || code == HttpURLConnection.HTTP_MOVED_TEMP){
			if(result.header("Location")?.contains("login")!!){
				isLogin = login()
				if(!isLogin) throw NeedLoginException()
				return executeWithRetry(request)
			}
		}
		return result
	}

	@Throws(IOException::class, NeedLoginException::class)
	override suspend fun post(url: String, body: RequestBody): Response{
		return if(isLogin || checkLogin()) {
			executeWithRetry(Request.Builder()
				.url("${scheme}://${host}/${url}".toHttpUrl())
				.post(body)
				.build()
			)
		} else {
			throw NeedLoginException()
		}
	}

	@Throws(IOException::class)
	override suspend fun getNoCheck(url: String): Response {
		return executeWithRetry(Request.Builder()
			.url("${scheme}://${host}/${url}".toHttpUrl())
			.build()
		)
	}

	@Throws(IOException::class)
	suspend fun getNoCheck(url: String, requestHeaders: Headers): Response {
		return executeWithRetry(Request.Builder()
			.url("${scheme}://${host}/${url}".toHttpUrl())
			.headers(requestHeaders)
			.build()
		)
	}

	@Throws(IOException::class)
	override suspend fun postNoCheck(url: String, requestBody: RequestBody): Response {
		return executeWithRetry(Request.Builder()
			.url("${scheme}://${host}/${url}".toHttpUrl())
			.post(requestBody)
			.build()
		)
	}

	@Throws(IOException::class)
	suspend fun postNoCheck(url: String, requestBody: RequestBody, requestHeaders: Headers): Response {
		return executeWithRetry(Request.Builder()
			.url("${scheme}://${host}/${url}".toHttpUrl())
			.headers(requestHeaders)
			.post(requestBody)
			.build()
		)
	}

	@Throws(IOException::class)
	override suspend fun getNoRedirect(url: String): Response {
		return clientNoRedirect.newCall(Request.Builder()
			.url("${scheme}://${host}/${url}".toHttpUrl())
			.build()
		).execute()
	}

	@Throws(IOException::class)
	override suspend fun postNoRedirect(url: String, requestBody: RequestBody): Response {
		return clientNoRedirect.newCall(Request.Builder()
			.url("${scheme}://${host}/${url}".toHttpUrl())
			.post(requestBody)
			.build()
		).execute()
	}


	/**
	 * 获取账号
	 */
	override fun getAccount(): String {
		return Setting.getString(accountName, "")
	}

	override fun getCookie(): List<Cookie> {
		return cookieJar.cookiesList
	}

	/**
	 * 清空本账号的会话 Cookie(不影响已保存的账号密码)
	 *
	 * 用于"会话已失效且无法自动登录"的场景: 过期 Cookie 一旦被注入 WebView,
	 * 网页端第一次登录必然被服务端弹回(表现为"要登录两次才能进去")。
	 */
	fun clearCookies() {
		cookieJar.clearCookies()
	}

	/**
	 * 退出登录: 清 Cookie + 清账号密码。
	 *
	 * 【第三十轮】改成 open —— 饮水的登录令牌(token)和条码(code)存在子类自己的字段里,
	 * 不一起清掉的话, 账号管理里"退出登录"之后内存里还留着旧条码,
	 * 界面上照样显示得好好的(用户报的"退出了饮水码还能正常显示"就是这个)。
	 */
	open override fun logout() {
		cookieJar.clearCookies()
		Setting.editSync { it.remove(accountName).remove(passwordName) }
		isLogin = false
	}

	@Throws(IOException::class)
	abstract suspend fun absCheckLogin(): Boolean

	@Throws(IOException::class)
	protected abstract suspend fun absLogin(account: String, password: String): Boolean
}