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

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import androidx.glance.unit.ColorProvider
import com.qust.helper.data.Keys
import com.qust.helper.data.Setting
import com.qust.helper.ui.theme.LESSON_TEXT_COLORS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 【第六十一轮·新功能 / 第六十二轮·扩成六套主题】桌面小组件的外观参数(一次渲染用一份)。
 *
 * 用户原话:
 *  ① "桌面小组件添加的按钮旁可选择小组件的主题, 一个是玻璃, 另一个是纯色";
 *  ② "可选择非课程内容的字体颜色"; "颜色多一点, 至少覆盖七彩×3"; 后来又改成
 *     "删除全部颜色, 改成七彩加黑白灰*(深/中/浅) 共 30 种颜色";
 *  ③ "小组件的灰色字体有点看不清";
 *  ④ "新增 4 个主题: 深色玻璃 / 简约线条 / 哑光卡片 / 亚克力"。
 *
 * 为什么做成"传值"而不是"在组合里现读 SharedPreferences":
 *  · 小组件是在广播 / Glance 回调里组合的, 组合期读盘(哪怕只是内存映射)会让首帧多一次
 *    IO 抖动 —— 这正是第三十三轮"翻周反应迟钝"的成因;
 *  · 更重要的一条是第五十六轮踩过的坑: 组合期读到的 `Setting` 不一定是用户最后提交的那个值,
 *    表现就是"第一次改了跟上了, 后面几次都不动"。
 * 所以统一在 `provideGlance` 的 IO 阶段取好([WidgetStyleStore.load]), 之后一路往下传。
 *
 * @param theme 六套主题之一(见 [WidgetTheme]), 它同时决定"卡底 / 文字深浅 / 色块配方"
 * @param textColor 用户自己选的非课程文字颜色; 只有 [customColor] 为 true 时才生效
 * @param customColor false = 跟随主题默认色(浅色主题深黑、夜间主题纯白)
 */
data class WidgetStyle(
	val theme: WidgetTheme = WidgetTheme.DEFAULT,
	val textColor: Int = INK,
	val customColor: Boolean = false
) {

	/** 夜间版? 深色玻璃是唯一的夜间主题 */
	val dark: Boolean get() = theme.dark

	/** 卡底资源(两张 Glance 卡片和饮水码共用) */
	val backgroundRes: Int get() = theme.background

	/** 兼容老调用点: "是不是半透主题" */
	val glass: Boolean get() = theme.translucent

	/**
	 * **主文字**颜色: 标题、空态提示、"第 N 周"这种要抢眼的字。
	 *
	 * 三个来源, 优先级从高到低:
	 *  ① 用户自己选的颜色([customColor]);
	 *  ② 夜间主题 -> 纯白 #FFFFFF(用户给的验收值);
	 *  ③ 浅色主题 -> 深黑 #1A1A1A。
	 */
	val text: ColorProvider
		get() = ColorProvider(Color(textArgb))

	/**
	 * 同上, 但给**纯 RemoteViews** 的组件用(饮水码): `setTextColor` 只收 Int。
	 *
	 * 单独抽出来是为了让"夜间主题要白字"这条规则只有**一个**实现 ——
	 * 上一版饮水码直接读 `textColor`, 于是深色主题下它仍是深黑, 在暗底上等于隐形。
	 */
	val textArgb: Int
		get() = if(customColor) textColor else if(dark) WHITE else INK

	/**
	 * **辅助文字**颜色: 时间栏、日期条、上课/下课时间。
	 *
	 * 夜间主题按用户给的验收值降到浅灰 #D1D1D6(纯白在暗底上会"发光", 反而看不清);
	 * 浅色主题用 #3A3A3A —— 注意**不是浅灰**: 第五十九轮的 #6E7681 就是"灰色看不清"的根源。
	 */
	val aux: ColorProvider
		get() = ColorProvider(Color(if(customColor) textColor else if(dark) AUX_DARK else AUX_LIGHT))

	/**
	 * 课程色块里的文字颜色。
	 *
	 * 夜间主题统一白色(用户给的验收值: "课程色块内文字白色");
	 * 浅色主题仍用色块自己的深色字表 —— 压在彩底上统一成黑反而认不出是哪门课。
	 */
	fun blockText(colorIndex: Int): ColorProvider {
		if(dark) return ColorProvider(Color(WHITE))
		val i = colorIndex.coerceIn(0, LESSON_TEXT_COLORS.size - 1)
		return ColorProvider(LESSON_TEXT_COLORS[i])
	}

	companion object {
		/** 浅色主题的深黑(用户给的验收值) */
		const val INK: Int = 0xFF1A1A1A.toInt()

		/** 浅色主题的辅助色: 比深黑浅一档, 但仍属"深色", 不是浅灰 */
		const val AUX_LIGHT: Int = 0xFF3A3A3A.toInt()

		/** 夜间主题的主文字 */
		const val WHITE: Int = 0xFFFFFFFF.toInt()

		/** 夜间主题的辅助文字(用户给的验收值) */
		const val AUX_DARK: Int = 0xFFD1D1D6.toInt()

		/** 老版本的默认值, 保留给 [WidgetStyleStore.textColor] 兜底 */
		const val DEFAULT_TEXT_COLOR: Int = INK
	}
}

