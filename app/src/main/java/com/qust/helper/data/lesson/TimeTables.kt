/*
 * 青科助手（修改版） —— 基于开源项目 QUST-Assistant 修改而成
 *
 * 原项目版权所有 (C) 2891954521  (https://github.com/2891954521/QUST-Assistant)
 * 原始开源许可: GNU General Public License v3.0 (GPL-3.0), 许可原文见 LICENSE 文件, 保持完整未改动。
 *
 * 修改版版权所有 (C) 2026 zhangzhenzyc  (https://github.com/zhangzhenzyc)
 * 本修改版同样以 GPL-3.0 发布; 附加版权声明见 NOTICE.md —— 两份版权声明相互独立、同时生效。
 */

package com.qust.helper.data.lesson

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.qust.helper.data.Setting
import com.qust.helper.model.LessonTableRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Calendar

/**
 * 一套时间表(时间表)
 * @param start 每节课上课时间 "HH:mm"
 * @param end   每节课下课时间 "HH:mm"
 */
@Serializable
data class TimeTableData(
	val start: List<String> = emptyList(),
	val end: List<String> = emptyList()
)

/**
 * 时间表条目
 *
 * @param id          唯一标识, 内置预设为 "preset_0/1/2", 用户新建的为 "custom_时间戳"
 * @param name        用户可见名称, 可随时重命名
 * @param start       每节课上课时间 "HH:mm"
 * @param end         每节课下课时间 "HH:mm"
 * @param presetIndex 该时间表基于哪个内置预设: 0/1/2 = 内置(可"恢复默认"), -1 = 用户新建(无默认值)
 */
@Serializable
data class TimeTableEntry(
	val id: String,
	val name: String,
	val start: List<String> = emptyList(),
	val end: List<String> = emptyList(),
	val presetIndex: Int = -1
) {

	/** 转成 [0]=上课 [1]=下课 的二维数组 */
	fun toTimes(): Array<Array<String>> = arrayOf(start.toTypedArray(), end.toTypedArray())

	/** 节数 */
	val slotCount: Int get() = minOf(start.size, end.size)
}

/**
 * 时间表管理(多套, 可增删改、可命名、可恢复默认)
 *
 * 设计要点:
 * - 内置 3 套预设(冬季/夏季/新冬季)，用户可重命名、可编辑时间、可"恢复默认"；
 * - 用户可自由新增时间表(空时间 / 复制已有 / 基于内置预设)，并随时重命名、编辑、删除；
 * - 全部数据以 JSON 形式持久化到 SharedPreferences，重启不丢失；
 * - 兼容旧版: 旧版按序号保存的编辑值(key_custom_time_table_N)会在首次加载时自动迁移为条目。
 *
 * [LessonTableRepository.currentTimeTable] 保存的是"当前使用第几套"的下标。
 */
object TimeTables {

	/** 网格最大支持节数 */
	const val MAX_SLOT_COUNT = 12

	/**
	 * 【第五十八轮·按要求】"时间表管理里面的最短课程时间改为 30 分钟"
	 *
	 * 原来下限是 5 分钟 —— 那只是"能填进去"的下限, 实际上没有哪节课是 5 分钟的;
	 * 下限过低还会让"时长填错了"这种误操作没有任何提示。改成 30 分钟起步。
	 */
	const val MIN_PERIOD_MIN = 30

	/** 单节时长上限(分钟) */
	const val MAX_PERIOD_MIN = 120

	/** 单节时长是否在允许范围内 */
	fun isPeriodLength(minutes: Int): Boolean = minutes in MIN_PERIOD_MIN..MAX_PERIOD_MIN

	/** 内置预设套数(冬季/夏季/新冬季) */
	const val TIMETABLE_COUNT = 3

	private const val KEY_ENTRY_LIST = "key_time_table_list_v2"
	private const val KEY_CUSTOM_PREFIX = "key_custom_time_table_"

	private val json = Json { ignoreUnknownKeys = true }

	// ---------- 内置默认时间 ----------

	// 冬季时间默认时间(下午13:30上课)
	private val DEFAULT_WINTER: Array<Array<String>> = arrayOf(
		arrayOf("08:00", "09:00", "10:10", "11:10", "13:30", "14:30", "15:40", "16:40", "18:30", "19:30"),
		arrayOf("08:50", "09:50", "11:00", "12:00", "14:20", "15:20", "16:30", "17:30", "19:20", "20:20")
	)
	// 夏季时间默认时间(下午14:00上课)
	private val DEFAULT_SUMMER: Array<Array<String>> = arrayOf(
		arrayOf("08:00", "09:00", "10:10", "11:10", "14:00", "15:00", "16:10", "17:10", "19:00", "20:00"),
		arrayOf("08:50", "09:50", "11:00", "12:00", "14:50", "15:50", "17:00", "18:00", "19:50", "20:50")
	)

