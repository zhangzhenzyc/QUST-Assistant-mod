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
import com.qust.helper.data.Setting
import com.qust.helper.model.Logger
import com.qust.helper.model.account.EASAccount
import com.qust.helper.model.account.IPassAccount
import com.qust.helper.model.account.ServerException
import com.qust.helper.utils.LessonTableDiag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

class IPassAccountViewModel: AccountViewModel() {

	private val ipassAccount = IPassAccount.getInstance()

	val ipassName = mutableStateOf(Setting.getString(key = Keys.IPASS_ACCOUNT))
	val ipassPassword = mutableStateOf(Setting.getString(key = Keys.IPASS_PASSWORD))

	/**
	 * 【本轮·新增】数字青科已登录, 但「教务管理系统」这条通道进不去。
	 *
	 * 登录页看到它为 true 就不再返回上一页, 而是接着打开「教务登陆」
	 * (学号 + 教务系统密码), 让用户改走教务系统原网址 —— 这就是
	 * "先试教务管理系统, 不行再试教务系统" 的落地。
	 */
	val needEasLogin = mutableStateOf(false)

	override fun login(accountStr: String, passwordStr: String, block: ()-> Unit) {
		viewModelScope.launch {
			// 【第二十七轮】登录日志: 登录页长按"确定"可查看/复制
			LessonTableDiag.clear()
			LessonTableDiag.add("==== 数字青科登录日志 ${LessonTableDiag.now()} ====")
			LessonTableDiag.add("账号=$accountStr")
			needEasLogin.value = false
			try{
				showDialog("登录中")

				val result = withContext(Dispatchers.IO){
					ipassAccount.login(accountStr, passwordStr, true)
				}
				LessonTableDiag.add("登录接口返回: ${if(result) "成功" else "失败(账号或密码错误)"}")

				if(result){
					// 【本轮·按要求】统一身份登上之后, 立刻确认"能不能进教务管理系统"(WebVPN → 教务 SSO)。
					// 能进  -> 直接回到查询页继续查询;
					// 进不去 -> 交给页面转到「教务登陆」, 用教务系统自己的账号密码走原网址直连。
					LessonTableDiag.add("确认教务管理系统(WebVPN → 教务 SSO)可达性…")
					val easOk = withContext(Dispatchers.IO){
						val enter = runCatching { EASAccount.getInstance().enterEasByVpn() }.getOrDefault(false)
						// 【第九十九轮·修"明明登不上却弹'登录成功'"】enterEasByVpn 只代表"SSO 链路走完了",
						// 教务服务端外部原因下会"假成功" —— 链路通了但教务实际用不了, 界面却弹了
						// "登录成功", 用户下次进教务又是"暂时无法登录", 两个气泡前后矛盾。
						// 这里再复核一次真实可用性(checkLogin), 复核不过一律按"进不去"处理。
						enter && runCatching { EASAccount.getInstance().checkLogin() }.getOrDefault(false)
					}
					LessonTableDiag.add("教务管理系统可达: ${if(easOk) "是" else "否 -> 转「教务登陆」"}")
					if(easOk){
						LessonTableDiag.add("登录完成: 即将返回上一页")
						block()
					}else{
						// 【第九十九轮·按要求】数字青科登上了、教务管理系统还是进不去:
						// 给气泡「暂时无法登录」; 之后的页面流转(账号管理入口回账号管理、
						// 功能页拉来的转「教务登陆」)保持原有逻辑, 不额外加任何行为。
						LessonTableDiag.add("数字青科已登录, 教务管理系统不可达: 提示「暂时无法登录」")
						toastError("暂时无法登录")
						needEasLogin.value = true
					}
				}else{
					// 能走到这里说明学校**明确拒绝**了这份凭据(见 IPassAccount.absLogin 的判定),
					// 网络/维护/被拦一律走下面的异常分支, 不会再误报成密码错误。
					toastError("账号或密码错误")
				}
			}catch(e: ServerException){
				Logger.e("LoginFail", e)
				LessonTableDiag.add("异常: ${e.message}")
				// 服务端故障 / 维护 / 暂时被拦: 不是账号密码的问题
				toastError(e.message ?: "暂时无法登录")
			}catch(e: IOException){
				Logger.e("LoginFail", e)
				LessonTableDiag.add("异常: 网络/服务不可用 ${e.message}")
				val msg = e.message ?: ""
				toastError(
					when {
						msg.contains("403") || msg.contains("没有访问权限") || msg.contains("访问被拒绝") -> "暂时没有访问权限"
						else -> "暂时无法登录"
					}
				)
			}catch(e: Exception){
				Logger.e("LoginFail", e)
				LessonTableDiag.add("异常: ${e.message}")
				toastError("暂时无法登录")
			}finally{
				clearDialog()
				LessonTableDiag.add("登录流程结束")
			}
		}
	}
}