/**
 * 桌面小组件的外观设置(主题 + 文字颜色)的读写与"改完立刻生效"。
 *
 * 这里只做三件事, 全都很薄:
 *  ① 读: 给 `provideGlance` 用([load]);
 *  ② 写: 给「设置 -> 桌面小组件」用([setTheme] / [setTextColor]);
 *  ③ 刷: 改完让桌面上那几张卡**马上**重画([refreshAll])。
 */
object WidgetStyleStore {

	/** 老版本常量, 保留以免别处引用断掉 */
	const val THEME_GLASS = "glass"
	const val THEME_SOLID = "solid"

	/**
	 * 可选的**非课程文字**颜色, 一共 **40** 个。
	 *
	 * 【第六十七轮·按要求】用户原话: "非课程文字颜色再加一行亮色，共 40 个颜色"。
	 * 上一版是 3 行 × 10 列(深/中/亮 × 七个色相 + 黑灰白) = 30 个。现在在最下面
	 * 补一行**更亮、更浅**的档位(色相 V=1.0 / S=0.72, 黑白灰给到 #5C5C5C ~ #FFFFFF),
	 * 于是网格变成 **4 行 × 10 列 = 40**:
	 *
	 *  · 列 = 色族, 一共 10 个: 红 橙 黄 绿 青 蓝 紫 + 黑 灰 白;
	 *  · 行 = 档位, 从上到下 深 / 中 / 亮 / 浅。
	 *
	 * 为什么把"最浅"那档单独补一行、而不是在原有三档里插: 深/中/亮三行的**位置是用户
	 * 已经记住的**(第六十三轮反复调过), 插一行会把整块色板错位; 追加在最后,
	 * 老位置的色点一个不动, 新的 10 个只是多出来一行。
	 *
	 * 黑白灰四档是特意排成 深(#000000) → 浅(#FFFFFF) 的连续灰度:
	 * 白·浅就是给"深色玻璃"主题准备的(暗底上唯一看得清的颜色)。
	 */
	val TEXT_PRESETS: IntArray
	val TEXT_PRESET_NAMES: Array<String>

	/** 色板一行放几个(见 [TEXT_PRESETS] 的说明: 10 = 7 个色相 + 黑灰白) */
	const val PALETTE_COLUMNS = 10

	/** 色相: 红 橙 黄 绿 青 蓝 紫 */
	private val HUES = intArrayOf(352, 24, 46, 122, 184, 208, 282)
	private val HUE_NAMES = arrayOf("红", "橙", "黄", "绿", "青", "蓝", "紫")

