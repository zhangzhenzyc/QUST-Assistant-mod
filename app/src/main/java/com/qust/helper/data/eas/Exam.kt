/*
 * 青科助手（修改版） —— 基于开源项目 QUST-Assistant 修改而成
 *
 * 原项目版权所有 (C) 2891954521  (https://github.com/2891954521/QUST-Assistant)
 * 原始开源许可: GNU General Public License v3.0 (GPL-3.0), 许可原文见 LICENSE 文件, 保持完整未改动。
 *
 * 修改版版权所有 (C) 2026 zhangzhenzyc  (https://github.com/zhangzhenzyc)
 * 本修改版同样以 GPL-3.0 发布; 附加版权声明见 NOTICE.md —— 两份版权声明相互独立、同时生效。
 */

package com.qust.helper.data.eas

import kotlinx.serialization.Serializable
import org.json.JSONObject


/**
 * 考试安排
 * @param name  课程名称
 * @param place 考试地点
 * @param time  考试时间
 */
@Serializable
data class Exam(
	val name: String = "",
	val place: String = "",
	val time: String = "",
) {

	companion object {
		fun createFromJson(js: JSONObject): Exam {
			// 【本轮·修复】以前把两个字段接反了: 教务返回的 `kssj` 是**考试时间**
			// (如 2026-10-25(18:00-19:40)), `cdmc` 是**场地名称**(如 重修教室1),
			// 旧代码 mapping 反了, 于是界面上"考试地点"显示成日期、"考试时间"显示成教室。
			return Exam(
				name = if(js.has("kcmc")) js.getString("kcmc") else "",
				place = if(js.has("cdmc")) js.getString("cdmc") else "",
				time = if(js.has("kssj")) js.getString("kssj") else ""
			)
		}
	}
}