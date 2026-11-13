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

import android.content.SharedPreferences
import android.graphics.Paint
import com.qust.helper.data.Setting
import com.qust.helper.data.eas.AcademicPageParser
import com.qust.helper.data.lesson.Lesson
import com.qust.helper.data.lesson.LessonClipboard
import com.qust.helper.data.lesson.LessonGroup
import com.qust.helper.data.lesson.LessonTable
import com.qust.helper.data.lesson.TimeTables
import com.qust.helper.ui.widget.LessonRenderData
import com.qust.helper.ui.widget.selectionLength
import com.qust.helper.utils.DateUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Calendar

/**
 * 排课/作息正确性验证套件
 * 覆盖: 时间解析边界、45分钟固定作息、无重叠、调休日期对齐、课表复制/清空/补齐、调休渲染逻辑
 * 通过反射注入内存版 SharedPreferences 初始化 Setting, 无需 Android 运行环境
 */
class ScheduleVerificationTest {

	private lateinit var lrd: LessonRenderData

	@Before
	fun setUp() {
		installMockSetting()
		// 每个用例都使用全新的内存配置: 重新读取并重置为内置三套作息, 避免用例间互相影响
		TimeTables.reload()
		TimeTables.resetToDefaults()
		lrd = LessonRenderData(hideTeacher = false, textPaint = Paint())
	}

	// ---------------- 时间解析 ----------------

	@Test
	fun parseTime_boundaries() {
		assertEquals(0, TimeTables.parseTime("00:00"))
		assertEquals(480, TimeTables.parseTime("08:00"))
		assertEquals(1439, TimeTables.parseTime("23:59"))
		// 非法输入一律返回 -1
		assertEquals(-1, TimeTables.parseTime("24:00"))      // 小时越界
		assertEquals(-1, TimeTables.parseTime("08:60"))      // 分钟越界
		assertEquals(-1, TimeTables.parseTime(""))           // 空
		assertEquals(-1, TimeTables.parseTime("abc"))        // 非数字
		assertEquals(-1, TimeTables.parseTime("08:00:00"))   // 多段
		assertEquals(-1, TimeTables.parseTime("08:0x"))      // 分钟非数字
		assertEquals(-1, TimeTables.parseTime("8"))          // 缺分钟
	}

	// ---------------- 固定45分钟作息生成 ----------------

	@Test
	fun fixedSchedule_strict45min() {
		val t = TimeTables.fixedSchedule(8, 0, 45, 10, TimeTables.MAX_SLOT_COUNT)
		assertEquals(2, t.size)
		assertEquals(TimeTables.MAX_SLOT_COUNT, t[0].size)
		assertEquals(TimeTables.MAX_SLOT_COUNT, t[1].size)

		val mins = TimeTables.periodMinutes(t)
		for(i in mins.indices) {
			assertEquals("第${i + 1}节必须为45分钟", 45, mins[i])
		}
		assertFalse("固定45分钟作息不应有冲突", TimeTables.hasConflict(t))
		assertEquals("第一节应 08:00", "08:00", t[0][0])
		// 【第五十八轮】节数上限 11 -> 12, 这里不再写死钟点, 按"起点 + 每节(45+10)"算, 以后改上限也不用动断言
		val expectedLast = TimeTables.formatTime(8 * 60 + (TimeTables.MAX_SLOT_COUNT - 1) * 55 + 45)
		assertEquals("最后一节下课应为 $expectedLast", expectedLast, t[1][TimeTables.MAX_SLOT_COUNT - 1])

		// 相邻节次: 上一节下课 + 课间 = 下一节上课
		for(i in 1 until t[0].size) {
			assertEquals(TimeTables.parseTime(t[0][i]), TimeTables.parseTime(t[1][i - 1]) + 10)
		}
		// 每节上课严格递增
		for(i in 1 until t[0].size) {
			assertTrue(TimeTables.parseTime(t[0][i]) > TimeTables.parseTime(t[0][i - 1]))
		}
	}

	@Test
	fun fixedSchedule_boundaries() {
		// 单节
		val one = TimeTables.fixedSchedule(9, 30, 45, 10, 1)
		assertEquals(1, one[0].size)
		assertEquals("09:30", one[0][0])
		assertEquals("10:15", one[1][0])
		assertEquals(45, TimeTables.periodMinutes(one)[0])
		assertFalse(TimeTables.hasConflict(one))

		// 零节
		val zero = TimeTables.fixedSchedule(8, 0, 45, 10, 0)
		assertEquals(0, zero[0].size)
		assertFalse(TimeTables.hasConflict(zero))
	}

	// ---------------- 内置作息表验证 ----------------

	@Test
	fun defaultTimetables_noConflict_and_durations() {
		for(index in 0 until TimeTables.TIMETABLE_COUNT) {
			val t = TimeTables.getDefaultTimes(index)
			assertEquals("作息$index 起止数组长度应一致", t[0].size, t[1].size)
			val mins = TimeTables.periodMinutes(t)
			for(i in mins.indices) {
				assertTrue("作息$index 第${i + 1}节时长应为正, 实际 ${mins[i]}", mins[i] > 0)
			}
			assertFalse("作息$index 不应存在时间冲突", TimeTables.hasConflict(t))
		}
		// 新冬季作息(图片, index=2) 应严格 45 分钟
		assertTrue("新冬季作息应为45分钟", TimeTables.periodMinutes(TimeTables.getDefaultTimes(2)).all { it == 45 })
	}

	// ---------------- 调休日期对齐 ----------------

	@Test
	fun weekStartDate_alignsToMonday() {
		// 开学日为周一
		val mon = LessonTable().apply { startDay = DateUtils.YMD.parse("2026-03-02")!! }
		assertEquals("2026-03-02", DateUtils.YMD.format(mon.weekStartDate(0)))
		assertEquals("2026-03-09", DateUtils.YMD.format(mon.weekStartDate(1)))

		// 开学日为周三 -> 第0周起始应回退到周一
		val wed = LessonTable().apply { startDay = DateUtils.YMD.parse("2026-03-04")!! }
		assertEquals("非周一开学, 第0周起点应为周一 2026-03-02", "2026-03-02", DateUtils.YMD.format(wed.weekStartDate(0)))
		assertEquals("周三开学日应为第0周第2天 2026-03-04", "2026-03-04", wed.dateKeyOf(0, 2))
	}

	@Test
	fun dateKeyOf_crossYear() {
		// 跨年边界: 开学 2025-12-29(周一)
		val lt = LessonTable().apply { startDay = DateUtils.YMD.parse("2025-12-29")!! }
		assertEquals("2025-12-29", lt.dateKeyOf(0, 0))   // 周一
		assertEquals("2026-01-05", lt.dateKeyOf(1, 0))   // 下周一跨年
		assertEquals("2026-01-11", lt.dateKeyOf(1, 6))   // 下周日(周一+6天)
	}

	@Test
	fun dateKeyOf_consistencyWithDisplay() {
		// 验证 dateKeyOf 与界面显示使用同一周一对齐规则: 以周一开学为例逐列核对
		val lt = LessonTable().apply { startDay = DateUtils.YMD.parse("2026-03-02")!! }
		val cal = Calendar.getInstance().also { it.time = lt.weekStartDate(0) }
		for(day in 0..6) {
			val expected = DateUtils.YMD.format(cal.time)
			assertEquals("第$day 列日期应一致", expected, lt.dateKeyOf(0, day))
			cal.add(Calendar.DATE, 1)
		}
	}

	// ---------------- 课表数据操作 ----------------

	@Test
	fun normalize_padsToMaxSlotCount() {
		val lt = LessonTable()
		// 模拟旧数据: 仅10节
		for(i in lt.lessons.indices) lt.lessons[i] = lt.lessons[i].copyOf(10)
		lt.normalize()
		for(i in lt.lessons.indices) {
			assertEquals("第$i 天应补齐到 ${TimeTables.MAX_SLOT_COUNT} 节", TimeTables.MAX_SLOT_COUNT, lt.lessons[i].size)
		}
		// 原有内容应保留
		lt.lessons[0][0] = LessonGroup(1, 1).also { it.lessons = arrayOf(Lesson().also { it.name = "高数" }) }
		lt.normalize()
		assertNotNull(lt.lessons[0][0])
		assertEquals("高数", lt.lessons[0][0]!!.lessons[0].name)
	}

	@Test
	fun override_getAndAbsent() {
		val lt = LessonTable().apply { startDay = DateUtils.YMD.parse("2026-03-02")!! }
		lt.overrides["2026-03-02"] = 1
		assertEquals(1, lt.getOverride("2026-03-02"))
		assertNull(lt.getOverride("2026-03-03"))
	}

	// ---------------- 调休渲染逻辑(纯函数) ----------------

	private fun holder(name: String, totalWeek: Int = 1): LessonRenderData.LessonHolder {
		val g = LessonGroup(1, 1)
		g.lessons = arrayOf(Lesson().also { it.name = name })
		return lrd.LessonHolder(g, totalWeek)
	}

	private fun makeBase(): Array<Array<LessonRenderData.LessonHolder?>> {
		val base = Array(7) { arrayOfNulls<LessonRenderData.LessonHolder?>(TimeTables.MAX_SLOT_COUNT) }
		val names = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
		for(d in 0..6) base[d][0] = holder(names[d])
		return base
	}

	@Test
	fun computeWeek_noOverrideReturnsBase() {
		val base = makeBase()
		val table = LessonTable().apply { startDay = DateUtils.YMD.parse("2026-03-02")!! }
		val result = lrd.computeWeek(0, base, table)
		assertTrue("无调休应原样返回 base", result === base)
	}

	@Test
	fun computeWeek_clearAndCopy() {
		val base = makeBase()
		val table = LessonTable().apply {
			startDay = DateUtils.YMD.parse("2026-03-02")!!   // 周一=2026-03-02
			overrides["2026-03-02"] = 0   // 周一放假: 只做标记, 课程保留
			overrides["2026-03-03"] = 3   // 周二补星期三的课
		}
		val result = lrd.computeWeek(0, base, table)

		// 放假不再清空课程(只标记), 该日期的课程仍会渲染出来
		assertNotNull("放假日期应保留原课程", result[0][0])
		assertEquals("放假日期课程不应被删除", "周一", result[0][0]!!.group.lessons[0].name)
		assertTrue("放假日期应被识别为放假标记", table.isHoliday("2026-03-02"))
		assertEquals("周二应复制星期三的课", "周三", result[1][0]!!.group.lessons[0].name)
		// 基础课表不被破坏
		assertNotNull(base[0][0])
		assertEquals("周一", base[0][0]!!.group.lessons[0].name)
	}

	@Test
	fun computeWeek_invalidActionFallsBack() {
		val base = makeBase()
		val table = LessonTable().apply {
			startDay = DateUtils.YMD.parse("2026-03-02")!!
			overrides["2026-03-04"] = 9   // 非法动作, 越界
		}
		val result = lrd.computeWeek(0, base, table)
		// 非法动作应回退原课表, 不崩溃, 且内容不变
		assertEquals("周三(非法动作)应回退原课", "周三", result[2][0]!!.group.lessons[0].name)
	}

	@Test
	fun computeWeek_overrideIsDateScoped() {
		// 放假标记只影响特定日期, 其它周同一星期不受影响
		val base = makeBase()
		val table = LessonTable().apply {
			startDay = DateUtils.YMD.parse("2026-03-02")!!
			overrides["2026-03-02"] = 0   // 仅第0周周一标记放假
		}
		val week0 = lrd.computeWeek(0, base, table)
		val week1 = lrd.computeWeek(1, base, table)
		assertNotNull("放假日期课程应保留", week0[0][0])
		assertEquals("周一", week0[0][0]!!.group.lessons[0].name)
		assertNotNull("第1周周一不受影响", week1[0][0])
		assertEquals("周一", week1[0][0]!!.group.lessons[0].name)
		assertFalse("第1周周一不应被视为放假", table.isHoliday(table.dateKeyOf(1, 0)))
	}

