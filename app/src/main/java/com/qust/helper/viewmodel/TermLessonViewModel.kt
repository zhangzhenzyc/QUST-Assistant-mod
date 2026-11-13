/*
 * 青科助手（修改版） —— 基于开源项目 QUST-Assistant 修改而成
 *
 * 原项目版权所有 (C) 2891954521  (https://github.com/2891954521/QUST-Assistant)
 * 原始开源许可: GNU General Public License v3.0 (GPL-3.0), 许可原文见 LICENSE 文件, 保持完整未改动。
 *
 * 修改版版权所有 (C) 2026 zhangzhenzyc  (https://github.com/zhangzhenzyc)
 * 本修改版同样以 GPL-3.0 发布; 附加版权声明见 NOTICE.md —— 两份版权声明相互独立、同时生效。
 */

package com.qust.helper.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.lifecycle.ViewModel
import com.qust.helper.R
import com.qust.helper.data.lesson.Lesson
import com.qust.helper.data.lesson.LessonGroup
import com.qust.helper.data.lesson.TimeTables
import com.qust.helper.model.LessonTableRepository
import com.qust.helper.ui.widget.LessonRender
import com.qust.helper.ui.widget.ToastContent

class TermLessonViewModel: ViewModel() {

	/** 编辑/新增课程时的"上课周数"选择器固定为 19 周(原版行为: 学期共 19 周) */
	private val EDITOR_WEEK_COUNT = 19

	val uiState = TermLessonUIState(totalWeek = EDITOR_WEEK_COUNT)

	val uiEvent = object: TermLessonUIEvent{
		override fun hideEdit() { uiState.isShowEdit = false }
		override fun clickLesson(select: LessonRender.SelectLesson) { this@TermLessonViewModel.clickLesson(select) }
	override fun deleteLessonAtWeek() { this@TermLessonViewModel.deleteLessonAtWeek() }
		override fun deleteBlockedMessage(): String? { return this@TermLessonViewModel.deleteBlockedMessage() }
	override fun saveLessonThisWeekOnly() { this@TermLessonViewModel.saveLessonThisWeekOnly() }
	override fun saveLessonMultiWeeks(weeks: Long) { this@TermLessonViewModel.saveLessonMultiWeeks(weeks) }
	override fun deleteLessonAllWeeks() { this@TermLessonViewModel.deleteLessonAllWeeks() }
		override fun saveLesson(force: Boolean) { this@TermLessonViewModel.saveLesson(force) }
		override fun pasteLessonNow(pasted: Lesson, overwrite: Lesson?) { this@TermLessonViewModel.pasteLessonNow(pasted, overwrite) }
		override fun toastOK(message: String){ uiState.toastContent.value = ToastContent(R.drawable.tips_finish, message) }
		override fun toastWarning(message: String) { uiState.toastContent.value = ToastContent(R.drawable.tips_warning, message) }
	}

	private var currentDayOfWeek = 0
	private var currentTimeSlot = 0

	/** 当前编辑所属周次(从0开始), 用于按具体日期校验时间重合 */
	private var currentWeekIndex = 0

	/** 当前选择框覆盖的节数(有课程时为该课程实际占用的节数) */
	private var currentSelectLen = 1

	/** 打开编辑框时该时段的原始节数, 用于判断是否真的改变了占用时长 */
	private var originLen = 1

	private var selectLesson: Lesson = Lesson.EMPTY_LESSON

	/** 该时段最多能占用的节数(不得越出时间末尾, 避免出现"半节课") */
	private fun maxLenFor(slot: Int): Int {
		val slotCount = TimeTables.currentSlotCount
		return (slotCount - slot).coerceIn(1, slotCount.coerceAtLeast(1))
	}

	/**
	 * 取"这一天的这一节, 本周实际在上"的课程(上课周数包含该周才算)
	 *
	 * 显示优先级与渲染层一致: 按日期副本(dayOverrides) > 调休(overrides) > 每周模板,
	 * 因此副本存在时只看副本, 否则用 LessonTable.effectiveDay 取当天实际生效的内容。
	 *
	 * 用途: 点击(尤其是刚粘贴完的那一格)时兜底 —— 点击结果可能带着过期快照,
	 * 只要这一格真的有课, 就不能让编辑界面显示成空白表单。
	 *
	 * @return 本周要上的那门课; 该格本周没课(含灰色课程)时返回 null
	 */
	private fun liveLessonAt(week: Int, dayOfWeek: Int, slot: Int): Lesson? {
		val table = LessonTableRepository.lessonTable
		val w = week.coerceIn(0, 62)
		val d = dayOfWeek.coerceIn(0, 6)
		val s = slot.coerceIn(0, TimeTables.MAX_SLOT_COUNT - 1)
		val mask = 1L shl w
		val col = table.dayOverrides[table.dateKeyOf(w, d)] ?: table.effectiveDay(w, d)
		return col.getOrNull(s)?.lessons?.firstOrNull { it.week and mask != 0L }
	}