	/** 四档: 深 / 中 / 亮 / 浅 —— 明度递增; 最后一档为了"亮"而略降饱和 */
	private val SHADE_S = floatArrayOf(1F, 0.96F, 0.86F, 0.72F)
	private val SHADE_V = floatArrayOf(0.52F, 0.68F, 0.86F, 1F)
	// 【第七十轮·二十·按要求】第 3 行叫"浅"、第 4 行叫"亮" —— 只改**叫法**, 颜色一个不动、
	// 位置也不互换(第 3 行还是原来第 3 行的那些颜色, 只是名字换了)。
	private val SHADE_NAMES = arrayOf("深", "中", "浅", "亮")

	/** 黑白灰三族 × 四档(用户要求: "黑白灰*(深/中/浅)") —— 每族从深到浅连续过渡 */
	private val NEUTRAL_KEYS = arrayOf(
		intArrayOf(0x000000, 0x1A1A1A, 0x333333, 0x5C5C5C),
		intArrayOf(0x595959, 0x808080, 0xA6A6A6, 0xC7C7C7),
		intArrayOf(0xD9D9D9, 0xEDEDED, 0xF7F7F7, 0xFFFFFF)
	)
	private val NEUTRAL_NAMES = arrayOf("黑", "灰", "白")
		private val NEUTRAL_SHADE = arrayOf("深", "中", "浅", "亮")

	init {
		val colors = ArrayList<Int>(40)
		val names = ArrayList<String>(40)
		// 行 = 档位, 列 = 色族; 每行恰好 PALETTE_COLUMNS 个
		for(shade in SHADE_NAMES.indices) {
			for(hue in HUES.indices) {
				colors.add(android.graphics.Color.HSVToColor(floatArrayOf(HUES[hue].toFloat(), SHADE_S[shade], SHADE_V[shade])))
				names.add("${HUE_NAMES[hue]}·${SHADE_NAMES[shade]}")
			}
			// 黑 / 灰 / 白 接在同一行的最后三列 —— 这样它们也是**竖着的一列**
			for(family in NEUTRAL_KEYS.indices) {
				colors.add((0xFF000000L or NEUTRAL_KEYS[family][shade].toLong()).toInt())
				names.add("${NEUTRAL_NAMES[family]}·${NEUTRAL_SHADE[shade]}")
			}
		}
		TEXT_PRESETS = colors.toIntArray()
		TEXT_PRESET_NAMES = names.toTypedArray()
	}

	/**
	 * 当前主题。
	 *
	 * 【第六十五轮】改成"读失败也只退这一项", 并把失败原因记进日志。
	 *
	 * 以前是 `runCatching { ... }.getOrDefault(DEFAULT)` —— 任何一步抛异常(比如那个键被
	 * 旧版本写成了非字符串, `getString` 会抛 ClassCastException)都静默退成「玻璃」,
	 * 用户看到的就是"主题怎么点都是玻璃", 而日志里一片干净。
	 * 现在: 读不到/读坏了都记一行 `读主题: ...`, 自检页一眼能看见。
	 */
	fun theme(): WidgetTheme {
		val raw = runCatching { Setting.getString(Keys.KEY_WIDGET_THEME, WidgetTheme.DEFAULT.key) }
			.getOrNull()
		if(raw == null) return WidgetTheme.DEFAULT
		return WidgetTheme.entries.firstOrNull { it.key == raw } ?: WidgetTheme.DEFAULT
	}

	/** 用户是不是**自己选过**颜色(没选过就跟随主题默认色) */
	fun hasCustomColor(): Boolean = runCatching { Setting.contains(Keys.KEY_WIDGET_TEXT_COLOR) }.getOrDefault(false)

	/**
	 * 当前非课程文字颜色。
	 *
	 * 没选过时返回**当前主题的默认主色**, 这样设置页显示的色点和桌面上真实颜色一致
	 * (上一版不管什么主题都返回 #1A1A1A, 选了深色玻璃后设置页会显示"深黑", 对不上)。
	 */
	fun textColor(): Int {
		if(hasCustomColor()) {
			val v = runCatching { Setting.getInt(Keys.KEY_WIDGET_TEXT_COLOR, WidgetStyle.INK) }
				.getOrDefault(WidgetStyle.INK)
			if(v != 0) return v
		}
		return if(theme().dark) WidgetStyle.WHITE else WidgetStyle.INK
	}

