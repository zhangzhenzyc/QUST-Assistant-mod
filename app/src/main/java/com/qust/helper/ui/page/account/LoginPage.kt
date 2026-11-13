/*
 * 青科助手（修改版） —— 基于开源项目 QUST-Assistant 修改而成
 *
 * 原项目版权所有 (C) 2891954521  (https://github.com/2891954521/QUST-Assistant)
 * 原始开源许可: GNU General Public License v3.0 (GPL-3.0), 许可原文见 LICENSE 文件, 保持完整未改动。
 *
 * 修改版版权所有 (C) 2026 zhangzhenzyc  (https://github.com/zhangzhenzyc)
 * 本修改版同样以 GPL-3.0 发布; 附加版权声明见 NOTICE.md —— 两份版权声明相互独立、同时生效。
 */

package com.qust.helper.ui.page.account

import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.qust.helper.R
import com.qust.helper.data.Keys
import com.qust.helper.data.LoginEntry
import com.qust.helper.data.Page
import com.qust.helper.data.PwdCrypto
import kotlinx.coroutines.launch
import com.qust.helper.data.Setting
import com.qust.helper.ui.page.web.WebPage.EasWebViewModel
import com.qust.helper.ui.page.web.WebPage.IpassWebViewModel
import com.qust.helper.ui.page.web.WebPage.MailWebViewModel
import com.qust.helper.ui.page.web.WebPage.SeatWebViewModel
import com.qust.helper.ui.page.web.WebPage.TiyuWebViewModel
import com.qust.helper.ui.page.web.WebPage.XsxkWebViewModel
import com.qust.helper.ui.widget.AppWidgets
import com.qust.helper.ui.widget.Toast
import com.qust.helper.viewmodel.account.AccountViewModel
import com.qust.helper.viewmodel.account.EasAccountViewModel
import com.qust.helper.viewmodel.account.IPassAccountViewModel

object LoginPage {