	fun clickLesson(select: LessonRender.SelectLesson? = null) {
		// 【第四十八轮】打开编辑前强制刷新渲染数据: 单次编辑(拆分)后立即点击课程时,
		// 渲染层可能还持有拆分前的旧课程对象, 导致上课周数显示滞后/为空 —— 先刷新再取值。
		uiState.lessonRender.updateLessonTable()
		if(select != null){
			currentDayOfWeek = select.dayOfWeek
			currentTimeSlot = select.timeSlot
			if(select.week >= 0) currentWeekIndex = select.week
			// 【粘贴后第一次双击显示空白表单】点击结果里可能带着"这一格还是空白"的旧快照
			// (蓝框在粘贴之前就选中了这一格, 粘贴写入后它没有被通知更新)。
			// 只要这一格本周确实有课, 就必须把那门课取出来当"编辑已有课程", 不能按"新建课程"处理 ——
			// 否则编辑界面读不到课程数据(名称/教师/教室为空、上课周数全不选), 必须先点一下别处才恢复。
			val live = select.lesson ?: liveLessonAt(currentWeekIndex, select.dayOfWeek, select.timeSlot)
			if(live != null) {
				selectLesson = live
				uiState.isNewLesson = false
			}else{
				selectLesson = Lesson.EMPTY_LESSON
				uiState.isNewLesson = true
			}
			currentSelectLen = if(select.lesson != null) select.lesson.len.coerceAtLeast(1) else select.len.coerceAtLeast(1)

			// 【第四十轮】打开编辑时优先使用"该具体日期"的按日期副本里的课程:
			// 单次编辑此次课程后, 当天实际在上的是副本中的独立课程(上课周数仅当周),
			// 编辑界面必须展示它(含"上课周数只显示当周"), 而不是模板里的原课程。
			if(select.lesson != null && select.week >= 0){
				val dateKey = LessonTableRepository.lessonTable.dateKeyOf(select.week, select.dayOfWeek)
				val slotIdx = select.timeSlot.coerceIn(0, TimeTables.MAX_SLOT_COUNT - 1)
				val mask = 1L shl select.week.coerceIn(0, 62)
				// 该日期该节次"本周实际上课"的课程优先(即单次编辑产生的独立课程);
				// 不再依赖名称/对象匹配, 彻底消除"点开显示旧课程/周数为空"的竞态。
				LessonTableRepository.lessonTable.dayOverrides[dateKey]
					?.getOrNull(slotIdx)?.lessons
					?.firstOrNull { it.week and mask != 0L }
					?.let { selectLesson = it }
			}
		}

		uiState.name = selectLesson.name
		uiState.place = selectLesson.place
		uiState.teacher = selectLesson.teacher

		// 新建课程时使用"选择框"覆盖的节数(有课程时即该课程实际占用的节数, 空白处支持下拉扩展)
		uiState.len = if(uiState.isNewLesson) currentSelectLen.coerceAtLeast(1) else selectLesson.len.coerceAtLeast(1)
		uiState.color = selectLesson.color
		uiState.maxLen = maxLenFor(currentTimeSlot)
		uiState.len = uiState.len.coerceAtMost(uiState.maxLen)
		originLen = uiState.len
		uiState.overlapPrompt = null

		// 【自定义课程保存不了】新建课程时周次默认一个都不选, 而周数网格默认是折叠的
		// ("已选 0 周"), 用户没注意到 -> 点保存只提示"请选择上课时间", 看着就是保存不了。
		// 新建课程默认按"整个学期都上"全部勾上, 用户展开周数表可自行调整。
		// totalWeek 可能是 0(还没查过课表), 这时周次列表是空的, 根本没得选, 必须兜底。
		val weekCount = uiState.totalWeek.coerceAtLeast(1)
		if(uiState.week.size != weekCount){
			uiState.week = List(weekCount){ false }.toMutableStateList()
		}
		// 【双击空白课程默认本周】新建课程时, 若来源是某一周的具体格子(双击空白 / 长按空白),
		// 上课周数默认只勾选"双击所在的那一周", 而不是全学期 —— 符合"在哪周加的就在哪周上"。
		for(i in 0..< weekCount){
			uiState.week[i] = if(uiState.isNewLesson) (select?.week ?: -1) >= 0 && i == (select?.week ?: -1) else (1L shl i) and selectLesson.week > 0
		}

		// "上课周数冲突"提示所需的上下文
		uiState.editDayOfWeek = currentDayOfWeek
		uiState.editTimeSlot = currentTimeSlot
		uiState.editingLesson = if(uiState.isNewLesson) null else selectLesson
		uiState.originWeekMask = selectLesson.week
		// 记录打开编辑时所在的周次(从0开始), 供"编辑课程"界面标注"本课程在第N周"
		uiState.editContextWeek = if(select != null && select.week >= 0) select.week else currentWeekIndex

		// 记下"未修改"基准, 退出编辑时据此提示是否保存
		uiState.takeSnapshot()
		uiState.isShowEdit = true
	}


	/**
	 * 【第三十五/三十七轮】"单独编辑此次课程": 只把当前表单内容写入**当前上下文周的具体日期**
	 * (按日期覆盖 dayOverrides, 优先级最高)。
	 * 【第三十七轮·关键修复】同时把模板原课程的**该周上课位去掉** —— 否则模板与按日期副本
	 * 同时生效, 表现为"修改内容不显示/删除不掉"; 其它周完全不受影响。
	 */
	fun saveLessonThisWeekOnly(){
		val maxLen = maxLenFor(currentTimeSlot)
		if(uiState.len > maxLen) uiState.len = maxLen
		if(uiState.len < 1) uiState.len = 1
		if(uiState.name.isEmpty()){
			uiEvent.toastWarning("课程名称不能为空")
			return
		}

		val table = LessonTableRepository.lessonTable
		val weekIndex = currentWeekIndex.coerceIn(0, 62)
		// 调休/放假日期(该日期由调休或按日期副本接管): 这里显示的课是"借来的",
		// 不能去动每周模板, 否则会把被借用的那一天(如 10月1日)的课一起删掉。
		val dateOwned = table.isDateAdjusted(table.dateKeyOf(weekIndex, currentDayOfWeek))

		// 1) 模板原课程去掉该周的上课周数(该周从此由按日期副本单独负责)
		if(!dateOwned && selectLesson !== Lesson.EMPTY_LESSON && selectLesson.week and (1L shl weekIndex) != 0L){
			selectLesson.week = selectLesson.week and (1L shl weekIndex).inv()
			if(selectLesson.week == 0L){
				table.lessons[currentDayOfWeek][currentTimeSlot]?.removeLesson(selectLesson)
			}
		}

		// 2) 写入该日期的按日期覆盖副本(单独编辑的课程, 上课周数仅为本周)
		val col = table.editDayOverride(weekIndex, currentDayOfWeek)
		val slotIndex = currentTimeSlot.coerceIn(0, TimeTables.MAX_SLOT_COUNT - 1)
		val newLesson = Lesson(
			type = 1,
			name = uiState.name,
			teacher = uiState.teacher,
			place = uiState.place,
			color = uiState.color,
			week = 1L shl weekIndex,
			len = uiState.len.coerceAtLeast(1),
			kchID = selectLesson.kchID
		)
		// 【第九十五轮】保留"本周不上"的灰色课程: 编辑框打开时该格是灰课(如形势与政策),
		// 单独编辑此次课程会把该日期副本的这一格整格替换 —— dayOverride 一旦存在就整列生效,
		// 模板里的灰课被副本遮住, 表现为"灰色课程消失了"; 之后删掉新课程这一格留下 null, 灰课也回不来。
		// 修复: 写入新课程时把副本里"上课周次不含本周"的课程(即灰课)一并留在这一格里,
		// 灰课继续按灰色显示, 删除新课程后灰课也还在。
		val grayKeep: List<Lesson> = if(!dateOwned){
			val mask = 1L shl weekIndex
			col.getOrNull(slotIndex)?.lessons
				?.filter { (it.week and mask) == 0L }
				?.map { it.copy() }
				?: emptyList()
		}else emptyList()
		col[slotIndex] = if(grayKeep.isEmpty()){
			LessonGroup(currentDayOfWeek + 1, slotIndex + 1, arrayOf(newLesson))
		}else{
			LessonGroup(currentDayOfWeek + 1, slotIndex + 1, (grayKeep + newLesson).toTypedArray())
		}

		// 3) 原课程若比新课程更长, 清掉副本后续节次的遗留(避免"缩短后当天仍显示旧课")
		val oldLen = if(selectLesson !== Lesson.EMPTY_LESSON) selectLesson.len.coerceAtLeast(1) else 1
		if(oldLen > uiState.len){
			for(s in (slotIndex + 1) until (slotIndex + oldLen).coerceAtMost(TimeTables.MAX_SLOT_COUNT)){
				val g = col.getOrNull(s) ?: continue
				val remaining = g.lessons.filterNot { l -> l.name == selectLesson.name && l.place == selectLesson.place && l.teacher == selectLesson.teacher }
				if(remaining.size != g.lessons.size){
					col[s] = if(remaining.isEmpty()) null else LessonGroup(g.week, g.count, remaining.toTypedArray())
				}
			}
		}

		LessonTableRepository.saveLessonTable()
		LessonTableRepository.notifyLessonTableChanged()
		uiState.lessonRender.updateLessonTable()
		uiEvent.toastOK(if(dateOwned) "已把此次课程拆分为独立课程（仅当周上课）"
			else "已把此次课程拆分为独立课程（仅当周上课），原课程已去掉该周")
		// 【修改版】保存成功后复位快照: 避免退出时误判"未保存"而再次普通保存(那会把其它周的课程覆盖掉)
		uiState.takeSnapshot()
		uiState.isShowEdit = false
	}

