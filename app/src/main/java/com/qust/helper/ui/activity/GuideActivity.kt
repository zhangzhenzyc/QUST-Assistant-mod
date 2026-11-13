/*
 * 青科助手（修改版） —— 基于开源项目 QUST-Assistant 修改而成
 *
 * 原项目版权所有 (C) 2891954521  (https://github.com/2891954521/QUST-Assistant)
 * 原始开源许可: GNU General Public License v3.0 (GPL-3.0), 许可原文见 LICENSE 文件, 保持完整未改动。
 *
 * 修改版版权所有 (C) 2026 zhangzhenzyc  (https://github.com/zhangzhenzyc)
 * 本修改版同样以 GPL-3.0 发布; 附加版权声明见 NOTICE.md —— 两份版权声明相互独立、同时生效。
 */

package com.qust.helper.ui.activity

import androidx.activity.compose.BackHandler
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.qust.helper.R
import com.qust.helper.data.Keys
import com.qust.helper.data.Setting
import com.qust.helper.model.LessonTableRepository
import com.qust.helper.model.Logger
import com.qust.helper.model.account.DirectEASAccount
import com.qust.helper.model.account.EASAccount
import com.qust.helper.model.account.IPassAccount
import com.qust.helper.model.account.NeedVpnLoginException
import com.qust.helper.ui.page.account.LoginPage
import com.qust.helper.ui.theme.colorSecondaryText
import com.qust.helper.ui.widget.AppWidgets
import com.qust.helper.utils.LessonTableDiag
import com.qust.helper.viewmodel.account.AccountViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.Bundle
import java.io.IOException
import java.util.Calendar

/** 【第三十轮】登录页条款链接的蓝色(与其他应用常见的协议链接蓝一致) */
private val LINK_BLUE = Color(0xFF1677FF)

class GuideActivity : BaseActivity() {

