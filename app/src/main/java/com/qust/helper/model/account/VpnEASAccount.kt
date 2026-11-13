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

import com.qust.helper.data.Keys
import com.qust.helper.data.LoginEntry
import com.qust.helper.data.Setting
import com.qust.helper.data.api.QustApi
import com.qust.helper.model.Logger
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Cookie
import okhttp3.RequestBody
import okhttp3.Response
import java.io.IOException

class VpnEASAccount: EASAccount(){

	private class VpnEas(host: String, scheme: String) : VpnAccount(host, scheme) {
		/**
		 * 数字青科登录成功后的「业务应用 → 教务管理系统」入口。
		 *
		 * WebVPN 的业务应用通过 `sso/driotlogin` 完成对目标系统的单点登录,
		 * 进入教务后教务接口才在该通道内可用(返回 200 视为已进入)。
		 */
		override suspend fun afterLogin(): Boolean {
			// 【本期】不能只看 200: 统一身份会话不在时, 这里会 302 到登录页(跟随重定向后仍是 200)。
			// 拿到登录页说明 SSO 没带过去, 按"未进入教务"处理, 由上层再补登一次。
			getNoCheck("sso/driotlogin").use { resp ->
				if(resp.code != 200) return false
				val body = try { resp.body?.string() } catch(_: Exception) { null }
				return !isLoginPageBody(body)
			}
		}
	}

	private var vpnAccount: VpnAccount = VpnEas(host, scheme)

	/**
	 * 【本轮】② 号通道: 数字青科里的「教务管理系统」已在上面的 [vpnAccount] 里;
	 * 下面这个是 ① 号通道 —— 教务系统**原网址**直连([DirectEASAccount])。
	 *
	 * 两条通道各有独立的 Cookie 存储键(`ipassCookie` / `eaDirectCookie`), 互不影响。
	 * 用 lazy 打破"构造期互相引用": 本类实例由 [EASAccount.getInstance] 创建,
	 * 而直连实例的父类也是 [EASAccount]。
	 */
	private val direct: DirectEASAccount by lazy { DirectEASAccount.getInstance() }

	/**
	 * 当前走哪条通道
	 *
	 * `true`  = ① 教务系统原网址(直连 jwglxt);
	 * `false` = ② 数字青科里的「教务管理系统」。
	 *
	 * 记的是"上一次真正跑通的那条", 落盘保存, 重启后无需重新试探。
	 */
	private var directChannel: Boolean
		get() = Setting.getBoolean(Keys.EAS_DIRECT_CHANNEL, false)
		set(value) {
			// 只有真的换道才写盘, 避免每个请求都碰一次 SharedPreferences
			if(Setting.getBoolean(Keys.EAS_DIRECT_CHANNEL, false) != value) {
				Setting.edit { it.putBoolean(Keys.EAS_DIRECT_CHANNEL, value) }
			}
		}

	companion object {
		/**
		 * 【第七十七轮·按要求】"登录教务系统超过 8 秒没有任何反应, 就自动走数字青科的教务系统"。
		 *
		 * 教务原网址(直连)在学校网关抽风时会把请求吊住: OkHttp 默认 10s 连接超时 + 内部
		 * executeWithRetry 还会重试几次, 整条路走完能到 30~60 秒 —— 这段时间界面一直"正在登录",
		 * 用户连续点几次就是连着弹好几个"请先登录"。
		 * 直连的探测/补登/登录全部套上这个上限, 超时即视为"这条路不通", 立刻改走②。
		 */
		const val DIRECT_TIMEOUT_MS = 8_000L

		fun getInstance(): VpnEASAccount {
			return Instance.INSTANCE
		}
	}

	private object Instance{
		val INSTANCE = VpnEASAccount()
	}

	/**
	 * 【退出教务系统把数字青科也踢下线】
	 *
	 * 走 WebVPN 时教务请求复用的都是同一个 WebVPN 会话, 以前直接把 `vpnAccount.isLogin`
	 * (= WebVPN/数字青科的登录状态) 当成教务的登录状态, 于是:
	 * - 退出教务登录 → 数字青科显示成未登录;
	 * - 只要登录过数字青科, 教务就永远算"已登录", 没登教务也能查课表。
	 *
	 * 这里用**教务自己的状态位**记录, 两者互不干扰:
	 * 退出教务只清教务, WebVPN/数字青科的登录保持不变。
	 */
	private var easSessionValid: Boolean
		get() = Setting.getBoolean(Keys.EAS_SESSION_VALID, false)
		set(value) { Setting.edit { it.putBoolean(Keys.EAS_SESSION_VALID, value) } }