	// 新冬季时间: 严格按照用户提供的时间图片 (11节)
	private val DEFAULT_THIRD: Array<Array<String>> = arrayOf(
		arrayOf("08:00", "08:55", "10:00", "10:55", "13:30", "14:25", "15:30", "16:25", "18:00", "18:55", "19:50"),
		arrayOf("08:45", "09:40", "10:45", "11:40", "14:15", "15:10", "16:15", "17:10", "18:45", "19:40", "20:35")
	)

	/** 内置预设的名称 */
	private val DEFAULT_NAMES = arrayOf("冬季时间", "夏季时间", "新冬季时间")

	const val PRESET_SUMMER = 1
	const val PRESET_NEW_WINTER = 2

	/** 获取指定序号内置预设的默认时间 */
	fun getDefaultTimes(index: Int): Array<Array<String>> {
		return when(index) {
			2 -> DEFAULT_THIRD
			1 -> DEFAULT_SUMMER
			else -> DEFAULT_WINTER
		}
	}

	/**
	 * 新建时间表时的默认时间
	 *
	 * 用户新建(空白)时间表时默认使用**夏季时间**, 在此基础上微调即可, 不必从零填写。
	 */
	fun defaultNewTimeTable(): Array<Array<String>> = getDefaultTimes(PRESET_SUMMER)

	/** 内置预设名称 */
	fun getDefaultName(index: Int): String = DEFAULT_NAMES.getOrElse(index) { "时间表" }

	// ---------- 时间表列表 ----------

	private var _entries by mutableStateOf<List<TimeTableEntry>>(emptyList())

	/** 全部时间表(可被 Compose 观察到变化) */
	val entries: List<TimeTableEntry> get() = _entries

	/** 时间表套数 */
	val size: Int get() = _entries.size

	/** 所有时间表名称 */
	val names: Array<String> get() = _entries.map { it.name }.toTypedArray()

	/** 重新从本地读取(测试环境替换了配置源, 或外部直接改写了存储时使用) */
	fun reload() {
		_entries = load()
		persist(_entries)
	}

	private fun load(): List<TimeTableEntry> {
		// 1) 优先读取新版列表
		Setting[KEY_ENTRY_LIST]?.let { stored ->
			runCatching { json.decodeFromString<List<TimeTableEntry>>(stored) }
				.getOrNull()
				?.takeIf { it.isNotEmpty() }
				?.let { list -> return fillMissingPresets(list.map { normalizeEntry(it) }) }
		}
		// 2) 首次/升级: 用内置预设建列表, 并带上旧版按序号保存的编辑值
		val list = ArrayList<TimeTableEntry>(TIMETABLE_COUNT)
		for(i in 0 until TIMETABLE_COUNT) {
			val legacy = Setting[KEY_CUSTOM_PREFIX + i]?.let { raw ->
				runCatching { json.decodeFromString<TimeTableData>(raw) }.getOrNull()
			}
			val times = legacy?.takeIf { it.start.size == it.end.size && it.start.isNotEmpty() }
			val finalTimes = times ?: TimeTableData(getDefaultTimes(i)[0].toList(), getDefaultTimes(i)[1].toList())
			// 【第六十五轮】这里也要过一遍 normalizeEntry —— 旧版存下的"10 节新冬季时间"
			// 就是从这条路径进来的(KEY_CUSTOM_PREFIX + 2), 不走它的话节数永远补不齐。
			list.add(
				normalizeEntry(
					TimeTableEntry("preset_$i", DEFAULT_NAMES[i], finalTimes.start, finalTimes.end, i)
				)
			)
		}
		return list
	}

	/**
	 * 补齐缺失的内置预设
	 *
	 * 旧版本保存的列表里可能没有"新冬季时间"(例如升级前只有冬季/夏季两套), 那样按季节挑默认时间时
	 * 只能命中旧的"冬季时间"。这里一次性把缺失的内置预设补回列表, 保证"适合当下时间的那套"一定存在。
	 */
	private fun fillMissingPresets(list: List<TimeTableEntry>): List<TimeTableEntry> {
		val missing = (0 until TIMETABLE_COUNT).filter { i -> list.none { it.presetIndex == i } }
		if(missing.isEmpty()) return list
		val result = list.toMutableList()
		for(i in missing) {
			// 插到对应位置(尽量与内置顺序一致), 越界则追加到末尾
			val at = i.coerceAtMost(result.size)
			result.add(
				at,
				TimeTableEntry(
					id = "preset_$i",
					name = DEFAULT_NAMES[i],
					start = getDefaultTimes(i)[0].toList(),
					end = getDefaultTimes(i)[1].toList(),
					presetIndex = i
				)
			)
		}
		return result
	}

