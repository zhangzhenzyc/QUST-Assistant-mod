/*
 * 青科助手（修改版） —— 基于开源项目 QUST-Assistant 修改而成
 *
 * 原项目版权所有 (C) 2891954521  (https://github.com/2891954521/QUST-Assistant)
 * 原始开源许可: GNU General Public License v3.0 (GPL-3.0), 许可原文见 LICENSE 文件, 保持完整未改动。
 *
 * 修改版版权所有 (C) 2026 zhangzhenzyc  (https://github.com/zhangzhenzyc)
 * 本修改版同样以 GPL-3.0 发布; 附加版权声明见 NOTICE.md —— 两份版权声明相互独立、同时生效。
 */

package com.qust.helper.ui.page

import android.view.WindowManager
import androidx.activity.viewModels
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.qust.helper.ui.theme.tapRipple
import com.qust.helper.R
import com.qust.helper.data.Keys
import com.qust.helper.data.Page
import com.qust.helper.ui.activity.BaseActivity
import com.qust.helper.ui.page.account.LoginPage
import com.qust.helper.ui.theme.Drawables
import com.qust.helper.ui.widget.AppWidgets
import com.qust.helper.utils.LinearBarCode
import com.qust.helper.viewmodel.DrinkUIEvent
import com.qust.helper.viewmodel.DrinkUIState
import com.qust.helper.viewmodel.DrinkViewModel

/**
 * 饮水码
 */
object DrinkPage {

	val DrinkPage = Page(Keys.Page.DrinkPage, "饮水码", iconRes = R.drawable.ic_water) { activity, padding, navController, _ ->
		val viewModel by activity.viewModels<DrinkViewModel>()
		val context = LocalContext.current
		// 【本轮·修复回归】进页面先按已保存的账号自动登录、取一次码。
		//
		// 第二十四轮把登录改成"等结果出来再回调"时, 顺手漏掉了这里的自动取码:
		// `getDrinkCode()` 内部会先 `checkLogin()`(用已保存的凭据刷新 token),
		// 成功才去拿条码 —— 这就是"自动登录"。少了这一步, 账号管理里明明登过,
		// 进来还是"请先登录"。
		LaunchedEffect(Unit) {
			// 【第三十轮】只在"存过账号"时才自动登录取码。
			// 以前不管有没有账号都去取 —— 用户在账号管理里退出饮水码登录后回到这一页,
			// 又会拿旧 token 去换一次码(甚至直接命中内存里的旧条码), 看起来"退出了还能正常显示"。
			// 没存账号就直接显示「请先登录」, 由用户点「登录」进统一登录页。
			// 先与本地存储对齐: 退出登录后内存里可能还留着旧账号, 不对齐就会"退出后还能自动登录"
			viewModel.syncAccountState()
			if(viewModel.uiState.drinkCode.isEmpty() && viewModel.uiState.account.value.isNotEmpty()) {
				viewModel.getDrinkCode()
			}
		}
		// 【本轮】码一变化就同步刷新桌面小组件: 否则登录完桌面还停在"尚未登录饮水账号"。
		LaunchedEffect(viewModel.uiState.drinkCode) {
			if(viewModel.uiState.drinkCode.isNotEmpty()) {
				runCatching { com.qust.helper.ui.appwidget.DrinkCodeWidget.push(context) }
			}
		}
		DrinkPage(padding, viewModel, activity, onNeedLogin = { Page.navigate(navController, Keys.Page.DrinkLogin) })
	}

