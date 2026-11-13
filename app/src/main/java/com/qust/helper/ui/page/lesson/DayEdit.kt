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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.qust.helper.data.Data
import com.qust.helper.data.Keys
import com.qust.helper.data.Page
import com.qust.helper.data.lesson.Lesson
import com.qust.helper.data.lesson.LessonClipboard
import com.qust.helper.data.lesson.LessonGroup
import com.qust.helper.data.lesson.TimeTables
import com.qust.helper.model.LessonTableRepository
import com.qust.helper.ui.activity.BaseActivity
import com.qust.helper.ui.activity.ComposeActivity
import com.qust.helper.ui.theme.TEXT_ACTION_H_PADDING
import com.qust.helper.ui.theme.TEXT_ACTION_V_PADDING
import com.qust.helper.ui.theme.textActionFilledColors
import com.qust.helper.ui.widget.Dialogs
import com.qust.helper.utils.DateUtils
import com.qust.helper.viewmodel.TermLessonUIEvent
import com.qust.helper.viewmodel.TermLessonUIState
import java.util.Calendar
import java.util.Date

/**
 * 单日课表编辑页面(按具体日期)
 * 点击课表顶部星期标签进入, 编辑与复制仅作用于该具体日期, 不影响每周课表模板与其它日期
 */
object DayEdit {

	/**
	 * 「添加」/「编辑」/「删除」的点击区域宽度**基准字数**(2 个汉字)。
	 *
	 * 【第三十四轮】不再用固定的 `56.dp` 撑宽度 —— 那是 4 个汉字「复制课程」的文字宽,
	 * 两个字的按钮被塞进去居中, 左右白留一大截(用户原话"留白太大了")。
	 * 现在宽度按字数现算(见 [DayEditUI] 里的 `actionWidth`), 与「复制课程」同一口径:
	 * 文字宽 + 左右 [TEXT_ACTION_H_PADDING] + 1dp 余量。
	 */
	private const val ACTION_TEXT_COUNT = 2

	val DayEditPageObj = Page(Keys.Page.DayEditPage, "编辑课表") { activity, padding, _, _ ->
		val day = (activity.intent?.getIntExtra("day", 0) ?: 0).coerceIn(0, 6)
		val week = (activity.intent?.getIntExtra("week", -1) ?: -1).let { w ->
			if(w < 0) LessonTableRepository.currentWeek.intValue - 1
			else w.coerceIn(0, (LessonTableRepository.lessonTable.totalWeek - 1).coerceAtLeast(0))
		}
		DayEditUI(activity = activity, padding = padding, week = week, day = day)
	}

	/** 某日期的 "M月d日" 文本 */
	private fun dateText(table: com.qust.helper.data.lesson.LessonTable, week: Int, day: Int): String {
		val c = Calendar.getInstance().also { it.time = table.weekStartDate(week) }
		c.add(Calendar.DATE, day)
		return "${c[Calendar.MONTH] + 1}月${c[Calendar.DATE]}日"
	}

	/** 将日期对齐到该周周一(零点) */
	private fun mondayOf(date: Date): Calendar = Calendar.getInstance().apply {
		time = date
		firstDayOfWeek = Calendar.MONDAY
		add(Calendar.DATE, when(get(Calendar.DAY_OF_WEEK)) {
			Calendar.TUESDAY -> -1; Calendar.WEDNESDAY -> -2; Calendar.THURSDAY -> -3
			Calendar.FRIDAY -> -4; Calendar.SATURDAY -> -5; Calendar.SUNDAY -> -6; else -> 0
		})
		set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
	}

	/** 将上课周掩码转为紧凑文本, 如 "1-3,5周" */
	private fun weeksText(mask: Long, totalWeek: Int): String {
		val weeks = ArrayList<Int>()
		for(i in 0 until totalWeek){
			if(mask and (1L shl i) > 0L) weeks.add(i + 1)
		}
		if(weeks.isEmpty()) return "无上课周"
		val sb = StringBuilder()
		var i = 0
		while(i < weeks.size){
			var j = i
			while(j + 1 < weeks.size && weeks[j + 1] == weeks[j] + 1) j++
			if(sb.isNotEmpty()) sb.append(",")
			if(j == i) sb.append(weeks[i]) else sb.append(weeks[i]).append("-").append(weeks[j])
			i = j + 1
		}
		return sb.append("周").toString()
	}

