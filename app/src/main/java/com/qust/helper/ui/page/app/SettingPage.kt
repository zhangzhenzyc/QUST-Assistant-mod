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

import androidx.activity.viewModels
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.qust.helper.data.Keys
import com.qust.helper.data.Page
import com.qust.helper.data.api.QustApi
import com.qust.helper.data.lesson.TimeTables
import com.qust.helper.model.LessonTableRepository
import com.qust.helper.ui.activity.ComposeActivity
import com.qust.helper.ui.theme.tapRipple
import com.qust.helper.ui.theme.colorSecondaryText
import com.qust.helper.ui.widget.Dialogs
import com.qust.helper.ui.page.lesson.ScheduleAdjust
import com.qust.helper.utils.DateUtils
import com.qust.helper.viewmodel.SettingViewModel

object SettingPage {

	const val LessonTable = "LessonTableSetting"
	const val Eas = "EasSetting"
	const val App = "AppSetting"

	val SettingPage = Page(Keys.Page.SettingPage, "设置", image = Icons.Rounded.Settings,
		arguments = listOf(navArgument("type") { type = NavType.StringType; nullable = true })
	) { activity, padding, navController, arguments ->
		val scrollState = rememberScrollState()

		Column(modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(scrollState)) {
			when(arguments?.getString("type")){
				LessonTable -> LessonTableSetting()
				Eas -> {
					val viewModel by activity.viewModels<SettingViewModel>()
					EasSetting(viewModel = viewModel)
				}
				null -> {
					val viewModel by activity.viewModels<SettingViewModel>()
					AllSetting(viewModel, navController)
				}
			}
		}
	}

	/**
	 * 「课表设置」独立入口
	 *
	 * 原来"我"页面是用 `setting?type=LessonTableSetting` 这种拼参数的方式进的, 侧边栏没法直接复用
	 * (抽屉按 key 从 Data.Pages 取页面)。这里做成独立页面, 两处共用同一入口。
	 */
	val LessonTableSettingPage = Page(Keys.Page.LessonTableSettingPage, "课表设置", image = Icons.Rounded.Settings) { _, padding, _, _ ->
		Column(modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
			LessonTableSetting()
		}
	}

	/** 【第六十七轮·按要求】原名「教务查询设置」, 改名为「教务设置」(供"我"页面与侧边栏共用) */
	val EasSettingPage = Page(Keys.Page.EasSettingPage, "教务设置", image = Icons.Rounded.Settings) { activity, padding, _, _ ->
		val viewModel by activity.viewModels<SettingViewModel>()
		Column(modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
			EasSetting(viewModel = viewModel)
		}
	}