	// ---------------- 按日期编辑/复制(dayOverrides) ----------------

	private fun lessonOf(name: String, weekMask: Long = 1L): Lesson =
		Lesson(type = 1).also { it.name = name; it.week = weekMask }

	private fun groupOf(vararg names: String): LessonGroup =
		LessonGroup(1, 1).also { g -> g.lessons = names.map { lessonOf(it) }.toTypedArray() }

	@Test
	fun effectiveDay_priority_dayOverrideOverridesOverridesOverBase() {
		val lt = LessonTable().apply { startDay = DateUtils.YMD.parse("2026-03-02")!! }
		// 基础：周一有"模板课"
		lt.lessons[0][0] = groupOf("模板课")
		// 调休：周三(2026-03-04)补周一的课
		lt.overrides["2026-03-04"] = 1
		assertEquals("无手动覆盖时应走调休(补周一)", "模板课", lt.effectiveDay(0, 2)[0]!!.lessons[0].name)

		// 手动按日期覆盖优先级最高
		val col = arrayOfNulls<LessonGroup>(TimeTables.MAX_SLOT_COUNT)
		col[0] = groupOf("手动课")
		lt.dayOverrides["2026-03-04"] = col
		assertEquals("手动覆盖应优先于调休", "手动课", lt.effectiveDay(0, 2)[0]!!.lessons[0].name)

		// 放假(0) 只标记不清空; 未覆盖日期回退基础模板
		lt.lessons[3][0] = groupOf("周四模板")
		lt.overrides["2026-03-05"] = 0
		assertEquals("放假日期课程应保留", "周四模板", lt.effectiveDay(0, 3)[0]!!.lessons[0].name)
		assertTrue("该日期应被识别为放假", lt.isHoliday("2026-03-05"))
		assertEquals("周一(未覆盖)应为基础模板", "模板课", lt.effectiveDay(0, 0)[0]!!.lessons[0].name)
		assertNull("周日(无课且未覆盖)应为空", lt.effectiveDay(0, 6)[0])
	}

	@Test
	fun editDayOverride_onlyAffectsThatDate_andDeepCopy() {
		val lt = LessonTable().apply { startDay = DateUtils.YMD.parse("2026-03-02")!! }
		lt.lessons[5][0] = groupOf("周六模板")

		val col = lt.editDayOverride(0, 5)
		assertEquals("应复制生效课程", "周六模板", col[0]!!.lessons[0].name)
		// 编辑副本
		col[0]!!.lessons[0].name = "改过的"
		assertEquals("基础模板不应被修改", "周六模板", lt.lessons[5][0]!!.lessons[0].name)
		assertEquals("该日期生效值应更新", "改过的", lt.effectiveDay(0, 5)[0]!!.lessons[0].name)
		// 仅该日期受影响: 第1周周六仍为模板
		assertEquals("其它周周六不应受影响", "周六模板", lt.effectiveDay(1, 5)[0]!!.lessons[0].name)

		// 恢复
		lt.restoreDay(0, 5)
		assertEquals("恢复后应回到模板", "周六模板", lt.effectiveDay(0, 5)[0]!!.lessons[0].name)
		assertTrue("dayOverrides 中不应残留", lt.dayOverrides.isEmpty())
	}

	@Test
	fun copyDayByDate_scopedToTargetDateOnly() {
		val lt = LessonTable().apply { startDay = DateUtils.YMD.parse("2026-03-02")!! }
		// 第0周周二(2026-03-03) 有课, 周六为空
		lt.lessons[1][0] = groupOf("周二课")
		lt.copyDayByDate(0, 1, 0, 5)   // 复制到第0周周六(2026-03-07)

		val sat = lt.effectiveDay(0, 5)
		assertEquals("目标日期应得到源日期课程", "周二课", sat[0]!!.lessons[0].name)
		// 每周模板的周六不被修改
		assertNull("每周课表模板周六应保持为空", lt.lessons[5][0])
		// 其它周的周六不受影响
		assertNull("第1周周六不应受影响", lt.effectiveDay(1, 5)[0])
		// 源日期不变
		assertEquals("源日期课程不应改变", "周二课", lt.lessons[1][0]!!.lessons[0].name)
		// 深拷贝: 目标与源不是同一对象
		assertTrue("目标应为深拷贝", sat[0] !== lt.lessons[1][0])
		sat[0]!!.lessons[0].name = "修改后"
		assertEquals("修改目标不应影响源", "周二课", lt.lessons[1][0]!!.lessons[0].name)
	}

	@Test
	fun computeWeek_dayOverrideRendersOverStoredColumn() {
		val base = makeBase()
		val table = LessonTable().apply {
			startDay = DateUtils.YMD.parse("2026-03-02")!!
			val col = arrayOfNulls<LessonGroup>(TimeTables.MAX_SLOT_COUNT)
			col[0] = LessonGroup(6, 1).also { it.lessons = arrayOf(Lesson().also { l -> l.name = "手动周六" }) }
			dayOverrides["2026-03-07"] = col
		}
		val result = lrd.computeWeek(0, base, table)
		assertEquals("该日期应渲染手动覆盖内容", "手动周六", result[5][0]!!.group.lessons[0].name)
		assertEquals("其它日期仍为原课表", "周一", result[0][0]!!.group.lessons[0].name)
		// 第1周不受影响
		val week1 = lrd.computeWeek(1, base, table)
		assertEquals("第1周周六仍为原课表", "周六", week1[5][0]!!.group.lessons[0].name)
	}

	// ---------------- 当前作息 delta ----------------

	@Test
	fun currentDeltas_monotonicFor45min() {
		com.qust.helper.model.LessonTableRepository.setTimeTableValue(2)   // 新冬季作息(45分钟/节)
		val deltas = TimeTables.currentDeltas()
		assertEquals(0, deltas[0])
		for(i in 1 until deltas.size) {
			assertTrue("delta 应单调递增", deltas[i] > deltas[i - 1])
		}
	}

	// ---------------- 拖动移动课程 ----------------

	// ---------------- 选择框长度(下拉扩展) ----------------

	@Test
	fun selectionLength_boundaries() {
		assertEquals("起点拖到自身应为1", 1, selectionLength(0, 0, 11))
		assertEquals("下拉1格应为2", 2, selectionLength(0, 1, 11))
		assertEquals("下拉2格应为3", 3, selectionLength(0, 2, 11))
		assertEquals("下拉3格应为4", 4, selectionLength(0, 3, 11))
		assertEquals("超过4格应封顶为4", 4, selectionLength(0, 9, 11))
		assertEquals("向上拖不扩展", 1, selectionLength(5, 3, 11))
		assertEquals("接近末尾应不越界", 1, selectionLength(10, 10, 11))
		assertEquals("起点非法应为1", 1, selectionLength(-1, 3, 11))
		assertEquals("节数非法应为1", 1, selectionLength(0, 3, 0))
	}

	// ---------------- 课程剪贴板(复制/粘贴课表) ----------------

	@Test
	fun clipboard_copyPaste_deepCopy() {
		LessonClipboard.clear()
		assertNull(LessonClipboard.paste())

		val src = lessonOf("物理")
		LessonClipboard.copy(src)

		val pasted = LessonClipboard.paste()!!
		assertEquals("物理", pasted.name)
		assertTrue("粘贴结果应为深拷贝", pasted !== src)

		// 修改源不影响剪贴板
		src.name = "改过的"
		assertEquals("剪贴板内容不应受源修改影响", "物理", LessonClipboard.paste()!!.name)

		LessonClipboard.clear()
	}

	// ---------------- 课程占用范围 / 重合检测 ----------------

	/** 构造一节带"占用节数"的课 */
	private fun lessonOfLen(name: String, len: Int, weekMask: Long = 1L): Lesson =
		Lesson(type = 1).also { it.name = name; it.len = len; it.week = weekMask }

	// ---------------- 调休识别: 中文数字日期 / 整段补漏 ----------------

	@Test
	fun parseText_chineseNumeralDates_expandsWholeStage() {
		val adjust = com.qust.helper.ui.page.lesson.ScheduleAdjust
		// 有的通知/截图用中文数字写日期, 之前完全识别不到
		val items = adjust.parseText("九月二十五日至十月七日放假调休，共十三天。", 2026, 9)
		assertEquals("应展开成整段13天", 13, items.size)
		assertEquals("2026-09-25", items.first().dateKey)
		assertEquals("2026-10-07", items.last().dateKey)
		assertTrue("应为放假标记", items.all { it.action == adjust.ACTION_CLEAR })
	}

	@Test
	fun parseText_fillsMissingPartByWholeTextScan() {
		val adjust = com.qust.helper.ui.page.lesson.ScheduleAdjust
		// 第一句能识别, 第二句的连接符被 OCR 吃掉 —— 以前只在"一条都没识别到"时才整段兜底, 于是漏掉后一句
		val text = "关于2026年国庆节放假安排的通知。9月30日放假。10月1日一10月3日放假。"
		val items = adjust.parseText(text, 2026, 9)
		val keys = items.map { it.dateKey }
		assertTrue("应识别到9月30日（$keys）", keys.contains("2026-09-30"))
		assertTrue("应补上10月1日（$keys）", keys.contains("2026-10-01"))
		assertTrue("应补上10月3日（$keys）", keys.contains("2026-10-03"))
	}

	@Test
	fun parseText_workDayExcludedFromRange() {
		val adjust = com.qust.helper.ui.page.lesson.ScheduleAdjust
		val items = adjust.parseText("10月1日至10月8日放假。10月11日（周日）上班。", 2026, 9)
		val keys = items.map { it.dateKey }
		assertFalse("上班日不应被标为放假", keys.contains("2026-10-11"))
		assertTrue("假期内的日期应保留", keys.contains("2026-10-01"))
	}

	@Test
	fun isElectiveCategory_classTableFilter() {
		val lt = LessonTable()
		// 班级课表要排除学生自选的课
		assertTrue("通识任选课应被排除", lt.isElectiveCategory("通识任选课"))
		assertTrue("专业任选应被排除", lt.isElectiveCategory("专业任选课"))
		assertTrue("公共选修应被排除", lt.isElectiveCategory("公共选修课"))
		assertTrue("公选应被排除", lt.isElectiveCategory("公选"))
		// 班级统一安排必须保留
		assertFalse("必修课不能排除", lt.isElectiveCategory("专业必修课"))
		assertFalse("通识必修不能排除", lt.isElectiveCategory("通识必修课"))
		assertFalse("空值不能误判", lt.isElectiveCategory(""))
	}

	// ---------------- 真实官网通知(《关于2026年中秋节、国庆节放假安排的通知》) ----------------

	private val NATIONAL_DAY_NOTICE =
		"各学院、部（处）、校区、校直各单位：根据《国务院办公厅关于2026年部分节假日安排的通知》" +
		"（国办发明电〔2025〕7号）精神，结合学校工作实际，现将2026年中秋节、国庆节放假安排通知如下。" +
		"一、放假时间9月25日（周五）至10月7日（周三）放假调休，共13天。" +
		"9月20日（周日）、10月10日（周六）、10月18日（周日）、10月25日（周日）、11月1日（周日）上班。" +
		"二、调课安排1.10月18日（周日）执行9月28日（周一）的教学安排。" +
		"2.10月25日（周日）执行9月29日（周二）的教学安排。3.11月1日（周日）执行9月30日（周三）的教学安排。" +
		"原10月18日、10月25日、11月1日的课程，由任课老师与学生沟通协商后自行补课。" +
		"4.法定调休日课程安排：9月20日执行10月6日的教学安排；10月10日执行10月7日的教学安排。"

