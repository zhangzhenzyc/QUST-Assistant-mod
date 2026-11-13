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

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * 【本轮】全应用「文字类功能按钮」共用的点击反馈。
 *
 * 基准取自「编辑课程」页里的 `TextButton("复制课程")`。反编译 material3 1.2.1 可以确认它的三个参数:
 *   · ContainerShape = ShapeKeyTokens.CornerFull → CircleShape(药丸形)
 *   · ContainerHeight = 40.0dp
 *   · 波纹颜色 = contentColor = colorScheme.primary
 *   · 波纹实现 = `androidx.compose.material.ripple.rememberRipple(bounded = true, color = contentColor)`
 *     (material3 1.2.1 的 Surface/Button 内部就是调这个, 所以这里用同一个函数, 手感完全一致)
 *
 * 之前各处的做法五花八门: 昵称、「回到本周」是裸 `Modifier.clickable`, 波纹取 `LocalContentColor`
 * → 在正文里就是**一团黑**且是直角(四方形);「复制整天课表」被 `weight(1F)` 撑满半行, 波纹长得离谱;
 * 「删除当天课表」同样被撑满, 看起来跟别处不一样。
 *
 * 统一成: **primary 色波纹 + 药丸形倒角 + 有界(bounded) + 波纹只包住文字本身**。
 * 「长度不完全一样」是允许的 —— 谁用谁决定内边距, 波纹自然跟着控件尺寸走。
 *
 * @param shape 默认药丸形(= TextButton 的 CircleShape);「我」页的功能按键要更小的倒角时
 *              显式传 `RoundedCornerShape(10.dp)` —— 方形图标格套药丸会变成椭圆, 反而更怪。
 */
@Suppress("DEPRECATION")
@Composable
fun Modifier.textAction(
	enabled: Boolean = true,
	shape: Shape = CircleShape,
	onClick: () -> Unit
): Modifier = tapRippleImpl(enabled, shape, onClick)

/**
 * 【第三十二轮·按要求】文字按钮统一口径。
 *
 * 用户原话: "7 个文字按钮的点击反馈动画还是有点短, 确保留白长度与『回到本周』一致;
 * 确保**所有**文字按钮的留白长度都与『回到本周』一致; 在所有文字按钮周围增加一个
 * 与点击反馈动画边缘一致的灰色边框。"
 *
 * 「回到本周」的留白就是 [TEXT_ACTION_H_PADDING] / [TEXT_ACTION_V_PADDING]
 * (Modifier.textAction{}.padding(...) —— 内边距在 clickable **里面**, 所以波纹/边框都把内边距包进去)。
 * 其余文字按钮一律用这同一套内边距 + 同一个灰色描边。
 */
val TEXT_ACTION_H_PADDING = 12.dp
val TEXT_ACTION_V_PADDING = 6.dp

/** 文字按钮统一的灰色描边(药丸形, 边缘与点击反馈完全重合) */
@Composable
fun textActionBorder(): BorderStroke = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)

/** 【第五十四轮·按要求】填充按钮统一浅蓝(白字仍清晰可读) */
val TEXT_ACTION_FILLED_BLUE = Color(0xFF64B5F6)

/**
 * 【第五十三轮·按要求】文字按钮改"添加到桌面"同款配色: 蓝色填充 + 白字。
 * 【第五十四轮·按要求】蓝色改浅蓝。
 * 调用方去掉 `border = textActionBorder()`, 换上 `colors = textActionFilledColors()`。
 * 红字按钮由调用方在 Text 上显式指定颜色, 不受此默认影响。
 */
@Composable
fun textActionFilledColors(): androidx.compose.material3.ButtonColors =
	androidx.compose.material3.ButtonDefaults.textButtonColors(
		containerColor = TEXT_ACTION_FILLED_BLUE,
		contentColor = Color.White
	)

/**
 * 【第五十五轮·按要求】"不可用"状态的填充色: 灰色填充 + 白字。
 *
 * 用户原话: "回到本周按钮变成灰色时也按照这个里面的灰色按钮样式修改",
 * "在不可使用单独编辑此次课程和多选编辑时，这两个按钮也改成灰色" ——
 * 以前不可用只是把字调暗(浅蓝底 + 半透明字), 看起来仍然像可点的按钮;
 * 现在统一成"灰色实心按钮", 一眼就能看出当前不能用。
 */
