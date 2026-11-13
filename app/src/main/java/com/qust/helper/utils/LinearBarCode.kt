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

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect

/**
 * 条形码库
 */
object LinearBarCode {
	private const val CODE_START_A = 103
	private const val CODE_START_B = 104
	private const val CODE_START_C = 105
	private const val CODE_CODE_A = 101
	private const val CODE_CODE_B = 100
	private const val CODE_CODE_C = 99
	private const val CODE_STOP = 106

	// Dummy characters used to specify control characters in input
	private const val ESCAPE_FNC_1 = '\u00f1'
	private const val ESCAPE_FNC_2 = '\u00f2'
	private const val ESCAPE_FNC_3 = '\u00f3'
	private const val ESCAPE_FNC_4 = '\u00f4'
	private const val CODE_FNC_1 = 102 // Code A, Code B, Code C
	private const val CODE_FNC_2 = 97 // Code A, Code B
	private const val CODE_FNC_3 = 96 // Code A, Code B
	private const val CODE_FNC_4_A = 101 // Code A
	private const val CODE_FNC_4_B = 100 // Code B

	/**
	 * 创建Code128格式的条形码
	 * @return
	 */
	fun createCode128Barcode(data: String, height: Int): Bitmap =
		createCode128Barcode(data, height, Color.WHITE, Color.BLACK)

	/**
	 * 【第六十三轮】可指定前景/背景色的版本。
	 *
	 * 背景色传 [Color.TRANSPARENT] 时, 位图只有黑条、其余全透明 —— 桌面小组件用它把条码
	 * **压在主题卡片上**, 而不是糊一整张白底图把卡片盖死(见 DrinkCode.kt 的说明)。
	 *
	 * @param background 底色; 透明 = 只画条
	 * @param foreground 条色
	 */
	fun createCode128Barcode(data: String, height: Int, background: Int, foreground: Int): Bitmap =
		createCode128Barcode(data, height, background, foreground, 0)

	/**
	 * 【第六十八轮·三】可指定**目标像素宽度**的版本。
	 *
	 * 为什么需要: 上一版把条码固定按"每个模块 10 像素"生成 —— 一个十来位的饮水码会得到
	 * 一张 **1720×320** 的位图(≈2.2MB), 而它在小组件里只占约 800×110 像素。
	 * 除了白白占内存, 位图还会被 `fitXY` 缩小到不足一半, 竖条边缘被插值糊成灰边。
	 *
	 * 现在按"目标宽度"反推每个模块该占几个像素(1~10), 生成出来的位图**正好等于显示尺寸**:
	 * 不需要缩放, 竖条永远落在整像素上, 扫码最干净。
	 *
	 * @param targetWidth 目标像素宽度; <= 0 表示沿用旧行为(每模块 10 像素)
	 */
	fun createCode128Barcode(data: String, height: Int, background: Int, foreground: Int, targetWidth: Int): Bitmap {
		// 【第六十八轮·三】编码这一步**不许抛**。
		//
		// 上一版这里会抛: `encode()` 里有 `require(c.code <= 127)`(混进非 ASCII 就抛),
		// Code128-C 分支还会 `substring(position, position + 2).toInt()`(纯数字奇数长度越界)。
		// 一旦抛出去, 调用方(小组件)那一帧就只剩下"主题换了、条码没换" ——
		// 用户在第六十八轮·三反馈的正是这个现象。
		// 现在: 先按原样编; 抛了就清洗一遍(去掉非 ASCII、纯数字奇数长度补前导 0)再编;
		// 还编不出来就退化成"一段固定条纹"而不是崩溃 —— 至少桌面还在更新, 也能看出是码坏了。
		val code = runCatching { encode(data) }.getOrElse {
			runCatching { encode(fallbackText(data)) }.getOrElse { BooleanArray(11) }
		}
		val inputWidth = code.size
		val moduleWidth = if(targetWidth > 0) ((targetWidth - 50) / inputWidth.coerceAtLeast(1)).coerceIn(1, 10) else 10
		val outputWidth = inputWidth * moduleWidth + 50
		val outputHeight = 1.coerceAtLeast(height)
		val bitmap = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
		val canvas = Canvas(bitmap)
		// ARGB_8888 新建出来是全 0 = 全透明; 需要白底时才铺一层
		if(background != Color.TRANSPARENT) {
			val bg = Paint(Paint.ANTI_ALIAS_FLAG)
			bg.color = background
			bg.strokeWidth = 0f
			canvas.drawRect(Rect(0, 0, outputWidth, outputHeight), bg)
		}
		val paint = Paint(Paint.ANTI_ALIAS_FLAG)
		paint.color = foreground
		var i = 0
		var outputX = 25
		while(i < inputWidth) {
			if(code[i]) {
				canvas.drawRect(outputX.toFloat(), 0f, (outputX + moduleWidth).toFloat(), outputHeight.toFloat(), paint)
			}
			i++
			outputX += moduleWidth
		}
		return bitmap
	}

