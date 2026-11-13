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

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.qust.helper.R
import com.qust.helper.data.Keys
import com.qust.helper.data.Page
import com.qust.helper.data.lesson.Lesson
import com.qust.helper.data.lesson.LessonGroup
import com.qust.helper.data.lesson.TimeTables
import com.qust.helper.model.LessonTableRepository
import com.qust.helper.ui.theme.TEXT_COLORS
import com.qust.helper.ui.theme.TEXT_COLOR_SECOND_COLOR
import com.qust.helper.ui.theme.colorSecondaryText
import com.qust.helper.ui.theme.tnum
import com.qust.helper.ui.widget.Texts

object DailyLesson {

	/**
	 * 今天是否被学校调休安排标记为放假
	 *
	 * 放假以学校官网通知为准: 放假当天实际上没有课要上, 当日课表不展示课程(课程数据仍保留)。
	 */
	fun isTodayHoliday(): Boolean {
		val table = LessonTableRepository.lessonTable
		val day = LessonTableRepository.dayOfWeek.intValue
		val week0 = LessonTableRepository.currentWeek.intValue - 1
		return table.isHoliday(table.dateKeyOf(week0, day))
	}

	/**
	 * 获取今天实际要上的课程(已应用单日调休; 放假当天返回空)
	 *
	 * 只显示"今天真的要上"的课:
	 * - 不在本周上课周次内的课程(即学期课表里显示为**灰色**的"暂时不上"的课程)不显示;
	 * - 超出学期总周数的日期不显示任何课程。
	 */
	fun getTodayLessonGroups(): Array<LessonGroup?> {
		val table = LessonTableRepository.lessonTable
		val day = LessonTableRepository.dayOfWeek.intValue
		val week0 = LessonTableRepository.currentWeek.intValue - 1
		if(table.isHoliday(table.dateKeyOf(week0, day))) return arrayOfNulls(TimeTables.MAX_SLOT_COUNT)
		return table.activeDay(week0, day)
	}

	val DailyLessonPage = Page(Keys.Page.DailyLessonPage, "今日课表", iconRes = R.drawable.ic_article) { _, padding, _, _ ->
		// 每次打开都重新计算当前周次/星期, 保证"本周是否上这门课"的判断是最新的
		LessonTableRepository.updateDate()
		DailyLessonUI(
			padding = padding,
			lessonGroups = getTodayLessonGroups(),
			currentWeek = LessonTableRepository.currentWeek.intValue,
			holiday = isTodayHoliday()
		)
	}

	@Composable
	fun DailyLessonUI(
		padding: PaddingValues,
		lessonGroups: Array<LessonGroup?>,
		currentWeek: Int = 1,
		holiday: Boolean = false
	) {
		val slotCount = TimeTables.currentSlotCount
		// 【第一百零三轮·按要求"跨段课程分段显示"】先算出每一节上应显示的课程:
		// 一门课跨上午/下午/晚上时, 在它覆盖的每一段各显示一次, 时间列只列本段的节次
		// (例: 第1~11节的课 -> 上午显示第1~4节、下午第5~8节、晚上第9~11节);
		// 同一节上有多门课(不同单双周)时与 getCurrentLesson 口径一致取第一门。
		val spanSlots = arrayOfNulls<SpanInfo>(slotCount)
		for(i in 0 until slotCount) {
			val lesson = lessonGroups.getOrNull(i)?.getCurrentLesson(currentWeek) ?: continue
			val end = (i + lesson.len.coerceAtLeast(1)).coerceAtMost(slotCount)
			for(j in i until end) if(spanSlots[j] == null) spanSlots[j] = SpanInfo(lesson, i)
		}
		Column(
			modifier = Modifier.padding(padding)
		) {
			if(holiday){
				// 放假当天: 以学校调休安排为准, 当天没有课要上
				Row(
					modifier = Modifier.padding(16.dp),
					verticalAlignment = Alignment.CenterVertically
				) {
					LessonLabel(color = TEXT_COLOR_SECOND_COLOR)
					Column(modifier = Modifier.weight(1F).padding(8.dp)) {
						Texts.SingleLineTextNoPadding(
							text = "今天放假",
							style = MaterialTheme.typography.titleLarge,
							color = colorSecondaryText
						)
						Texts.SingleLineTextNoPadding(
							text = "按学校调休安排当天不上课（课程保留，可在课表中查看）",
							color = colorSecondaryText
						)
					}
				}
				return@Column
			}

			Texts.SingleLineText(text = "上午课程")
			LessonSegment(spanSlots, first = 0, last = 3)

			Texts.SingleLineText(text = "下午课程")
			LessonSegment(spanSlots, first = 4, last = 7)

			Texts.SingleLineText(text = "晚上课程")
			LessonSegment(spanSlots, first = 8, last = slotCount - 1)
		}
	}

