/*
 * 青科助手（修改版） —— 基于开源项目 QUST-Assistant 修改而成
 *
 * 原项目版权所有 (C) 2891954521  (https://github.com/2891954521/QUST-Assistant)
 * 原始开源许可: GNU General Public License v3.0 (GPL-3.0), 许可原文见 LICENSE 文件, 保持完整未改动。
 *
 * 修改版版权所有 (C) 2026 zhangzhenzyc  (https://github.com/zhangzhenzyc)
 * 本修改版同样以 GPL-3.0 发布; 附加版权声明见 NOTICE.md —— 两份版权声明相互独立、同时生效。
 */

package com.qust.helper.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import com.qust.helper.data.Keys
import com.qust.helper.data.Setting

object Theme{

	private val LightColors = lightColorScheme(
		primary = light_primary,
		onPrimary = light_onPrimary,
		primaryContainer = light_primaryContainer,
		onPrimaryContainer = light_onPrimaryContainer,
		secondary = light_secondary,
		onSecondary = light_onSecondary,
		secondaryContainer = light_secondaryContainer,
		onSecondaryContainer = light_onSecondaryContainer,
		tertiary = light_tertiary,
		onTertiary = light_onTertiary,
		tertiaryContainer = light_tertiaryContainer,
		onTertiaryContainer = light_onTertiaryContainer,
		error = light_error,
		errorContainer = light_errorContainer,
		onError = light_onError,
		onErrorContainer = light_onErrorContainer,
		background = light_background,
		onBackground = light_onBackground,
		surface = light_surface,
		onSurface = light_onSurface,
		surfaceVariant = light_surfaceVariant,
		onSurfaceVariant = light_onSurfaceVariant,
		outline = light_outline,
		inverseOnSurface = light_inverseOnSurface,
		inverseSurface = light_inverseSurface,
		inversePrimary = light_inversePrimary,
		surfaceTint = light_surfaceTint,
		outlineVariant = light_outlineVariant,
		scrim = light_scrim,
	)


	private val DarkColors = darkColorScheme(
		primary = dark_primary,
		onPrimary = dark_onPrimary,
		primaryContainer = dark_primaryContainer,
		onPrimaryContainer = dark_onPrimaryContainer,
		secondary = dark_secondary,
		onSecondary = dark_onSecondary,
		secondaryContainer = dark_secondaryContainer,
		onSecondaryContainer = dark_onSecondaryContainer,
		tertiary = dark_tertiary,
		onTertiary = dark_onTertiary,
		tertiaryContainer = dark_tertiaryContainer,
		onTertiaryContainer = dark_onTertiaryContainer,
		error = dark_error,
		errorContainer = dark_errorContainer,
		onError = dark_onError,
		onErrorContainer = dark_onErrorContainer,
		background = dark_background,
		onBackground = dark_onBackground,
		surface = dark_surface,
		onSurface = dark_onSurface,
		surfaceVariant = dark_surfaceVariant,
		onSurfaceVariant = dark_onSurfaceVariant,
		outline = dark_outline,
		inverseOnSurface = dark_inverseOnSurface,
		inverseSurface = dark_inverseSurface,
		inversePrimary = dark_inversePrimary,
		surfaceTint = dark_surfaceTint,
		outlineVariant = dark_outlineVariant,
		scrim = dark_scrim,
	)

	var themeDark = mutableStateOf(Setting.getBoolean(Keys.KEY_THEME_DARK, false))

	@Composable
	fun AppTheme(content: @Composable () -> Unit) {
		// 【第三十二轮·按要求】"主题跟随系统"功能已删除: 只认本应用自己的「暗色模式」开关,
		// 不再随手机系统的深浅色切换(设置里也没有这个开关了)。
		val colorScheme = if(themeDark.value) DarkColors else LightColors

		MaterialTheme(
			colorScheme = colorScheme,
			typography = Typography,
			content = content
		)
	}
}
