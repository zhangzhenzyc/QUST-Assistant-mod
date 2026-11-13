/*
 * 青科助手（修改版） —— 基于开源项目 QUST-Assistant 修改而成
 *
 * 原项目版权所有 (C) 2891954521  (https://github.com/2891954521/QUST-Assistant)
 * 原始开源许可: GNU General Public License v3.0 (GPL-3.0), 许可原文见 LICENSE 文件, 保持完整未改动。
 *
 * 修改版版权所有 (C) 2026 zhangzhenzyc  (https://github.com/zhangzhenzyc)
 * 本修改版同样以 GPL-3.0 发布; 附加版权声明见 NOTICE.md —— 两份版权声明相互独立、同时生效。
 */

package com.qust.helper.schedule

import com.qust.helper.data.lesson.Lesson
import com.qust.helper.data.lesson.LessonGroup
import com.qust.helper.data.lesson.LessonTable
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 【第九十八轮】"切换时间表前检查课程显示不全"的回归测试。
 *
 * 判据: 目标时间表节数 >= 课表实际占用的最大结束节次([LessonTable.maxUsedEndSlot])。
 * 最大结束节次 = "起始槽位下标(0基) + 连堂长度" 的最大值, 每周模板与
 * dayOverrides(单日手动覆盖)都要计入, 周位掩码为 0(已删除态)的课程不计。
 */
class TimeTableFitTest {

	/** 在指定 0 基槽位放一门课(周位掩码默认第 1 周) */
	private fun put(table: LessonTable, day: Int, slot: Int, len: Int = 1, week: Long = 1L, name: String = "课") {
		val group = table.getLessonGroupNotNull(day, slot)
		group.lessons += Lesson(name = name, len = len, week = week)
	}

	/** 空课表不占用任何节次, 任何时间表(哪怕 1 节)都放得下 */
	@Test
	fun emptyTableFitsAnySlotCount() {
		val table = LessonTable()
		assertEquals(0, table.maxUsedEndSlot())
		assertEquals(true, table.fitsTimeTable(1))
	}

	/** 第 3 节(0基2)单节课 -> 占到第 3 节; 3 节以上够, 2 节不够 */
	@Test
	fun singleLessonEndSlot() {
		val table = LessonTable()
		put(table, 0, 2)
		assertEquals(3, table.maxUsedEndSlot())
		assertEquals(false, table.fitsTimeTable(2))
		assertEquals(true, table.fitsTimeTable(3))
	}

	/** 连堂长度计入结束节次: 0基第7节起 2 连堂 -> 占到第 9 节 */
	@Test
	fun conjoinedLessonCountsLen() {
		val table = LessonTable()
		put(table, 3, 7, len = 2)
		assertEquals(9, table.maxUsedEndSlot())
		assertEquals(false, table.fitsTimeTable(8))
		assertEquals(true, table.fitsTimeTable(9))
	}

	/** 多天取最大: 周一第 2 节 + 周五第 10 节 -> 最大结束节 = 10 */
	@Test
	fun maxAcrossDays() {
		val table = LessonTable()
		put(table, 0, 1)
		put(table, 4, 9)
		assertEquals(10, table.maxUsedEndSlot())
		assertEquals(false, table.fitsTimeTable(9))
		assertEquals(true, table.fitsTimeTable(10))
	}

	/** 同一格多条课程: 有一条 2 连堂也按连堂算 */
	@Test
	fun multipleLessonsInSameGroup() {
		val table = LessonTable()
		put(table, 1, 4, len = 1, name = "A")
		put(table, 1, 4, len = 2, name = "B")
		assertEquals(6, table.maxUsedEndSlot())
	}

	/** 周位掩码为 0(已删除态)的课程不计入 */
	@Test
	fun deletedWeekMaskIgnored() {
		val table = LessonTable()
		put(table, 2, 9, week = 0L)
		put(table, 2, 5)
		assertEquals(6, table.maxUsedEndSlot())
		assertEquals(true, table.fitsTimeTable(6))
	}

	/** dayOverrides(单日手动覆盖)里的课程同样计入 */
	@Test
	fun dayOverridesCounted() {
		val table = LessonTable()
		put(table, 0, 1)
		val override = arrayOfNulls<LessonGroup>(LessonTable().lessons[0].size)
		override[9] = LessonGroup(1, 10, arrayOf(Lesson(name = "晚课", len = 1, week = 1L)))
		table.dayOverrides["2026-11-09"] = override
		// 模板只占到第 2 节, 覆盖列占到第 10 节 -> 取 10
		assertEquals(10, table.maxUsedEndSlot())
		assertEquals(false, table.fitsTimeTable(9))
		assertEquals(true, table.fitsTimeTable(10))
	}
}