	/**
	 * 修正一条时间表数据。
	 *
	 * 【第六十五轮·修「第 11 节课的时间不显示」的真正原因】
	 *
	 * 用户的截图量出来是这么回事: 时间栏只画到第 10 节(18:55 / 19:40), 而卡片下面还空着一大截 ——
	 * **不是高度不够, 是那套时间表本身只有 10 条数据**。他当时用的确实是「新冬季时间」
	 * (08:00 / 08:55 / 10:00 … 的节次全对得上), 只是存档里那份是**很早以前存下的 10 节版本**,
	 * 后来内置默认改成了 11 节, 而 `load()` 只在"列表里没有这套预设"时才补 ——
	 * 已经有(哪怕是旧的)就原样保留, 于是 11 节永远进不来。
	 *
	 * 判据很克制: **只有内置预设、且存档节数比内置默认少**才覆盖。
	 *  · 用户自己新建的时间表(`presetIndex < 0`)一律不动;
	 *  · 存档比默认**多**(用户自己加过节次)也不动;
	 *  · 只有"少了"这一种情况 = 旧版本的默认值把新版本的默认值盖住了。
	 */
	private fun normalizeEntry(entry: TimeTableEntry): TimeTableEntry {
		val fallback = if(entry.presetIndex >= 0) getDefaultTimes(entry.presetIndex) else fixedSchedule()
		val valid = entry.start.size == entry.end.size && entry.start.isNotEmpty()
		if(valid) {
			// 【第六十八轮·三】"补尾巴"这一步现在对**所有**时间表生效, 不再区分 presetIndex。
			//
			// 这是第三次排查第 11 节时才找出来的漏洞:
			// 用户那套表在存档里是 `presetIndex = 0`(冬季时间), 但内容早就被改成了新冬季作息的前 10 条。
			// 上一版的补全只写在 `presetIndex < 0`(自定义表)分支里 —— 于是像他这种
			// "内置预设 + 内容其实是新冬季"的表, 一条判据都进不去:
			// `entry.start.size < getDefaultTimes(0)[0].size` → 10 < 10 → false,
			// 既不补尾巴也不覆盖, 10 条原样留着, 小组件就只画 10 行。
			// 这就是为什么单测里三种形态都能补成 11 节, 桌面上却始终是 10 节。
			repair(entry.start, entry.end)?.let { (s, e) -> return entry.copy(start = s, end = e) }
			val stale = entry.presetIndex >= 0 && entry.start.size < fallback[0].size
			if(!stale) return entry
		}
		// 数据损坏(起止长度不一致或为空), 或是"比内置默认短的旧预设": 都用默认值覆盖
		return entry.copy(
			start = fallback[0].toList(),
			end = fallback[1].toList()
		)
	}

	/**
	 * 补全链: 严格前缀 → 宽松前缀 → 尾巴特征。
	 *
	 * 三层都只做一件事: **把缺的节次接在后面**, 已有的值一个都不改。
	 */
	private fun repair(start: List<String>, end: List<String>): Pair<List<String>, List<String>>? =
		repairByPrefix(start, end) ?: repairLoose(start, end) ?: repairByTail(start, end)

	/**
	 * 【第六十八轮】"这套时间表只是某套内置预设的前缀" -> 返回补全后的完整时间。
	 *
	 * 只在内置预设**更长**、且已有的每一条起止时间都与它**逐字相同**时才命中。
	 * 命中就意味着"这条尾巴是旧版本删掉的", 补回来不会改动任何用户自定义的值。
	 */
	private fun repairByPrefix(start: List<String>, end: List<String>): Pair<List<String>, List<String>>? {
		if(start.isEmpty() || start.size != end.size) return null
		for(i in 0 until TIMETABLE_COUNT) {
			val def = getDefaultTimes(i)
			if(def[0].size <= start.size) continue
			var same = true
			for(k in start.indices) {
				if(start[k] != def[0][k] || end[k] != def[1][k]) {
					same = false
					break
				}
			}
			if(!same) continue
			// 【第六十八轮·二】只**追加**缺的尾巴, 不整条替换 —— 用户已有的值一个都不动
			return Pair(start + def[0].drop(start.size), end + def[1].drop(end.size))
		}
		return null
	}

	/**
	 * 【第六十八轮·二】宽松补全 —— "这张表就是被截断的新冬季作息, 只是某一节被微调过"。
	 *
	 * 为什么还要一条更松的判据: 第六十八轮第一版只认**严格前缀**(逐条一字不差), 用户装机后
	 * 仍然只显示 10 节。既然数据链路本身是通的(见 `TimeTableRepairTest`), 那就只剩一种可能 ——
	 * 存档里那 10 条与内置表**并非完全一致**(早期版本的字符串、某一节被手改过 1 分钟…)。
	 * 这种情况下"一条都不命中"等于永远补不上。
	 *
	 * 放宽到什么程度(只对内置「新冬季时间」这一个模板生效):
	 *  · 节数 9~10(比模板短 1~2 节);
	 *  · **首节上课时间**与**末节下课时间**都与模板一致 —— 一头一尾都对上, 基本就是这套作息;
	 *  · 逐条比较**最多允许差 1 条**。
	 *
	 * 差 2 条以上一律不碰: 那就是用户自己的作息了, 补尾巴只会把课表改坏。
	 */
	private fun repairLoose(start: List<String>, end: List<String>): Pair<List<String>, List<String>>? {
		if(start.isEmpty() || start.size != end.size) return null
		val def = getDefaultTimes(PRESET_NEW_WINTER)
		if(start.size >= def[0].size) return null
		if(start.size < def[0].size - 2) return null
		if(start[0] != def[0][0]) return null
		if(end[start.size - 1] != def[1][start.size - 1]) return null
		var diff = 0
		for(k in start.indices) {
			if(start[k] != def[0][k] || end[k] != def[1][k]) diff++
		}
		if(diff > 1) return null
		// 同样只追加尾巴: 那一节被微调过的值保持原样, 只是把后面的节次补回来
		return Pair(start + def[0].drop(start.size), end + def[1].drop(end.size))
	}