	override var isLogin: Boolean
		get() = if(directChannel) direct.isLogin else (easSessionValid && vpnAccount.isLogin)
		set(value) {
			easSessionValid = value
			if(directChannel) direct.isLogin = value
		}

	/**
	 * 【本轮】两条通道任一还有会话就算"登录过"
	 *
	 * 直连通道的会话在 `eaDirectCookie` 里, 数字青科通道的会话在会话位 + `ipassCookie` 里。
	 * 账号管理/我的页面只关心"能不能进教务", 不关心走哪条, 所以这里取并集。
	 */
	override val hasSession: Boolean
		get() = easSessionValid || direct.hasSession

	/** 【本轮】当前走线(日志/界面显示用) */
	override val channelName: String
		get() = if(directChannel) "教务系统原网址" else "数字青科"

	/** 【本轮】走原网址时网页入口直接打开教务自己的域名, 走数字青科时才需要 WebVPN 编码 */
	override val openWebDirectly: Boolean
		get() = directChannel

	override fun changeHost(index: Int) {
		if(index >= QustApi.EA_HOSTS.size) return
		// 切换节点后旧的教务(jwglxt)会话必然失效, 必须让应用重新登录教务,
		// 否则 queryMark/getAcademic 会带着失效会话静默返回空(表现为"查不出东西")。
		// 这里只清教务自己的会话位与 eaCookie, 不动 WebVPN/数字青科的登录。
		easSessionValid = false
		cookieJar.clearCookies()
		host = QustApi.EA_HOSTS[index]
		vpnAccount = VpnEas(host, scheme)
		// 【本轮】直连(原网址)通道打的是同一批主机, 节点换了它也得跟着换、并把旧会话清掉,
		// 否则直连还在打旧地址、还揣着旧节点的 Cookie。
		direct.changeHost(index)
		Setting.edit { it.putInt(Keys.EA_HOST, index) }
		// 【切换教务节点后仍查不出】上面把 easSessionValid 清成 false, 但 checkLogin() 看到
		// easSessionValid=false 会直接抛 NeedLoginException, 于是后续查询永远被拦、查不出东西。
		// 若已存有教务账号(说明用户确实登录过教务), 这里把会话位重新置为有效,
		// 让查询走"重新登录教务"的流程(只要 WebVPN/数字青科通道仍在线即可), 不再被拦死。
		// 注意: 用户主动"退出教务登录"会同时清除账号名与 easSessionValid, 这里不会误恢复。
		if(Setting.getString(Keys.EAS_ACCOUNT, "").isNotEmpty()) easSessionValid = true
	}

	/**
	 * 教务账号
	 *
	 * 【本期·按要求改口径】只认**教务系统自己的账号位**(`EAS_ACCOUNT`)。
	 *
	 * 以前这里回退到 `vpnAccount.getAccount()`(数字青科的统一身份账号), 于是"只登录了数字青科"
	 * 也会让账号管理里的「教务系统」一栏显示学号, 看起来像是把数字青科的账号存进了教务。
	 * 走 WebVPN 之后 `EAS_ACCOUNT` 根本不再被写入, 所以这里直接返回空 ——
	 * 界面上「教务系统」只显示"已登录/未登录"状态(由 [isLogin] 决定), 不再显示数字青科的学号与密码。
	 */
	override fun getAccount(): String {
		return if(easSessionValid) Setting.getString(Keys.EAS_ACCOUNT, "") else ""
	}

	/**
	 * 【本期·网页入口"没能自动登录"根因】会话 Cookie 必须取**通道上真正的那一份**。
	 *
	 * 走 WebVPN 之后, 所有请求(含教务)用的都是数字青科(ipass)那一套 Cookie,
	 * 本类自己的 `cookieJar`(eaCookie) 早已不再收 Cookie —— 一直是空列表。
	 * 以前没有覆写这里, 网页入口 `injectCookies()` 拿到的是空列表, 等于**什么都没注入**,
	 * 于是 WebView 打开教务地址就被网关弹回登录页, 看起来就是"教务系统没能自动登录数字青科"。
	 */
	override fun getCookie(): List<Cookie> {
		// 【本轮】网页入口注入的必须是**当前通道**的那一份会话:
		// 走直连就注入直连的 Cookie, 走数字青科就注入统一身份的 Cookie, 否则等于没注入。
		return if(directChannel) direct.getCookie() else vpnAccount.getCookie()
	}

