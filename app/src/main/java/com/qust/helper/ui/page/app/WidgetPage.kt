/*
 * 青科助手（修改版） —— 基于开源项目 QUST-Assistant 修改而成
 *
 * 原项目版权所有 (C) 2891954521  (https://github.com/2891954521/QUST-Assistant)
 * 原始开源许可: GNU General Public License v3.0 (GPL-3.0), 许可原文见 LICENSE 文件, 保持完整未改动。
 *
 * 修改版版权所有 (C) 2026 zhangzhenzyc  (https://github.com/zhangzhenzyc)
 * 本修改版同样以 GPL-3.0 发布; 附加版权声明见 NOTICE.md —— 两份版权声明相互独立、同时生效。
 */

package com.qust.helper.ui.page.app

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import com.qust.helper.R
import com.qust.helper.data.Data
import com.qust.helper.data.Keys
import com.qust.helper.data.Page
import com.qust.helper.model.LessonTableRepository
import com.qust.helper.model.account.DrinkAccount
import com.qust.helper.ui.activity.ComposeActivity
import com.qust.helper.ui.appwidget.DailyLessonReceiver
import com.qust.helper.ui.appwidget.DrinkCodeReceiver
import com.qust.helper.ui.appwidget.TermLessonReceiver
import com.qust.helper.ui.appwidget.WidgetStyleStore
import com.qust.helper.ui.appwidget.WidgetTheme
import com.qust.helper.ui.page.account.AccountManagerPage
import com.qust.helper.ui.theme.colorSecondaryText
import com.qust.helper.ui.theme.textActionFilledColors

/**
 * 【本轮·重做】桌面小组件入口 + 自检
 *
 * 背景: 三个小组件(当日课表 / 学期课表 / 饮水码)一直在 AndroidManifest 里注册着,
 * 但不少机型(尤其国产 ROM)的"添加小部件"面板里根本翻不到它们。
 * 应用内没法强制别的启动器把它列出来, 所以这一页做两件事:
 *
 * 1. **一键钉到桌面**(Android 8.0+ 的 `requestPinAppWidget`), 绕过面板;
 * 2. **把系统真实状态摊开给用户看**: 系统到底识别到了几个、当前桌面是哪个、
 *    这个桌面支不支持"一键添加"。这样"找不到"到底卡在哪一环一目了然,
 *    而不是让用户对着一句"请重启手机"反复试。
 */
object WidgetPage {

	val WidgetPage = Page(Keys.Page.WidgetPage, "小组件", iconRes = R.drawable.ic_widget_card_blue) { activity, padding, navController, _ ->
		WidgetUI(padding, activity.toast, navController)
	}

	/**
	 * 【第七十八轮·按要求】桌面上是不是**一个小组件都没有**。
	 *
	 * 「我 → 小组件」在没有任何实例时先弹说明弹窗(见 MyPage), 判据就是这里:
	 * 三个 provider 逐个问系统要实例 ID(`getAppWidgetIds`), 有一个就算"添加过"。
	 * 查询失败(系统服务异常)按"没添加"处理 —— 弹个说明窗没有副作用。
	 */
	fun hasAnyWidget(context: Context): Boolean {
		val manager = runCatching { AppWidgetManager.getInstance(context) }.getOrNull() ?: return false
		for(entry in ENTRIES) {
			val ids = runCatching { manager.getAppWidgetIds(ComponentName(context, entry.cls)) }.getOrNull()
			if(ids != null && ids.isNotEmpty()) return true
		}
		return false
	}

	/** 三个小组件的静态描述 */
	private class Entry(
		val title: String,
		val desc: String,
		val cls: Class<*>,
		val needsDrinkLogin: Boolean = false,
		val needsLessonTable: Boolean = false
	)

	/**
	 * 三个小组件的入口配置。
	 *
	 * 【第六十七轮·按要求】"将饮水码与每日课表位置互换，桌面小组件的副标题也要互换"。
	 *
	 * 换的是**整条 Entry**(标题 + 副标题 + 目标组件 + 前置条件一起搬), 而不是只挪
	 * 标题那一行 —— 只挪标题会出现"饮水码 · 显示今天的课程与节次"这种错配。
	 * 顺序: 饮水码 → 本周课表 → 今日课表。
	 */
	private val ENTRIES = listOf(
		// 【第九十八轮·按要求】副标题追加烧屏提示: 饮水码小组件是整卡静态位图,
		// 长时间固定显示高对比条码, 启动器滑屏的硬件层缓存帧叠加会让残影更明显。
		Entry("饮水码", "显示饮水码，同步刷新，请勿长时间静止显示饮水码，防止烧屏", DrinkCodeReceiver::class.java, needsDrinkLogin = true),
		Entry("本周课表", "显示本周课表，每周自动刷新", TermLessonReceiver::class.java, needsLessonTable = true),
		Entry("今日课表", "显示今天的课程与节次，每日自动刷新", DailyLessonReceiver::class.java, needsLessonTable = true)
	)

