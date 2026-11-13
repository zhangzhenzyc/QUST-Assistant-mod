/*
 * 青科助手（修改版） —— 基于开源项目 QUST-Assistant 修改而成
 *
 * 原项目版权所有 (C) 2891954521  (https://github.com/2891954521/QUST-Assistant)
 * 原始开源许可: GNU General Public License v3.0 (GPL-3.0), 许可原文见 LICENSE 文件, 保持完整未改动。
 *
 * 修改版版权所有 (C) 2026 zhangzhenzyc  (https://github.com/zhangzhenzyc)
 * 本修改版同样以 GPL-3.0 发布; 附加版权声明见 NOTICE.md —— 两份版权声明相互独立、同时生效。
 */

package com.qust.helper.viewmodel.account

import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.viewModelScope
import com.qust.helper.data.Keys
import com.qust.helper.data.LoginEntry
import com.qust.helper.data.Setting
import com.qust.helper.data.lesson.LessonTable
import com.qust.helper.model.LessonTableRepository
import com.qust.helper.model.Logger
import com.qust.helper.model.account.EASAccount
import com.qust.helper.model.account.IPassAccount
import com.qust.helper.model.account.NeedLoginException
import com.qust.helper.model.account.NeedVpnLoginException
import com.qust.helper.model.account.ServerException
import com.qust.helper.utils.LessonTableDiag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.Calendar

class EasAccountViewModel: AccountViewModel(){

	// 【要点两次登录】必须使用 getInstance(): 查询课表走的也是这个实例,
	// 以前这里固定用另一个账号对象, 于是"登录成功的是这个、查询用的是那个",
	// 表现为必须再登录一次才行。现在这个实例同时管着两条通道(①原网址 / ②数字青科),
	// 登录与查询一定落在同一条通道上。
	private val easAccount = EASAccount.getInstance()

	/** 登录/会话相关超时与重试参数 */
	private companion object {
		// 【第四十轮】20s -> 30s: 登录顺序改为"先原网址、不行再自动走数字青科",
		// 一整条链路(直连登录 + 统一身份 + 教务 SSO)比以前多一截, 20 秒偶尔会误报超时。
		// 【第九十九轮·按要求"无需等待25秒"】30s -> 15s: 外部原因(服务挂了/网络不通)时
		// 用户不应该对着"登录中"等上二三十秒 —— 15 秒足够正常网络下走完整条链路,
		// 到点立即按"暂时无法登录"给出反馈。
		const val LOGIN_TIMEOUT_MS = 15_000L
		const val SESSION_READY_RETRIES = 3      // 初始校验 + 静默重试 2 次
		const val SESSION_READY_INTERVAL_MS = 600L
		const val VPN_TIP_DELAY_MS = 800L        // "暂时无法登录教务系统"气泡停留多久再跳页
	}

	/**
	 * 【本轮·按要求】"暂时无法登录教务系统 -> 打开「数字青科登陆」"的唯一出口。
	 *
	 * 顺序必须是「先气泡, 后界面」: 气泡是「教务登陆」页里的 Compose 组件
	 * (`toast.ToastContent`), 一旦跳到「数字青科登陆」页, 它就随原页面一起消失 ——
	 * 所以先在原页把气泡弹出来、停一小会儿让用户看清, 再置位让页面跳转。
	 * (以前两件事在同一帧做, 结果是"界面出来了、气泡没看见"。)
	 */
	private suspend fun needVpnLoginWithTip() {
		LessonTableDiag.add("提示「暂时无法登录教务系统」, ${VPN_TIP_DELAY_MS}ms 后打开「数字青科登陆」")
		clearDialog()                 // 先收起"登录中", 别和气泡叠在一起
		toastError("暂时无法登录教务系统")
		delay(VPN_TIP_DELAY_MS)
		needVpnLogin.value = true
	}