	@Composable
	fun AllSetting(viewModel: SettingViewModel, navController: NavController? = null) {

		// 课表相关设置统一放在"课表设置"入口(我的 -> 课表设置), 此处不再重复展示

		// 按要求删除"教务"内容: 教务查询设置仍保留为独立入口(我的 → 教务查询设置), 此处不再展示
		// 【第三十二轮·按要求】删除蓝色分组标题「界面」与"主题跟随系统"功能(只保留"暗色模式")。
		// 【第五十轮·按要求】开关保留, 删掉的是下方的"暗色模式已开启/已关闭"状态说明文字。
		// 【第五十五轮·按要求】每个条目单独一张卡片(与下面几个入口同一套外观)
		SettingCard {
			SwitchItemUI("暗色模式", "", "", viewModel.themeDark) { viewModel.setThemeDarkValue(it) }
		}
		// 【第三十轮】按要求去掉标题下方的"点击查看完整…"说明文字
		// 【第三十一轮】两个条目**各自**加一个卡片外框(与"界面"等其他分组同款样式), 方便识别与点击
		val docContext = LocalContext.current
		// 【第六十八轮·二·按要求】"设置里面的桌面小组件移出来, 单独在其他里面开一个功能叫「小组件」"
		// —— 入口搬到「我 → 其他」宫格(饮水码与科大官网之间)与侧边栏同名分组, 这里不再重复放一份。
		Card(
			modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 8.dp),
			colors = CardDefaults.cardColors(containerColor = Color.White),
			elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
		) {
			Column {
				SettingItemUI("免责声明", "") { ComposeActivity.startActivity(docContext, Keys.Page.DisclaimerPage) }
			}
		}
		Card(
			modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 8.dp),
			colors = CardDefaults.cardColors(containerColor = Color.White),
			elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
		) {
			Column {
				SettingItemUI("隐私政策", "") { ComposeActivity.startActivity(docContext, Keys.Page.PolicyPage) }
			}
		}
		// 【第七十一轮】开源许可(原项目版权声明 + GPL-3.0 全文)与修改说明
		Card(
			modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 8.dp),
			colors = CardDefaults.cardColors(containerColor = Color.White),
			elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
		) {
			Column {
				SettingItemUI("开源许可", "") { ComposeActivity.startActivity(docContext, Keys.Page.LicensePage) }
			}
		}
		Card(
			modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 8.dp),
			colors = CardDefaults.cardColors(containerColor = Color.White),
			elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
		) {
			Column {
				SettingItemUI("修改说明", "") { ComposeActivity.startActivity(docContext, Keys.Page.ModificationPage) }
			}
		}
	}

	/**
	 * 课表设置界面
	 */
	@Composable
	fun LessonTableSetting() {
		val context = LocalContext.current
		var showTimePicker by remember { mutableStateOf(false) }

		var startDayStr by remember { mutableStateOf(DateUtils.YMD.format(LessonTableRepository.startDay)) }

		// 时间表数量/名称会随"时间表管理"里的增删改变化, 这里读取一次以订阅变化
		val timeTableNames = TimeTables.names
		// 【第九十八轮·按要求】"设置时间表"弹窗顺序: 新冬季时间第一位、冬季时间第二位、夏季时间第三位。
		// 只改**展示层**顺序 —— 内置预设的 presetIndex(0=冬季 1=夏季 2=新冬季)与 entries 下标都已
		// 持久化, 一旦真改下标, 旧存档里"当前时间表"就会指到另一套。这里按 presetIndex 2→0→1
		// 排出展示顺序(同一预设的副本跟随原预设), 其余条目(自定义表)按原顺序排在后面;
		// 选择时再由 displayOrder 映射回真实下标, 不影响存档与"当前使用"标记。
		val displayOrder = remember(timeTableNames) {
			val list = ArrayList<Int>()
			for(p in intArrayOf(2, 0, 1)) {
				TimeTables.entries.withIndex().filter { it.value.presetIndex == p }.forEach { list.add(it.index) }
			}
			TimeTables.entries.withIndex().filter { it.value.presetIndex !in 0..2 }.forEach { list.add(it.index) }
			list.toIntArray()
		}
		val displayNames = Array(displayOrder.size) { TimeTables.entries[displayOrder[it]].name }
		val displayIndex = displayOrder.indexOf(LessonTableRepository.currentTimeTable).let { if(it >= 0) it else 0 }

		// 【第五十五轮·按要求】"把这两个界面的条目分成一个一个的条目":
		// 以前是全部塞在一张 Card 里(中间没有任何分隔, 看起来是一大块),
		// 现在每个条目各自一张卡片、卡片之间留 8dp 间距 —— 和参考图一样一条一条的。
		SettingCard { SwitchItemUI("显示未来课程", "将在某周某时间无课并且未来某周同一时间有课的相对有课课程为过去的无课课程以灰色显示", value = LessonTableRepository.showAllLesson) { LessonTableRepository.setShowAllLessonValue(it) } }

		SettingCard { SwitchItemUI("隐藏教师", "隐藏课表教师信息", value = LessonTableRepository.hideTeacher) { LessonTableRepository.setHideTeacherValue(it) } }

		SettingCard { SwitchItemUI("锁定课表", "禁止编辑课表", "禁止编辑课表", LessonTableRepository.lockLesson) { LessonTableRepository.setLockLessonValue(it) } }

		SettingCard { SettingItemUI("设置开学时间", startDayStr){ showTimePicker = true } }

		SettingCard {
			InputItemUI("设置总周数", LessonTableRepository.totalWeek.toString(),
				keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done)
			){ week -> week.toIntOrNull()?.let{ LessonTableRepository.setTotalWeekValue(it) } }
		}

		SettingCard {
			ListItemUI("设置时间表", displayIndex, displayNames){ _, it ->
				// 【第九十八轮·按要求】切换前先检查: 目标时间表的节数装不下课表里的全部课程
				// 则**不切换**, 给出气泡提示"该时间表无法正常显示全部课程"。
				val real = displayOrder.getOrNull(it) ?: -1
				if(real >= 0 && LessonTableRepository.timeTableFits(real)) {
					LessonTableRepository.setTimeTableValue(real)
				}else if(real >= 0){
					(context as? android.app.Activity)?.let { act ->
						com.qust.helper.ui.widget.Toast.of(act).toastError("该时间表无法正常显示全部课程")
					}
				}
			}
		}

		SettingCard { SettingItemUI("时间表管理", "新增/命名/编辑/删除时间表，可恢复默认"){ ComposeActivity.startActivity(context, Keys.Page.TimeTablePage) } }

		SettingCard { SettingItemUI("调休管理", "图片/官网/手动调休与教务通知调课，调休安排的具体管理"){ ComposeActivity.startActivity(context, Keys.Page.AdjustPage) } }

		// 说明: "全部课表恢复默认"已删除 —— 与「课表查询」里的"恢复默认课表"重复, 那里更贴合使用场景
		// 说明: "撤销上次课表重置"已按需求删除

		if(showTimePicker){
			ScheduleAdjust.AdjustDatePickerDialog(
				title = "设置开学时间",
				initialDate = DateUtils.YMD.format(LessonTableRepository.startDay),
				onlyMonday = true,
				onDismiss = { showTimePicker = false }
			){ key ->
				val date = DateUtils.YMD.parse(key)!!
				LessonTableRepository.setStartDayValue(date)
				startDayStr = DateUtils.YMD.format(date)
				showTimePicker = false
			}
		}
	}

	/**
	 * 教务查询设置
	 */
	@Composable
	fun EasSetting(viewModel: SettingViewModel) {
		// 【第五十五轮·按要求】每个条目单独一张卡片(与「设置」页同一套外观)
		SettingCard { ListItemUI("教务节点", viewModel.eaHost, QustApi.EA_HOSTS){ _, it -> viewModel.setEaHostValue(it) } }

		SettingCard {
			InputItemUI("设置入学年份", viewModel.entranceTime, KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done)){
				year -> year.toIntOrNull()?.let{ viewModel.setEntranceTimeValue(it) }
			}
		}
	}

	/** 读取 assets/policy.html 并剥离 HTML 标签, 转成纯文本(保留段落/列表换行) */
	fun loadPolicyPlainText(context: Context): String {
		return try {
			val html = context.assets.open("policy.html").bufferedReader().readText()
			html
				.replace(Regex("<h1[^>]*>", RegexOption.IGNORE_CASE), "\n")
				.replace(Regex("<h2[^>]*>", RegexOption.IGNORE_CASE), "\n")
				.replace(Regex("<li[^>]*>", RegexOption.IGNORE_CASE), "\n")
				.replace(Regex("</(li|h1)>", RegexOption.IGNORE_CASE), "")
				.replace(Regex("</h2>", RegexOption.IGNORE_CASE), "\n")
				// 【第一百零一轮】原版隐私政策恢复后含第三方 SDK 的 <dl><dt><dd> 结构, 各条目同样换行
				.replace(Regex("</(dt|dd)>", RegexOption.IGNORE_CASE), "\n")
				.replace(Regex("</(p|div)>", RegexOption.IGNORE_CASE), "\n")
				.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
				.replace(Regex("<[^>]+>", RegexOption.IGNORE_CASE), "")
				.replace(Regex("^[ \\t]+", RegexOption.MULTILINE), "")
				.replace("&nbsp;", " ")
				.replace("&amp;", "&")
				.replace("&lt;", "<")
				.replace("&gt;", ">")
				.replace(Regex("\n{2,}"), "\n")
				.trim()
		} catch(_: Exception) { "" }
	}

	/**
	 * 【第五十五轮·按要求】"一个条目一张卡片"
	 *
	 * 以前「课表设置」「教务查询设置」是**所有条目塞在一张 Card 里**(中间没有分隔, 看上去是一大块),
	 * 用户要求改成参考图那样"一条一条的"。这里统一成: 一条一个小卡片, 卡片之间 8dp 间距。
	 * 与「设置」页其它入口(桌面小组件/免责声明/隐私政策…)完全同一套外观。
	 */
	@Composable
	private fun SettingCard(content: @Composable () -> Unit) {
		Card(
			modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 8.dp),
			colors = CardDefaults.cardColors(containerColor = Color.White),
			elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
		) {
			Column { content() }
		}
	}

	@Composable
	private fun SettingItemUI(title: String, description: String, enable: Boolean = true, onClick: () -> Unit) {
		Box(modifier = Modifier.fillMaxWidth().tapRipple(enabled = enable) { onClick() }) {
			Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
				Text(text = title, style = MaterialTheme.typography.titleMedium, color = if(enable) Color.Unspecified else colorSecondaryText)
				if(description.isNotEmpty()) Text(text = description, color = colorSecondaryText)
			}
		}
	}

	@Composable
	private fun SwitchItemUI(title: String, onText: String, offText: String? = null, value: Boolean, enable: Boolean = true, onChange: (Boolean) -> Unit) {
		Box(modifier = Modifier.fillMaxWidth().tapRipple {  }) {
			Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
				Column(modifier = Modifier.weight(1F)) {
					Text(text = title, style = MaterialTheme.typography.titleMedium, color = if(enable) Color.Unspecified else colorSecondaryText)
					// 【第五十一轮】状态说明文字为空串时不渲染(占位的空 Text 会多出一行高度)
					val sub = if(value) onText else (offText ?: onText)
					if(sub.isNotEmpty()) {
						Text(text = sub, color = colorSecondaryText)
					}
				}
				Switch(
					modifier = Modifier.semantics { contentDescription = title }.padding(start = 8.dp),
					checked = value,
					enabled = enable,
					onCheckedChange = { onChange(it) }
				)
			}
		}
	}

	@Composable
	private fun InputItemUI(title: String, value: String, keyboardOptions: KeyboardOptions, enable: Boolean = true, onInput: (String) -> Unit) {
		var showInput by remember { mutableStateOf(false) }
		Box(modifier = Modifier.fillMaxWidth().tapRipple { showInput = true }){
			Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
				Text(text = title, style = MaterialTheme.typography.titleMedium, color = if(enable) Color.Unspecified else colorSecondaryText)
				Text(text = value, color = colorSecondaryText)
			}
		}
		if(showInput){
			Dialogs.InputDialog(title = title, content = value, keyboardOptions = keyboardOptions, onDismiss = { showInput = false }){
				onInput(it)
				showInput = false
			}
		}
	}

	@Composable
	private fun ListItemUI(title: String, index: Int, items: Array<String>, enable: Boolean = true, onSelect: (Array<String>, Int) -> Unit) {
		var showList by remember { mutableStateOf(false) }
		Box(modifier = Modifier.fillMaxWidth().tapRipple { showList = true }){
			Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
				Text(text = title, style = MaterialTheme.typography.titleMedium, color = if(enable) Color.Unspecified else colorSecondaryText)
				Text(text = items[index], color = colorSecondaryText)
			}
		}
		if(showList){
			// 【修改版】列表里标出"当前使用"的那一项(选择回调按下标, 不受文案影响)
			val shown = items.copyOf()
			if(index in shown.indices) shown[index] = shown[index] + "（当前使用）"
			Dialogs.ListDialog(title = title, shown, onDismiss = { showList = false }){ _, it ->
				onSelect(items, it)
				showList = false
			}
		}
	}

}