	private val viewModel by viewModels<GuideViewModel>()

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		// 【修改版】进入引导页申请通知权限(等首帧绘制完成再申请, 保证系统弹窗会出现)
		window.decorView.post { com.qust.helper.utils.Notices.requestPermission(this, 40210) }
	}

	@Composable
	override fun Content() {
		// 【本期】按要求去掉进入动画: 本页直接完整显示(以前会先整体下沉 100dp + 半透明, 300ms 后才归位)
		Column(
			modifier = Modifier.statusBarsPadding().fillMaxSize().padding(horizontal = 16.dp),
			verticalArrangement = Arrangement.Top,
			horizontalAlignment = Alignment.CenterHorizontally
		) {

			Row(modifier = Modifier.padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
				Text(
					text = stringResource(R.string.text_welcome),
					modifier = Modifier.weight(1f),
					style = MaterialTheme.typography.titleLarge,
					color = MaterialTheme.colorScheme.primary,
					textAlign = TextAlign.Start,
				)
				// 【第二十八轮】按要求删除"跳过"按钮: 首次下载必须登录成功才能进入软件
			}

			Text(text = "欢迎使用青科助手，我们需要以下信息以便使用软件完整功能")

			val context = LocalContext.current
			val keyboardController = LocalSoftwareKeyboardController.current

			fun login(){
				keyboardController?.hide()
				viewModel.login(viewModel.account.value, viewModel.password.value){ loginSuccess() }
			}

			// 【第四十一轮·按要求改顺序】第一次下载软件后, 第一个登录界面是**教务系统**(学号 + 教务系统密码);
			// 教务系统无法正常登录时, 给出气泡提示并自动转到「数字青科」登录界面(见 GuideViewModel.switchToVpn)。
			Text(
				text = if(viewModel.vpnMode) "请使用数字青科账号登录" else "请使用教务系统账号登录",
				modifier = Modifier.fillMaxWidth().padding(top = 8.dp, start = 4.dp),
				style = MaterialTheme.typography.bodyMedium,
				textAlign = TextAlign.Start
			)
			Text(
				text = if(viewModel.vpnMode) "登录后将自动进入教务系统并获取课表"
					else "登录后将获取课表；教务系统登录不上时，会自动转到数字青科登录",
				modifier = Modifier.fillMaxWidth().padding(top = 2.dp, start = 4.dp),
				style = MaterialTheme.typography.bodySmall,
				color = colorSecondaryText,
				textAlign = TextAlign.Start
			)

			LoginPage.AccountInput(
				account = viewModel.account,
				password = viewModel.password,
				accountError = viewModel.errorText.value.ifEmpty { viewModel.accountError },
				passwordError = viewModel.passwordError,
				"学号", if(viewModel.vpnMode) "数字青科密码" else "教务系统密码",
				login = { login() }
			)

			// 【第四十一轮】已切到数字青科登录后, 保留一个改回「教务系统登录」的入口
			if(viewModel.vpnMode){
				TextButton(
					modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
					onClick = { viewModel.vpnMode = false; viewModel.errorText.value = "" }
				) {
					Text(text = "改用教务系统登录")
				}
			}

		// 【第三十轮】一句话条款: 《免责声明》《用户协议》《隐私政策》为蓝色可点击文字, 分别点击进入对应全文;
		// 上方的两个条文卡片已按要求取消
		// 【第一百零一轮·按要求】按原版软件新增《用户协议》, 排在《免责声明》之后
		val termsText = buildAnnotatedString {
			append("我已阅读并同意")
			pushStringAnnotation(tag = "link", annotation = Keys.Page.DisclaimerPage)
			withStyle(SpanStyle(color = LINK_BLUE)) { append("《免责声明》") }
			pop()
			pushStringAnnotation(tag = "link", annotation = Keys.Page.UserAgreementPage)
			withStyle(SpanStyle(color = LINK_BLUE)) { append("《用户协议》") }
			pop()
			append("与")
			pushStringAnnotation(tag = "link", annotation = Keys.Page.PolicyPage)
			withStyle(SpanStyle(color = LINK_BLUE)) { append("《隐私政策》") }
			pop()
		}
		Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
			Checkbox(checked = viewModel.agreeTerms, onCheckedChange = { viewModel.agreeTerms = it })
			ClickableText(
				text = termsText,
				style = MaterialTheme.typography.bodySmall.copy(color = colorSecondaryText),
				modifier = Modifier.weight(1f),
				onClick = { offset ->
					termsText.getStringAnnotations(tag = "link", start = offset, end = offset).firstOrNull()?.let { ann ->
						ComposeActivity.startActivity(context, ann.item)
					(context as? android.app.Activity)?.overridePendingTransition(0, 0)
					}
				}
			)
		}

		Button(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), onClick = { login() }){
				Text(text = stringResource(id = R.string.text_ok))
			}
		}

		// 【第二十八轮】首次下载: 不允许跳过登录 —— 返回键直接退出软件
		// 【第二十九轮】登录失败/课表获取失败不再退出软件, 停留本页可直接重试
		BackHandler { exitApp() }

		AppWidgets.DialogBar(dialogText = viewModel.dialogText)
		toast.ToastContent(viewModel.toastContent)
	}

	/** 【第二十八轮】首次下载未登录成功时退出软件(连同其下的主界面一起关闭) */
	private fun exitApp() {
		finishAffinity()
	}

	private fun loginSuccess() {
		Setting.edit { it.putBoolean(Keys.IS_FIRST_USE, false) }
		// 【第二十九轮·修复】不能再用 onBackPressedDispatcher.onBackPressed(): 页面里的 BackHandler
		// (返回键=退出软件)会把它拦截掉, 结果"登录成功"也会触发退出软件。直接 finish 本页,
		// 回到其下的主界面即可(GuideActivity 是盖在 MainActivity 上启动的)。
		finish()
	}

	class GuideViewModel: AccountViewModel(){

		private val easAccount = EASAccount.getInstance()
		private val ipassAccount = IPassAccount.getInstance()

		var account = mutableStateOf("")
		var password = mutableStateOf("")
		var accountError by mutableStateOf("")
		var passwordError by mutableStateOf("")
		/** 【第二十八轮】免责声明与隐私政策合并为一个勾选(一句话); 【第一百零一轮】新增用户协议 */
		var agreeTerms by mutableStateOf(false)

		/**
		 * 【第四十一轮·按要求】当前登录界面:
		 * `false` = **教务系统**(首启默认, 学号 + 教务系统密码);
		 * `true`  = 数字青科(教务系统登录不上时, 气泡提示后自动转来, 输统一身份账号密码)。
		 */
		var vpnMode by mutableStateOf(false)

		/**
		 * 【第四十一轮·按要求】教务系统登录不上 -> 气泡提示 -> 转到「数字青科」登录界面。
		 * 转过去后用户输统一身份账号密码, 登录成功即自动进入教务(经「业务应用 → 教务系统」)并查询课表。
		 */
		private fun switchToVpn(reason: String) {
			toastWarning(reason)
			vpnMode = true
			errorText.value = ""
			password.value = ""
		}

		override fun login(accountStr: String, passwordStr: String, block: () -> Unit) {
			if(account.value.isEmpty()) { accountError = "请输入学号"; return }
			else if(account.value.length < 2){ accountError = "学号格式错误"; return  }
			else accountError = ""
			if(password.value.isEmpty()) { passwordError = "请输入密码"; return }
			else passwordError = ""
			if(!agreeTerms){
				// 【第一百零一轮】条款新增《用户协议》
				toastWarning("请先阅读并同意《免责声明》《用户协议》与《隐私政策》")
				return
			}
			viewModelScope.launch {
				errorText.value = ""
				try{
					if(!vpnMode){
						// ---- ① 教务系统(首启默认): 学号 + 教务系统密码, 直连教务原网址 ----
						// 【第四十三轮·按要求】凭据错误与网络失败分开处理:
						//   教务系统明确拒绝凭据(login 返回 false) -> 气泡「账号或密码错误」, **不跳转**;
						//   网络不通/服务故障/被拦(抛异常) -> 气泡提示后转到数字青科登录界面。
						showDialog("正在登录教务系统")
						val directOk = withContext(Dispatchers.IO){
							DirectEASAccount.getInstance().login(accountStr, passwordStr, true)
						}
						if(!directOk){
							// 【第四十四轮·按要求】凭据错误只给气泡, 输入框不变红、不显示红色文字
							toastWarning("账号或密码错误")
							return@launch
						}
						// 直连登录成功: 会话 Cookie 已写入直连通道, 下面的 checkLogin 会自动识别并切到直连
						// 教务登录成功 -> 查询课表(用学校当前学期) -> 成功才进入软件
						val entranceTime = currentEntranceTime(accountStr)
						easAccount.entranceTime = entranceTime
						if(queryLesson(entranceTime, easAccount.getCurrentGrade())) block()
						// 查询失败时 queryLesson 内部已提示, 留在本页可再点"确定"重试
					}else{
						// ---- ② 数字青科(教务转来): 统一身份账号密码 -> 自动进入教务 -> 查询课表 ----
						showDialog("登录中")
						val vpnOk = withContext(Dispatchers.IO){
							ipassAccount.login(accountStr, passwordStr, true)
						}
						if(!vpnOk){
							// 【第四十四轮·按要求】凭据错误只给气泡, 不再显示红色文字
							toastWarning("账号或密码错误")
							return@launch
						}
						val entranceTime = currentEntranceTime(accountStr)
						easAccount.entranceTime = entranceTime
						if(queryLesson(entranceTime, easAccount.getCurrentGrade())) block()
						else toastWarning("无法进入教务系统，可尝试改用教务系统登录")
					}
				}catch(e: NeedVpnLoginException){
					// 【第四十一轮·按要求】教务系统无法正常登录(直连不通、统一身份也没登上):
					// 气泡提示 -> 跳到数字青科登录界面。
					Logger.e("LoginFail", e)
					switchToVpn("暂时无法登录教务系统，请使用数字青科账号登录")
				}catch(e: IOException){
					Logger.e("LoginFail", e)
					// 非凭据类失败(403/维护/超时)按既有口径提示; 教务模式下同样转到数字青科再试一次
					if(vpnMode){
						toastWarning(if(isForbidden(e)) "暂时没有访问权限" else "暂时无法登录，请检查网络后重试")
					}else{
						switchToVpn(if(isForbidden(e)) "暂时没有访问权限，请使用数字青科账号登录" else "暂时无法登录教务系统，请使用数字青科账号登录")
					}
				}catch(e: Exception){
					// 兜底: 不让用户卡死在欢迎页
					Logger.e("LoginFail", e)
					if(vpnMode){
						toastWarning("暂时无法登录，请检查网络后重试")
					}else{
						switchToVpn("暂时无法登录教务系统，请使用数字青科账号登录")
					}
				}finally{
					clearDialog()
				}
			}
		}

		/** 按学号前两位推算入学年份(2026 年 25 级 -> 2025) */
		private fun currentEntranceTime(accountStr: String): Int {
			val year = Calendar.getInstance()[Calendar.YEAR].toString()
			return (year.substring(0, year.length - 2) + accountStr.substring(0, 2)).toInt()
		}

		/** 是否为"没有访问权限"(HTTP 403)导致的失败 */
		private fun isForbidden(e: Throwable): Boolean {
			val msg = e.message ?: return false
			return msg.contains("403") || msg.contains("没有访问权限") || msg.contains("访问被拒绝")
		}

		/**
		 * 登录成功后查询并保存课表
		 *
		 * 【第二十九轮】按"学校当前学期"查询: useSchoolCurrentTerm=true 时由接口返回的学年信息
		 * (形如"2026-2027学年1学期")解析真实学年/学期, 不再依赖本地推算的固定值。
		 *
		 * 【第三十一轮】"确保课表正常加载后再进入软件": 查询成功但**内容为空**也视为未加载,
		 * 不进入软件; 真正就绪(取到课程 -> 保存 -> 刷新课表状态/周次)才返回 true。
		 *
		 * @return 课表是否已正常加载(失败时已 toast 提示)
		 */
		suspend fun queryLesson(entranceTime: Int, index: Int): Boolean {
			showDialog("正在查询课表")
			var ok = false
			withContext(Dispatchers.IO){
				// 登录校验失败时不能让引导页崩溃, 交给下面的查询流程统一报"请先登录"
				try { easAccount.checkLogin() } catch(_: Exception) { }

				val result = LessonTableRepository.queryLessonTable(
					easAccount = easAccount,
					year = (index / 2 + entranceTime).toString(),
					term = if(index % 2 == 0) "3" else "12",
					useSchoolCurrentTerm = true
				)
				val error = result.error
				if(error == null) {
					// 【第三十一轮】空课表视为未正常加载: 不进入软件, 提示后留在本页可重试
					val slotCount = result.lessonTable.activeSlotCount()
					if(slotCount <= 0){
						LessonTableDiag.add("[界面] 查询到空课表, 不进入软件")
						toastError("未获取到课表内容，请稍后重试")
					}else if(LessonTableRepository.saveLessonTable(result.lessonTable)){
						// 保存成功后立刻刷新课表状态与周次, 保证进入主界面时课表已经完全就绪
						LessonTableRepository.notifyLessonTableChanged()
						LessonTableRepository.updateDate()
						LessonTableDiag.add("[界面] 课表已就绪($slotCount 个有课时段), 进入软件")
						ok = true
					}else{
						toastError("保存课表失败")
					}
				}else{
					toastError(error)
				}
			}
			clearDialog()
			return ok
		}
	}
}

