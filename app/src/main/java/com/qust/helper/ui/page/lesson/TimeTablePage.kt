/*
 * 青科助手（修改版） —— 基于开源项目 QUST-Assistant 修改而成
 *
 * 原项目版权所有 (C) 2891954521  (https://github.com/2891954521/QUST-Assistant)
 * 原始开源许可: GNU General Public License v3.0 (GPL-3.0), 许可原文见 LICENSE 文件, 保持完整未改动。
 *
 * 修改版版权所有 (C) 2026 zhangzhenzyc  (https://github.com/zhangzhenzyc)
 * 本修改版同样以 GPL-3.0 发布; 附加版权声明见 NOTICE.md —— 两份版权声明相互独立、同时生效。
 */

package com.qust.helper.ui.page.lesson

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.qust.helper.R
import com.qust.helper.data.Keys
import com.qust.helper.data.Page
import com.qust.helper.data.lesson.TimeTableEntry
import com.qust.helper.data.lesson.TimeTables
import com.qust.helper.model.LessonTableRepository
import com.qust.helper.ui.activity.BaseActivity
import com.qust.helper.ui.theme.colorSecondaryText
import com.qust.helper.ui.theme.tnum
import com.qust.helper.ui.widget.Dialogs
import com.qust.helper.ui.widget.ListPicker
import com.qust.helper.ui.widget.Toast
import kotlin.math.abs

/**
 * 时间表管理
 *
 * - 可新增任意多套时间表, 并为之命名
 * - 可随时重命名 / 编辑每节课起止时间 / 复制一份 / 删除
 * - 内置的三套(冬季/夏季/新冬季)可"恢复默认"; 用户新建的时间表没有默认值, 只能删除
 * - 可把任意一套设为"当前使用"
 */
object TimeTablePage {

	val TimeTablePage = Page(Keys.Page.TimeTablePage, "时间表管理", iconRes = R.drawable.ic_view_list) { activity, padding, _, _ ->
		TimeTablePageUI(activity = activity, padding = padding)
	}

	/** 「编辑时间」弹窗左列各列的固定宽度(节次 / 起止时间), 保证每行竖直对齐 */
	private val TIME_SLOT_WIDTH = 64.dp
	private val TIME_RANGE_WIDTH = 104.dp

	/** 【第五十八轮】最右侧时长按钮的固定宽度: 让"45分钟 ▾"/"120分钟 ▾"的箭头落在同一列 */
	private val TIME_DURATION_WIDTH = 92.dp

	/** 把一套时间表的节数/时长压缩成一句摘要 */
	private fun summary(entry: TimeTableEntry): String {
		if(entry.start.isEmpty()) return "暂无节次，请先编辑时间"
		val mins = TimeTables.periodMinutes(entry.toTimes())
		if(mins.isEmpty()) return "暂无节次"
		val distinct = mins.distinct()
		val durText = if(distinct.size == 1) "每节${distinct[0]}分钟" else "每节${mins.min()}~${mins.max()}分钟"
		val first = entry.start.firstOrNull() ?: "--:--"
		val last = entry.end.lastOrNull() ?: "--:--"
		// 【第五十七轮·按要求】"时间管理表的 '~' 改成 '-'"
		return "${mins.size}节 · $durText · $first - $last"
	}

	/**
	 * 【第九十九轮·按要求】本学期"单日课程达到的最大节数"(最深有课到第几节, 连堂按结束节计)。
	 *
	 * 以前按"单日最多课程门数 × 2"给新建时间表的节数下限 —— 用户要求取消 ×2 逻辑:
	 * 以"学期当中单日课程能到多少节"为准, 例如 10 月 1 日上午没课、下午第 11 节还有课,
	 * 最低节数就是 11 节(上限仍为 12)。直接复用 [LessonTable.maxUsedEndSlot]
	 * (每周模板 + 按日期手动覆盖一起取最大)。课表为空返回 0(此时下限回落到 1, 不拦人)。
	 */
	private fun maxUsedSlotPerDay(): Int = runCatching {
		LessonTableRepository.lessonTable.maxUsedEndSlot()
	}.getOrDefault(0)