val TEXT_ACTION_FILLED_GRAY = Color(0xFFBDBDBD)

/** 【第五十五轮】灰色填充按钮配色(不可用态): 与 [textActionFilledColors] 同形状, 只换底色 */
@Composable
fun textActionFilledGrayColors(): androidx.compose.material3.ButtonColors =
	androidx.compose.material3.ButtonDefaults.textButtonColors(
		containerColor = TEXT_ACTION_FILLED_GRAY,
		contentColor = Color.White
	)

/**
 * 【第三十二轮】带灰色描边的文字按钮点击区域: 与 [textAction] 同一套波纹, 外加一圈同形状的灰边框。
 * 调用方在后面接 `.padding(TEXT_ACTION_H_PADDING, TEXT_ACTION_V_PADDING)` 就是标准留白。
 */
@Suppress("DEPRECATION")
@Composable
fun Modifier.textActionOutlined(
	enabled: Boolean = true,
	onClick: () -> Unit
): Modifier {
	val shape = CircleShape
	val source = remember { MutableInteractionSource() }
	val indication = rememberRipple(bounded = true, color = MaterialTheme.colorScheme.primary)
	return this
		.clip(shape)
		.border(textActionBorder(), shape)
		.clickable(interactionSource = source, indication = indication, enabled = enabled, onClick = onClick)
}

/**
 * 【第二十九轮】"矩形/整行/整格"类点击区域共用的反馈。
 *
 * 和 [textAction] 是同一套波纹(primary 色 + 有界 + 同一实现), 只是默认倒角换成了 12dp:
 * 文字按钮是药丸形, 而整行/整格套药丸会变成椭圆, 很怪。
 *
 * 用户的原话是"其他的按键有四边形的反馈动画的都加个倒角, 做到样式统一" ——
 * 就是把这些地方的直角波纹统一成圆角。
 */
@Suppress("DEPRECATION")
@Composable
fun Modifier.tapRipple(
	enabled: Boolean = true,
	shape: Shape = RoundedCornerShape(12.dp),
	onClick: () -> Unit
): Modifier = tapRippleImpl(enabled, shape, onClick)

@Suppress("DEPRECATION")
@Composable
private fun Modifier.tapRippleImpl(
	enabled: Boolean,
	shape: Shape,
	onClick: () -> Unit
): Modifier {
	val source = remember { MutableInteractionSource() }
	val indication = rememberRipple(bounded = true, color = MaterialTheme.colorScheme.primary)
	return this
		.clip(shape)
		.clickable(
			interactionSource = source,
			indication = indication,
			enabled = enabled,
			onClick = onClick
		)
}

/**
 * 【第三十一轮·按要求】"整块矩形"区域(底部导航的两个页签)的点击反馈。
 *
 * 用户原话: "底部『我』和『学期课表』的点击反馈动画太生硬了, 改成点击『复制课程』那种过度动画, 不加倒角"。
 *
 * 上一版是自绘淡层(`drawBehind` + `animateFloatAsState`): 按下瞬间整块变成一个死板的色块,
 * 松手再淡出 —— 没有扩散、没有水波, 所以"生硬"。
 * 现在改用和「复制课程」**完全同一个实现**: `rememberRipple(bounded = true, color = primary)`。
 * 差别只在没有 [clip] 任何 Shape —— 波纹被裁在 View 的边界里, 四个角是标准的直角, 不加倒角。
 */
@Suppress("DEPRECATION")
@Composable
fun Modifier.rectPressFeedback(
	enabled: Boolean = true,
	color: Color = MaterialTheme.colorScheme.primary,
	onClick: () -> Unit
): Modifier {
	val source = remember { MutableInteractionSource() }
	val indication = rememberRipple(bounded = true, color = color)
	return this.clickable(
		interactionSource = source,
		indication = indication,
		enabled = enabled,
		onClick = onClick
	)
}