	/**
	 * 校验教务会话: 【第四十轮·按要求】**先试教务系统原网址, 不行再走数字青科里的教务系统**
	 *
	 * ① 原网址: 会话还在(之前用学号+教务系统密码登录过, Cookie 没过期) -> 直接用它, 不必重登;
	 *    会话断了但保存过教务账号密码 -> 用它自动补登一次(这就是"自动登录原来的教务系统网址")。
	 *    从没直连登录过(Cookie 为空、也没存过教务账号)时不发请求, 不白加一次往返;
	 * ② 原网址进不去 -> 走数字青科: 能自动用已保存的统一身份补登,
	 *    连统一身份都没有才抛 [NeedLoginException] 让界面去登录。
	 */
	override suspend fun checkLogin(): Boolean {
		// ① 教务系统原网址 —— 【第七十八轮·按要求"8 秒没反应直接走数字青科"】
		// 探测 + 补登**共用 8 秒预算**: 上一版各自 8 秒, 网关把两个请求都吊住时要 16 秒才换道,
		// 外层(WEB 页)等不及先把整个登录判超时了, 数字青科那条路根本没机会跑。
		val directDeadline = System.currentTimeMillis() + DIRECT_TIMEOUT_MS
		if(probeDirectSession(directDeadline)) {
			directChannel = true
			direct.isLogin = true
			easSessionValid = true
			EasLoginState.clear()
			return true
		}
		if(autoReloginDirect(directDeadline)) {
			directChannel = true
			easSessionValid = true
			EasLoginState.clear()
			return true
		}
		// 【第四十二轮·提速】欢迎页刚走完"数字青科登录"时, afterVpnLogin 已把 easSessionValid
		// 置位、VPN 会话也是刚建立的 —— 这时再走一遍 vpnAccount.checkLogin() 纯属浪费一两个
		// 网络往返(数字青科域名本来就不快)。会话位 + VPN 在线即直接放行; 万一会话其实已失效,
		// 后续查询会走 get() 里的重登录兜底, 不会卡死。
		if(!directChannel && easSessionValid && vpnAccount.isLogin) return true
		// 换道前先把"教务已进入"的标记清掉 —— 那是给①号通道记的,
		// 留着会让②误以为已经进过教务, 跳过「业务应用 → 教务管理系统」的进入动作。
		if(directChannel) easSessionValid = false
		direct.isLogin = false
		directChannel = false
		// ② 数字青科里的教务系统: 先保证通道本身可用(没有账号时这里会抛 NeedLoginException)
		val vpnOk = try {
			vpnAccount.checkLogin()
		}catch(e: NeedLoginException) {
			// 【第六十七轮】数字青科自己都没登录 —— 这是"没有凭据", 不是外部原因
			// 【第七十七轮·按要求"账号管理里有教务系统的账号和密码就算是登录成功了"】
			// 存过教务账号+密码的, 上面①已经试过一次(8 秒上限), 走到这里说明是**校外网/网关/维护**
			// 这类外部原因, 不是"用户没登录"。这时记成 EXTERNAL, 界面就不会再弹"请先登录教务系统",
			// 而是走"用数字青科自动登录/直接进网页"那条分支。
			val hasEasCred = Setting.getString(Keys.EAS_ACCOUNT).isNotEmpty() &&
				Setting.getString(Keys.EAS_PASSWORD).isNotEmpty()
			if(hasEasCred) {
				EasLoginState.mark(EasLoginState.Reason.EXTERNAL, "教务原网址暂时无法登录(已存账号密码)")
			}else{
				EasLoginState.mark(EasLoginState.Reason.NO_CREDENTIAL, "数字青科未登录")
			}
			easSessionValid = false
			throw e
		}
		if(!vpnOk) {
			easSessionValid = false
			EasLoginState.mark(EasLoginState.Reason.NO_CREDENTIAL, "数字青科会话不可用")
			throw NeedLoginException()
		}
		if(easSessionValid) return true
		// 教务是数字青科的一个业务应用: 只要数字青科在线, 就直接经「业务应用 → 教务管理系统」
		// 进入教务(SSO)。只有 SSO 进不去、且没有可用的教务凭据时才提示去登录。
		val entered = enterEasSystem(null, null)
		easSessionValid = entered
		if(!entered) {
			// 【第六十七轮·关键】数字青科**是在线的**, 但「业务应用 → 教务管理系统」进不去 ——
			// 这就是用户说的"教务系统因为外部原因无法正常登录"(服务端故障/维护/权限/页面变化)。
			// 界面据此才会走"不弹登录、直接用数字青科尝试"那条分支。
			EasLoginState.mark(EasLoginState.Reason.EXTERNAL, "数字青科在线, 但教务入口进不去")
			throw NeedLoginException()
		}
		EasLoginState.clear()
		return true
	}

