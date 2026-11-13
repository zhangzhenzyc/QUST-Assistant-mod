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
import kotlinx.serialization.Serializable
import org.json.JSONObject

/**
 * 一节课程
 * @param type 课程类型 0: auto 自动添加的课程; 1: user 用户创建的课程
 * @param kchID 课程ID
 * @param color 课程颜色
 * @param len 课程课时
 * @param week 第几周有课, long形式的boolean数组
 * @param name 课程名称
 * @param place 课程教室
 * @param teacher 课程教师
 */
@Serializable
data class Lesson(
	var type: Int = 0,
	var kchID: String = "",
	var color: Int = 0,
	var len: Int = 1,
	var week: Long = 0L,
	var name: String = "",
	var place: String = "",
	var teacher: String = "",
){
	/**
	 * 是否为"同一门课"(比较内容而非实例)
	 *
	 * 同一节课在"每周模板"与"按日期手动覆盖"里可能是两个不同实例, 编辑时判断
	 * "冲突课程里是否包含自己"必须按内容比较, 否则会把课程自己当成冲突。
	 */
	fun isSameLessonAs(other: Lesson): Boolean =
		this === other || (name == other.name && place == other.place &&
			teacher == other.teacher && color == other.color && type == other.type)

	companion object {

		val EMPTY_LESSON = Lesson(type = 1)

		/**
		 * 从json中解析Lesson
		 *
		 * 【问题2-班级课表】班级课表返回的字段名与个人课表并不完全一致(有的没有 kch / xm,
		 * 节次叫 jc 而不是 jcs, 地点叫 jxdd 等), 原来用 getString 取不到就直接抛异常,
		 * 整条课程会变成"课程解析错误"。这里改为**多字段名兜底 + 缺字段可容忍**。
		 *
		 * @param json
		 * @return
		 */
		fun loadFromJson(json: JSONObject): Lesson {
			var lesson: Lesson
			try {
				val name = firstString(json, "kcmc", "kcbmc", "kcmc_id", "kchmc").trim()
				val place = firstString(json, "cdmc", "jxdd", "cdmc_id", "jsmc", "jash").trim()
				val teacher = firstString(json, "xm", "jsxm", "jsmc", "jsxx").trim()
				// kch 缺失时用"课程名+教师+地点"合成一个稳定ID, 避免影响后续按ID合并
				val kchId = firstString(json, "kch", "kch_id", "kchID", "kcbm")
					.ifEmpty { "$name|$teacher|$place" }
				lesson = Lesson(
					kchID = kchId,
					name = if(name.isEmpty()) "未知课程" else name,
					place = place,
					teacher = teacher,
				)

				lesson.week = parseWeekMask(
					firstString(json, "zcd", "zcmc", "zcxq", "zcd_id", "zcs", "zc", "skzc", "zcsm")
				)
			}catch(e: Exception){
				Logger.w(json.toString(), e)
				lesson = Lesson(name = "课程解析错误")
			}
			return lesson
		}

		/** 一个学期最多按多少周处理(周次位掩码长度) */
		const val MAX_WEEK_COUNT = 32

		/** 全学期每周都上课 */
		val ALL_WEEK_MASK: Long = (1L shl MAX_WEEK_COUNT) - 1L

		/**
		 * 解析"上课周次"字段(纯函数, 便于单元测试)
		 *
		 * 【班级课表只剩体育】以前只认 `1-16(单)` 这一种写法, 且用"截掉最后一个字符"的方式去掉
		 * 周标记: 一旦遇到全角括号 `1-16（周）`、或者末尾是"周"、或者带多余后缀, `toInt()` 就抛异常,
		 * 整条课程被降级成"课程解析错误"、week = 0, 最后被"去除上课周数为0的课程"**整门删掉**
		 * —— 表现就是班级课表里只剩下一两门课(如体育)。
		 *
		 * 这里按"语义"解析: 全角/半角括号都认, 末尾的"周"去掉, 数字之外的杂字符忽略。
		 * **一条周次都解析不出来时按"全学期上课"处理**, 绝不再让课程凭空消失。
		 *
		 * @return 周次位掩码(第 n 周对应第 n-1 位), 恒不为 0
		 */
		fun parseWeekMask(raw: String): Long {
			val normalized = raw
				.replace("（", "(").replace("）", ")")
				.replace("，", ",")
				.replace(" ", "")
			var mask = 0L
			for(part in normalized.split(",")) {
				var text = part.trim()
				if(text.isEmpty()) continue
				// 单双周: (单)/(双)/(周) 等
				var type = -1
				val open = text.indexOf('(')
				if(open >= 0) {
					val bracket = text.substring(open)
					text = text.substring(0, open).trim()
					type = when {
						bracket.contains("单") -> 0
						bracket.contains("双") -> 1
						else -> -1
					}
				}
				// 末尾的"周"及其它说明文字
				text = text.trimEnd('周', '周', ' ', '节')

				val nums = Regex("\\d+").findAll(text).mapNotNull { it.value.toLongOrNull() }.toList()
				if(nums.isEmpty()) continue
				if(nums.size >= 2 && text.contains("-")) {
					val start = (nums[0].toInt() - 1).coerceIn(0, MAX_WEEK_COUNT - 1)
					val end = nums[1].toInt().coerceIn(start + 1, MAX_WEEK_COUNT)
					mask = mask or weekMask(start, end, type)
				} else {
					val w = nums[0].toInt()
					if(w in 1..MAX_WEEK_COUNT) mask = mask or (1L shl (w - 1))
				}
			}
			return if(mask == 0L) ALL_WEEK_MASK else mask
		}

		/** 依次尝试多个字段名, 返回第一个非空字符串(缺失/类型不符都不抛异常) */
		private fun firstString(json: JSONObject, vararg keys: String): String {
			for(k in keys) {
				if(json.has(k) && !json.isNull(k)) {
					val v = json.optString(k, "")
					if(v.isNotEmpty()) return v
				}
			}
			return ""
		}

		/**
		 * 计算一段"上课周次"的位掩码
		 * @param start 起始周下标(从0开始)
		 * @param end 结束周(含)
		 * @param type -1 正常 0 单周 1 双周
		 */
		fun weekMask(start: Int, end: Int, type: Int): Long {
			var _start = start
			if(type == -1) {
				return ((1L shl (end - _start)) - 1L) shl _start
			}
			var mask = 0L
			// 单周: 从第一个奇数周(下标为偶数)开始; 双周: 从第一个偶数周(下标为奇数)开始
			if((type == 0 && _start % 2 == 1) || (type == 1 && _start % 2 == 0)) _start += 1
			var i = _start
			while(i < end) {
				mask = mask or (1L shl i)
				i += 2
			}
			return mask
		}

	}
}
