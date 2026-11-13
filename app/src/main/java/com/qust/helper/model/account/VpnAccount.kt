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

import com.qust.helper.utils.VpnEncodeUtils
import okhttp3.Cookie
import okhttp3.RequestBody
import okhttp3.Response
import java.io.IOException
import java.net.HttpURLConnection

open class VpnAccount(
	private val host: String,
	private val scheme: String = "https",
	private val ipass: IPassAccount = IPassAccount.getInstance()
): IAccount{

	var needLogin = true

	override var isLogin: Boolean
		get() = ipass.isLogin
		set(_) {}

	override fun getAccount(): String {
		return ipass.getAccount()
	}

	override fun getCookie(): List<Cookie> {
		return ipass.getCookie()
	}

	@Throws(IOException::class, NeedLoginException::class)
	override suspend fun checkLogin(): Boolean {
		ipass.getNoRedirect("").use {
			val code: Int = it.code
			if(code == HttpURLConnection.HTTP_MOVED_PERM || code == HttpURLConnection.HTTP_MOVED_TEMP){
				// 跳登录页 = 会话已失效, 用已保存的统一身份账号重新登录
				if(it.header("Location")?.contains("login") != false) return login()
				return if(needLogin) afterLogin() else true
			}
			// 【本期】HTTP 200 也可能是"登录页"(学校在会话失效时返回 200 + 登录框, 不是 302)。
			// 以前这里直接当"已登录", 于是拿过期 Cookie 去加载网页, 页面停在登录框,
			// 表现为「教务系统没能自动登录数字青科」。检出登录页就重新登录一次。
			val body = try { it.body?.string() } catch(_: Exception) { null }
			if(isLoginPageBody(body)) return login()
			return if(needLogin) afterLogin() else true
		}
	}

	open suspend fun afterLogin(): Boolean{
		return true
	}

	override suspend fun login(): Boolean {
		ipass.login()
		if(!isLogin){
			needLogin = true
			throw NeedLoginException()
		}else{
			needLogin = false
		}
		return afterLogin()
	}

	override suspend fun login(account: String?, password: String?, saveData: Boolean): Boolean {
		ipass.login(account, password, saveData)
		if(!isLogin){
			needLogin = true
			throw NeedLoginException()
		}else{
			needLogin = false
		}
		return afterLogin()
	}

	@Throws(IOException::class, NeedLoginException::class)
	 suspend fun get(scheme: String, host: String, url: String): Response {
		return ipass.get(VpnEncodeUtils.encryptUrl(scheme, host, url))
	}

	@Throws(IOException::class, NeedLoginException::class)
	 suspend fun post(scheme: String, host: String, url: String, body: RequestBody): Response {
		return ipass.post(VpnEncodeUtils.encryptUrl(scheme, host, url), body)
	}

	@Throws(IOException::class)
	 suspend fun getNoCheck(scheme: String, host: String, url: String): Response {
		return ipass.getNoCheck(VpnEncodeUtils.encryptUrl(scheme, host, url))
	}

	@Throws(IOException::class)
	 suspend fun postNoCheck(scheme: String, host: String, url: String, requestBody: RequestBody): Response {
		return ipass.postNoCheck(VpnEncodeUtils.encryptUrl(scheme, host, url), requestBody)
	}

	@Throws(IOException::class)
	 suspend fun getNoRedirect(scheme: String, host: String, url: String): Response {
		return ipass.getNoRedirect(VpnEncodeUtils.encryptUrl(scheme, host, url))
	}

	@Throws(IOException::class)
	 suspend fun postNoRedirect(scheme: String, host: String, url: String, requestBody: RequestBody): Response {
		return ipass.postNoRedirect(VpnEncodeUtils.encryptUrl(scheme, host, url), requestBody)
	}


	@Throws(IOException::class, NeedLoginException::class)
	override suspend fun get(url: String): Response {
		return ipass.get(VpnEncodeUtils.encryptUrl(scheme, host, url))
	}

	@Throws(IOException::class, NeedLoginException::class)
	override suspend fun post(url: String, body: RequestBody): Response {
		return ipass.post(VpnEncodeUtils.encryptUrl(scheme, host, url), body)
	}

	@Throws(IOException::class)
	override suspend fun getNoCheck(url: String): Response {
		return ipass.getNoCheck(VpnEncodeUtils.encryptUrl(scheme, host, url))
	}

	@Throws(IOException::class)
	override suspend fun postNoCheck(url: String, requestBody: RequestBody): Response {
		return ipass.postNoCheck(VpnEncodeUtils.encryptUrl(scheme, host, url), requestBody)
	}

	@Throws(IOException::class)
	override suspend fun getNoRedirect(url: String): Response {
		return ipass.getNoRedirect(VpnEncodeUtils.encryptUrl(scheme, host, url))
	}

	@Throws(IOException::class)
	override suspend fun postNoRedirect(url: String, requestBody: RequestBody): Response {
		return ipass.postNoRedirect(VpnEncodeUtils.encryptUrl(scheme, host, url), requestBody)
	}

	override fun logout() {
		ipass.logout()
	}
}