	@Composable
	fun WidgetUI(padding: PaddingValues, toast: com.qust.helper.ui.widget.Toast, navController: NavController? = null) {
		val context = LocalContext.current

		/**
		 * 【第三十一轮·修"点『登录后添加』没有跳到饮水码登录界面"】
		 *
		 * 这一页既可能从**主界面**(有 NavHost)进来, 也可能从**单页宿主**进来 —— 设置里点
		 * "桌面小组件"走的就是 `ComposeActivity.startActivity`, 那里根本没有导航图,
		 * `navController.navigate(...)` 什么也不会发生(以前就是"点了没反应")。
		 * 现在统一走这里: 导航图在就用导航图, 不在就拉起主界面并直接开到那一页。
		 */
		fun openPage(key: String) {
			val target = Page.resolve(key)
			// 底部导航的页(学期课表/我)一律交给主界面切页签, 不要另开"带返回箭头的单页"
			if(Data.bottomPage.any { it.key == target }) {
				context.startActivity(ComposeActivity.mainIntentFor(context, target))
				return
			}
			val nc = navController
			if(nc != null && nc.currentDestination != null) {
				if(nc.currentDestination?.route != target) {
					nc.navigate(target) {
						popUpTo(target) { inclusive = true }
						launchSingleTop = true
					}
				}
			}else{
				context.startActivity(ComposeActivity.mainIntentFor(context, target))
			}
		}

		// 点"重新检测"就 +1, 让下面的状态重新读一遍系统
		var nonce by remember { mutableIntStateOf(0) }

		// 【第四十三轮·修"状态不对"】页面停在栈里时系统状态会变(比如刚从桌面加完组件回来),
		// 只在首次组合读一次就会显示过期结果(用户看到"还能添加", 点一下重组才变成"已添加")。
		// 每次回到前台(ON_RESUME)都 +1 强制重读。
		val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
		DisposableEffect(lifecycleOwner) {
			val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
				if(event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) nonce++
			}
			lifecycleOwner.lifecycle.addObserver(observer)
			onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
		}

		val manager = remember(context) { AppWidgetManager.getInstance(context) }

		/**
		 * 【第五十八轮·按要求】"点击重新检测是否成功要给出气泡提示, 并告诉我失败的可能原因"
		 *
		 * 直接问系统要一次真实状态(不走 recomposition 的 remember), 所以点下去立刻就知道结果:
		 *  · 全部识别 -> toastOK, 顺带说几个已经在桌面上了;
		 *  · 有没识别到的 -> toastWarning, 把最常见的三个原因写进气泡
		 *    (覆盖安装后启动器缓存没刷新 / 系统冻结了组件服务 / 当前启动器不支持小组件)。
		 * 不识别 ≠ 装坏了: `getInstalledProviders` 是启动器侧的列表, 它不刷新时这里就查不到。
		 */
		fun detectAndReport() {
			val providers = runCatching { manager.getInstalledProviders().map { it.provider }.toSet() }
				.getOrDefault(emptySet())
			val known = ENTRIES.count { ComponentName(context, it.cls) in providers }
			val onHome = ENTRIES.count { entry ->
				runCatching { manager.getAppWidgetIds(ComponentName(context, entry.cls)).isNotEmpty() }.getOrDefault(false)
			}
			val total = ENTRIES.size
			if(known == total) {
				toast.toastOK(
					if(onHome > 0) "检测完成：$known/$total 个小组件已被系统识别，其中 $onHome 个已在桌面"
					else "检测完成：$known/$total 个小组件已被系统识别"
				)
			}else{
				// 气泡不宜太长, 只给"最可能的那一条 + 共 3 条"的骨架, 细节见本页下方的说明
				val first = when {
					known == 0 -> "系统一个都没读到，通常是刚覆盖安装、桌面还没重新扫描"
					else -> "有 ${total - known} 个没读到，通常是桌面缓存未刷新"
				}
				toast.toastWarning("检测完成：仅识别 $known/$total。$first；也可能是系统冻结了组件服务，或当前启动器不支持小组件")
			}
		}

		// 【第四十二轮·按要求】桌面**已经有**这个小组件时禁止重复添加:
		// `getAppWidgetIds` 返回该 provider 当前在桌面上的全部实例, 有一个就算"已添加"。
		fun isOnHome(entry: Entry): Boolean = runCatching {
			manager.getAppWidgetIds(ComponentName(context, entry.cls)).isNotEmpty()
		}.getOrDefault(false)
		val installed = remember(nonce, context) {
			runCatching { manager.getInstalledProviders().map { it.provider }.toSet() }
				.getOrDefault(emptySet())
		}
		val knownCount = ENTRIES.count { ComponentName(context, it.cls) in installed }

		// 【本轮】饮水码小组件只有登录后才有意义: 没登录时加进去就是一块"请先登录"的空白,
		// 所以先在入口挡住, 直接把人送到饮水登录页。
		val drinkReady = remember(nonce) {
			runCatching {
				AccountManagerPage.hasSavedPassword(Keys.DRINK_PASSWORD) ||
					DrinkAccount.getInstance().drinkCode.isNotEmpty()
			}.getOrDefault(false)
		}

		// 【本轮】课表全空时, 前两个小组件加进去只会是"今天没有课"/一片空白, 同样在入口挡住
		val tableReady = remember(nonce) {
			runCatching { LessonTableRepository.lessonTable.activeSlotCount() > 0 }.getOrDefault(false)
		}

		// 【本轮】点了"添加到桌面"之后, 统一走这个确认框(里面再判断权限)
		var askShortcutFor by remember { mutableStateOf<Entry?>(null) }