	@Composable
	fun TimeTablePageUI(activity: BaseActivity, padding: PaddingValues) {
		// 订阅列表变化: entries 是 Compose 状态, 任意增删改后本页会自动重组
		val entries = TimeTables.entries
		val current = LessonTableRepository.currentTimeTable

		var renameIndex by remember { mutableIntStateOf(-1) }
		var editIndex by remember { mutableIntStateOf(-1) }
		var duplicateIndex by remember { mutableIntStateOf(-1) }
		var deleteIndex by remember { mutableIntStateOf(-1) }
		var restoreIndex by remember { mutableIntStateOf(-1) }
		var showAdd by remember { mutableStateOf(false) }
		var askResetAll by remember { mutableStateOf(false) }

		Column(modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {

			// 【第七十轮·十九·按要求】删掉了顶部那段灰色介绍
			//（"可新增多套时间表并命名……"）—— 页面就这几样功能, 介绍纯属噪音。

			// 【第七十轮·十九·按要求】展示顺序: 新冬季时间 → 冬季时间 → 夏季时间 → 用户自建表。
			// 注意这只是**展示**顺序, 每张卡操作仍按它在存档里的原始下标(presetIndex 0/1/2 不动),
			// 否则"设为当前/编辑/删除"会改错表。
			val ordered = remember(entries) {
				entries.indices.sortedWith(compareBy(
					{ i -> when(entries[i].presetIndex) { 2 -> 0; 0 -> 1; 1 -> 2; else -> 3 } },
					{ i -> i }
				))
			}
			for(origin in ordered) {
				val entry = entries[origin]
				val index = origin
				Card(
					modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
					// 【第五十五轮·按要求】条目底色统一改成**白底**(参考图里的条目样式)
					colors = CardDefaults.cardColors(containerColor = Color.White),
					elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
				) {
					Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
						Row(verticalAlignment = Alignment.CenterVertically) {
							Text(
								text = entry.name,
								style = MaterialTheme.typography.titleMedium,
								modifier = Modifier.weight(1F)
							)
							if(index == current) {
								Text(
									text = "当前使用",
									style = MaterialTheme.typography.labelMedium,
									color = MaterialTheme.colorScheme.primary
								)
							}else{
								TextButton(onClick = {
									// 【第九十八轮·按要求】与「设置时间表」弹窗同一道拦截:
									// 目标时间表的节数装不下课表里的全部课程则**不切换**, 气泡提示。
									if(LessonTableRepository.timeTableFits(index)) {
										LessonTableRepository.setTimeTableValue(index)
										activity.toast.toastOK("已切换为「${entry.name}」")
									}else{
										activity.toast.toastError("该时间表无法正常显示全部课程")
									}
								}) { Text(text = "设为当前") }
							}
						}
						Text(
							text = summary(entry),
							style = MaterialTheme.typography.bodySmall,
							color = colorSecondaryText
						)
						if(entry.presetIndex >= 0 && TimeTables.isCustomized(index)) {
							Text(
								text = "已修改（可恢复默认）",
								style = MaterialTheme.typography.bodySmall,
								color = MaterialTheme.colorScheme.error
							)
						}

						// 【第五十轮】按钮行改为"横向滚动": 按钮不再被挤压, "删除"始终完整显示
						// (去掉占位的 Spacer(weight), 避免把后面的按钮挤出屏幕被裁掉)
						Row(
							modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
							horizontalArrangement = Arrangement.Start,
							verticalAlignment = Alignment.CenterVertically
						) {
							TextButton(onClick = { editIndex = index }) { Text(text = "编辑时间", maxLines = 1, softWrap = false) }
							TextButton(onClick = { renameIndex = index }) { Text(text = "重命名", maxLines = 1, softWrap = false) }
							// 【第五十轮】"复制一份"改名为"复制"
							TextButton(onClick = { duplicateIndex = index }) { Text(text = "复制", maxLines = 1, softWrap = false) }
							if(TimeTables.canRestoreDefault(index)) {
								TextButton(onClick = { restoreIndex = index }) { Text(text = "恢复默认", maxLines = 1, softWrap = false) }
							}
							TextButton(onClick = { deleteIndex = index }) {
								// 强制单行显示("横过来写", 不再被挤成竖排)
								Text(text = "删除", color = MaterialTheme.colorScheme.error, maxLines = 1, softWrap = false)
							}
						}
					}
				}
			}

			// 【第五十六轮·按要求】"该页面下面两个条目也按照上面 3 个条目的样式去改":
			// 原来这两个用的是 `colorScheme.background`(和页面底色几乎一样, 看着像两块凹进去的灰条),
			// 现在和上面的时间表卡片完全同一套外观 —— 白底 + 6dp 阴影 + 同样的外边距。
			Card(
				modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
				colors = CardDefaults.cardColors(containerColor = Color.White),
				elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
			) {
				Row(
					modifier = Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(8.dp)).clickable { showAdd = true },
					verticalAlignment = Alignment.CenterVertically
				) {
					Icon(imageVector = Icons.Rounded.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
					Text(text = "新增时间表", modifier = Modifier.padding(start = 12.dp), style = MaterialTheme.typography.titleMedium)
				}
			}

			Card(
				modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
				colors = CardDefaults.cardColors(containerColor = Color.White),
				elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
			) {
				Row(
					modifier = Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(8.dp)).clickable { askResetAll = true },
					verticalAlignment = Alignment.CenterVertically
				) {
					Text(
						text = "全部恢复默认（清空自定义时间表）",
						style = MaterialTheme.typography.titleMedium,
						color = MaterialTheme.colorScheme.error
					)
				}
			}

			Spacer(modifier = Modifier.height(24.dp))
		}