	val EasLoginPage = Page(Keys.Page.EasLogin, "教务登陆", iconRes = R.drawable.ic_login) { activity, padding, navController, _ ->
		val viewModel by activity.viewModels<EasAccountViewModel>()
		// 【本期】"从账号管理进来的"标记只在停留本页期间有效: 离开本页就复位,
		// 避免之后从别处进入教务登录页时被误判成"账号管理入口"(那会跳过保存与课表刷新)。
		// 【第四十轮】同时记下"是否由欢迎页(刚进入软件)打开": 只有这条路径登录后才会自动查课表。
		DisposableEffect(Unit) {
			LoginEntry.fromAppEntry = activity.intent.getBooleanExtra("finishOnLogin", false)
			onDispose {
				LoginEntry.fromAccountManager = false
				LoginEntry.fromVpnLogin = false
				LoginEntry.fromAppEntry = false
			}
		}
		EASLoginPage(padding, viewModel, activity.toast, onSuccess = {
			// 【第三十二轮·按要求】从「账号管理」进来的这一次登录: 只是给教务系统留一份可用的登录态,
			// 不做任何"顺手"的事 ——
			//   ① 不重置/不重载网页页的自动登录标记(以前无条件 `hasCheck=false; reloadNonce++`,
			//      返回后网页页会立刻重新加载并自动登录, 用户看到的就是"登录完自己跳到教务系统网页");
			//   ② 不拉课表(见 EasAccountViewModel.login 里 fromAccountManager 分支);
			//   ③ 只回退到「账号管理」。
			val fromAccountManager = LoginEntry.fromAccountManager
			if(!fromAccountManager) {
				// 【登录逻辑】其它入口(教务查询/网页页等): 登录成功才清"从登录页返回"标记,
				// 返回网页页后照常执行原有自动登录
				try {
					activity.viewModels<EasWebViewModel>().value.leavingForLogin = false
					activity.viewModels<XsxkWebViewModel>().value.leavingForLogin = false
					// 【修改版】重置“已检查”标记: 返回网页后立即执行自动登录(不再需要手动刷新)
					activity.viewModels<EasWebViewModel>().value.hasCheck = false
					activity.viewModels<XsxkWebViewModel>().value.hasCheck = false
					activity.viewModels<EasWebViewModel>().value.reloadNonce++
					activity.viewModels<XsxkWebViewModel>().value.reloadNonce++
				}catch(_: Exception){ }
			}
			// 【安全改造】登录成功立即清空内存中的明文密码(下次打开登录页时按需重新解密)
			viewModel.easPassword.value = ""
			AccountManagerPage.notifyAccountChanged()
			// 【第三十四轮·按要求】登录成功: 记一笔, 回到「账号管理」时弹"登录成功"气泡
			AccountManagerPage.notifyLoginSuccess()
			if(fromAccountManager) {
				// 【第三十二轮·按要求】回到「账号管理」本身: 即使回退栈中间夹了别的页面(网页页/查询页),
				// 也直接退到账号管理, 不会停在"教务系统网页"上。
				val back = try {
					navController.popBackStack(Keys.Page.AccountManager, inclusive = false)
				}catch(_: Exception){ false }
				if(!back && navController.currentDestination?.route == Keys.Page.EasLogin) navController.popBackStack()
			}
			// 【登录逻辑】用户在登录过程中已经返回时, 不再多弹一层页面
			else if(navController.currentDestination?.route == Keys.Page.EasLogin) navController.popBackStack()
			// 【本期】欢迎页打开本页时(单页宿主, 回退栈只有一页): 登录成功即关闭本页,
			// 由欢迎页接着进入教务管理系统并查询课表(与「数字青科登陆」页同一套处理)。
			if(activity.intent.getBooleanExtra("finishOnLogin", false)) {
				activity.finish()
				activity.overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
			}
		}, onNeedVpnLogin = {
			// 【本轮】两个输入框都留空、且原网址没有活会话时才走到这里:
			// 这时去「数字青科登陆」页(第②条登录路径), 不再只弹一句气泡让用户自己找入口。
			//
			// 【本轮·防回环】但如果本页**就是从**「数字青科登陆」转过来的
			// (数字青科登上了、教务管理系统进不去), 就绝不能再把人送回去, 否则两页互相弹。
			if(LoginEntry.fromVpnLogin){
				activity.toast.toastWarning("请在上方输入教务系统学号与密码")
			}else if(navController.currentDestination?.route == Keys.Page.EasLogin){
				Page.navigate(navController, Keys.Page.VpnLoginPage)
			}
		})
	}