	@Composable
	@OptIn(ExperimentalMaterial3Api::class)
	fun DayEditUI(activity: BaseActivity, padding: PaddingValues, week: Int, day: Int){
		val times = TimeTables.currentTimes
		val slotCount = TimeTables.currentSlotCount
		val table = LessonTableRepository.lessonTable

		// 【第三十四轮·按要求】「添加/编辑/删除」与「复制课程」**完全同一套尺寸**:
		// 「复制课程」在 TermLesson.EditLessonUI 里的算法是
		//   width = 汉字个数 × 字号 + 左右 TEXT_ACTION_H_PADDING + 1dp 余量,
		// 高度由 TextButton 的 40dp 最小高度决定(药丸半径恒为 20dp)。
		// 这里用完全相同的一行算式, 只是字数换成 2(「添加」「编辑」「删除」都是两个字)。
		// 这样三个按钮: 形状(40dp 高的药丸)一致、波纹(primary, bounded)一致、留白一致,
		// 只有"长度"按字数不同 —— 与「复制课程」并列时看得出是同一套控件。
		val density = LocalDensity.current
		val actionLabelSize = MaterialTheme.typography.labelLarge.fontSize.value.let { if(it > 0f) it else 14f }
		fun actionWidth(): Dp = with(density) {
			// +2dp 是"不裁字"的余量(「复制课程」那边是 +1dp; 这里多留 1dp, 因为个别机型的
			// 中文字宽会略大于 1 个字号, 卡得太死会出现"添加"变成"添…")
			(actionLabelSize * ACTION_TEXT_COUNT).sp.toDp() + TEXT_ACTION_H_PADDING * 2 + 2.dp
		}

		// 界面刷新计数: 任何修改后自增
		var refreshTick by remember { mutableIntStateOf(0) }

		// 从本页返回学期课表时让其刷新
		DisposableEffect(Unit){
			val observer = LifecycleEventObserver{ _, event ->
				if(event == Lifecycle.Event.ON_RESUME) LessonTableRepository.notifyLessonTableChanged()
			}
			// onResume在本页面首次组合时也会触发一次, 无副作用
			activity.lifecycle.addObserver(observer)
			onDispose{ activity.lifecycle.removeObserver(observer) }
		}

		// ---------- 编辑课程状态 ----------
		var isShowEdit by remember { mutableStateOf(false) }
		var editSlot by remember { mutableIntStateOf(0) }
		var editLessonIndex by remember { mutableIntStateOf(-1) }
		var isNewLesson by remember { mutableStateOf(false) }
		/** 打开编辑框时该课程的原始节数, 用于判断占用时长是否被改动 */
		var originLen by remember { mutableIntStateOf(1) }
		// 从近期课表选择课程
		var showPicker by remember { mutableStateOf(false) }
		// 粘贴位置重合时的覆盖确认: (待粘贴的课程, 将被覆盖的课程)
		var pasteOverlap by remember { mutableStateOf<Pair<Lesson, Lesson>?>(null) }
		// 【本轮】从课表选择课程填充时, 目标位置重合的覆盖确认: (待填充的课程, 将被覆盖的课程)
		var pickOverlap by remember { mutableStateOf<Pair<Lesson, Lesson>?>(null) }
		val uiState = remember { TermLessonUIState(LessonTableRepository.lessonTable.totalWeek) }

		/**
		 * 粘贴到该节时, 该位置是否已有别的课程(判定口径与学期课表/拖动覆盖一致)
		 * @return 会被覆盖的课程; 没有重合返回 null
		 */
		fun pasteConflictAt(slot: Int, pasted: Lesson): Lesson? {
			val t = LessonTableRepository.lessonTable
			val s0 = slot.coerceIn(0, TimeTables.MAX_SLOT_COUNT - 1)
			val end = (s0 + pasted.len.coerceAtLeast(1) - 1).coerceAtMost(TimeTables.MAX_SLOT_COUNT - 1)
			for(s in s0..end){
				val hit = t.occupantsAt(week, day, s).firstOrNull { !it.lesson.isSameLessonAs(pasted) }
				if(hit != null) return hit.lesson
			}
			return null
		}

		/**
		 * 保存课程(仅作用于该具体日期)
		 * @param force 为true时跳过"时间重合"确认直接保存
		 * @param closeEdit 保存后是否关闭编辑框(粘贴立即写入时传 false, 编辑框保持打开)
		 */
		fun performSave(force: Boolean, closeEdit: Boolean = true){
			if(uiState.name.isEmpty()){
				activity.toast.toastWarning("请输入课程名称！")
				return
			}
			val t = LessonTableRepository.lessonTable
			// 整节对齐: 课程不得越出时间末尾, 避免出现"半节课"
			val maxLen = (slotCount - editSlot).coerceAtLeast(1)
			if(uiState.len > maxLen) uiState.len = maxLen
			if(uiState.len < 1) uiState.len = 1

		val editing = if(isNewLesson) null
			else t.effectiveDay(week, day).getOrNull(editSlot)?.lessons?.getOrNull(editLessonIndex)
		if(!force && (isNewLesson || uiState.len != originLen)){
			val conflict = t.spanOccupied(week, day, editSlot, uiState.len, editing)
			if(conflict != null){
				// 【第九十五轮】按"课程重叠"弹窗确认: 确认=覆盖, 已有课程会被删除
				uiState.overlapPrompt = "该时间段已有课程，会造成课程重叠，覆盖会导致已有课程删除，是否确认覆盖？" +
					"\n\n目标位置：${dateText(t, week, day)}（${Data.WEEK_STRING[day]}）第${editSlot + 1}~${editSlot + uiState.len}节（已有「${conflict.name}」）"
				return
			}
		}
		uiState.overlapPrompt = null

		// 仅修改该具体日期: 获取(必要时创建)该日期的覆盖列
		val col = t.editDayOverride(week, day)
		// 【第九十五轮】副本列里锁定"正在编辑的那一节": 新建的覆盖列是深拷贝,
		// 与 editing 不是同一个对象 —— 只能按"本周上课位 + 内容相同"匹配,
		// 否则 force 清理会误删自己的副本、写入时也会因下标错位改错课程。
		val thisMask = 1L shl week
		val editingInCol = editing?.let { src ->
			col.getOrNull(editSlot)?.lessons?.firstOrNull { l ->
				(l.week and thisMask) != 0L && (l === src || l.isSameLessonAs(src))
			}
		}
		// 【第九十五轮】覆盖语义: force 保存是用户在"课程重叠"弹窗里确认过的,
		// 必须先把新课程占用区间里已有的课程删掉 —— 任何情况下都不允许同一时间两节课同时在上课。
		if(force){
			val end = editSlot + uiState.len.coerceAtLeast(1)
			for(j in col.indices){
				val g = col.getOrNull(j) ?: continue
				// 与目标区间相交(起始节次 + 节数跨越), 且这一周真的会上课的课程才清;
				// 本周不上的灰色课程(周位不含本周)一律保留
				val remaining = g.lessons.filterNot { l ->
					l !== editingInCol && (l.week and thisMask) != 0L &&
						j < end && j + l.len.coerceAtLeast(1) > editSlot
				}
				if(remaining.size != g.lessons.size){
					col[j] = if(remaining.isEmpty()) null else LessonGroup(g.week, g.count, remaining.toTypedArray())
				}
			}
		}
			if(isNewLesson){
				val lesson = Lesson(type = 1)
				lesson.name = uiState.name
				lesson.place = uiState.place
				lesson.teacher = uiState.teacher
				lesson.len = uiState.len
				lesson.color = uiState.color
				lesson.week = 1L shl week      // 仅该日期所在周
				val group = col.getOrNull(editSlot) ?: LessonGroup(day + 1, editSlot + 1).also { col[editSlot] = it }
				group.addLesson(lesson)
				// 【修改版】粘贴立即写入后编辑框保持打开: 切换为"编辑已写入的课程",
				// 之后点保存是就地修改, 不会重复添加同一门课
				isNewLesson = false
				editLessonIndex = group.lessons.indexOf(lesson)
				originLen = uiState.len
			}else{
			val group = col.getOrNull(editSlot)
			// 【第九十五轮】优先用副本列里锁定的那一节(force 清理后下标可能错位)
			val lesson = editingInCol ?: group?.lessons?.getOrNull(editLessonIndex)
			if(lesson != null){
				lesson.name = uiState.name
				lesson.place = uiState.place
				lesson.teacher = uiState.teacher
				lesson.len = uiState.len
				lesson.color = uiState.color
				lesson.week = 1L shl week
				// 【第九十五轮】U -> A: 除课程颜色外全部内容与最新原始课表一致时恢复为 A
				lesson.type = if(LessonTableRepository.matchesOriginalLesson(day, editSlot, lesson)) 0 else 1
			}
			}
			// 清除空课程组
			col.getOrNull(editSlot)?.let { if(it.lessons.isEmpty()) col[editSlot] = null }
			LessonTableRepository.saveLessonTable()
			LessonTableRepository.notifyLessonTableChanged()
			refreshTick++
			if(closeEdit) isShowEdit = false
		}

		/**
		 * 【本轮】打开编辑框时同步"编辑上下文"(与学期课表 clickLesson 同一套字段)
		 *
		 * 周次选择器在日课表里是隐藏的(按具体日期编辑, 周次只可能是本周),
		 * 但表单仍要记下"所在周/星期/节次"和"上课周数基准":
		 * 一是 [currentFormLesson] 复制课程时能带上正确的周次(以前复制出的课程周次为空),
		 * 二是内容改动检测(contentChange/weekChange)与学期课表完全同口径。
		 *
		 * @param lesson 正在编辑的课程; 新建课程传 null
		 */
		fun bindEditContext(slot: Int, lesson: Lesson?){
			uiState.editContextWeek = week
			uiState.editDayOfWeek = day
			uiState.editTimeSlot = slot
			uiState.editingLesson = lesson
			uiState.originWeekMask = lesson?.week ?: 0L
			val weekCount = uiState.totalWeek.coerceAtLeast(1)
			if(uiState.week.size != weekCount) uiState.week = List(weekCount){ false }.toMutableStateList()
			// 新建课程按"就在本周这一周上"(与学期课表双击空白格一致); 已有课程沿用其上课周次
			val thisWeek = 1L shl week.coerceIn(0, 62)
			val mask = lesson?.week?.takeIf { it != 0L } ?: thisWeek
			for(i in 0 until weekCount) uiState.week[i] = (mask and (1L shl i)) != 0L
		}

		val uiEvent = remember {
			object: TermLessonUIEvent{
				override fun hideEdit(){ isShowEdit = false }

				override fun toastWarning(message: String){
					activity.toast.toastWarning(message)
				}

				override fun saveLesson(force: Boolean){ performSave(force) }

				/**
				 * 【本轮】粘贴课程: 点右上角"完成"后才真正写入该日期(覆盖该时段)。
				 * 之前粘贴是立刻写库的, 于是"没点完成直接退出"也会把课程存进课表。
				 */
				@Suppress("UNUSED_PARAMETER")
				override fun pasteLessonNow(pasted: Lesson, overwrite: Lesson?){
					table.replaceLessonAt(week, day, editSlot, pasted)
					LessonTableRepository.saveLessonTable()
					LessonTableRepository.notifyLessonTableChanged()
					refreshTick++
					uiState.name = pasted.name
					uiState.place = pasted.place
					uiState.teacher = pasted.teacher
					uiState.color = pasted.color
					uiState.len = pasted.len.coerceAtLeast(1)
					isNewLesson = false
					editLessonIndex = 0
					originLen = uiState.len
					uiState.takeSnapshot()
					isShowEdit = false
				}

				/**
				 * 【本轮修复】与学期课表编辑界面一致: 空白课程(点"添加"后还没保存)视为"无课程",
				 * 点删除时直接给气泡, 不弹删除确认框 —— 否则会出现"删了个空课程却提示已删除"。
				 * 日课表里列出的课都只取本周生效的课程(灰色课程不显示), 因此无需灰色判定。
				 */
				override fun deleteBlockedMessage(): String? {
					if(isNewLesson || editLessonIndex < 0) return "没有可删除的课程"
					val lesson = table.effectiveDay(week, day).getOrNull(editSlot)?.lessons?.getOrNull(editLessonIndex)
					return if(lesson == null) "没有可删除的课程" else null
				}
			}
		}

		// ---------- 整天复制状态(按日期) ----------
		// 【课表锁定】锁定后禁用全部编辑操作(复制 / 恢复 / 删除 / 调休)
		val locked = LessonTableRepository.lockLesson
		var copyFromWeek by remember { mutableIntStateOf(-1) }
		var copyFromDay by remember { mutableIntStateOf(-1) }

		// 删除当日课表确认
		var askDeleteDay by remember { mutableStateOf(false) }

		/** 【第四十八轮】删除单门课程前确认: (节次, 该节次内课程下标) */
		var askDeleteLesson by remember { mutableStateOf<Pair<Int, Int>?>(null) }

		// 复制整天课表: 选择源日期的弹窗(与手动调休同款日历样式)
		var showCopyDatePicker by remember { mutableStateOf(false) }

		/** 弹出日期选择器选择源日期(与手动调休同款日历样式) */
		fun pickCopyDate(){
			showCopyDatePicker = true
		}

		Column(modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {

		// 标题栏:
		// 第一行: "编辑X月X日课表" 单独一行 **真正居中**。
		// 两栏操作区(复制/从图/手动 在左列竖直堆叠, 删除/科大 在右列对齐)由下方 ScheduleAdjustActions
		// 与本行"复制|删除"共同构成; "调休管理"放在操作区下方, 不打断左列竖排。
		// 【第三十二轮·按要求】操作按钮改成"两栏各占半屏、按钮在自己那半屏内居中":
		// 左列按钮中心落在屏幕 1/4 处, 右列按钮中心落在 3/4 处。
		// 所以这一块**不能**再套 8dp 的左右内边距(会让中心偏内), 内边距只加在标题上。
		Column(modifier = Modifier.fillMaxWidth()) {
			Text(
				text = "编辑${dateText(table, week, day)}（${Data.WEEK_STRING[day]}）课表",
				modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
				style = MaterialTheme.typography.bodyMedium,
				maxLines = 1,
				textAlign = TextAlign.Center,
				overflow = TextOverflow.Ellipsis,
				color = androidx.compose.ui.graphics.Color.Black
			)

			// 第一行操作按钮: 复制(左) 与 删除(右)
			// 与下方 ScheduleAdjustActions 的「从图片导入调休安排 / 科大官网调休同步」「手动调休」对齐,
			// 构成左列(复制/从图/手动) + 右列(删除/科大) 两栏布局。
			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically
			) {
				// 【本轮·按要求】「复制整天课表」/「删除当天课表」不再直接 `weight(1F)` 撑满半行:
				// TextButton 一旦被撑开, 波纹就跟着铺满整个半行, 又长又难看(用户原话"长度太长")。
				// 这里改成 Box 占位(负责左右对齐) + 内部 TextButton 按内容自适应宽度,
				// 波纹就只包住文字 + 12dp/8dp 内边距, 与「复制课程」完全一致 —— 只是长度不同。
				Box(modifier = Modifier.weight(1F), contentAlignment = Alignment.Center) {
					TextButton(onClick = { pickCopyDate() }, enabled = !locked, colors = textActionFilledColors(),
						contentPadding = PaddingValues(horizontal = TEXT_ACTION_H_PADDING, vertical = TEXT_ACTION_V_PADDING)) {
						// 标签左对齐: 与下方「从图片导入…」「手动调休」的"从图/手动"竖直对齐
						Text(text = "复制整天课表", maxLines = 1)
					}
				}
				// 【第五十五轮·按要求】删除「恢复当天课表」功能: 这一行原来在"当天课表已被删除"时会
				// 换成"恢复当天课表", 现在恒为「删除当天课表」(删除操作本身不可撤销, 见下面的确认弹窗)。
				Box(modifier = Modifier.weight(1F), contentAlignment = Alignment.Center) {
					TextButton(onClick = { askDeleteDay = true }, enabled = !locked, colors = textActionFilledColors(),
						contentPadding = PaddingValues(horizontal = TEXT_ACTION_H_PADDING, vertical = TEXT_ACTION_V_PADDING)) {
						// 【第五十五轮·按要求】蓝底上的红字改回红色(「取消」等非红色按钮仍为白字)
						Text(text = "删除当天课表", maxLines = 1, color = MaterialTheme.colorScheme.error)
					}
				}
			}

		}

		// 调休管理: 进入独立调休管理页(查看/删除已有调休)
		// 按要求放在「从图片导入调休安排 | 科大官网调休同步」这一行的**正上方居中**位置
		Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
			TextButton(onClick = {
				ComposeActivity.startActivity(activity, Keys.Page.AdjustPage)
			}, colors = textActionFilledColors(),
				contentPadding = PaddingValues(horizontal = TEXT_ACTION_H_PADDING, vertical = TEXT_ACTION_V_PADDING), enabled = !locked) {
				Text(text = "调休管理")
			}
		}

		ScheduleAdjust.ScheduleAdjustActions(activity, { message ->
			activity.toast.toastOK(message)
			refreshTick++
		}, locked = locked)

		// 复制整天课表: 选择源日期(与手动调休同款日历样式)
		if(showCopyDatePicker){
			ScheduleAdjust.AdjustDatePickerDialog(
				title = "选择要复制哪一天的课表",
				initialDate = DateUtils.YMD.format(Calendar.getInstance().also { it.time = table.weekStartDate(week); it.add(Calendar.DATE, day) }.time),
				onDismiss = { showCopyDatePicker = false }
			){ key ->
				val sel = Calendar.getInstance().apply {
					time = DateUtils.YMD.parse(key)!!
					set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
				}
				val diffDays = ((mondayOf(sel.time).timeInMillis - mondayOf(table.startDay).timeInMillis) / 86400000L).toInt()
				val fromWeek = diffDays / 7
				val fromDay = sel[Calendar.DAY_OF_WEEK].let { if(it == Calendar.SUNDAY) 6 else it - 2 }
				if(fromWeek < 0 || fromWeek >= table.totalWeek){
					activity.toast.toastWarning("所选日期不在学期范围内")
				}else if(fromWeek == week && fromDay == day){
					activity.toast.toastWarning("不能复制同一天")
				}else{
					copyFromWeek = fromWeek
					copyFromDay = fromDay
				}
				showCopyDatePicker = false
			}
		}

			Spacer(modifier = Modifier.height(4.dp))

			// 每一节的课程列表
			//
			// 【问题8】只渲染**本教学周生效**的课程: 不在本教学周上课的课程(其它周才上的)在本页隐藏,
			// 避免"把匹配该星期的所有课程全列出来"造成的混杂。
			// 注意: 这里只过滤**展示**, 底层数据完全不动 —— 编辑/删除依旧按"原始下标"操作真实的课程列。
			val weekMask = 1L shl week.coerceIn(0, 62)
			val displaySlots: Array<List<Pair<Int, Lesson>>> = remember(refreshTick, week, day) {
				val column = table.effectiveDay(week, day)
				Array(column.size) { i ->
					val g = column[i] ?: return@Array emptyList()
					// pair = (原始下标, 课程), 保证编辑/删除作用在正确的课程上
					g.lessons.mapIndexed { idx, lesson -> idx to lesson }
						.filter { it.second.week and weekMask != 0L }
				}
			}
			for(slot in 0 until slotCount){
				// 【第五十七轮·按要求】条目外观与「时间表管理」的卡片统一: 白底 + 6dp 阴影
				Card(
					modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
					colors = CardDefaults.cardColors(containerColor = Color.White),
					elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
				) {
					Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
						Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
							Text(
								text = "第${slot + 1}节",
								style = MaterialTheme.typography.titleMedium,
								modifier = Modifier.weight(1F)
							)
							run {
								val t0 = times[0].getOrNull(slot)
								val t1 = times[1].getOrNull(slot)
								Text(
									text = if(t0 != null && t1 != null) "$t0 - $t1" else "",
									style = MaterialTheme.typography.bodySmall,
									color = MaterialTheme.colorScheme.secondary,
									textAlign = TextAlign.End,
									modifier = Modifier.width(112.dp).padding(end = 4.dp)
								)
							}
							val slotLessons = displaySlots.getOrNull(slot) ?: emptyList()
							// 【修改版】“延续”时段(被上方多节课程覆盖)不提供添加/编辑按钮
							val coveringSlot = if(slotLessons.isEmpty()) table.occupantsAt(week, day, slot)
								.firstOrNull { it.startSlot < slot && it.lesson.week and weekMask != 0L } else null
							if(coveringSlot == null){
							TextButton(onClick = {
								// 【修改版】该时段已有课程 -> 按钮显示"编辑"并直接编辑该课程; 无课 -> "添加"
								val first = slotLessons.firstOrNull()
								if(first != null){
									editSlot = slot
									editLessonIndex = first.first
									isNewLesson = false
									uiState.name = first.second.name
									uiState.place = first.second.place
									uiState.teacher = first.second.teacher
									uiState.len = first.second.len
									uiState.color = first.second.color
									uiState.maxLen = (slotCount - slot).coerceAtLeast(1)
									uiState.overlapPrompt = null
									originLen = first.second.len
									bindEditContext(slot, first.second)
									uiState.takeSnapshot()
									isShowEdit = true
									return@TextButton
								}
								// 添加新课(仅该日期)
								editSlot = slot
								editLessonIndex = -1
								isNewLesson = true
								uiState.name = ""
								uiState.place = ""
								uiState.teacher = ""
								uiState.len = 1
								uiState.color = 0
								// 整节对齐: 最多占用到时间末尾
								uiState.maxLen = (slotCount - slot).coerceAtLeast(1)
								uiState.overlapPrompt = null
								originLen = 1
								bindEditContext(slot, null)
								uiState.takeSnapshot()   // 记下"未修改"基准(退出编辑时据此提示保存)
							isShowEdit = true
							}, enabled = !locked,
							// 【第三十四轮·按要求】"形状不一样、留白太大"的两个根因都在这里:
							//  ① 形状:「删除」用的是 `textActionOutlined`(裸 clickable + padding),
							//     高度只有"文字高 + 上下各 6dp" ≈ 32dp, 药丸半径 = 16dp;
							//     而「添加/编辑」是 TextButton, 有 40dp 最小高度, 半径 = 20dp ——
							//     一个偏扁一个偏鼓, 并排放着就是"形状不一样"。
							//  ② 留白: 文字外面还套了 `Modifier.width(56.dp)`(那是 4 个汉字「复制课程」
							//     的文字宽), 两个字的"添加"被塞进 56dp 里居中, 左右各多出一截空白。
							// 现在三个按钮一律是 TextButton, 参数与「复制课程」逐字相同:
							// 同一个 border、同一套 contentPadding、同一个 40dp 最小高度,
							// 宽度按"2 个字的文字宽 + 左右留白"现算, 文字正好填满按钮。
							modifier = Modifier.width(actionWidth()),
							colors = textActionFilledColors(),
							contentPadding = PaddingValues(horizontal = TEXT_ACTION_H_PADDING, vertical = TEXT_ACTION_V_PADDING)) { Text(text = if(slotLessons.isEmpty()) "添加" else "编辑", maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
							}else{
								// 【修改版】延续时段占位: 与按钮等宽, 保证时间列竖直对齐
								Spacer(modifier = Modifier.width(actionWidth()))
							}
						}

						val lessons: List<Pair<Int, Lesson>> = displaySlots.getOrNull(slot) ?: emptyList()
						if(lessons.isEmpty()){
							// 本节可能被上方跨多节的课程覆盖(例如两节连上的课),
							// 此时显示"延续"而不是"暂无课程", 避免误解为课程丢失
							// (同样只认"本教学周生效"的课程)
							val covering = table.occupantsAt(week, day, slot)
								.firstOrNull { it.startSlot < slot && it.lesson.week and weekMask != 0L }
							if(covering != null){
								Text(
									text = "↑ 第${covering.startSlot + 1}~${covering.startSlot + covering.len}节「${covering.lesson.name}」的延续",
									style = MaterialTheme.typography.bodyMedium,
									color = androidx.compose.ui.graphics.Color.Black,
									modifier = Modifier.padding(bottom = 8.dp)
								)
							}else{
								Text(
									text = "暂无课程",
									style = MaterialTheme.typography.bodyMedium,
									color = MaterialTheme.colorScheme.secondary,
									modifier = Modifier.padding(bottom = 8.dp)
								)
							}
						}else{
							for((lessonIdx, lesson) in lessons){
								Row(
									// 【修改版】取消点击课程行进入编辑: 编辑入口统一为该时段的"编辑"按钮
									modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
									verticalAlignment = Alignment.CenterVertically
								) {
									Column(modifier = Modifier.weight(1F)) {
										Text(text = lesson.name, style = MaterialTheme.typography.bodyMedium, color = androidx.compose.ui.graphics.Color.Black)
										Text(
											text = weeksText(lesson.week, LessonTableRepository.lessonTable.totalWeek) +
												(if(lesson.place.isEmpty()) "" else "  ${lesson.place}") +
												(if(lesson.teacher.isEmpty()) "" else "  ${lesson.teacher}"),
											style = MaterialTheme.typography.bodySmall,
											color = androidx.compose.ui.graphics.Color.Black
										)
									}
									// 【第三十四轮·按要求】「删除」也改用 TextButton(与「添加/编辑」「复制课程」
									// 同一套参数): 40dp 高的药丸 + 同样的灰色描边 + 同样的 primary 波纹。
									// 以前是 `Modifier.textActionOutlined`, 高度只有 32dp(半径 16dp), 与「添加/编辑」
									// 并排时形状明显不同 —— 这就是用户说的"形状不一样"。
									// 文字颜色仍单独指定(红色), 不影响波纹(follow contentColor = primary)。
									TextButton(
										onClick = {
											// 【问题7】灰色课程(本教学周不上课、只是别的周同时间有)禁止删除
											if(lesson.week and weekMask == 0L){
												activity.toast.toastWarning("该课程本周不上课，不能删除")
											}else{
												// 【第四十八轮】删除前弹窗确认(与其它删除操作一致)
												askDeleteLesson = slot to lessonIdx
											}
										},
										enabled = !locked,
										modifier = Modifier.width(actionWidth()),
										colors = textActionFilledColors(),
										contentPadding = PaddingValues(horizontal = TEXT_ACTION_H_PADDING, vertical = TEXT_ACTION_V_PADDING)
									) {
										// 【第五十五轮·按要求】浅蓝填充上的红字改回红色(「取消」等非红色按钮仍是白字)
										Text(
											text = "删除",
											maxLines = 1,
											textAlign = TextAlign.Center,
											color = MaterialTheme.colorScheme.error,
											modifier = Modifier.fillMaxWidth()
										)
									}
								}
							}
						}
					}
				}
			}

			Spacer(modifier = Modifier.height(24.dp))
		}