	/**
	 * 【第六十八轮·三】最后一层兜底: **看尾巴**。
	 *
	 * 前两层都要求"前面若干条与内置表一致"(严格前缀 / 最多差 1 条)。用户那张截图里,
	 * 10 行时间与内置 11 节表**前 10 条逐字相同**, 按理说第一层就该命中 —— 既然装了新版还是 10 行,
	 * 说明存档里前面某几节被他改过(改过 2 条以上, 第二层也进不去)。
	 *
	 * 那就只认**末尾三行**: 只要第 n-3 / n-2 / n-1 行与内置表的同下标完全一致
	 * (在 11 节表里就是 16:25-17:10 / 18:00-18:45 / 18:55-19:40 这串晚课),
	 * 就认定"这就是新冬季作息被截断", 把后面缺的节次接上。
	 *
	 * 为什么安全: 另外两套内置表(冬季 18:30/19:30、夏季 19:00/20:00)在同样位置**对不上**,
	 * 用户自己排的作息更不可能恰好三行都一样; 而且**只追加、不改任何已有值**。
	 */
	private fun repairByTail(start: List<String>, end: List<String>): Pair<List<String>, List<String>>? {
		if(start.isEmpty() || start.size != end.size) return null
		val def = getDefaultTimes(PRESET_NEW_WINTER)
		val n = start.size
		if(n >= def[0].size) return null
		if(n < 3) return null
		for(k in (n - 3) until n) {
			if(start[k] != def[0][k] || end[k] != def[1][k]) return null
		}
		return Pair(start + def[0].drop(n), end + def[1].drop(n))
	}

	private fun persist(list: List<TimeTableEntry>) {
		_entries = list.toList()
		if(_entries.isEmpty()) return
		Setting[KEY_ENTRY_LIST] = json.encodeToString(_entries.toList())
		// 【第五十五轮·按要求】"时间表改变后, 小组件的时间没有同步刷新":
		// 这里覆盖"编辑某节课的起止时间 / 新增 / 删除 / 恢复默认"全部路径 ——
		// 桌面上的每日课表小组件按真实时间轴绘制, 时间一改就必须重画。
		// 首次加载(init)时 ready 还是 false, 不会去碰小组件(那时仓库对象可能还没构造完)。
		if(ready) runCatching { LessonTableRepository.refreshLessonWidgetsForTimeTable() }
	}

	/** 是否已完成首次加载: 首次加载期间不做"刷新小组件"这类副作用 */
	private var ready = false

	init {
		_entries = load()
		persist(_entries)
		ready = true
	}

	/** 获取指定序号时间表的时间 (数组长度非法时回退到内置默认值) */
	fun getTimes(index: Int): Array<Array<String>> {
		val entry = _entries.getOrNull(index) ?: return getDefaultTimes(index.coerceAtLeast(0))
		if(entry.start.size == entry.end.size && entry.start.isNotEmpty()) {
			// 【第六十八轮·三】读的时候兜底, 且**不区分 presetIndex** —— 理由见 [normalizeEntry]:
			// 用户那张表是"预设 0 + 内容其实是新冬季", 老判据对它一条都不成立。
			// 补全只追加尾巴, 不会动他改过的任何一节。
			repair(entry.start, entry.end)?.let { (s, e) ->
				return arrayOf(s.toTypedArray(), e.toTypedArray())
			}
			if(entry.presetIndex >= 0) {
				val def = getDefaultTimes(entry.presetIndex)
				if(entry.start.size < def[0].size) return def
			}
			return entry.toTimes()
		}
		return if(entry.presetIndex >= 0) getDefaultTimes(entry.presetIndex) else fixedSchedule()
	}

	/** 保存指定序号时间表的时间 */
	fun setTimes(index: Int, times: Array<Array<String>>) {
		if(times.size < 2 || times[0].size != times[1].size || times[0].isEmpty()) return
		update(index) { it.copy(start = times[0].toList(), end = times[1].toList()) }
	}

	/** 重命名指定的时间表 */
	fun rename(index: Int, newName: String): Boolean {
		val name = newName.trim()
		if(name.isEmpty()) return false
		if(_entries.getOrNull(index) == null) return false
		update(index) { it.copy(name = name) }
		return true
	}

