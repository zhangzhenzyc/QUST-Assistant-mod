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

import android.os.SystemClock
import android.webkit.CookieManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qust.helper.R
import com.qust.helper.data.Keys
import com.qust.helper.data.LoginEntry
import com.qust.helper.data.Page
import com.qust.helper.data.Setting
import com.qust.helper.model.account.DrinkAccount
import com.qust.helper.model.account.EASAccount
import com.qust.helper.model.account.IPassAccount
import com.qust.helper.ui.appwidget.DrinkCodeWidget
import com.qust.helper.ui.theme.TEXT_ACTION_H_PADDING
import com.qust.helper.ui.theme.TEXT_ACTION_V_PADDING
import com.qust.helper.ui.theme.colorSecondaryText
import com.qust.helper.ui.theme.textActionFilledColors
import com.qust.helper.ui.widget.Dialogs
import kotlinx.coroutines.launch


/** 【本期】账号卡片的操作文字颜色("点击登录"与"更换账号"用同一个蓝) */
private val ACTION_BLUE = Color(0xFF1976D2)

object AccountManagerPage {

	/**
	 * 【登录逻辑】账号数据版本号。
	 * 登录页保存账号后调用 [notifyAccountChanged]，账号管理即使还在返回栈中也会立即重组，
	 * 避免用户看到"点了确定但没有保存账号"的假象。
	 */
	var accountRevision by mutableIntStateOf(0)
		private set

	fun notifyAccountChanged(){
		accountRevision++
	}

	/**
	 * 【第三十四轮·按要求】"登录成功"气泡。
	 *
	 * 用户原话: "账号管理里面所有的登录成功后要给出气泡提示'登录成功'"。
	 *
	 * 四个登录入口(教务系统 / 数字青科 / 饮水码 / 学校邮箱)都**不是**在本页完成登录的 ——
	 * 它们各自跳到自己的登录页, 成功后 `popBackStack()` 回到本页。所以本页拿不到"登录成功"这个回调,
	 * 只能由各登录页在成功那一刻来 [notifyLoginSuccess] 记一笔, 本页重新组合时再把气泡弹出来。
	 *
	 * 记的是**时间戳**而不是 boolean, 是为了避免"张冠李戴":
	 * 从「欢迎页」等别的入口登录成功后, 账号管理并不在回退栈里, 那份"登录成功"记录就会一直挂着,
	 * 等用户很久以后第一次打开账号管理时莫名弹一句"登录成功"。15 秒之外的记录直接丢掉(见页面里的消费逻辑)。
	 */
	private var loginSuccessAt by mutableLongStateOf(0L)

	/** 某个账号登录成功了(由各登录页在成功分支调用); 回到本页时弹一次"登录成功" */
	fun notifyLoginSuccess(){
		loginSuccessAt = SystemClock.elapsedRealtime()
	}