		if(askDeleteDay){
			Dialogs.AskDialog(
				title = "删除当日课表",
				// 【第五十五轮·按要求】"恢复当天课表"已删除, 这句话改成"此操作不可撤销"
				content = "是否删除 ${dateText(table, week, day)}（${Data.WEEK_STRING[day]}）的全部课程？此操作不可撤销。",
				onDismiss = { askDeleteDay = false },
				confirmText = "删除",
				danger = true,
				onConfirm = {
					askDeleteDay = false
					if(LessonTableRepository.deleteDayLessonTable(week, day)){
						refreshTick++
						activity.toast.toastOK("已删除${dateText(table, week, day)}的课表")
					}else{
						activity.toast.toastError("删除失败")
					}
				}
			)
		}

		// 【第四十八轮】单门课程删除确认(红色"删除")
		if(askDeleteLesson != null){
			val (askSlot, askLessonIdx) = askDeleteLesson!!
			val targetName = table.editDayOverride(week, day).getOrNull(askSlot)?.lessons?.getOrNull(askLessonIdx)?.name ?: "该课程"
			Dialogs.AskDialog(
				title = "删除课程",
				content = "是否删除「${targetName}」？此操作不可撤销。",
				onDismiss = { askDeleteLesson = null },
				confirmText = "删除",
				danger = true,
				onConfirm = {
					val col = table.editDayOverride(week, day)
					val g = col.getOrNull(askSlot)
					// 【问题8】按"原始下标"删除, 与页面隐藏了哪些课程无关
					val target = g?.lessons?.getOrNull(askLessonIdx)
					if(g != null && target != null){
						g.removeLesson(target)
						if(g.lessons.isEmpty()) col[askSlot] = null
						LessonTableRepository.saveLessonTable()
						LessonTableRepository.notifyLessonTableChanged()
						refreshTick++
						activity.toast.toastOK("已删除${target.name}")
					}
					askDeleteLesson = null
				}
			)
		}

