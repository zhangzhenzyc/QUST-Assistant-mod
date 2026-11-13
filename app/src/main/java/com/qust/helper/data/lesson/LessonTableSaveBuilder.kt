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

import java.util.Date

/**
 * 「保存课表」勾选"保存用户修改的课程与调休安排"时, 计算出要真正写入的课表
 *
 * 【为什么单独抽出来】
 * 这段规则以前写在 ViewModel 里, 并且用"预览课表(preview)"当基准 —— 而预览课表在保存成功之后
 * 会被替换成"已经保存下来的课表"(它本身就已经少了被删除的课程), 于是从第二次保存开始,
 * "按最新原始课表补回被删除的课程"就永远补不回来了(只有查询课表后的第一次保存有效)。
 * 现在统一以 [original](最新查询到的、没有任何修改的课表快照)为底, 规则集中在这里,
 * 既可以单元测试, 也不会再被"当前课表"的内容带偏。
 *
 * 规则(与勾选项的说明文字一致):
 * 1. 课程内容以**最新原始课表**为准 —— 删除(删单节课 / 删整天课表 / 删整门课)不算修改, 一律补回;
 * 2. 用户修改过(type==1, 改名/换教室/换老师/换颜色/拖动)的课程保留用户版本, 用户新建的课程追加;
 * 3. 按日期修改(dayOverrides)原样保留, 但其中被删空的节次按原始课表在该日期生效的课程补回;
 * 4. 调休借课日期(overrides 1..7)按"保存这一刻显示的课程"冻结成按日期副本, 保存不改变显示;
 * 5. 勾选了"连同调休安排一起保存"时保留全部调休/放假标记, 否则只保留"没有调休标记"的按日期修改;
 * 6. 「教务通知调课」明确停掉的**节次**(lessonTable.easSuspended)不补回, 其余空洞照常补回。
 */
object LessonTableSaveBuilder {

	/**
	 * @param original 最新查询到的原始课表(不含任何用户修改)
	 * @param current 当前内存里的课表(用户改过/删过)
	 * @param startDay 本次保存使用的开学时间
	 * @param totalWeek 本次保存使用的总周数
	 * @param saveAdjustments 是否保存调休/放假安排
	 */
	fun buildKeepUserTable(
		original: LessonTable,
		current: LessonTable,
		startDay: Date,
		totalWeek: Int,
		saveAdjustments: Boolean
	): LessonTable {
		// 1) 以最新原始课表为底: 被删掉的课程天然都在这里, 因此每次保存都会被补回
		val result = original.deepCopy()
		result.startDay = startDay
		result.totalWeek = totalWeek

		// 2) 合并用户修改过/新建的课程
		result.mergeUserDefinedLesson(current)

		// 3) 调休标记与按日期修改
		val dayCopies = HashMap<String, Array<LessonGroup?>>()
		for((k, v) in current.dayOverrides){
			// 调休/放假日期上的副本属于"调休安排", 不保存调休时一并丢弃
			if(!saveAdjustments && current.overrides.containsKey(k)) continue
			dayCopies[k] = Array(v.size) { j -> v[j]?.deepCopy() }
		}
		if(saveAdjustments){
			result.overrides = LinkedHashMap(current.overrides)
		}else{
			result.overrides.clear()
		}
		result.dayOverrides = dayCopies
		// 教务通知调课写过的日期: 第 5 步要据此跳过"删除补回", 因此必须在第 5 步之前放进来
		result.easDates = HashSet(current.easDates)
		result.easSuspended = HashMap()
		for((k, slots) in current.easSuspended) result.easSuspended[k] = HashSet(slots)

		// 4) 调休借课日期(overrides 1..7): 冻结"保存这一刻这一天显示的课程"
		//
		// 【本期·"调休日期保存后莫名多出课程"的修复】借课日期的内容有两种来源:
		// ① 建调休时由 copyDayByDate 写好的按日期副本(只含**源周**要上的课);
		// ② 没有副本时 effectiveDay 会退回"被借用那一列的整列模板"(里面还有别的周才上的课)。
		// 以前保存只处理"空节次", 于是没有副本(或副本被清过)的调休日期在保存后会被模板重新解释,
		// 只要那一天有别的周才上的课, 就会"凭空多出几门课"。
		// 现在统一在保存时把这个日期**当前显示的内容**按本周周次过滤后快照成副本:
		// 保存前后所见即所得, 用户在这天删掉的课也保持删除。
		freezeBorrowedDates(result)

		// 5) 删除不算修改: 普通日期副本里"本周没有课"的节次, 按最新原始课表当天本应上的课补回
		//
		// 【本期·"在原课程位置粘贴新课后删除, 保存后该位置空"的修复】
		// 粘贴是"覆盖语义"(replaceLessonAt 会把该节次原课程在本周的周次位清掉), 删除只把粘贴的
		// 课程从副本里摘掉, 于是副本里剩下一条"被清掉本周"的旧课程 —— 它既不是空节次, 也不会在
		// 本周显示, 课表上就成了一个空洞。所以判据不能是"这一节为空", 而必须是"这一节本周有没有课"。
		//
		// 【本期】教务通知调课写过的日期例外: 那里的"没有课"就是通知的结果(停课),
		// 按"删除补回"处理会让教务停课失效 —— 这正是"教务通知调课没生效"的原因。
		for((k, col) in result.dayOverrides.toList()){
			val (week, day) = result.weekDayOfDate(k) ?: continue
			val action = result.overrides[k]
			if(action != null && action in 1..7) continue   // 借课日期已在第 4 步冻结
			// 【本期】"教务通知停课"的例外只精确到**节次**, 不再跳过整天 ——
			// 跳过整天会让这一天里因"粘贴覆盖后删除"留下的空洞永远补不回来(空白课程无法恢复)。
			val suspended = result.easSuspended[k]
			// 暂时摘掉该日期自己的副本, 才能拿到"这一天本来是哪些课"(放假标记不参与, 仍是当天模板)
			result.dayOverrides.remove(k)
			val baseline = result.effectiveDay(week, day)
			result.dayOverrides[k] = col
			val mask = 1L shl week.coerceIn(0, 62)
			for(i in col.indices){
				// 教务通知明确停掉的节次: 这就是通知的结果, 不补回
				if(suspended != null && i in suspended) continue
				val cur = col.getOrNull(i)
				// 该节次本周已经有课 -> 属于用户自己的内容(粘贴覆盖 / 新建课程), 原样保留
				if(cur != null && cur.lessons.any { it.week and mask != 0L }) continue
				val restored = baseline.getOrNull(i)?.lessons?.filter { it.week and mask != 0L }
				if(restored.isNullOrEmpty()){
					// 原始课表这一节本周同样没课: 只清掉"空壳分组", 其它周才上的灰色课程保持原样
					if(cur != null && cur.lessons.isEmpty()) col[i] = null
					continue
				}
				// 深拷贝: 补回的课程不能与"每周模板"共用同一个对象, 否则改一处会连带改另一处
				col[i] = LessonGroup(day + 1, i + 1).also { g ->
					g.lessons = restored.map { it.copy() }.toTypedArray()
				}
			}
		}

		// 6) 教务通知调课的标记跟着"保留下来的按日期副本"走
		result.easDates = current.easDates.filterTo(HashSet()) { result.dayOverrides.containsKey(it) }
		result.easSuspended = HashMap()
		for((k, slots) in current.easSuspended) {
			if(!result.dayOverrides.containsKey(k)) continue
			result.easSuspended[k] = HashSet(slots)
		}
		return result
	}