	@Test
	fun parseText_officialNotice_2026NationalDay() {
		val adjust = com.qust.helper.ui.page.lesson.ScheduleAdjust
		val items = adjust.parseText(NATIONAL_DAY_NOTICE, 2026, 9)
		val map = items.associate { it.dateKey to it.action }

		// 一、放假时间: 9月25日~10月7日 共13天
		var holidayDays = 0
		val cal = java.util.Calendar.getInstance()
		cal.clear(); cal.set(2026, 8, 25)
		for(i in 0 until 13) {
			val key = com.qust.helper.utils.DateUtils.YMD.format(cal.time)
			if(map[key] == adjust.ACTION_CLEAR) holidayDays++
			cal.add(java.util.Calendar.DATE, 1)
		}
		assertEquals("放假应为9月25日~10月7日共13天（实际=$map）", 13, holidayDays)

		// 二、调课: 执行某日教学安排
		assertEquals("10月18日应补9月28日(周一)", 1, map["2026-10-18"])
		assertEquals("10月25日应补9月29日(周二)", 2, map["2026-10-25"])
		assertEquals("11月1日应补9月30日(周三)", 3, map["2026-11-01"])
		// 法定调休日: 9月20日执行10月6日(周二)、10月10日执行10月7日(周三)
		assertEquals("9月20日应补10月6日(周二)", 2, map["2026-09-20"])
		assertEquals("10月10日应补10月7日(周三)", 3, map["2026-10-10"])

		// 源日期必须记录成"具体日期", 便于按日期复制整天的课
		val oct18 = items.first { it.dateKey == "2026-10-18" }
		assertEquals("应记录源日期", "2026-09-28", oct18.sourceDateKey)
	}

	@Test
	fun parseText_ocrWithoutPunctuation_keepsWholeHoliday() {
		val adjust = com.qust.helper.ui.page.lesson.ScheduleAdjust
		// 截图 OCR 常把换行/句号丢掉 —— 整篇会变成"一句话"。
		// 以前这种文本里只要出现一次"上班", 全篇日期都会被当成上班日而从放假里删掉,
		// 于是假期最后一天(10月7日)也会消失, 表现为"部分调休识别不出来"。
		val text = "一、放假时间\n9月25日（周五）至10月7日（周三）放假调休，共13天\n" +
			"9月20日（周日）、10月10日（周六）、10月18日（周日）、10月25日（周日）、11月1日（周日）上班\n" +
			"二、调课安排\n1.10月18日（周日）执行9月28日（周一）的教学安排"
		val map = adjust.parseText(text, 2026, 9).associate { it.dateKey to it.action }
		assertEquals("假期第一天不能丢", adjust.ACTION_CLEAR, map["2026-09-25"])
		assertEquals("假期最后一天不能丢", adjust.ACTION_CLEAR, map["2026-10-07"])
		assertEquals("上班日不能被标成放假", 1, map["2026-10-18"])
		assertNull("9月20日不在假期内, 且是上班日", map["2026-09-20"])
	}

	@Test
	fun parseText_officialNotice_dragonBoat_omittedEndMonth() {
		val adjust = com.qust.helper.ui.page.lesson.ScheduleAdjust
		val text = "一、放假时间6月19日（周五）至21日（周日）放假，共3天。二、调课安排放假期间的教学安排按教务处、研究生处通知执行。"
		val map = adjust.parseText(text, 2026, 6).associate { it.dateKey to it.action }
		assertEquals("19日应放假", adjust.ACTION_CLEAR, map["2026-06-19"])
		assertEquals("20日应放假", adjust.ACTION_CLEAR, map["2026-06-20"])
		assertEquals("21日应放假", adjust.ACTION_CLEAR, map["2026-06-21"])
		assertEquals("应为3天", 3, map.count { it.value == adjust.ACTION_CLEAR })
	}

	// ---------------- 周次解析(班级课表只剩体育的根因) ----------------

	@Test
	fun parseWeekMask_allWeeksForms() {
		// 全角括号 + "周" 字: 以前会抛异常 -> 课程被当成 week=0 整门删掉
		val full = Lesson.parseWeekMask("1-16（周）")
		assertEquals("1周应上课", true, full and 1L != 0L)
		assertEquals("16周应上课", true, full and (1L shl 15) != 0L)
		assertEquals("17周不上课", false, full and (1L shl 16) != 0L)

		// 末尾"周"、不带括号
		val plain = Lesson.parseWeekMask("1-16周")
		assertEquals("1-16周应上课", true, plain and (1L shl 15) != 0L)
		assertEquals("1-16周之外不上课", false, plain and (1L shl 16) != 0L)

		// 半角 (单)/(双)
		val odd = Lesson.parseWeekMask("1-8(单)")
		assertEquals("第1周(单)上课", true, odd and 1L != 0L)
		assertEquals("第2周(单)不上课", false, odd and 2L != 0L)
		val even = Lesson.parseWeekMask("1-8(双)")
		assertEquals("第2周(双)上课", true, even and 2L != 0L)
		assertEquals("第1周(双)不上课", false, even and 1L != 0L)

		// 多个区间
		val multi = Lesson.parseWeekMask("1-4,7-9")
		assertTrue("第7周应上课", multi and (1L shl 6) != 0L)
		assertFalse("第5周不上课", multi and (1L shl 4) != 0L)

		// 解析不出任何周次时不能返回 0(否则课程会被整门删掉)
		assertTrue("空值必须按全学期处理", Lesson.parseWeekMask("") != 0L)
		assertTrue("乱码不能返回0", Lesson.parseWeekMask("未知") != 0L)
	}

	@Test
	fun parseText_traditionalCharacters_executesSchedule() {
		val adjust = com.qust.helper.ui.page.lesson.ScheduleAdjust
		// OCR 把"执"识别成繁体"執" —— 以前"執行"匹配不到"执行", 整条调课安排就丢了
		val text = "二、调课安排\n1.10月18日（周日）執行9月28日（周一）的教学安排"
		val map = adjust.parseText(text, 2026, 9).associate { it.dateKey to it.action }
		assertEquals("繁体'執行'也要能识别", 1, map["2026-10-18"])
	}

	@Test
	fun occupantsAt_blankSlot_isEmpty() {
		val lt = LessonTable().apply { startDay = DateUtils.YMD.parse("2026-03-02")!! }
		assertTrue("空白时段不应有占用课程", lt.occupantsAt(0, 0, 3).isEmpty())
	}

	@Test
	fun occupantsAt_longCourse_coversFollowingSlots() {
		val lt = LessonTable().apply { startDay = DateUtils.YMD.parse("2026-03-02")!! }
		// 周一第1节开始的2节连堂课
		lt.lessons[0][0] = LessonGroup(1, 1).also { it.lessons = arrayOf(lessonOfLen("连堂课", 2)) }

		val first = lt.occupantsAt(0, 0, 0)
		assertEquals(1, first.size)
		assertEquals(0, first[0].startSlot)
		assertEquals("选框长度应等于课程实际占用节数", 2, first[0].len)

		// 点击下半节(第2节)时仍应定位到起始节与完整长度, 不再出现"半节课"的选框
		val second = lt.occupantsAt(0, 0, 1)
		assertEquals(1, second.size)
		assertEquals("点击下半节应定位到起始节", 0, second[0].startSlot)
		assertEquals(2, second[0].len)

		assertTrue("超出课程范围不应算作占用", lt.occupantsAt(0, 0, 2).isEmpty())
	}

	@Test
	fun occupantsAt_overlappingCourses_reported() {
		val lt = LessonTable().apply { startDay = DateUtils.YMD.parse("2026-03-02")!! }
		// 第1节起2节的连堂课, 与第2节的单节课时间重合
		lt.lessons[0][0] = LessonGroup(1, 1).also { it.lessons = arrayOf(lessonOfLen("连堂课", 2)) }
		lt.lessons[0][1] = LessonGroup(1, 2).also { it.lessons = arrayOf(lessonOfLen("单节课", 1)) }

		val occ = lt.occupantsAt(0, 0, 1)
		assertEquals("该时刻应检测到两门重合课程", 2, occ.size)
		assertEquals(listOf("连堂课", "单节课"), occ.map { it.lesson.name })
	}

	@Test
	fun occupantsAt_multiLessonsInSameSlot_reported() {
		val lt = LessonTable().apply { startDay = DateUtils.YMD.parse("2026-03-02")!! }
		// 同一格内两节课都在第0周上课 -> 重合
		lt.lessons[3][2] = LessonGroup(4, 3).also { it.lessons = arrayOf(lessonOfLen("A课", 1), lessonOfLen("B课", 1)) }
		assertEquals("同一格内同周的多节课程应全部返回", 2, lt.occupantsAt(0, 3, 2).size)
	}

	@Test
	fun occupantsAt_onlyActiveInGivenWeek() {
		val lt = LessonTable().apply { startDay = DateUtils.YMD.parse("2026-03-02")!! }
		lt.lessons[0][0] = LessonGroup(1, 1).also { it.lessons = arrayOf(lessonOfLen("单周课", 1, 1L shl 1)) }
		assertTrue("非上课周不应算占用", lt.occupantsAt(0, 0, 0).isEmpty())
		assertEquals(1, lt.occupantsAt(1, 0, 0).size)
	}

	@Test
	fun occupantsAt_followsHolidayOverride() {
		val lt = LessonTable().apply { startDay = DateUtils.YMD.parse("2026-03-02")!! }
		lt.lessons[0][0] = LessonGroup(1, 1).also { it.lessons = arrayOf(lessonOfLen("周一课", 1)) }
		lt.lessons[2][0] = LessonGroup(3, 1).also { it.lessons = arrayOf(lessonOfLen("周三课", 1)) }
		lt.overrides["2026-03-04"] = 0     // 3月4日(周三)放假: 只标记, 不清空
		assertEquals("放假日期课程应保留", "周三课", lt.occupantsAt(0, 2, 0)[0].lesson.name)
		assertTrue("放假日期应被识别为放假", lt.isHoliday("2026-03-04"))
		lt.overrides["2026-03-05"] = 1     // 3月5日(周四)补周一的课
		assertEquals("补课日期应按源星期的课程占用", "周一课", lt.occupantsAt(0, 3, 0)[0].lesson.name)
	}

	// ---------------- 删除当日课表 / 删除学期课表 ----------------

	@Test
	fun clearDayByDate_onlyClearsThatDate() {
		val lt = LessonTable().apply { startDay = DateUtils.YMD.parse("2026-03-02")!! }
		lt.lessons[5][0] = groupOf("周六模板")
		lt.overrides["2026-03-07"] = 0      // 先标记放假

		lt.clearDayByDate(0, 5)             // 删除第0周周六(2026-03-07)的课表

		assertNull("该日期应无课", lt.effectiveDay(0, 5)[0])
		assertTrue("该日期应被标记为已删除", lt.isDayCleared("2026-03-07"))
		assertFalse("删除当日课表时应清除放假标记", lt.isHoliday("2026-03-07"))
		// 每周模板与其它周次不受影响
		assertNotNull("每周模板不应被修改", lt.lessons[5][0])
		assertEquals("周六模板", lt.effectiveDay(1, 5)[0]!!.lessons[0].name)

		// 恢复原课表(撤销删除)
		lt.restoreDay(0, 5)
		assertEquals("撤销后应回到模板", "周六模板", lt.effectiveDay(0, 5)[0]!!.lessons[0].name)
	}

