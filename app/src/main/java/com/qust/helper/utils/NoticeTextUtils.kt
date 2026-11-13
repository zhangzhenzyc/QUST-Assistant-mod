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
 * 【本期】教务通知原文的**展示去重**。
 *
 * 现象: 「教务通知调课」弹窗里同一句通知出现两遍, 两行的发布时间一模一样,
 * 正文只差一两个字(例如 "Java语言设计课程" / "Java语言课程")。
 *
 * 原因不在抓取: 教务对同一次调课确实下发了**两条通知**, 取课程名的字段不同(教学班名/课程名),
 * 发布时间精确到秒都相同。解析(parseEASNotice)那边按"日期|节次|类型|课程名"合并,
 * 两条会被当成两门课; 而"原文"是直接按行展示的, 于是看起来就是重复。
 *
 * 这里按"发布时间相同 + 正文数字序列相同 + 正文高度相似"判为同一条, 只保留信息更全(更长)的那条。
 * 必须同时满足三个条件, 避免把**同一批发布**的两条真正不同的调课(节次/周次不同 -> 数字序列不同)误合并。
 */
object NoticeTextUtils {

	private val DIGITS = Regex("\\d+")

	/** 去掉所有空白: 中文正文里空格/换行不影响语义, 比较时应忽略 */
	fun normalize(text: String): String = text.filterNot { it.isWhitespace() }

	/** 提取正文里的全部数字(周次、节次、日期、房间号等), 用于"是不是同一件事"的粗判定 */
	fun digitsOf(text: String): String = DIGITS.findAll(text).joinToString(",") { it.value }

	/** 行的发布时间(形如 `2026-10-27 14:22:44`), 取不到时返回空串 */
	fun timeOf(line: String): String {
		if(!line.startsWith("[")) return ""
		return line.substringAfter("[").substringBefore("]").trim()
	}

	/** 行的正文(去掉 `[时间] ` 前缀) */
	fun bodyOf(line: String): String {
		if(!line.startsWith("[")) return line.trim()
		return line.substringAfter("]").trim()
	}

	/**
	 * 两段文本的相似度: 最长公共子序列长度 / 较短串长度, 取值 0..1。
	 * 用 LCS 而不是"逐位比较", 因为两条通知可能只在一个词上多/少两个字, 位置会错开。
	 */
	fun similarity(a: String, b: String): Double {
		if(a.isEmpty() || b.isEmpty()) return 0.0
		if(a == b) return 1.0
		val n = a.length
		val m = b.length
		var prev = IntArray(m + 1)
		var cur = IntArray(m + 1)
		for(i in 1..n) {
			for(j in 1..m) {
				cur[j] = if(a[i - 1] == b[j - 1]) prev[j - 1] + 1 else maxOf(prev[j], cur[j - 1])
			}
			val tmp = prev
			prev = cur
			cur = tmp
			java.util.Arrays.fill(cur, 0)
		}
		return prev[m].toDouble() / minOf(n, m)
	}

	/**
	 * 按"同一发布时间 + 同一批数字 + 高度相似"去重, 保留信息更全的那条。
	 *
	 * @param lines 形如 `[2026-10-27 14:22:44] 正文` 的行
	 * @param threshold 相似度阈值(默认 0.95: 只吃掉"措辞几乎一样"的重复)
	 */
	fun dedupeNotices(lines: List<String>, threshold: Double = 0.95): List<String> {
		val kept = ArrayList<String>()
		val keptTime = ArrayList<String>()
		val keptDigits = ArrayList<String>()
		val keptBody = ArrayList<String>()

		for(line in lines) {
			if(line.isBlank()) continue
			val time = timeOf(line)
			val body = normalize(bodyOf(line))
			val digits = digitsOf(body)
			var mergedInto = -1
			for(i in kept.indices) {
				if(keptTime[i] != time) continue
				if(keptDigits[i] != digits) continue
				val minLen = minOf(keptBody[i].length, body.length)
				val maxLen = maxOf(keptBody[i].length, body.length)
				// 长度差太多的一定不是同一条(防止"短通知"被长通知整段包含而误判相似)
				if(maxLen > 0 && minLen.toDouble() / maxLen < 0.8) continue
				if(similarity(keptBody[i], body) >= threshold) {
					mergedInto = i
					break
				}
			}
			if(mergedInto >= 0) {
				// 保留更长的那条: 措辞更完整(例如课程名带"设计"两字)
				if(line.length > kept[mergedInto].length) {
					kept[mergedInto] = line
					keptBody[mergedInto] = body
				}
			}else{
				kept.add(line)
				keptTime.add(time)
				keptDigits.add(digits)
				keptBody.add(body)
			}
		}
		return kept
	}
}