	/**
	 * 【第四十轮】探测教务系统原网址(直连)那条路的会话是否还有效
	 *
	 * 没有任何直连 Cookie 时直接返回 false, 不再多发一次网络请求(从没直连登录过的用户
	 * 不该为另一条通道白等一次往返)。探测本身的网络/403 异常一律按"这条路不通"处理,
	 * 交给上层改走数字青科 —— 不能因为直连被拦就把登录流程整个判失败。
	 */
	private suspend fun probeDirectSession(deadline: Long = 0L): Boolean {
		if(direct.getCookie().isEmpty()) return false
		return try {
			// 【第七十七轮】探测也要有上限: 网关吊住时这里同样会一直等。
			// 【第七十八轮】deadline > 0 时用"剩余预算"(与补登共用 8 秒), 到点立刻换道。
			val budget = (if(deadline > 0L) deadline - System.currentTimeMillis() else DIRECT_TIMEOUT_MS)
				.coerceAtLeast(1L)
			withTimeoutOrNull(budget) { direct.absCheckLogin() } ?: false
		}catch(e: Exception) {
			Logger.i("教务原网址(直连)探测失败, 自动改走数字青科: ${e.message}")
			false
		}
	}

	/**
	 * 【第四十轮】用**已保存的教务账号密码**自动补登教务系统原网址。
	 *
	 * 这就是"点击教务系统时先自动登录原网址"的落地: 直连会话断了, 但用户之前在「教务登陆」页
	 * 存过学号+教务系统密码, 就静默补登一次, 用户完全无感。从没存过教务账号直接返回 false。
	 * 登录异常(403/维护/网络)一律折算成 false, 交给上层改走数字青科。
	 */
	private suspend fun autoReloginDirect(deadline: Long = 0L): Boolean {
		val acc = Setting.getString(Keys.EAS_ACCOUNT)
		val pwd = Setting.getString(Keys.EAS_PASSWORD)
		if(acc.isEmpty() || pwd.isEmpty()) return false
		return try {
			// 【第七十七轮·按要求】超过 8 秒没反应就当作这条路不通, 立刻改走数字青科。
			// 【第七十八轮】deadline > 0 时与探测共用同一个 8 秒预算(剩余多少用多少)。
			val budget = (if(deadline > 0L) deadline - System.currentTimeMillis() else DIRECT_TIMEOUT_MS)
				.coerceAtLeast(1L)
			val ok = withTimeoutOrNull(budget) { direct.login(acc, pwd, false) } ?: false
			if(ok) Logger.i("教务原网址(直连)已用保存的账号密码自动补登成功")
			else Logger.i("教务原网址(直连)补登超时或失败, 改走数字青科")
			ok
		}catch(e: Exception) {
			Logger.i("教务原网址(直连)自动补登未成功: ${e.message}")
			false
		}
	}

	override suspend fun login(): Boolean {
		// 【第四十轮·按要求】两条通道的尝试顺序调整为:
		//   ① 教务系统原网址(jwglxt 直连, 学号 + 教务系统密码): 会话在就用, 断了就自动补登;
		//   ② 数字青科里的教务系统(统一身份 → WebVPN → 教务 SSO)。
		// ①的登录即使失败也**不在这里报错**, 而是安静地回退到②:
		// 两条通道都走不通时才抛 [NeedLoginException], 由界面统一跳登录页。
		// 【第七十八轮】直连两条腿同样共用 8 秒预算, 到点立刻换道数字青科
		val directDeadline = System.currentTimeMillis() + DIRECT_TIMEOUT_MS
		if(probeDirectSession(directDeadline) || autoReloginDirect(directDeadline)) {
			direct.isLogin = true
			directChannel = true
			easSessionValid = true
			Logger.i("教务登录: ①教务系统原网址(直连)成功")
			return true
		}
		// ② 数字青科里的教务系统
		if(directChannel) easSessionValid = false
		direct.isLogin = false
		directChannel = false
		val vpnOk = try {
			vpnAccount.login()
		}catch(e: NeedLoginException) {
			false
		}catch(e: Exception) {
			Logger.i("数字青科通道登录失败: ${e.message}")
			false
		}
		if(vpnOk) {
			easSessionValid = true
			directChannel = false
			return afterVpnLogin(true, null, null)
		}
		easSessionValid = false
		directChannel = false
		throw NeedLoginException()
	}

