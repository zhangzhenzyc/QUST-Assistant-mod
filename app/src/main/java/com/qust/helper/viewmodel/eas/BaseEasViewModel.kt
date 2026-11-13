/*
 * 青科助手（修改版） —— 基于开源项目 QUST-Assistant 修改而成
 *
 * 原项目版权所有 (C) 2891954521  (https://github.com/2891954521/QUST-Assistant)
 * 原始开源许可: GNU General Public License v3.0 (GPL-3.0), 许可原文见 LICENSE 文件, 保持完整未改动。
 *
 * 修改版版权所有 (C) 2026 zhangzhenzyc  (https://github.com/zhangzhenzyc)
 * 本修改版同样以 GPL-3.0 发布; 附加版权声明见 NOTICE.md —— 两份版权声明相互独立、同时生效。
 */

package com.qust.helper.viewmodel.eas

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.qust.helper.model.Logger
import com.qust.helper.model.account.EASAccount
import com.qust.helper.model.account.NeedLoginException
import com.qust.helper.ui.widget.DialogAble
import com.qust.helper.ui.widget.DialogAbleImpl
import com.qust.helper.ui.widget.ToastAble
import com.qust.helper.ui.widget.ToastAbleImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

abstract class BaseEasViewModel(application: Application) : AndroidViewModel(application), ToastAble by ToastAbleImpl(), DialogAble by DialogAbleImpl() {

	protected val easAccount = EASAccount.getInstance()

	val pickYear = mutableIntStateOf(easAccount.getCurrentGrade())

	/**
	 * 【第二十八轮·根因修复】用户是否在"学期"滚轮上手动选择过。
	 *
	 * 没手动选过时, 每次查询都按"当前学期"重新计算 pickYear(并同步滚轮位置);
	 * 手动选过之后, 以用户的选择为准, 不再自动覆盖。
	 */
	var pickYearUserSet = false

	var needLogin by mutableStateOf(false)

	/** 因跳转登录页(前进)而离开 Web 页时为 true: onDispose 不复位 hasCheck, 避免返回后重复弹"正在登录" */
	var leavingForLogin by mutableStateOf(false)

	/** 【修改版】最近一次跳登录页的时间戳: 同一瞬间多处触发时只导航一次
	 *  (教务/数字青科等页面共用, 避免"连着弹出两个登录界面") */
	var lastLoginNavAt = 0L

	/**
	 * 获取选择的学期参数
	 */
	protected fun getYearAndTerm(): Pair<String, String>{
		val entranceTime = easAccount.entranceTime
		if(entranceTime == -1) toastWarning("未设置入学年份，可能导致查询结果异常")
		// 【第二十八轮·根因修复】用户没手动选过学期时, 按真实入学年份重算"当前学期"并同步滚轮:
		// 首次登录时 ViewModel 可能在登录前就已创建(那时入学年份未知), pickYear 会被算成无效值,
		// 若一直沿用, 查询会带错误学年(xnm=2028) → "该学年学期尚无您的课表"。
		val pick = if(pickYearUserSet) {
			pickYear.intValue
		} else {
			easAccount.getCurrentGrade().also { pickYear.intValue = it }
		}
		return Pair(
			(pick / 2 + entranceTime).toString(),
			if(pick % 2 == 0) "3" else "12"
		)
	}