		if(copyFromWeek >= 0){
			val srcText = dateText(table, copyFromWeek, copyFromDay)
			Dialogs.AskDialog(
				title = "覆盖确认",
				content = "是否用${srcText}（${Data.WEEK_STRING[copyFromDay]}）的全部课程覆盖当前日期（${dateText(table, week, day)} ${Data.WEEK_STRING[day]}）？",
				onDismiss = { copyFromWeek = -1; copyFromDay = -1 },
				onConfirm = {
					table.copyDayByDate(copyFromWeek, copyFromDay, week, day)
					LessonTableRepository.saveLessonTable()
					LessonTableRepository.notifyLessonTableChanged()
					refreshTick++
					activity.toast.toastOK("已复制${srcText}的课程到当前日期")
					copyFromWeek = -1; copyFromDay = -1
				}
			)
		}

		// 【本轮·按要求】"未保存"的询问统一挪到编辑弹窗**关闭之前**(见 EditLessonSheet 的
		// requestClose / confirmValueChange): 有改动时先弹窗、不退出, 选完才退出 ——
		// 与「手动调休」的未保存行为一致。这里原来"先退出、再补弹窗"的逻辑已删除。

		// ---------- 弹窗: 编辑课程 (复用学期课表编辑界面, 隐藏周次选择) ----------
		// 统一走 EditLessonSheet: 退出需要"较长下滑", 且有未保存修改时提示保存
		TermLesson.EditLessonSheet(
			uiState = uiState,
			uiEvent = uiEvent,
			onDismissed = { isShowEdit = false },
			visible = isShowEdit,
			hideWeekPicker = true,
			onPickFromTable = { showPicker = true },
			onCopyLesson = {
				if(uiState.name.isEmpty()){
					activity.toast.toastWarning("空白课程不可复制")
				}else{
					// 【本轮】与学期课表一致: 复制出的课程带上上课周数(以前手工构造的 Lesson 周次为 0)
					LessonClipboard.copy(currentFormLesson(uiState))
					activity.toast.toastOK("已复制课程")
				}
			},
			onPasteLesson = {
				val pasted = LessonClipboard.paste()
				if(pasted == null){
					activity.toast.toastWarning("剪贴板暂无课程")
				}else{
					// 【修改版】目标位置已有课程 -> 先弹覆盖确认(与学期课表的粘贴/拖动覆盖同一个样式)
					val conflict = pasteConflictAt(editSlot, pasted)
					if(conflict == null){
						uiState.name = pasted.name
						uiState.place = pasted.place
						uiState.teacher = pasted.teacher
						uiState.color = pasted.color
						uiState.len = pasted.len.coerceAtLeast(1)
						// 【本轮】粘贴只填表单, **点右上角"完成"才写入课表**(与学期课表一致)
						uiState.pasteFillMode = true
						uiState.pickFillMode = false
						activity.toast.toastOK("已粘贴课程")
					}else{
						pasteOverlap = pasted to conflict
					}
				}
			},
			onDeleteLesson = {
				// 【需求1】仅从该日期移除当前编辑的课程(单日编辑模型下即整门从该日期删除)
				val col = table.editDayOverride(week, day)
				val g = col.getOrNull(editSlot)
				val target = g?.lessons?.getOrNull(editLessonIndex)
				if(g != null && target != null){
					g.removeLesson(target)
					if(g.lessons.isEmpty()) col[editSlot] = null
					LessonTableRepository.saveLessonTable()
					LessonTableRepository.notifyLessonTableChanged()
					refreshTick++
					activity.toast.toastOK("已删除${target.name}")
				}
				// 【本轮修复】删除后关闭编辑框不再询问"是否保存"(与学期课表界面一致):
				// 先把"未保存"基准复位, 否则 LaunchedEffect 会因表单内容与快照不同而弹保存询问。
				uiState.takeSnapshot()
				isShowEdit = false
			}
		)

