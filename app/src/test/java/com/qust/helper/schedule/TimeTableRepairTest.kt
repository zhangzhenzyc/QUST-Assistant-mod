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

import com.qust.helper.data.Setting
import com.qust.helper.data.lesson.TimeTableEntry
import com.qust.helper.data.lesson.TimeTables
import com.qust.helper.model.LessonTableRepository
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * 【第六十八轮】"第 11 节课的时间还是不显示"的**数据侧**复现与回归。
 *
 * 用户那份存档的关键特征(从截图逐行比对): 起止时间 = 内置「新冬季时间」的前 10 条,
 * 第 9 条 18:00-18:45、第 10 条 18:55-19:40, **只少了尾巴 19:50-20:35**。
 *
 * 这里把四种可能的存档形态都写出来, 断言小组件真正读到的节数(`currentSlotCount`)符合预期:
 *  ① 自定义表(`presetIndex = -1`)的 10 条**严格前缀**   -> 补成 11;
 *  ② 内置预设(`presetIndex = 2`)的 10 条旧存档           -> 补成 11;
 *  ③ 自定义表**有一节被微调过**(不是严格前缀)           -> 也要补成 11, 且那一节保持用户的值;
 *  ④ 真正的另一套作息(差 3 条以上)                      -> **保持 10**, 不能被"补全"改坏。
 *
 * 另有一条脏数据兜底: 起止条数不一致时回退成固定 45 分钟作息。
 */
class TimeTableRepairTest {

	private val KEY_ENTRY_LIST = "key_time_table_list_v2"

	/** 内置「新冬季时间」的 11 条 */
	private val start11 = listOf(
		"08:00", "08:55", "10:00", "10:55", "13:30", "14:25",
		"15:30", "16:25", "18:00", "18:55", "19:50"
	)
	private val end11 = listOf(
		"08:45", "09:40", "10:45", "11:40", "14:15", "15:10",
		"16:15", "17:10", "18:45", "19:40", "20:35"
	)

	@Before
	fun setUp() {
		installMockSetting()
		Setting[KEY_ENTRY_LIST] = null
		TimeTables.reload()
		TimeTables.resetToDefaults()
	}

	/** ① 自定义表 + 10 条严格前缀 -> 补成 11 节 */
	@Test
	fun customPrefix_isRepairedTo11() {
		seed(
			TimeTableEntry(
				id = "custom_1699999999999",
				name = "新冬季时间",
				start = start11.take(10),
				end = end11.take(10),
				presetIndex = -1
			)
		)
		assertEquals("自定义表是内置预设的前缀时, 节数应补到 11", 11, TimeTables.currentSlotCount)
		assertEquals("19:50", TimeTables.currentTimes[0][10])
		assertEquals("20:35", TimeTables.currentTimes[1][10])
	}

	/** ② 内置预设(新冬季) + 10 条旧存档 -> 用内置 11 节 */
	@Test
	fun stalePreset_isRepairedTo11() {
		seed(
			TimeTableEntry(
				id = "preset_2",
				name = "新冬季时间",
				start = start11.take(10),
				end = end11.take(10),
				presetIndex = 2
			)
		)
		assertEquals(11, TimeTables.currentSlotCount)
	}

	/** ③ 有一节被微调过(差 1 条) -> 仍然补到 11, 且那一节保持用户的值 */
	@Test
	fun slightlyEditedCustom_isRepaired_andKeepsEdit() {
		val start = start11.take(10).toMutableList().also { it[2] = "10:05" }
		seed(
			TimeTableEntry(
				id = "custom_1",
				name = "我的课表",
				start = start,
				end = end11.take(10),
				presetIndex = -1
			)
		)
		assertEquals("差一条也应补到 11 节", 11, TimeTables.currentSlotCount)
		assertEquals("用户改过的 10:05 必须保留", "10:05", TimeTables.currentTimes[0][2])
		assertEquals("19:50", TimeTables.currentTimes[0][10])
	}

	/**
	 * ④ 真正的另一套作息(末尾是 19:30-20:20, 不是新冬季那个 18:55-19:40) -> 不能被补全改坏。
	 *
	 * 【第六十八轮·三】注意末两条被改成了 冬季时间 的值: 原先这条用例只改了中间几节,
	 * 末节仍是 18:55-19:40 —— 而 `currentTimes` 新增的兜底判据("末节 18:55-19:40 就补第 11 节")
	 * 会（按设计）把这种表也补成 11 节。所以这里改成末尾也对不上, 才测得出"不误伤"。
	 */
	@Test
	fun differentSchedule_isLeftAlone() {
		val start = start11.take(10).toMutableList().also {
			it[2] = "10:05"
			it[5] = "14:30"
			it[7] = "16:30"
			it[8] = "18:30"
			it[9] = "19:30"
		}
		val end = end11.take(10).toMutableList().also {
			it[8] = "19:20"
			it[9] = "20:20"
		}
		seed(
			TimeTableEntry(
				id = "custom_3",
				name = "自己排的",
				start = start,
				end = end,
				presetIndex = -1
			)
		)
		assertEquals("差 3 条说明是另一套作息, 必须保持 10 节", 10, TimeTables.currentSlotCount)
		assertEquals("10:05", TimeTables.currentTimes[0][2])
	}