	/**
	 * 登录教务: **先走教务系统原网址(学号 + 教务系统密码), 进不去再自动改走数字青科**
	 *
	 * 这就是"教务系统有两条登录路径"的落地:
	 * - ① 直连 `https://jwglxt.qust.edu.cn/jwglxt/xtgl/login_slogin.html`(RSA 加密密码);
	 * - ② 数字青科统一身份 -> WebVPN -> 「业务应用 → 教务管理系统」的 SSO。
	 *
	 * ①的账号密码按 `saveData` 决定是否保存 —— 用户在本应用的「教务登陆」页(原网址登录)填的
	 * 就是**教务系统自己的账号密码**, 登录成功后保存下来, 下次进教务不用再输;
	 * 若①没走通而改走②, 则**不保存**这份凭据(通用统一身份那一份由「数字青科登陆」页自己管)。
	 */
	override suspend fun login(account: String?, password: String?, saveData: Boolean): Boolean {
		// 【本轮·按要求】记下①这条路上"非凭据类"的失败原因(服务端故障 / 维护 / 被拦 / 网络不通)。
		// 若两条通道最终都没进得去, 用它给出准确提示 —— 不能因为直连那边超时就报"账号或密码错误"。
		var directFailure: Exception? = null
		val hasInput = !account.isNullOrEmpty() && !password.isNullOrEmpty()
		if(hasInput) {
			val directOk = try {
				// 【第七十七轮·按要求】直连最多等 8 秒: 超时按"这条路不通"处理, 立刻改走②
				withTimeoutOrNull(DIRECT_TIMEOUT_MS) { direct.login(account, password, saveData) } ?: false
			}catch(e: Exception) {
				// 被拦(403)/服务端故障/网络不通/页面结构变化: 都按"这条路不通"处理
				directFailure = e
				Logger.i("教务原网址登录未成功(${e.message})")
				false
			}
			if(directOk) {
				directChannel = true
				easSessionValid = true
				EasLoginState.clear()
				Logger.i("教务登录: ①教务系统原网址(直连)成功, 节点=${direct.host}")
				return true
			}
			// 【第六十七轮】① 这条路是"非凭据类"失败(服务端故障/维护/403/网络不通) ——
			// 记成"外部原因", 界面据此才会改用数字青科那条已经通了的路, 而不是反复让用户重输密码。
			directFailure?.let { EasLoginState.mark(EasLoginState.Reason.EXTERNAL, it.message ?: "教务原网址不可用") }
			Logger.i("教务登录: ①教务系统原网址(直连)未成功")
			// 【本轮·修正】如果这一页是"数字青科已经登上、但教务管理系统进不去"之后才打开的
			// (LoginEntry.fromVpnLogin), 这里**不再走②**:
			//   ① 数字青科 → 业务应用 → 教务 这条路刚刚才失败过, 再试一次结论不会变;
			//   ② 这一页让用户输的是**教务系统密码**, 拿去登统一身份属于串号 —— 必然是错的,
			//      多试几次还可能触发学校的账号锁定。
			// 所以「教务登陆」在这一场景下只做一件事: 用原网址直连; 成功就回查询页, 失败如实报错
			// (服务端故障 -> 「暂时无法登录」, 凭据被拒 -> 「账号或密码错误」)。
			if(LoginEntry.fromVpnLogin) {
				Logger.i("教务登录: 本页由「数字青科」转来, 跳过②(数字青科 → 教务 已证明不通)")
				directFailure?.let { throw it }
				throw NeedLoginException()
			}
			// 【第四十轮·按要求】①原网址没进去: 先**自动**试数字青科里已保存的统一身份
			// (`vpnAccount.login()` 无参 = 用已保存的账号密码, 登上后自己走「业务应用 → 教务系统」)。
			// 注意这里用的是**统一身份自己那份**凭据, 绝不会拿刚输入的教务密码去撞统一身份;
			// 数字青科这条路也不通时, 才把"该输数字青科账号密码"的决定权交回用户
			// (抛 NeedVpnLoginException, 由界面弹「暂时无法登录教务系统」并打开「数字青科登陆」页)。
			val vpnAutoOk = try {
				vpnAccount.login()
			}catch(e: NeedLoginException) {
				false
			}catch(e: Exception) {
				Logger.i("数字青科通道自动登录未成功: ${e.message}")
				false
			}
			if(vpnAutoOk) {
				easSessionValid = true
				directChannel = false
				EasLoginState.clear()
				Logger.i("教务登录: ①原网址未成功, 已自动改走数字青科里的教务系统")
				return afterVpnLogin(true, null, null)
			}
			// 【第六十七轮·按要求】走到这里 = ① 原网址不通 **且** ② 数字青科也自动登不上。
			// 分两种前提, 界面要走完全不同的分支:
			//  · directFailure != null(教务服务端故障/维护/403/网络不通) -> EXTERNAL:
			//    这时让用户输密码也没用, 界面应该去走"数字青科 → 教务"那条已经验证过的路;
			//  · 没有 directFailure(纯粹是没有数字青科凭据) -> NO_CREDENTIAL:
			//    正常弹「数字青科登陆」让用户输统一身份。
			if(directFailure != null) {
				EasLoginState.mark(EasLoginState.Reason.EXTERNAL, directFailure.message ?: "教务原网址不可用")
			}else{
				EasLoginState.mark(EasLoginState.Reason.NO_CREDENTIAL, "没有可用的数字青科凭据")
			}
			Logger.i("教务登录: ①原网址未成功, 转「数字青科登陆」由用户输入统一身份凭据")
			throw NeedVpnLoginException()
		}
		// ② 数字青科里的「教务管理系统」
		if(directChannel) easSessionValid = false
		directChannel = false
		// 走②时这份输入是"统一身份"口径, 不写教务账号位(避免又把统一身份密码串存成教务密码)
		return try {
			val vpnOk = vpnAccount.login(account, password, false)
			afterVpnLogin(vpnOk, account, password)
		}catch(e: NeedLoginException) {
			// 两条通道都没进去: 有"非凭据类"的失败原因就抛它(界面显示「暂时无法登录」),
			// 否则才是真的账号密码不对(界面显示「账号或密码错误」)。
			throw directFailure ?: e
		}
	}