		// ---------- 新增 ----------
		if(showAdd) {
			AddTimeTableDialog(
				onDismiss = { showAdd = false },
				onConfirm = { name, sourceIndex, slots, minutes ->
					// 【第五十七轮】"新建时间表"不再套用夏季时间: 按用户填的节数/时长生成空白时间表
					// (每节首尾相接、起点 08:00), 具体上课时间用户再到「编辑时间」里改。
					val times = if(sourceIndex < 0) TimeTables.fixedSchedule(8, 0, minutes, 10, slots)
						else TimeTables.getTimes(sourceIndex)
					val presetIndex = if(sourceIndex in 0 until TimeTables.TIMETABLE_COUNT) sourceIndex else -1
					val newIndex = TimeTables.addTimeTable(name, times, presetIndex)
					LessonTableRepository.clampTimeTableIndex()
					activity.toast.toastOK("已新增时间表「${TimeTables.entries.getOrNull(newIndex)?.name ?: name}」")
					showAdd = false
				}
			)
		}

		// ---------- 重命名 ----------
		if(renameIndex in entries.indices) {
			Dialogs.InputDialog(
				title = "重命名时间表",
				content = entries[renameIndex].name,
				onDismiss = { renameIndex = -1 }
			){ input ->
				if(TimeTables.rename(renameIndex, input)) {
					activity.toast.toastOK("已重命名为「${input.trim()}」")
				}else{
					activity.toast.toastWarning("名称不能为空")
				}
				renameIndex = -1
			}
		}

		// ---------- 复制一份 ----------
		if(duplicateIndex in entries.indices) {
			Dialogs.InputDialog(
				title = "复制时间表",
				content = "${entries[duplicateIndex].name} 副本",
				onDismiss = { duplicateIndex = -1 }
			){ input ->
				val newIndex = TimeTables.duplicate(duplicateIndex, input.trim().ifEmpty { null })
				LessonTableRepository.clampTimeTableIndex()
				if(newIndex >= 0) activity.toast.toastOK("已复制为「${TimeTables.entries.getOrNull(newIndex)?.name}」")
				duplicateIndex = -1
			}
		}

		// ---------- 删除 ----------
		if(deleteIndex in entries.indices) {
			val entry = entries[deleteIndex]
			// 【第四十八轮】删除的是当前正在使用的时间表时给出"会改动课程数据"的提示; 确认按钮为红色"删除"
			val isCurrentTimeTable = deleteIndex == LessonTableRepository.currentTimeTable
			Dialogs.AskDialog(
				title = "删除时间表",
				// 【第五十轮】非当前时间表提示也去掉"？"(统一为无问号句式)
				content = if(isCurrentTimeTable) "是否删除「${entry.name}」？该操作会改动课程数据"
					else "是否删除「${entry.name}」？该操作不会改动课程数据，仅删除这套时间。",
				onDismiss = { deleteIndex = -1 },
				confirmText = "删除",
				danger = true,
				onConfirm = {
					val wasCurrent = deleteIndex == LessonTableRepository.currentTimeTable
					// 【第九十九轮·按要求】删除前先确认: 剩下的时间表里**至少还有一套能完整显示全部课程**,
					// 否则这套表也不可删 —— 不然删完无论切到哪套, 课表都有课程显示不全。
					val remaining = TimeTables.entries.indices.filter { it != deleteIndex }
					val anyRemainingFit = remaining.any { LessonTableRepository.timeTableFits(it) }
					if(!anyRemainingFit){
						activity.toast.toastWarning("删除后没有时间表能完整显示全部课程，无法删除")
					}else if(TimeTables.removeTimeTable(deleteIndex)) {
						if(wasCurrent){
							// 【第九十九轮·按要求】删的是正在使用的表: 自动切换, 优先选**符合当下日期季节**的
							// (10月1日~次年端午=冬季, 端午~同年10月1日=夏季), 季节定了以后优先带"新"的那套
							// (defaultIndexBySeason 已含这两条规则); 没有叫冬季/夏季的表就退到
							// **第一个能装下全部课程**的时间表(见 pickFallbackAfterDelete)。
							LessonTableRepository.setTimeTableValue(pickFallbackAfterDelete())
						}
						LessonTableRepository.clampTimeTableIndex()
						activity.toast.toastOK("已删除「${entry.name}」")
					}else{
						activity.toast.toastWarning("至少需要保留一套时间表")
					}
					deleteIndex = -1
				}
			)
		}

