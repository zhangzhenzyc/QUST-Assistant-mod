/*
 * 青科助手（修改版） —— 基于开源项目 QUST-Assistant 修改而成
 *
 * 原项目版权所有 (C) 2891954521  (https://github.com/2891954521/QUST-Assistant)
 * 原始开源许可: GNU General Public License v3.0 (GPL-3.0), 许可原文见 LICENSE 文件, 保持完整未改动。
 *
 * 修改版版权所有 (C) 2026 zhangzhenzyc  (https://github.com/zhangzhenzyc)
 * 本修改版同样以 GPL-3.0 发布; 附加版权声明见 NOTICE.md —— 两份版权声明相互独立、同时生效。
 */

package com.qust.helper.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.qust.helper.data.Keys
import com.qust.helper.data.Setting
import com.qust.helper.model.account.DrinkAccount
import com.qust.helper.model.account.NeedLoginException
import com.qust.helper.model.account.ServerException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DrinkViewModel: BaseViewModel() {

	private val drinkAccount: DrinkAccount = DrinkAccount.getInstance()

	val uiState = DrinkUIState(drinkAccount = drinkAccount)

	val uiEvent = object : DrinkUIEvent{
		override fun login(refreshCode: Boolean, onResult: (Boolean) -> Unit){
			this@DrinkViewModel.login(refreshCode, onResult)
		}
		override fun getDrinkCode(){ this@DrinkViewModel.getDrinkCode() }
	}

	/**
	 * 登录饮水码
	 *
	 * 【本轮·按要求】改成"**登录结果出来后再回调**":
	 * 以前登录页点了确定就立刻 `popBackStack()`, 而登录是在后台协程里跑的 ——
	 * 回到账号管理时账号密码还没写进去, 要等这次网络请求(约 3 秒)回来才变成"更换账号/退出登录"。
	 * 现在由登录页等回调, 成功才返回, 回到账号管理时数据已经落盘。
	 *
	 * @param refreshCode 登录成功后是否顺手刷新一次饮水码(「我 → 饮水码」页需要; 账号管理里的登录页不需要)
	 * @param onResult 登录结果回调(主线程), true=成功
	 */
	fun login(refreshCode: Boolean = true, onResult: (Boolean) -> Unit = { }){
		viewModelScope.launch {
			showDialog("登录中")
			var ok = false
			try {
				val result = withContext(Dispatchers.IO){
					drinkAccount.login(uiState.account.value, uiState.password.value, true)
				}
				if(result){
					// 【修改版】登录成功后同步界面账号/密码状态(避免需要点两次才生效)
					uiState.account.value = Setting.getString(Keys.DRINK_ACCOUNT)
					uiState.password.value = Setting.getString(Keys.DRINK_PASSWORD)
					ok = true
				}
				else toastError("账号或密码错误")
			}catch(e: Exception) {
				// 【本轮·需求7】与教务/数字青科统一口径:
				// 只有服务端明确说凭据不对才报"账号或密码错误", 连不上/超时/维护一律"暂时无法登录",
				// 避免把 java.net.UnknownHostException 这种原始异常直接怼到用户脸上。
				toastError(friendlyLoginError(e))
			}
			// 【第三十一轮·修"登录成功后又弹了一遍登录页"】
			// 以前是 `onResult(ok)` 之后才 `getCode()`: 登录页一收到成功就 popBackStack 回到饮水码页,
			// 而此刻条码还没取到(drinkCode 仍是空的), 饮水码页的"进页面自动取码"立刻又发一次请求 ——
			// 两个请求并发、其中一个失败就把 needLogin 置 true, 于是登录页被**第二次**打开。
			// 现在先把条码取回来(静默, 不弹"正在刷新"), 再回调让登录页返回: 回去时码已经在手上了。
			if(ok && refreshCode) refreshCodeQuietly()
			clearDialog()
			onResult(ok)
		}
	}

	/**
	 * 静默刷新一次条码(登录成功后调用): 不弹进度条、失败也不打扰用户 ——
	 * 失败时只是"码没取到", 由饮水码页按正常流程再提示, 不会连带把登录页再弹一遍。
	 */
	private suspend fun refreshCodeQuietly(){
		runCatching {
			withContext(Dispatchers.IO) {
				if(drinkAccount.checkLogin()) drinkAccount.getDrinkCode()
			}
		}
	}

	fun getDrinkCode(){
		// 【第三十一轮】同一个请求还没回来时不再发第二次:
		// 以前"进页面自动取码"和"登录成功后刷新"会撞在一起, 两个请求互相打架。
		if(codeJob?.isActive == true) return
		codeJob = viewModelScope.launch { getCode() }
	}

	/** 正在跑的"取码"任务(用于去重) */
	private var codeJob: Job? = null

	/**
	 * 【第三十轮】把界面上的账号/密码与本地存储对齐。
	 *
	 * `uiState` 是 ViewModel 级的(整个 Activity 共用一份), 账号管理里退出登录只会删掉
	 * SharedPreferences 里的键, 内存里那份**还是旧的** —— 于是回到「饮水码」页时
	 * 界面以为"还存着账号", 又去自动登录一次(即便 token 已经清掉)。
	 * 进页面/进登录页时先调一次这里, 退出登录就不会留尾巴。
	 */
	fun syncAccountState(){
		uiState.account.value = Setting.getString(Keys.DRINK_ACCOUNT)
		uiState.password.value = Setting.getString(Keys.DRINK_PASSWORD)
	}

	private suspend fun getCode(){
		try {
			showDialog("正在刷新")
			withContext(Dispatchers.IO) {
				if(drinkAccount.checkLogin()){
					drinkAccount.getDrinkCode()
				}else{
					throw NeedLoginException()
				}
			}
		}catch(e: NeedLoginException){
			toastWarning("请先登录")
			uiState.needLogin = true

		}catch(e: Exception) {
			// 【本轮·需求7】刷新失败同样不把底层异常字符串直接抛给用户
			toastError("刷新失败: ${friendlyLoginError(e)}")

		}finally {
			clearDialog()
		}
	}
}