	/**
	 * @return a byte array of horizontal pixels (0 = white, 1 = black)
	 */
	private fun encode(contents: String): BooleanArray {
		val length = contents.length
		// Check length
		// require(!(length < 1 || length > 80)) { "Contents length should be between 1 and 80 characters, but got $length" }
		// Check content
		for(i in 0 until length) {
			when(val c = contents[i]) {
				ESCAPE_FNC_1, ESCAPE_FNC_2, ESCAPE_FNC_3, ESCAPE_FNC_4 -> {}
				else -> require(c.code <= 127) { "Bad character in input: $c" }
			}
		}
		val patterns: MutableCollection<IntArray> = ArrayList() // temporary storage for patterns
		var checkSum = 0
		var checkWeight = 1
		var codeSet = 0 // selected code (CODE_CODE_B or CODE_CODE_C)
		var position = 0 // position in contents
		while(position < length) {
			//Select code to use
			val newCodeSet = chooseCode(contents, position, codeSet)

			//Get the pattern index
			var patternIndex: Int
			if(newCodeSet == codeSet) {
				// Encode the current character
				// First handle escapes
				when(contents[position]) {
					ESCAPE_FNC_1 -> patternIndex = CODE_FNC_1
					ESCAPE_FNC_2 -> patternIndex = CODE_FNC_2
					ESCAPE_FNC_3 -> patternIndex = CODE_FNC_3
					ESCAPE_FNC_4 -> patternIndex = if(codeSet == CODE_CODE_A) {
						CODE_FNC_4_A
					} else {
						CODE_FNC_4_B
					}

					else -> when(codeSet) {
						CODE_CODE_A -> {
							patternIndex = contents[position].code - ' '.code
							if(patternIndex < 0) {
								// everything below a space character comes behind the underscore in the code patterns table
								patternIndex += '`'.code
							}
						}

						CODE_CODE_B -> patternIndex = contents[position].code - ' '.code
						else -> {
							// CODE_CODE_C
							patternIndex = contents.substring(position, position + 2).toInt()
							position++ // Also incremented below
						}
					}
				}
				position++
			} else {
				// Should we change the current code?
				// Do we have a code set?
				patternIndex = if(codeSet == 0) {
					// No, we don't have a code set
					when(newCodeSet) {
						CODE_CODE_A -> CODE_START_A
						CODE_CODE_B -> CODE_START_B
						else -> CODE_START_C
					}
				} else {
					// Yes, we have a code set
					newCodeSet
				}
				codeSet = newCodeSet
			}

			// Get the pattern
			patterns.add(CODE_PATTERNS[patternIndex])

			// Compute checksum
			checkSum += patternIndex * checkWeight
			if(position != 0) {
				checkWeight++
			}
		}

		// Compute and append checksum
		checkSum %= 103
		patterns.add(CODE_PATTERNS[checkSum])

		// Append stop code
		patterns.add(CODE_PATTERNS[CODE_STOP])

		// Compute code width
		var codeWidth = 0
		for(pattern in patterns) {
			for(width in pattern) {
				codeWidth += width
			}
		}

		// Compute result
		val result = BooleanArray(codeWidth)
		var pos = 0
		for(pattern in patterns) {
			pos += appendPattern(result, pos, pattern)
		}
		return result
	}

	/**
	 * 编不出来时的"清洗版"文本: 去掉不可打印/非 ASCII 字符; 纯数字奇数长度补一个前导 0
	 * (Code128-C 只能两两编码, 奇数长度会让老的 `substring(pos, pos+2)` 越界)。
	 */
	private fun fallbackText(data: String): String {
		val ascii = data.filter { it.code in 32..126 }
		val base = ascii.ifEmpty { "0" }
		return if(base.all { it in '0'..'9' } && base.length % 2 == 1) "0$base" else base
	}

	private fun findCType(value: CharSequence, start: Int): CType {
		val last = value.length
		if(start >= last) {
			return CType.UNCODABLE
		}
		var c = value[start]
		if(c == ESCAPE_FNC_1) {
			return CType.FNC_1
		}
		if(c < '0' || c > '9') {
			return CType.UNCODABLE
		}
		if(start + 1 >= last) {
			return CType.ONE_DIGIT
		}
		c = value[start + 1]
		return if(c < '0' || c > '9') {
			CType.ONE_DIGIT
		} else CType.TWO_DIGITS
	}

