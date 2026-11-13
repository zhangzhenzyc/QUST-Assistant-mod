/*
 * 青科助手（修改版） —— 基于开源项目 QUST-Assistant 修改而成
 *
 * 原项目版权所有 (C) 2891954521  (https://github.com/2891954521/QUST-Assistant)
 * 原始开源许可: GNU General Public License v3.0 (GPL-3.0), 许可原文见 LICENSE 文件, 保持完整未改动。
 *
 * 修改版版权所有 (C) 2026 zhangzhenzyc  (https://github.com/zhangzhenzyc)
 * 本修改版同样以 GPL-3.0 发布; 附加版权声明见 NOTICE.md —— 两份版权声明相互独立、同时生效。
 */

package com.qust.helper.utils

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object DateUtils {

	val YMD_HMS = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)

	val YMD_HM = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)

	val YMD = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)


	@Synchronized
	fun getDateString(c: Calendar): String {
		return YMD_HM.format(c.time)
	}

	/**
	 * 【本轮】"9月9日" —— 只保留月与日(不显示年份)。
	 *
	 * 用于弹窗里的日期展示: 界面上一律不出现 "2026-10-09" 这种带年份的写法。
	 * 这里直接按字符串切分 yyyy-MM-dd, 不走 SimpleDateFormat —— 共享的 SimpleDateFormat
	 * 实例不是线程安全的, 一旦解析失败旧写法会把 "2026-10-09" 原样显示出来。
	 */
	fun mdChinese(dateKey: String): String {
		val parts = dateKey.trim().split("-")
		if(parts.size == 3){
			val m = parts[1].toIntOrNull()
			val d = parts[2].take(2).toIntOrNull()
			if(m != null && d != null && m in 1..12 && d in 1..31) return "${m}月${d}日"
		}
		return dateKey
	}

	/** 【本轮】同上, 入参是 Date */
	fun mdChinese(date: Date): String {
		val c = Calendar.getInstance().also { it.time = date }
		return "${c[Calendar.MONTH] + 1}月${c[Calendar.DATE]}日"
	}

	fun getDateString(s: String): Date {
		return try {
			YMD_HM.parse(s)!!
		} catch(_: Exception) {
			Date()
		}
	}

	fun calcDayOffset(date1: Date, date2: Date): Int {
		val cal1 = Calendar.getInstance()
		cal1.time = date1
		val cal2 = Calendar.getInstance()
		cal2.time = date2
		val day1 = cal1[Calendar.DAY_OF_YEAR]
		val day2 = cal2[Calendar.DAY_OF_YEAR]
		val year1 = cal1[Calendar.YEAR]
		val year2 = cal2[Calendar.YEAR]
		return if(year1 != year2) {  //同一年
			var timeDistance = 0
			for(i in year1 until year2) {
				timeDistance += if(i % 4 == 0 && i % 100 != 0 || i % 400 == 0) {  //闰年
					366
				} else {  //不是闰年
					365
				}
			}
			timeDistance + (day2 - day1)
		} else { //不同年
			day2 - day1
		}
	}

	fun calcWeekOffset(startTime: Date, endTime: Date): Int {
		val cal = Calendar.getInstance()
		cal.time = startTime
		var dayOfWeek = cal[Calendar.DAY_OF_WEEK]
		dayOfWeek -= 1
		if(dayOfWeek == 0) dayOfWeek = 7
		val dayOffset = calcDayOffset(startTime, endTime)
		var weekOffset = dayOffset / 7
		val a: Int = if(dayOffset > 0) {
			if(dayOffset % 7 + dayOfWeek > 7) 1 else 0
		} else {
			if(dayOfWeek + dayOffset % 7 < 1) -1 else 0
		}
		weekOffset += a
		return weekOffset
	}

	@OptIn(ExperimentalSerializationApi::class)
	@Serializer(forClass = Date::class)
	object DateSerializer {
		override fun deserialize(decoder: Decoder): Date {
			val dateString = decoder.decodeString()
			return YMD.parse(dateString) ?: Date()
		}

		override fun serialize(encoder: Encoder, value: Date) {
			val dateString = YMD.format(value)
			encoder.encodeString(dateString)
		}
	}
}