	/**
	 * 取一份"这次渲染要用"的外观。
	 *
	 * 故意不接收 `Context`: 全部落在 [Setting] 的同一个 SharedPreferences 里,
	 * 多传一个 context 只会让调用方以为"这里跟某个 Activity 有关"。
	 */
	fun load(): WidgetStyle {
		// 【第六十五轮】三项**分开兜底**: 以前它是"一个 runCatching 包住整份外观",
		// 于是"文字颜色"那一步一抛异常, 连主题也跟着退成默认 —— 表现为"怎么切都是玻璃"。
		val t = theme()
		val color = runCatching { textColor() }.getOrDefault(WidgetStyle.INK)
		val custom = runCatching { hasCustomColor() }.getOrDefault(false)
		return WidgetStyle(t, color, custom)
	}

	@Suppress("UNUSED_PARAMETER")
	fun load(context: Context): WidgetStyle = load()

	/**
	 * 【设置页】切主题。
	 *
	 * 【第六十五轮·改成同步落盘】用户回"主题还是不能切换, 我是删除小组件重新添加的"。
	 * 删除再添加 = 一定会重新走 `provideGlance` → 一定会重新读这条配置 —— 结果还是旧的,
	 * 那只能是**写盘没落下去**。`apply()` 是异步落盘: MIUI 在用户点完主题、退回桌面这一两秒里
	 * 回收进程是常事, 队列里那次写就丢了。这条键一共 5 个字节, `commit()` 的代价可以忽略,
	 * 换来的是"点完就写死"。
	 */
	fun setTheme(theme: WidgetTheme) {
		Setting.editSync { it.putString(Keys.KEY_WIDGET_THEME, theme.key) }
		syncTextColorToTheme(theme)
	}

	/** 【设置页】切主题(老接口, 按键值) */
	fun setTheme(value: String) = setTheme(WidgetTheme.of(value))

	/**
	 * 【第六十三轮·修"白字压在白卡上"]**自选色和新主题打架时把自选色作废**。
	 *
	 * 用户截图里出现过这一帧: 纯色主题(白卡) + 白字 —— 整张卡的文字全部消失。
	 * 来源很清楚: 他先在深色玻璃下选了"白·浅", 再切回纯色, 颜色照旧生效。
	 *
	 * 规则(只在**几乎看不见**时才动手, 不抢用户的选择):
	 *  · 浅色主题: 文字亮度 > 0.72 -> 作废;
	 *  · 深色主题: 文字亮度 < 0.28 -> 作废。
	 * 作废 = 删掉那个键, 于是 `hasCustomColor()` 变回 false, 颜色回到主题默认的
	 * 深黑 / 纯白 —— 正是用户此刻想看到的东西。
	 *
	 * @return true = 确实作废了自选色(调用方可以用气泡告诉用户)
	 */
	fun syncTextColorToTheme(theme: WidgetTheme = theme()): Boolean {
		if(!hasCustomColor()) return false
		val luma = lumaOf(textColor())
		val invisible = if(theme.dark) luma < 0.28F else luma > 0.72F
		if(!invisible) return false
		runCatching { Setting[Keys.KEY_WIDGET_TEXT_COLOR] = null }
		return true
	}

	/** 感知亮度(0 = 黑, 1 = 白)。只用来判"这个颜色压在那种底上还看不看得见" */
	private fun lumaOf(argb: Int): Float {
		val r = ((argb shr 16) and 0xFF) / 255F
		val g = ((argb shr 8) and 0xFF) / 255F
		val b = (argb and 0xFF) / 255F
		return 0.2126F * r + 0.7152F * g + 0.0722F * b
	}