	// 【本轮】教务登陆页是**教务系统原网址的登录界面**(学号 + 教务系统密码),
	// 这一页会**回填并保存**教务系统自己的账号密码(EAS_ACCOUNT/EAS_PASSWORD);
	// 注意: 走数字青科通道时用的是统一身份凭据, 那一份由「数字青科登陆」页自己管, 不写到这里。
	//
	// 【本轮修复】初值必须在这里就取好(与 [IPassAccountViewModel] 一致):
	// 输入框内部用 remember 只认**第一次组合**时的值, 放在页面的 LaunchedEffect 里回填会晚一帧,
	// 表现为"第一次点「更换账号」看不到账号和密码, 第二次才出现"。
	val easName = mutableStateOf(Setting.getString(key = Keys.EAS_ACCOUNT))
	val easPassword = mutableStateOf(Setting.getString(key = Keys.EAS_PASSWORD))

	/**
	 * 【本轮】需要先去「数字青科登陆」
	 *
	 * 触发时机: 两个输入框都留空(表示"用现有会话进教务"), 而原网址没有活会话、数字青科也没登录过。
	 * 以前这里只在界面上弹一句"请先完成「数字青科登陆」"的气泡, 用户还得自己去找登录页;
	 * 现在改成: 教务登陆页看到它为 true 就直接跳到「数字青科登陆」页(第②条登录路径)。
	 */
	val needVpnLogin = mutableStateOf(false)