	val AccountManagerPage = Page(
		key = Keys.Page.AccountManager,
		name = "账号管理",
		iconRes = R.drawable.ic_login,
	) { activity, padding, navController, _ ->

		val scope = rememberCoroutineScope()
		var update by remember { mutableStateOf(false) }
		// 读取该状态, 登录页保存账号后账号管理会立即刷新
		val revision = accountRevision

		var iPassAccountName = IPassAccount.getInstance().getAccount()
		// 【本期】教务账号位只在"用户单独存过教务账号"时才有值; 教务账号密码按要求已不再保存 -> 这里恒为空
		var easAccountName = Setting.getString(Keys.EAS_ACCOUNT)
		// 【本期】是否"通过数字青科进入教务": 数字青科的账号+密码都已保存
		var easViaIpass = easViaIpassNow()
		var drinkAccountName = Setting.getString(Keys.DRINK_ACCOUNT)
		// 【第三十六轮】学校邮箱为独立账号(不与教务/统一身份共用)
		var mailAccountName = Setting.getString(Keys.MAIL_ACCOUNT)

		LaunchedEffect(update, revision){
			iPassAccountName = IPassAccount.getInstance().getAccount()
			easAccountName = Setting.getString(Keys.EAS_ACCOUNT)
			easViaIpass = easViaIpassNow()
			drinkAccountName = Setting.getString(Keys.DRINK_ACCOUNT)
			mailAccountName = Setting.getString(Keys.MAIL_ACCOUNT)
		}

		// 【第三十四轮·按要求】从任意一个登录页登录成功返回本页时, 弹一次"登录成功"。
		// 四个入口(教务系统/数字青科/饮水码/学校邮箱)统一走这里, 文案一律是"登录成功"。
		val successAt = loginSuccessAt
		LaunchedEffect(successAt){
			if(successAt <= 0L) return@LaunchedEffect
			// 消费掉, 保证只提示一次(返回本页会重新组合, 不然会反复弹)
			loginSuccessAt = 0L
			// 只认"刚刚"的: 15 秒之前的记录视为过期(见 notifyLoginSuccess 的注释)
			if(SystemClock.elapsedRealtime() - successAt <= 15_000L) {
				activity.toast.toastOK("登录成功")
			}
		}

		Box(Modifier.padding(padding)) {
			AccountManagerPageUI(
				easAccountName = easAccountName,
				easViaIpass = easViaIpass,
				iPassAccountName = iPassAccountName,
				drinkAccountName = drinkAccountName,
				mailAccountName = mailAccountName,
				go2EasLogin = {
					// 【本轮·按要求】教务系统有**两条**登录路径:
					//   ① 教务系统原网址(本页「教务登陆」: 学号 + 密码);
					//   ② 数字青科里的「教务管理系统」。
					// 账号管理里「教务系统 → 点击登录」走的是**第①条**(原网址路径), 所以进「教务登陆」页;
					// 登录成功后**会保存**教务系统自己的账号密码(下次自动回填), 但不自动拉课表(避免覆盖本地调休/调课改动)。
					LoginEntry.fromAccountManager = true
					Page.navigate(navController, Keys.Page.EasLogin)
				},
				go2IpassLogin = {
					Page.navigate(navController, Keys.Page.VpnLoginPage)
				},
				go2DrinkLogin = {
					// 【第四十六轮】账号管理里的饮水码"更换账号": 直接进登录界面(不显示饮水码);
					// 饮水码条码只在「我 → 饮水码」入口显示
					Page.navigate(navController, Keys.Page.DrinkLogin)
				},
				// 【第三十六轮】学校邮箱为独立邮箱账号: 登录入口为「学校邮箱登陆」页
				// 【第三十三轮·按要求】从账号管理进来只"存账号密码", 保存完直接退回本页, 不进邮箱网页
				go2MailLogin = {
					LoginEntry.mailFromAccountManager = true
					Page.navigate(navController, Keys.Page.MailLogin)
				},
				// 【本期】只有"单独存过教务账号"时才会用到(那种情况下才显示退出登录)
				easLogout = {
					EASAccount.getInstance().logout()
					clearSiteWebCookies(EASAccount.getInstance().host)
					notifyAccountChanged()
					update = !update
				},
				ipassLogout = {
					IPassAccount.getInstance().logout()
					clearSiteWebCookies("wvpn.qust.edu.cn")
					notifyAccountChanged()
					update = !update
				},
				mailLogout = {
					// 学校邮箱为独立账号: 只清除邮箱账号与邮箱站点的登录状态
					Setting.edit { it.remove(Keys.MAIL_ACCOUNT).remove(Keys.MAIL_PASSWORD) }
					try {
						val cookieManager = CookieManager.getInstance()
						for(host in arrayOf("mail.qust.edu.cn")){
							for(scheme in arrayOf("https", "http")){
								val url = "$scheme://$host"
								val cookies = cookieManager.getCookie(url) ?: continue
								for(cookie in cookies.split(";")){
									val name = cookie.substringBefore("=").trim()
									if(name.isEmpty()) continue
									cookieManager.setCookie(url, "$name=; Max-Age=0; path=/")
									cookieManager.setCookie(url, "$name=; Max-Age=0; path=/; domain=$host")
									cookieManager.setCookie(url, "$name=; Max-Age=0; path=/; domain=.$host")
								}
							}
						}
						cookieManager.flush()
					}catch(_: Exception){ }
					notifyAccountChanged()
					update = !update
				},
				drinkLogout = {
					// 【第三十轮】DrinkAccount.logout() 现在会把内存里的登录令牌与条码一起清掉
					// (以前只清了 Cookie/账号密码, 条码还留在内存里, 「其他 → 饮水码」照样显示,
					//  用户看到的就是"退出登录了但饮水码还是正常的")
					DrinkAccount.getInstance().logout()
					Setting.edit { it.remove("drinkToken").remove("drinkCode") }
					// 桌面小组件同步切成"未登录", 不留旧条码
					scope.launch { runCatching { DrinkCodeWidget.push(activity) } }
					update = !update
				},
			)
		}
	}

	/**
	 * 账号管理界面
	 * 【第三十五轮】新增"学校邮箱"(与数字青科共用统一身份账号, 自动登录方式与智慧体测一致)
	 */
	/** 【第四十八轮】清除某站点在 WebView 里的全部 Cookie(退出独立账号时用) */
	private fun clearSiteWebCookies(host: String) {
		try {
			val cookieManager = CookieManager.getInstance()
			for(scheme in arrayOf("https", "http")){
				val url = "$scheme://$host"
				val cookies = cookieManager.getCookie(url) ?: continue
				for(cookie in cookies.split(";")){
					val name = cookie.substringBefore("=").trim()
					if(name.isEmpty()) continue
					cookieManager.setCookie(url, "$name=; Max-Age=0; path=/")
					cookieManager.setCookie(url, "$name=; Max-Age=0; path=/; domain=$host")
					cookieManager.setCookie(url, "$name=; Max-Age=0; path=/; domain=.$host")
				}
			}
			cookieManager.flush()
		}catch(_: Exception){ }
	}

