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

import androidx.compose.runtime.Composable
import androidx.glance.GlanceModifier
import androidx.glance.ImageProvider
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.qust.helper.ui.theme.LESSON_BACKGROUND_COLORS


/**
 * 【第五十九轮·重写】桌面小组件的"磨砂玻璃"底。
 *
 * ────────────────────────────────────────────────────────────────────────
 * 一、这一版砍掉了什么(以及为什么必须砍)
 * ────────────────────────────────────────────────────────────────────────
 *
 * 第三十九 ~ 五十一轮为了让卡片"真的透出壁纸", 写了一套「读桌面壁纸 → 连续减半降采样 →
 * 高斯平滑 → 叠白蒙版 → 烘圆角 → 当位图背景」的流程。它在 AOSP / 部分 ROM 上确实出过效果,
 * 但代价是:
 *
 *   · **要读壁纸**(`WallpaperManager.getWallpaperFile/getBitmap/drawable/…)。
 *     MIUI 把这几条路**全部封死**(第四十六轮日志里全是 SecurityException), 于是绝大多数
 *     用户看到的其实是兜底纯色底 —— 白写了三个月;
 *   · **一张静态位图**。它按"卡片比例 + 壁纸 id"缓存, 壁纸换了/卡片挪了位置, 底下的画面
 *     已经变了, 位图还是老的 —— 这就是用户说的"静态截图模糊";
 *   · 每次生成要几十毫秒 + 一块 256² 位图走 Binder, 渲染慢、内存抖。
 *
 * 用户第五十九轮把要求写得很明确:
 *   ① 底层透明, **不读取壁纸**、**不依赖厂商专属模糊接口**, 全安卓兼容;
 *   ② 均匀柔和的半透雾面, 能透出壁纸, 无条纹断层;
 *   ③ 柔和内高光 + 轻外发光 + 淡软阴影;
 *   ④ 课程色块保持半透;
 *   ⑤ 自动降级: 桌面支持模糊就动态跟随壁纸, 不支持就靠半透蒙版保观感, **不能变成白底**;
 *   ⑥ **禁止静态截图模糊**。
 *
 * 所以这一版把整套位图方案**全部删除**, 只留一件事: 一张**半透明的 layer-list**
 * (见 res/drawable/card_background.xml)。它的"雾面"不靠计算, 靠 alpha 留白 ——
 * 壁纸从卡片底下透上来, 桌面糊不糊、是哪家 ROM, 都不影响它成立。
 *
 * 这样也顺手满足了 ⑤: 卡是透明的, 启动器把"它背后的东西"画在它下面 ——
 * 桌面开了模糊, 透过来就是糊好的壁纸; 没开, 透过来是清晰壁纸但被 18% + 10% 的雾面压住
 * 对比度, 观感仍是雾玻璃。**两条路都不需要检测, 也不存在"不支持就白底"的分支。**
 *
 * 【第六十轮·按用户给的六条参数重做】上一版的白蒙版是 **78% -> 71%**: alpha 越过 70% 之后
 * 壁纸只剩一成能透上来, 观感就是一块白塑料片(用户原话"泛白太厉害")。
 * 现在按他给的区间重排 —— 白蒙版 18% -> 15%, 再叠 10% -> 8% 浅冷灰,
 * 1px 内白高光 30%, 外发光 15% / 20% 两道; 每一层的 alpha 与理由见
 * res/drawable/card_background.xml 的逐层说明。
 *
 * ────────────────────────────────────────────────────────────────────────
 * 二、还剩什么
 * ────────────────────────────────────────────────────────────────────────
 *   · [GlassRoot]: 小组件根容器 —— 挂上那块玻璃底, 内容盖在上面;
 *   · [BLOCK_COLORS] / [block]: 课程色块的**统一配方**(去饱和 + 半透),
 *     两个课表小组件共用, 保证"风格统一"(约束 ④)。
 */
object WidgetGlass {

	/**
	 * 【约束④·课程色块保持半透, 通透度与外层卡片统一】色块不透明度。
	 *
	 * 【第六十轮】0.78 -> 0.72。
	 *
	 * 卡底这一版从"78% 白"降到"18% 白 + 10% 冷灰", 整块卡的通透度上了一个量级;
	 * 色块若还留在 0.78, 两者并排就像"雾玻璃上贴了几张实心贴纸"(约束④说的就是这个)。
	 * 0.72 仍然比卡底实(否则深色壁纸下认不出是哪门课), 但已经能透出底下那层雾 ——
	 * 卡与块是同一个质感, 不是两种材料。
	 */
	const val BLOCK_ALPHA = 0.72F

	/**
	 * 课程色块的饱和度系数。
	 *
	 * 【第三十八轮起的老规矩】主题里的课程色是按"软件内白底"调的, 直接搬到玻璃卡片上
	 * 会偏艳、把卡片的雾面压掉。降 15% 后色相不变、观感更"磨砂"。
	 */
	private const val DESATURATE = 0.85F

	/**
	 * 课程色块的最终颜色表(已去饱和, **alpha 由 [block] 统一给**)。
	 *
	 * `by lazy`: 只算一次 —— 两个小组件每次渲染都要按颜色下标取色, 现算 HSV 转换会成为
	 * 缩放时的固定开销(第三十六轮为此专门做过缓存)。
	 */
	val BLOCK_COLORS: Array<Color> by lazy {
		Array(LESSON_BACKGROUND_COLORS.size) { i ->
			val hsv = FloatArray(3)
			android.graphics.Color.colorToHSV(LESSON_BACKGROUND_COLORS[i].toArgb(), hsv)
			hsv[1] = (hsv[1] * DESATURATE).coerceIn(0F, 1F)
			Color(android.graphics.Color.HSVToColor(hsv))
		}
	}

