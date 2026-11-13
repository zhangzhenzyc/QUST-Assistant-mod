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

import androidx.annotation.DrawableRes
import com.qust.helper.R

/**
 * 【第六十七轮·按要求重排】桌面小组件的**六套主题**。
 *
 * 用户原话: "「简约线条」改为「透明简约」位置改为（2，3）…「玻璃」改为「磨砂玻璃」位置改为（2，1），
 * 「纯色」改为「纯白简约」位置改为（1，1），「深色玻璃」位置改为（2，2），「哑光卡片」位置改为（1，2），
 * 「亚克力」改为「渐变磨砂」位置改为（1，3）"
 *
 * ────────────────────────────────────────────────────────────────────────
 * **枚举的声明顺序 = 设置页九宫格的位置**（设置页按 `entries.chunked(3)` 铺行），
 * 所以这一版的顺序必须严格是：
 *
 *   第 1 行:  纯色简约(1,1)   渐变磨砂(1,3)   磨砂玻璃(2,1)
 *   第 2 行:  深色玻璃(2,2)   透明简约(2,3)
 *
 * 【第八十四轮·按用户要求】"哑光卡片"改名为「纯色简约」、原「纯白简约」(card_solid)
 * 删除、小组件第一次添加的默认主题 = 纯色简约。所有主题的卡体**内缩统一为 2dp**
 * (原来玻璃 3dp/深玻璃 1dp/透明 0dp, 卡片看着大小不一), 描边层全部删除。
 *
 * 注意 [key] 一个都没动 —— 它写进了 SharedPreferences，改了老用户的选择就丢了
 * (选过"solid"的老用户升级后落到默认主题, 由 [of] 兜底)。
 * 用户看到的 [label] 才是新名字。
 * ────────────────────────────────────────────────────────────────────────
 *
 * [desc] 全部清空：用户明确要求"删除对主题的解释，比如"不透明，任何壁纸都清晰""。
 * 字段本身保留，是因为 `WidgetStyleStore` / 桌面空态文案还在读它（为空时自动不显示）。
 */
enum class WidgetTheme(
	/** 存进 SharedPreferences 的键值。**不要改** —— 改了老用户的选择就丢了 */
	val key: String,
	/** 设置页上的按钮文字 */
	val label: String,
	/** 设置页上按钮下面那行小字。**本版按要求全部清空**（空串 = 不显示） */
	val desc: String,
	/** true = 夜间版: 默认文字改成纯白 + 浅灰辅助色, 课程色块也转成深色系 */
	val dark: Boolean,
	/** 卡底资源 */
	@DrawableRes val background: Int,
	/**
	 * 卡片本体相对视图边界**向右/向下缩进**了多少 dp。
	 *
	 * 只有把本体缩进去, 垫在底下的暗色层才能露出来当"外阴影"(layer-list 的硬限制: 背景被
	 * 裁在视图边界内, 画出去的部分会被直接裁掉)。但缩进去的那一圈**不再是卡片**, 而是壁纸本色 ——
	 * 课表内容如果还按"视图宽度"排, 最右边的周日就会压到这一圈外面去。
	 *
	 * 所以布局必须知道这个数: `metricsOf` 会把 [WidgetTheme.bodyInset] 一起扣掉。
	 */
	val bodyInset: Float
) {

	// —— 第 1 行 ——

	/** (1,1) 纯色简约(原"哑光卡片"): 不透明柔白板, 无高光无描边。**默认主题** */
	MATTE(
		key = "matte", label = "纯色简约", desc = "",
		dark = false, background = R.drawable.card_matte, bodyInset = 2F
	),

	/** (1,2) 渐变磨砂: 乳白厚板, 微渐变 + 强散射 */
	ACRYLIC(
		key = "acrylic", label = "渐变磨砂", desc = "",
		dark = false, background = R.drawable.card_acrylic, bodyInset = 2F
	),

	// —— 第 2 行 ——

	/** (1,3) 磨砂玻璃: 壁纸高强度模糊 + 顶部窄高光 + 底部内阴影 */
	GLASS(
		key = "glass", label = "磨砂玻璃", desc = "",
		dark = false, background = R.drawable.card_background, bodyInset = 2F
	),

	/** (2,1) 深色玻璃: 磨砂玻璃的夜间配套版 */
	DARK_GLASS(
		key = "dark_glass", label = "深色玻璃", desc = "",
		dark = true, background = R.drawable.card_dark_glass, bodyInset = 2F
	),

	/** (2,2) 透明简约: 完全不挡壁纸, 没有填充也没有边框 */
	OUTLINE(
		key = "outline", label = "透明简约", desc = "",
		dark = false, background = R.drawable.card_outline, bodyInset = 2F
	);

	/**
	 * 这个主题是不是"半透"的(能看见壁纸)。
	 *
	 * 只用在两处: 课程色块的不透明度、饮水码的底色选择 —— 纯色简约是实心的。
	 */
	val translucent: Boolean get() = this != MATTE

	/**
	 * 课程色块要不要走"深色配方"。
	 *
	 * 目前只有深色玻璃要: 底色已经很暗, 色块若还是高明度半透色, 会像"暗玻璃上贴了几张贴纸"。
	 */
	val darkBlocks: Boolean get() = dark

	companion object {

		/** 默认主题: 纯色简约(【第八十四轮】按用户要求, 小组件第一次添加即它) */
		val DEFAULT: WidgetTheme = MATTE

		/** 按键值找主题; 找不到(老版本的数据/脏数据)就回默认 */
		fun of(key: String?): WidgetTheme = entries.firstOrNull { it.key == key } ?: DEFAULT
	}
}