	/**
	 * 【本轮】"多选编辑": 把编辑框里的新内容应用到勾选的若干周, 并把这几周从原课程中拆出去。
	 *
	 * 保存后该时段是**两份课程**(与"单独编辑此次课程"完全同一口径, 只是从"一周"变成"多周"):
	 * - 勾选的周 -> 独立成一份课程, 使用编辑框里的新内容;
	 * - 原课程   -> 只保留没被勾选的周, 内容不变。
	 * - 周数默认全选: 全部勾选时没有"剩余"部分, 等价于把新内容应用到整门课。
	 *
	 * 优先在**每周模板**里拆(两份都留在模板里, 之后编辑/删除都最自然):
	 * 原课程去掉被勾选的周位, 新课程带着这些周位放进同一格;
	 * 勾选周对应的日期若已有"按日期副本"(单独编辑/粘贴/教务通知调课产生), 同步把副本里
	 * 那一份换成新内容(仅该周) —— 否则副本会把模板盖住, 表现为"拆分没生效"。
	 *
	 * 正在编辑的课程只存在于某一天的副本里时(单独编辑出来的课程等), 退化为逐周写该日期副本
	 * (等同连续执行"单独编辑此次课程")。
	 *
	 * @param weeks 用户勾选的上课周位掩码(第1周 = bit0), 只会取与原课程周次相交的部分
	 */
	fun saveLessonMultiWeeks(weeks: Long){
		// 整节对齐: 与其它保存入口同一口径, 不越出时间末尾
		val maxLen = maxLenFor(currentTimeSlot)
		if(uiState.len > maxLen) uiState.len = maxLen
		if(uiState.len < 1) uiState.len = 1
		if(uiState.name.isEmpty()){
			uiEvent.toastWarning("课程名称不能为空")
			return
		}
		if(selectLesson === Lesson.EMPTY_LESSON || selectLesson.week == 0L){
			uiEvent.toastWarning("没有可拆分的课程")
			return
		}

		val weekCount = uiState.totalWeek.coerceIn(1, 62)
		val owned = selectLesson.week
		// 只认"这门课真的在上、且在本学期周数范围内"的周
		val split = weeks and owned and ((1L shl weekCount) - 1L)
		if(split == 0L){
			uiEvent.toastWarning("请至少选择一周")
			return
		}
		val remain = owned and split.inv()
		// 【本轮·按要求】周数默认全选: 全选时不下"拆分"结论, 走"整门课更新"(见下面的 toast 与第 1 步)

		val table = LessonTableRepository.lessonTable
		val day = currentDayOfWeek.coerceIn(0, 6)
		val slot = currentTimeSlot.coerceIn(0, TimeTables.MAX_SLOT_COUNT - 1)
		val oldLen = selectLesson.len.coerceAtLeast(1)
		val slotCount = TimeTables.MAX_SLOT_COUNT

		// 【先判断后改动】勾选周里哪些天的按日期副本确实有这份课程
		// (要在改写周位之前取, 之后副本里的周位会被清掉)
		val weeksWithCopy = HashSet<Int>()
		for(w in 0 until weekCount){
			val bit = 1L shl w
			if(split and bit == 0L) continue
			val col = table.dayOverrides[table.dateKeyOf(w, day)] ?: continue
			if(col.getOrNull(slot)?.lessons?.any { it.week and bit != 0L && it.isSameLessonAs(selectLesson) } == true) {
				weeksWithCopy.add(w)
			}
		}

		val newLesson = Lesson(
			type = 1,
			kchID = selectLesson.kchID,
			color = uiState.color,
			len = uiState.len.coerceAtLeast(1),
			week = split,
			name = uiState.name,
			place = uiState.place,
			teacher = uiState.teacher
		)

		// 1) 每周模板里"同一份课程"去掉勾选的周; 新课程(勾选周)放进同一格
		val templateGroup = table.lessons.getOrNull(day)?.getOrNull(slot)
		val templateLesson = templateGroup?.lessons?.firstOrNull { it === selectLesson }
			?: templateGroup?.lessons?.firstOrNull { it.isSameLessonAs(selectLesson) && (it.week and split) != 0L }
		val inTemplate = templateLesson != null && templateGroup != null
		if(templateLesson != null && templateGroup != null){
			templateLesson.week = templateLesson.week and split.inv()
			if(templateLesson.week == 0L) templateGroup.removeLesson(templateLesson)
			templateGroup.addLesson(newLesson)
		}else{
			// 只存在于按日期副本里: 原对象保留剩余周, 勾选周逐日写副本(见第 2 步)
			selectLesson.week = remain
		}

		// 2) 勾选周: 该日期已有按日期副本的把旧内容换成新内容(仅这一周);
		//    没有副本时模板(第 1 步)已经把这周铺好, 不用额外写。
		for(w in 0 until weekCount){
			val bit = 1L shl w
			if(split and bit == 0L) continue
			val key = table.dateKeyOf(w, day)
			val existing = table.dayOverrides[key]
			// 模板已铺好且这天没有副本 -> 显示正确, 不用动
			if(existing == null && inTemplate) continue
			// 这天被别的操作改过(整天删课/换成别的课): 保持原样, 不把课程塞回去
			if(existing != null && w !in weeksWithCopy) continue
			val col = existing ?: table.editDayOverride(w, day)
			val group = col.getOrNull(slot) ?: LessonGroup(day + 1, slot + 1).also { col[slot] = it }
			// 旧内容在这一周让位(它在其它周的周位保持不变)
			for(l in group.lessons.filter { it.week and bit != 0L && it.isSameLessonAs(selectLesson) }){
				l.week = l.week and bit.inv()
				if(l.week == 0L) group.removeLesson(l)
			}
			// 新内容(只落在这一周)
			group.addLesson(newLesson.copy(week = bit))
			// 新课程比原来短时, 清掉副本后续节次的遗留(否则缩短后当天还显示旧课)
			if(oldLen > uiState.len){
				for(s in (slot + 1) until (slot + oldLen).coerceAtMost(slotCount)){
					val g = col.getOrNull(s) ?: continue
					val remaining = g.lessons.filterNot { l ->
						l.name == selectLesson.name && l.place == selectLesson.place && l.teacher == selectLesson.teacher
					}
					if(remaining.size != g.lessons.size){
						col[s] = if(remaining.isEmpty()) null else LessonGroup(g.week, g.count, remaining.toTypedArray())
					}
				}
			}
		}

		LessonTableRepository.saveLessonTable()
		LessonTableRepository.notifyLessonTableChanged()
		uiState.lessonRender.updateLessonTable()
		uiEvent.toastOK(
			if(remain == 0L) "已更新该课程（共 ${java.lang.Long.bitCount(split)} 周）"
			else "已拆分：第${weeksText(split)}周使用新内容，原课程保留其余 ${java.lang.Long.bitCount(remain)} 周"
		)
		// 复位"未修改"基准: 否则退出编辑框时会再弹一次"未保存的修改"
		uiState.takeSnapshot()
		uiState.isShowEdit = false
	}

