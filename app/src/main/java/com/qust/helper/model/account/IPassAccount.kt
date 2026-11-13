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
import com.qust.helper.data.api.QustApi
import com.qust.helper.utils.CodeUtils
import com.qust.helper.utils.VpnEncodeUtils
import okhttp3.Cookie
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.Response
import java.io.IOException
import java.net.HttpURLConnection

class IPassAccount private constructor() : Account(
	QustApi.VPN_HOST,
	Keys.IPASS_ACCOUNT,
	Keys.IPASS_PASSWORD,
	"https",
	"ipassCookie",
){

	companion object {
		fun getInstance(): IPassAccount {
			return Instance.INSTANCE
		}
	}

	private object Instance{
		val INSTANCE = IPassAccount()
	}

	/**
	 * 【第四十二轮·提速】CAS 登录成功后重定向链已经落到门户页(会话必然已建立),
	 * 不必再做"GET / 看会不会跳登录页"的复核 —— 少一个网络往返, 欢迎页登录快一截。
	 */
	override fun skipVerifyAfterLogin(): Boolean = true
	
	/**
	 * 【本期·首次登录兜底】把网页里登录成功后的会话 Cookie 导入接口通道
	 *
	 * 刚装好软件时"直接登录教务"可能失败(教务入口/SSO 异常), 这时改由用户在「数字青科」
	 * 登录页里手动登录。登录成功后 Cookie 只存在 WebView 的 CookieManager 里,
	 * OkHttp 的 CookieJar 拿不到 —— 不导进来的话, 后续查课表仍会被当成"未登录"。
	 *
	 * @param raw `CookieManager.getCookie(host)` 返回的 "k=v; k2=v2" 串
	 * @return 是否导入成功
	 */
	fun adoptWebViewCookies(raw: String?): Boolean {
		if(raw.isNullOrEmpty()) return false
		val url: HttpUrl = HttpUrl.Builder().scheme(scheme).host(host).build()
		val cookies = raw.split(";").mapNotNull { Cookie.parse(url, it.trim()) }
		if(cookies.isEmpty()) return false
		cookieJar.saveFromResponse(url, cookies)
		return true
	}

	override suspend fun absCheckLogin(): Boolean {
		getNoRedirect("").use {
			val code: Int = it.code
			if(code == HttpURLConnection.HTTP_MOVED_PERM || code == HttpURLConnection.HTTP_MOVED_TEMP){
				// 【本期】跳登录页 = 未登录。这里只回答"是否已登录", 真正的登录交给
				// Account.checkLogin() 统一执行(避免 absCheckLogin ↔ login 互相调用)。
				return !(it.header("Location")?.contains("login") ?: false)
			}
			// 【本期】200 也可能是登录页(学校在会话失效时返回 200 + 登录框):
			// 以前只看状态码 -> 误判已登录 -> 一直用过期 Cookie, 网页停在登录页("没能自动登录数字青科")
			val body = try { it.body?.string() } catch(_: Exception) { null }
			if(isLoginPageBody(body)) return false
			return true
		}
	}

	/**
	 * 【第四十轮·修"自动登录登录不上"】学校把登录页升级成了 CAS(数字青科), 本方法按新页面重写,
	 * 并保留旧页面的登录路子做兜底 —— 两个分支由登录页里**有没有 `execution` 字段**区分:
	 *
	 *  · 新 CAS 页(现在线上是这个): 表单字段 `username / password / captcha / mfaState /
	 *    currentMenu / failN / execution / _eventId / geolocation / fpVisitorId / trustAgent`,
	 *    密码用登录页同目录 `jwt/publicKey` 下发的 PEM 公钥做 **RSA(PKCS1) 加密**。
	 *    旧代码在这一步就死了: 它按 `name="lt"` 抓令牌, 新页面根本没有这个字段,
	 *    于是永远抛"无法获取lt" —— 用户看到的就是"自动登录登录不上"。
	 *  · 旧网关页(万一哪个入口还在用): `rsa / ul / pl / lt / execution=e1s1 / _eventId`。
	 *
	 * 成功判定沿用老办法: 登录请求发生 302 跳转即成功; 停在原页时只有页面明确写
	 * "账号/密码不对"才返回 false, 其余一律抛「暂时无法登录」。
	 */
	override suspend fun absLogin(account: String, password: String): Boolean {
		cookieJar.clearCookies()

		var loginUrl: HttpUrl
		var html: String

		getNoCheck("").use {
			html = it.body?.string() ?: throw IOException("无法打开登录页")
			// 登录页被拦(403 拦截页): 明确提示"暂时没有访问权限"
			if(isForbiddenBody(html)) throw ServerException("暂时没有访问权限")
			loginUrl = it.request.url
		}

		// CAS 的 execution 是会话级的, 必须取"这一张登录页"里的值
		val execution = CodeUtils.matcher(VpnEncodeUtils.EXECUTION_PATTERN, html)
		val body: FormBody = if(!execution.isNullOrEmpty()) {
			// ---- 新 CAS 登录 ----
			// 公钥在登录页同目录的 jwt/publicKey(把路径末段的 login 换成 jwt/publicKey)
			val keyPath = loginUrl.encodedPath.let { p ->
				val i = p.lastIndexOf('/')
				if(i > 0) p.substring(0, i) else ""
			} + "/jwt/publicKey"
			val pem = getNoCheck(keyPath.removePrefix("/")).use { resp ->
				val key = resp.body?.string()
				if(resp.code != HttpURLConnection.HTTP_OK || key.isNullOrEmpty()) throw IOException("无法获取登录公钥")
				key
			}
			val rsa = VpnEncodeUtils.rsaEncryptWithPem(password, pem) ?: throw IOException("登录公钥加密失败")
			FormBody.Builder()
				.add("username", account)
				// 【第四十一轮·实测根因】登录页 JS 是 `password = "__RSA__" + JSEncrypt.encrypt(password)`,
				// 服务端只认带 **__RSA__ 前缀** 的密文。上一轮直接发了裸 RSA Base64,
				// 服务端解不开 -> 每次都停在登录页 -> 界面永远「暂时无法登录」。
				.add("password", "__RSA__" + rsa)
				.add("captcha", "")
				.add("mfaState", "")
				.add("currentMenu", "1")
				.add("failN", "-1")
				.add("execution", execution)
				.add("_eventId", "submit")
				.add("geolocation", "")
				.add("fpVisitorId", "")
				.add("trustAgent", "")
				.build()
		}else{
			// ---- 旧网关登录(兜底) ----
			val lt = CodeUtils.matcher(VpnEncodeUtils.LT_PATTERN, html) ?: throw IOException("无法获取登录参数")
			FormBody.Builder()
				.add("rsa", VpnEncodeUtils.encode(account, password, lt))
				.add("ul", account.length.toString())
				.add("pl", password.length.toString())
				.add("lt", lt)
				.add("execution", "e1s1")
				.add("_eventId", "submit")
				.build()
		}

		postNoCheck(
			loginUrl.pathSegments.joinToString(separator = "/"),
			body
		).use { resp ->
			val lastResponse: Response? = resp.priorResponse
			// 302 跳转 = 登录成功(进门户)
			if(lastResponse != null && (lastResponse.code == HttpURLConnection.HTTP_MOVED_PERM ||
				lastResponse.code == HttpURLConnection.HTTP_MOVED_TEMP)) {
				return true
			}
			// 没有跳转: 先排除服务端故障
			if(resp.code >= 500) throw ServerException("暂时无法登录")
			val respBody = try { resp.body?.string() } catch(_: Exception) { null }
			if(isBadCredentialsBody(respBody)) return false
			// 【第四十八轮·按要求】第四十一轮已实测: CAS 拒绝凭据的方式就是
			// "200 + 重新发一张登录页"(页面带"登录失败，处理失败"标记, 没有明确的
			// "密码错误"文案) —— 所以**服务端正常时停在登录页 = 账号或密码不对**,
			// 统一返回 false 让界面报「账号或密码错误」。
			// 真正的服务故障(维护页/网关错误页/空白页)不是登录页, 仍走「暂时无法登录」。
			if(isLoginPageBody(respBody) || respBody?.contains("登录失败") == true) return false
			throw ServerException("暂时无法登录")
		}
	}
}