	/**
	 * 【本期】「教务系统」是不是"通过数字青科进入"的。
	 *
	 * 口径: 数字青科的账号+密码都已保存(说明用户是在统一身份里登录的),
	 * 此时教务可以经"业务应用 → 教务管理系统"进入。
	 * 注意: 教务系统自己的账号密码是**另一份**(在「教务登陆」页用原网址登录时保存的),
	 * 这里判断的只是"统一身份已登录", 所以两者要分开看。
	 */
	private fun easViaIpassNow(): Boolean {
		return IPassAccount.getInstance().getAccount().isNotEmpty() && hasSavedPassword(Keys.IPASS_PASSWORD)
	}

	/**
	 * 【本轮】某个密码键是否**存过**。
	 *
	 * 密码是加密落盘的, [Setting.getString] 读的时候还要解密一次; 万一 Keystore 临时不可用
	 * 就会解出空串 —— 于是"刚登录成功的账号"在账号管理里又变成"未登录", 连"更换账号/退出登录"
	 * 都不显示。这里只看键是否存在: 键只会在登录成功时写入、退出登录时删除, 用来判断"登没登过"
	 * 已经够了, 也不会再被"网页里残留 Cookie"骗到。
	 */
	internal fun hasSavedPassword(key: String): Boolean {
		// 键存在 = 写进去过(登录成功才会写, 退出登录会删); 解密失败也照样算"登过"。
		return Setting.contains(key) || Setting.getString(key).isNotEmpty()
	}

	@Composable
	fun AccountManagerPageUI(
		/** 【本期】教务系统自己保存的账号(在「教务登陆」页正常登录时会保存; 只用数字青科进入时为空) */
		easAccountName: String = "",
		/** 【本期】是否通过数字青科进入教务: 为真时也算"能进教务"(统一身份已验证过) */
		easViaIpass: Boolean = false,
		iPassAccountName: String = "",
		drinkAccountName: String = "",
		mailAccountName: String = "",
		go2EasLogin: () -> Unit = { },
		go2IpassLogin: () -> Unit = { },
		go2DrinkLogin: () -> Unit = { },
		go2MailLogin: () -> Unit = { },
		easLogout: () -> Unit = { },
		ipassLogout: () -> Unit = { },
		drinkLogout: () -> Unit = { },
		mailLogout: () -> Unit = { },
	){
		var askForLogoutEas by remember { mutableStateOf(false) }
		var askForLogoutIpass by remember { mutableStateOf(false) }
		var askForLogoutDrink by remember { mutableStateOf(false) }
		var askForLogoutMail by remember { mutableStateOf(false) }

		Column {
			// 【本轮·按要求】教务系统这一栏恢复**三态**(上一版误把文案改成了"已登录"):
			// - 通过数字青科登录(统一身份账号+密码都已保存) -> 「已通过数字青科进入」+「点击登录」
			//   (不显示学号: 那条路径用的是统一身份凭据, 不是教务系统自己的账号)
			// - 单独存过教务系统账号(在本应用「教务登陆」页用原网址登录过) -> 显示学号 + 更换账号/退出登录;
			// - 都没登录 -> 「未登录」+「点击登录」。
			AccountCard(
				title = "教务系统",
				account = easAccountName,
				loggedIn = easAccountName.isNotEmpty() || easViaIpass,
				statusText = "已通过数字青科进入",
				loginOnly = easAccountName.isEmpty(),
				go2Login = go2EasLogin,
				askLogout = { askForLogoutEas = true }
			)

			// 数字青科是 WebView 登录, 会话保存在 WebView 的 CookieManager 里, 需同时探测网页会话
			AccountCard(
				title = "数字青科",
				account = iPassAccountName,
				// 【修改版】账号+密码都在才算已登录(仅凭网页 Cookie 会把"没登录"显示成"已登录")
				// 【本轮】密码键"存在"即可: 密码是加密落盘的, 读取时还要过一遍解密,
				// 万一 Keystore 临时不可用会解出空串 —— 那会让**刚登录成功**的账号管理又显示"未登录",
				// 连"更换账号/退出登录"都没有。判"登没登过"看键在不在就够了。
				loggedIn = iPassAccountName.isNotEmpty() && hasSavedPassword(com.qust.helper.data.Keys.IPASS_PASSWORD),
				go2Login = go2IpassLogin,
				askLogout = { askForLogoutIpass = true }
			)

			AccountCard(title = "饮水码", account = drinkAccountName, go2Login = go2DrinkLogin, askLogout = { askForLogoutDrink = true })

			// 【第三十六轮】学校邮箱: 独立邮箱账号(不与教务/统一身份共用)
			AccountCard(
				title = "学校邮箱",
				account = mailAccountName,
				loggedIn = mailAccountName.isNotEmpty() && Setting.getString(Keys.MAIL_PASSWORD).isNotEmpty(),
				go2Login = go2MailLogin,
				askLogout = { askForLogoutMail = true }
			)

			// 只有"单独存过教务账号"时这条才可能出现(通过数字青科进入时不提供退出)
			if(askForLogoutEas){
				Dialogs.AskDialog("退出登录", "是否确定退出教务系统？储存的教务系统账号登录信息会被删除", { askForLogoutEas = false }){
					easLogout()
					askForLogoutEas = false
				}
			}

			if(askForLogoutIpass){
				Dialogs.AskDialog("退出登录", "是否确定退出数字青科？储存的数字青科账号登录信息会被删除", { askForLogoutIpass = false }){
					ipassLogout()
					askForLogoutIpass = false
				}
			}

			if(askForLogoutDrink){
				Dialogs.AskDialog("退出登录", "是否确定退出饮水码？储存的饮水码账号登录信息会被删除", { askForLogoutDrink = false }){
					drinkLogout()
					askForLogoutDrink = false
				}
			}

			// 【第三十六轮】学校邮箱: 独立邮箱账号, 退出只清除邮箱账号与邮箱站点登录状态
			if(askForLogoutMail){
				Dialogs.AskDialog("退出登录", "是否确定退出学校邮箱？储存的邮箱账号登录信息会被删除", { askForLogoutMail = false }){
					mailLogout()
					askForLogoutMail = false
				}
			}

		}
	}