	/** 周位掩码 -> "1、2、3" 这样的周次文案(超过 6 周只列前 6 个) */
	private fun weeksText(mask: Long): String {
		val list = ArrayList<Int>()
		for(w in 0 until 62) if(mask and (1L shl w) != 0L) list.add(w + 1)
		val head = list.take(6).joinToString("、")
		return if(list.size > 6) "$head 等 ${list.size}" else head
	}

	/**
	 * 【第三十五轮】"删除全部课程"。
	 * 【第七十四轮·按需求重写判定】删除的始终是**当前正在编辑的这一份课程**：
	 * - 若编辑的是"单独编辑此次课程"拆分出来的**单周课程**（该日期副本里 week 仅为本周、且每周模板已不含本周）
	 *   -> 只删除本周这一条，其它周的同名课程**保持不变**；
	 * - 若编辑的是**每周模板里的原课程** -> 删除模板整门 + 各日期里"继承"的副本，
	 *   但**保留**各处"单独编辑拆分出来的单周课程"。
	 */
	fun deleteLessonAllWeeks(){
		// 【本轮修复】空白课程 -> "没有可删除的课程"; 灰色课程 -> 本周不上, 一律不许删
		val blocked = deleteBlockedMessage()
		if(blocked != null){
			uiEvent.toastWarning(blocked)
			return
		}
		val table = LessonTableRepository.lessonTable
		val weekIndex = currentWeekIndex.coerceIn(0, 62)
		val mask = 1L shl weekIndex
		val dateKey = table.dateKeyOf(weekIndex, currentDayOfWeek)

		// 判定: 当前编辑的是不是"单独编辑此次课程"拆分出来的单周课程
		val overrideCol = table.dayOverrides[dateKey]
		val splitInOverride = overrideCol?.getOrNull(currentTimeSlot)?.lessons?.any {
			it.week == mask && it.name == selectLesson.name &&
				it.teacher == selectLesson.teacher && it.place == selectLesson.place
		} == true
		val templateHasWeek = table.lessons.getOrNull(currentDayOfWeek)?.getOrNull(currentTimeSlot)
			?.lessons?.any { it.name == selectLesson.name && (it.week and mask) != 0L } == true
		val editingSplit = splitInOverride && !templateHasWeek

		if(editingSplit){
			// 只删除本周(当前编辑日期)的这一条拆分课程, 其它周保持不变
			val g = overrideCol!!.getOrNull(currentTimeSlot) ?: return
			val remaining = g.lessons.filterNot { l ->
				l.week == mask && l.name == selectLesson.name &&
					l.teacher == selectLesson.teacher && l.place == selectLesson.place
			}
			overrideCol[currentTimeSlot] = if(remaining.isEmpty()) null else LessonGroup(g.week, g.count, remaining.toTypedArray())
			LessonTableRepository.saveLessonTable()
			LessonTableRepository.notifyLessonTableChanged()
			uiState.lessonRender.updateLessonTable()
			uiEvent.toastOK("已删除本周（第${weekIndex + 1}周）的该课程，其它周保持不变")
			// 【修改版】删除已处理: 复位快照, 退出时不再误弹"是否保存"
			uiState.takeSnapshot()
			uiState.isShowEdit = false
			return
		}

		// 编辑的是每周模板里的原课程: 整门删除(以对象身份精确匹配, 再以名称/老师/地点兜底)
		val group = table.lessons.getOrNull(currentDayOfWeek)?.getOrNull(currentTimeSlot)
		if(group != null){
			val remaining = group.lessons.filterNot { l ->
				l === selectLesson || (l.name == selectLesson.name && l.teacher == selectLesson.teacher && l.place == selectLesson.place)
			}
			if(remaining.size != group.lessons.size){
				table.lessons[currentDayOfWeek][currentTimeSlot] =
					if(remaining.isEmpty()) null else LessonGroup(group.week, group.count, remaining.toTypedArray())
			}
		}
		// 2) 各日期的按日期副本: 删除"继承自原课程"的副本;
		//    【第七十三/七十四轮】**保留"单独编辑此次课程"拆分出来的单周副本** ——
		//    拆分副本的特征: 该副本只上所在日期那一周(week==该周掩码), 且每周模板里的原课程已不含该周。
		//    (上面模板删除之后 templateStillHasWeek 恒为 false, 拆分副本因此被保留。)
		for((key, col) in table.dayOverrides){
			val g = col.getOrNull(currentTimeSlot) ?: continue
			val wd = table.weekDayOfDate(key) ?: continue
			val mask = 1L shl wd.first
			val tpl = table.lessons.getOrNull(wd.second)?.getOrNull(currentTimeSlot)
			val templateStillHasWeek = tpl?.lessons?.any {
				it.week and mask != 0L && it.name == selectLesson.name
			} == true
			val remaining = g.lessons.filterNot { l ->
				l.name == selectLesson.name && (templateStillHasWeek || l.week != mask)
			}
			if(remaining.size != g.lessons.size){
				col[currentTimeSlot] = if(remaining.isEmpty()) null else LessonGroup(g.week, g.count, remaining.toTypedArray())
			}
		}
		LessonTableRepository.saveLessonTable()
		LessonTableRepository.notifyLessonTableChanged()
		uiState.lessonRender.updateLessonTable()
		uiEvent.toastOK("已删除该课程的全部周次")
		// 【修改版】删除已处理: 复位快照, 退出时不再误弹"是否保存"
		uiState.takeSnapshot()
		uiState.isShowEdit = false
	}

