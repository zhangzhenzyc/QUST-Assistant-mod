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

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.qust.helper.ui.theme.colorSecondaryText

object Texts {
	
	@Composable
	fun SingleLineText(
		text: String,
		style: TextStyle = MaterialTheme.typography.bodyMedium,
		color: Color = style.color,
		textAlign: TextAlign = TextAlign.Center,
		modifier: Modifier = Modifier
	){
		Text(
			text = text,
			modifier = modifier.padding(8.dp),
			style = style,
			color = color,
			textAlign = textAlign,
			maxLines = 1,
		)
	}

	@Composable
	fun SingleLineTextNoPadding(
		text: String,
		style: TextStyle = MaterialTheme.typography.bodyMedium,
		color: Color = style.color,
		textAlign: TextAlign = TextAlign.Center,
		modifier: Modifier = Modifier
	){
		Text(
			text = text,
			modifier = modifier,
			style = style,
			color = color,
			textAlign = textAlign,
			maxLines = 1,
		)
	}

	@Composable
	fun IconText(
		text: String,
		icon: ImageVector,
		modifier: Modifier = Modifier,
		iconModifier: Modifier = Modifier
	) {
		Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically){
			Text(
				text = text,
				style = MaterialTheme.typography.bodySmall,
				modifier = Modifier.padding(start = 8.dp),
				color = colorSecondaryText,
			)
			Icon(imageVector = icon, modifier = iconModifier, contentDescription = null, tint = colorSecondaryText)
		}
	}
}