	/**
	 * 数字青科登录成功后的收尾: 以**通道登录结果**判定教务是否可用。
	 *
	 * 【本期根因修复】教务只是数字青科的一个业务应用, 统一身份登录成功就等于"有权限进入教务"。
	 * 以前这里要求 `进教务也成功` 才算登录成功, 一旦教务入口(SSO/补登)失败, 整次登录被判失败,
	 * 界面既没有提示也不会返回上一页 —— 用户看到的现象就是"登录数字青科后也不行"、"卡在登录页"。
	 * 现在教务入口改为**尽力而为**: 成功就顺手建立会话, 失败也不影响登录结论,
	 * 后续查询会给出准确原因(如「暂时没有访问权限」), 不再把人挡在登录页。
	 */
	private suspend fun afterVpnLogin(vpnOk: Boolean, account: String?, password: String?): Boolean {
		if(vpnOk) {
			try {
				enterEasSystem(account, password)
			}catch(e: Exception) {
				// 教务侧 403/维护/页面结构变化: 不影响"数字青科已登录"这个结论
				Logger.e("进入教务系统失败(忽略, 数字青科已登录)", e)
			}
		}
		easSessionValid = vpnOk
		return vpnOk
	}

	/**
	 * 【本轮·新增】「数字青科」登录成功之后，立刻按「业务应用 → 教务管理系统」建立教务会话。
	 *
	 * 登录页用它来判断**这次登录能不能直接查教务**：
	 * - 返回 true  -> 教务会话已建立，可以直接回到查询页继续查;
	 * - 返回 false -> 数字青科登上了，但教务入口(SSO / 权限 / 维护)进不去，
	 *   这时界面会接着给出「教务登陆」(学号 + 教务系统密码)页面，让用户改走原网址直连。
	 *
	 * 这里吞掉所有异常并折算成 false：调用方只关心"教务能不能用"，具体原因由后续查询报出。
	 */
	override suspend fun enterEasByVpn(): Boolean {
		return try {
			val ok = enterEasSystem(null, null)
			// 【第七十六轮·修"教务系统明明登录了却显示没登陆"】这条路径(数字青科登录页 →
			// 教务管理系统)成功后, 以前**不落盘教务会话位** —— isLogin/getAccount 全都
			// 还认为"没登录", 「我」页面板上就显示"未登录"。
			// 现在进去了就记一笔, 与 checkLogin()/afterVpnLogin() 的口径一致。
			if(ok) easSessionValid = true
			ok
		}catch(e: Exception) {
			Logger.e("数字青科已登录, 但进入教务管理系统失败", e)
			false
		}
	}