		// 【修改版】粘贴位置重合 -> 覆盖确认(与学期课表的"课程重叠"弹窗同一形式)
		pasteOverlap?.let { pair ->
			val pasted = pair.first
			val conflict = pair.second
			val from = editSlot + 1
			val to = (editSlot + pasted.len.coerceAtLeast(1)).coerceAtMost(slotCount)
			Dialogs.AskDialog(
				title = "课程重叠",
				content = "该时间段已有课程，会造成课程重叠，覆盖会导致已有课程删除，是否确认覆盖？\n\n目标位置：" +
					"${dateText(table, week, day)}（${Data.WEEK_STRING[day]}）第${from}~${to}节（已有「${conflict.name}」）",
				onDismiss = { pasteOverlap = null },   // 取消: 什么都不做
				onConfirm = {
					pasteOverlap = null
					// 【本轮】确认覆盖 -> 只填充表单, 点"完成"才真正覆盖写入该日期
					uiState.name = pasted.name
					uiState.place = pasted.place
					uiState.teacher = pasted.teacher
					uiState.color = pasted.color
					uiState.len = pasted.len.coerceAtLeast(1)
					uiState.pasteFillMode = true
					uiState.pickFillMode = false
					activity.toast.toastOK("已粘贴课程")
				}
			)
		}

