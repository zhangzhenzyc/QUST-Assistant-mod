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

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.qust.helper.R
import com.qust.helper.data.Keys
import com.qust.helper.data.Page
import com.qust.helper.data.Setting
import com.qust.helper.model.account.EASAccount
import com.qust.helper.model.account.IPassAccount
import com.qust.helper.ui.page.app.SettingPage
import com.qust.helper.ui.page.app.WidgetPage
import com.qust.helper.ui.page.eas.GetAcademic
import com.qust.helper.ui.page.eas.GetExams
import com.qust.helper.ui.page.eas.GetLessonTable
import com.qust.helper.ui.page.eas.GetMarks
import com.qust.helper.ui.page.eas.GetNotice
import com.qust.helper.ui.page.web.SchoolWebPages
import com.qust.helper.ui.page.web.WebPage
import com.qust.helper.ui.theme.TEXT_ACTION_H_PADDING
import com.qust.helper.ui.theme.TEXT_COLORS
import com.qust.helper.ui.theme.colorSecondaryText
import com.qust.helper.ui.theme.tapRipple
import com.qust.helper.ui.theme.textAction
import com.qust.helper.ui.widget.Toast

object MyPage {

	// 「课表」栏目: 只保留「课表查询」「课表设置」
	// 「当日课表」「学期课表」已从这里移除(学期课表仍是底部导航的主页面, 主界面不删除)
	private val lesson = arrayOf(
		GetLessonTable.GetLessonPage,
		SettingPage.LessonTableSettingPage
	)

	// 「教务系统」栏目: 网页版教务系统放在第一位, 自主选课放在"教务查询设置"之前
	private val eas = arrayOf(
		WebPage.EasWebPage,
		GetNotice.GetNoticePage,
		GetMarks.GetMarksPage,
		GetAcademic.GetAcademicPage,
		GetExams.GetExamsPage,
		WebPage.XsxkWebPage,
		SettingPage.EasSettingPage
	)

	// 「数字青科」栏目(原"网页入口"): 教务系统与自主选课已移到教务系统栏目
	// 智慧体测与数字青科同一套自动登录逻辑(账号管理统一身份 + WebVPN)
	// 【第二十九轮】新增"座位预约"(zyyy.qust.edu.cn, 同款登录)
	private val web = arrayOf(
		WebPage.IpassWebPage,
		WebPage.TiyuWebPage,
		WebPage.SeatReservePage
	)

	// 「其他」栏目: 原"其他系统"(饮水码)与"其他"(设置)合并, 设置在第一位
	// 【第三十五轮】科大官网右边新增"学校邮箱"
	// 【第六十八轮·二·按要求】"设置里面的桌面小组件移出来, 单独在其他里面开一个功能叫『小组件』,
	// 位置放在饮水码和科大官网之间, 学校邮箱换到下一行" —— 宫格一行 4 个, 插到第 3 格后
	// 学校邮箱自然落到第二行(见 ContentGridView 的 `(pages.size + 3) / 4`)。
	private val other = arrayOf(
		SettingPage.SettingPage,
		DrinkPage.DrinkPage,
		WidgetPage.WidgetPage,
		SchoolWebPages.OfficialSitePage,
		WebPage.MailPage
	)

	val MyPage = Page(Keys.Page.MyPage, "我", iconRes = R.drawable.ic_login) { activity, padding, navController, _ ->
		MyPageUI(padding, activity.toast, navController)
	}