	/**
	 * 【本轮修复】"删除课程 / 删除全部课程"之前的可删除性判断
	 *
	 * 规则(与课表显示口径一致: 上课周次包含本周才算"本周有课"):
	 * 1. **空白课程** —— 该格没有任何已保存的课程, 编辑框里的内容还没保存 -> 一律视为"无课程", 不能删;
	 * 2. **灰色课程** —— 本周不上、只是别的周同一时间有课(课表里显示为灰色),
	 *    它在本周并不存在, 删它只会把"别的周要上的那门课"删掉/删残, 因此永远不能删。
	 *
	 * @return null 表示可以删除; 否则为应弹出的提示文案
	 */
	fun deleteBlockedMessage(): String? {
		// 1) 空白课程: 没有已保存的课程(新建/只填了表单但没保存)
		if(selectLesson === Lesson.EMPTY_LESSON || selectLesson.name.isEmpty()) return "没有可删除的课程"
		// 2) 灰色课程: 上课周次不含当前周 -> 本周根本没上这门课
		val weekIndex = currentWeekIndex.coerceIn(0, 62)
		if(selectLesson.week and (1L shl weekIndex) == 0L) return "该课程本周不上课，不能删除"
		return null
	}

	/**
	 * 【需求1】编辑底部"删除课程": 只取消该课程在"当前编辑上下文所在周"的上课,
	 * 其它周照常保留(如删第6周物理课 -> 只删第6周同一时间的物理课)。
	 * 若该周是这门课唯一的上课周, 整门从时段移除。
	 *
	 * 【第三十六轮·修复】该日期存在按日期覆盖副本(如"单独编辑此次课程"产生)时,
	 * 先把副本里的该课程(即"单次修改的课程")删掉, 再清除模板里该周的上课周次 ——
	 * 保证当周确实不再显示, 且**不影响其它周**。
	 *
	 * 【本轮修复】空白课程与灰色课程一律不删(直接给气泡, 见 [deleteBlockedMessage]);
	 * 并且在按日期副本里只删"本周真的会上"的那一节 —— 副本是整天的深拷贝,
	 * 里面还带着"其它周才上"的灰色课程, 按名字一概删掉会把这些课误删。
	 */
	fun deleteLessonAtWeek(){
		val blocked = deleteBlockedMessage()
		if(blocked != null){
			uiEvent.toastWarning(blocked)
			return
		}
		val weekIndex = currentWeekIndex.coerceIn(0, 62)
		val mask = 1L shl weekIndex
		// 【修改版】只要该课程确实存在于“当天的模板或日期副本”中, 就允许删除;
		// 只有完全找不到该课程(异常状态)时才提示, 不再用周次掩码误判(教务通知调课写入的课程掩码常不含本周)
		val t = LessonTableRepository.lessonTable
		fun sameAs(l: Lesson) = l.name == selectLesson.name && l.place == selectLesson.place && l.teacher == selectLesson.teacher
		val inTemplate = t.lessons.getOrNull(currentDayOfWeek)?.getOrNull(currentTimeSlot)?.lessons?.any { sameAs(it) } == true
		val inOverride = t.dayOverrides[t.dateKeyOf(weekIndex, currentDayOfWeek)]?.getOrNull(currentTimeSlot)?.lessons?.any { sameAs(it) } == true
		if(!inTemplate && !inOverride){
			uiEvent.toastWarning("该课程本周不上课，无法删除")
			return
		}
		// 【调休日期】这一天显示的课是从别的星期"借"过来的(如 10月21日 上 10月1日的课):
		// 只在该日期的副本里删掉, 绝不去清模板里的上课周次 ——
		// 否则会把被借用的那一天(10月1日)同一周的课一起删掉。
		val ownedKey = t.dateKeyOf(weekIndex, currentDayOfWeek)
		if(t.overrides.containsKey(ownedKey) && !t.dayOverrides.containsKey(ownedKey)){
			val col = t.editDayOverride(weekIndex, currentDayOfWeek)
			val slotIndex = currentTimeSlot.coerceIn(0, TimeTables.MAX_SLOT_COUNT - 1)
			val g = col.getOrNull(slotIndex)
			if(g != null){
				// 【本轮修复】只删本周真的会上的一节(副本里其它周才上的灰色课程不许动)
				val remaining = g.lessons.filterNot { l -> l.week and mask != 0L && sameAs(l) }
				if(remaining.size != g.lessons.size){
					col[slotIndex] = if(remaining.isEmpty()) null else LessonGroup(g.week, g.count, remaining.toTypedArray())
					LessonTableRepository.saveLessonTable()
					LessonTableRepository.notifyLessonTableChanged()
					uiState.lessonRender.updateLessonTable()
					uiEvent.toastOK("已删除该课程在本周的上课")
					uiState.takeSnapshot()
					uiState.isShowEdit = false
					return
				}
			}
		}
		// 1) 按日期覆盖副本里删除该课程(单次修改的课程 / 粘贴的课程)
		val overrideKey = LessonTableRepository.lessonTable.dateKeyOf(weekIndex, currentDayOfWeek)
		val overrideCol = LessonTableRepository.lessonTable.dayOverrides[overrideKey]
		if(overrideCol != null){
			val slotIndex = currentTimeSlot.coerceIn(0, TimeTables.MAX_SLOT_COUNT - 1)
			val g = overrideCol.getOrNull(slotIndex)
			if(g != null){
				// 【本轮修复】同样只删"本周真的会上"的那一节
				val remaining = g.lessons.filterNot { l ->
					l.week and mask != 0L && l.name == selectLesson.name && (selectLesson.teacher.isEmpty() || l.teacher == selectLesson.teacher)
				}
				if(remaining.size != g.lessons.size){
					overrideCol[slotIndex] = if(remaining.isEmpty()) null else LessonGroup(g.week, g.count, remaining.toTypedArray())
				}
			}
		}
		// 2) 模板里清除该周的上课周次
		selectLesson.week = selectLesson.week and mask.inv()
		val group = LessonTableRepository.lessonTable.lessons[currentDayOfWeek][currentTimeSlot]
		if(selectLesson.week == 0L){
			group?.removeLesson(selectLesson)
		}
		LessonTableRepository.saveLessonTable()
		LessonTableRepository.notifyLessonTableChanged()
		uiState.lessonRender.updateLessonTable()
		uiEvent.toastOK("已删除该课程在本周的上课")
				// 【修改版】删除已处理: 复位快照, 退出时不再误弹"是否保存"
		uiState.takeSnapshot()
uiState.isShowEdit = false
	}

