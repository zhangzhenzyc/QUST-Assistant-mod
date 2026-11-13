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
 * 【第一百零五轮·小组件渲染诊断】环形缓冲区
 *
 * 背景: OPPO/ColorOS 上小组件的尺寸上报(SIZES / MIN/MAX_HEIGHT / Glance LocalSize)
 * 各不可信且比例随卡片规格漂移, 远程截图 + 像素估算已经连猜四轮(v225~v228)没有收敛。
 * 把每次渲染拿到的**原始数值**记进内存, 「设置 → 桌面小组件」自检卡直接展示,
 * 用户截图发来即可看到真实数字 —— 用数据定系数, 不再盲调。
 *
 * 只存最近 [MAX] 行, 仅内存不落盘, 不参与任何业务; 敏感信息(课表内容/账号)一律不记。
 */
object WidgetDiag {

	private const val MAX = 60

	private val lines = ArrayDeque<String>()

	private val timeFmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA)

	fun clear() = lines.clear()

	/** 【第一百零五轮·v237·按要求】每行带渲染时刻(HH:mm:ss) —— 配合"刷新"按钮能看出
	 *  哪几条是刚生成的、小组件最后一次重画是什么时候。 */
	fun add(line: String){
		if(lines.size >= MAX) lines.removeFirst()
		lines.addLast("[" + timeFmt.format(java.util.Date()) + "] " + line)
	}

	fun dump(): String = lines.joinToString("\n")

	val isEmpty: Boolean get() = lines.isEmpty()
}