	/**
	 * 把调休借课日期(overrides 1..7)的副本按"本周真正会上"过滤后冻结
	 *
	 * 保存课表与「教务通知调课」都要写按日期副本, 两边必须用同一套规则,
	 * 否则同一份调休在不同入口保存出来的课表会不一样。
	 */
	fun freezeBorrowedDates(table: LessonTable) {
		for(k in table.dayOverrides.keys.toList()){
			val (week, day) = table.weekDayOfDate(k) ?: continue
			val action = table.overrides[k]
			if(action == null || action !in 1..7) continue
			table.dayOverrides[k] = snapshotWeekColumn(table.dayOverrides[k]!!, week, day)
		}
		// 借课日期没有副本时: 按"借来的那一列 + 本周过滤"补一份, 不让它退回整列模板
		for((k, action) in table.overrides.toList()){
			if(action !in 1..7) continue
			if(table.dayOverrides.containsKey(k)) continue
			val (week, day) = table.weekDayOfDate(k) ?: continue
			val source = table.lessons.getOrNull(action - 1) ?: continue
			table.dayOverrides[k] = snapshotWeekColumn(source, week, day)
		}
	}

	/**
	 * 把某一天的课程列按"本周会上"过滤后快照成一份按日期副本
	 *
	 * @param col   源课程列(某天的副本 或 被借用那一列的每周模板)
	 * @param week  第几周(从0开始)
	 * @param day   星期几(0=周一), 用于生成副本里课程组的星期字段
	 */
	private fun snapshotWeekColumn(col: Array<LessonGroup?>, week: Int, day: Int): Array<LessonGroup?> {
		val mask = 1L shl week.coerceIn(0, 62)
		return Array(TimeTables.MAX_SLOT_COUNT) { j ->
			val g = col.getOrNull(j) ?: return@Array null
			// 只保留"这一周真的会上的"课程; 深拷贝避免与模板共用对象
			val lessons = g.lessons.filter { it.week and mask != 0L }.map { it.copy() }
			if(lessons.isEmpty()) null else LessonGroup(day + 1, j + 1).also {
				it.lessons = lessons.toTypedArray()
			}
		}
	}
}