	@Composable
	fun MyPageUI(padding: PaddingValues, toast: Toast, navController: NavController) {
		val scrollState = rememberScrollState()
		val context = LocalContext.current

		// 头像旁状态: 与「账号管理」用**同一套判据**, 两处不能各说各话。
		// 【本轮修复】以前这里额外用"WebView 里有没有 wvpn 的 Cookie"来判定已登录 ——
		// 只要浏览器里残留任意一个 Cookie(哪怕只是登录页的临时 Cookie), 就显示"数字青科已登录",
		// 而账号管理里明明是"未登录"。现在统一按"账号 + 密码都已保存"判断。
		val easAccount = EASAccount.getInstance().getAccount()
		val ipassAccount = IPassAccount.getInstance().getAccount()
		// 【本轮】与账号管理共用同一个判据(密码键"存在"即算登过), 避免两处口径不一致
		val ipassLoggedIn = ipassAccount.isNotEmpty() &&
			com.qust.helper.ui.page.account.AccountManagerPage.hasSavedPassword(Keys.IPASS_PASSWORD)
		// 【第七十六轮·修"教务系统明明登录了却显示没登陆"】
		// 以前这里只认"账号名"(getAccount()): 账号名只在「教务登陆」(原网址直连)登录时落盘,
		// 走数字青科进教务的用户它恒为空; 而「数字青科登陆 → 教务管理系统」这条路径此前
		// 不落盘教务会话位 —— 两把钥匙都打不开, 头像旁边就显示"未登录"。
		// 现在加上 hasSession(教务两条通道任一还有会话, 与账号管理同口径):
		// 会话位有效或 Cookie 还在, 就算登录着。
		// 【第七十九轮·修"账号管理里存了教务账号密码,'我'页却显示未登录"】
		// 按"账号管理里有教务系统的账号和密码就算是登录成功了"的口径:
		// 存过教务账号+密码也计入已登录 —— 会话过期时不再误显示"未登录"。
		val easCredSaved = Setting.getString(Keys.EAS_ACCOUNT).isNotEmpty() &&
			com.qust.helper.ui.page.account.AccountManagerPage.hasSavedPassword(Keys.EAS_PASSWORD)
		val easLoggedIn = easCredSaved || easAccount.isNotEmpty() ||
			runCatching { EASAccount.getInstance().hasSession }.getOrDefault(false)
		val loggedIn = easLoggedIn || ipassLoggedIn
		val statusTitle = when {
			easLoggedIn -> easAccount.ifEmpty { "教务系统已登录" }
			ipassLoggedIn -> "数字青科已登录"
			else -> "未登录"
		}
		val statusDetail = when {
			easLoggedIn -> when {
				easAccount.isNotEmpty() -> "教务系统已登录"
				easCredSaved -> "已保存教务账号密码"
				else -> "教务会话有效"
			}
			ipassLoggedIn -> "已通过数字青科进入教务"
			else -> "请先登录"
		}

		// 【本轮·新增】自定义昵称: 设置了就显示昵称, 没设置回退到上面的状态文案
		var nickname by remember { mutableStateOf(Setting.getString(Keys.NICKNAME)) }
		var editNickname by remember { mutableStateOf(false) }
		// 【第七十八轮·按要求】一个小组件都没添加时, 点「小组件」先弹说明弹窗
		var widgetHint by remember { mutableStateOf(false) }
		val account = nickname.ifEmpty { statusTitle }

		// 【点击头像更换头像】从相册选择图片 -> 保存到应用私有目录 -> 立即显示
		var avatar by remember { mutableStateOf(loadAvatarBitmap(context)) }
		val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){ uri ->
			if(uri != null){
				saveAvatarFromUri(context, uri)?.let { avatar = it }
			}
		}