	val VpnLoginPage = Page(Keys.Page.VpnLoginPage, "数字青科登陆", iconRes = R.drawable.ic_login) { activity, padding, navController, _ ->
		val viewModel by activity.viewModels<IPassAccountViewModel>()
		// 【本轮】"这次是被功能页面拉来登录的"标记只在停留本页期间有效, 离开即复位,
		// 免得之后从「账号管理」进来登录时被误判成"登录完还要接着弹教务登录页"。
		DisposableEffect(Unit) {
			onDispose { LoginEntry.autoFallbackToEas = false }
		}
		IPassLoginPage(padding, viewModel, activity.toast,
			onSuccess = {
			// 【登录逻辑】登录成功才清"从登录页返回"标记, 返回网页页后照常执行原有自动登录
			try {
				activity.viewModels<IpassWebViewModel>().value.leavingForLogin = false
				activity.viewModels<TiyuWebViewModel>().value.leavingForLogin = false
				activity.viewModels<SeatWebViewModel>().value.leavingForLogin = false
				// 启用 WebVPN 时教务入口也会跳到本登录页, 同样要清教务侧的返回标记
				activity.viewModels<EasWebViewModel>().value.leavingForLogin = false
				activity.viewModels<XsxkWebViewModel>().value.leavingForLogin = false
			}catch(_: Exception){ }
			// 【安全改造】登录成功立即清空内存中的明文密码(下次打开登录页时按需重新解密)
			viewModel.ipassPassword.value = ""
			AccountManagerPage.notifyAccountChanged()
			// 【第三十四轮·按要求】登录成功: 记一笔, 回到「账号管理」时弹"登录成功"气泡
			AccountManagerPage.notifyLoginSuccess()
			// 【第三十一轮·按要求】"数字青科"栏目的功能页在没登录时是**直接改道**到本页的
			// (见 Page.resolve), 登录成功就直接进用户本来想去的那个功能页 ——
			// 把本页从回退栈里换掉, 返回键回到主界面, 而不是又退到一个什么都没打开的地方。
			val pending = com.qust.helper.data.LoginEntry.pendingVpnPage
			if(pending != null) {
				com.qust.helper.data.LoginEntry.pendingVpnPage = null
				if(navController.currentDestination?.route == Keys.Page.VpnLoginPage) {
					navController.navigate(pending) {
						popUpTo(Keys.Page.VpnLoginPage) { inclusive = true }
						launchSingleTop = true
					}
				}
			}
			// 【登录逻辑】用户在登录过程中已经返回时, 不再多弹一层页面
			else if(navController.currentDestination?.route == Keys.Page.VpnLoginPage) navController.popBackStack()
			// 【本期】首次登录兜底: 本页是从欢迎页打开的话, 登录成功即关闭, 由欢迎页接着查课表
			if(activity.intent.getBooleanExtra("finishOnLogin", false)) {
				activity.finish()
				// 与进入时相反: 登录页向右退出, 欢迎页从左侧回到原位
				activity.overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
			}
		}, onNeedEasLogin = {
			// 【本轮·按要求】数字青科已经登上, 但「教务管理系统」这条通道进不去(SSO/权限/维护):
			// 不再原地打转, 接着打开「教务登陆」(学号 + 教务系统密码), 改走教务系统原网址。
			//
			// 【本轮·修正】但**只在"被功能页面拉来登录"时**才这样做(autoFallbackToEas)。
			// 用户自己在「账号管理」里登录数字青科, 登完就该停在账号管理 ——
			// 以前不区分入口, 于是"账号管理里刚登录完, 紧接着蹦出教务系统的登录界面"。
			AccountManagerPage.notifyAccountChanged()
			// 账号管理/其它入口: 数字青科登录成功就够了, 不自动跳教务登录页
			if(LoginEntry.autoFallbackToEas){
				// 用 popUpTo 把本页从回退栈里替换掉, 返回时直接回到原来的查询页。
				LoginEntry.fromVpnLogin = true
				if(navController.currentDestination?.route == Keys.Page.VpnLoginPage){
					navController.navigate(Keys.Page.EasLogin){
						popUpTo(Keys.Page.VpnLoginPage){ inclusive = true }
						launchSingleTop = true
					}
				}
			}else if(navController.currentDestination?.route == Keys.Page.VpnLoginPage){
				// 【本轮·按要求】账号管理入口: 统一身份已经登上了, 教务管理系统那条路通不通是另一回事 ——
				// 账号该存就存、该显示"更换账号/退出登录"就显示。这里直接退回账号管理,
				// 不要让用户对着一个"已经登录成功"的登录页干等(以前 easOk=false 时连返回都不会自动做)。
				navController.popBackStack()
			}
		})
	}