	fun request(
		block: suspend EASAccount.() -> Unit,
		final: () -> Unit = { },
		onError: (Exception) -> Unit = {
			// 【第四十六轮】去掉"网络错误:"前缀: 直接展示具体原因
			// 【第七十轮·修"考试查询 <1s 中断无提示"】空消息/空白消息也必须有一句人话:
			// 偶发的异常 message 为 null 或一串空白时, 旧兜底只对 null 生效, 用户啥也看不到。
			toastError(it.message?.takeIf { m -> m.isNotBlank() } ?: "查询失败，请稍后再试")
		},
		/**
		 * 【v3.6.0180·新增】被拦在"未登录"这一关时额外要做的事。
		 *
		 * 未登录不走 [onError], 只弹一个 1 秒气泡 + 把 [needLogin] 置 true —— 页面列表是空的,
		 * 又没有任何文字说明, 用户看到的就是"'查询中'闪一下, 然后什么都没有"。
		 * 需要"把原因留在页面上"的页面(考试查询)用这个回调自己写一句。
		 */
		onNeedLogin: () -> Unit = { }
	) {
		// 【第六十七轮·按要求】"如果登录了数字青科, 就自动登录数字青科的教务系统"。
		//
		// 这里的 `lastRequest` 就是"本页上一次查询" —— 自动登录成功之后要**把这次查询重跑一遍**,
		// 否则登录虽然成功了, 页面上还是那句"请先登录", 用户只能自己再点一次刷新。
		// 只存**最后一次**(不留历史): 页面被销毁时它跟着 ViewModel 一起走, 不会拖住任何引用。
		lastRequest = block
		lastFinal = final
		lastError = onError
		lastNeedLogin = onNeedLogin
		viewModelScope.launch {
			withContext(Dispatchers.IO) {
				try {
					block(easAccount)
				}catch(e: NeedLoginException){
					// 【第三十二轮·按要求】气泡统一成「请先登录教务系统」, 随后由页面上的
					// AppWidgets.CheckEasLogin 决定下一步(见那里的说明)。
					// 【第七十七轮·按要求"账号管理里有账号和密码就算登录成功"】存过教务凭据时不弹这句:
					// 用户明明已经存过账号密码, 再提示"请先登录"只会让他反复点、反复弹(连续几次截图)。
					// 这时交给 CheckEasLogin 走"自动登录/直接进网页"的分支。
					val hasEasCred = runCatching {
						com.qust.helper.data.Setting.getString(com.qust.helper.data.Keys.EAS_ACCOUNT).isNotEmpty() &&
							com.qust.helper.ui.page.account.AccountManagerPage.hasSavedPassword(com.qust.helper.data.Keys.EAS_PASSWORD)
					}.getOrDefault(false)
					if(!hasEasCred) toastWarning("请先登录教务系统")
					needLogin = true
					onNeedLogin()
				}catch(e: Exception){
					Logger.e(e)
					// 【第三十二轮】教务没登录时, 服务器会返回一个**登录网页**(HTML), 解析 JSON 直接失败,
					// 于是以前会把 Gson 的英文原文("Value <!DOCTYPE of type java.lang.String cannot be
					// converted to JSONObject…")当气泡弹给用户 —— 完全看不懂。
					// 这类"拿到网页而不是数据"的失败一律按"未登录教务系统"处理:
					// 气泡「请先登录教务系统」+ 弹「教务登陆」界面, 与用户要求一致。
					if(isLoginPageError(e)) {
						// 【第六十七轮】"接口返回了登录网页" = 只是没有会话(不是教务坏了),
						// 顺手把失败原因记成"没有凭据" —— 否则界面上那条 autoLoginEas 分支
						// 会读到上一次遗留的"外部原因", 做出错误判断。
						com.qust.helper.model.account.EasLoginState.mark(
							com.qust.helper.model.account.EasLoginState.Reason.NO_CREDENTIAL,
							"接口返回登录页"
						)
						// 【第七十七轮】同上面那条: 存过教务凭据就不再弹"请先登录"(见该处说明)
						val hasEasCred = runCatching {
							com.qust.helper.data.Setting.getString(com.qust.helper.data.Keys.EAS_ACCOUNT).isNotEmpty() &&
								com.qust.helper.ui.page.account.AccountManagerPage.hasSavedPassword(com.qust.helper.data.Keys.EAS_PASSWORD)
						}.getOrDefault(false)
						if(!hasEasCred) toastWarning("请先登录教务系统")
						needLogin = true
						onNeedLogin()
					}else{
						onError(e)
					}
				}finally {
					final()
				}
			}
		}
	}

	/** 本页最后一次查询的入参(见 [request] 里的说明) */
	private var lastRequest: (suspend EASAccount.() -> Unit)? = null
	private var lastFinal: (() -> Unit)? = null
	private var lastError: ((Exception) -> Unit)? = null
	private var lastNeedLogin: (() -> Unit)? = null

	/**
	 * 【第六十七轮·新增】用**已登录的数字青科**静默登录教务, 成功就把本页最后的查询重跑一遍。
	 *
	 * 为什么要放在 ViewModel 里: 登录动作要跑网络(可能好几秒), 又必须持有页面协程;
	 * 而"重跑哪一次查询"只有 ViewModel 自己知道(`lastRequest`)。
	 *
	 * 走的是 [EASAccount.login] —— 它内部已经是**两条通道**: ① 教务原网址直连(有教务凭据才试),
	 * ② 数字青科 → 业务应用 → 教务管理系统(SSO)。所以"已登录数字青科"这件事在这里就能落地,
	 * 不需要页面再去分辨走哪条。
	 *
	 * @return true = 教务已经能用了(页面不用再弹登录界面)
	 */
	open suspend fun autoLoginEas(): Boolean {
		return withContext(Dispatchers.IO) {
			val ok = try {
				easAccount.login()
			}catch(e: NeedLoginException) {
				false
			}catch(e: Exception) {
				Logger.i("自动登录教务未成功: ${e.message}")
				false
			}
			if(ok) {
				Logger.i("已登录数字青科, 自动登录教务成功")
				lastRequest?.let { b -> request(b, lastFinal ?: { }, lastError ?: { }, lastNeedLogin ?: { }) }
			}
			ok
		}
	}

	/** 是否是"拿到了登录网页/HTML 而不是接口数据"导致的解析失败(等价于没登录) */
	private fun isLoginPageError(e: Exception): Boolean {
		if(e is java.io.IOException) return false
		val msg = e.message ?: return false
		return msg.contains("<!DOCTYPE", true) ||
			msg.contains("<html", true) ||
			msg.contains("cannot be converted to", true) ||
			msg.contains("End of input at", true)
	}

}