	/**
	 * 【第一百零三轮】上午/下午/晚上一个"段"的课程列表
	 *
	 * 从 first 到 last 逐节扫描 [spanSlots]: 命中课程就显示"本段内的部分"
	 * (时间列只列本段的节次, 节次区间也按本段裁剪), 并跳到该课在本段内的结尾继续;
	 * 整段没有课则显示"空闲"(首末时间合并成一行)。
	 */
	@Composable
	private fun LessonSegment(spanSlots: Array<SpanInfo?>, first: Int, last: Int) {
		var idx = first
		var noLesson = true
		while(idx <= last) {
			val sp = spanSlots.getOrNull(idx)
			if(sp == null) { idx++; continue }
			noLesson = false
			val end = minOf(sp.startSlot + sp.lesson.len.coerceAtLeast(1), last + 1)
			LessonItem(slot = idx, len = end - idx, lesson = sp.lesson)
			idx = end
		}
		if(noLesson) LessonItemFree(slot = first, len = (last - first + 1).coerceAtLeast(1))
	}

	/** 某一节上应显示的课程及其整课起始节(用于把跨段的课分段展示) */
	private class SpanInfo(val lesson: Lesson, val startSlot: Int)

	@Composable
	fun LessonItem(slot: Int = 0, len: Int = 1, lesson: Lesson = Lesson(), ){
		Row(
			Modifier.padding(8.dp).height(intrinsicSize = IntrinsicSize.Min),
			verticalAlignment = Alignment.CenterVertically
		) {

			TimeColumn(slot = slot, len = len)

			LessonLabel(color = Color(TEXT_COLORS[lesson.color]))

			Column(modifier = Modifier.weight(1F).padding(8.dp)) {
				Texts.SingleLineTextNoPadding(text = lesson.name, style = MaterialTheme.typography.titleLarge)
				Texts.SingleLineTextNoPadding(
					text = if(lesson.teacher.isEmpty() || lesson.place.isEmpty()) "${lesson.place}${lesson.teacher}" else "${lesson.place} | ${lesson.teacher}",
					color = colorSecondaryText
				)
			}
		}
	}

	@Composable
	fun LessonItemFree(slot: Int, len: Int = 1){
		Row(
			Modifier.padding(8.dp).height(intrinsicSize = IntrinsicSize.Min),
			verticalAlignment = Alignment.CenterVertically
		) {

			TimeColumn(slot = slot, len = len, free = true)

			LessonLabel(color = TEXT_COLOR_SECOND_COLOR)

			Column(modifier = Modifier.weight(1F).padding(8.dp)) {
				Texts.SingleLineTextNoPadding(
					text = "空闲",
					style = MaterialTheme.typography.titleLarge,
					color = colorSecondaryText
				)
				Texts.SingleLineTextNoPadding(
					text = ""
				)
			}
		}
	}

