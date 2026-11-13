/*
 * 青科助手（修改版） —— 基于开源项目 QUST-Assistant 修改而成
 *
 * 原项目版权所有 (C) 2891954521  (https://github.com/2891954521/QUST-Assistant)
 * 原始开源许可: GNU General Public License v3.0 (GPL-3.0), 许可原文见 LICENSE 文件, 保持完整未改动。
 *
 * 修改版版权所有 (C) 2026 zhangzhenzyc  (https://github.com/zhangzhenzyc)
 * 本修改版同样以 GPL-3.0 发布; 附加版权声明见 NOTICE.md —— 两份版权声明相互独立、同时生效。
 */

package com.qust.helper.ui.appwidget

/**
 * 【第六十八轮·按要求】两个课表小组件共用的「字号范围」。
 *
 * 用户原话: "本周课表和今日课表小组件也尝试采取学期课表通过设置字号范围并且改变字体大小
 * 使课程信息尽可能完全展现的方法"。
 *
 * 软件内「学期课表」的做法(`LessonRenderData.textScale`)是:
 * 以 12sp 为基准, 从 **1.00 倍开始按 0.06 一档往下试**, 直到"课名 + 教室(+ 教师)"的总行数
 * 能被这一格装下为止, **下限 0.72 倍**。所以软件里不会出现"地点/教师整片消失" ——
 * 它只是把字缩小了一点, 信息一直在。
 *
 * 小组件这边以前是反过来的: 字号**定死**, 然后用"行数预算"决定要不要画地点/教师 ——
 * 行数不够就直接不画, 字一点没小, 格子里反而空着半行。这个类就是把"范围"这件事固定下来,
 * 两个小组件都按同一套区间去挑字号。
 *
 * 数值和软件内保持一致(1.00 ~ 0.72), 另外给一个绝对下限 [MIN_SP]:
 * 少数极端情况(12 节 + 矮卡)基准字号本身只有 5sp 出头, 再乘 0.72 就没法看了,
 * 这时宁可少显示一行, 也不要把字缩成一条灰线。
 */
object WidgetFontRange {

	/**
	 * 缩放下限。
	 *
	 * 原本和软件内「学期课表」的 0.72 完全一致;【第九十二轮】0.72 -> **0.62**:
	 * 用户实测 5×9 本周课表上 16 字长课名("习近平新时代中国特色社会主义思想概论")仍出省略号 ——
	 * 这类课名在 6.9sp 基准字号下要折 3 行, cap 扣掉地点/教师后连 0.72 档都排不进预算,
	 * 必须再多给几档"更小但完整"的选择。`largest()` 从大往小挑, 已经放得下的格子
	 * 不会受影响(仍然选它能用的最大字号), 下限只决定"最后实在塞不下时缩到多小"。
	 */
	const val MIN_RATIO = 0.62F

	/** 小组件里**绝对**不再小于这个字号(sp) */
	const val MIN_SP = 4.6F

	/** 逐档试算的步长(相对基准字号的比例) —— 软件内是 0.06, 这里略细一点, 更容易卡到"刚好好" */
	const val STEP_RATIO = 0.04F

	/**
	 * 给定基准字号, 返回允许的区间 `下限 .. 基准`。
	 *
	 * 注意上限**就是基准字号本身**: 基准字号在 `TermLesson.metricsOf` 里已经被"格高"夹过
	 * (3 行必须放得下), 所以它已经是"这一帧能用的最大字号", 不能再往上加。
	 */
	fun around(base: Float): ClosedFloatingPointRange<Float> {
		val max = base.coerceAtLeast(MIN_SP)
		val min = (base * MIN_RATIO).coerceAtLeast(MIN_SP).coerceAtMost(max)
		return min..max
	}