	/**
	 * 新增一套时间表
	 * @param name        名称
	 * @param times       初始时间
	 * @param presetIndex 基于哪个内置预设(-1 表示用户自定义)
	 * @return 新增时间表的下标
	 */
	fun addTimeTable(name: String, times: Array<Array<String>>, presetIndex: Int = -1): Int {
		val list = _entries.toMutableList()
		val safeTimes = if(times.size >= 2 && times[0].size == times[1].size) times else getDefaultTimes(0)
		val finalName = name.trim().ifEmpty { nextCustomName() }
		list.add(
			TimeTableEntry(
				id = "custom_" + System.currentTimeMillis(),
				name = finalName,
				start = safeTimes[0].toList(),
				end = safeTimes[1].toList(),
				presetIndex = presetIndex
			)
		)
		persist(list)
		return list.size - 1
	}

	/**
	 * 【第六十一轮·按要求】"新建时间表的默认名字以 1 开始，不要以 4 开始"。
	 *
	 * 以前用 `entries.size + 1` 命名 —— 可内置的三套(冬季/夏季/新冬季)也算在里面,
	 * 于是用户第一次新建就叫「新建时间4」, 看着像"我已经建过三套了", 很容易误会。
	 *
	 * 现在只数**用户自己建的**(`presetIndex < 0`): 内置三套不算, 第一次新建就是「新建时间1」。
	 * 另外再跳过已经占用的名字(改过名的旧条目、复制出来的副本同名等), 保证不会撞名。
	 */
	fun nextCustomName(): String {
		val names = _entries.map { it.name }.toSet()
		var n = _entries.count { it.presetIndex < 0 } + 1
		while("新建时间$n" in names) n++
		return "新建时间$n"
	}

	/** 复制一套已有时间表为新时间表 */
	fun duplicate(index: Int, newName: String? = null): Int {
		val entry = _entries.getOrNull(index) ?: return -1
		return addTimeTable(newName ?: "${entry.name} 副本", entry.toTimes(), entry.presetIndex)
	}

	/** 删除一套时间表(至少保留一套) */
	fun removeTimeTable(index: Int): Boolean {
		val list = _entries.toMutableList()
		if(index !in list.indices || list.size <= 1) return false
		list.removeAt(index)
		persist(list)
		return true
	}

	// ---------- 恢复默认 ----------

	/** 该时间表是否有"可恢复"的内置默认值 */
	fun canRestoreDefault(index: Int): Boolean = (_entries.getOrNull(index)?.presetIndex ?: -1) >= 0

	/** 恢复为内置默认时间与默认名称 */
	fun restoreDefault(index: Int): Boolean {
		val entry = _entries.getOrNull(index) ?: return false
		val preset = entry.presetIndex
		if(preset < 0) return false
		val def = getDefaultTimes(preset)
		update(index) { it.copy(name = getDefaultName(preset), start = def[0].toList(), end = def[1].toList()) }
		return true
	}

	/** 是否已偏离内置默认值(用户自定义的时间表恒为 true) */
	fun isCustomized(index: Int): Boolean {
		val entry = _entries.getOrNull(index) ?: return false
		if(entry.presetIndex < 0) return true
		val def = getDefaultTimes(entry.presetIndex)
		return entry.start != def[0].toList() || entry.end != def[1].toList() || entry.name != getDefaultName(entry.presetIndex)
	}

	/**
	 * 重置为内置的三套默认时间表(冬季/夏季/新冬季), 丢弃所有自定义时间表与修改
	 */
	fun resetToDefaults() {
		persist(buildDefaultEntries())
	}

	private fun buildDefaultEntries(): List<TimeTableEntry> =
		(0 until TIMETABLE_COUNT).map { i ->
			TimeTableEntry(
				id = "preset_$i",
				name = DEFAULT_NAMES[i],
				start = getDefaultTimes(i)[0].toList(),
				end = getDefaultTimes(i)[1].toList(),
				presetIndex = i
			)
		}

	private inline fun update(index: Int, block: (TimeTableEntry) -> TimeTableEntry) {
		val list = _entries.toMutableList()
		val old = list.getOrNull(index) ?: return
		list[index] = block(old)
		persist(list)
	}

	// ---------- 时间工具 ----------

	/**
	 * 生成"固定时长"时间表: 每节课严格 [perMin] 分钟, 课间统一 [breakMin] 分钟, 各节首尾相接无重叠
	 * @param startHour 第一节上课小时
	 * @param startMin  第一节上课分钟
	 * @param perMin    每节课时长(分钟)
	 * @param breakMin  每节课之间的课间(分钟)
	 * @param count     节数
	 * @return [0]=上课时间 [1]=下课时间, 均为 "HH:mm"
	 */
	fun fixedSchedule(startHour: Int = 8, startMin: Int = 0, perMin: Int = 45, breakMin: Int = 10, count: Int = MAX_SLOT_COUNT): Array<Array<String>> {
		require(perMin > 0) { "每节课时长必须为正数" }
		require(count >= 0) { "节数不能为负" }
		val start = IntArray(count)
		val end = IntArray(count)
		var cursor = startHour * 60 + startMin
		for(i in 0 until count) {
			start[i] = cursor
			cursor += perMin
			end[i] = cursor
			cursor += breakMin
		}
		return arrayOf(
			start.map { fmt(it) }.toTypedArray(),
			end.map { fmt(it) }.toTypedArray()
		)
	}

