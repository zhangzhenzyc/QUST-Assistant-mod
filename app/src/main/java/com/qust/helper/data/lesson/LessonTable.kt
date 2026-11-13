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

import com.qust.helper.model.Logger
import com.qust.helper.ui.theme.BACKGROUND_COLORS
import com.qust.helper.utils.DateUtils
import kotlinx.serialization.Serializable
import org.json.JSONObject

import java.text.ParseException
import java.util.Calendar
import java.util.Date

/**
 * 课表
 * @param startDay 开学时间
 * @param totalWeek 总周数
 */
@Serializable
data class LessonTable(
	@Serializable(with = DateUtils.DateSerializer::class)
	var startDay: Date = Date(),
	var totalWeek: Int = 1,
	val lessons: Array<Array<LessonGroup?>> = Array(7) {
		arrayOfNulls(TimeTables.MAX_SLOT_COUNT)
	},
	/**
	 * 单日调休: 日期("yyyy-MM-dd") -> 调整动作
	 * 0: 放假, 当日清空课程
	 * 1-7: 补星期几的课(1=周一 ... 7=周日)
	 */
	var overrides: MutableMap<String, Int> = HashMap(),
	/**
	 * 单日手动编辑的课程覆盖: 日期("yyyy-MM-dd") -> 该日每节的课程组(长度 MAX_SLOT_COUNT, 元素可为null)
	 * 优先级高于 overrides(调休); 仅作用于该具体日期, 不影响其它日期与每周课表模板
	 */
	var dayOverrides: MutableMap<String, Array<LessonGroup?>> = HashMap(),
	/**
	 * 「教务通知调课」直接写过的日期("yyyy-MM-dd")
	 *
	 * 这些日期上的内容是**通知带来的结果**(停课/加课/改地点), 与用户手动删课不是一回事:
	 * 保存课表时"删除不算修改, 一律补回"的规则必须跳过它们, 否则教务通知停掉的课
	 * 会在下一次保存时被原始课表补回来 —— 也就是"教务通知调课没生效"。
	 */
	var easDates: MutableSet<String> = HashSet(),
	/**
	 * 「教务通知调课」明确**停掉**的节次: 日期("yyyy-MM-dd") -> 节次下标(0 起)
	 *
	 * 保存课表的规则是"删除不算修改, 一律按最新原始课表补回"。教务通知的"停课"在数据上
	 * 也是"这一节没有课", 如果按整日或整节补回, 停课就白做了; 反过来, 如果整日都跳过补回,
	 * 这一天里**因覆盖语义留下的空洞(空白课程)就再也补不回来**。
	 * 因此标记精确到"日期 + 节次", 只跳过通知明确停掉的那几节。
	 */
	var easSuspended: MutableMap<String, MutableSet<Int>> = HashMap(),
){

	constructor(_startDay: String, _totalWeek: Int) : this(totalWeek = _totalWeek) {
		try{
			setStartDay(_startDay)
		}catch(e: ParseException){
			startDay = Date()
		}
	}

	fun setStartDay(startDay: String) {
		this.startDay = DateUtils.YMD.parse(startDay)!!
	}

	fun getLessonGroupNotNull(week: Int, count: Int): LessonGroup {
		if(lessons[week][count] == null) {
			lessons[week][count] = LessonGroup(week + 1, count + 1)
		}
		return lessons[week][count]!!
	}

	/**
	 * 【第九十八轮】整张课表实际占用的最大**结束**节次(1 基)。
	 *
	 * 遍历每周模板(lessons, 内层下标 = 0 基起始槽位)与"单日手动覆盖"(dayOverrides, 同结构),
	 * 取 "起始槽位下标 + 连堂长度" 的最大值; 周位掩码为 0(已删除态)的课程不计。
	 * 空课表返回 0 —— 用于"切换时间表前先检查这套表的节数够不够画下全部课程"。
	 */
	fun maxUsedEndSlot(): Int {
		var max = 0
		for(daily in lessons) {
			for(i in daily.indices) {
				val group = daily[i] ?: continue
				for(l in group.lessons) {
					if(l.week == 0L) continue
					max = maxOf(max, i + l.len.coerceAtLeast(1))
				}
			}
		}
		for(column in dayOverrides.values) {
			for(i in column.indices) {
				val group = column[i] ?: continue
				for(l in group.lessons) {
					if(l.week == 0L) continue
					max = maxOf(max, i + l.len.coerceAtLeast(1))
				}
			}
		}
		return max
	}

	/** 给定时间表节数能否容纳整张课表的全部课程(节数不够 = 有课显示不全) */
	fun fitsTimeTable(slotCount: Int): Boolean = slotCount >= maxUsedEndSlot()

	/**
	 * 兼容旧数据: 把每行的节数补齐到 MAX_SLOT_COUNT
	 */
	fun normalize() {
		for(i in lessons.indices) {
			if(lessons[i].size < TimeTables.MAX_SLOT_COUNT) {
				lessons[i] = lessons[i].copyOf(TimeTables.MAX_SLOT_COUNT)
			}
		}
		// 【内容缺失】"按日期修改"的覆盖数据同样要补齐: 旧数据/旧版本可能只有 10 节,
		// 渲染与拖动都按 MAX_SLOT_COUNT 取, 少的那几节会直接"消失"。
		for((key, column) in dayOverrides) {
			if(column.size >= TimeTables.MAX_SLOT_COUNT) continue
			dayOverrides[key] = column.copyOf(TimeTables.MAX_SLOT_COUNT)
		}
		// 【本期】教务通知调课不再登记进"调休安排"(旧版本用 overrides == 9 标记调课日期)。
		// 9 既不是"放假"也不是"补星期X", 留着只会在调休安排里显示成一条语义不明的条目,
		// 因此直接清掉标记; dayOverrides 里的课程内容保留, 课表显示不受影响。
		overrides.keys.removeAll { overrides[it] == 9 }
		// 教务通知调课的日期标记只对"仍然存在按日期副本"的日期有意义:
		// 副本被删掉(删除该天/删除调休安排)后标记也要一起清掉, 避免留下死数据。
		easDates.retainAll(dayOverrides.keys)
		easSuspended.keys.retainAll(dayOverrides.keys)
	}

	/**
	 * 获取某日期的调休安排, 无调休返回null
	 * @param dateKey "yyyy-MM-dd"
	 */
	fun getOverride(dateKey: String): Int? = overrides[dateKey]

	/**
	 * 计算某一周"周一"对应的日期(该周起始日)
	 * 始终按周一为一周起点对齐, 不受地区/跨年周数边界影响, 作为调休日期计算的唯一真源
	 * @param week 第几周, 从0开始
	 */
	fun weekStartDate(week: Int): Date {
		val c = Calendar.getInstance()
		c.firstDayOfWeek = Calendar.MONDAY
		c.time = startDay
		val dow = c[Calendar.DAY_OF_WEEK]   // SUNDAY=1, MONDAY=2, ... SATURDAY=7
		val backToMonday = when(dow) {
			Calendar.MONDAY -> 0
			Calendar.TUESDAY -> 1
			Calendar.WEDNESDAY -> 2
			Calendar.THURSDAY -> 3
			Calendar.FRIDAY -> 4
			Calendar.SATURDAY -> 5
			Calendar.SUNDAY -> 6
			else -> 0
		}
		c.add(Calendar.DATE, -backToMonday)
		c.add(Calendar.DATE, week * 7)
		return c.time
	}

	/**
	 * 计算某周某天对应的日期key
	 * @param week 第几周, 从0开始
	 * @param dayOfWeek 星期几, 0=周一
	 */
	fun dateKeyOf(week: Int, dayOfWeek: Int): String {
		val c = Calendar.getInstance()
		c.firstDayOfWeek = Calendar.MONDAY
		c.time = weekStartDate(week)
		c.add(Calendar.DATE, dayOfWeek)
		return DateUtils.YMD.format(c.time)
	}

	/**
	 * 获取某周某天"生效"的课程列(不修改数据)
	 *
	 * 优先级: 手动按日期覆盖 dayOverrides > 补课调休 overrides(1..7) > 每周课表模板
	 * (补课时 [setOverride] 会先清掉该日期遗留的手动覆盖, 因此补课仍然一定会生效)
	 *
	 * 注意 "放假"(overrides == 0) **不再清空课程**:
	 * 放假只是一个日期标记(见 [isHoliday]), 该日期原本的课程(手动修改或每周模板)完整保留,
	 * 因此放假日期上的课程仍可被查看、编辑, 也可被"复制整天课表"复制到其它日期。
	 *
	 * @param week 第几周, 从0开始
	 * @param dayOfWeek 星期几, 0=周一
	 */
	fun effectiveDay(week: Int, dayOfWeek: Int): Array<LessonGroup?> {
		if(dayOfWeek !in lessons.indices) return arrayOfNulls(TimeTables.MAX_SLOT_COUNT)
		val key = dateKeyOf(week, dayOfWeek)
		// 手动按日期修改: 只作用于该具体日期, 优先级最高
		dayOverrides[key]?.let { return it }
		// 补课: 用被补那一天(星期)的课表
		val action = overrides[key]
		if(action != null && action in 1..7) {
			val source = action - 1
			if(source in lessons.indices) return lessons[source]
		}
		// 放假(0)与无调休(null)一致: 使用该日期自己的课程, 不删除
		return lessons[dayOfWeek]
	}

	/**
	 * 该日期是否被标记为"放假"
	 * 放假只做标记, 不删除课程数据(课程仍可通过 [effectiveDay] 读取与复制)
	 */
	fun isHoliday(dateKey: String): Boolean = overrides[dateKey] == 0

	/**
	 * 【问题3-④】把"具体日期"换算成 (第几周(从0开始), 星期几(0=周一))
	 *
	 * 调休的一切操作都以具体日期为基准, 因此需要这个反向换算。
	 * 直接与 [dateKeyOf] 对照, 保证与正/反向逻辑完全一致; 不在学期范围内返回 null。
	 */
	fun weekDayOfDate(dateKey: String): Pair<Int, Int>? {
		if(dateKey.isEmpty()) return null
		for(w in 0 until totalWeek.coerceAtLeast(1)) {
			for(d in 0..6) {
				if(dateKeyOf(w, d) == dateKey) return w to d
			}
		}
		return null
	}

	/**
	 * 统计全表"有课程内容的时段"数量(不限周次)。
	 * 抽取自登录/查询流程中重复出现的空课表判断, 纯统计函数, 不改变任何数据。
	 */
	fun activeSlotCount(): Int = lessons.sumOf { row -> row.count { it != null } }

	/**
	 * 某周某天"真的要上"的课程列
	 *
	 * 在 [effectiveDay] 基础上再做一次过滤: 不在本周上课周次内的课程属于"暂时不上"的课
	 * (在学期课表里显示为灰色), 不再出现在当日课表里。
	 *
	 * @param week 第几周, 从0开始
	 * @param dayOfWeek 星期几, 0=周一
	 */
	fun activeDay(week: Int, dayOfWeek: Int): Array<LessonGroup?> {
		val currentWeek = week + 1
		if(currentWeek < 1 || currentWeek > totalWeek) return arrayOfNulls(TimeTables.MAX_SLOT_COUNT)
		val column = effectiveDay(week, dayOfWeek)
		return Array(column.size) { i ->
			val group = column[i]
			if(group != null && group.hasLessonInWeek(currentWeek, totalWeek)) group else null
		}
	}

	/**
	 * 获取某周某天"可编辑"的课程列(必要时创建该日期的覆盖副本)
	 * 副本从当前生效列深拷贝而来, 后续编辑只影响该具体日期
	 */
	fun editDayOverride(week: Int, dayOfWeek: Int): Array<LessonGroup?> {
		val key = dateKeyOf(week, dayOfWeek)
		dayOverrides[key]?.let { return it }
		val src = effectiveDay(week, dayOfWeek)
		val copy = Array(TimeTables.MAX_SLOT_COUNT) { j ->
			src.getOrNull(j)?.let { g ->
				LessonGroup(dayOfWeek + 1, j + 1).also { n -> n.lessons = g.lessons.map { it.copy() }.toTypedArray() }
			}
		}
		dayOverrides[key] = copy
		return copy
	}

	/**
	 * 按日期复制整天课程(深拷贝): 将源日期实际生效的课程覆盖到目标日期
	 * 仅修改目标日期, 不影响每周课表模板与其它日期
	 *
	 * 【需求6】"复制整天课表"不复制上课周数: 复制出的课程其上课周数一律设为"目标日期所在周",
	 * 即该天在第几周、上课周数就是第几周(与单课复制/粘贴、从课表选择的行为一致);
	 * 源课程仍按目标周次过滤(只保留目标当周实际会上的课), 避免把其它周的课也复制进来。
	 *
	 * @param merge 【不覆盖，只加入】为 true 时: 目标日期原有课程一律保留, 仅把源日期
	 *   "当周实际上"的课程**追加**进对应节次(用于"课程存在覆盖情况"弹窗中选择「不覆盖，只加入」);
	 *   为 false(默认)时: 用源日期的课程整列替换目标日期(覆盖语义)。
	 */
	fun copyDayByDate(fromWeek: Int, fromDay: Int, toWeek: Int, toDay: Int, merge: Boolean = false) {
		if(fromDay !in lessons.indices || toDay !in lessons.indices) return
		val src = effectiveDay(fromWeek, fromDay)
		val mask = 1L shl toWeek.coerceIn(0, 63)
		// 【需求6/巩固需求2/需求4】复制整天课表不复制上课周数(复制出的课程只落在目标周);
		// 同时只复制"源周当周实际会上"的课程, 不复制其它周才上(在课表里显示为灰色)的课程,
		// 避免把灰色课程一并复制过来。
		val srcMask = 1L shl fromWeek.coerceIn(0, 63)
		if(merge) {
			// 【不覆盖，只加入】保留原课程; 只把源日期"当周实际上"的课程加入到目标日期**没有原课程**的节次:
			//   该节次已有"当周实际上"的原课程时一律跳过 —— 原课程位置不加入。
			// 例: 10月1日1-2节已物理, 调休"10月1日上10月2日的课"且10月2日1-2节是数学
			//     -> 结果只显示物理(数学不加入), 而不是两门课同时显示。
			val dst = editDayOverride(toWeek, toDay)
			for(j in src.indices) {
				val g = src.getOrNull(j) ?: continue
				val add = g.lessons
					.filter { it.week and srcMask != 0L }
					.map { it.copy().also { c -> c.week = mask } }
				if(add.isEmpty()) continue
				val existing = dst.getOrNull(j)
				// 原课程位置: 该节次已有"当周实际会上"的课程 -> 不加入
				val occupied = existing?.lessons?.any { it.week and mask != 0L } == true
				if(occupied) continue
				if(existing == null) {
					dst[j] = LessonGroup(toDay + 1, j + 1).also { n -> n.lessons = add.toTypedArray() }
				}else{
					// 该节次只有"其它周才上"的课程(本周不显示): 直接追加, 不影响本周显示
					val more = add.filter { nl ->
						existing.lessons.none { it.name == nl.name && (it.teacher.isEmpty() || nl.teacher.isEmpty() || it.teacher == nl.teacher) }
					}
					more.forEach { existing.addLesson(it) }
				}
			}
			return
		}
		val copy = Array(TimeTables.MAX_SLOT_COUNT) { j ->
			src.getOrNull(j)?.let { g ->
				val filtered = g.lessons
					.filter { it.week and srcMask != 0L }
					.map { it.copy().also { c -> c.week = mask } }
				if(filtered.isEmpty()) null
				else LessonGroup(toDay + 1, j + 1).also { n -> n.lessons = filtered.toTypedArray() }
			}
		}
		dayOverrides[dateKeyOf(toWeek, toDay)] = copy
	}

	/**
	 * 恢复某周某天为原始课表(删除该日期的手动覆盖)
	 */
	fun restoreDay(week: Int, dayOfWeek: Int) {
		dayOverrides.remove(dateKeyOf(week, dayOfWeek))
	}

	/**
	 * 一节课程在某日实际占用的时段
	 * @param startSlot 起始节次(从0开始)
	 * @param lesson 课程
	 * @param len 该课程占用的节数(>=1)
	 */
	data class Occupant(val startSlot: Int, val lesson: Lesson, val len: Int)

	/**
	 * 找出覆盖 (week, dayOfWeek, slot) 的全部课程, 按起始节次升序
	 *
	 * 判定依据是课程的"实际占用节数", 因此既包含起始于该节的课程,
	 * 也包含上方长课程(len>1)跨越覆盖到该节的课程, 以及同一格内重合的多节课程。
	 * 返回数量 > 1 即表示该时段存在课程重合。
	 *
	 * @param week 第几周, 从0开始
	 * @param dayOfWeek 星期几, 0=周一
	 * @param slot 节次, 从0开始
	 */
	fun occupantsAt(week: Int, dayOfWeek: Int, slot: Int): List<Occupant> {
		if(dayOfWeek !in lessons.indices || slot < 0 || slot >= TimeTables.MAX_SLOT_COUNT) return emptyList()
		val col = effectiveDay(week, dayOfWeek)
		val mask = weekMask(week)
		val result = ArrayList<Occupant>(2)
		for(k in 0..minOf(slot, col.size - 1)){
			val group = col.getOrNull(k) ?: continue
			for(lesson in group.lessons){
				if(lesson.week and mask <= 0L) continue
				val len = lesson.len.coerceAtLeast(1)
				if(slot - k < len) result.add(Occupant(k, lesson, len))
			}
		}
		return result
	}

	/**
	 * 目标区间 [slot, slot+len-1] 内是否已有课程(可忽略某节课自身)
	 * 用于判断"落下/保存后是否与其它课程时间重合"
	 *
	 * @param ignore 需要忽略的课程(通常是正在编辑/移动的那节课本身)
	 */
	fun spanOccupied(
		week: Int, dayOfWeek: Int, slot: Int, len: Int,
		ignore: Lesson? = null,
		/**
		 * 正在被拖动的课程所在位置。
		 * 拖动过程中渲染层持有的 Lesson 实例可能与课表里的不是同一个对象(调休/按日期覆盖会重建副本),
		 * 只按实例判断会出现"把被拖动的课程自己当成障碍"的情况 —— 也就是拖到两个课程中间时
		 * 反而提示替换、被拖动的课程再也放不下去。带上源位置可以彻底规避。
		 */
		srcWeek: Int = -1, srcDay: Int = -1, srcSlot: Int = -1
	): Lesson? {
		val end = slot + len.coerceAtLeast(1) - 1
		for(s in slot..end){
			for(occupant in occupantsAt(week, dayOfWeek, s)){
				if(ignore != null && occupant.lesson === ignore) continue
				if(srcSlot >= 0 && week == srcWeek && dayOfWeek == srcDay && occupant.startSlot == srcSlot) continue
				return occupant.lesson
			}
		}
		return null
	}

	/**
	 * 在指定日期上寻找**最近的可用空位**(用于把课程拖动到两个课程中间时自动吸附到空隙)
	 *
	 * 从期望位置开始向两侧逐格试探, 找到一个能完整容纳 [len] 节且不与其它课程冲突的起始节次。
	 * 找不到时返回 null(调用方应改为询问是否替换)。
	 */
	fun nearestFreeSlot(
		week: Int, dayOfWeek: Int, wanted: Int, len: Int,
		ignore: Lesson? = null,
		srcWeek: Int = -1, srcDay: Int = -1, srcSlot: Int = -1,
		slotCount: Int = TimeTables.currentSlotCount
	): Int? {
		val length = len.coerceAtLeast(1)
		val maxStart = (slotCount - length).coerceAtLeast(0)
		val start = wanted.coerceIn(0, maxStart)
		for(offset in 0..maxStart){
			for(candidate in intArrayOf(start - offset, start + offset)){
				if(candidate < 0 || candidate > maxStart) continue
				if(spanOccupied(week, dayOfWeek, candidate, length, ignore, srcWeek, srcDay, srcSlot) == null) return candidate
			}
		}
		return null
	}

	/**
	 * 找出"所选周次里上课时间已被其它课程占用"的周次
	 *
	 * 编辑课程时调整"上课周数"会用到: 例如把课程改成只上单周后, 第3周该时段其实已经排了别的课,
	 * 此时应提示用户, 否则课表上会出现两门课重叠。
	 *
	 * @param dayOfWeek 星期(0-6)
	 * @param slot 起始节次(0开始)
	 * @param len 占用节数
	 * @param weekMask 本次选择的上课周掩码(第1周 = bit0)
	 * @param ignore 正在编辑的课程自身(不算冲突)
	 * @param totalWeek 学期总周数
	 * @return 周次(从1开始) -> 占用该时段的课程名, 按周次升序
	 */
	fun conflictingWeeks(
		dayOfWeek: Int, slot: Int, len: Int, weekMask: Long,
		ignore: Lesson? = null, totalWeek: Int = this.totalWeek
	): List<Pair<Int, String>> {
		if(dayOfWeek !in lessons.indices || weekMask == 0L) return emptyList()
		val length = len.coerceAtLeast(1)
		val others = ArrayList<Lesson>()
		for(s in slot until (slot + length)) {
			lessons[dayOfWeek].getOrNull(s)?.lessons?.forEach { l ->
				// 同一门课可能因"按日期覆盖"存在不同副本, 不能只比实例
				if(ignore != null && l.isSameLessonAs(ignore)) return@forEach
				others.add(l)
			}
		}
		if(others.isEmpty()) return emptyList()
		val result = ArrayList<Pair<Int, String>>()
		for(w in 0 until totalWeek.coerceIn(1, 63)) {
			val bit = 1L shl w
			if(weekMask and bit == 0L) continue
			val hit = others.firstOrNull { it.week and bit != 0L } ?: continue
			result.add((w + 1) to hit.name)
		}
		return result
	}

	/**
	 * 课程最常见的时间跨度(节数)
	 *
	 * 点击空白课表时用这个值作为默认选择框大小: 大多数课程都是"两节连上"时,
	 * 点一下空白处默认就是 2 格, 不必每次都从 1 格往下拉。
	 */
	fun majorityLessonLength(): Int {
		val counter = HashMap<Int, Int>()
		for(day in lessons) for(group in day) {
			group?.lessons?.forEach { lesson ->
				val len = lesson.len.coerceAtLeast(1)
				counter[len] = (counter[len] ?: 0) + 1
			}
		}
		if(counter.isEmpty()) return DEFAULT_SELECT_LENGTH
		var best = DEFAULT_SELECT_LENGTH
		var bestCount = -1
		// 次数相同时取较小的跨度(更保守)
		for(len in counter.keys.sorted()) {
			val c = counter.getValue(len)
			if(c > bestCount){ best = len; bestCount = c }
		}
		return best.coerceIn(DEFAULT_SELECT_LENGTH, TimeTables.MAX_SLOT_COUNT)
	}

	/**
	 * 某日期(self dateKey)是否存在人为调整(手动按日期修改 或 调休)
	 * 用于判断该日期是否已被特殊处理, 避免被其它逻辑静默覆盖
	 */
	fun isDateAdjusted(dateKey: String): Boolean =
		dayOverrides.containsKey(dateKey) || overrides.containsKey(dateKey)

	/**
	 * 直接设置某日期的调休安排(覆盖写入)
	 *
	 * - **补课**(action 1..7): 调休属于"日期级"强规则, 若该日期此前存在手动按日期修改
	 *   (dayOverrides), 会一并清除, 否则补课会被手动覆盖顶掉而"看起来没有生效"。
	 * - **放假**(action 0): 只写入放假标记, **不清除**该日期的手动修改与课程数据,
	 *   放假日期上的课程仍然保留, 可被查看与"复制整天课表"复制。
	 *
	 * @return 是否清除了遗留的手动修改(仅补课时可能发生)
	 */
	fun setOverride(dateKey: String, action: Int): Boolean {
		val cleared = if(action == 0) false else dayOverrides.remove(dateKey) != null
		overrides[dateKey] = action
		return cleared
	}

	/**
	 * 【修改版】把一节课"覆盖"到某一天的某个节次上(粘贴时用户确认覆盖后使用)
	 *
	 * 与其它"覆盖式写入"(replaceExisting=true 的语义)同一套口径: 先清掉该课程要占用的
	 * [slot, slot+len-1] 区间里**本周会上的**其它课程(含从上方延伸下来压住落点的长课程),
	 * 再把课程放进落点。只修改该日期的按日期副本, 每周模板与其它日期完全不受影响。
	 *
	 * @param week 第几周, 从0开始
	 * @param dayOfWeek 星期几, 0=周一
	 * @return 该日期的按日期副本(已写入)
	 */
	fun replaceLessonAt(week: Int, dayOfWeek: Int, slot: Int, lesson: Lesson): Array<LessonGroup?> {
		val col = editDayOverride(week, dayOfWeek)
		val mask = weekMask(week)
		// 粘贴的课程只在本周上课(与"复制粘贴只粘目标周"保持一致)
		lesson.week = mask
		clearRangeOccupants(col, mask, slot, lesson.len.coerceAtLeast(1))
		val group = col.getOrNull(slot) ?: LessonGroup(dayOfWeek + 1, slot + 1).also { col[slot] = it }
		if(group.lessons.none { it.isSameLessonAs(lesson) }) group.addLesson(lesson)
		return col
	}

	/** 第几周(从0开始)对应的位掩码 */
	private fun weekMask(week: Int): Long = 1L shl week.coerceIn(0, 63)

	/**
	 * 清掉某一天(按日期覆盖副本)[slot, slot+len-1] 区间内**本周会上的**其它课程
	 *
	 * 用于"确认覆盖"的移动: 不只清落点那一格, 从上方延伸下来、压住落点的长课程同样要让位,
	 * 否则移动完成后画面上依旧是两门课叠在一起。
	 *
	 * @param col 目标日期的按日期覆盖副本(就地修改)
	 * @param mask 目标周次掩码
	 * @param skipSlot 需要跳过的节次(通常是"被拖动的课程"自身所在格), -1 表示不跳过
	 */
	private fun clearRangeOccupants(col: Array<LessonGroup?>, mask: Long, slot: Int, len: Int, skipSlot: Int = -1) {
		val end = slot + len.coerceAtLeast(1) - 1
		for(s in slot..end) {
			if(s != skipSlot) {
				val group = col.getOrNull(s) ?: continue
				val hit = group.lessons.filter { it.week and mask != 0L }
				for(lesson in hit) {
					lesson.week = lesson.week and mask.inv()
					if(lesson.week == 0L) group.removeLesson(lesson)
				}
				// 课程被清空后不要留下空分组: 空分组仍会"占位", 看起来像有课却什么都不显示
				if(group.lessons.isEmpty()) col[s] = null
			}
		}
		// 从上方延伸下来压住本区间的长课程: 它所在的那一组在区间之外
		for(s in 0 until slot) {
			if(s == skipSlot) continue
			val group = col.getOrNull(s) ?: continue
			val hit = group.lessons.filter { it.week and mask != 0L && s + it.len.coerceAtLeast(1) > slot }
			for(lesson in hit) {
				lesson.week = lesson.week and mask.inv()
				if(lesson.week == 0L) group.removeLesson(lesson)
			}
			if(group.lessons.isEmpty()) col[s] = null
		}
	}

	/**
	 * 删除某一个**具体日期**的课表(仅该日期, 不影响每周课表模板与其它日期)
	 *
	 * 做法是把该日期写入一份"全空"的按日期覆盖, 因此:
	 * - 该日期之后显示为无课;
	 * - 同一星期的其它周次不受影响;
	 * - 该日期原有的补课/放假标记一并清除;
	 * - 可通过 [restoreDay] 撤销(恢复为每周课表模板)。
	 */
	fun clearDayByDate(week: Int, dayOfWeek: Int) {
		if(dayOfWeek !in lessons.indices) return
		if(week !in 0 until 64) return
		val key = dateKeyOf(week, dayOfWeek)
		overrides.remove(key)
		// 用户主动"删除这一天"后, 这一天不再算"教务通知调课的结果"
		easDates.remove(key)
		easSuspended.remove(key)
		dayOverrides[key] = arrayOfNulls(TimeTables.MAX_SLOT_COUNT)
	}

	/**
	 * 该日期是否已被"删除当日课表"(按日期覆盖且全空)
	 */
	fun isDayCleared(dateKey: String): Boolean =
		dayOverrides[dateKey]?.all { it == null || it.lessons.isEmpty() } == true

	/**
	 * 删除整个学期的课表(每周课表模板 + 调休 + 按日期修改全部清空)
	 * 保留开学时间与总周数等设置
	 */
	fun clearTerm() {
		for(i in lessons.indices) {
			lessons[i] = arrayOfNulls(TimeTables.MAX_SLOT_COUNT)
		}
		overrides.clear()
		dayOverrides.clear()
		easDates.clear()
		easSuspended.clear()
	}

	/**
	 * 深拷贝(数组/Map 全部另复制一份), 用于删除前备份以便撤销
	 */
	fun deepCopy(): LessonTable {
		val copy = LessonTable(startDay = Date(startDay.time), totalWeek = totalWeek)
		for(i in lessons.indices) {
			copy.lessons[i] = Array(lessons[i].size) { j -> lessons[i][j]?.deepCopy() }
		}
		copy.overrides = HashMap(overrides)
		copy.dayOverrides = HashMap()
		for((key, column) in dayOverrides) {
			copy.dayOverrides[key] = Array(column.size) { j -> column[j]?.deepCopy() }
		}
		copy.easDates = HashSet(easDates)
		copy.easSuspended = HashMap()
		for((key, slots) in easSuspended) copy.easSuspended[key] = HashSet(slots)
		return copy
	}

	/**
	 * 用另一张课表的内容替换自己(**保持对象引用不变**)
	 *
	 * 界面/ViewModel 里到处持有 `LessonTableRepository.lessonTable` 这同一个对象,
	 * "重新算一张课表再写回"时必须写回原对象, 否则界面上看到的还是旧数据。
	 */
	fun replaceContentWith(other: LessonTable) {
		startDay = other.startDay
		totalWeek = other.totalWeek
		for(i in lessons.indices) {
			lessons[i] = if(i < other.lessons.size) other.lessons[i] else arrayOfNulls(TimeTables.MAX_SLOT_COUNT)
		}
		overrides = other.overrides
		dayOverrides = other.dayOverrides
		easDates = other.easDates
		easSuspended = other.easSuspended
	}

	/**
	 * 该课表是否还有任何课程(用于"删除学期课表"前的提示)
	 */
	fun hasAnyLesson(): Boolean {
		for(column in lessons) {
			for(group in column) {
				if(group != null && group.lessons.isNotEmpty()) return true
			}
		}
		for(column in dayOverrides.values) {
			for(group in column) {
				if(group != null && group.lessons.isNotEmpty()) return true
			}
		}
		return false
	}

	/**
	 * 从json中解析课表
	 */
	fun loadFromJson(json: JSONObject, excludeElective: Boolean = false): Boolean {
		return try {
			if(!json.has("kbList")) return false

			var index = 0
			val colors = ArrayList<String>()
			val array = json.getJSONArray("kbList")

			for(i in 0 until array.length()) {
				val js = array.getJSONObject(i)

				// 【问题2-班级课表】星期/节次的字段名与格式各部署不一致(有 xqj / xqjmc / jcs / jc / jcor),
				// 原来直接 getInt("xqj") + getString("jcs") 一旦取不到就整表解析失败。
				val week = dayOfWeek(js) ?: continue
				val (count, len) = slotRange(js) ?: continue
				if(week !in 1..7 || count !in 1..TimeTables.MAX_SLOT_COUNT) continue

				// 【班级课表】班级课表要的是"整个班级的统一安排", 不能带学生自己选的课。
				// 通识任选/选修课是人人都不同的自选课, 出现在班级课表里只会干扰, 直接跳过。
				if(excludeElective && isElectiveLesson(js)) continue

				val lesson = Lesson.loadFromJson(js)
				lesson.len = len

				val j = colors.indexOf(lesson.name)
				if(j != -1){
					lesson.color = j % (BACKGROUND_COLORS.size - 1) + 1
				}else{
					if(++index == BACKGROUND_COLORS.size) index = 1
					lesson.color = index
					colors.add(lesson.name)
				}

				if(lessons[week - 1][count - 1] == null) {
					lessons[week - 1][count - 1] = LessonGroup(week, count)
				}
				lessons[week - 1][count - 1]!!.addLesson(lesson)
			}
			true
		} catch(e: Exception) {
			Logger.e(json.toString(), e)
			false
		}
	}

	/**
	 * 【班级课表】判断一条课程记录是否属于"通识任选 / 选修"类课程
	 *
	 * 正方教务的课程 JSON 里, 课程性质/类别字段各部署叫法不同, 这里按常见字段名逐个尝试:
	 * kcxzmc(课程性质名称) / kclbmc(课程类别名称) / kcgsmc(课程归属名称) / kcxz / kclb。
	 * 只要命中"任选/选修/公选"就视为学生自选课(班级课表里不需要)。
	 */
	fun isElectiveLesson(js: JSONObject): Boolean {
		for(key in arrayOf("kcxzmc", "kclbmc", "kcgsmc", "kcxz", "kclb", "kcxz_id", "kclb_id")) {
			if(!js.has(key) || js.isNull(key)) continue
			if(isElectiveCategory(js.optString(key, ""))) return true
		}
		return false
	}

	/** 课程性质/类别文本是否属于"通识任选/选修/公选"(纯函数, 便于单元测试) */
	fun isElectiveCategory(value: String): Boolean =
		value.contains("任选") || value.contains("选修") || value.contains("公选")

	/** 【问题2】星期几: 兼容 xqj(数字/字符串) 与 xqjmc(中文"星期一") 两种写法 */
	private fun dayOfWeek(js: JSONObject): Int? {
		for(key in arrayOf("xqj", "xqjmc", "xq")) {
			if(!js.has(key) || js.isNull(key)) continue
			val raw = js.optString(key, "").trim()
			if(raw.isEmpty()) continue
			raw.toIntOrNull()?.let { if(it in 1..7) return it }
			// "星期一"/"周一"/"礼拜一"
			val idx = "一二三四五六日天".indexOf(raw.last())
			if(idx >= 0) return if(raw.last() == '天') 7 else idx + 1
		}
		return null
	}

	/**
	 * 【问题2】起始节次与节数
	 *
	 * 兼容 "1-2"(jcs) / "1"(jcs) / "0102"(jcor) / jc(单个节次) 几种写法。
	 * @return (起始节次, 节数) 均从1开始计数; 无法解析返回 null
	 */
	private fun slotRange(js: JSONObject): Pair<Int, Int>? {
		val raw = arrayOf("jcs", "jc", "jcor", "jcs_id")
			.map { if(js.has(it) && !js.isNull(it)) js.optString(it, "").trim() else "" }
			.firstOrNull { it.isNotEmpty() } ?: return null

		// "0102" 这种拼接写法: 两位一组
		if(!raw.contains("-") && raw.length == 4 && raw.all { it.isDigit() }) {
			val start = raw.substring(0, 2).toIntOrNull() ?: return null
			val end = raw.substring(2, 4).toIntOrNull() ?: return null
			return if(start in 1..TimeTables.MAX_SLOT_COUNT && end >= start) start to (end - start + 1) else null
		}

		val nums = Regex("\\d+").findAll(raw).map { it.value.toIntOrNull() ?: 0 }.toList()
		if(nums.isEmpty()) return null
		val start = nums[0]
		val end = nums.getOrElse(1) { start }
		if(start !in 1..TimeTables.MAX_SLOT_COUNT) return null
		return start to ((end - start + 1).coerceAtLeast(1))
	}

	/**
	 * 将用户定义的课程合并到当前课程
	 * @param other
	 */
	fun mergeUserDefinedLesson(other: LessonTable): LessonTable {
		for(i in lessons.indices) {
			val groups = lessons[i]
			val otherGroups = other.lessons[i]
			for(j in groups.indices) {
				if(j >= otherGroups.size) break
				val otherGroup = otherGroups[j] ?: continue
				// 【本期·"空白课程"根因】以前无条件先建一个空课程组再合并,
				// 而 LessonGroup.mergeUserDefinedLesson 在没有用户课程时会直接返回 ——
				// 于是"新课表这一节没课、旧课表这一节有课"的每一格都会留下一个**空组**,
				// 界面上就是一张没有文字的空白课程卡片, 点开还是空表单。
				// 只有确实带着"用户课程(type==1)"时才需要建组。
				if(groups[j] == null) {
					if(otherGroup.lessons.none { it.type == 1 }) continue
					groups[j] = LessonGroup(i + 1, j + 1)
				}
				groups[j]!!.mergeUserDefinedLesson(otherGroup)
				// 合并后仍然没有课程 -> 不能留空壳
				if(groups[j]!!.lessons.isEmpty()) groups[j] = null
			}
		}
		return this
	}

	override fun equals(other: Any?): Boolean {
		if(this === other) return true
		if(javaClass != other?.javaClass) return false

		other as LessonTable

		if(startDay != other.startDay) return false
		if(totalWeek != other.totalWeek) return false
		if(!lessons.contentDeepEquals(other.lessons)) return false
		if(overrides != other.overrides) return false
		return dayOverrides == other.dayOverrides
	}

	override fun hashCode(): Int {
		var result = startDay.hashCode()
		result = 31 * result + totalWeek
		result = 31 * result + lessons.contentDeepHashCode()
		result = 31 * result + overrides.hashCode()
		return result
	}

	companion object{

		/** 点击空白课表时的默认选择框节数 */
		const val DEFAULT_SELECT_LENGTH = 2
	}
}
