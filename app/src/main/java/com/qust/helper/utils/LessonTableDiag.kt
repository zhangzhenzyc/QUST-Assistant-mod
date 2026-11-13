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

import java.util.Calendar

/**
 * 【第二十七轮·课表查询日志】环形缓冲区
 *
 * "课表查询 → 查询(登录获取课表)"过程中把关键步骤(登录检查、请求地址与参数、HTTP 状态、响应长度、
 * 解析出的课程数、重试与错误原因)存进内存;
 * 课表查询页**长按「查询」按钮**可弹出查看并一键复制, 便于远程排查"查不出课表/课表不全"类问题。
 *
 * 只存最近 [MAX] 行, 仅内存不落盘, 不参与任何业务。
 */
object LessonTableDiag {

	private const val MAX = 400

	private val lines = ArrayDeque<String>()

	fun clear() = lines.clear()

	fun add(line: String){
		if(lines.size >= MAX) lines.removeFirst()
		lines.addLast(line)
	}

	fun dump(): String = lines.joinToString("\n")

	val isEmpty: Boolean get() = lines.isEmpty()

	/** 当前时间戳(用于日志首行), 形如 2026-10-14 19:40:15 */
	fun now(): String {
		val c = Calendar.getInstance()
		fun p(v: Int) = if(v < 10) "0$v" else "$v"
		return "${c[Calendar.YEAR]}-${p(c[Calendar.MONTH] + 1)}-${p(c[Calendar.DATE])} " +
			"${p(c[Calendar.HOUR_OF_DAY])}:${p(c[Calendar.MINUTE])}:${p(c[Calendar.SECOND])}"
	}
}
