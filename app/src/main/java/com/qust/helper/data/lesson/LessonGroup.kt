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

import kotlinx.serialization.Serializable

/**
 * 一个时间的课程组
 * 表示一个时间点的N节不同的课程
 * @param week 星期几 1-7
 * @param count 节次 1-10
 */
@Serializable
data class LessonGroup(
	var week: Int,
	var count: Int,
	var lessons: Array<Lesson> = emptyArray()
){

	/**
	 * 添加一节课程
	 */
	fun addLesson(lesson: Lesson){
		lessons += lesson
	}

	/**
	 * 移除一节课程
	 */
	fun removeLesson(lesson: Lesson){
		lessons = lessons.filterNot { it === lesson }.toTypedArray()
	}

	/**
	 * 移除指定位置的课程
	 */
	fun removeLesson(index: Int) {
		require(index >= 0 && index < lessons.size) { "Index out of bounds" }
		lessons = lessons.copyOfRange(0, index) + lessons.copyOfRange(index + 1, lessons.size)
	}

	/**
	 * 获取当前周会上的课程
	 * @param currentWeek 当前周（从 1 开始）
	 * @return 会上的课程, 没有课则为null
	 */
	fun getCurrentLesson(currentWeek: Int): Lesson? {
		// 掩码是 Long: 左移位数必须落在 0..62, 否则 Kotlin 会按 (n and 63) 处理而错误命中第1周
		if(currentWeek < 1 || currentWeek > 63) return null
		val bit = 1L shl (currentWeek - 1)
		for(lesson in lessons) {
			if(lesson.week and bit > 0) return lesson
		}
		return null
	}

	/**
	 * 指定的周次(从1开始)是否真的要上这门课
	 *
	 * 用于"当日课表"与桌面小组件: 不在本周上课周次内的课程(学期课表里显示为灰色的)
	 * 属于"暂时不上"的课程, 不应出现在当天课程里。
	 *
	 * @param week 周次(从1开始)
	 * @param totalWeek 学期总周数(>0 时会同时排除超出总周数的周次)
	 */
	fun hasLessonInWeek(week: Int, totalWeek: Int = 0): Boolean {
		if(week < 1 || week > 63) return false
		if(totalWeek > 0 && week > totalWeek) return false
		val bit = 1L shl (week - 1)
		return lessons.any { it.week and bit > 0 }
	}

	/**
	 * 将用户定义的课程合并到当前课程
	 *
	 * 【保存后"周4第一节"消失】原来用 `HashMap<kchID>` 做合并。教务常把**同一门课按不同
	 * 周次段拆成多条记录**(如"1-8周"与"9-16周"各一条), 它们 `kchID` 相同且落在同一个时段格子里;
	 * 用课程号当键会互相覆盖, 只剩最后一条 —— 保存后某一整段周次(如只含第4周的那条)凭空消失。
	 *
	 * 现在改为**不丢课**的合并: 本次查询到的课程**一条都不去掉重**(同一格里的多条记录全部保留),
	 * 再把"用户创建/修改过(type==1)"的课程并进来 —— 能按课程号匹配到就替换那一条, 匹配不到就追加。
	 * 保证教务返回的课程永远不会被合并吃掉。
	 *
	 * @param oldGroup 已保存的课表里同一时段的那组课程
	 */
	fun mergeUserDefinedLesson(oldGroup: LessonGroup) {
		val userLessons = oldGroup.lessons.filter { it.type == 1 }
		if(userLessons.isEmpty()) return // 没有用户创建的课程, 保持教务数据原样(不丢任何一条)
		val merged = ArrayList<Lesson>(lessons.size + userLessons.size)
		val used = BooleanArray(userLessons.size)
		// 先原样保留全部教务课程(不去重), 同一 kchID 的多条周次段记录也全部留下
		for(cur in lessons){
			// 只有课程号非空且与某条用户课程相同时才算"同一门课"; 用户手建课程 kchID 为空, 不参与匹配
			val idx = if(cur.kchID.isEmpty()) -1 else userLessons.indexOfFirst { it.kchID == cur.kchID }
			if(idx >= 0 && !used[idx]){
				used[idx] = true
				merged.add(userLessons[idx]) // 同一门课: 用用户修改过的版本替换
			}else{
				merged.add(cur)               // 教务原课: 原样保留
			}
		}
		// 用户新增的课程(教务数据里没有)追加进去
		for(i in userLessons.indices){
			if(!used[i]) merged.add(userLessons[i])
		}
		lessons = merged.toTypedArray()
	}

	/**
	 * 深拷贝(课程数组另复制一份), 用于删除课表前备份以便撤销
	 */
	fun deepCopy(): LessonGroup = LessonGroup(week, count, lessons.map { it.copy() }.toTypedArray())

	override fun equals(other: Any?): Boolean {
		if(this === other) return true
		if(javaClass != other?.javaClass) return false

		other as LessonGroup

		if(week != other.week) return false
		if(count != other.count) return false
		return lessons.contentEquals(other.lessons)
	}

	override fun hashCode(): Int {
		var result = week
		result = 31 * result + count
		result = 31 * result + lessons.contentHashCode()
		return result
	}

	companion object{
		val Empty = LessonGroup(0, 0)
	}
}