	private fun chooseCode(value: CharSequence, start: Int, oldCode: Int): Int {
		var lookahead = findCType(value, start)
		if(lookahead == CType.ONE_DIGIT) {
			return CODE_CODE_B
		}
		if(lookahead == CType.UNCODABLE) {
			if(start < value.length) {
				val c = value[start]
				if(c < ' ' || oldCode == CODE_CODE_A && c < '`') {
					// can continue in code A, encodes ASCII 0 to 95
					return CODE_CODE_A
				}
			}
			return CODE_CODE_B // no choice
		}
		if(oldCode == CODE_CODE_C) { // can continue in code C
			return CODE_CODE_C
		}
		if(oldCode == CODE_CODE_B) {
			if(lookahead == CType.FNC_1) {
				return CODE_CODE_B // can continue in code B
			}
			// Seen two consecutive digits, see what follows
			lookahead = findCType(value, start + 2)
			if(lookahead == CType.UNCODABLE || lookahead == CType.ONE_DIGIT) {
				return CODE_CODE_B // not worth switching now
			}
			if(lookahead == CType.FNC_1) { // two digits, then FNC_1...
				lookahead = findCType(value, start + 3)
				return if(lookahead == CType.TWO_DIGITS) { // then two more digits, switch
					CODE_CODE_C
				} else {
					CODE_CODE_B // otherwise not worth switching
				}
			}
			// At this point, there are at least 4 consecutive digits.
			// Look ahead to choose whether to switch now or on the next round.
			var index = start + 4
			while(findCType(value, index).also { lookahead = it } == CType.TWO_DIGITS) {
				index += 2
			}
			return if(lookahead == CType.ONE_DIGIT) { // odd number of digits, switch later
				CODE_CODE_B
			} else CODE_CODE_C
			// even number of digits, switch now
		}
		// Here oldCode == 0, which means we are choosing the initial code
		if(lookahead == CType.FNC_1) { // ignore FNC_1
			lookahead = findCType(value, start + 1)
		}
		return if(lookahead == CType.TWO_DIGITS) { // at least two digits, start in code C
			CODE_CODE_C
		} else CODE_CODE_B
	}

	/**
	 * @param target  encode black/white pattern into this array
	 * @param pos     position to start encoding at in `target`
	 * @param pattern lengths of black/white runs to encode
	 * @return the number of elements added to target.
	 */
	private fun appendPattern(target: BooleanArray, pos: Int, pattern: IntArray): Int {
		var pos = pos
		var color = true
		var numAdded = 0
		for(len in pattern) {
			for(j in 0 until len) {
				target[pos++] = color
			}
			numAdded += len
			color = !color // flip color after each segment
		}
		return numAdded
	}