	/**
	 * 单个账号卡片
	 * - 未登录: "未登录" + "点击登录"
	 * - 已登录但没有独立账号(教务通过数字青科进入): [statusText] + "点击登录"([loginOnly]=true)
	 * - 已登录且有账号: 账号 + "更换账号" + "退出登录"
	 */
	@Composable
	private fun AccountCard(
		title: String,
		account: String,
		loggedIn: Boolean = account.isNotEmpty(),
		/** 已登录但没有账号时左侧显示的文字 */
		statusText: String = "已登录",
		/** 已登录时是否只给一个"点击登录"(不显示更换账号/退出登录) */
		loginOnly: Boolean = false,
		go2Login: () -> Unit,
		askLogout: () -> Unit
	) {
		// 【第五十七轮·按要求】条目外观与「时间表管理」的卡片统一: 白底 + 6dp 阴影 + 8/4dp 外边距
		Card(
			modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
			colors = CardDefaults.cardColors(containerColor = Color.White),
			elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
		) {
			Text(text = title, fontWeight = FontWeight.W700, modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp))
			Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically){
				// 【第三十三轮·按要求】三个文字按钮统一加"与波纹边缘重合"的灰色药丸边框,
				// 留白同全应用其它文字按钮(TEXT_ACTION_H/V_PADDING)。
				if(!loggedIn){
					Text(text = "未登录", modifier = Modifier.weight(1F), color = colorSecondaryText)
					TextButton({ go2Login() },
						colors = textActionFilledColors(),
						contentPadding = PaddingValues(horizontal = TEXT_ACTION_H_PADDING, vertical = TEXT_ACTION_V_PADDING)){
						// 【本期】"点击登录"统一用与"更换账号"一致的操作蓝
						Text(text = "点击登录")
					}
				}else if(loginOnly){
					Text(text = account.ifEmpty { statusText }, modifier = Modifier.weight(1F))
					TextButton({ go2Login() },
						colors = textActionFilledColors(),
						contentPadding = PaddingValues(horizontal = TEXT_ACTION_H_PADDING, vertical = TEXT_ACTION_V_PADDING)){
						Text(text = "点击登录")
					}
				}else{
					Text(text = account.ifEmpty { statusText }, modifier = Modifier.weight(1F))
					TextButton({ go2Login() },
						colors = textActionFilledColors(),
						contentPadding = PaddingValues(horizontal = TEXT_ACTION_H_PADDING, vertical = TEXT_ACTION_V_PADDING)){
						Text(text = "更换账号")
					}
					// 【第五十四轮·按要求】两个按钮之间留出 8dp 间距, 之前紧贴在一起
					Spacer(modifier = Modifier.width(8.dp))
					TextButton({ askLogout() },
						colors = textActionFilledColors(),
						contentPadding = PaddingValues(horizontal = TEXT_ACTION_H_PADDING, vertical = TEXT_ACTION_V_PADDING)){
						// 【第五十四轮·按要求】蓝底上的红字也统一改白
						Text(text = "退出登录")
					}
				}
			}
		}
	}
}