	@Test
	fun clearTerm_wipesLessonsAndAdjustments() {
		val lt = LessonTable().apply { startDay = DateUtils.YMD.parse("2026-03-02")!! }
		lt.lessons[0][0] = groupOf("周一课")
		lt.lessons[3][2] = groupOf("周四课")
		lt.copyDayByDate(0, 0, 0, 6)
		lt.overrides["2026-03-04"] = 0
		assertTrue("删除前应存在课程", lt.hasAnyLesson())

		val backup = lt.deepCopy()
		lt.clearTerm()

		assertFalse("删除学期课表后不应再有课程", lt.hasAnyLesson())
		assertTrue("调休应被清空", lt.overrides.isEmpty())
		assertTrue("按日期修改应被清空", lt.dayOverrides.isEmpty())
		for(i in lt.lessons.indices) {
			assertTrue("第$i 天应全部为空", lt.lessons[i].all { it == null })
		}
		// 备份不受影响, 可用于撤销
		assertTrue("备份应保留课程", backup.hasAnyLesson())
		assertEquals("周一课", backup.lessons[0][0]!!.lessons[0].name)
		// 备份是深拷贝: 清空原表不影响备份
		assertNotNull("撤销数据应与原表解耦", backup.lessons[0][0])
	}

	@Test
	fun deepCopy_isIndependent() {
		val lt = LessonTable().apply { startDay = DateUtils.YMD.parse("2026-03-02")!! }
		lt.lessons[0][0] = groupOf("原课")
		lt.copyDayByDate(0, 0, 0, 5)
		lt.overrides["2026-03-04"] = 0

		val copy = lt.deepCopy()
		copy.lessons[0][0]!!.lessons[0].name = "改过的"
		copy.dayOverrides["2026-03-07"]!!.let { it[0] = null }
		copy.overrides["2026-03-04"] = 1

		assertEquals("源模板不应受影响", "原课", lt.lessons[0][0]!!.lessons[0].name)
		assertNotNull("源按日期覆盖不应受影响", lt.dayOverrides["2026-03-07"]!![0])
		assertEquals("源调休不应受影响", 0, lt.overrides["2026-03-04"])
	}

	// ---------------- 区间占用(落下/保存的重合判定) ----------------

	@Test
	fun spanOccupied_detectsOverlapAcrossSpan() {
		val lt = LessonTable().apply { startDay = DateUtils.YMD.parse("2026-03-02")!! }
		lt.lessons[0][2] = LessonGroup(1, 3).also { it.lessons = arrayOf(lessonOfLen("已有课", 1)) }

		assertNotNull("2节区间覆盖到已有课应判定重合", lt.spanOccupied(0, 0, 1, 2))
		assertNull("区间不覆盖时不应判定重合", lt.spanOccupied(0, 0, 3, 2))
		val self = lt.lessons[0][2]!!.lessons[0]
		assertNull("忽略自身后不应判定重合", lt.spanOccupied(0, 0, 2, 1, self))
	}

	// ---------------- 作息表管理: 新增/命名/编辑/恢复默认/删除 ----------------

	@Test
	fun timeTables_addRenameEditAndRemove() {
		val initial = TimeTables.size
		assertTrue("应至少有内置作息表", initial >= 3)

		val index = TimeTables.addTimeTable("我的作息", TimeTables.fixedSchedule(8, 0, 45, 10, TimeTables.MAX_SLOT_COUNT))
		assertEquals(initial + 1, TimeTables.size)
		assertEquals("名称应被保存", "我的作息", TimeTables.names[index])
		assertEquals("新作息应为45分钟", 45, TimeTables.periodMinutes(TimeTables.getTimes(index))[0])
		assertTrue("用户自定义作息无内置默认值", !TimeTables.canRestoreDefault(index))

		// 重命名
		assertTrue(TimeTables.rename(index, "  课表B  "))
		assertEquals("名称应被裁剪并更新", "课表B", TimeTables.names[index])
		assertFalse("空名称应被拒绝", TimeTables.rename(index, "   "))

		// 编辑时间
		val edited = TimeTables.fixedSchedule(9, 30, 45, 10, 6)
		TimeTables.setTimes(index, edited)
		assertEquals("编辑后应为6节", 6, TimeTables.getTimes(index)[0].size)
		assertEquals("09:30", TimeTables.getTimes(index)[0][0])

		// 复制一份
		val dup = TimeTables.duplicate(index, "课表B 副本")
		assertEquals("课表B 副本", TimeTables.names[dup])
		assertEquals("复制应带上时间", 6, TimeTables.getTimes(dup)[0].size)

		// 删除
		assertTrue(TimeTables.removeTimeTable(dup))
		assertEquals("删除后应恢复数量", initial + 1, TimeTables.size)

		// 持久化: 重新读取仍然保留
		TimeTables.reload()
		assertEquals("重新读取后数量应一致", initial + 1, TimeTables.size)
		assertEquals("重新读取后名称应一致", "课表B", TimeTables.names[index])

		// 清理
		assertTrue(TimeTables.removeTimeTable(index))
		assertEquals(initial, TimeTables.size)
	}

	@Test
	fun currentTimeTable_indexClampedAfterRemoval() {
		com.qust.helper.model.LessonTableRepository.setTimeTableValue(TimeTables.size - 1)
		val last = TimeTables.size - 1
		assertTrue(TimeTables.removeTimeTable(last))
		com.qust.helper.model.LessonTableRepository.clampTimeTableIndex()
		val current = com.qust.helper.model.LessonTableRepository.currentTimeTable
		assertTrue("当前下标应在范围内", current in 0 until TimeTables.size)
		// 越界时不应崩溃
		assertEquals("越界时应能取到时间", TimeTables.currentTimes[0].size, TimeTables.currentSlotCount)
	}

	// ---------------- 调休强规则 ----------------

	@Test
	fun setOverride_clearsStaleDayOverride() {
		val lt = LessonTable().apply { startDay = DateUtils.YMD.parse("2026-03-02")!! }
		lt.lessons[0][0] = LessonGroup(1, 1).also { it.lessons = arrayOf(lessonOfLen("周一课", 1)) }

		// 先有一处"手动按日期修改"(例如之前复制整天课表/拖动课程留下的)
		lt.editDayOverride(0, 2)
		assertTrue("应被识别为已调整日期", lt.isDateAdjusted("2026-03-04"))

		// 之后写入调休: 必须清除手动覆盖, 否则调休会被顶掉(表现为"调休没有生效")
		assertTrue("应报告清除了手动覆盖", lt.setOverride("2026-03-04", 1))
		assertFalse("手动覆盖应已被清除", lt.dayOverrides.containsKey("2026-03-04"))
		assertEquals("调休应生效(补周一)", "周一课", lt.effectiveDay(0, 2)[0]!!.lessons[0].name)
	}

	// ---------------- 调休文本解析(以学校官网通知的写法为准) ----------------

	/** 官网原文(2026年中秋节、国庆节放假安排的通知)的关键段落 */
	private val OFFICIAL_NOTICE = """
		各学院、部（处）、校区、校直各单位：
		根据《国务院办公厅关于2026年部分节假日安排的通知》精神，结合学校工作实际，现将2026年中秋节、国庆节放假安排通知如下。
		一、放假时间
		9月25日（周五）至10月7日（周三）放假调休，共13天。
		9月20日（周日）、10月10日（周六）、10月18日（周日）、10月25日（周日）、11月1日（周日）上班。
		二、调课安排
		1.10月18日（周日）执行9月28日（周一）的教学安排。
		2.10月25日（周日）执行9月29日（周二）的教学安排。
		3.11月1日（周日）执行9月30日（周三）的教学安排。
		4.法定调休日课程安排：9月20日执行10月6日的教学安排；10月10日执行10月7日的教学安排。
	""".trimIndent()

	@Test
	fun parseText_至_isAWholeStage_notJustTwoDays() {
		val items = com.qust.helper.ui.page.lesson.ScheduleAdjust.parseText(OFFICIAL_NOTICE, 2026, 9)
		val holidays = items.filter { it.action == com.qust.helper.ui.page.lesson.ScheduleAdjust.ACTION_CLEAR }.map { it.dateKey }

		assertEquals("“X日至X日”应覆盖整段日期(13天), 而不是只有起止两天", 13, holidays.size)
		assertEquals("起始日", "2026-09-25", holidays.first())
		assertEquals("结束日", "2026-10-07", holidays.last())
		assertTrue("区间中间的日期也要放假", holidays.contains("2026-09-30"))
		assertTrue("区间中间的日期也要放假", holidays.contains("2026-10-01"))
	}

	@Test
	fun parseText_workDayInsideRange_isNotHoliday() {
		// 区间里明确写了"上班"的日期不应被当成放假
		val text = "9月25日（周五）至10月7日（周三）放假调休，共13天。9月28日（周一）上班。"
		val items = com.qust.helper.ui.page.lesson.ScheduleAdjust.parseText(text, 2026, 9)
		val map = items.associateBy { it.dateKey }
		assertFalse("9月28日上班，不应标为放假", map["2026-09-28"]?.action == com.qust.helper.ui.page.lesson.ScheduleAdjust.ACTION_CLEAR)
		assertTrue("9月27日仍在假期内", map["2026-09-27"]?.action == com.qust.helper.ui.page.lesson.ScheduleAdjust.ACTION_CLEAR)
	}

	@Test
	fun parseText_execSchedule_followsSourceWeekday() {
		val items = com.qust.helper.ui.page.lesson.ScheduleAdjust.parseText(OFFICIAL_NOTICE, 2026, 9)
		val map = items.associateBy { it.dateKey }
		assertEquals("10月18日执行9月28日(周一)的教学安排", 1, map["2026-10-18"]?.action)
		assertEquals("10月25日执行9月29日(周二)的教学安排", 2, map["2026-10-25"]?.action)
		assertEquals("11月1日执行9月30日(周三)的教学安排", 3, map["2026-11-01"]?.action)
		assertEquals("9月20日执行10月6日(周二)的教学安排", 2, map["2026-09-20"]?.action)
		assertEquals("10月10日执行10月7日(周三)的教学安排", 3, map["2026-10-10"]?.action)
	}

	@Test
	fun parseText_singleDayAndShortRange() {
		// 端午: 结束日期省略月份
		val text = "现将2026年端午节放假安排通知如下。一、放假时间 6月19日（周五）至21日（周日）放假，共3天。"
		val items = com.qust.helper.ui.page.lesson.ScheduleAdjust.parseText(text, 2026, 6)
		val holidays = items.map { it.dateKey }
		assertEquals(3, holidays.size)
		assertEquals("2026-06-19", holidays[0])
		assertEquals("2026-06-21", holidays[2])

		// 单日放假
		val single = com.qust.helper.ui.page.lesson.ScheduleAdjust.parseText("2026年1月1日放假。", 2026, 12)
		assertEquals(1, single.size)
		assertEquals("2026-01-01", single[0].dateKey)
	}

	// ---------------- 官网同步只处理"最近"的调休 ----------------

	private fun changeItemOf(dateKey: String) = com.qust.helper.ui.page.lesson.ScheduleAdjust.ChangeItem(
		dateKey, com.qust.helper.ui.page.lesson.ScheduleAdjust.ACTION_CLEAR, dateKey
	)

