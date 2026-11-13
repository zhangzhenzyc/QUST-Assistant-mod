/*
 * 青科助手（修改版） —— 基于开源项目 QUST-Assistant 修改而成
 *
 * 原项目版权所有 (C) 2891954521  (https://github.com/2891954521/QUST-Assistant)
 * 原始开源许可: GNU General Public License v3.0 (GPL-3.0), 许可原文见 LICENSE 文件, 保持完整未改动。
 *
 * 修改版版权所有 (C) 2026 zhangzhenzyc  (https://github.com/zhangzhenzyc)
 * 本修改版同样以 GPL-3.0 发布; 附加版权声明见 NOTICE.md —— 两份版权声明相互独立、同时生效。
 */

package com.qust.helper.ui.widget

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.qust.helper.ui.theme.colorSuccess

/**
 * 有三种颜色的ProgressBar
 */
object TripleProgressBar {

	@Composable
	fun TripleProgressBar(
		redValue: Float = 0F,
		greenValue: Float = 0F,
		height: Dp = 5.dp,
		redColor: Color = MaterialTheme.colorScheme.error,
		greenColor: Color = colorSuccess,
	) {
		Canvas(modifier = Modifier.fillMaxWidth().height(height)) {
			drawRoundRect(
				color = Color.Gray,
				topLeft = Offset(0F, 0F),
				size = Size(size.width, size.height),
				cornerRadius = CornerRadius(size.height / 2F),
			)
			if(redValue > 0F) {
				drawRoundRect(
					color = redColor,
					topLeft = Offset(0F, 0F),
					size = Size(size.width * redValue, size.height),
					cornerRadius = CornerRadius(size.height / 2F),
				)
			}
			if(greenValue > 0F) {
				drawRoundRect(
					color = greenColor,
					topLeft = Offset(0F, 0F),
					size = Size(size.width * greenValue, size.height),
					cornerRadius = CornerRadius(size.height / 2F),
				)
			}
		}
	}
}