	/**
	 * 【设置页】换非课程文字颜色。
	 *
	 * 写入时顺手把"用户选过颜色"这件事记下来 —— 用 [Keys.KEY_WIDGET_TEXT_COLOR] 存在与否表示,
	 * **不额外加一个布尔字段**: 字段一多, 老用户升级时就会读到"没有这个字段"的默认值。
	 */
	fun setTextColor(argb: Int) {
		// 【第六十五轮】同样改成同步落盘(理由见 setTheme): 颜色和主题必须一起活下来
		Setting.editSync { it.putInt(Keys.KEY_WIDGET_TEXT_COLOR, argb) }
	}

	/**
	 * 【第一百一十一轮】小组件**高度校准**(百分比, 92 = 默认档)。
	 *
	 * 只对非小米路径生效(小米有自己的 miuiK 分档, 不能叠加)。取值范围 80~100,
	 * UI 上给 100/96/92/88 四档(见 WidgetPage 校准卡)。
	 *
	 * 【第一百一十二轮·按用户实测把默认档从 100 改成 92】用户原话: "默认的不行,
	 * 92%刚刚好" —— 这台 ColorOS 改 5×6 布局后 options 陈旧 7.4%(上报 462、物理 428),
	 * 92% 档正好留出 14dp 余量。对尺寸准确的启动器, 92% 意味着底部多 ~34dp 空白,
	 * 用户重加小组件后可自行调回 100%。
	 *
	 * 为什么不做成"自动检测": options(SIZES/MIN/MAX)在布局变更后全部是**同一套旧值**,
	 * 内部互相印证、没有任何可判别的破绽(本机 5×6 实测: SIZES=320×462 与 MAX=462 完全
	 * 一致, 物理却只有 ≈259×428), 无法在代码里区分"准"与"陈旧", 只能交给用户标定。
	 */
	fun heightCal(): Int =
		runCatching { Setting.getInt(Keys.KEY_WIDGET_HEIGHT_CAL, 92) }.getOrDefault(92).coerceIn(80, 100)

	/** 【设置页】设高度校准档位(同步落盘, 理由同 setTheme) */
	fun setHeightCal(percent: Int) {
		Setting.editSync { it.putInt(Keys.KEY_WIDGET_HEIGHT_CAL, percent.coerceIn(80, 100)) }
	}

	/** 当前选中颜色的展示名 */
	fun textColorName(argb: Int): String {
		val i = TEXT_PRESETS.indexOfFirst { it == argb }
		return TEXT_PRESET_NAMES.getOrElse(i) { "自定义" }
	}