	fun saveLesson(force: Boolean = false) {
		var week = 0L
		var tmp = 1L
		for(i in uiState.week){
			if(i) week = week or tmp
			tmp = tmp shl 1
		}

		if(week == 0L) {
			uiEvent.toastWarning("请选择上课时间！")
			return
		}

		// 整节对齐: 课程不得越出时间末尾, 避免出现"半节课"
		val maxLen = maxLenFor(currentTimeSlot)
		if(uiState.len > maxLen) uiState.len = maxLen
		if(uiState.len < 1) uiState.len = 1

		// 时间重合校验: 与同一天其它课程的占用时段冲突时, 先按"课程重叠"弹窗确认
		// 【第九十五轮】文案与"课程重叠"弹窗统一, 且确认=覆盖(已有课程会被删除, 不允许两节课同时上)
		if(!force && (uiState.isNewLesson || uiState.len != originLen)){
			val editing = if(uiState.isNewLesson) null else selectLesson
			val conflict = LessonTableRepository.lessonTable
				.spanOccupied(currentWeekIndex, currentDayOfWeek, currentTimeSlot, uiState.len, editing)
			if(conflict != null){
				uiState.overlapPrompt = "该时间段已有课程，会造成课程重叠，覆盖会导致已有课程删除，是否确认覆盖？" +
					"\n\n目标位置：第${currentTimeSlot + 1}~${currentTimeSlot + uiState.len}节（已有「${conflict.name}」）"
				return
			}
		}
		// 上课周数冲突: 所选周次里该时段已被别的课程占用时给出提示(确认后可继续保存)
		// 【第九十五轮】同样按"课程重叠"口径: 确认后这些周已有的课程会被覆盖删除
		if(!force && (uiState.isNewLesson || week != uiState.originWeekMask || uiState.len != originLen)){
			val conflicts = LessonTableRepository.lessonTable.conflictingWeeks(
				uiState.editDayOfWeek, uiState.editTimeSlot, uiState.len, week,
				uiState.editingLesson, uiState.totalWeek
			)
			if(conflicts.isNotEmpty()){
				val detail = conflicts.take(6).joinToString("、"){ "第${it.first}周「${it.second}」" } +
					if(conflicts.size > 6) " 等 ${conflicts.size} 周" else ""
				uiState.overlapPrompt = "该时间段已有课程，会造成课程重叠，覆盖会导致已有课程删除，是否确认覆盖？" +
					"\n\n涉及周次：$detail"
				return
			}
		}
		uiState.overlapPrompt = null

		// 【第九十五轮】覆盖语义: 走到这里的 force 保存是用户在"课程重叠"弹窗里确认过的,
		// 必须清掉新课程占用区间里已有的课程(对所有勾选周生效) ——
		// 任何情况下都不允许同一时间两节课同时在上课(已有课程只清本周的上课位, 其它周不受影响)。
		if(force){
			val tbl = LessonTableRepository.lessonTable
			val sel = if(uiState.isNewLesson) null else selectLesson
			val slotEnd = (currentTimeSlot + uiState.len.coerceAtLeast(1)).coerceAtMost(TimeTables.MAX_SLOT_COUNT)
			for(w in 0 until 63){
				if(week and (1L shl w) == 0L) continue
				for(s in currentTimeSlot until slotEnd){
					for(occ in tbl.occupantsAt(w, currentDayOfWeek, s).toList()){
						val l = occ.lesson
						if(sel != null && (l === sel || l.isSameLessonAs(sel))) continue
						val mask = 1L shl w
						var hit = false
						// 每周模板里的课程: 清掉这一周的上课位, 清空后整门移除
						tbl.lessons.getOrNull(currentDayOfWeek)?.forEach { g ->
							if(g != null && g.lessons.contains(l)){
								l.week = l.week and mask.inv()
								if(l.week == 0L) g.removeLesson(l)
								hit = true
							}
						}
						// 按日期副本里的课程: 同一口径
						val ocol = tbl.dayOverrides[tbl.dateKeyOf(w, currentDayOfWeek)]
						ocol?.forEach { g ->
							if(g != null && g.lessons.contains(l)){
								l.week = l.week and mask.inv()
								if(l.week == 0L) g.removeLesson(l)
								hit = true
							}
						}
						if(hit) break   // 同一门课只处理一次
					}
				}
			}
		}

		val table = LessonTableRepository.lessonTable
		val weekIndex = currentWeekIndex.coerceIn(0, 62)
		val slotIndex = currentTimeSlot.coerceIn(0, TimeTables.MAX_SLOT_COUNT - 1)
		// 【调休日期】这一天显示的并不是"这一列每周模板"的课, 而是调休借来的那一列或该日期的按日期副本
		// (例如 10月21日 上 10月1日 的课)。此时把课程写进每周模板 → 这天根本不显示,
		// 却已经在模板里占住了位置(再粘贴就提示重合)。所以此类日期必须写进"该日期的按日期副本"。
		val dateOwned = table.isDateAdjusted(table.dateKeyOf(weekIndex, currentDayOfWeek))

		var hasEdit: Boolean = uiState.isNewLesson

		if(uiState.isNewLesson){ selectLesson = Lesson(type = 1) }

		// 编辑"调休借来的课程"时, 先在该日期生成按日期副本, 再改副本里的那一节:
		// 否则改的是被借用那一列(如 10月1日)的模板课, 会连带改掉所有同星期的日期。
		// 只有"确实改了内容"才建副本, 免得一次什么都没改的保存就把该日期冻结成副本。
		val formChanged = uiState.isNewLesson || week != selectLesson.week || uiState.len != selectLesson.len ||
			uiState.name != selectLesson.name || uiState.place != selectLesson.place ||
			uiState.teacher != selectLesson.teacher || uiState.color != selectLesson.color
		if(dateOwned && formChanged && !uiState.isNewLesson && selectLesson !== Lesson.EMPTY_LESSON){
			val col = table.editDayOverride(weekIndex, currentDayOfWeek)
			col.getOrNull(slotIndex)?.lessons?.firstOrNull { it.isSameLessonAs(selectLesson) }?.let { selectLesson = it }
		}

		if(week != selectLesson.week || uiState.len != selectLesson.len) {
			selectLesson.len = uiState.len
			selectLesson.week = week
			hasEdit = true
		}

		if(uiState.name != selectLesson.name) {
			selectLesson.name = uiState.name
			hasEdit = true
		}

		if(uiState.place != selectLesson.place) {
			selectLesson.place = uiState.place
			hasEdit = true
		}

		if(uiState.teacher != selectLesson.teacher) {
			selectLesson.teacher = uiState.teacher
			hasEdit = true
		}

		var hasEditColor = false
		if(uiState.color != selectLesson.color) {
			selectLesson.color = uiState.color
			hasEditColor = true
		}

		if(hasEdit){
			// 【第九十五轮】U -> A: 反复修改后, 除课程颜色外的全部内容(名称/教师/教室/节数/周数)
			// 与最新原始课表(最近一次查询导入的快照)完全一致时, 标记恢复为 A;
			// 否则维持用户修改(U)。
			selectLesson.type = if(
				LessonTableRepository.matchesOriginalLesson(currentDayOfWeek, slotIndex, selectLesson)
			) 0 else 1
		}

		if(hasEdit || hasEditColor) {
			if(uiState.isNewLesson){
				if(dateOwned){
					// 调休/放假日期: 写进该日期的副本, 当天立即可见
					val col = table.editDayOverride(weekIndex, currentDayOfWeek)
					val group = col.getOrNull(slotIndex) ?: LessonGroup(currentDayOfWeek + 1, slotIndex + 1).also { col[slotIndex] = it }
					group.addLesson(selectLesson)
				}else{
					table.getLessonGroupNotNull(currentDayOfWeek, slotIndex).addLesson(selectLesson)
				}
			}
			LessonTableRepository.saveLessonTable()
			LessonTableRepository.notifyLessonTableChanged()
			uiState.lessonRender.updateLessonTable()
		}

		uiState.isShowEdit = false
	}