	/**
	 * 【第三十六轮】学校邮箱登陆页: **独立邮箱账号**(不与教务/统一身份共用)。
	 * 输入并保存后返回邮箱页, 由 WebView 自动填充完成登录; 保存即成功, 不做退出软件等处理。
	 */
	val MailLoginPage = Page(Keys.Page.MailLogin, "学校邮箱登陆", iconRes = R.drawable.ic_login) { activity, padding, navController, _ ->
		val toast = activity.toast
		val scope = androidx.compose.runtime.rememberCoroutineScope()
		val account = remember { mutableStateOf(Setting.getString(Keys.MAIL_ACCOUNT)) }
		val password = remember { mutableStateOf(Setting.getString(Keys.MAIL_PASSWORD)) }
		var accountError by remember { mutableStateOf("") }
		var passwordError by remember { mutableStateOf("") }
		/** 【第三十二轮】后台登录进行中: 挡住重复点击并显示"登录中" */
		var busy by remember { mutableStateOf(false) }

		// 【第三十三轮】"从账号管理进来的"标记只在停留本页期间有效: 离开本页(含直接返回)就复位,
		// 避免之后从「其他 → 学校邮箱」进来时被误判成账号管理入口(那样会存完就退出, 不进邮箱网页)。
		DisposableEffect(Unit) {
			onDispose { LoginEntry.mailFromAccountManager = false }
		}

		/** 登录成功(或已登录过)后的收尾: 存账号密码 -> 通知刷新 -> 退出本页 */
		fun saveAndExit(accountStr: String, pwd: String){
			// 学校邮箱为独立账号(不与教务/统一身份共用); 密码加密落盘
			Setting.edit {
				it.putString(Keys.MAIL_ACCOUNT, accountStr)
				it.putString(Keys.MAIL_PASSWORD, PwdCrypto.encrypt(pwd))
			}
			// 【第四十四轮】复位邮箱页的校验标记: 返回「学校邮箱」页时立即重新执行自动登录并进入
			val vm = activity.viewModels<MailWebViewModel>().value
			vm.hasCheck = false
			vm.leavingForLogin = false
			AccountManagerPage.notifyAccountChanged()

			// 【第三十三轮·按要求】从「账号管理 → 学校邮箱 → 点击登录」进来的:
			// 只保存账号密码, **不进学校邮箱网页**, 直接退回账号管理页。
			// (用户原话: "不要进入学校邮箱网页，保存好账号和密码后直接退出就行";
			//  只有从「其他 → 学校邮箱」进来的才继续回到邮箱网页由 WebView 自动登录)
			if(LoginEntry.mailFromAccountManager){
				LoginEntry.mailFromAccountManager = false
				val back = runCatching {
					navController.popBackStack(Keys.Page.AccountManager, inclusive = false)
				}.getOrDefault(false)
				if(!back && navController.currentDestination?.route == Keys.Page.MailLogin) {
					runCatching { navController.popBackStack() }
				}
				return
			}

			// 【本期】保存后回到「学校邮箱」页。两种入口都要覆盖:
			// ① 从邮箱页/账号管理进来 -> 栈里已有邮箱页, 直接回退;
			// ② 没存过账号时由 Page.resolve 直接放进本页(栈里没有邮箱页) -> 补一次导航,
			//    并且把本登录页从栈里替换掉, 避免按返回键又回到这里。
			val hasMailPage = try {
				navController.currentBackStack.value.any { it.destination.route == Keys.Page.MailPage }
			}catch(_: Exception){ false }
			if(hasMailPage) navController.popBackStack()
			else runCatching {
				// 单页宿主里没有邮箱页路由: 导航失败就停在原地(不崩)
				navController.navigate(Keys.Page.MailPage) {
					popUpTo(Keys.Page.MailLogin) { inclusive = true }
				}
			}
		}

		/**
		 * 【第三十二轮·按要求】学校邮箱改为**后台自动登录**, 不再"先存下再跳网页":
		 * 用不可见的 WebView 跑一遍学校邮箱自己的登录流程, 凭据正确(出现"二次验证"或直接进了邮箱)
		 * 才保存账号密码并退出本页; 账号/密码不对只弹气泡, 不保存任何东西。
		 * 校验逻辑与「数字青科」一致: 登录中 -> 成功提示 -> 退出页面; 失败按原因给气泡。
		 */
		fun login(){
			if(busy) return
			val acc = account.value.trim()
			if(acc.isEmpty()){ accountError = "请输入邮箱账号"; return }
			else accountError = ""
			val pwd = password.value
			if(pwd.isEmpty()){ passwordError = "请输入密码"; return }
			else passwordError = ""
			busy = true
			scope.launch {
				val result = com.qust.helper.model.account.MailLoginChecker.check(activity, acc, pwd)
				busy = false
				when(result){
					com.qust.helper.model.account.MailLoginChecker.Outcome.OK -> {
						password.value = ""   // 【安全改造】保存后立即清空界面里的明文密码
						// 【第三十四轮·按要求】从「账号管理」进来的这一次, 气泡统一由账号管理弹"登录成功"
						// (本页马上就要退出, 两个气泡前后脚跳只会互相打断);
						// 其它入口(「其他 → 学校邮箱」)照旧用原来那句更具体的文案。
						if(LoginEntry.mailFromAccountManager){
							AccountManagerPage.notifyLoginSuccess()
						}else{
							toast.toastOK("学校邮箱登录成功")
						}
						saveAndExit(acc, pwd)
					}
					com.qust.helper.model.account.MailLoginChecker.Outcome.BAD_PASSWORD ->
						toast.toastError("账号或密码错误")
					// 【第三十五轮】把"根本连不上"和"账号密码不对"分开报, 免得用户反复改密码
					com.qust.helper.model.account.MailLoginChecker.Outcome.NETWORK ->
						toast.toastError("连不上学校邮箱，请确认已连接校园网")
					// 登录页要验证码: 后台替人填不了, 请用户到「学校邮箱」页手动登一次
					com.qust.helper.model.account.MailLoginChecker.Outcome.NEED_CAPTCHA ->
						toast.toastError("学校邮箱需要验证码，请到「学校邮箱」页手动登录")
					else ->
						toast.toastError("暂时无法登录")
				}
			}
		}

		// 【第四十五轮】按要求删除页面内标题/说明文字; 布局与「教务系统/数字青科」登录页完全一致
		// (同样的 Box+padding + 整行宽输入框与确定按钮, 登录框大小统一)
		Box(modifier = Modifier.padding(padding)) {
			Column(Modifier.padding(horizontal = 16.dp)) {
				AccountInput(
					account = account,
					password = password,
					accountError = accountError,
					passwordError = passwordError,
					"邮箱账号", "邮箱密码",
					login = { login() }
				)
				Button(modifier = Modifier.fillMaxWidth().padding(8.dp), onClick = { login() }){
					Text(text = stringResource(id = R.string.text_ok))
				}
			}
		}
		if(busy){
			com.qust.helper.ui.widget.Dialogs.IndeterminateProgressDialog(dialogText = "正在登录")
		}
	}

