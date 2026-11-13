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

import java.util.regex.Pattern

object CodeUtils {

	fun byteToHexString(byteArray: ByteArray): String {
		val hexString = StringBuilder()
		for(b in byteArray) {
			val hex = Integer.toHexString(0xFF and b.toInt())
			if(hex.length == 1) hexString.append('0')
			hexString.append(hex)
		}
		return hexString.toString()
	}

	fun matcher(pattern: Pattern, string: String, index: Int = 1): String?{
		val matcher = pattern.matcher(string)
		return if(matcher.find()) matcher.group(index) else null
	}
}