	override fun login(accountStr: String, passwordStr: String, block: ()-> Unit) {
		viewModelScope.launch {
			// 【本期·按要求】从「账号管理 → 教务系统 → 点击登录」进来的这一次登录:
			// ① 登录成功后**不自动获取课表**(避免把本地调休/调课改动覆盖掉), 需要时用户自己去「课表查询」;
			// ② 账号密码正常保存(见下): 这是**教务系统自己的**账号密码, 与数字青科的统一身份分开存。
			val fromAccountManager = com.qust.helper.data.LoginEntry.fromAccountManager
			// 【本轮】两个输入框都填了才算"用输入的凭据登录"; 只填一个按"没填"处理(不发请求, 直接提示)
			val hasInput = accountStr.isNotEmpty() && passwordStr.isNotEmpty()
			// 【第二十七轮】登录日志: 登录页长按"确定"可查看/复制(排查"要点两次确认才能进入"问题)
			LessonTableDiag.clear()
			LessonTableDiag.add("==== 教务登录日志 ${LessonTableDiag.now()} ====")
			LessonTableDiag.add("账号=${accountStr.ifEmpty { "(留空)" }} 登录路径=①教务系统原网址(进不去自动改走②数字青科) 节点=${easAccount.host} 入口=${if(fromAccountManager) "账号管理(不查课表)" else "常规"}")
			try{
				showDialog("登录中")

				val ipassSaved = IPassAccount.getInstance().getAccount()
				val ipassSavedPwd = Setting.getString(Keys.IPASS_PASSWORD)
				val canUseSavedIpass = ipassSaved.isNotEmpty() && ipassSavedPwd.isNotEmpty()

				// 【本轮·按要求】既没输入账号密码、直连也没有活会话、连数字青科都还没登录过 ->
				// 不再弹"请先完成「数字青科登陆」"的气泡, 直接把人带到「数字青科登陆」页。
				if(!hasInput && !easAccount.hasSession && !canUseSavedIpass){
					LessonTableDiag.add("无输入、无原网址会话、也没有已保存的统一身份: 转到「数字青科登陆」")
					needVpnLoginWithTip()
					return@launch
				}

				// 【超时】登录网络调用整体超时, 超时按"暂时无法登录"处理(不是密码错), 不卡死 UI
				LessonTableDiag.add("发起登录请求(整体超时 ${LOGIN_TIMEOUT_MS}ms)…")
				// 【本轮·按要求】教务系统有**两条**登录路径, 这一页是**第①条**(教务系统原网址):
				// - 填了账号密码 -> 学号 + 教务系统密码 走 jwglxt 原网址登录(RSA 加密);
				//   ①进不去(被拦 / 网络不通 / 密码不对)会自动改走②数字青科, 用户不用自己判断;
				// - 两个都留空 -> 用**现有会话**进教务(之前登录过的原网址 Cookie / 数字青科会话)。
				// 账号密码**正常保存**(saveData=true): 这是教务系统自己的账号密码,
				// 保存后账号管理里能看到学号, 下次进教务也不用再输一遍。
				var timedOut = false
				val loginOk = withTimeoutOrNull(LOGIN_TIMEOUT_MS) {
					withContext(Dispatchers.IO) {
						if(hasInput){
							LessonTableDiag.add("用输入的账号密码登录(先①教务系统原网址, 不行自动改走②数字青科)")
							easAccount.login(accountStr, passwordStr, true)
						}else{
							LessonTableDiag.add("账号密码留空: 用现有会话进入教务")
							runCatching { easAccount.checkLogin() }.getOrDefault(false)
						}
					}
				} ?: run { timedOut = true; false }
				LessonTableDiag.add("登录接口返回: ${if(loginOk) "成功(当前走线=${easAccount.channelName})" else "失败/超时"}")

				if(loginOk){
					// 【时序控制】登录成功后不要立刻认为"可用", 轻量复核会话是否真正就绪
					// (强智教务偶发"登录返回 302 但会话未生效"), 就绪才继续, 否则静默重试
					var sessionReady = false
					repeat(SESSION_READY_RETRIES) { attempt ->
						sessionReady = withContext(Dispatchers.IO) {
							// 【本轮】用 checkLogin() 判断可用性: 它会先看教务系统原网址(直连)那条路的
							// 会话还在不在, 不在再自动改走数字青科(「业务应用 → 教务管理系统」)。
							// 以前这里只看教务页探测结果(absCheckLogin), 只要探测失败就永远"未就绪",
							// 用户停在登录页且只看到一句"本学期未查询到你的课表"—— 即"登录后也不行"。
							runCatching { easAccount.checkLogin() }.getOrDefault(false)
						}
						LessonTableDiag.add("会话校验#${attempt + 1}: ${if(sessionReady) "已就绪" else "未就绪"}")
						if(sessionReady) return@repeat
						if(attempt < SESSION_READY_RETRIES - 1) withContext(Dispatchers.IO) { delay(SESSION_READY_INTERVAL_MS) }
					}

					if(sessionReady){
						// 【本轮·修复】账号密码允许留空(用现有会话进教务), 这时不能直接拿 accountStr
						// 的前两位算入学年份 —— 空串取前两位会直接越界崩溃。留空时退回已保存的统一身份账号。
						val accForYear = accountStr.ifEmpty { ipassSaved }
						val currentYear = if(accForYear.length >= 2) {
							val year = Calendar.getInstance()[Calendar.YEAR].toString()
							(year.substring(0, year.length - 2) + accForYear.substring(0, 2)).toInt()
						}else easAccount.entranceTime
						if(currentYear > 0) easAccount.entranceTime = currentYear
						LessonTableDiag.add("入学年份=$currentYear")
						// 【第四十轮·按要求】登录成功后**不再自动查课表** —— 自动查课表只发生在
						// "刚进入软件"(欢迎页)那条路上: 欢迎页登录成功回到 GuideActivity 后,
						// 那边本来就会进教务并查询课表, 这里再查一次纯属重复(还会把加载时间翻倍)。
						// 其余所有入口(账号管理 / 教务网页 / 选课网页 / 各功能页)登录完只建立登录态,
						// 课表保持用户本地那份(含调休/调课改动), 需要更新去「课表查询」手动查。
						// 唯一要做的收尾是"换了账号就清旧账号缓存"(也只在欢迎页那条路上做,
						// 因为紧接着就有一次查询把新课表填回来; 其它入口清了没人补, 课表会变空白)。
						if(!fromAccountManager && LoginEntry.fromAppEntry){
							// 【登录成功清旧账号缓存】避免串号/旧数据残留
							clearOldAccountCache()
							LessonTableDiag.add("刚进入软件: 已清旧账号缓存, 课表由欢迎页接着查询")
						}else{
							LessonTableDiag.add("跳过自动获取课表(保留现有课表)")
						}
						LessonTableDiag.add("登录完成: 即将返回上一页(入学年份=$currentYear)")
						block()
					}else{
						// 会话始终未就绪: 多半是教务服务端问题
						LessonTableDiag.add("会话始终未就绪(3次), 不返回上一页")
						toastWarning("本学期未查询到你的课表")
					}
				}else{
					// 【本轮·按要求】这里要分清两种完全不同的失败:
					// - 超时 = 学校服务/网络长时间没响应 -> 「暂时无法登录」;
					// - 学校明确拒绝了这份凭据        -> 「账号或密码错误」。
					// 以前一律报"用户名或密码错误", 教务维护时用户会以为是自己改密码了。
					LessonTableDiag.add("登录失败: ${if(timedOut) "超时(按暂时无法登录处理)" else "账号或密码错误"}")
					when {
						timedOut -> toastError("暂时无法登录")
						hasInput -> toastError("账号或密码错误")
						else -> needVpnLoginWithTip()
					}
				}
			}catch(_: NeedVpnLoginException){
				// 【本轮·按要求】教务系统原网址这条路进不去时, **不再拿教务密码去试统一身份**
				// (两套密码不同, 必然错, 多试还可能锁号)。改为:
				//   ① 弹气泡「暂时无法登录教务系统」;
				//   ② 打开「数字青科登陆」页, 由用户输入统一身份账号密码;
				//   ③ 那边登录成功后会自己走「业务应用 → 教务管理系统」, 也就是"再尝试自动登录"。
				LessonTableDiag.add("异常: 教务原网址未登录成功, 转「数字青科登陆」由用户输入统一身份凭据")
				needVpnLoginWithTip()
			}catch(_: NeedLoginException){
				// 【本轮·按要求】不再提示"请先完成「数字青科登陆」":
				// ①原网址 + ②数字青科 两条通道都没能登录成功 —— 输过账号密码就是账号密码不对;
				// 没输过才把人带到「数字青科登陆」页。
				LessonTableDiag.add("异常: 两条通道均未登录成功(NeedLogin), 输入=${if(hasInput) "有" else "无"}")
				if(hasInput) toastError("账号或密码错误") else needVpnLoginWithTip()
			}catch(e: ServerException){
				// 教务服务端故障(5xx/维护) / 无访问权限(403) / 暂时进不去: 都不是账号密码问题
				Logger.e("LoginFail", e)
				LessonTableDiag.add("异常: 教务服务端 ${e.message}")
				toastError(e.message ?: "暂时无法登录")
			}catch(e: IOException){
				Logger.e("LoginFail", e)
				LessonTableDiag.add("异常: 网络/服务不可用 ${e.message}")
				// 【本轮·按要求】只有被校方拦下(403)才说"没有访问权限", 其余一律"暂时无法登录",
				// 不再报成"密码错误", 也不再报成生硬的技术错误。
				toastError(if(isForbidden(e)) "暂时没有访问权限" else "暂时无法登录")
			}catch(e: Exception){
				Logger.e("LoginFail", e)
				LessonTableDiag.add("异常: ${e.message}")
				toastError(classifyEasError(e))
			}finally{
				clearDialog()
				LessonTableDiag.add("登录流程结束")
			}
		}
	}