	/**
	 * 「业务应用 → 教务管理系统」后的教务会话确认。
	 *
	 * 数字青科登录成功、并已通过 `sso/driotlogin` 进入教务后:
	 * - 教务会话已建立(SSO 生效) -> 直接返回 true, 不再多发登录请求;
	 * - 教务会话未建立(SSO 没带过去) -> 用同一套统一身份账号经该通道登录一次教务。
	 * 这样无论 SSO 是否生效, 教务功能(课表/成绩/教务通知调课等)都能正常调用。
	 */
	private suspend fun enterEasSystem(account: String?, password: String?): Boolean {
		if(absCheckLogin()) return true
		// 【本期】没有单独的教务凭据时, 回退到「数字青科」的统一身份账号 ——
		// 两者本来就是同一个账号, 用户只登录过数字青科也能自动进入教务。
		val acc = account ?: Setting.getString(Keys.EAS_ACCOUNT).ifEmpty { Setting.getString(Keys.IPASS_ACCOUNT) }
		val pwd = password ?: Setting.getString(Keys.EAS_PASSWORD).ifEmpty { Setting.getString(Keys.IPASS_PASSWORD) }
		if(acc.isEmpty() || pwd.isEmpty()) return false
		return absLogin(acc, pwd)
	}

	/**
	 * 退出**教务**登录
	 *
	 * 只清教务自己的会话与账号信息, **不动 WebVPN / 数字青科的登录状态**
	 * (它们本来就是两套东西, 以前会被一起退掉)。
	 *
	 * 【本轮】教务现在有两条通道, 退的时候必须**两条一起退**: 只退一条的话另一条的会话还在,
	 * 下一次查询会从那条通道静默恢复, 表现为"点了退出登录却还是已登录"。
	 */
	override fun logout() {
		cookieJar.clearCookies()
		Setting.edit { it.remove(accountName).remove(passwordName) }
		easSessionValid = false
		directChannel = false
		// 清直连通道自己的会话(`eaDirectCookie`)与会话位, 不动数字青科的登录
		direct.logout()
	}

	/**
	 * 请求走线: 走哪条通道由 [directChannel] 决定
	 *
	 * 教务有两条登录路径, 但业务接口(课表/成绩/学业/考试/教务通知调课)完全一样,
	 * 所以这里只换"怎么把请求送到 jwglxt 去", 业务逻辑全部留在 [EASAccount] 里。
	 */

	@Throws(IOException::class, NeedLoginException::class)
	override suspend fun get(url: String): Response {
		return if(directChannel) direct.get(url) else vpnAccount.get(url)
	}

	@Throws(IOException::class, NeedLoginException::class)
	override suspend fun post(url: String, body: RequestBody): Response {
		return if(directChannel) direct.post(url, body) else vpnAccount.post(url, body)
	}

	@Throws(IOException::class)
	override suspend fun getNoCheck(url: String): Response {
		return if(directChannel) direct.getNoCheck(url) else vpnAccount.getNoCheck(url)
	}

	@Throws(IOException::class)
	override suspend fun postNoCheck(url: String, requestBody: RequestBody): Response {
		return if(directChannel) direct.postNoCheck(url, requestBody) else vpnAccount.postNoCheck(url, requestBody)
	}

	@Throws(IOException::class)
	override suspend fun getNoRedirect(url: String): Response {
		return if(directChannel) direct.getNoRedirect(url) else vpnAccount.getNoRedirect(url)
	}

	@Throws(IOException::class)
	override suspend fun postNoRedirect(url: String, requestBody: RequestBody): Response {
		return if(directChannel) direct.postNoRedirect(url, requestBody) else vpnAccount.postNoRedirect(url, requestBody)
	}
}