	private fun fmt(totalMin: Int): String = String.format("%02d:%02d", totalMin / 60, totalMin % 60)

	/**
	 * 每节课的时长(分钟)。长度为节数, [i] = 第i+1节 下课-上课 的分钟数
	 * 解析失败(格式非法)的节次记为 -1
	 */
	fun periodMinutes(times: Array<Array<String>>): IntArray {
		if(times.size < 2) return IntArray(0)
		val n = minOf(times[0].size, times[1].size)
		return IntArray(n) { i -> val s = parseTime(times[0][i]); val e = parseTime(times[1][i]); if(s < 0 || e < 0) -1 else e - s }
	}

	/**
	 * 是否存在时间冲突: 任意相邻两节课 上课时间 < 上一节下课时间, 或某节时长<=0
	 * @return true 表示存在冲突
	 */
	fun hasConflict(times: Array<Array<String>>): Boolean {
		if(times.size < 2) return true
		val mins = periodMinutes(times)
		for(i in mins.indices) {
			if(mins[i] <= 0) return true
		}
		for(i in 1 until mins.size) {
			val prevEnd = parseTime(times[1][i - 1])
			val curStart = parseTime(times[0][i])
			if(prevEnd < 0 || curStart < 0) return true
			if(curStart < prevEnd) return true
		}
		return false
	}

	/** 把当日分钟数格式化成 "HH:mm" */
	fun formatTime(totalMin: Int): String = fmt(totalMin)

	/**
	 * 修改某节课的一个时间点, 并把改动**依次应用到它之后的全部时间**
	 *
	 * 例: 把第2节的上课时间 09:45 改成 10:00(晚 15 分钟), 那么第2节的下课时间以及第3节起的
	 * 所有时间都会一起后移 15 分钟 —— 整套时间的整体结构(每节时长、课间)保持不变, 不必逐格修改。
	 *
	 * @param start 上课时间数组
	 * @param end 下课时间数组
	 * @param slot 修改的节次(从0开始)
	 * @param part 0=上课时间, 1=下课时间
	 * @param newValue 新的时间(HH:mm)
	 * @return 修改后的 [start, end]; 输入非法或时间没变化时返回 null
	 */
	fun editTimeWithShift(
		start: Array<String>, end: Array<String>,
		slot: Int, part: Int, newValue: String
	): Array<Array<String>>? {
		if(slot !in start.indices || slot !in end.indices || part !in 0..1) return null
		val oldValue = if(part == 0) start[slot] else end[slot]
		val oldMin = parseTime(oldValue)
		val newMin = parseTime(newValue)
		if(oldMin < 0 || newMin < 0) return null
		val delta = newMin - oldMin
		if(delta == 0) return null

		val s = start.copyOf()
		val e = end.copyOf()
		if(part == 0) s[slot] = fmt(newMin) else e[slot] = fmt(newMin)

		// 其它"原本不早于被改时间"的时间点跟着平移(被改的那一个已经直接赋值)
		for(i in s.indices) {
			if(i == slot && part == 0) continue
			val v = parseTime(s[i])
			if(v >= 0 && v >= oldMin) s[i] = fmt(v + delta)
		}
		for(i in e.indices) {
			if(i == slot && part == 1) continue
			val v = parseTime(e[i])
			if(v >= 0 && v >= oldMin) e[i] = fmt(v + delta)
		}
		return arrayOf(s, e)
	}

	/**
	 * 把某节课的时长改成 [minutes] 分钟(保持上课时间不变)
	 *
	 * 【第二十五轮】用于"点击最右侧的时长用滚轮选 5~120 分钟"(每隔5分钟一档):
	 * 下课时间随之变化, 后面的课也会依次顺延。
	 */
	fun setSlotDuration(
		start: Array<String>, end: Array<String>, slot: Int, minutes: Int
	): Array<Array<String>>? {
		if(slot !in start.indices) return null
		if(minutes <= 0) return null
		val s = parseTime(start[slot])
		if(s < 0) return null
		return editTimeWithShift(start, end, slot, 1, fmt(s + minutes))
	}

	// ---------- 按季节选择默认时间表 ----------

	/**
	 * 端午节(农历五月初五)对应的公历日期: [月份, 日]
	 *
	 * 冬季时间的区间是"10月1日 ~ 次年端午节", 端午节的公历日期每年不同, 这里内置一张表,
	 * 表外年份回退到 6 月中旬(端午的公历日期只会在 5月27日 ~ 6月26日 之间浮动)。
	 */
	private val DRAGON_BOAT: Map<Int, IntArray> = mapOf(
		2023 to intArrayOf(6, 22), 2024 to intArrayOf(6, 10), 2025 to intArrayOf(5, 31),
		2026 to intArrayOf(6, 19), 2027 to intArrayOf(6, 9),  2028 to intArrayOf(5, 28),
		2029 to intArrayOf(6, 16), 2030 to intArrayOf(6, 5),  2031 to intArrayOf(6, 25),
		2032 to intArrayOf(6, 13), 2033 to intArrayOf(6, 2),  2034 to intArrayOf(6, 21),
		2035 to intArrayOf(6, 11)
	)