		// ---------- 恢复默认 ----------
		if(restoreIndex in entries.indices) {
			val entry = entries[restoreIndex]
			Dialogs.AskDialog(
				title = "恢复默认",
				content = "是否把「${entry.name}」恢复为内置默认时间与默认名称？",
				onDismiss = { restoreIndex = -1 },
				onConfirm = {
					if(TimeTables.restoreDefault(restoreIndex)) activity.toast.toastOK("已恢复默认")
					restoreIndex = -1
				}
			)
		}

		// ---------- 全部恢复默认 ----------
		if(askResetAll) {
			Dialogs.AskDialog(
				title = "全部恢复默认",
				content = "将清除所有自定义时间表与修改，恢复为内置的冬季/夏季/新冬季三套默认时间。确定继续吗？",
				onDismiss = { askResetAll = false },
				onConfirm = {
					TimeTables.resetToDefaults()
					LessonTableRepository.setTimeTableValue(0)
					activity.toast.toastOK("已恢复为内置默认时间表")
					askResetAll = false
				}
			)
		}

		// ---------- 编辑时间 ----------
		if(editIndex in entries.indices) {
			EditTimesDialog(
				index = editIndex,
				toast = activity.toast,
				onDismiss = { editIndex = -1 }
			)
		}
	}

	/**
	 * 新增时间表: 输入名称并选择初始时间来源
	 *
	 * 【第五十七轮·按要求】
	 *  · 名称输入框的占位文字只保留"名称";
	 *  · "新建时间表"不再默认套用夏季时间 —— 改成让用户填**课程节数**与**课程时长**,
	 *    据此生成一张只有节数和时长的空白时间表(每节首尾相接, 起点 08:00),
	 *    具体每节课的**开始时间再由用户到「编辑时间」里自己改**;
	 *  · "复制内置/复制列表"这两类仍然保留, 行为不变。
	 */
	/**
	 * 【第九十九轮·按要求】删除"正在使用的时间表"后自动挑一套替补(调用时被删表已不在列表里):
	 *  ① 优先符合当下日期季节的(10月1日~次年端午=冬季; 端午~同年10月1日=夏季),
	 *     季节定了以后优先名字带"新"的那套(如当下的"新冬季时间") —— [TimeTables.defaultIndexBySeason]
	 *     已含这两条规则;
	 *  ② 没有叫冬季/夏季的时间表(或那套装不下全部课程)时, 选**第一个**能完整显示全部课程的;
	 *  ③ 全都装不下时(调用前已被"不可删除"挡住, 理论到不了)兜底回第一套。
	 */
	private fun pickFallbackAfterDelete(): Int {
		val seasonal = TimeTables.defaultIndexBySeason()
		if(LessonTableRepository.timeTableFits(seasonal)) return seasonal
		return TimeTables.entries.indices.firstOrNull { LessonTableRepository.timeTableFits(it) }
			?: seasonal.coerceIn(0, (TimeTables.entries.size - 1).coerceAtLeast(0))
	}

	@Composable
	private fun AddTimeTableDialog(onDismiss: () -> Unit, onConfirm: (String, Int, Int, Int) -> Unit) {
		var name by remember { mutableStateOf("") }
		var source by remember { mutableIntStateOf(-1) }
		// 自定义: 节数 / 每节时长(分钟)
		var slotsText by remember { mutableStateOf(TimeTables.MAX_SLOT_COUNT.toString()) }
		var minutesText by remember { mutableStateOf("45") }

		// 【第九十九轮·按要求】节数下限改为"本学期单日最高节数"(最深有课到第几节, 连堂按结束节计):
		// 例如 10 月 1 日上午没课、下午第 11 节还有课, 最低节数就是 11 —— 不再按"单日最多门数 × 2"算,
		// 上限仍是 12。空课表(0 节)回落到 1, 不拦人。打开对话框时算一次即可。
		val maxUsed = remember { maxUsedSlotPerDay() }
		val minSlots = maxUsed.coerceIn(1, TimeTables.MAX_SLOT_COUNT)

		// value: <0 自定义(节数+时长); 0..TIMETABLE_COUNT 复制内置预设; >=100 复制列表中的第(value-100)套
		val options = ArrayList<Pair<String, Int>>()
		// 【第五十八轮·按要求】去掉"（自定义节数与课程时长）"这段后缀, 只留「新建时间表」
		options.add("新建时间表" to -1)
		// 【第一百轮·按要求】两组各自按 新冬季时间(1) -> 冬季时间(2) -> 夏季时间(3) 排序,
		// "复制内置"保持一组、"复制列表中的"保持一组, 组与组之间不交叉。
		// 内置组按 presetIndex 2/0/1 遍历(value 仍是 presetIndex, 与选择回调的语义不变);
		// 列表组按同一规则排(自定义表跟在季节表后面), value 仍是 100+真实下标。
		for(p in intArrayOf(2, 0, 1)) {
			options.add("复制内置「${TimeTables.getDefaultName(p)}」" to p)
		}
		val listOrder = TimeTables.entries.indices.sortedBy { i ->
			when(TimeTables.entries[i].presetIndex) { 2 -> 0; 0 -> 1; 1 -> 2; else -> 3 }
		}
		for(i in listOrder) {
			options.add("复制列表中的「${TimeTables.entries[i].name}」" to (100 + i))
		}

		val slotValue = slotsText.toIntOrNull() ?: -1
		val minuteValue = minutesText.toIntOrNull() ?: -1
		// 【第七十轮·十九】节数下限 = 本学期单日最多课程门数 × 2(见 minSlots 的说明)
		val customValid = slotValue in minSlots..TimeTables.MAX_SLOT_COUNT && TimeTables.isPeriodLength(minuteValue)

		Dialog(onDismissRequest = { onDismiss() }) {
			Card(
				modifier = Modifier.fillMaxWidth().padding(16.dp),
				shape = RoundedCornerShape(16.dp),
				elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
			) {
				Column(modifier = Modifier.padding(16.dp)) {
					Text(text = "新增时间表", style = MaterialTheme.typography.titleLarge)

					OutlinedTextField(
						value = name,
						onValueChange = { name = it },
						modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
						singleLine = true,
						label = { Text(text = "名称") },
						keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
					)

					Text(
						text = "初始时间",
						style = MaterialTheme.typography.bodySmall,
						color = colorSecondaryText,
						modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
					)
					LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
						items(options.size) { i ->
							val label = options[i].first
							val value = options[i].second
							Box(
								modifier = Modifier.fillMaxWidth()
									.clip(RoundedCornerShape(8.dp))
									.clickable { source = value }
									.padding(vertical = 10.dp, horizontal = 4.dp)
							) {
								Text(
									text = if(source == value) "● $label" else "○ $label",
									style = MaterialTheme.typography.bodyMedium,
									color = if(source == value) MaterialTheme.colorScheme.primary else colorSecondaryText
								)
							}
						}
					}

					// 选"新建时间表"时才出现的两个输入框: 节数 + 每节时长
					if(source < 0) {
						Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
							OutlinedTextField(
								value = slotsText,
								onValueChange = { slotsText = it.filter { c -> c.isDigit() }.take(2) },
								modifier = Modifier.weight(1F),
								singleLine = true,
								label = { Text(text = "课程节数") },
								keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next)
							)
							OutlinedTextField(
								value = minutesText,
								onValueChange = { minutesText = it.filter { c -> c.isDigit() }.take(3) },
								modifier = Modifier.weight(1F),
								singleLine = true,
								label = { Text(text = "每节时长(分钟)") },
								keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done)
							)
						}
						Text(
							text = if(customValid) "将生成 $slotValue 节、每节 $minuteValue 分钟的空白时间表，上课时间默认从 08:00 起，可稍后在「编辑时间」里修改。"
								// 【第九十九轮·按要求】括号里只保留"本学期单日最高节数为 N 节"
								else "节数请填 $minSlots~${TimeTables.MAX_SLOT_COUNT}（本学期单日最高节数为 $maxUsed 节），时长请填 ${TimeTables.MIN_PERIOD_MIN}~${TimeTables.MAX_PERIOD_MIN} 分钟。",
							style = MaterialTheme.typography.bodySmall,
							color = if(customValid) colorSecondaryText else MaterialTheme.colorScheme.error,
							modifier = Modifier.padding(top = 6.dp)
						)
					}

					Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
						TextButton(onClick = { onDismiss() }) { Text(text = "取消") }
						TextButton(
							enabled = source >= 0 || customValid,
							onClick = {
								val resolved = if(source >= 100) source - 100 else source
								// 【第六十一轮·按要求】默认名从「新建时间1」开始(内置三套不计数), 见 TimeTables.nextCustomName
								val finalName = name.trim().ifEmpty { TimeTables.nextCustomName() }
								onConfirm(finalName, resolved, slotValue, minuteValue)
							}
						) { Text(text = "创建") }
					}
				}
			}
		}
	}

	/**
	 * 编辑某一套时间表每节课的起止时间
	 * 点击某一节, 依次输入新的上课/下课时间(格式 HH:mm), 保存后立即生效并持久化
	 */
	@Composable
	private fun EditTimesDialog(index: Int, toast: Toast, onDismiss: () -> Unit) {
		val entryName = TimeTables.entries.getOrNull(index)?.name ?: "时间表"
		var times by remember { mutableStateOf(TimeTables.getTimes(index)) }
		var editSlot by remember { mutableIntStateOf(-1) }
		var editPart by remember { mutableIntStateOf(0) }
		var hint by remember { mutableStateOf("") }
		/** 正在选择时长的节次(点击最右侧的时长) */
		var durationSlot by remember { mutableIntStateOf(-1) }

		/** 【本轮】打开时的原始时间: 用来判断"有没有未保存的改动"(与「手动调休」同一套未保存行为) */
		val originTimes = remember(index) { TimeTables.getTimes(index) }
		var askSave by remember { mutableStateOf(false) }

		fun dirty(): Boolean {
			if(times.size != originTimes.size) return true
			for(i in times.indices){
				if(times[i].size != originTimes[i].size) return true
				for(j in times[i].indices) if(times[i][j] != originTimes[i][j]) return true
			}
			return false
		}

		/** 【完成】才真正写入时间表 */
		fun saveNow() {
			TimeTables.setTimes(index, times)
			if(TimeTables.hasConflict(times)) {
				toast.toastWarning("时间存在冲突或非法时长，请检查")
			}else{
				toast.toastOK("已保存「$entryName」的时间")
			}
			onDismiss()
		}

		/**
		 * 应用一组新的时间(**只改界面上的副本, 不落盘**)
		 *
		 * 【取消按钮】以前每改一次就立刻写进时间表, 就算点取消也已经改了;
		 * 现在改动先存在副本里, 点"完成"才真正保存, 点"取消"则全部丢弃。
		 */
		fun applyTimes(newTimes: Array<Array<String>>?) {
			if(newTimes == null) {
				hint = "时间没有变化，或格式不正确"
				return
			}
			times = newTimes
			hint = if(TimeTables.hasConflict(newTimes)) "注意：当前时间存在冲突或非法时长" else ""
		}

		// 【本轮】与「手动调休」同一套未保存行为: 有改动时点外部/返回不直接关, 先弹窗问是否保存
		Dialog(onDismissRequest = { if(dirty()) askSave = true else onDismiss() }) {
			Card(
				modifier = Modifier.fillMaxWidth().padding(16.dp, 48.dp),
				shape = RoundedCornerShape(16.dp),
				elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
			) {
				Column(modifier = Modifier.padding(16.dp)) {
					Text(
						text = "编辑「$entryName」",
						style = MaterialTheme.typography.titleLarge,
						modifier = Modifier.padding(bottom = 4.dp)
					)
					Text(
						text = "点击某一节课可用滚轮选择新的上课/下课时间；改动会依次顺延后面的课。点最右侧的时长可用滚轮选择上课时长（${TimeTables.MIN_PERIOD_MIN}~${TimeTables.MAX_PERIOD_MIN}分钟）。",
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.secondary
					)
					if(hint.isNotEmpty()) {
						Text(
							text = hint,
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.error,
							modifier = Modifier.padding(top = 4.dp)
						)
					}
					LazyColumn(modifier = Modifier.heightIn(max = 380.dp).padding(vertical = 8.dp)) {
						items(times[0].size) { slot ->
							// 【第五十七轮·按要求】
							//  ① "点击「第 X 节 时间」的反馈动画不要覆盖到课程时长":
							//     以前整行都是 clickable, 波纹横跨到最右边的时长按钮上; 现在只把
							//     "节次 + 起止时间"做成可点区域, 时长按钮在它外面、各点各的。
							//  ② "50分钟最好与箭头在同一行": 时长文字强制单行, 不许把 ▾ 折到下一行。
							Row(
								modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
								verticalAlignment = Alignment.CenterVertically
							) {
								Row(
									modifier = Modifier
										.clip(RoundedCornerShape(8.dp))
										.clickable {
											editSlot = slot
											editPart = 0
										}
										.padding(vertical = 8.dp, horizontal = 4.dp),
									verticalAlignment = Alignment.CenterVertically
								) {
								// 【第五十五轮】左列竖直对齐: 节次、起止时间各占**固定宽度**
								// 【第五十八轮】再补上**等宽数字**: 固定宽度只能保证左边缘对齐,
								// "08:00" 与 "10:00" 的字宽其实不同(比例数字), 冒号和 " - " 会错开一点点。
								Text(
									text = "第${slot + 1}节",
									modifier = Modifier.width(TIME_SLOT_WIDTH),
									style = MaterialTheme.typography.bodyLarge.tnum(),
									textAlign = TextAlign.Start,
									maxLines = 1,
									softWrap = false
								)
								val dur = run { val s = TimeTables.parseTime(times[0][slot]); val e = TimeTables.parseTime(times[1][slot]); if(s >= 0 && e >= 0) e - s else -1 }
								Text(
									text = "${times[0][slot]} - ${times[1][slot]}",
									modifier = Modifier.width(TIME_RANGE_WIDTH),
									style = MaterialTheme.typography.bodyMedium.tnum(),
									color = if(TimeTables.isPeriodLength(dur)) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
									textAlign = TextAlign.Start,
									maxLines = 1,
									softWrap = false
								)
								}
								Spacer(modifier = Modifier.weight(1F))
								// 最右侧: 点击时长可用滚轮选择上课时长(30~120分钟)
								val duration = run { val s = TimeTables.parseTime(times[0][slot]); val e = TimeTables.parseTime(times[1][slot]); if(s >= 0 && e >= 0) e - s else -1 }
								Box(
									modifier = Modifier
										.width(TIME_DURATION_WIDTH)
										.clip(RoundedCornerShape(8.dp))
										.background(MaterialTheme.colorScheme.surfaceVariant)
										.clickable { durationSlot = slot }
										.padding(horizontal = 10.dp, vertical = 6.dp),
									contentAlignment = Alignment.CenterEnd
								) {
									// 【第五十八轮】固定宽 + 右对齐 + 等宽数字: "45分钟 ▾" 与 "120分钟 ▾"
									// 的箭头落在同一列(以前左边缘对齐, 箭头位置随字数左右飘)
									Text(
										text = if(duration >= 0) "${duration}分钟 ▾" else "时长 ▾",
										style = MaterialTheme.typography.labelLarge.tnum(),
										color = MaterialTheme.colorScheme.primary,
										maxLines = 1,
										softWrap = false,
										textAlign = TextAlign.End
									)
								}
							}
						}
					}
					Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
						// 【取消】丢弃本次所有改动(改动一直只存在界面副本里, 这里直接关闭即可)
						TextButton(onClick = { onDismiss() }) { Text(text = "取消") }
						Spacer(modifier = Modifier.width(8.dp))
						// 【完成】才真正写入时间表
						TextButton(onClick = { saveNow() }) { Text(text = "完成") }
					}
				}
			}
		}

		// 【本轮】未保存就退出: 先问"是否保存", 与「手动调休」完全同口径(没有取消, 必须二选一)
		if(askSave) {
			AlertDialog(
				onDismissRequest = { },
				title = { Text(text = "未保存的修改") },
				text = { Text(text = "当前时间表尚有未保存的修改，是否保存？") },
				confirmButton = {
					TextButton(onClick = { askSave = false; saveNow() }) { Text(text = "保存") }
				},
				dismissButton = {
					TextButton(onClick = { askSave = false; onDismiss() }) { Text(text = "不保存") }
				}
			)
		}

		if(editSlot != -1) {
			// 【第二十五轮】上课/下课时间改为滚轮选择: 全天 00:00~23:55, 每隔5分钟一档(共288档)
			val allTimes = remember { (0 until 288).map { it * 5 } }
			val curMin = TimeTables.parseTime(times[editPart].getOrElse(editSlot) { "" }).let { if(it < 0) 8 * 60 else it }
			val initMin = allTimes.minByOrNull { abs(it - curMin) } ?: curMin
			var picked by remember(editSlot, editPart) { mutableIntStateOf(initMin) }
			AlertDialog(
				onDismissRequest = {
					editSlot = -1
					editPart = 0
				},
				title = { Text(text = if(editPart == 0) "第${editSlot + 1}节 上课时间" else "第${editSlot + 1}节 下课时间") },
				text = {
					Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
						Text(
							text = "上下滑动滚轮选择时间（每隔5分钟一档）",
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.secondary,
							modifier = Modifier.padding(bottom = 4.dp)
						)
						ListPicker.NumberPicker(
							value = picked,
							range = allTimes,
							label = { TimeTables.formatTime(it) },
							onValueChange = { picked = it },
							horizontalPadding = 24.dp
						)
					}
				},
				confirmButton = {
					TextButton(onClick = {
						val slot = editSlot
						val part = editPart
						// 改动会依次顺延: 后面的课一起平移, 保持整体结构不变
						val newTimes = TimeTables.editTimeWithShift(
							times[0], times[1], slot, part, TimeTables.formatTime(picked)
						)
						if(newTimes == null) {
							hint = "时间没有变化"
						}else{
							applyTimes(newTimes)
						}
						// 与旧版一致: 先调"上课时间", 确认后自动接着调"下课时间"
						if(part == 0) editPart = 1 else { editSlot = -1; editPart = 0 }
					}) { Text(text = "确认") }
				},
				dismissButton = {
					TextButton(onClick = { editSlot = -1; editPart = 0 }) { Text(text = "取消") }
				}
			)
		}

		// 【第二十五轮】点击最右侧的时长: 滚轮选时长(每隔5分钟一档; 保持上课时间不变, 后面的课依次顺延)
		// 【第五十八轮】下限 5 -> 30 分钟(见 TimeTables.MIN_PERIOD_MIN)
		if(durationSlot != -1) {
			val durationOptions = remember { (TimeTables.MIN_PERIOD_MIN..TimeTables.MAX_PERIOD_MIN step 5).toList() }
			val curDur = run {
				val s = TimeTables.parseTime(times[0].getOrElse(durationSlot) { "" })
				val e = TimeTables.parseTime(times[1].getOrElse(durationSlot) { "" })
				if(s >= 0 && e >= 0) e - s else 45
			}
			val initDur = durationOptions.minByOrNull { abs(it - curDur) } ?: 45
			var pickedDur by remember(durationSlot) { mutableIntStateOf(initDur) }
			AlertDialog(
				onDismissRequest = { durationSlot = -1 },
				title = { Text(text = "第${durationSlot + 1}节 上课时长") },
				text = {
					Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
						Text(
							text = "当前：${times[0].getOrElse(durationSlot){ "" }} - ${times[1].getOrElse(durationSlot){ "" }}\n上下滑动滚轮选择时长（最短${TimeTables.MIN_PERIOD_MIN}分钟，每隔5分钟一档，最长${TimeTables.MAX_PERIOD_MIN}分钟）。选择后下课时间会随之调整，后面的课依次顺延。",
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.secondary,
							modifier = Modifier.padding(bottom = 4.dp)
						)
						ListPicker.NumberPicker(
							value = pickedDur,
							range = durationOptions,
							label = { "$it 分钟" },
							onValueChange = { pickedDur = it },
							horizontalPadding = 24.dp
						)
					}
				},
				confirmButton = {
					TextButton(onClick = {
						val slot = durationSlot
						val minutes = pickedDur
						durationSlot = -1
						applyTimes(TimeTables.setSlotDuration(times[0], times[1], slot, minutes))
					}) { Text(text = "确认") }
				},
				dismissButton = {
					TextButton(onClick = { durationSlot = -1 }) { Text(text = "取消") }
				}
			)
		}
	}
}
