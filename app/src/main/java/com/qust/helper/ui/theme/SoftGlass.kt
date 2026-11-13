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
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor

/**
 * 【第三十四轮·按要求】页面底板色 —— 全应用**只有一个**背景色。
 *
 * 用户原话: "该界面上面的部分和下面的部分有色差，不要出现色差。"
 *
 * 上一版这里是一道竖向渐变(上 #F8FBFF / 中 #F1F5FB / 下 #F7F4FA), 想模拟"柔光玻璃背后的光"。
 * 实测截图取色证实了问题:
 *   · 顶栏(TopAppBar 用的是 colorScheme.surface = #F5F5F5)是**中性灰**;
 *   · 页面内容区是那道**偏冷蓝**的渐变, 而且从 y≈250 的 #F9FAFE 一路淡到 y≈900 的 #F1F4FB。
 * 于是同一个页面被分成三截颜色(顶栏灰 / 上半偏白 / 下半偏灰蓝), 面积一大就非常明显。
 *
 * 现在直接取主题的 [MaterialTheme.colorScheme.background](浅色 #F5F5F5 / 暗色 #1E1E1E):
 * 它和 TopAppBar 的 surface 是同一个值, 顶栏与内容区**严丝合缝**; 整页只有一种底色,
 * 白色卡片浮在上面 —— 既没有色差, 也没有渐变带来的横向色带。
 *
 * (返回 Brush 是为了不改 [MainActivity] / [ComposeActivity] 的调用点;
 *  里面的颜色是单色填充, 与"渐变"无关。)
 */
@Composable
fun softGlassBackdrop(): Brush = SolidColor(softGlassSurface())

/** 页面底板色(单色): 顶栏、内容区、底部导航都取这一个值, 保证没有色差 */
@Composable
fun softGlassSurface(): Color = MaterialTheme.colorScheme.background

/**
 * 柔光玻璃面板的**边缘折射光晕**: 1dp 的暖白描边。
 *
 * 玻璃压在浅色背景上时, 没有这道边就和背景糊在一起; 有了它, 边界"亮"起来, 才像一块玻璃。
 * 用 outlineVariant 会偏灰(那是"描边"), 这里要的是"光", 所以走 surface + 白。
 */
@Composable
fun glassEdgeColor(): Color {
	return if(Theme.themeDark.value) Color(0x22FFFFFF) else Color(0xB3FFFFFF)
}

/** 柔光玻璃面板的底色: 比纯白略透一点, 让背景那层光透上来(文字仍清晰可读) */
@Composable
fun glassPanelColor(): Color {
	return if(Theme.themeDark.value) Color(0xF2212121) else Color(0xF7FFFFFF)
}

/** 面板投影色(极淡): 只用来把面板"托"起来, 不能压暗背景 */
@Composable
fun glassShadowColor(): Color {
	return if(Theme.themeDark.value) Color(0x66000000) else Color(0x14000000)
}