	@Composable
	fun EASLoginPage(
		padding: PaddingValues,
		viewModel: EasAccountViewModel,
		toast: Toast,
		onSuccess: () -> Unit = { },
		/** 【本轮】需要先去「数字青科登陆」时由页面负责跳转(不再弹气泡让用户自己找) */
		onNeedVpnLogin: () -> Unit = { }
	){
		// 【本轮·按要求】这里是**教务系统原网址的登录界面**(学号 + 教务系统密码):
		// 教务系统的账号密码**正常保存**, 所以进入本页时带出已保存的那一份(与「数字青科登陆」页一致);
		// 没有存过就留空, 由用户自己填。
		//
		// 【本轮修复】回填必须在**第一次组合**就位: 输入框内部用 remember 只认首次组合时的初值,
		// 以前放在 LaunchedEffect 里, 会晚一帧 —— 表现为"第一次点「更换账号」看不到账号密码,
		// 第二次点才正常出现"(第二次是上一帧已经写进 ViewModel 了)。这里改成组合期同步取值。
		val savedAccount = remember { Setting.getString(Keys.EAS_ACCOUNT) }
		val savedPassword = remember { Setting.getString(Keys.EAS_PASSWORD) }
		androidx.compose.runtime.LaunchedEffect(Unit){
			// ViewModel 里也放一份, 供本页其它逻辑使用(不用它当输入框初值)
			viewModel.easName.value = savedAccount
			viewModel.easPassword.value = savedPassword
		}
		androidx.compose.runtime.LaunchedEffect(viewModel.needVpnLogin.value){
			if(viewModel.needVpnLogin.value){
				viewModel.needVpnLogin.value = false
				onNeedVpnLogin()
			}
		}
		Box(modifier = Modifier.padding(padding)){
			EASLoginView(
				viewModel = viewModel,
				accountStr = savedAccount,
				passwordStr = savedPassword,
				loginSuccess = onSuccess
			)
		}
		AppWidgets.DialogBar(viewModel.dialogText)
		toast.ToastContent(viewModel.toastContent)
	}

