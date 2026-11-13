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

/**
 * 【学业查询诊断日志】环形缓冲区
 *
 * 用途: getAcademic() 查询过程中把关键诊断行(接口A纯文本/模块识别/接口B返回/分支二兜底)
 * 存进内存; 学业查询页长按「重新查询」可弹出查看并一键复制, 便于远程排查
 * "通识任选等课程缺失"类问题, 无需 adb logcat。
 *
 * 只存最近 MAX 行(接口A 纯文本占大头), 避免内存无限增长; 仅调试用途, 不参与任何业务。
 */
object AcademicDiag {

	private const val MAX = 500

	private val lines = ArrayDeque<String>()

	fun clear() = lines.clear()

	fun add(line: String){
		if(lines.size >= MAX) lines.removeFirst()
		lines.addLast(line)
	}

	fun dump(): String = lines.joinToString("\n")

	val isEmpty: Boolean get() = lines.isEmpty()
}