	@Test
	fun filterRecentChanges_dropsTooOldHolidays() {
		val adjust = com.qust.helper.ui.page.lesson.ScheduleAdjust
		val now = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 10, 12, 0, 0); set(Calendar.MILLISECOND, 0) }
		val items = listOf(
			changeItemOf("2026-05-01"),   // 半年前 -> 丢弃
			changeItemOf("2025-10-01"),   // 去年 -> 丢弃
			changeItemOf("2026-09-25"),   // 15天前 -> 保留
			changeItemOf("2026-10-10"),   // 今天 -> 保留
			changeItemOf("2026-11-01")    // 将来 -> 保留
		)
		val recent = adjust.filterRecentChanges(items, now).map { it.dateKey }
		assertEquals("只保留最近与将来的调休", listOf("2026-09-25", "2026-10-10", "2026-11-01"), recent)
	}

	// ---------------- 当日课表: 不上(灰色)的课程不显示 ----------------

	@Test
	fun activeDay_hidesLessonsNotInCurrentWeek() {
		val lt = LessonTable().apply {
			startDay = DateUtils.YMD.parse("2026-03-02")!!   // 周一
			totalWeek = 20
		}
		// 第0节: 第1周与第5周各有不同的课
		val onlyWeek1 = lessonOfLen("第一周课", 1).also { it.week = 1L shl 0 }
		val onlyWeek5 = lessonOfLen("第五周课", 1).also { it.week = 1L shl 4 }
		lt.lessons[0][0] = LessonGroup(1, 1).also { it.lessons = arrayOf(onlyWeek1, onlyWeek5) }
		// 第2节: 只有第5周才上 -> 在第1周里就是学期课表上显示为灰色的"暂时不上"的课
		lt.lessons[0][2] = LessonGroup(1, 3).also {
			it.lessons = arrayOf(lessonOfLen("第五周专有课", 1).also { l -> l.week = 1L shl 4 })
		}

		// 第1周周一: 第0节要上(第一周课), 第2节属于灰色课程 -> 不显示
		val week1 = lt.activeDay(0, 0)
		assertNotNull("本周有课的第0节应保留", week1[0])
		assertEquals("第一周课", week1[0]!!.getCurrentLesson(1)!!.name)
		assertNull("灰色课程(第5周才上)不应出现在当日课表", week1[2])

		// 第5周周一: 两节都要上
		val week5 = lt.activeDay(4, 0)
		assertNotNull(week5[0])
		assertNotNull(week5[2])
		assertEquals("第五周课", week5[0]!!.getCurrentLesson(5)!!.name)
		assertEquals("第五周专有课", week5[2]!!.getCurrentLesson(5)!!.name)
	}

	@Test
	fun activeDay_outsideTermWeeks_returnsNothing() {
		val lt = LessonTable().apply {
			startDay = DateUtils.YMD.parse("2026-03-02")!!
			totalWeek = 18
		}
		lt.lessons[0][0] = LessonGroup(1, 1).also { it.lessons = arrayOf(lessonOfLen("周一课", 1).also { l -> l.week = 1L shl 17 }) }
		// 超出总周数的周次(第19周)不应有课
		assertNull("超出学期周数不应显示课程", lt.activeDay(18, 0)[0])
	}

	// ---------------- 新建作息表默认使用夏季时间 ----------------

	// ---------------- 拖动课程: 落点吸附 / 不被自己挡住 ----------------

	/** 造一张"周一 0 节是两节连上的课(占 0-1), 4 节也是两节连上的课(占 4-5)"的课表 */
	private fun makeGapTable(): LessonTable = LessonTable().apply {
		startDay = DateUtils.YMD.parse("2026-03-02")!!
		totalWeek = 18
		lessons[0][0] = LessonGroup(1, 1).also { it.lessons = arrayOf(lessonOfLen("A上", 2)) }
		lessons[0][1] = LessonGroup(1, 2).also { it.lessons = arrayOf(lessonOfLen("A下", 1)) }
		lessons[0][4] = LessonGroup(1, 5).also { it.lessons = arrayOf(lessonOfLen("B上", 2)) }
		lessons[0][5] = LessonGroup(1, 6).also { it.lessons = arrayOf(lessonOfLen("B下", 1)) }
	}

	@Test
	fun nearestFreeSlot_snapsIntoGapBetweenTwoCourses() {
		val lt = makeGapTable()
		// 2~3 节是空隙: 直接落在这里可用
		assertEquals(2, lt.nearestFreeSlot(0, 0, 2, 1))
		// 落到被占用的第5节(0开始=4)时, 应吸附到最近的空位而不是"放不下去"
		val snapped = lt.nearestFreeSlot(0, 0, 4, 1)
		assertNotNull("拖到两门课中间时应能找到空位", snapped)
		assertTrue("应吸附到空隙(第3~4节)", snapped == 2 || snapped == 3)
	}

	@Test
	fun spanOccupied_doesNotTreatDraggedCourseAsObstacle() {
		val lt = makeGapTable()
		// 正在拖动第3节(0开始=2)那门两节连上的课, 目标是它自己覆盖到的第4节
		// 旧实现会因为拿到的是另一个 Lesson 实例而把它自己当成障碍 -> 提示替换 -> 移动不了
		assertNull(
			"不应把被拖动的课程自己当成障碍",
			lt.spanOccupied(0, 0, 3, 1, null, 0, 0, 2)
		)
		// 真正的冲突仍要报出来
		assertEquals("B上", lt.spanOccupied(0, 0, 4, 1, null, 0, 0, 2)?.name)
	}

	// ---------------- 点击空白课表默认 2 格(按大多数课程跨度) ----------------

	@Test
	fun majorityLessonLength_isAtLeastTwoAndFollowsTheMajority() {
		val lt = LessonTable().apply { startDay = DateUtils.YMD.parse("2026-03-02")!!; totalWeek = 18 }
		assertEquals("没有课程时默认 2 格", 2, lt.majorityLessonLength())

		lt.lessons[0][0] = LessonGroup(1, 1).also { it.lessons = arrayOf(lessonOfLen("两节1", 2)) }
		lt.lessons[0][2] = LessonGroup(1, 3).also { it.lessons = arrayOf(lessonOfLen("两节2", 2)) }
		lt.lessons[0][4] = LessonGroup(1, 5).also { it.lessons = arrayOf(lessonOfLen("四节", 4)) }
		lt.lessons[0][8] = LessonGroup(1, 9).also { it.lessons = arrayOf(lessonOfLen("一节", 1)) }
		assertEquals("大多数课程是两节连上 -> 默认 2 格", 2, lt.majorityLessonLength())

		// 大多数变成 4 节时, 默认也跟着变成 4 格
		lt.lessons[1][0] = LessonGroup(2, 1).also { it.lessons = arrayOf(lessonOfLen("四节2", 4)) }
		lt.lessons[1][4] = LessonGroup(2, 5).also { it.lessons = arrayOf(lessonOfLen("四节3", 4)) }
		assertEquals(4, lt.majorityLessonLength())
	}

	// ---------------- 调整上课周数时的时间冲突提示 ----------------

	@Test
	fun conflictingWeeks_reportsWeeksAlreadyTaken() {
		val lt = LessonTable().apply { startDay = DateUtils.YMD.parse("2026-03-02")!!; totalWeek = 6 }
		val lessonA = lessonOfLen("A", 1, 1L shl 0)   // 第1周
		val lessonB = lessonOfLen("B", 1, 1L shl 1)   // 第2周
		lt.lessons[0][0] = LessonGroup(1, 1).also { it.lessons = arrayOf(lessonA, lessonB) }

		// 把 A 改成只上第2周 -> 第2周已被 B 占用
		val conflicts = lt.conflictingWeeks(0, 0, 1, 1L shl 1, lessonA, 6)
		assertEquals(1, conflicts.size)
		assertEquals(2, conflicts[0].first)
		assertEquals("B", conflicts[0].second)

		// 改成第1周(自己所在的周)不应报冲突
		assertTrue("不应把自己算成冲突", lt.conflictingWeeks(0, 0, 1, 1L shl 0, lessonA, 6).isEmpty())
	}

	// ---------------- 全部课表恢复默认 ----------------

	@Test
	fun resetLessonTableToDefault_clearsAdjustmentsButKeepsWeeklyLessons() {
		val lt = LessonTable().apply {
			startDay = DateUtils.YMD.parse("2026-03-02")!!
			totalWeek = 18
		}
		lt.lessons[0][0] = LessonGroup(1, 1).also { it.lessons = arrayOf(lessonOfLen("周一课", 1)) }
		lt.overrides["2026-03-04"] = 0
		lt.dayOverrides["2026-03-05"] = arrayOfNulls(TimeTables.MAX_SLOT_COUNT)
		com.qust.helper.model.LessonTableRepository.saveLessonTable(lt, keepAdjustments = false)

		assertTrue("准备阶段: 应写入调休", com.qust.helper.model.LessonTableRepository.lessonTable.overrides.isNotEmpty())

		assertTrue(com.qust.helper.model.LessonTableRepository.resetLessonTableToDefault())
		val after = com.qust.helper.model.LessonTableRepository.lessonTable
		assertTrue("调休/放假标记应被清空", after.overrides.isEmpty())
		assertTrue("按日期手动修改应被清空", after.dayOverrides.isEmpty())
		assertNotNull("每周课程本身要保留(不能丢课)", after.lessons[0][0])

		assertTrue("应可撤销", com.qust.helper.model.LessonTableRepository.hasDeletedBackup())
		assertTrue(com.qust.helper.model.LessonTableRepository.restoreDeletedLessonTable())
		assertTrue(
			"撤销后调休应恢复",
			com.qust.helper.model.LessonTableRepository.lessonTable.overrides.isNotEmpty()
		)

		// 清理: 避免影响其它用例
		com.qust.helper.model.LessonTableRepository.saveLessonTable(LessonTable(), keepAdjustments = false)
	}

	// ---------------- 图片调休: 整段兜底识别 ----------------

	@Test
	fun parseText_fallsBackToWholeTextWhen放假AndDatesAreInDifferentSentences() {
		// 截图 OCR 常见情况: 日期与"放假"被句号隔开(逐句匹配会漏)
		val text = "2026年国庆节放假安排通知如下。9月25日至10月7日调休，共13天。"
		val items = com.qust.helper.ui.page.lesson.ScheduleAdjust.parseText(text, 2026, 9)
		val holidays = items.filter { it.action == com.qust.helper.ui.page.lesson.ScheduleAdjust.ACTION_CLEAR }.map { it.dateKey }
		assertEquals("整段兜底识别也要把假期时间段识别出来", 13, holidays.size)
		assertEquals("2026-09-25", holidays.first())
		assertEquals("2026-10-07", holidays.last())
	}

	@Test
	fun parseText_recognizes号AsDay() {
		val items = com.qust.helper.ui.page.lesson.ScheduleAdjust.parseText("2026年1月1号放假。", 2026, 12)
		assertEquals(1, items.size)
		assertEquals("2026-01-01", items[0].dateKey)
	}

	@Test
	fun dailyLesson_week4ShowsPhysicsOnly_notTheWeek8Math() {
		// 第4周周二有物理, 第8周周二同一时间有数学: 点第4周只应显示物理
		val lt = LessonTable().apply { startDay = DateUtils.YMD.parse("2026-03-02")!!; totalWeek = 18 }
		val physics = lessonOfLen("物理", 1, 1L shl 3)   // 只上第4周
		val math = lessonOfLen("数学", 1, 1L shl 7)      // 只上第8周
		lt.lessons[1][0] = LessonGroup(2, 1).also { it.lessons = arrayOf(physics, math) }

		val week4 = lt.activeDay(3, 1)
		assertNotNull("第4周周二第1节应有课", week4[0])
		assertEquals("第4周只显示物理", "物理", week4[0]!!.getCurrentLesson(4)!!.name)

		val week8 = lt.activeDay(7, 1)
		assertNotNull("第8周周二第1节应有课", week8[0])
		assertEquals("第8周只显示数学", "数学", week8[0]!!.getCurrentLesson(8)!!.name)

		assertNull("第5周两门课都不上, 不应显示任何课程", lt.activeDay(4, 1)[0])
	}

	// ---------------- 拖拽后仍可反复移动(回归) ----------------

	// ---------------- 调休以"具体日期"为基准 ----------------

	@Test
	fun applyChanges_makeupCopiesConcreteSourceDateCourses() {
		val scheduleAdjust = com.qust.helper.ui.page.lesson.ScheduleAdjust
		val table = LessonTable().apply { startDay = DateUtils.YMD.parse("2026-03-02")!!; totalWeek = 18 }
		// 第1周周一是"物理", 第3周周一是"数学"(同一星期、不同周、课程不同)
		table.lessons[0][0] = LessonGroup(1, 1).also { it.lessons = arrayOf(lessonOfLen("物理", 1, 1L shl 0)) }
		table.lessons[0][2] = LessonGroup(1, 3).also { it.lessons = arrayOf(lessonOfLen("数学", 1, 1L shl 2)) }

		val srcDate = table.dateKeyOf(2, 0)   // 第3周周一
		val dstDate = table.dateKeyOf(2, 6)   // 第3周周日
		assertEquals("2026-03-16", srcDate)
		assertEquals("2026-03-22", dstDate)

		scheduleAdjust.applyChanges(
			table,
			listOf(com.qust.helper.ui.page.lesson.ScheduleAdjust.ChangeItem(dstDate, 1, "", srcDate))
		)

		val names = table.effectiveDay(2, 6).filterNotNull().flatMap { it.lessons.toList() }.map { it.name }
		assertTrue("调休后该日期应上『数学』", names.contains("数学"))
		assertFalse("不应把别周的课也带过来", names.contains("物理"))
	}

	@Test
	fun weekDayOfDate_isConsistentWithDateKeyOf() {
		val table = LessonTable().apply { startDay = DateUtils.YMD.parse("2026-03-02")!!; totalWeek = 18 }
		for(w in 0 until 3) {
			for(d in 0..6) {
				assertEquals("日期与(周,星期)必须能互相换算", w to d, table.weekDayOfDate(table.dateKeyOf(w, d)))
			}
		}
		assertNull("学期范围外的日期应返回 null", table.weekDayOfDate("2020-01-01"))
	}

	// ---------------- 一键恢复默认课表(调休前 / 调休后) ----------------

	@Test
	fun restoreOriginalLessonTable_beforeAndAfterAdjustments() {
		val repo = com.qust.helper.model.LessonTableRepository

		// 模拟"最初从教务系统查询导入并保存"的课表
		val imported = LessonTable().apply {
			startDay = DateUtils.YMD.parse("2026-03-02")!!
			totalWeek = 18
		}
		imported.lessons[0][0] = LessonGroup(1, 1).also { it.lessons = arrayOf(lessonOfLen("周一课", 1)) }
		repo.saveLessonTable(imported, keepAdjustments = false)
		repo.saveOriginalLessonTable()
		assertTrue("应存在可恢复的默认课表", repo.hasOriginalLessonTable())

		// 用户后来把课删了、又加了调休
		val cur = repo.lessonTable
		cur.lessons[0][0] = null
		cur.overrides["2026-03-05"] = 2
		repo.saveLessonTable()

		// 调休后: 课程回到导入时, 调休保留
		assertTrue(repo.restoreOriginalLessonTable(keepAdjustments = true, keepEasChanges = true))
		val after1 = repo.lessonTable
		assertNotNull("课程应恢复为导入时的样子", after1.lessons[0][0])
		assertTrue("调休后应保留调休标记", after1.overrides.containsKey("2026-03-05"))

		// 调休前: 连调休一起清除
		after1.lessons[0][0] = null
		assertTrue(repo.restoreOriginalLessonTable(keepAdjustments = false, keepEasChanges = false))
		val after2 = repo.lessonTable
		assertNotNull("课程应恢复", after2.lessons[0][0])
		assertTrue("调休前应清空调休标记", after2.overrides.isEmpty())

		// 清理, 避免影响其它用例
		repo.saveLessonTable(LessonTable(), keepAdjustments = false)
	}

	// ---------------- 保存课表: 「删除不算修改」, 每次都按最新原始课表补回 ----------------

	/**
	 * 一份"查询到的原始课表": 周一 1~2 节「高数」(两节连上), 周三第 3 节「英语」。
	 * 教务课程都带课程号(kchID) —— 合并用户修改时"同一门课"就是靠它来匹配的, 所以要模拟真实数据。
	 */
	private fun makeOriginalTable(): LessonTable = LessonTable().apply {
		startDay = DateUtils.YMD.parse("2026-03-02")!!
		totalWeek = 18
		lessons[0][0] = LessonGroup(1, 1).also {
			it.lessons = arrayOf(lessonOfLen("高数", 2, 1L shl 0).also { l -> l.kchID = "KC001" })
		}
		lessons[2][2] = LessonGroup(3, 3).also {
			it.lessons = arrayOf(lessonOfLen("英语", 1, 1L shl 0).also { l -> l.kchID = "KC002" })
		}
	}

	private fun buildSave(original: LessonTable, current: LessonTable, saveAdjustments: Boolean = true) =
		com.qust.helper.data.lesson.LessonTableSaveBuilder.buildKeepUserTable(
			original, current, original.startDay, original.totalWeek, saveAdjustments
		)

	@Test
	fun saveBuilder_restoresDeletedLesson_fromSecondSaveOn() {
		val original = makeOriginalTable()
		// 第一次保存: 什么都没删, 结果应与原始课表一致
		val first = buildSave(original, original.deepCopy())
		assertNotNull("第一次保存应保留课程", first.lessons[0][0])

		// 用户删掉周三的英语, 再保存一次(此时"当前课表"已经是第一次保存的结果)
		val current = first.deepCopy()
		current.lessons[2][2] = null
		assertNull("删除后模板里应没有这节课", current.lessons[2][2])

		val second = buildSave(original, current)
		assertNotNull("第二次保存也必须按最新原始课表补回被删掉的课程", second.lessons[2][2])
		assertEquals("英语", second.lessons[2][2]!!.lessons[0].name)

		// 第三次照样生效(以前只有查询后的第一次能补回)
		current.lessons[2][2] = null
		val third = buildSave(original, second.deepCopy().also { it.lessons[2][2] = null })
		assertNotNull("第三次保存同样要补回", third.lessons[2][2])
	}

	@Test
	fun saveBuilder_restoresDeletedDay() {
		val original = makeOriginalTable()
		// 英语在第3周也要上(否则"按原始课表补回"补回来的也是一节本周不上的课)
		original.lessons[2][2]!!.lessons[0].week = (1L shl 18) - 1
		val current = original.deepCopy()
		current.clearDayByDate(2, 2)     // 删除第3周周三的当天课表
		assertNull("删除后该日期应无课", current.effectiveDay(2, 2)[2])

		val saved = buildSave(original, current)
		assertNotNull("整天的课也应按最新原始课表补回", saved.effectiveDay(2, 2)[2])
		assertEquals("英语", saved.effectiveDay(2, 2)[2]!!.lessons[0].name)
	}

	@Test
	fun saveBuilder_keepsUserModifiedLesson_andAdjustment() {
		val original = makeOriginalTable()
		val current = original.deepCopy()
		val english = current.lessons[2][2]!!.lessons[0]
		english.name = "大学英语"
		english.type = 1               // 用户改过
		// 用户自己新建的课程(没有课程号)应当被追加, 而不是被丢掉
		current.getLessonGroupNotNull(1, 4).addLesson(lessonOfLen("讲座", 1, 1L shl 0))
		current.overrides["2026-03-05"] = 0

		val saved = buildSave(original, current)
		assertEquals("用户改过的课程名必须保留", "大学英语", saved.lessons[2][2]!!.lessons[0].name)
		assertNotNull("没被删的课程不应丢", saved.lessons[0][0])
		assertTrue("用户新建的课程应追加", saved.effectiveDay(0, 1)[4]!!.lessons.any { it.name == "讲座" })
		assertTrue("调休标记应一并保存", saved.overrides.containsKey("2026-03-05"))
	}

	@Test
	fun saveBuilder_withoutAdjustments_dropsOverridesButKeepsUserDayEdit() {
		val original = makeOriginalTable()
		val current = original.deepCopy()
		// 普通日期上的一次手动修改(无调休标记) + 一个调休标记
		val col = current.editDayOverride(1, 3)
		col[5] = LessonGroup(4, 6).also { it.lessons = arrayOf(lessonOfLen("讲座", 1, 1L shl 1)) }
		current.overrides["2026-03-05"] = 3
		current.dayOverrides["2026-03-05"] = arrayOfNulls(TimeTables.MAX_SLOT_COUNT)

		val saved = buildSave(original, current, saveAdjustments = false)
		assertTrue("不保存调休时应清空调休标记", saved.overrides.isEmpty())
		assertFalse("带调休标记的日期副本也应丢弃", saved.dayOverrides.containsKey("2026-03-05"))
		assertNotNull("用户自己的按日期修改要保留", saved.dayOverrides[original.dateKeyOf(1, 3)])
		assertEquals("讲座", saved.effectiveDay(1, 3)[5]!!.lessons[0].name)
	}

	@Test
	fun saveBuilder_backfilledDayCopy_isDeepCopied() {
		val original = makeOriginalTable()
		val current = original.deepCopy()
		// 该日期被删空 -> 保存时按原始课表补回, 补回的课程不能与每周模板共用对象
		current.clearDayByDate(0, 0)
		val saved = buildSave(original, current)
		val fromCopy = saved.effectiveDay(0, 0)[0]
		assertNotNull(fromCopy)
		assertTrue("补回的课程应是深拷贝, 不能与模板共用同一个对象", fromCopy !== saved.lessons[0][0])
		fromCopy!!.lessons[0].name = "改过的"
		assertEquals("改副本不应影响每周模板", "高数", saved.lessons[0][0]!!.lessons[0].name)
	}

	/**
	 * 【保存 bug①】在原课程的位置粘贴新课后又删掉新课 -> 保存后该位置必须回到原课程
	 *
	 * 粘贴是"覆盖语义": replaceLessonAt 会把该节次里原课程在**本周**的周次位清掉;
	 * 「删除课程」只把粘贴的课程从日期副本里摘掉。于是副本里剩下一条"被清掉本周"的旧课程 ——
	 * 它既不是空节次也不会在本周显示, 课表上就成了一个空洞。
	 * 保存时必须以"这一节本周有没有课"为判据, 从最新原始课表补回本周该上的课。
	 */
	@Test
	fun saveBuilder_pasteThenDelete_restoresOriginalLesson() {
		val original = makeOriginalTable()
		val english = original.lessons[2][2]!!.lessons[0]
		english.week = (1L shl 18) - 1          // 英语: 第1~18周都上
		english.type = 0
		val current = original.deepCopy()

		// 第3周周三第3节: 在原课程位置粘贴一门新课(覆盖)
		current.replaceLessonAt(2, 2, 2, Lesson(type = 0, name = "讲座", len = 1, week = 1L shl 2))
		assertTrue("粘贴的课程应出现在原位置",
			current.effectiveDay(2, 2)[2]!!.lessons.any { it.name == "讲座" && it.week and (1L shl 2) != 0L })

		// 再把粘贴上去的课程删掉(与「删除课程」一致: 只从该日期的副本里摘掉)
		val key = current.dateKeyOf(2, 2)
		val col = current.dayOverrides[key]!!
		val remaining = col[2]!!.lessons.filterNot { it.name == "讲座" }
		col[2] = if(remaining.isEmpty()) null else LessonGroup(3, 3, remaining.toTypedArray())

		val saved = buildSave(original, current)
		val day = saved.effectiveDay(2, 2)
		assertNotNull("粘贴后删除, 保存时该位置应补回原课程", day[2])
		assertEquals("英语", day[2]!!.lessons[0].name)
	}

	/**
	 * 【保存 bug②】调休日期保存后莫名多出课程
	 *
	 * 借课日期(overrides 1..7)的副本是"把别的星期的课按当周过滤后复制过来"的结果,
	 * 与"当天模板"没有对应关系。以前补回空节次时用 effectiveDay 取基准 —— 对借课日期它会
	 * 返回被借用那一列的**整列模板**, 于是把本周本来不上的课也塞进这一天。
	 * 这类日期一律不补回: 用户删掉的课就按删除处理。
	 */
	@Test
	fun saveBuilder_adjustedDate_doesNotAddExtraLessons() {
		val original = makeOriginalTable()
		original.lessons[2][2]!!.lessons[0].week = (1L shl 18) - 1   // 周三的英语: 第1~18周都上
		original.lessons[2][2]!!.lessons[0].type = 0
		val current = original.deepCopy()

		// 第3周周日: 调休"上周三的课", 用户把这天补来的课都删了
		val key = current.dateKeyOf(2, 6)
		current.overrides[key] = 3
		current.dayOverrides[key] = arrayOfNulls(TimeTables.MAX_SLOT_COUNT)

		val saved = buildSave(original, current)
		assertNull("调休日期删掉的课不应在保存后又被补回来", saved.effectiveDay(2, 6)[2])
		assertTrue("调休标记要保留", saved.overrides.containsKey(key))
	}

	/**
	 * 【保存 bug②·副本缺失】调休日期没有按日期副本时, 保存要按"本周会上"生成副本
	 *
	 * 借课日期没有副本时, effectiveDay 会退回被借用那一列的**整列模板**; 以前保存时会把
	 * 整列里的课都当成这一天的课, 于是保存后凭空多出"只在别的周上"的课程。
	 */
	@Test
	fun saveBuilder_adjustedDate_withoutCopy_keepsOnlyThisWeek() {
		val original = makeOriginalTable()
		original.lessons[2][2]!!.lessons[0].week = (1L shl 18) - 1      // 英语: 第1~18周
		original.lessons[2][2]!!.lessons[0].type = 0
		original.lessons[2][5] = LessonGroup(3, 6).also {                  // 讲座: 只在第1周上
			it.lessons = arrayOf(Lesson(type = 0, name = "讲座", len = 1, week = 1L shl 0))
		}
		val current = original.deepCopy()
		val key = current.dateKeyOf(2, 6)
		current.overrides[key] = 3                                        // 第3周周日 补周三的课(没有副本)

		val saved = buildSave(original, current)
		val day = saved.effectiveDay(2, 6)
		assertEquals("本周会上的课要保留", "英语", day[2]!!.lessons[0].name)
		assertNull("只在别的周上的课不能因为保存而落到这一天", day[5])
	}

	/**
	 * 【教务通知调课与"保存课表"同步】
	 *
	 * 保存课表的规则是"删除不算修改, 一律按最新原始课表补回"。但教务通知调课写进
	 * dayOverrides 的"停课"本质上就是"这一天这一节没有课", 若也按"删除"补回,
	 * 教务停掉的课会在下一次保存时复活 —— 用户看到的就是"教务通知调课没生效"。
	 * 因此教务通知调课写过的日期(lessonTable.easDates)必须跳过补回。
	 */
	@Test
	fun saveBuilder_easSuspendedLesson_staysSuspended() {
		val original = makeOriginalTable()                      // 第1周周三第3节: 英语
		val current = original.deepCopy()
		val key = current.dateKeyOf(0, 2)
		current.editDayOverride(0, 2)[2] = null                 // 教务通知: 该日停课
		current.easSuspended[key] = hashSetOf(2)

		val saved = buildSave(original, current)
		assertNull("教务通知停掉的课, 保存课表时不能被'删除补回'规则还原", saved.effectiveDay(0, 2)[2])
		assertTrue("这一节的'教务停课'标记要一起保留", saved.easSuspended[key]?.contains(2) == true)
	}

	/**
	 * 【"空白课程无法恢复"】停课标记必须精确到节次
	 *
	 * 以前按"整个日期"跳过补回, 于是教务通知调课那天里因"粘贴覆盖后删除"留下的空洞
	 * 再也补不回来(用户看到的就是空白课程)。现在只跳过通知明确停掉的那几节。
	 */
	@Test
	fun saveBuilder_easSuspendedSlot_onlyThatSlotSkipped() {
		val original = makeOriginalTable()
		original.lessons[0][3] = LessonGroup(1, 4).also {      // 周一第4节: 物理
			it.lessons = arrayOf(lessonOfLen("物理", 1, 1L shl 0).also { l -> l.type = 0 })
		}
		val current = original.deepCopy()
		val key = current.dateKeyOf(0, 0)
		val col = current.editDayOverride(0, 0)
		col[0] = null                                           // 空洞: 应补回
		col[3] = null                                           // 教务停课: 不补回
		current.easSuspended[key] = hashSetOf(3)

		val saved = buildSave(original, current)
		val day = saved.effectiveDay(0, 0)
		assertEquals("同一日期里的空洞仍要补回", "高数", day[0]!!.lessons[0].name)
		assertNull("教务通知停掉的节次不能补回", day[3])
	}

	/** 没有按日期副本的"教务调课"标记是死数据, 保存时要丢掉(与 normalize 的清理一致) */
	@Test
	fun saveBuilder_easDateWithoutCopy_markerDropped() {
		val original = makeOriginalTable()
		val current = original.deepCopy()
		current.easDates.add(current.dateKeyOf(0, 2))           // 只有标记, 没有副本

		val saved = buildSave(original, current)
		assertTrue("没有副本的标记不应留下", saved.easDates.isEmpty())
	}

	/**
	 * 【"空白课程"根因】合并用户课程时不能凭空建出空课程组
	 *
	 * LessonTable.mergeUserDefinedLesson 以前对"新课表这一节为空、旧课表这一节有课"的每一格
	 * 先建一个空组再合并, 而没有用户课程时合并会直接返回 —— 空组就留在课表里,
	 * 渲染成一张没有文字的空白卡片(点开是空表单)。
	 */
	@Test
	fun mergeUserDefinedLesson_doesNotCreateEmptyGroup() {
		val original = makeOriginalTable()
		val other = makeOriginalTable()
		other.lessons[0][5] = LessonGroup(1, 6).also {         // 旧课表这节有一门"教务课"(type=0)
			it.lessons = arrayOf(lessonOfLen("物理", 1, 1L shl 0).also { l -> l.type = 0 })
		}

		original.mergeUserDefinedLesson(other)
		assertNull("没有用户课程时不能留下空课程组", original.lessons[0][5])
	}

	/** 教务通知调课命中的调休借课日期, 与保存课表用同一套"按本周冻结"规则 */
	@Test
	fun freezeBorrowedDates_keepsOnlyThisWeek() {
		val table = makeOriginalTable()
		table.lessons[2][2]!!.lessons[0].week = (1L shl 18) - 1  // 英语: 第1~18周
		table.lessons[2][5] = LessonGroup(3, 6).also {            // 讲座: 只在第1周上
			it.lessons = arrayOf(Lesson(type = 0, name = "讲座", len = 1, week = 1L shl 0))
		}
		val key = table.dateKeyOf(2, 6)
		table.overrides[key] = 3                                 // 第3周周日补周三的课

		com.qust.helper.data.lesson.LessonTableSaveBuilder.freezeBorrowedDates(table)

		val day = table.effectiveDay(2, 6)
		assertEquals("本周会上的课要保留", "英语", day[2]!!.lessons[0].name)
		assertNull("只在别的周上的课不能落到这一天", day[5])
	}

	@Test
	fun replaceLessonAt_overwritesWholeSpan_onlyThatDate() {
		val table = makeOriginalTable()   // 周一 1~2 节「高数」(两节连上)
		// 该日期第2节另有一门「物理」, 与「高数」叠在一起
		val col = table.editDayOverride(0, 0)
		col[1] = LessonGroup(1, 2).also { it.lessons = arrayOf(lessonOfLen("物理", 1, 1L shl 0)) }

		// 把两节长的「讲座」覆盖到第1节: 整段(1~2节)都要让位
		table.replaceLessonAt(0, 0, 0, lessonOfLen("讲座", 2, 1L))

		val day = table.effectiveDay(0, 0)
		assertEquals("粘贴的课程应出现在落点", "讲座", day[0]!!.lessons[0].name)
		assertNull("覆盖区间内的其它课程应被清掉", day[1])
		assertEquals("每周模板不能被按日期覆盖改掉", "高数", table.lessons[0][0]!!.lessons[0].name)
		assertEquals("其它日期(第2周)不受影响", "高数", table.effectiveDay(1, 0)[0]!!.lessons[0].name)
	}

	/**
	 * 【粘贴"要再点一下其它位置才显示"的根因锁定】
	 *
	 * 学期课表的显示优先级是: 按日期副本(dayOverrides) > 调休(overrides) > 每周模板。
	 * 目标日期一旦已经存在副本(之前做过"单独编辑此次课程"/调休后留下的), 之后写进**每周模板**的
	 * 课程会被副本整列遮住 —— 数据确实写进去了, 但画面上永远不显示, 双击那一格还会被当成空白,
	 * 弹出空表单(教室/教师为空、周次全不选)。
	 *
	 * 所以粘贴必须走 [LessonTable.replaceLessonAt] —— 只写该日期的副本(优先级最高), 当天立刻可见。
	 */
	@Test
	fun replaceLessonAt_visibleWhenDateAlreadyHasOverride() {
		val table = makeOriginalTable()   // 周一 1~2 节「高数」
		// 该日期此前被"单独编辑此次课程"过: 已存在按日期副本(副本里第3节有一门「英语」)
		table.editDayOverride(0, 0)[2] = LessonGroup(1, 3).also { it.lessons = arrayOf(lessonOfLen("英语", 1, 1L)) }

		// 旧口径(写每周模板): 被副本遮住, 当天看不到 —— 就是"要再点一下别的位置才出现"的原因
		table.getLessonGroupNotNull(0, 3).addLesson(lessonOfLen("旧口径", 1, 1L))
		assertTrue("模板里确实写进去了", table.lessons[0][3]!!.lessons.any { it.name == "旧口径" })
		assertNull(
			"但当天被副本整列遮住, 画面上看不到",
			table.effectiveDay(0, 0).getOrNull(3)?.lessons?.firstOrNull { it.name == "旧口径" }
		)

		// 新口径(粘贴走 replaceLessonAt): 写该日期副本, 当天立即可见
		table.replaceLessonAt(0, 0, 3, lessonOfLen("粘贴课", 1, 1L))
		val day = table.effectiveDay(0, 0)
		assertNotNull("粘贴后该格必须有课", day[3])
		assertEquals("粘贴后当天应立即能看到", "粘贴课", day[3]!!.lessons.first().name)
		assertEquals("上课周数只保留目标周", 1L, day[3]!!.lessons.first().week)
		assertEquals("当天原有课程不受影响", "英语", day[2]!!.lessons.first().name)
	}

	// ---------------- 编辑作息时间: 依次顺延 / 45·50 分钟 ----------------
	@Test
	fun editTimeWithShift_movesFollowingPeriods() {
		val times = TimeTables.fixedSchedule(perMin = 45, breakMin = 10, count = 4)
		// 第2节(索引1)上课时间 08:55 -> 09:10(晚15分钟), 后面的时间都应跟着后移15分钟
		val after = TimeTables.editTimeWithShift(times[0], times[1], 1, 0, "09:10")!!
		assertEquals("被改的那一项直接生效", "09:10", after[0][1])
		assertEquals("第1节不受影响", times[0][0], after[0][0])
		assertEquals("第1节下课不受影响", times[1][0], after[1][0])
		assertEquals("第2节下课顺延", TimeTables.formatTime(TimeTables.parseTime(times[1][1]) + 15), after[1][1])
		assertEquals("第3节上课顺延", TimeTables.formatTime(TimeTables.parseTime(times[0][2]) + 15), after[0][2])
		assertEquals("第4节下课顺延", TimeTables.formatTime(TimeTables.parseTime(times[1][3]) + 15), after[1][3])
		// 每节时长与课间结构不变
		assertEquals(45, TimeTables.periodMinutes(after)[1])
		assertFalse("顺延后不应产生冲突", TimeTables.hasConflict(after))
	}

	@Test
	fun setSlotDuration_switchesBetween45And50Minutes() {
		val times = TimeTables.fixedSchedule(perMin = 45, breakMin = 10, count = 3)
		val after = TimeTables.setSlotDuration(times[0], times[1], 0, 50)!!
		assertEquals("上课时间不变", times[0][0], after[0][0])
		assertEquals("第1节变成50分钟", 50, TimeTables.periodMinutes(after)[0])
		// 后面的课依次顺延 5 分钟
		assertEquals(TimeTables.formatTime(TimeTables.parseTime(times[0][1]) + 5), after[0][1])
		assertFalse(TimeTables.hasConflict(after))
	}

	// ---------------- 图片调休: 识别"一个阶段"的时间 ----------------

	@Test
	fun parseText_implicitStageWhenConnectorIsLost() {
		// OCR 把"至"认丢了: 只剩两个孤立日期, 也应识别成整段假期
		val items = com.qust.helper.ui.page.lesson.ScheduleAdjust.parseText(
			"2026年国庆节放假安排的通知。9月25日10月7日放假调休，共13天。", 2026, 9
		)
		val holidays = items.filter { it.action == com.qust.helper.ui.page.lesson.ScheduleAdjust.ACTION_CLEAR }.map { it.dateKey }
		assertEquals("应识别成一个时间段(13天)", 13, holidays.size)
		assertEquals("2026-09-25", holidays.first())
		assertEquals("2026-10-07", holidays.last())
	}

	@Test
	fun parseText_implicitStage_ignoresWorkdaySentences() {
		// 含"上班"的句子不能把日期连成假期
		val items = com.qust.helper.ui.page.lesson.ScheduleAdjust.parseText(
			"2026年通知。9月20日10月10日上班。", 2026, 9
		)
		assertTrue("上班句不应被当成假期", items.none { it.action == com.qust.helper.ui.page.lesson.ScheduleAdjust.ACTION_CLEAR })
	}

	@Test
	fun getCurrentLesson_invalidWeek_isNull() {
		val group = LessonGroup(1, 1).also { it.lessons = arrayOf(lessonOfLen("周一课", 1).also { l -> l.week = 1L shl 0 }) }
		assertNull("周次为0不应命中", group.getCurrentLesson(0))
		assertNull("周次越界不应命中", group.getCurrentLesson(64))
		assertNotNull("第1周应命中", group.getCurrentLesson(1))
	}

	// ---------- 首次启动按季节选择作息表 ----------

	private fun cal(year: Int, month: Int, day: Int): Calendar = Calendar.getInstance().apply {
		clear()
		set(Calendar.YEAR, year)
		set(Calendar.MONTH, month - 1)
		set(Calendar.DAY_OF_MONTH, day)
	}

	@Test
	fun isWinterSeason_boundaries() {
		assertTrue("端午当天(2026-06-19)仍属冬季作息", TimeTables.isWinterSeason(2026, 6, 19))
		assertFalse("端午次日转入夏季作息", TimeTables.isWinterSeason(2026, 6, 20))
		assertFalse("9月30日仍是夏季作息", TimeTables.isWinterSeason(2026, 9, 30))
		assertTrue("10月1日起进入冬季作息", TimeTables.isWinterSeason(2026, 10, 1))
		assertTrue("寒假期间属冬季作息", TimeTables.isWinterSeason(2027, 1, 15))
		assertFalse("暑假期间属夏季作息", TimeTables.isWinterSeason(2026, 7, 20))
	}

	// ---------- 学业查询: 培养方案模块解析(通识任选) ----------

	@Test
	fun academicParser_parsesModulesWithAnyIdChars() {
		val html = """
			<a xfyqjd_id='G001' yxxf='10.0' yqzdxf='20.0'>通识必修</a>
			<span xfyqjd_id="TS_RX-2024" yqzdxf='6.0' yxxf='2.0'>通识任选</span>
		""".trimIndent()

		val ids = AcademicPageParser.parseModuleIds(html)
		assertTrue("普通模块应解析出来", ids.containsKey("G001"))
		assertTrue("带下划线/连字符的模块 id 也必须解析出来", ids.containsKey("TS_RX-2024"))

		val credits = AcademicPageParser.parseCredits(html)
		assertEquals(20F, credits["G001"]!!.second)
		assertEquals(10F, credits["G001"]!!.first)
		assertEquals(6F, credits["TS_RX-2024"]!!.second)
		assertEquals(2F, credits["TS_RX-2024"]!!.first)
	}

	@Test
	fun academicParser_creditsWithReversedOrder() {
		// 部分部署先写 yqzdxf 再写 yxxf
		val html = "<a xfyqjd_id='A1' yqzdxf='8' yxxf='3'>专业选修</a>"
		val credits = AcademicPageParser.parseCredits(html)
		assertEquals(8F, credits["A1"]!!.second)
		assertEquals(3F, credits["A1"]!!.first)
	}

	@Test
	fun academicParser_parseCredits_attributesSeparatedByNoise() {
		// 真实页面里 id 与学分属性之间常隔着换行/其他属性,
		// 严格的"必须紧挨着"正则会匹配不到(学分变 0, 模块被当成 0 分模块)
		val html = "<a\n\t\txfyqjd_id='TSRX'\n\t\tclass='btn btn-xs' data-t='1'\n\t\tyxxf='0.0'\n\t\tyqzdxf='4.0'\n\t>通识任选</a>"
		val credits = AcademicPageParser.parseCredits(html)
		assertNotNull("属性被换行/其他属性隔开时也必须解析出学分", credits["TSRX"])
		assertEquals(4.0F, credits["TSRX"]!!.second)
		assertEquals(0.0F, credits["TSRX"]!!.first)
	}

	@Test
	fun academicParser_parseModuleHeaders_fromVisibleText() {
		// 标题行被标签打断 + 名字与学分在不同元素里, 也能在去标签后的文本上匹配到
		val html = "<div><b>通识任选</b><span>要求学分:</span>4.0 获得学分:11.5 未获得学分:6.0</div>" +
			"<div>专选 要求学分:12.5 获得学分:2.0 未获得学分:10.5</div>"
		val headers = AcademicPageParser.parseModuleHeaders(html)
		assertEquals(2, headers.size)
		assertEquals("通识任选", headers[0].name)
		assertEquals(4.0F, headers[0].requireCredits)
		assertEquals(11.5F, headers[0].obtainedCredits)
		assertEquals(6.0F, headers[0].notEarnedCredits)
		assertEquals("专选", headers[1].name)
		assertEquals(12.5F, headers[1].requireCredits)
	}

	@Test
	fun academicParser_parseModuleHeaders_traditionalChars() {
		// 页面偶发繁体: "要求學分" 也要能匹配(繁转简归一化)
		val html = "<div>通识任選 要求學分:4.0 獲得學分:0.0 未獲得學分:4.0</div>"
		val headers = AcademicPageParser.parseModuleHeaders(html)
		assertEquals(1, headers.size)
		assertEquals("通识任选", headers[0].name)
		assertEquals(4.0F, headers[0].requireCredits)
		assertEquals(0.0F, headers[0].obtainedCredits)
	}

	@Test
	fun academicParser_normalize_keepsAttributeParsingOnRawHtml() {
		// 归一化(去标签)只用于标题行; id/学分等属性仍从原始 HTML 解析
		val html = "<a xfyqjd_id='TSRX' yxxf='1.5' yqzdxf='4.0'>詳情</a>"
		assertTrue(AcademicPageParser.parseModuleIds(html).containsKey("TSRX"))
		assertNotNull(AcademicPageParser.parseCredits(html)["TSRX"])
		// 去标签后属性文本消失
		assertFalse(AcademicPageParser.normalize(html).contains("xfyqjd_id"))
		// 可见文本保留且繁体已转简
		assertTrue(AcademicPageParser.normalize(html).contains("详情"))
	}

	@Test
	fun academicParser_guessModuleName_forModuleWithoutCourses() {
		// 通识任选这类模块没有预置课程, 拿不到"课程性质", 只能从页面文本猜名字
		val html = "<div class=\"m\">模块</div><a href='#' xfyqjd_id='TSRX' yqzdxf='6.0' yxxf='0.0'>通识任选</a>"
		val ids = AcademicPageParser.parseModuleIds(html)
		val name = AcademicPageParser.guessModuleName(html, ids["TSRX"] ?: -1)
		assertEquals("通识任选", name)
	}

	@Test
	fun defaultIndexBySeason_prefersNewSummer() {
		TimeTables.resetToDefaults()
		TimeTables.addTimeTable("新夏季作息", TimeTables.getDefaultTimes(TimeTables.PRESET_SUMMER))
		val i = TimeTables.defaultIndexBySeason(cal(2026, 7, 20))
		assertEquals("有新的夏季作息时应默认用新的", "新夏季作息", TimeTables.entries[i].name)
	}
}