	@Composable
	fun IPassLoginPage(
		padding: PaddingValues,
		viewModel: IPassAccountViewModel,
		toast: Toast,
		onSuccess: () -> Unit = { },
		/** 【本轮·新增】数字青科登上了但教务管理系统进不去时回调(页面据此转到「教务登陆」) */
		onNeedEasLogin: () -> Unit = { }
	){
		// 【安全改造】密码只在登录瞬间解密: 进入登录页时按需重新解密填充
		// 【本轮修复】同「教务登陆」: 初值必须在组合期取到, 否则登录成功后密码被清空,
		// 再次进入本页时输入框仍显示空(第二次才出现)。
		val savedAccount = remember { Setting.getString(Keys.IPASS_ACCOUNT) }
		val savedPassword = remember { Setting.getString(Keys.IPASS_PASSWORD) }
		androidx.compose.runtime.LaunchedEffect(Unit){
			viewModel.ipassName.value = savedAccount
			viewModel.ipassPassword.value = savedPassword
		}
		androidx.compose.runtime.LaunchedEffect(viewModel.needEasLogin.value){
			if(viewModel.needEasLogin.value){
				viewModel.needEasLogin.value = false
				onNeedEasLogin()
			}
		}
		Box(modifier = Modifier.padding(padding)) {
			IPassLoginView(
				viewModel = viewModel,
				accountStr = savedAccount,
				passwordStr = savedPassword,
				loginSuccess = onSuccess
			)
		}
		AppWidgets.DialogBar(viewModel.dialogText)
		toast.ToastContent(viewModel.toastContent)
	}

	@Composable
	fun EASLoginView(
		viewModel: AccountViewModel,
		accountStr: String = "",
		passwordStr: String = "",
		loginSuccess: () -> Unit
	){
		val errorText by viewModel.errorText

		val account = remember { mutableStateOf(accountStr) }
		val password = remember { mutableStateOf(passwordStr) }
		var accountError by remember { mutableStateOf("") }
		var passwordError by remember { mutableStateOf("") }

		val keyboardController = LocalSoftwareKeyboardController.current

		fun login(){
			keyboardController?.hide()
			// 【本轮·按要求】本页是**教务系统原网址的登录界面**(学号 + 教务系统密码), 不回填账号密码:
			// 两个都留空 = 用**现有会话**进教务(之前登录过的原网址 Cookie / 数字青科会话);
			// 只要填了任意一项, 就按"教务系统原网址"的账号密码校验输入。
			val bothEmpty = account.value.isEmpty() && password.value.isEmpty()
			if(!bothEmpty){
				if(account.value.isEmpty()) { accountError = "请输入学号"; return }
				else if(account.value.length < 2){ accountError = "学号格式错误"; return  }
				else accountError = ""
				if(password.value.isEmpty()) { passwordError = "请输入密码"; return }
				else passwordError = ""
			}
			viewModel.login(account.value, password.value){
				loginSuccess()
			}
		}

		Column(Modifier.padding(horizontal = 16.dp)) {
			AccountInput(
				account = account,
				password = password,
				accountError = errorText.ifEmpty { accountError },
				passwordError = passwordError,
				// 【本轮·按要求】这里是教务系统原网址的登录界面, 密码是**教务系统密码**
				// (走数字青科时用的那个统一身份密码在「数字青科登陆」页, 两个入口分开)
				"学号", "教务系统密码",
				login = { login() }
			)
			// 【第三十一轮】按要求删除"长按查看登录日志"功能: 恢复为普通确定按钮
			Button(modifier = Modifier.fillMaxWidth().padding(8.dp), onClick = { login() }){
				Text(text = stringResource(id = R.string.text_ok))
			}
		}
	}