	/**
	 * 【修改版】粘贴后立即写入课表(编辑框保持打开, 无需再点其它位置/保存)
	 *
	 * 【必须写"该日期的按日期副本"】学期课表的显示优先级是
	 * 按日期副本(dayOverrides) > 调休(overrides) > 每周模板;
	 * 之前写每周模板, 一旦该日期已经有调休/单日编辑产生的副本, 模板就被完全遮住 ——
	 * 数据其实写进去了但画面上永远不显示(表现为"要再点一下别的位置才出现")。
	 * 这里统一走 [LessonTable.replaceLessonAt]: 只改这一天的副本, 写在哪天就显示在哪天,
	 * 同时清掉目标区间里原有的课程(覆盖语义), 并去重, 不会出现两门一样的课。
	 *
	 * 写完后把编辑状态切换为"正在编辑这门已写入的课程" —— 之后点保存是就地修改, 不会重复添加。
	 *
	 * @param pasted 剪贴板取出的课程(深拷贝, 可直接持有)
	 * @param overwrite 目标位置将被覆盖的课程; 无重合时传 null(仅用于提示文案, 写入口径相同)
	 */
	fun pasteLessonNow(pasted: Lesson, overwrite: Lesson?) {
		val table = LessonTableRepository.lessonTable
		val weekIndex = uiState.editContextWeek.coerceIn(0, 62)
		val dayIndex = uiState.editDayOfWeek.coerceIn(0, 6)
		val slotIndex = uiState.editTimeSlot.coerceIn(0, TimeTables.MAX_SLOT_COUNT - 1)
		currentWeekIndex = weekIndex
		currentDayOfWeek = dayIndex
		currentTimeSlot = slotIndex

		// 整节对齐: 课程不得越出时间末尾, 避免出现"半节课"(与 saveLesson 同一口径)
		val maxLen = maxLenFor(slotIndex)
		if(pasted.len > maxLen) pasted.len = maxLen
		if(pasted.len < 1) pasted.len = 1
		uiState.len = pasted.len
		uiState.maxLen = maxLen

		// 只写该日期的按日期副本(显示优先级最高): 内部会设好周次、清掉区间内原有课程并去重
		table.replaceLessonAt(weekIndex, dayIndex, slotIndex, pasted)

		LessonTableRepository.saveLessonTable()
		LessonTableRepository.notifyLessonTableChanged()
		uiState.lessonRender.updateLessonTable()

		// 编辑界面保持打开: 切换为"正在编辑这门已写入的课程", 之后点保存是就地修改, 不会重复添加
		selectLesson = pasted
		uiState.isNewLesson = false
		uiState.editingLesson = pasted
		uiState.originWeekMask = pasted.week
		originLen = pasted.len.coerceAtLeast(1)
		uiState.overlapPrompt = null
		uiState.takeSnapshot()
		// 【本轮】粘贴改为"点完成后才写入": 写入即完成, 关闭编辑框(与普通保存一致)
		uiState.isShowEdit = false
	}
}