	/**
	 * 【第八十九轮】登录成功后自动发起"把饮水码小组件钉到桌面"。
	 * 走系统 requestPinAppWidget: 桌面会弹系统确认框, 用户点「添加」即落到桌面;
	 * 启动器不支持时静默结束(用户仍可回小组件页手动添加)。
	 */
	private fun autoPinDrinkWidget(context: android.content.Context) {
		try {
			if(android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
			val manager = android.appwidget.AppWidgetManager.getInstance(context)
			if(!manager.isRequestPinAppWidgetSupported) return
			val provider = android.content.ComponentName(context,
				com.qust.helper.ui.appwidget.DrinkCodeReceiver::class.java)
			val homeIntent = android.content.Intent(android.content.Intent.ACTION_MAIN)
				.addCategory(android.content.Intent.CATEGORY_HOME)
				.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
			val callback = android.app.PendingIntent.getActivity(
				context, 1001, homeIntent,
				android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
			)
			manager.requestPinAppWidget(provider, null, callback)
		}catch(_: Exception){ }
	}

	/**
	 * 【第四十六轮】饮水码登陆页(账号管理里饮水码"更换账号"的入口):
	 * 只显示登录界面, 不显示饮水码; 样式与「教务系统/数字青科」登录页完全一致。
	 * 饮水码(条码)仍只在「我 → 饮水码」入口显示。
	 */
	val DrinkLoginPage = Page(Keys.Page.DrinkLogin, "饮水码登陆", iconRes = R.drawable.ic_login) { activity, padding, navController, _ ->
		val viewModel by activity.viewModels<DrinkViewModel>()
		val keyboardController = LocalSoftwareKeyboardController.current
		// 【第三十轮】进入登录页时同样对齐一次: 退出登录后输入框不该还显示着旧手机号
		LaunchedEffect(Unit) { viewModel.syncAccountState() }

		fun login(){
			keyboardController?.hide()
			// 【本轮·按要求】等登录结果出来再退出登录页:
			// 以前是"点了确定立刻 pop, 登录在后台跑", 回到账号管理时账号密码还没写进去,
			// 要等三四秒才变成"更换账号/退出登录"。现在成功才返回, 返回时数据已经落盘。
			viewModel.uiEvent.login(refreshCode = true){ ok ->
				if(ok){
					com.qust.helper.ui.page.account.AccountManagerPage.notifyAccountChanged()
					// 【第三十四轮·按要求】登录成功: 记一笔, 回到「账号管理」时弹"登录成功"气泡
					com.qust.helper.ui.page.account.AccountManagerPage.notifyLoginSuccess()
					// 【第八十九轮·按要求】从小组件页「登录后添加」进来的: 登录成功后不用
					// 再回小组件页点一次, 直接自动发起"把饮水码小组件钉到桌面"。
					if(com.qust.helper.data.LoginEntry.autoAddDrinkWidget){
						com.qust.helper.data.LoginEntry.autoAddDrinkWidget = false
						runCatching { autoPinDrinkWidget(activity) }
					}
					navController.popBackStack()
				}
			}
		}

		// 【第三十轮】与「教务系统」「数字青科」登录页完全同一套排版: 两个输入框 + 整行确定按钮;
		// 校验、进度弹窗、失败提示也一致 —— 以前这一页没有 DialogBar/Toast, 登录时既看不到"登录中",
		// 也看不到"账号或密码错误"。
		var accountError by remember { mutableStateOf("") }
		var passwordError by remember { mutableStateOf("") }

		fun loginWithCheck(){
			if(viewModel.uiState.account.value.isEmpty()){ accountError = "请输入手机号"; return }
			else accountError = ""
			if(viewModel.uiState.password.value.isEmpty()){ passwordError = "请输入密码"; return }
			else passwordError = ""
			login()
		}

		Box(modifier = Modifier.padding(padding)) {
			Column(Modifier.padding(horizontal = 16.dp)) {
				LoginPage.AccountInput(
					account = viewModel.uiState.account,
					password = viewModel.uiState.password,
					accountError = accountError,
					passwordError = passwordError,
					labelAccount = "手机号",
					login = { loginWithCheck() }
				)
				Button(modifier = Modifier.fillMaxWidth().padding(8.dp), onClick = { loginWithCheck() }){
					Text(text = stringResource(id = R.string.text_ok))
				}
			}
		}
		AppWidgets.DialogBar(dialogText = viewModel.dialogText)
		activity.toast.ToastContent(toastContent = viewModel.toastContent)
	}

	@Composable
	fun DrinkPage(padding: PaddingValues, viewModel: DrinkViewModel, activity: BaseActivity, onNeedLogin: () -> Unit = { }){
		val layoutParams: WindowManager.LayoutParams = activity.window.attributes
		// 【第三十一轮·按要求】进页面时**亮度与手机保持一致**, 只有拖滑块时才改本页亮度。
		//
		// 之前是无条件把本窗口压到"最大亮度的一半", 用户一进页面屏幕就暗一截, 还得自己拖回去。
		// 现在: 进页面 = 跟随系统(BRIGHTNESS_OVERRIDE_NONE, -1), 滑块初始值读系统的实际亮度;
		// 只有用户手动拖滑块才写 screenBrightness —— 这是**窗口级**属性, 只影响这一页,
		// 不改手机系统亮度、也不影响软件里的其它页面; 离开本页还原原值(可能本来就是 -1)。
		val original = remember { layoutParams.screenBrightness }
		val systemBrightness = remember { readSystemBrightness(activity) }
		DisposableEffect(Unit){
			layoutParams.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
			activity.window.attributes = layoutParams
			onDispose {
				layoutParams.screenBrightness = original
				activity.window.attributes = layoutParams
			}
		}
		DrinkUI(
			padding = padding,
			defaultProgress = systemBrightness,
			onProgress = {
				layoutParams.screenBrightness = it
				activity.window.attributes = layoutParams
			},
			uiState = viewModel.uiState,
			uiEvent = viewModel.uiEvent,
			onNeedLogin = onNeedLogin,
		)
		AppWidgets.DialogBar(dialogText = viewModel.dialogText)
		activity.toast.ToastContent(toastContent = viewModel.toastContent)
	}

	/**
	 * 读手机当前的屏幕亮度(0f~1f)。
	 *
	 * `Settings.System.SCREEN_BRIGHTNESS` 是系统亮度档位(0~255), 读它不需要任何权限。
	 * 读不到(极少数定制系统)就退回"最大亮度的一半", 保证滑块不会卡在 0(那会让屏幕全黑)。
	 */
	private fun readSystemBrightness(activity: BaseActivity): Float = runCatching {
		val value = android.provider.Settings.System.getInt(
			activity.contentResolver,
			android.provider.Settings.System.SCREEN_BRIGHTNESS
		)
		(value / 255F).coerceIn(0.05F, 1F)
	}.getOrDefault(HALF_BRIGHTNESS)

	@Composable
	fun DrinkUI(
		padding: PaddingValues,
		defaultProgress: Float = 0F,
		onProgress: (Float) -> Unit = { },
		uiState: DrinkUIState,
		uiEvent: DrinkUIEvent,
		/** 【第三十轮】"登录/更换账号"统一进登录页(不再从底部弹出半屏的 ModalBottomSheet) */
		onNeedLogin: () -> Unit = { },
	){
		var brightness by remember { mutableFloatStateOf(defaultProgress) }

		var currentSize by remember { mutableIntStateOf(100) }
		var currentRotate by remember { mutableFloatStateOf(0F) }

		val barSize by animateDpAsState(targetValue = currentSize.dp, animationSpec = tween(300), label = "barSize")
		val arrowRotate by animateFloatAsState(targetValue = currentRotate, animationSpec = tween(300), label = "barSize")

		Box(modifier = Modifier.padding(padding)){
			Column(
				Modifier.fillMaxSize(),
				horizontalAlignment = Alignment.CenterHorizontally
			) {
				if(uiState.drinkCode.isNotEmpty()){
					Image(
						// 【第九十九轮·按要求】软件内条码前景色与小组件统一: 纯黑 -> 深灰 #2B2B2B。
						// Code128 只要求暗区反射率 <= 33%(PCS >= 0.67), #2B2B2B 反射率约 7%, 扫码余量充足。
						bitmap = LinearBarCode.createCode128Barcode(uiState.drinkCode, 50, android.graphics.Color.WHITE, 0xFF2B2B2B.toInt()).asImageBitmap(),
						contentDescription = "",
						contentScale = ContentScale.FillBounds,
						modifier = Modifier.fillMaxWidth().height(barSize).padding(horizontal = 16.dp).clip(RoundedCornerShape(8.dp))
					)
					Card(
						shape = RoundedCornerShape(0.dp, 0.dp, 16.dp, 16.dp),
						elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
					) {
						// 【第二十九轮】展开/收起箭头: 原来是直角四方形反馈, 换成统一圆角
						Box(modifier = Modifier.padding(horizontal = 4.dp).tapRipple {
							currentSize = if(currentSize == 100) 200 else 100
							currentRotate = if(currentRotate == 0F) 180F else 0F
						}){
							Icon(
								imageVector = Icons.Rounded.ArrowDropDown,
								contentDescription = "",
								modifier = Modifier.padding(8.dp, 4.dp, 8.dp, 4.dp).rotate(arrowRotate)
							)
						}
					}
				}else{
					Box(modifier = Modifier.fillMaxWidth().height(100.dp)){
						Text(
							text = "请先登录",
							modifier = Modifier.align(Alignment.Center),
							style = MaterialTheme.typography.displaySmall,
							textAlign = TextAlign.Center
						)
					}
				}

				Row(
					modifier = Modifier.padding(8.dp),
					verticalAlignment = Alignment.CenterVertically
				) {
					Icon(
						imageVector = Drawables.Brightness,
						contentDescription = "",
						modifier = Modifier.padding(8.dp),
						tint = Color(200, 200, 200)
					)
					Slider(
						value = brightness,
						onValueChange = { brightness = it; onProgress(it) },
					)
				}

				Button(
					modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp),
					onClick = { if(uiState.drinkCode.isEmpty()){ onNeedLogin() } else uiEvent.getDrinkCode() }
				) {
					Text(text = if(uiState.drinkCode.isEmpty()) stringResource(id = R.string.text_login) else "刷新条码")
				}

				// 【第六十七轮·按要求】"删除『我』里面的饮水码的『更换账号』按钮, 只保留账号管理里的"。
				// 「更换账号」是账号维度的操作, 集中放在「账号管理」一处; 饮水码页只负责展示与刷新。
			}
		}

		// 【第三十轮】取码时发现"没登录"(needLogin)也送到同一个登录页, 不再弹底部半屏
		LaunchedEffect(uiState.needLogin){
			if(uiState.needLogin){
				uiState.needLogin = false
				onNeedLogin()
			}
		}
	}

	/** 【第三十轮】饮水码页默认亮度: 最大亮度(1.0)的一半 */
	private const val HALF_BRIGHTNESS = 0.5F
}