		// 【本轮】"从课表选择课程填充"位置重合 -> 覆盖确认(与"粘贴课程"完全同一形式与文案)
		pickOverlap?.let { pair ->
			val picked = pair.first
			val conflict = pair.second
			val from = editSlot + 1
			// 【第九十五轮】节数以被编辑课程为准, 目标区间按被编辑课程的节数展示
			val to = (editSlot + uiState.len.coerceAtLeast(1)).coerceAtMost(slotCount)
			Dialogs.AskDialog(
				title = "课程重叠",
				content = "该时间段已有课程，会造成课程重叠，覆盖会导致已有课程删除，是否确认覆盖？\n\n目标位置：" +
					"${dateText(table, week, day)}（${Data.WEEK_STRING[day]}）第${from}~${to}节（已有「${conflict.name}」）",
				onDismiss = { pickOverlap = null },   // 取消: 什么都不做(不填充表单)
				onConfirm = {
					pickOverlap = null
					// 确认覆盖 -> 用所选课程填充表单, 保存时覆盖该时段
					// 【第九十五/九十六轮】节数以被编辑课程为准(空白/新建课程同样保留当前节数)
					uiState.name = picked.name
					uiState.place = picked.place
					uiState.teacher = picked.teacher
					uiState.color = picked.color
					uiState.pasteFillMode = false
					uiState.pickFillMode = false
					activity.toast.toastOK("已选择「${picked.name}」")
				}
			)
		}

		// 从近期课表选择课程后填充到编辑表单
		if(showPicker){
			TermLesson.CoursePickerDialog(
				onDismiss = { showPicker = false },
				onPicked = { lesson ->
					showPicker = false
					// 【本轮】与"粘贴课程"一致: 该时段已有别的课程 -> 先弹"课程重叠"确认
					val conflict = pasteConflictAt(editSlot, lesson)
					if(conflict == null){
						uiState.name = lesson.name
						uiState.place = lesson.place
						uiState.teacher = lesson.teacher
						uiState.color = lesson.color
						// 【第九十五/九十六轮】从课表选择=填充: 节数以被编辑课程为准,
						// 空白/新建课程当前表单里的节数同样保留, 不被源课程覆盖
						uiState.pasteFillMode = false
						uiState.pickFillMode = false
						activity.toast.toastOK("已选择「${lesson.name}」")
					}else{
						pickOverlap = lesson to conflict
					}
				}
			)
		}
	}
}