	/**
	 * 从区间上限往下逐档找, 返回**第一个**满足 [ok] 的字号; 都不满足返回 null。
	 *
	 * 为什么是"从大到小找第一个"而不是"取最小": 字号越小越容易塞下, 取最小等于永远选最糊的那档;
	 * 从大往小找第一个可行解, 才是"在放得下的前提下尽量大"。
	 */
	inline fun largest(range: ClosedFloatingPointRange<Float>, step: Float, ok: (Float) -> Boolean): Float? {
		var font = range.endInclusive
		val stepSize = step.coerceAtLeast(0.05F)
		while(font >= range.start - 1e-4F) {
			if(ok(font)) return font
			font -= stepSize
		}
		return null
	}

	/**
	 * 一段文字在给定字号下折成几行 —— 【第七十轮】改成 Paint **逐字精确测量**。
	 *
	 * 旧版按"每个字 = 1em"估: 中文确实约 1em, 但(a)每行还差半个字的余量没扣、(b)英文/数字
	 * 比例字体宽窄不一、(c)渲染端(TextView)的换行点与"长度/字号"的整除结果对不上。
	 * 三种误差都朝"少估一行"的方向偏, 于是"网络数据开发技术"这种 8 字课名在基准字号下
	 * 被判成"2 行放得下" -> 字号不缩 -> 实际渲染要 2.2 行 -> maxLines 掐掉尾巴出现省略号。
	 *
	 * Glance 的 Text 最终就是 RemoteViews 里的 TextView, TextView 换行用的同样是
	 * Paint.measureText 系度量 —— 用 Paint.breakText 按同样的宽度逐行断字, 结果和渲染一致
	 * (±1 个字的抗锯齿差, 不会跨行)。
	 *
	 * 单位说明: [font] 与 [width] 一个是 sp 一个是 dp, 但都乘同一个 density 才变成像素 ——
	 * 直接拿 sp 当 px 字号、dp 当 px 行宽, 比值与真实渲染完全相同。
	 * 线程: 组合可能并发(两张卡各自的 Recomposer), 共享 Paint 要上锁。
	 */
	private val measurePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

	fun lineCount(text: String, font: Float, width: Float, maxLines: Int): Int {
		if(text.isEmpty()) return 0
		if(width <= 0F || font <= 0F) return maxLines.coerceAtLeast(1)
		synchronized(measurePaint) {
			measurePaint.textSize = font.coerceAtLeast(0.1F)
			var lines = 1
			var pos = 0
			val n = text.length
			while(pos < n && lines < maxLines) {
				val count = measurePaint.breakText(text, pos, n, true, width, null)
				// 一个字都放不下(宽度异常小): 按"每行一个字"推进, 防死循环
				if(count <= 0) { lines++; pos++ }
				else {
					pos += count
					if(pos < n) lines++
				}
			}
			// 还有剩余字 -> 就是 maxLines 行(封顶)
			return lines.coerceIn(1, maxLines)
		}
	}

	/**
	 * 同 [lineCount], 但**不封顶**(传 maxLines 后由调用方自己判断"放不放得下")。
	 * 【第七十轮】给 [com.qust.helper.ui.appwidget.TermLesson] 的 nameFits 用:
	 * 旧版 `字数 ≤ 每行估字数 × 最大行数` 的乘法判据在边界上(整除附近)会误判"放得下",
	 * 直接数真实行数和 maxNameLines 比大小, 边界情形宁可多缩一档字号 ——
	 * "字小一点但完整"比"字大一点被省略号吃掉"好(用户原话: 要让他显示完全)。
	 */
	fun rawLineCount(text: String, font: Float, width: Float): Int {
		if(text.isEmpty()) return 0
		if(width <= 0F || font <= 0F) return Int.MAX_VALUE
		synchronized(measurePaint) {
			measurePaint.textSize = font.coerceAtLeast(0.1F)
			var lines = 1
			var pos = 0
			val n = text.length
			while(pos < n) {
				val count = measurePaint.breakText(text, pos, n, true, width, null)
				if(count <= 0) { lines++; pos++ }
				else {
					pos += count
					if(pos < n) lines++
				}
			}
			return lines
		}
	}
}