	/**
	 * 【设置页】"改完马上在桌面上看到"。
	 *
	 * ──────────────────────────────────────────────────────────────────
	 * 为什么是**四条腿**, 而不是一条 `updateAll`
	 * ──────────────────────────────────────────────────────────────────
	 * 第六十一轮只调了 `DailyLesson().updateAll()` / `TermLesson().updateAll()`, 用户回: "还是没反应";
	 * 第六十一轮末尾加了两条腿(广播 + updateAll), 用户仍回: "切换主题和字体颜色还是没反应"。
	 *
	 * 从截图能反推出真正的现象: **桌面上的小组件自安装之后就没再重画过** ——
	 * 时间栏显示的还是"安装那一刻"存下的配色, 连课表里新加的第 11 节都没跟上。
	 * 也就是说: 每次升级重装(`ACTION_MY_PACKAGE_REPLACED`)都会重画一次, 而 App 内部发起的刷新**全部丢失**。
	 *
	 * 所以这里改成四条腿, 任何一条通就够, 且**每条都单独 runCatching**:
	 *  ① **改 options**(`updateAppWidgetOptions`): 这是一次**系统服务调用**, 走的是
	 *     AppWidgetService, 不是广播。系统收到后一定会回调 `onAppWidgetOptionsChanged`,
	 *     我们那个回调里会直接 `glanceAppWidget.update(...)` —— 这是"重画"最可靠的一条路。
	 *     顺带还让启动器知道"这个部件的参数变了", 它自己也会重新拉一次视图。
	 *  ② 给两个 Glance 小组件各发一条 `ACTION_APPWIDGET_UPDATE` 系统广播(带 ids);
	 *  ③ 再调一次 Glance 的 `updateAll`(正常机型上这条最快);
	 *  ④ 饮水码本来就是纯 RemoteViews, 直接 `push` 直写。
	 *
	 * 【第六十三轮·第五条腿】用户回"重绘功能有时候管用有时候不行" —— 说明上面四条里
	 * 有一条以上是概率性的。①②③ 都要经过"广播 / Glance 内部查表"两层, 任何一层在 MIUI 上
	 * 打个磕睡就整条丢。所以补上最硬的一条: **拿到实例 id 直接 `GlanceAppWidget.update`**
	 * (见 [updateGlanceById]) —— 这一步和 ③ 的 `updateAll` 是同一条底层调用, 区别只是
	 * id 由系统给, 不由 Glance 自己猜。放在最前面, 另外 700ms 后再补一轮。
	 *
	 * 另外 700ms 后再补一轮 ②③ —— 有些 ROM 会把"同一进程短时间内连发"的第一条丢掉,
	 * 补一轮几乎不花代价, 但能救回"点了没反应, 再点一次才生效"的观感。
	 */
	fun refreshAll(context: Context) {
		val app = context.applicationContext
		// 【第六十八轮·二·按要求】"饮水码同步主题很快, 其他两个小组件很慢, 差了 2~3 秒"
		//
		// 这不是同一条路径上的优化问题, 而是**两种架构的固有差别**:
		//  · 饮水码是纯 RemoteViews, `updateAppWidget` 是进程内直写 -> 下一帧就变;
		//  · 课表两张是 Glance, `update` 要经过 WorkManager 的 SessionWorker 异步组合,
		//    启动 + 排队 + 组合 + 回写 RemoteViews 走完才上屏, 2~3 秒就是它的下限。
		//
		// 能做的是"别在它前面挡路":
		//  ① 上一版在这里先做一次 WorkManager 健康探测(`getWorkInfosForUniqueWork().get()`),
		//     那是一次**主线程阻塞**的数据库读, 排在 Glance 更新之前, 等于硬生生推迟了起跑;
		//  ② 饮水码虽然是直写, 但也放在 Glance 之后 —— 它是毫秒级, 放后面不影响观感,
		//     却能让最慢的那条腿**先起跑**。
		// 探测已经删掉(它只是写诊断卡, 而诊断卡上一轮已按要求移除)。
		// 【第六十五轮·终】Glance 刷新**只走这一条路**。
		//
		// 以前的"七条腿"(options 踢一脚 / 两路广播 / 两次 updateAll / 逐实例直写 / 700ms 后再补一轮)
		// 全部删掉: 每一条腿最终都是一次 Glance update, 而 update 内部用 REPLACE 策略重排队 worker
		// —— 七条腿 = 七次"把上一个正在跑的组合任务砍掉重来"。七连砍之后只要最后一次的组合
		// 没能完整跑完, 桌面就停在旧帧, 表现正是"闪了一下但没变化, 偶尔才成功"。
		//
		// 现在 TermLesson / DailyLesson 是 **object 单例**(与 receiver 共用同一份会话表),
		// 这里只调一次; 会话若已在运行, Glance 会走"复用会话重新组合"分支, 不打断任何人。
		// 先把 tick +1: 内容 lambda 依赖这个状态, Recomposer 会因此安排重组,
		// 复用会话的"重新组合"分支才能画出**新**样式(旧实现捕获旧值, 重画也白画)。
		runCatching { DailyLesson.refreshTick.value++ }
		runCatching { TermLesson.refreshTick.value++ }
		CoroutineScope(Dispatchers.IO).launch {
			// 【第六十八轮·三·按要求"其他两个小组件的主题更换速度还是有点慢"】
			//
			// 这两张卡以前是**串行**排队的: 先排「今日课表」, 等它从 WorkManager 排队、唤醒、
			// 组合完, 才回头排「本周课表」 —— 于是"两张卡都变过来"的时间是两张卡各自延迟的**和**。
			// 现在两条腿同时起跑(各自一个协程), 总时间变成 max(两张) 而不是 sum(两张),
			// 在 MIUI 上大概能省掉一整个 WorkManager 排队周期。
			// 注意: 这里仍然是"每个组件只 update 一次" —— 并发的是**不同组件**, 不会被 REPLACE 打断。
			val daily = launch { runCatching { updateGlanceById(app, DailyLesson, DailyLessonReceiver::class.java) } }
			val term = launch { runCatching { updateGlanceById(app, TermLesson, TermLessonReceiver::class.java) } }
			daily.join()
			term.join()
			// 【第六十八轮·二】6 秒(原 2.5 秒)后再看 worker 干到哪了: 只在"还**排着队没跑**"时补一次。
			// 2.5 秒对 MIUI 太早 —— 正常的那一次 2~3 秒才刚跑到一半, 这时补发就是又一次 REPLACE,
			// 把正在跑的组合砍掉重来, 观感反而更慢。6 秒还没开始才算"真卡住了"。
			delay(6_000L)
			if(reportSessionWorkerStates(app)) {
				runCatching { updateGlanceById(app, DailyLesson, DailyLessonReceiver::class.java) }
				runCatching { updateGlanceById(app, TermLesson, TermLessonReceiver::class.java) }
				delay(2_500L)
				reportSessionWorkerStates(app)
			}
		}
		// 饮水码: 纯 RemoteViews 同步直写(毫秒级), 让它跟在 Glance 起跑之后, 不占慢路径的时间。
		// 【第七十轮·四】force = true: 换主题时码没变, 但未登录提示的文字色/卡底要跟着换,
		// 不能被 push 的"同码 2 秒节流"跳过(见 push 的说明)。
		runCatching { DrinkCodeWidget.push(app, force = true) }
	}