		Column(modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(scrollState)) {
			// 【第二十九轮·按要求】账号管理这一整行原来是裸 clickable, 反馈是"整个四方形 + 直角黑波纹";
			// 改成统一的 tapRipple(主题色波纹 + 12dp 倒角), 和「我」页下面那些功能格、文字按钮同一套手感。
			Box(
				Modifier
					.fillMaxWidth()
					.padding(horizontal = 16.dp)
					.tapRipple { Page.navigate(navController, Keys.Page.AccountManager) }
			){
				Row(verticalAlignment = Alignment.CenterVertically) {
					Box(modifier = Modifier.size(84.dp)) {
						val bmp = avatar
						if(bmp != null){
							Image(
								bitmap = bmp.asImageBitmap(),
								contentDescription = "头像",
								contentScale = ContentScale.Crop,
								modifier = Modifier
									.size(84.dp)
									.clip(CircleShape)
									.clickable { avatarPicker.launch("image/*") }
							)
						}else{
							Image(
								painter = painterResource(if(!loggedIn) R.drawable.icon_no_login_user else R.drawable.icon_login_user_male),
								contentDescription = "头像",
								modifier = Modifier
									.size(84.dp)
									.clickable { avatarPicker.launch("image/*") }
							)
						}
					}
					Column(modifier = Modifier.padding(start = 8.dp)){
						// 昵称(未设置时显示账号/未登录), 点一下改昵称
						//
						// 【第三十四轮·按要求】"上面两个角有点尖锐"的根因不是圆角半径, 而是**被父级裁掉了**:
						// 整行外面套着 `tapRipple{}`(波纹要圆角), 它内部是
						// `clip(RoundedCornerShape(12.dp))` —— 这是个**裁剪**修饰符, 会连子项一起裁。
						// 上一版为了让药丸的文字与下面两行左对齐, 用了 `offset(x = -12dp, y = -6dp)`:
						// 药丸被整体上移 6dp 后, 上边缘跑到了裁剪框外面(y = -2dp) —— 上面两个角正好在
						// 弧度最大处被"切平", 看起来就是"上面两个角尖、下面两个角圆"。
						// 水平方向的 -12dp 没问题(离裁剪框左边界还有 80dp), 所以**只去掉竖直那个偏移**。
						//
						// 现在药丸完全落在父级边界内(上边缘在 y≈3dp), 四个角都是完整的 20dp 半圆;
						// 高度锁定 40dp(= TextButton 的容器高), 形状/波纹/留白与「复制课程」完全一致。
						// 文字**横向**位置与上一版一字不差(仍是 Column 起点 + 0), 只是不再上移 6dp。
						Box(
							modifier = Modifier
								// 负偏移 + 等量内边距: 文字仍落在原位置(与下面两行左对齐), 只是点击区变大
								.offset(x = (-TEXT_ACTION_H_PADDING))
								// 药丸下面留 2dp: 药丸不再上移之后, 它的下边缘会正好贴着下一行的行框,
								// 补 2dp 才和"上下三行"的行距看起来一致(这 2dp 在点击区外面, 不缩小手指目标)
								.padding(bottom = 2.dp)
								.textAction { editNickname = true }
								.padding(horizontal = TEXT_ACTION_H_PADDING)
								.height(40.dp),
							contentAlignment = Alignment.Center
						) {
							Text(
								text = account,
								style = MaterialTheme.typography.titleLarge
							)
						}
						Text(
							text = statusDetail,
							style = MaterialTheme.typography.titleSmall,
							color = colorSecondaryText
						)
						Text(
							text = "点击头像可更换 · 点击昵称可修改",
							style = MaterialTheme.typography.labelSmall,
							color = colorSecondaryText
						)
					}
				}
				Icon(contentDescription = null, imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight, tint = colorSecondaryText, modifier = Modifier.align(Alignment.CenterEnd))
			}

			ContentGridView("课表", lesson, clickItem = { Page.navigate(navController, it) })
			ContentGridView("教务系统", eas, clickItem = { Page.navigate(navController, it) })
			ContentGridView("数字青科", web, clickItem = { Page.navigate(navController, it) })
			ContentGridView("其他", other, clickItem = { key ->
				// 【第七十八轮·按要求】桌面上一个小组件都没有时, 点「小组件」先弹说明弹窗,
				// 不直接进小组件页 —— 很多人卡在"桌面快捷方式权限"上, 先把话说清楚。
				if(key == WidgetPage.WidgetPage.key && !WidgetPage.hasAnyWidget(context)) widgetHint = true
				else Page.navigate(navController, key)
			})


			// 【本轮·新增】设置昵称: 保存到本地, 留空即恢复默认(显示账号/未登录)
			// 【本轮·按要求】输入阶段不再拦字数(以前超 12 字直接打不进去, 用户不知道发生了什么);
			// 改成允许继续输入, 但**超过 12 字点保存会被拒绝并弹气泡**, 由用户自己删到 12 字以内。
			if(editNickname){
				var input by remember { mutableStateOf(nickname) }
				val over = (input.trim().length - 12).coerceAtLeast(0)
				AlertDialog(
					onDismissRequest = { editNickname = false },
					title = { Text(text = "设置昵称") },
					text = {
						Column {
							OutlinedTextField(
								value = input,
								onValueChange = { input = it },
								singleLine = true,
								isError = over > 0,
								// 【本轮·按要求】标签只留「昵称」, 括号里的字数限制不再写在这里
								// (限制与"留空恢复默认"由输入框下方那行小字说明, 不重复占标签位置)
								label = { Text(text = "昵称") }
							)
							Text(
								text = if(over > 0) "已超出 $over 个字，最多 12 个字" else "最多 12 个字，留空则恢复默认",
								style = MaterialTheme.typography.labelSmall,
								color = if(over > 0) MaterialTheme.colorScheme.error else colorSecondaryText,
								modifier = Modifier.padding(start = 4.dp, top = 4.dp)
							)
						}
					},
					confirmButton = {
						TextButton(onClick = {
							val value = input.trim()
							if(value.length > 12){
								// 不关闭弹窗, 让用户直接接着删
								toast.toastWarning("昵称最多 12 个字，请再删掉 ${value.length - 12} 个字")
							}else{
								Setting.edit {
									if(value.isEmpty()) it.remove(Keys.NICKNAME) else it.putString(Keys.NICKNAME, value)
								}
								nickname = value
								editNickname = false
							}
						}) { Text(text = "保存") }
					},
					dismissButton = {
						TextButton(onClick = { editNickname = false }) { Text(text = "取消") }
					}
				)
			}

			// 【第七十八轮·按要求】「小组件」入口的说明弹窗: 一个都没添加时才出现
			if(widgetHint){
				AlertDialog(
					onDismissRequest = { widgetHint = false },
					title = { Text(text = "添加小组件") },
					text = {
						Text(text = "请在授权桌面快捷方式权限后，添加小组件，如果添加失败，请在手机系统的安卓小部件里面找到青科助手即可添加")
					},
					confirmButton = {
						TextButton(onClick = {
							widgetHint = false
							// 确认后仍进入小组件页: 里面有"一键钉到桌面"和系统检测, 添加不成就用它兜底
							Page.navigate(navController, WidgetPage.WidgetPage.key)
						}) { Text(text = "确认") }
					}
				)
			}

		}
	}

	@Composable
	private fun ContentGridView(title: String, pages: Array<Page>, clickItem: (String) -> Unit) {
		// 【第五十七轮·按要求】条目外观与「时间表管理」的卡片统一: 白底 + 6dp 阴影 + 8/4dp 外边距
		Card(
			modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
			colors = CardDefaults.cardColors(containerColor = Color.White),
			elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
		) {
			Text(text = title, fontWeight = FontWeight.W700, modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, bottom = 4.dp))

			repeat((pages.size + 3) / 4){ row ->
				Row(Modifier.fillMaxWidth()) {
					repeat(4){ col ->
						if(row * 4 + col < pages.size){
							val page = pages[row * 4 + col]
							Column(
								horizontalAlignment = Alignment.CenterHorizontally,
								modifier = Modifier
									.weight(1F)
									// 【本轮】功能按键的点击反馈补一个小倒角 + 主题色波纹,
									// 不再是"完整四方形 + 一圈黑". padding 放到里面, 波纹盖住整格。
									// 倒角取 10dp(不是文字按钮那种药丸形): 方形图标格上用药丸会变成椭圆, 反而更怪。
									.textAction(shape = RoundedCornerShape(10.dp)) { clickItem(page.key) }
									.padding(8.dp)
							) {
								Icon(
									painter = if(page.image != null) rememberVectorPainter(page.image) else painterResource(id = page.iconRes),
									contentDescription = page.name,
									// 【第三十七/六十二轮】多色图标(自带颜色)不做 tint; 个别单色图标用指定颜色; 其余走循环调色板
									tint = when {
										page.key in com.qust.helper.data.PageIconStyle.OWN_COLOR -> Color.Unspecified
										else -> com.qust.helper.data.PageIconStyle.colorOf(page.key, TEXT_COLORS)
									}
								)
								Text(text = page.name, style = MaterialTheme.typography.labelMedium)
							}
						}else{
							Spacer(modifier = Modifier.weight(1F))
						}
					}

				}

			}
			Spacer(modifier = Modifier.height(12.dp))
		}
	}
}

// ---------------- 自定义头像 ----------------

/** 自定义头像在应用私有目录里的文件名 */
private const val AVATAR_FILE = "user_avatar.png"

/** 读取自定义头像(没有则返回 null, 界面回退到默认头像) */
private fun loadAvatarBitmap(context: Context): Bitmap? = try {
	val file = context.getFileStreamPath(AVATAR_FILE)
	if(file != null && file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
}catch(_: Exception){ null }

/**
 * 从相册返回的 Uri 保存自定义头像(限制到 512px, 避免大图占用过多内存)
 *
 * @return 保存后的位图; 失败返回 null(界面保持原样)
 */
private fun saveAvatarFromUri(context: Context, uri: Uri): Bitmap? {
	return try {
		val src = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return null
		val max = 512F
		val scaled = if(src.width > max || src.height > max){
			val s = max / maxOf(src.width, src.height)
			Bitmap.createScaledBitmap(src, (src.width * s).toInt().coerceAtLeast(1), (src.height * s).toInt().coerceAtLeast(1), true)
		}else src
		context.openFileOutput(AVATAR_FILE, Context.MODE_PRIVATE).use { scaled.compress(Bitmap.CompressFormat.PNG, 90, it) }
		scaled
	}catch(_: Exception){ null }
}