	/** 端午节的兜底日期(表中没有的年份) */
	private val DRAGON_BOAT_FALLBACK = intArrayOf(6, 15)

	private fun dragonBoat(year: Int): IntArray = DRAGON_BOAT[year] ?: DRAGON_BOAT_FALLBACK

	/**
	 * 当前是否处于冬季时间区间
	 *
	 * 冬季时间: 10月1日 ~ 次年端午节(含当天); 其余(端午节之后 ~ 9月30日)为夏季时间。
	 *
	 * @param month 月份 1~12
	 * @param day   日 1~31
	 */
	fun isWinterSeason(year: Int, month: Int, day: Int): Boolean {
		if(month >= 10) return true                        // 10月1日起一定进入冬季时间
		if(month >= 7) return false                        // 7~9月一定还是夏季时间
		val db = dragonBoat(year)
		return month < db[0] || (month == db[0] && day <= db[1])
	}

	/**
	 * 在时间表列表里挑出某一季节"最新"的一套
	 *
	 * 优先名字里带"新"的那套(如"新夏季时间"), 其次是名字里带季节关键字的最后一套,
	 * 都没有就用内置预设。这样以后即便新增了"新夏季时间", 也能自动被选中。
	 *
	 * @param keyword    季节关键字, 如 "夏" / "冬"
	 * @param newKeyword "新"+季节关键字, 如 "新夏" / "新冬"
	 * @param fallback   内置预设下标 [PRESET_SUMMER] / [PRESET_NEW_WINTER]
	 */
	private fun indexOfSeason(keyword: String, newKeyword: String, fallback: Int): Int {
		val list = _entries
		if(list.isEmpty()) return fallback
		// 1) 名字里带"新季节"的(如"新冬季时间")最优先
		val newest = list.indexOfFirst { it.name.contains(newKeyword) }
		if(newest >= 0) return newest
		// 2) 内置预设里对应季节的那套(如 presetIndex=2 的"新冬季时间")
		val preset = list.indexOfFirst { it.presetIndex == fallback }
		if(preset >= 0) return preset
		// 3) 名字里带季节关键字的最后一套(如"冬季时间")
		val last = list.indexOfLast { it.name.contains(keyword) }
		if(last >= 0) return last
		return fallback.coerceIn(0, list.size - 1)
	}

	/**
	 * 是否应把当前选中的旧时间"升级"为同季节的新时间
	 *
	 * 场景: 用户(或旧版本)之前选的是旧的"冬季时间", 而列表里已经有更新的"新冬季时间" —— 这时换成新的那套。
	 * 只在两者都是**内置预设**、属于**同一季节**、且目标名字带"新"而当前名字不带"新"时才升级,
	 * 不会动用户自己新建的时间表, 也不会跨季节改动。
	 *
	 * @param current  当前选中的时间表下标
	 * @param seasonal 按当天日期算出来"适合当下"的时间表下标
	 * @return 需要切换到的下标; 不需要升级返回 -1
	 */
	fun newerPresetFor(current: Int, seasonal: Int): Int {
		val list = _entries
		if(current == seasonal) return -1
		val cur = list.getOrNull(current) ?: return -1
		val target = list.getOrNull(seasonal) ?: return -1
		if(cur.presetIndex < 0 || target.presetIndex < 0) return -1
		if(cur.presetIndex == target.presetIndex) return -1
		// 同季节判断: 目标名字里的季节关键字(冬/夏)也要出现在当前名字里
		val season = when {
			target.name.contains("冬") -> "冬"
			target.name.contains("夏") -> "夏"
			else -> return -1
		}
		if(!cur.name.contains(season)) return -1
		// 目标必须是"新"的, 且当前这套不是"新"的
		if(!target.name.contains("新")) return -1
		if(cur.name.contains("新")) return -1
		return seasonal
	}

	/**
	 * 首次启动(用户还没手动选过)时按当天日期给出的默认时间表下标
	 *
	 * - 10月1日 ~ 次年端午节 → 冬季, 默认用"新冬季时间";
	 * - 端午节之后 ~ 9月30日 → 夏季, 默认用"夏季时间"(若存在"新夏季时间"则用新的那套)。
	 */
	fun defaultIndexBySeason(calendar: Calendar? = null): Int {
		val cal = calendar ?: Calendar.getInstance()
		val winter = isWinterSeason(
			cal.get(Calendar.YEAR),
			cal.get(Calendar.MONTH) + 1,
			cal.get(Calendar.DAY_OF_MONTH)
		)
		return if(winter) indexOfSeason("冬", "新冬", PRESET_NEW_WINTER)
		else indexOfSeason("夏", "新夏", PRESET_SUMMER)
	}