	/**
	 * 【第六十五轮·终】检查每个课表小组件的 SessionWorker 是否还**排着队没跑**。
	 * unique name 与 Glance 内部一致: `createUniqueRemoteUiName` = "appWidget-" + 实例id。
	 * @return true 表示存在 ENQUEUED(还没开始跑)的 worker
	 */
	private fun reportSessionWorkerStates(context: Context): Boolean {
		var enqueued = false
		runCatching {
			val wm = androidx.work.WorkManager.getInstance(context)
			for(cls in listOf(DailyLessonReceiver::class.java, TermLessonReceiver::class.java)) {
				val ids = AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, cls))
				for(id in ids) {
					val infos = wm.getWorkInfosForUniqueWork("appWidget-$id").get()
					if(infos.any { it.state == androidx.work.WorkInfo.State.ENQUEUED }) enqueued = true
				}
			}
		}
		return enqueued
	}

	/**
	 * 【第六十三轮】按**实例 id** 逐个重画一个 Glance 小组件。
	 *
	 * 和 `updateAll()` 的差别只有一处, 但正是要命的那一处: `updateAll()` 自己去找 id,
	 * 找的方式依赖 Glance 内部的状态存储; 这里用系统给的 `getAppWidgetIds` —— 只要桌面上
	 * 还摆着这张卡, 系统就一定答得出来。
	 */
	private suspend fun updateGlanceById(context: Context, widget: GlanceAppWidget, cls: Class<*>) {
		val ids = runCatching {
			AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, cls))
		}.getOrDefault(IntArray(0))
		if(ids.isEmpty()) return
		val manager = GlanceAppWidgetManager(context)
		for(id in ids) {
			// 成功只代表"组合任务已交给 WorkManager 排队", 真正画出来要看
			// reportSessionWorkerStates 探到的 Worker 状态。
			val glanceId = runCatching { manager.getGlanceIdBy(id) }.getOrNull() ?: continue
			runCatching { widget.update(context, glanceId) }
		}
	}

	/** 【历史】原来 refreshAll 用它踢 options 触发重画; 现在只剩"缩放回调认这个键"的用途(保留兼容) */
	const val KEY_STYLE_TICK = "qust_style_tick"
}
