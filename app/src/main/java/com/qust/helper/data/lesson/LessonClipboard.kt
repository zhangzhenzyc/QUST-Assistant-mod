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

/**
 * 课程剪贴板(内存态, 不持久化)
 *
 * 支持"复制课程 / 粘贴课程"的方式修改课表: 复制后可在任意日期/时段粘贴,
 * 存的是深拷贝, 后续修改源课程不会影响剪贴板内容, 反之亦然。
 */
object LessonClipboard {

	private var copied: Lesson? = null

	/** 复制一节课程(深拷贝) */
	fun copy(lesson: Lesson) {
		copied = lesson.copy()
	}

	/** 取出一份课程副本(深拷贝), 无内容返回null */
	fun paste(): Lesson? = copied?.copy()

	/** 清空剪贴板 */
	fun clear() {
		copied = null
	}
}