class TermLessonUIState(totalWeek: Int = 1) {
	val lessonRender = LessonRender()

	var toastContent = mutableStateOf(ToastContent.EMPTY_TOAST)

	var totalWeek by mutableIntStateOf(totalWeek)

	var name by mutableStateOf("")
	var place by mutableStateOf("")
	var teacher by mutableStateOf("")

	var len by mutableIntStateOf(1)
	/** 当前时段最多可占用的节数(整节对齐, 不越出时间末尾) */
	var maxLen by mutableIntStateOf(TimeTables.MAX_SLOT_COUNT)
	/** 时间重合/上课周数冲突提示文本, 非null时弹出确认框 */
	var overlapPrompt by mutableStateOf<String?>(null)
	/** 正在编辑的星期(0-6)与起始节次(0开始): 用于"上课周数冲突"提示 */
	var editDayOfWeek by mutableIntStateOf(0)
	var editTimeSlot by mutableIntStateOf(0)

	/** 打开编辑时所在的周次(从0开始): 用于"编辑课程"界面标注"本课程在第N周" */
	var editContextWeek by mutableIntStateOf(-1)

	/** 正在编辑的课程(新建时为null): 冲突检查时忽略它自己 */
	var editingLesson by mutableStateOf<Lesson?>(null)

	/** 进入编辑前的上课周掩码: 用于判断"上课周数是否被改动" */
	var originWeekMask by mutableLongStateOf(0L)
	var color by mutableIntStateOf(0)

	var week = List(totalWeek){ false }.toMutableStateList()

	var isShowEdit by mutableStateOf(false)
	var isNewLesson by mutableStateOf(false)

	/** 【修改版】本次编辑是否来自"从课表选择课程填充": 保存时按"单独编辑此次课程"处理(仅当周, 原课程拆成两部分) */
	var pickFillMode by mutableStateOf(false)

	/**
	 * 【本轮】本次编辑是否来自"粘贴课程":
	 * 粘贴只把内容填进表单, **点右上角"完成"才真正写入课表** ——
	 * 之前粘贴是立刻落库的, 于是"没点完成直接退出"也会把课程存进课表。
	 * 该标记为 true 时, "完成/保存"走覆盖写入(pasteLessonNow), 而不是普通保存。
	 */
	var pasteFillMode by mutableStateOf(false)

	// ---------- 表单改动检测(退出编辑时提示"是否保存") ----------

	private var snapshotName = ""
	private var snapshotPlace = ""
	private var snapshotTeacher = ""
	private var snapshotColor = 0
	private var snapshotLen = 1
	private var snapshotWeek = 0L

	/** 当前"上课周数"选择的位掩码 */
	fun weekMask(): Long {
		var mask = 0L
		var tmp = 1L
		for(b in week){
			if(b) mask = mask or tmp
			tmp = tmp shl 1
		}
		return mask
	}

	/** 记下当前表单内容, 作为"未修改"的基准(打开编辑弹窗时调用) */
	fun takeSnapshot() {
		pickFillMode = false
		pasteFillMode = false
		snapshotName = name
		snapshotPlace = place
		snapshotTeacher = teacher
		snapshotColor = color
		snapshotLen = len
		snapshotWeek = weekMask()
	}

	/** 【第六十二轮】"课程内容"是否发生改动(课程名称/教师/教室/课程颜色/节数);
	 *  仅修改上课周数时不算内容改动(周数改动对所有周次生效, 单周编辑无意义)。
	 *
	 *  【本轮】按要求把**节数**也算进内容改动: 改短/改长一节同样是一次真实的课表修改,
	 *  应当可以"只改这一次"或"只改勾选的几周", 而不是被迫对全部周次生效。 */
	fun contentChange(): Boolean =
		name != snapshotName || place != snapshotPlace || teacher != snapshotTeacher || color != snapshotColor ||
		len != snapshotLen

	/** 【第六十三轮】上课周数是否被改动 */
	fun weekChange(): Boolean = weekMask() != snapshotWeek

	/**
	 * 【修改版】退出编辑时是否需要弹"是否保存":
	 * 只有 课程名称/教室/教师/上课周数/课程颜色/节数 相对打开时发生了改变才需要。
	 * 仅点击"从课表选择"而未真正选择课程不算修改, 不弹窗。
	 *
	 * 【本轮】节数改动也算修改(见 [contentChange] 的说明)。
	 */
	fun needConfirmExit(): Boolean =
		pasteFillMode || name != snapshotName || place != snapshotPlace || teacher != snapshotTeacher ||
		color != snapshotColor || weekMask() != snapshotWeek || len != snapshotLen
}

interface TermLessonUIEvent{
	fun hideEdit(){ }

	fun clickLesson(select: LessonRender.SelectLesson){ }

	/** 【需求1】只取消该课程在当前编辑上下文所在周的上课, 其它周保留 */
	fun deleteLessonAtWeek(){ }

	/**
	 * 【本轮修复】"删除课程 / 删除全部课程"之前的可删除性判断
	 * @return null 可删除; 否则为提示文案(空白课程 -> "没有可删除的课程"; 灰色课程 -> "该课程本周不上课，不能删除")
	 */
	fun deleteBlockedMessage(): String? = null

	/** 【第三十五轮】只把当前表单内容写入当前上下文周的具体日期, 每周模板不动 */
	fun saveLessonThisWeekOnly(){ }

	/**
	 * 【本轮】"多选编辑": 把当前表单内容应用到勾选的若干周, 并把这几周从原课程中拆出去
	 * @param weeks 勾选的上课周位掩码(第1周 = bit0)
	 */
	fun saveLessonMultiWeeks(weeks: Long){ }
	/** 【第三十五轮】把该课程从每周课表模板中整门删除(所有周次) */
	fun deleteLessonAllWeeks(){ }

	/**
	 * 保存课程
	 * @param force 为true时跳过"时间重合"确认直接保存(用户在确认框中选择了继续)
	 */
	fun saveLesson(force: Boolean = false){ }

	/**
	 * 【修改版】粘贴后立即写入课表(编辑框保持打开, 无需再点其它位置/保存)
	 * @param pasted 剪贴板取出的课程
	 * @param overwrite 目标位置将被覆盖的课程; 无重合时传 null
	 */
	fun pasteLessonNow(pasted: Lesson, overwrite: Lesson?){ }

	fun toastOK(message: String){ }
	fun toastWarning(message: String){ }
}