class DrinkUIState(drinkAccount: DrinkAccount) {
	var drinkCode by drinkAccount._drinkCode
	var account = mutableStateOf(Setting.getString(key = Keys.DRINK_ACCOUNT))
	var password = mutableStateOf(Setting.getString(key = Keys.DRINK_PASSWORD))
	var needLogin by mutableStateOf(false)
}

interface DrinkUIEvent{
	/** @param refreshCode 登录成功后是否顺手刷新条码(「我 → 饮水码」需要, 账号管理里的登录页不需要) */
	fun login(refreshCode: Boolean = true, onResult: (Boolean) -> Unit = { }){ }
	fun getDrinkCode(){ }
}

/**
 * 【本轮·需求7】登录失败的提示口径, 与教务/数字青科保持一致 —— 只分三种话:
 * 「账号或密码错误」(服务端明确说凭据不对) / 「暂时无法登录」(连不上、超时、维护) / 「暂时没有访问权限」(403)。
 *
 * 判定规则:
 * - ServerException: 服务端故障或网络不通 -> 「暂时无法登录」;
 * - message 里带中文: 那是饮水服务端返回的原话(如"账号不存在"), 原样展示;
 * - 其余(形如 java.net.UnknownHostException / timeout / SSL 的底层异常字符串) -> 「暂时无法登录」。
 */
private fun friendlyLoginError(e: Exception): String {
	if(e is ServerException) return "暂时无法登录"
	val msg = e.message
	if(msg.isNullOrBlank()) return "暂时无法登录"
	val hasChinese = msg.any { it.code in 0x4E00..0x9FFF }
	return if(hasChinese) msg else "暂时无法登录"
}