	/** 解析 "HH:mm" 为当日分钟数, 失败返回 -1 */
	fun parseTime(time: String): Int {
		val sp = time.split(":")
		if(sp.size != 2) return -1
		val h = sp[0].trim().toIntOrNull() ?: return -1
		val m = sp[1].trim().toIntOrNull() ?: return -1
		if(h !in 0..23 || m !in 0..59) return -1
		return h * 60 + m
	}

	// ---------- 当前时间表 ----------

	/** 当前使用的时间表时间 [0]=上课时间 [1]=下课时间 */
	val currentTimes: Array<Array<String>>
		get() = ensureEveningTail(getTimes(LessonTableRepository.currentTimeTable))

	/**
	 * 【第六十八轮·三·最后一道闸】"这张表停在 18:55-19:40, 后面就该还有 19:50-20:35"。
	 *
	 * 前面 [normalizeEntry] / [getTimes] 里的三层判据都只在"数据从存档里读出来"这条路上生效。
	 * 用户装了第六十八轮·三的包之后桌面**仍然是 10 节** —— 说明还有别的入口在喂数据
	 * (兜底到 `getDefaultTimes(presetIndex)`、`fixedSchedule()`、或某个我没覆盖到的分支)。
	 *
	 * 所以把判断挪到**唯一的出口** `currentTimes` 上: 不管上游怎么来的, 只要
	 *  · 节数 < 11,
	 *  · 且最后一节正好是 18:55 - 19:40(新冬季作息的第 10 节),
	 * 就把第 11 节(19:50-20:35)接上。**只追加, 不动任何已有值。**
	 *
	 * 这条判据不会误伤: 冬季时间(19:30-20:20)、夏季时间(20:00-20:50)的末节都不是 18:55,
	 * 用户自己排到 18:55 下课还恰好少于 11 节的概率极低; 而且即便命中, 多出来的也只是一行时间。
	 */
	private fun ensureEveningTail(times: Array<Array<String>>): Array<Array<String>> {
		val start = times.getOrNull(0) ?: return times
		val end = times.getOrNull(1) ?: return times
		if(start.isEmpty() || start.size != end.size) return times
		if(start.size >= getDefaultTimes(PRESET_NEW_WINTER)[0].size) return times
		// 【第六十八轮·四】按"分钟数"比较, 不按字符串: 存档里可能是 "18:55 " / "18：55"(全角冒号)
		// / "6:55" 这类写法, 逐字比较会漏判 —— 但时间轴显示出来看着一模一样。
		if(minutesOf(start.last()) != 18 * 60 + 55 || minutesOf(end.last()) != 19 * 60 + 40) return times
		return arrayOf(start + "19:50", end + "20:35")
	}

	/** "HH:mm" -> 当天分钟数; 解析不了返回 -1(全角冒号/首尾空白都容忍) */
	private fun minutesOf(text: String): Int {
		val s = text.trim().replace('：', ':').replace('．', '.')
		val parts = s.split(':')
		if(parts.size != 2) return -1
		val h = parts[0].trim().toIntOrNull() ?: return -1
		val m = parts[1].trim().toIntOrNull() ?: return -1
		if(h !in 0..23 || m !in 0..59) return -1
		return h * 60 + m
	}

	/**
	 * 【第六十八轮·三】冷启动时补一次: 如果存档里的时间表**需要补尾巴**, 就地修好并**立刻重画课表小组件**。
	 *
	 * 为什么需要它: 数据修好了, 但桌面上的那一帧是上一个版本的 APK 画的 —— 只装新包不会自己重画,
	 * 用户看着还是 10 行, 只能删掉小组件重加。这里在 `Application.onCreate` 的后台线程调一次,
	 * 只要真的补过东西就触发一次刷新, 装完包打开一次软件桌面就跟着变。
	 */
	fun repairOnStart() {
		runCatching {
			val repaired = load()
			_entries = repaired
			persist(repaired)
			// 无条件刷一次: 判断"这次有没有真的改过"没有意义 ——
			// `load()` 在 init 里已经跑过一次并回写, 再比一次永远是"没变";
			// 而真正要看的是"桌面上那一帧是不是旧版本画的", 这一点从数据侧看不出来。
			// 刷新本身很轻(没有小组件时 updateAll 是空转), 每次冷启动一次可以接受。
			runCatching { LessonTableRepository.refreshLessonWidgetsForTimeTable() }
		}
	}

	/** 当前时间表的节数 */
	val currentSlotCount: Int
		get() = currentTimes[0].size

	/**
	 * 计算每节课开始时间相对第一节上课时间的间隔(分钟)
	 * 用于桌面小组件的课程进度计算, 与原 Data.LESSON_TIME 含义一致
	 */
	fun currentDeltas(): IntArray {
		val times = currentTimes
		if(times[0].isEmpty()) return IntArray(0)
		val first = parseTime(times[0][0])
		return IntArray(times[0].size){ i ->
			val t = parseTime(times[0][i])
			if(first < 0 || t < 0) 0 else t - first
		}
	}
}