	/**
	 * 【本期】是否为"没有访问权限"的失败(教务/数字青科返回 HTTP 403)。
	 *
	 * 校方对直连教务或异常来源会返回 403, 这种情况既不是账号密码错误也不是本机断网,
	 * 统一提示「暂时没有访问权限」。
	 */
	private fun isForbidden(e: Throwable): Boolean {
		val msg = e.message ?: return false
		return msg.contains("403") || msg.contains("没有访问权限") || msg.contains("访问被拒绝")
	}

	/**
	 * 把捕获到的异常转成用户能看懂的教务错误提示
	 *
	 * 【本轮·按要求】只有两种口径:
	 * - 校方拦下(403)      -> 「暂时没有访问权限」;
	 * - 其余一律           -> 「暂时无法登录」。
	 * 不再把"访问教务失败，原因：xxx"这种技术文案抛给用户, 更不能误报成账号密码错误。
	 */
	private fun classifyEasError(e: Throwable): String {
		return if(isForbidden(e)) "暂时没有访问权限" else "暂时无法登录"
	}

	/**
	 * 切换账号登录成功后, 清空上一个账号的课表/学业缓存, 避免串号
	 */
	private fun clearOldAccountCache(){
		// 学业: 重置缓存版本, 进页面时强制重新查询(顺带清掉旧账号的 Room 数据)
		Setting.edit { it.putInt(Keys.ACADEMIC_CACHE_VERSION, 0) }
		// 课表: 清空内存表 + 删除旧账号保存的课表文件
		LessonTableRepository._lessonTable.value = LessonTable()
		LessonTableRepository.clearSavedLessonTableForNewAccount()
	}
}