	/**
	 * 取课程色块颜色。
	 *
	 * 越界的颜色下标一律夹住 —— 坏数据(导入的课表颜色下标超范围)以前会让整个小组件崩掉。
	 *
	 * 【第六十二轮】新增 [dark]: 深色玻璃主题下, 色块改用"深色配方" ——
	 * 明度压到 0.62、不透明度降到 0.55。理由见 [DARK_VALUE] / [DARK_ALPHA]。
	 *
	 * @param index 课程颜色下标(来自 [com.qust.helper.data.lesson.Lesson.color])
	 * @param dark 是否是深色主题
	 * @param alpha 不透明度, 默认 [BLOCK_ALPHA]
	 */
	fun block(index: Int, dark: Boolean = false, alpha: Float = BLOCK_ALPHA): Color {
		if(dark) {
			if(DARK_BLOCKS.isEmpty()) return Color.White.copy(alpha = DARK_ALPHA)
			return DARK_BLOCKS[index.coerceIn(0, DARK_BLOCKS.size - 1)].copy(alpha = DARK_ALPHA)
		}
		if(BLOCK_COLORS.isEmpty()) return Color.White.copy(alpha = alpha)
		return BLOCK_COLORS[index.coerceIn(0, BLOCK_COLORS.size - 1)].copy(alpha = alpha)
	}

	/**
	 * 【第六十二轮】深色主题下课程色块的**明度系数**。
	 *
	 * 用户对深色玻璃的要求是"半透深色色块, 饱和度偏低, 和卡片底色拉开层次"。
	 * 主题里的课程色是按"软件内白底"调的, 明度都在 0.9 上下 —— 直接搬到 #121218 的暗底上,
	 * 就是一排发光的荧光贴纸(用户明确禁止"高饱和亮色")。
	 *
	 * 【第六十五轮】0.62 -> 0.78: 用户回"深色玻璃的课程颜色太淡了"。
	 * 0.62 是为了防"荧光贴纸", 但配上 0.55 的不透明度, 结果是色块几乎融进暗底,
	 * 课名都要靠猜 —— 那是另一个极端。0.78 的明度仍然比白底版低一大档, 不会发光,
	 * 但色相认得出来。
	 */
	private const val DARK_VALUE = 0.78F

	/**
	 * 深色主题下色块的不透明度。
	 *
	 * 【第六十五轮】0.55 -> 0.85。上一版"半透"透得太狠: 暗卡本身已经 54% 压黑,
	 * 色块再半透就等于把颜色又洗一遍, 用户看到的就是"太淡"。
	 * 0.85 仍留 15% 的透光, 观感还是玻璃上的色块, 不是贴纸。
	 */
	private const val DARK_ALPHA = 0.85F

	/**
	 * 深色主题下色块的饱和度系数。
	 *
	 * 0.85 是"白底卡"的配方 —— 白底不需要那么艳。暗底会**吃掉**颜色(暗色块压暗色背景,
	 * 边界本来就弱), 所以深色版少降一点: 0.95, 只削掉最刺眼的那一点点。
	 */
	private const val DARK_DESATURATE = 0.95F

	/** 深色主题的色块表(已降明度), 计算方式与 [BLOCK_COLORS] 一致 */
	val DARK_BLOCKS: Array<Color> by lazy {
		Array(LESSON_BACKGROUND_COLORS.size) { i ->
			val hsv = FloatArray(3)
			android.graphics.Color.colorToHSV(LESSON_BACKGROUND_COLORS[i].toArgb(), hsv)
			hsv[1] = (hsv[1] * DARK_DESATURATE).coerceIn(0F, 1F)
			hsv[2] = (hsv[2] * DARK_VALUE).coerceIn(0F, 1F)
			Color(android.graphics.Color.HSVToColor(hsv))
		}
	}
}


/**
 * 小组件根容器: 按 [WidgetStyle.theme] 铺一层卡底, 内容盖在上面。
 *
 * 【第六十二轮】从"两个主题"扩到"六套主题", 卡底统一走 [WidgetTheme.background]:
 *   玻璃 / 纯色 / 深色玻璃 / 简约线条 / 哑光卡片 / 亚克力 —— 六张 layer-list, 一张不多一张不少。
 * 这里**不再判断**"是不是玻璃", 只认 `style.backgroundRes`: 以后再加主题,
 * 只要在 [WidgetTheme] 里补一行 + 一张 drawable, 这个文件一个字都不用改。
 *
 * 注意: 玻璃 / 深色玻璃 / 亚克力 / 哑光 这四张底**必须是半透明的**(见各自的说明)。
 * 如果哪天有人把它们改成不透明色, 卡片会立刻退回"白(黑)底塑料片", 再也透不出壁纸 ——
 * 这是约束 ⑤ 的红线, 也是第六十轮"泛白太厉害"的教训。
 *
 * `modifier` 留给调用方: 今日课表要挂点击、饮水码要留 2dp 边距。
 */
@Composable
fun GlassRoot(
	style: WidgetStyle,
	modifier: GlanceModifier = GlanceModifier.fillMaxSize(),
	contentAlignment: Alignment = Alignment.TopStart,
	content: @Composable () -> Unit
) {
	Box(
		modifier = modifier.background(ImageProvider(style.backgroundRes)),
		contentAlignment = contentAlignment
	) {
		content()
	}
}
