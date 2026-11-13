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

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import com.qust.helper.data.Keys
import com.qust.helper.data.Setting
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import kotlinx.coroutines.launch
import java.io.IOException

class DrinkAccount private constructor(): Account(
	host = "dcxy-customer-app.dcrym.com",
	accountName = Keys.DRINK_ACCOUNT,
	passwordName = Keys.DRINK_PASSWORD,
	scheme = "https",
	cookieName = "drinkCookie"
) {

	private var userToken = Setting.getString("drinkToken")
		set(value){
			field = value
			Setting.edit { it.putString("drinkToken", value) }
		}

	val _drinkCode = mutableStateOf(Setting.getString("drinkCode"))
	val drinkCode by _drinkCode

	/**
	 * 【第四十二轮·按要求】条码一变, 桌面饮水码小组件同步刷新。
	 *
	 * 这里是**所有**条码更新的必经点 —— 软件内手动刷新、「我 → 饮水码」进页自动取码、
	 * 登录后首次取码、退出登录清码, 全部走 [setDrinkCodeValue]。挂在这一处,
	 * 就不用在每个界面里各自记得去刷小组件(以前只有 DrinkPage / 账号管理两处记得刷)。
	 *
	 * updateAll 是挂起函数, 这里用 GlobalScope 扔后台: 条码更新本身已在 IO 协程里,
	 * 小组件刷新失败(没添加过小组件/系统忙)也不该影响取码结果, 全部 runCatching 吞掉。
	 */
	fun setDrinkCodeValue(value: String) {
		val changed = _drinkCode.value != value
		_drinkCode.value = value
		Setting.edit { it.putString("drinkCode", value) }
		if(changed) {
			kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
				// 【第四十七轮·纯 RemoteViews 直写】日志实锤 Glance 在 MIUI 上"刷新指令发出但渲染
				// 不落地"(4 次刷新只有 1 次渲染且迟到 19 秒)。现在饮水码小组件已弃用 Glance,
				// `push` = 直接 AppWidgetManager.updateAppWidget —— 最底层调用, 返回时系统已收到
				// 新画面, 条码立即跟着变。不再有任何广播排队/黑盒 compose。
				com.qust.helper.ui.appwidget.DrinkCodeWidget.push(com.qust.helper.App.instance)
			}
		}
	}

	/**
	 * 【第三十轮】退出登录时把饮水自己的会话也清干净:
	 * `userToken`(登录令牌) 和 `_drinkCode`(条码) 都是内存里的状态, 基类只清 Cookie 与账号密码,
	 * 不清这两项的话「我 → 饮水码」还会拿旧条码渲染, 看起来"退出登录没生效"。
	 */
	override fun logout() {
		super.logout()
		userToken = ""
		setDrinkCodeValue("")
		isLogin = false
	}

	companion object {
		fun getInstance(): DrinkAccount {
			return Instance.INSTANCE
		}
	}

	private object Instance {
		val INSTANCE = DrinkAccount()
	}

	override suspend fun absCheckLogin(): Boolean {
		if(userToken.isEmpty()) throw NeedLoginException()
		val js: JSONObject
		try {
			postNoCheck("app/customer/login",
				"{\"loginTime\": \"${System.currentTimeMillis()}\"}".toRequestBody("application/json".toMediaType()),
				Headers.headersOf("clientsource", "{}", "token", userToken)
			).use {
				js = JSONObject(it.body!!.string())
			}
		} catch(_: Exception) {
			return false
		}
		return if(js.getInt("code") != 1000) {
			userToken = ""
			false
		}else{
			true
		}
	}

	override suspend fun absLogin(account: String, password: String): Boolean {
		val js: JSONObject

		try {
			postNoCheck(
				"app/customer/login",
				JSONObject().apply {
					put("loginAccount", account)
					put("password", password)
				}.toString().toRequestBody("application/json".toMediaType()),
				Headers.headersOf("clientsource", "{}")
			).use {
				js = JSONObject(it.body!!.string())
			}
		}catch(e: ServerException){
			// 【本轮·需求7】服务端故障/网络不通的准确文案不能被下面的 IOException 包装吞掉,
			// 否则界面只能拿到 "com.qust.helper.model.account.ServerException: ..." 这种原始字符串。
			throw e
		}catch(e: Exception){
			throw IOException(e)
		}


		return if(js.getInt("code") == 1000) {
			userToken = js.getJSONObject("data").getString("token")
			true
		} else {
			throw IOException(js.getString("msg"))
		}
	}

	@Throws(IOException::class)
	suspend fun getDrinkCode(){
		val js: JSONObject
		try {
			getNoCheck("app/customer/flush/idbar", Headers.headersOf("clientsource", "{}", "token", userToken)).use {
				js = JSONObject(it.body!!.string())
			}
		}catch(e: ServerException){
			throw e
		}catch(e: Exception){
			throw IOException(e)
		}

		val data: String = js.getString("data")
		setDrinkCodeValue(data.substring(0, data.length - 1) + "3")
	}
}