		Column(
			modifier = Modifier
				.fillMaxSize()
				.padding(padding)
				.verticalScroll(rememberScrollState())
		) {
			// —— 自检卡片 ——
			// 【第三十二轮·按要求】只保留三样: 识别结果、重新检测、一句怎么添加的说明;
			// 版本/系统/机型/桌面/"复制自检信息"全部去掉。
			Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
				Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
					Text(text = "系统识别情况", style = MaterialTheme.typography.titleMedium)
					Text(
						text = "已识别 $knownCount / ${ENTRIES.size} 个小组件",
						style = MaterialTheme.typography.bodyMedium,
						color = if(knownCount == ENTRIES.size) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
					)
					Text(
						text = "授予本软件桌面快捷方式权限，之后点下面的按钮可以添加到桌面，更改桌面布局后请重新添加小组件",
						style = MaterialTheme.typography.bodySmall,
						color = colorSecondaryText
					)
					Row(modifier = Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
						// 【第五十四轮·按要求】「重新检测」与其他按钮同一套样式: 蓝色填充 + 白字, 取消灰描边
						// 【第五十八轮·按要求】"重新检测是否成功要给出气泡提示, 并说明失败的可能原因":
						// 以前点完只是 nonce++, 界面上的数字变没变全靠自己盯 —— 现在点一下就把
						// 系统真实状态读一遍, 用气泡说清"识别到几个", 没识别到的把常见原因列出来。
						TextButton(onClick = {
							nonce++
							detectAndReport()
						}, colors = textActionFilledColors()) { Text(text = "重新检测") }
					}
				}
			}

			// —— 高度校准 ——
			// 【第一百一十一轮·v238】像素级定案: ColorOS 在更改桌面布局(如 4×5 → 5×6)后
			// **不更新**已放置小组件的 options(SIZES/MIN/MAX 全是旧布局的值: 上报 320×462dp,
			// 物理实测只有 ≈259×428dp), 且 resizeMode=none 无法靠长按缩放触发刷新 ——
			// 按 462dp 排版的固定行高在 428dp 的物理卡里正好溢出一行(第 11 节被裁)。
			// 启动器重新测量根布局时**横向权重列会自动适应**(所以列永远看得全),
			// 纵向行高是固定 dp, 只能在排版源头按比例预留 —— 这就是本卡的四个档位。
			// options 内部互相印证(陈旧的 SIZES 与同样陈旧的 MAX 一致), 代码无法自动判别,
			// 只能交给用户标定: 被裁就调低一档, 空白大了就调高一档。
			// 【第一百一十二轮·v239】默认档 100 → 92(用户实测"默认的不行, 92%刚刚好"),
			// 已设过的用户仍以落盘值优先, 默认值只影响没动过这个设置的设备。
			// 【第一百一十三轮·v240】学期课表改为纵向权重自适应(表格行 defaultWeight 均分
			// 物理剩余高度), 任何布局下都自动贴合 —— 本档位对学期课表**不再起作用**,
			// 现在只影响今日课表的时间轴高度与课程字号估算。
			var heightCal by remember { mutableStateOf(WidgetStyleStore.heightCal()) }
			Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
				Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
					Text(text = "小组件高度校准", style = MaterialTheme.typography.titleMedium)
					Text(
						text = "学期课表小组件已自适应任何桌面布局（5×6 / 4×6 等自动贴合，无需再调）。此档位现只影响【今日课表】小组件：若其最后一节被卡底裁掉，选低一档；若底部空白明显，选高些。改完小组件会自动按新比例重画。当前默认 92%（按实测标定）。",
						style = MaterialTheme.typography.bodySmall,
						color = colorSecondaryText
					)
					Row(modifier = Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
						listOf(100 to "100%", 96 to "96%", 92 to "92%（默认）", 88 to "88%").forEach { (pct, label) ->
							TextButton(
								onClick = {
									heightCal = pct
									WidgetStyleStore.setHeightCal(pct)
									WidgetStyleStore.refreshAll(context)
									toast.toastOK(if(pct == 100) "高度校准已设为 100%（不校准），小组件正在重新渲染" else "高度校准已设为 $label，小组件正在重新渲染")
								},
								colors = if(heightCal == pct) textActionFilledColors() else ButtonDefaults.textButtonColors()
							) { Text(text = label) }
						}
					}
				}
			}

			// —— 渲染诊断 ——
			// 【第一百零五轮】OPPO/ColorOS 的小组件尺寸上报(SIZES / MIN/MAX / LocalSize)
			// 各不可信且比例随卡片规格漂移, 远程截图连猜四轮未收敛 —— 把小组件每次渲染
			// 拿到的原始数值记进内存(WidgetDiag), 这里直接展示, 用户截图发来即可用
			// 真实数字定校准系数, 不再盲调。只记数值, 不含课表内容等隐私。
			Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
				Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
					// 【第一百零五轮·v232·按要求加复制按钮】诊断文本多行换行, 截图容易看串行 ——
					// 一键复制进剪贴板, 发文字就能精确对上每个数值。
					Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
						Text(text = "渲染诊断", style = MaterialTheme.typography.titleMedium)
						if(!com.qust.helper.utils.WidgetDiag.isEmpty) {
							Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
								// 【第一百零五轮·v237·按要求加刷新】不改数据也让全部小组件立刻重画一遍:
								// 桌面上重新渲染后回到本页, 新日志条目立即出现 —— 不用再"删掉小组件重加"。
								// 【第一百一十二轮·v239·按要求刷新先清空旧日志】诊断是 60 行的环形缓冲,
								// 一次刷新要写 ~20 行: 旧日志不清, 第二次刷新的新行会把第一次的挤出去一半,
								// 复制出来的就不全。先 clear() 再触发, 缓冲里只留本轮的新行。
								TextButton(onClick = {
									com.qust.helper.utils.WidgetDiag.clear()
									com.qust.helper.model.LessonTableRepository.refreshLessonWidgetsForTimeTable()
									android.widget.Toast.makeText(context, "已清空旧日志，正在让小组件重新渲染，稍等几秒再复制", android.widget.Toast.LENGTH_SHORT).show()
								}, colors = textActionFilledColors()) { Text(text = "刷新") }
								TextButton(onClick = {
									val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
									cm.setPrimaryClip(android.content.ClipData.newPlainText("widget_diag", com.qust.helper.utils.WidgetDiag.dump()))
									android.widget.Toast.makeText(context, "已复制渲染诊断", android.widget.Toast.LENGTH_SHORT).show()
								}, colors = textActionFilledColors()) { Text(text = "复制") }
							}
						}
					}
					Text(
						text = if(com.qust.helper.utils.WidgetDiag.isEmpty) "还没有渲染记录 —— 添加小组件或等它刷新一次后回来"
						else com.qust.helper.utils.WidgetDiag.dump(),
						style = MaterialTheme.typography.bodySmall,
						fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
					)
				}
			}


			// —— 外观: 主题 + 非课程文字颜色 ——
			//
			// 用户原话: "桌面小组件添加的按钮旁可选择小组件的主题" -> 后来又补了 4 套:
			// "深色玻璃 / 简约线条 / 哑光卡片 / 亚克力"。一共 6 套, 见 [WidgetTheme]。
			//
			// 放在三个「添加到桌面」按钮**正上方**, 一个页面只有一处 —— 而不是每张卡里各放一份:
			// 三个小组件共用同一套外观(否则桌面上并排摆着两种质感的卡, 比"没得选"还难看)。
			// 每次点选都立刻写盘 + 让桌面重画(refreshAll), 不需要"保存"按钮。
			var widgetTheme by remember { mutableStateOf(WidgetStyleStore.theme()) }
			var widgetTextColor by remember { mutableIntStateOf(WidgetStyleStore.textColor()) }

			Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
				Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
					// 【第六十七轮·按要求】这里原本有三样东西, 全部删除:
					//  ① 「重绘」按钮 —— "删除重绘功能";
					//  ② "下面三个小组件共用这套外观，改完立刻生效";
					//  ③ "当前：纯色 · v3.6.0166-mod（66）"。
					// ②③ 都是第六十五轮为了排查"主题切不动"临时加的说明文字,
					// 现在小组件能正常切换了, 留在页面上只是噪音。
					Text(text = "小组件外观", style = MaterialTheme.typography.titleMedium)

					// —— 主题: 六套, 每行 3 个, **带真实底色预览** ——
					//
					// 【第六十五轮】上一版是 6 个纯文字按钮 —— 用户根本看不出"哑光 / 亚克力 / 玻璃"
					// 差在哪, 也就没法判断切换有没有生效(他连问了几轮"还是不能切换")。
					// 现在每个按钮就是一张**缩小的卡片**: 底下垫一条彩色渐变当"壁纸",
					// 上面盖这个主题真实的 drawable, 于是"透不透、白不白、有没有边"一眼可比。
					//
					// 预览用 AndroidView + ImageView.setBackgroundResource: 直接加载真正的 layer-list,
					// 而不是在 Compose 里重画一遍近似效果 —— 预览必须和桌面**逐像素**同源才可信。
					Text(
						text = "主题",
						style = MaterialTheme.typography.titleSmall,
						modifier = Modifier.padding(top = 6.dp)
					)
					WidgetTheme.values().toList().chunked(3).forEach { line ->
						Row(
							modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
							horizontalArrangement = Arrangement.spacedBy(8.dp),
							verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
						) {
							line.forEach { theme ->
								ThemePreview(
									theme = theme,
									selected = widgetTheme == theme,
									modifier = Modifier.weight(1f)
								) {
									if(widgetTheme == theme) return@ThemePreview
									widgetTheme = theme
									// 【第六十三轮】自选色和新主题"打架"时会被 setTheme 内部作废
									// (白字 + 白卡那种整张卡文字消失的情况, 用户截图里已经出现过)。
									// 这里先记一笔, 好把原因说清楚, 而不是让用户以为"我的颜色丢了"。
									val hadCustom = WidgetStyleStore.hasCustomColor()
									WidgetStyleStore.setTheme(theme)
									val cleared = hadCustom && !WidgetStyleStore.hasCustomColor()
									// 主题决定"默认文字色": 换到深色玻璃时, 没自选过颜色的用户
									// 应该立刻看到白色, 而不是还停在深黑 —— 否则设置页和桌面都对不上。
									widgetTextColor = WidgetStyleStore.textColor()
									WidgetStyleStore.refreshAll(context)
									toast.toastOK(
										if(cleared) "已切换为「${theme.label}」，原来的文字颜色在这个主题上看不清，已改回默认"
										else "已切换为「${theme.label}」主题"
									)
								}
							}
							// 补满整行: 最后一行不满 3 个时, 前面的方块宽度才不会跳变
							repeat(3 - line.size) { Spacer(modifier = Modifier.weight(1f)) }
						}
					}
					// 【第六十七轮·按要求】"删除对主题的解释, 比如『不透明，任何壁纸都清晰』"。
					// 以前这里会显示 widgetTheme.desc —— 现在 WidgetTheme.desc 全部清空,
					// 这段也一并删掉(留一个空的 Text 会在卡片底部白占一行高度)。

					// —— 非课程文字颜色 ——
					//
					// "非课程"这三个字很要紧: 只改周次/日期/时间栏/空态提示这些字。
					// 课程色块里的课名/教室/教师**不跟着变** —— 它们压在彩色底上,
					// 统一成黑色会让"哪门课"全靠底色区分, 反而更难认。
					//
					// 【第六十二轮·按要求】"删除全部颜色, 改成七彩加黑白灰*(深/中/浅) 共30种颜色"
					//  -> 7 色 × 3 档(21) + 黑/灰/白 × 3 档(9) = 30, 见 WidgetStyleStore.TEXT_PRESETS。
					//     上一版最后那个"炫彩"已经删掉: 它是按位置铺彩虹, 在 11 节的时间栏上会把
					//     "1 / 08:00 / 08:45" 三行染成三个颜色, 反而更难扫读。
					//
					// 【第六十三轮·按要求重排】"黑白灰颜色也竖着排，一行10个颜色"。
					// 色板现在是 **3 行 × 10 列**: 列 = 色族(红橙黄绿青蓝紫 + 黑灰白),
					// 行 = 档位(深/中/亮)。所以黑白灰也是竖着的一列, 每行正好 10 个。
					// 布局用 `Modifier.weight(1f)`, 10 个圆点自动均分一行的宽度 —— 不用再写
					// "28dp × 10 + 8dp × 9 = 352dp"这种算死的宽度(窄屏必撑爆)。
					Row(
						modifier = Modifier.padding(top = 6.dp),
						horizontalArrangement = Arrangement.spacedBy(6.dp),
						verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
					) {
						Text(
							text = "非课程文字颜色",
							style = MaterialTheme.typography.titleSmall
						)
						// 【第六十七轮·按要求】"删除『黑·中』前面的点" —— 只留颜色名本身
						Text(
							text = WidgetStyleStore.textColorName(widgetTextColor),
							style = MaterialTheme.typography.bodySmall,
							color = colorSecondaryText
						)
					}
					Text(
						// 【第六十七轮·按要求】原文案是"只改周次、日期、时间栏和提示文字；
						// 课程色块里的课名/教室不跟着变", 改成下面这句更明确的表述。
						text = "仅改变周次、日期、时间栏和提示文字颜色；不改变课程色块里的文字颜色",
						style = MaterialTheme.typography.bodySmall,
						color = colorSecondaryText
					)
					Column(
						modifier = Modifier.padding(top = 8.dp),
						verticalArrangement = Arrangement.spacedBy(8.dp)
					) {
						WidgetStyleStore.TEXT_PRESETS.indices
							.chunked(WidgetStyleStore.PALETTE_COLUMNS)
							.forEach { line ->
								Row(
									modifier = Modifier.fillMaxWidth(),
									horizontalArrangement = Arrangement.spacedBy(4.dp),
									verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
								) {
									line.forEach { index ->
										val argb = WidgetStyleStore.TEXT_PRESETS[index]
										val selected = widgetTextColor == argb
										Box(
											modifier = Modifier.weight(1f),
											contentAlignment = androidx.compose.ui.Alignment.Center
										) {
											Box(
												modifier = Modifier
													.size(26.dp)
													.clip(CircleShape)
													.background(Color(argb))
													.border(
														width = if(selected) 3.dp else 1.dp,
														color = if(selected) MaterialTheme.colorScheme.primary else Color(0x22000000),
														shape = CircleShape
													)
													.clickable {
														widgetTextColor = argb
														WidgetStyleStore.setTextColor(argb)
														WidgetStyleStore.refreshAll(context)
														toast.toastOK("文字颜色已改为${WidgetStyleStore.TEXT_PRESET_NAMES[index]}")
													}
											)
										}
									}
									// 最后一行不满 10 个时补足, 免得圆点被拉宽
									repeat(WidgetStyleStore.PALETTE_COLUMNS - line.size) {
										Spacer(modifier = Modifier.weight(1f))
									}
								}
							}
					}
				}
			}

			// —— 三个小组件 ——
			ENTRIES.forEach { entry ->
				val known = ComponentName(context, entry.cls) in installed
				// 【第四十二轮·按要求】桌面已有实例时禁止再次添加(防重复)
				val onHome = isOnHome(entry)
				// 【本轮】两类前置条件: 饮水码要先登录、课表类要先把课表导进来
				val blockedByDrink = entry.needsDrinkLogin && !drinkReady
				val blockedByTable = entry.needsLessonTable && !tableReady
				Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
					Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
						Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
							Text(text = entry.title, style = MaterialTheme.typography.titleMedium)
							Text(
								text = if(onHome) "（已在桌面）" else if(known) "（系统已识别）" else "· 系统未识别",
								style = MaterialTheme.typography.bodySmall,
								color = if(known || onHome) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
								modifier = Modifier.padding(top = 4.dp)
							)
						}
						Text(text = entry.desc, style = MaterialTheme.typography.bodySmall, color = colorSecondaryText)
						if(blockedByTable && !onHome) {
							Text(
								text = "课表还是空的。这两个小组件的内容都来自课表，请先在「课表」里完成一次查询，再来添加。",
								style = MaterialTheme.typography.bodySmall,
								color = MaterialTheme.colorScheme.error
							)
						}
						Button(
							modifier = Modifier.padding(top = 6.dp),
							enabled = !onHome,
							// 【第五十五轮·按要求】"添加到桌面"的填充色改成与外头文字按钮同一款浅蓝
							colors = ButtonDefaults.buttonColors(
								containerColor = com.qust.helper.ui.theme.TEXT_ACTION_FILLED_BLUE,
								contentColor = androidx.compose.ui.graphics.Color.White
							),
							onClick = {
								when {
									blockedByDrink -> {
										// 【第八十九轮·按要求】不再弹"请先登录饮水账号"气泡:
										// 直接进饮水码登录页, 登录成功后自动把饮水码小组件钉到桌面。
										com.qust.helper.data.LoginEntry.autoAddDrinkWidget = true
										openPage(Keys.Page.DrinkLogin)
									}
									blockedByTable -> {
										toast.toastWarning("课表为空，请先查询一次课表")
										openPage(Keys.Page.TermLessonPage)
									}
									// 【第四十四轮·按要求】未授权时点「添加到桌面」: 气泡提示 + 直接跳到
									// 本应用的系统设置页(权限管理), 不再弹解释对话框。
									else -> {
										if(!isShortcutOpAllowed(context)) {
											toast.toastWarning("请打开桌面快捷方式权限")
											openAppSettings(context)
										}else if(!runCatching { manager.isRequestPinAppWidgetSupported }.getOrDefault(false)) {
											// 【第七十轮·修"OPPO 开全权限后软件内添加小组件失败"】
											// 用户实测: OPPO(ColorOS)上权限全开, requestPinAppWidget 仍然静默失败,
											// 但从桌面"长按 → 小组件"里手动添加是成功的。也就是说这台启动器
											// 根本不支持 requestPin(或对第三方不开放), 权限页跳一百次也没用。
											// 这里在点按钮的**当下**就检测(不再等到失败后), 直接给手动添加指引,
											// 不再把用户引去开一个不解决问题的权限开关。
											toast.toastWarning("当前桌面不支持一键添加：请回到桌面长按空白处 → 小组件，找到「青科助手」后手动添加")
										}else askShortcutFor = entry
									}
								}
							}
						) {
							Text(
								text = when {
									onHome -> "已添加到桌面"
									blockedByDrink -> "登录后添加"
									blockedByTable -> "先导入课表"
									else -> "添加到桌面"
								}
							)
						}
					}
				}
			}

			// 【第三十二轮·按要求】删除整块「手动添加」说明。
		}

		// 【本轮】点了「添加到桌面」之后的确认框(此时必有桌面快捷方式权限,
		// 未授权的已在按钮点击时被直接送去系统设置页)。
		askShortcutFor?.let { entry ->
			val doPin = {
				// 【第四十四轮·按要求】先把用户**送回桌面**, 再发起添加:
				// 系统确认框会弹在桌面**当前页**上, 用户点「添加」后组件就落在眼前这一页 ——
				// 成功回调(见 pinToHome)也会在这一刻把用户带回桌面, 正是组件所在的页。
				runCatching {
					context.startActivity(
						Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
							.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
					)
				}
				android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
					runCatching {
						val known = ComponentName(context, entry.cls) in installed
						val (ok, msg) = pinToHome(context, entry.cls, known)
						// 【第四十七轮】pin 被系统拒绝(多数是桌面快捷方式权限没开, MIUI 不弹确认框也不说明):
						// 给出气泡并直接跳到权限管理页, 不再静默失败。
						// 【第六十七轮·按要求】气泡文案固定成这一句。
						// 用户实测: 小米上系统根本不弹"添加失败"的提示, OPPO 弹了但开了权限仍然失败 ——
						// 也就是说失败原因十有八九就是"桌面快捷方式权限", 那就不必再把 $msg 里的
						// 内部原因(系统没识别/不支持 requestPin)直接甩给用户。
						if(!ok) {
							android.widget.Toast.makeText(context, "添加失败，请打开桌面快捷方式权限后重试", android.widget.Toast.LENGTH_LONG).show()
							openAppSettings(context)
						}
					}
				}, 800L)
			}
			AlertDialog(
				onDismissRequest = { askShortcutFor = null },
				title = { Text(text = "添加到桌面") },
				text = {
					Text(
						text = "将把「${entry.title}」放到桌面。在桌面弹出的弹窗点击「添加」即可",
						style = MaterialTheme.typography.bodySmall
					)
				},
				confirmButton = {
					TextButton(onClick = {
						askShortcutFor = null
						doPin()
					}) { Text(text = "确定") }
				},
				dismissButton = {
					TextButton(onClick = { askShortcutFor = null }) { Text(text = "取消") }
				}
			)
		}
	}

	/**
	 * 【第六十五轮】主题预览卡: 一张"缩小的真卡片"。
	 *
	 * 三层的叠法就是桌面上的真实叠法 ——
	 *   ① 底下是一条彩色渐变, 充当"壁纸"(预览里必须有东西可透, 否则玻璃和纯色看着一样);
	 *   ② 上面盖这个主题**真实的 drawable**(`setBackgroundResource`, 与小组件同源);
	 *   ③ 中间压一行主题名, 深色主题自动用白字。
	 *
	 * 为什么用 AndroidView 而不是 Compose 里重画: layer-list 里的 alpha 叠加、径向高光、
	 * 渐变角度, 自己重画一遍必然走样 —— 预览一旦和桌面不一致, 就会把用户引到错误的选择上。
	 */
	@Composable
	private fun ThemePreview(
		theme: WidgetTheme,
		selected: Boolean,
		modifier: Modifier = Modifier,
		onClick: () -> Unit
	) {
		val shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
		Box(
			modifier = modifier
				.height(52.dp)
				.clip(shape)
				.clickable { onClick() }
		) {
			// ① 【第七十一轮】底下的"壁纸"改成六色明亮彩条(横向分段渐变),
			//    与用户描述的"彩色条纹背景"一致, 六个按钮都按这个假设来填:
			//    粉 → 紫 → 蓝 → 青绿 → 暖黄 → 橘。饱和度整体调高, 透出来的
			//    颜色更接近真实壁纸观感。
			Box(
				modifier = Modifier.matchParentSize().background(
					Brush.linearGradient(
						listOf(
							Color(0xFFF6C9DC),
							Color(0xFFC9B6F4),
							Color(0xFF9CD0FF),
							Color(0xFFA8EFD8),
							Color(0xFFFFE9A8),
							Color(0xFFFFB9A3)
						)
					)
				)
			)
			// ② 这个主题**真实的** drawable, 与桌面逐像素同源。
			//    【第七十二轮·按要求"磨砂玻璃和深色玻璃的按钮填充不够透"】两套玻璃在
			//    预览里各让一步透明度: 桌面上玻璃卡背后是**糊过的壁纸**(亮度被启动器拉高),
			//    预览里只有一条高饱和彩条 —— drawable 原样叠上去就显得比桌面"实"。
			//    用 Drawable.setAlpha 把预览里的卡底乘一个系数(必须 mutate(),
			//    layer-list 是共享缓存对象, 直接改会污染桌面真正在用的那一份):
			//    磨砂玻璃 ×0.74、深色玻璃 ×0.70 —— 透出更多彩条, 接近桌面真实观感。
			AndroidView(
				modifier = Modifier.matchParentSize(),
				factory = { ctx -> PreviewCardView(ctx) },
				update = { v ->
					v.bind(
						theme.background,
						when(theme) {
							WidgetTheme.ACRYLIC -> 0.78F
							WidgetTheme.GLASS -> 0.74F
							WidgetTheme.DARK_GLASS -> 0.70F
							else -> 1F
						}
					)
				}
			)
			// ②.5 【第七十二轮·按要求"磨砂玻璃的填充增加透光"】白雾从 0x66 降到 0x3D(24%),
			//    透光带也整体调淡 —— 上一轮的 0x66(40%) 白雾把彩条盖得只剩个影子, 不符合
			//    "玻璃"的透。桌面观感以"能明显看到壁纸色彩"为准, 白雾只负责提亮。
			if(theme == WidgetTheme.GLASS) {
				Box(modifier = Modifier.matchParentSize().background(Color(0x3DFFFFFF)))
				Box(
					modifier = Modifier.matchParentSize().background(
						Brush.linearGradient(
							0F to Color(0x42FFFFFF),
							0.45F to Color(0x00FFFFFF),
							1.0F to Color(0x21FFFFFF),
							start = Offset(0F, Float.POSITIVE_INFINITY),
							end = Offset(Float.POSITIVE_INFINITY, 0F)
						)
					)
				)
			}
			//  · 【第七十一轮·按要求】透明简约: 预览里**什么都不垫**, 直接露出底下
			//    的彩色条纹 —— 与桌面上"完全不画、纯透壁纸"的真实表现一致。
			//    (第七十轮曾用灰白棋盘格表示"透明", 用户明确要求去掉格子。)
			Text(
				text = theme.label,
				style = MaterialTheme.typography.labelLarge,
				color = if(theme.dark) Color.White else Color(0xFF1A1A1A),
				maxLines = 1,
				modifier = Modifier.align(androidx.compose.ui.Alignment.Center)
			)
			// ③ 选中环**必须最后画**。
			//    以前它是 Box 自己的 border, 画在子内容**下面** —— 卡底一旦填满,
			//    蓝色的选中框就被盖住了(用户看不出来选了哪一套)。现在单独叠一层。
			Box(
				modifier = Modifier
					.matchParentSize()
					.border(
						width = if(selected) 2.5.dp else 1.dp,
						color = if(selected) com.qust.helper.ui.theme.TEXT_ACTION_FILLED_BLUE else Color(0x22000000),
						shape = shape
					)
			)
		}
	}

	/**
	 * 【第六十七轮·按要求】"主题里面的按钮填充要填充完全，不要有空隙"。
	 *
	 * 空隙是两个原因一起造成的, 两个都在这里解决:
	 *
	 *  ① **外阴影内缩**。六套卡底都是 layer-list, 靠"卡片本体向右/下缩 1~2dp, 让垫底的
	 *     暗层露出来"来做外阴影 —— 这些缩进在**桌面**上是对的(那一圈就是影子),
	 *     但在一个 52dp 高的预览方块里, 就成了一圈"透出彩色壁纸的缝"。
	 *  ② **圆角半径不匹配**。卡底圆角是 @dimen/widget_corner_radius(22dp), 而预览块只有
	 *     52dp 高、圆角 10dp —— 卡底那 22dp 的圆角落在预览块内部, 四个角上又露出一块壁纸。
	 *
	 * 解法不是"再画一层颜色去补", 而是**把卡底放大后居中裁切**:
	 * 绘制边界向外扩 30% 宽 / 55% 高, 于是卡底的内缩圈和 22dp 圆角全部落到可视区之外,
	 * 眼睛里只剩下卡片的**中段平面** —— 边缘贴着预览块的四边, 一个像素的缝都没有。
	 * 放大的副作用只是"纹理更粗一点", 对判断"透不透、白不白"没有影响。
	 */
	private class PreviewCardView(context: Context) : android.view.View(context) {

		private var resId: Int = 0
		private var alpha: Float = 1F
		private var drawable: android.graphics.drawable.Drawable? = null

		/**
		 * 【第七十二轮】[a] = 预览里卡底的不透明度系数(仅预览用)。
		 * Drawable 可能来自全局缓存(layer-list 共享), 必须 mutate() 之后再 setAlpha,
		 * 否则改的是"桌面小组件正在用的同一份对象"。
		 */
		fun bind(id: Int, a: Float = 1F) {
			if(resId == id && alpha == a) return
			resId = id
			alpha = a
			drawable = runCatching { context.getDrawable(id)?.mutate() }.getOrNull()
			drawable?.alpha = (a * 255F).toInt().coerceIn(0, 255)
			invalidate()
		}

		override fun onDraw(canvas: android.graphics.Canvas) {
			val d = drawable ?: return
			val dw = (width * 0.30F).toInt()
			val dh = (height * 0.55F).toInt()
			d.setBounds(-dw, -dh, width + dw, height + dh)
			d.draw(canvas)
			// 还原边界: layer-list 是共享对象, 下次别人 setBounds 前留着"放大值"会画错
			d.setBounds(0, 0, width, height)
		}
	}

	/**
	 * 【第六十二轮】桌面上现在摆着几个本软件的小组件。
	 *
	 * 只给「重绘」按钮的气泡用: 如果返回 0, 说明用户根本没往桌面加过 —— 那"改主题没反应"
	 * 就不是刷新丢了, 而是压根没有卡可以刷。这一句话能省掉一整轮排查。
	 */
	private fun countOnHome(context: Context): Int = runCatching {
		val manager = AppWidgetManager.getInstance(context)
		ENTRIES.sumOf { manager.getAppWidgetIds(ComponentName(context, it.cls)).size }
	}.getOrDefault(0)

	/**
	 * 【第四十五轮·修"未授权没有跳权限页"】
	 * 之前用 `checkPermission` 查的是 AndroidManifest 里的权限**声明** —— 只要清单里写过
	 * INSTALL_SHORTCUT 就永远返回 GRANTED, 而 MIUI 等国产 ROM 的"桌面快捷方式"开关是
	 * 私有 AppOps, 普通权限查询根本碰不到它, 所以检测形同虚设(点按钮从不跳权限页)。
	 *
	 * 现在改用 AppOps 反射读 `android:install_shortcut`:
	 * - 读得到且明确被拒(IGNORED=1 / ERRORED=2) → 判定未授权, 跳权限管理页;
	 * - 读不到(ROM 没实现/反射失败)或处于"询问"等私有模式 → 放行, 交给系统确认框自己拦。
	 */
	private fun isShortcutOpAllowed(context: Context): Boolean {
		return try {
			val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
			val uid = context.applicationInfo.uid
			val opResult: Int? =
				// API 26+ 有字符串重载; 更旧的版本退回 int 重载(op 号用反射取隐藏常量)
				try {
					val m = appOps.javaClass.getMethod(
						"checkOpNoThrow", String::class.java, Int::class.javaPrimitiveType, String::class.java
					)
					m.invoke(appOps, "android:install_shortcut", uid, context.packageName) as? Int
				}catch(_: Exception) {
					try {
						val op = android.app.AppOpsManager::class.java.getField("OP_INSTALL_SHORTCUT").getInt(null)
						val m = appOps.javaClass.getMethod(
							"checkOpNoThrow", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java
						)
						m.invoke(appOps, op, uid, context.packageName) as? Int
					}catch(_: Exception) { null }
				}
			// 只把"明确拒绝"判为未授权; ALLOWED/DEFAULT/ROM 私有的"询问"模式都放行
			opResult == null || (opResult != 1 && opResult != 2)
		}catch(_: Exception) {
			// AppOps 整个拿不到: 不要挡路
			true
		}
	}

	private fun openAppSettings(context: Context) {
		runCatching {
			context.startActivity(
				Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
					.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
			)
		}
	}

	/**
	 * 把某个小组件固定到桌面
	 *
	 * @param known 系统是否已识别该组件(由页面读取的状态直接传进来, 避免重复查询)
	 * @return Pair(是否发起成功, 给用户看的提示)
	 */
	private fun pinToHome(context: Context, cls: Class<*>, known: Boolean): Pair<Boolean, String> {
		val manual = "请在桌面长按空白处 → 小部件/小组件 → 找到「青科助手」后手动添加"
		if(Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false to manual
		if(!known) return false to "系统没有识别到该小组件：请卸载后重新安装本应用，再重启手机"
		val manager = AppWidgetManager.getInstance(context)
		val provider = ComponentName(context, cls)
		return try {
			if(!manager.isRequestPinAppWidgetSupported) {
				false to manual
			}else {
				// 【第四十三轮·按要求】把"跳回桌面"挂进系统的成功回调:
				// 用户在系统确认框点「添加」、组件真正落上桌面的那一刻, 系统会发出这个 PendingIntent,
				// 于是直接落在**组件被加到的那一页桌面**(系统把组件放在哪一页我们无从得知,
				// 但确认框本来就在当前桌面页上, 回到桌面即是对的那一页)。
				val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
					.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
				val callback = if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
					android.app.PendingIntent.getActivity(
						context, 0, homeIntent,
						android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
					)
				}else{
					android.app.PendingIntent.getActivity(
						context, 0, homeIntent, android.app.PendingIntent.FLAG_UPDATE_CURRENT
					)
				}
				if(manager.requestPinAppWidget(provider, null, callback)) {
					true to "已发起添加，请按桌面弹出的提示确认"
				}else {
					false to manual
				}
			}
		}catch(_: Exception) {
			false to manual
		}
	}
}