	/** ⑤ 起止条数不一致的脏数据 -> 兜底成固定 45 分钟作息 */
	@Test
	fun brokenEntry_fallsBackToFixedSchedule() {
		seed(
			TimeTableEntry(
				id = "custom_2",
				name = "坏数据",
				start = start11.take(10),
				end = end11.take(8),
				presetIndex = -1
			)
		)
		assertEquals(TimeTables.MAX_SLOT_COUNT, TimeTables.currentSlotCount)
	}

	/**
	 * ⑥ **用户设备上真正的形态**: `presetIndex = 0`(冬季时间), 但内容是「新冬季」的前 10 条。
	 *
	 * 第六十八轮·二之前, 补全只写在 `presetIndex < 0` 分支里, 这一类表一条判据都进不去 ——
	 * 这正是"单测全绿、桌面上还是 10 节"的原因。
	 */
	@Test
	fun presetZeroWithNewWinterContent_isRepairedTo11() {
		seed(
			TimeTableEntry(
				id = "preset_0",
				name = "冬季时间",
				start = start11.take(10),
				end = end11.take(10),
				presetIndex = 0
			)
		)
		assertEquals("内置预设 0 也必须有补全的机会", 11, TimeTables.currentSlotCount)
		assertEquals("19:50", TimeTables.currentTimes[0][10])
		assertEquals("20:35", TimeTables.currentTimes[1][10])
	}

	/**
	 * ⑦ 改过两节(宽松判据进不去), 但**末尾三行**还是原样 -> 靠 [TimeTables] 的尾巴判据补上。
	 */
	@Test
	fun editedMiddle_tailSignatureStillRepairs() {
		val start = start11.take(10).toMutableList().also {
			it[2] = "10:05"
			it[5] = "14:30"
		}
		seed(
			TimeTableEntry(
				id = "custom_4",
				name = "改过两节",
				start = start,
				end = end11.take(10),
				presetIndex = 0
			)
		)
		assertEquals("末尾三行对得上就该补尾巴", 11, TimeTables.currentSlotCount)
		assertEquals("10:05", TimeTables.currentTimes[0][2])
		assertEquals("14:30", TimeTables.currentTimes[0][5])
	}

	/**
	 * ⑧ 最后一层闸门(`TimeTables.currentTimes` 里的 `ensureEveningTail`):
	 * 只要末节是 18:55-19:40 且不足 11 节, 不管前面被改成什么样, 都补上第 11 节。
	 *
	 * 这一条是为"前面两层都进不去、桌面上却还是 10 行"的现场准备的兜底。
	 */
	@Test
	fun lastRowIsEveningTail_stillAppendsEleventh() {
		val start = start11.take(10).toMutableList().also {
			it[6] = "15:20"      // 第 7/8 节被改过 -> 前缀/尾巴两层都进不去
			it[7] = "16:20"
		}
		seed(
			TimeTableEntry(
				id = "custom_5",
				name = "只差最后一节",
				start = start,
				end = end11.take(10),
				presetIndex = 0
			)
		)
		assertEquals("末节 18:55-19:40 就该有第 11 节", 11, TimeTables.currentSlotCount)
		assertEquals("19:50", TimeTables.currentTimes[0][10])
		assertEquals("20:35", TimeTables.currentTimes[1][10])
		assertEquals("15:20", TimeTables.currentTimes[0][6])
	}

	/**
	 * 写入存档并**把"当前使用"指到这一条**。
	 *
	 * 注意 `fillMissingPresets` 会把缺的内置预设插到前面, 所以下标不能假设是 0 ——
	 * 必须按 id 找回真实下标(这也是第一版测试误判的原因)。
	 */
	private fun seed(vararg entries: TimeTableEntry) {
		Setting[KEY_ENTRY_LIST] = Json.encodeToString(entries.toList())
		TimeTables.reload()
		val target = entries.first().id
		val index = TimeTables.entries.indexOfFirst { it.id == target }
		LessonTableRepository.setTimeTableValue(if(index >= 0) index else 0)
	}
}

/**
 * 与 `ScheduleVerificationTest` 里那份同名的私有函数互不影响(文件级作用域):
 * 通过反射注入内存版 SharedPreferences 与临时课表目录, 让 `Setting` 在没有 Android 环境时也能用。
 */
private fun installMockSetting() {
	val cls = Setting::class.java
	val spField = cls.getDeclaredField("defaultSharedPreferences").apply { isAccessible = true }
	spField.set(Setting, MemorySharedPreferences())
	val folderField = cls.getDeclaredField("lessonTableFolder").apply { isAccessible = true }
	val tmp = java.io.File.createTempFile("qttest_lt2", "").also { it.delete(); it.mkdirs() }
	folderField.set(Setting, tmp)
}