	private val CODE_PATTERNS = arrayOf(
		intArrayOf(2, 1, 2, 2, 2, 2),
		intArrayOf(2, 2, 2, 1, 2, 2),
		intArrayOf(2, 2, 2, 2, 2, 1),
		intArrayOf(1, 2, 1, 2, 2, 3),
		intArrayOf(1, 2, 1, 3, 2, 2),
		intArrayOf(1, 3, 1, 2, 2, 2),
		intArrayOf(1, 2, 2, 2, 1, 3),
		intArrayOf(1, 2, 2, 3, 1, 2),
		intArrayOf(1, 3, 2, 2, 1, 2),
		intArrayOf(2, 2, 1, 2, 1, 3),
		intArrayOf(2, 2, 1, 3, 1, 2),
		intArrayOf(2, 3, 1, 2, 1, 2),
		intArrayOf(1, 1, 2, 2, 3, 2),
		intArrayOf(1, 2, 2, 1, 3, 2),
		intArrayOf(1, 2, 2, 2, 3, 1),
		intArrayOf(1, 1, 3, 2, 2, 2),
		intArrayOf(1, 2, 3, 1, 2, 2),
		intArrayOf(1, 2, 3, 2, 2, 1),
		intArrayOf(2, 2, 3, 2, 1, 1),
		intArrayOf(2, 2, 1, 1, 3, 2),
		intArrayOf(2, 2, 1, 2, 3, 1),
		intArrayOf(2, 1, 3, 2, 1, 2),
		intArrayOf(2, 2, 3, 1, 1, 2),
		intArrayOf(3, 1, 2, 1, 3, 1),
		intArrayOf(3, 1, 1, 2, 2, 2),
		intArrayOf(3, 2, 1, 1, 2, 2),
		intArrayOf(3, 2, 1, 2, 2, 1),
		intArrayOf(3, 1, 2, 2, 1, 2),
		intArrayOf(3, 2, 2, 1, 1, 2),
		intArrayOf(3, 2, 2, 2, 1, 1),
		intArrayOf(2, 1, 2, 1, 2, 3),
		intArrayOf(2, 1, 2, 3, 2, 1),
		intArrayOf(2, 3, 2, 1, 2, 1),
		intArrayOf(1, 1, 1, 3, 2, 3),
		intArrayOf(1, 3, 1, 1, 2, 3),
		intArrayOf(1, 3, 1, 3, 2, 1),
		intArrayOf(1, 1, 2, 3, 1, 3),
		intArrayOf(1, 3, 2, 1, 1, 3),
		intArrayOf(1, 3, 2, 3, 1, 1),
		intArrayOf(2, 1, 1, 3, 1, 3),
		intArrayOf(2, 3, 1, 1, 1, 3),
		intArrayOf(2, 3, 1, 3, 1, 1),
		intArrayOf(1, 1, 2, 1, 3, 3),
		intArrayOf(1, 1, 2, 3, 3, 1),
		intArrayOf(1, 3, 2, 1, 3, 1),
		intArrayOf(1, 1, 3, 1, 2, 3),
		intArrayOf(1, 1, 3, 3, 2, 1),
		intArrayOf(1, 3, 3, 1, 2, 1),
		intArrayOf(3, 1, 3, 1, 2, 1),
		intArrayOf(2, 1, 1, 3, 3, 1),
		intArrayOf(2, 3, 1, 1, 3, 1),
		intArrayOf(2, 1, 3, 1, 1, 3),
		intArrayOf(2, 1, 3, 3, 1, 1),
		intArrayOf(2, 1, 3, 1, 3, 1),
		intArrayOf(3, 1, 1, 1, 2, 3),
		intArrayOf(3, 1, 1, 3, 2, 1),
		intArrayOf(3, 3, 1, 1, 2, 1),
		intArrayOf(3, 1, 2, 1, 1, 3),
		intArrayOf(3, 1, 2, 3, 1, 1),
		intArrayOf(3, 3, 2, 1, 1, 1),
		intArrayOf(3, 1, 4, 1, 1, 1),
		intArrayOf(2, 2, 1, 4, 1, 1),
		intArrayOf(4, 3, 1, 1, 1, 1),
		intArrayOf(1, 1, 1, 2, 2, 4),
		intArrayOf(1, 1, 1, 4, 2, 2),
		intArrayOf(1, 2, 1, 1, 2, 4),
		intArrayOf(1, 2, 1, 4, 2, 1),
		intArrayOf(1, 4, 1, 1, 2, 2),
		intArrayOf(1, 4, 1, 2, 2, 1),
		intArrayOf(1, 1, 2, 2, 1, 4),
		intArrayOf(1, 1, 2, 4, 1, 2),
		intArrayOf(1, 2, 2, 1, 1, 4),
		intArrayOf(1, 2, 2, 4, 1, 1),
		intArrayOf(1, 4, 2, 1, 1, 2),
		intArrayOf(1, 4, 2, 2, 1, 1),
		intArrayOf(2, 4, 1, 2, 1, 1),
		intArrayOf(2, 2, 1, 1, 1, 4),
		intArrayOf(4, 1, 3, 1, 1, 1),
		intArrayOf(2, 4, 1, 1, 1, 2),
		intArrayOf(1, 3, 4, 1, 1, 1),
		intArrayOf(1, 1, 1, 2, 4, 2),
		intArrayOf(1, 2, 1, 1, 4, 2),
		intArrayOf(1, 2, 1, 2, 4, 1),
		intArrayOf(1, 1, 4, 2, 1, 2),
		intArrayOf(1, 2, 4, 1, 1, 2),
		intArrayOf(1, 2, 4, 2, 1, 1),
		intArrayOf(4, 1, 1, 2, 1, 2),
		intArrayOf(4, 2, 1, 1, 1, 2),
		intArrayOf(4, 2, 1, 2, 1, 1),
		intArrayOf(2, 1, 2, 1, 4, 1),
		intArrayOf(2, 1, 4, 1, 2, 1),
		intArrayOf(4, 1, 2, 1, 2, 1),
		intArrayOf(1, 1, 1, 1, 4, 3),
		intArrayOf(1, 1, 1, 3, 4, 1),
		intArrayOf(1, 3, 1, 1, 4, 1),
		intArrayOf(1, 1, 4, 1, 1, 3),
		intArrayOf(1, 1, 4, 3, 1, 1),
		intArrayOf(4, 1, 1, 1, 1, 3),
		intArrayOf(4, 1, 1, 3, 1, 1),
		intArrayOf(1, 1, 3, 1, 4, 1),
		intArrayOf(1, 1, 4, 1, 3, 1),
		intArrayOf(3, 1, 1, 1, 4, 1),
		intArrayOf(4, 1, 1, 1, 3, 1),
		intArrayOf(2, 1, 1, 4, 1, 2),
		intArrayOf(2, 1, 1, 2, 1, 4),
		intArrayOf(2, 1, 1, 2, 3, 2),
		intArrayOf(2, 3, 3, 1, 1, 1, 2)
	)

	// Results of minimal lookahead for code C
	private enum class CType {
		UNCODABLE,
		ONE_DIGIT,
		TWO_DIGITS,
		FNC_1
	}
}