	@Composable
	fun IPassLoginView(
		viewModel: IPassAccountViewModel,
		accountStr: String = "",
		passwordStr: String = "",
		loginSuccess: () -> Unit
	){
		val errorText by viewModel.errorText

		val account = remember { mutableStateOf(accountStr) }
		val password = remember{ mutableStateOf(passwordStr) }
		var accountError by remember { mutableStateOf("") }
		var passwordError by remember { mutableStateOf("") }

		val keyboardController = LocalSoftwareKeyboardController.current

		fun login(){
			keyboardController?.hide()
			if(account.value.isEmpty()) { accountError = "请输入学号"; return }
			else accountError = ""
			if(password.value.isEmpty()) { passwordError = "请输入密码"; return }
			else passwordError = ""
			viewModel.login(account.value, password.value){
				loginSuccess()
			}
		}

		Column(Modifier.padding(horizontal = 16.dp)) {
			AccountInput(
				account = account,
				password = password,
				accountError = errorText.ifEmpty { accountError },
				passwordError = passwordError,
				"学号", "数字青科密码",
				login = { login() }
			)

			// 【第三十一轮】按要求删除"长按查看登录日志"功能: 恢复为普通确定按钮
			Button(modifier = Modifier.fillMaxWidth().padding(8.dp), onClick = { login() }){
				Text(text = stringResource(id = R.string.text_ok))
			}
		}
	}

	@Composable
	fun AccountInput(
		account: MutableState<String>,
		password: MutableState<String>,
		accountError: String = "",
		passwordError: String = "",
		labelAccount: String = "账号",
		labelPassword: String = "密码",
		login: () -> Unit
	){
		var passwordHidden by remember{ mutableStateOf(true) }

		OutlinedTextField(
			value = account.value,
			onValueChange = { account.value = it },
			modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
			singleLine = true,
			label = { Text(text = labelAccount) },
			isError = accountError.isNotEmpty(),
			supportingText = { if(accountError.isNotEmpty()) Text(text = accountError) },
			keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
			maxLines = 1
		)

		OutlinedTextField(
			value = password.value,
			onValueChange = { password.value = it },
			modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
			label = { Text(text = labelPassword) },
			isError = passwordError.isNotEmpty(),
			supportingText = { if(passwordError.isNotEmpty()) Text(text = passwordError) },
			trailingIcon = {
				// 【第三十一轮·修复小眼睛逻辑】隐藏密码(显示圆点)时应是"带斜杠的眼睛"(visibility_off);
				// 密码可见时应是"不带斜杠的眼睛"(visibility)。以前图标正好相反, 全部登录入口都已纠正。
				IconButton(onClick = { passwordHidden = !passwordHidden }){
					Icon(painterResource(if(passwordHidden) R.drawable.ic_visibility_off else R.drawable.ic_visibility), null)
				}
			},
			visualTransformation = if(passwordHidden) PasswordVisualTransformation('*') else VisualTransformation.None,
			keyboardOptions = KeyboardOptions(autoCorrect = false, keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
			keyboardActions = KeyboardActions(onDone = { login() }),
			maxLines = 1
		)
	}
}

