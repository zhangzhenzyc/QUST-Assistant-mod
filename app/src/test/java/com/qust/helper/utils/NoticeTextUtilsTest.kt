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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 【本期】教务通知原文去重(「教务通知调课」弹窗里同一句通知出现两遍)。
 *
 * 真实样本来自用户截图: 两条通知发布时间连秒都相同, 正文只差"设计"两个字。
 */
class NoticeTextUtilsTest {

	private val n1 = "[2026-10-27 14:22:44] 调课提醒:靳庆庆老师于第9周星期五第1-2节在D2-613上的Java语言设计课程调课到由靳庆庆老师在第17周星期二第1-2节弘E202上课，请各位同学相互告知！"

	private val n1Short = "[2026-10-27 14:22:44] 调课提醒:靳庆庆老师于第9周星期五第1-2节在D2-613上的Java语言课程调课到由靳庆庆老师在第17周星期二第1-2节弘E202上课，请各位同学相互告知！"

	@Test
	fun dedupe_sameTimeOnlyCourseNameDiffers_keepsLongerOne() {
		val out = NoticeTextUtils.dedupeNotices(listOf(n1, n1Short))
		assertEquals(1, out.size)
		// 保留信息更全的那条(带"设计")
		assertEquals(n1, out[0])
	}

	@Test
	fun dedupe_sameTimeDifferentSlots_keepsBoth() {
		// 同一批发布(时间戳相同), 但节次不同: 是两件事, 不能合并
		val other = "[2026-10-27 14:22:44] 调课提醒:靳庆庆老师于第30周星期五第3-4节在D2-613上的Java语言课程调课到由靳庆庆老师在第41周星期二第5-6节弘E202上课，请各位同学相互告知！"
		val out = NoticeTextUtils.dedupeNotices(listOf(n1, other))
		assertEquals(2, out.size)
	}

	@Test
	fun dedupe_differentTimeSameBody_keepsBoth() {
		// 发布时间不同 -> 不改历史行为(可能是教务补发/更正), 两条都保留
		val later = n1.replace("[2026-10-27 14:22:44]", "[2026-10-28 09:00:00]")
		val out = NoticeTextUtils.dedupeNotices(listOf(n1, later))
		assertEquals(2, out.size)
	}

	@Test
	fun dedupe_duplicateLine_keepsOne() {
		val out = NoticeTextUtils.dedupeNotices(listOf(n1, n1))
		assertEquals(1, out.size)
	}

	@Test
	fun similarity_identicalIsOne() {
		assertEquals(1.0, NoticeTextUtils.similarity("调课提醒", "调课提醒"), 0.0001)
	}

	@Test
	fun similarity_isHighForOneWordDiff() {
		val a = NoticeTextUtils.normalize("Java语言设计课程调课到")
		val b = NoticeTextUtils.normalize("Java语言课程调课到")
		assertTrue(NoticeTextUtils.similarity(a, b) > 0.9)
	}

	@Test
	fun digitsOf_extractsAllNumbers() {
		assertEquals("9,1,2,2,613,17,1,2,202", NoticeTextUtils.digitsOf("第9周星期五第1-2节在D2-613上的第17周第1-2节202"))
	}

	@Test
	fun timeOf_and_bodyOf_parsePrefixedLine() {
		assertEquals("2026-10-27 14:22:44", NoticeTextUtils.timeOf(n1))
		assertTrue(NoticeTextUtils.bodyOf(n1).startsWith("调课提醒:"))
	}
}