	/**
	 * 左侧"节次 + 每节课的起止时间"
	 *
	 * 【第五十五轮·按要求】三处改动:
	 *  · 时间**上面加节数**: "第1-2节"(连堂按区间显示, 单节就是"第3节");
	 *  · 连堂课按**每一节**分段显示 —— 中间有 10 分钟课间, 不能只给"08:00 / 09:40"
	 *    这种跨过去的写法; 第1-2节应该是 08:00-08:45 / 08:55-09:40;
	 *  · 这一段(上午/下午/晚上)如果整段没课, 只显示**首末时间**(第五十六轮起合并成一行
	 *    "08:00-12:00", 与有课时的时间写法一致), 不用把 10 分钟间隔也列出来。
	 *
	 * 字号用 bodySmall(12sp): "08:00-08:45" 有 11 个字符, 用正文字号会把课程名挤得只剩几个字。
	 * 左对齐 + 固定最小宽度, 保证节次、各段时间都落在同一条竖线上。
	 */
	@Composable
	private fun TimeColumn(slot: Int, len: Int, free: Boolean = false){
		val times = TimeTables.currentTimes
		val lastIndex = (times[0].size - 1).coerceAtLeast(0)
		val first = slot.coerceIn(0, lastIndex)
		val last = (first + len.coerceAtLeast(1) - 1).coerceIn(0, lastIndex)
		Column(
			// 【第五十六轮】定宽(不是 widthIn min): 有课的行走"08:00-08:45"、空闲行走"08:00-12:00",
			// 都是 11 个字符, 宽度必须一致, 否则两行的时间会差半个字, 看着就是没对齐。
			modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp).width(TIME_COLUMN_WIDTH),
			horizontalAlignment = Alignment.Start
		) {
			// 【第五十八轮】数字用等宽字形: 让"第1-2节"与"第10-11节"的横杠、"08:00-08:45"与
			// "13:30-14:15"的冒号都落在同一列(比例数字会差 1~2dp, 看着就是没对齐)
			Text(
				text = if(first == last) "第${first + 1}节" else "第${first + 1}-${last + 1}节",
				color = colorSecondaryText,
				style = MaterialTheme.typography.labelMedium.tnum(),
				textAlign = TextAlign.Start,
				maxLines = 1,
				softWrap = false
			)
			if(free) {
				// 【第五十六轮·按要求】"空闲的也表示为 08:00-12:00 这种样式":
				// 整段没课时合并成**一行** "首节上课时间-末节下课时间",
				// 不再拆成上下两行(以前是 08:00 / 12:00, 看着像是两节不同的课)。
				Text(
					text = "${times[0].getOrElse(first){ "" }}-${times[1].getOrElse(last){ "" }}",
					color = colorSecondaryText,
					style = MaterialTheme.typography.bodySmall.tnum(),
					textAlign = TextAlign.Start,
					maxLines = 1,
					softWrap = false
				)
			}else{
				// 每一节一段时间(节间 10 分钟不显示, 但它们确实分开了)
				for(i in first..last) {
					Text(
						text = "${times[0].getOrElse(i){ "" }}-${times[1].getOrElse(i){ "" }}",
						color = colorSecondaryText,
						style = MaterialTheme.typography.bodySmall.tnum(),
						textAlign = TextAlign.Start,
						maxLines = 1,
						softWrap = false
					)
				}
			}
		}
	}

	/** 左侧时间列的**固定**宽度: 放得下"08:00-08:45"/"08:00-12:00"(11 个字符), 又不会把课程名挤得太窄 */
	// 【第一百零五轮】74 -> 78: 用户 OPPO 实测"时间右边有一点点遮挡"—— "08:55-09:40"
	// 11 个字符在 12sp 等宽字形下实宽略超 74dp, 最右字符贴着课时竖条; 加宽 4dp,
	// 课名列是 weight(1F) 自动让位, 不影响其它布局。
	private val TIME_COLUMN_WIDTH = 78.dp


	@Composable
	fun LessonLabel(color: Color){
		Canvas(modifier = Modifier.fillMaxHeight().padding(8.dp).width(6.dp)){
			drawRoundRect(
				color = color,
				topLeft = Offset(0F, 0F),
				size = Size(size.width, size.height),
				cornerRadius = CornerRadius(size.height / 2F, size.height / 2F),
			)
		}
	}
}