/**
 * 内存版 SharedPreferences, 供单元测试初始化 Setting
 */
class MemorySharedPreferences : SharedPreferences {
	private val map = HashMap<String, Any?>()
	private val listeners = ArrayList<SharedPreferences.OnSharedPreferenceChangeListener>()

	override fun getAll(): MutableMap<String, Any?> = HashMap(map)
	override fun getString(k: String, d: String?): String? = map[k] as? String ?: d
	override fun getStringSet(k: String, d: Set<String>?): Set<String>? = map[k] as? Set<String> ?: d
	override fun getInt(k: String, d: Int): Int = (map[k] as? Int) ?: d
	override fun getLong(k: String, d: Long): Long = (map[k] as? Long) ?: d
	override fun getFloat(k: String, d: Float): Float = (map[k] as? Float) ?: d
	override fun getBoolean(k: String, d: Boolean): Boolean = (map[k] as? Boolean) ?: d
	override fun contains(k: String): Boolean = map.containsKey(k)
	override fun edit(): SharedPreferences.Editor = MemoryEditor(map)
	override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) { listeners.add(l) }
	override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) { listeners.remove(l) }

	private class MemoryEditor(private val map: MutableMap<String, Any?>) : SharedPreferences.Editor {
		override fun putString(k: String, v: String?): SharedPreferences.Editor { map[k] = v; return this }
		override fun putStringSet(k: String, v: Set<String>?): SharedPreferences.Editor { map[k] = v; return this }
		override fun putInt(k: String, v: Int): SharedPreferences.Editor { map[k] = v; return this }
		override fun putLong(k: String, v: Long): SharedPreferences.Editor { map[k] = v; return this }
		override fun putFloat(k: String, v: Float): SharedPreferences.Editor { map[k] = v; return this }
		override fun putBoolean(k: String, v: Boolean): SharedPreferences.Editor { map[k] = v; return this }
		override fun remove(k: String): SharedPreferences.Editor { map.remove(k); return this }
		override fun clear(): SharedPreferences.Editor { map.clear(); return this }
		override fun apply() { }
		override fun commit(): Boolean = true
	}
}

/**
 * 通过反射注入内存版 SharedPreferences 与一个临时课表目录, 以便无 Android 环境初始化 Setting
 */
private fun installMockSetting() {
	val cls = Setting::class.java
	val spField = cls.getDeclaredField("defaultSharedPreferences").apply { isAccessible = true }
	spField.set(Setting, MemorySharedPreferences())
	val folderField = cls.getDeclaredField("lessonTableFolder").apply { isAccessible = true }
	val tmp = File.createTempFile("qttest_lt", "").also { it.delete(); it.mkdirs() }
	folderField.set(Setting, tmp)
}
