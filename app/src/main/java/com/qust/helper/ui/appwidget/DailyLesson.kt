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
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.TextAlign
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.qust.helper.data.Data
import com.qust.helper.data.lesson.Lesson
import com.qust.helper.data.lesson.LessonGroup
import com.qust.helper.data.lesson.TimeTables
import com.qust.helper.model.LessonTableRepository
import com.qust.helper.ui.activity.ComposeActivity
import com.qust.helper.ui.page.lesson.DailyLesson as TodayLessonPage
import com.qust.helper.ui.theme.LESSON_BACKGROUND_COLORS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar


/**
 * 【本轮·重写】当日课表小组件。
 *
 * 原来是 RemoteViews 实现, 有三个绕不过去的问题, 所以整体改成 Glance:
 *
 * 1. **崩溃**(用户看到的"应用发生错误 class java.lang.RuntimeException"):
 *    旧代码在 `updateLessonTime` 里用 `lessonView[count++]` 逐节标记"已结束/未开始",
 *    但 `lessonView` 只装了"每门课一个 view", 而连上两节的课会在循环里被走两遍 ——
 *    count 一超出数组长度就是 ArrayIndexOutOfBoundsException。
 *    现在状态在渲染时**按时间算**, 不靠累加游标, 从结构上不可能越界。
 *
 * 2. **跨天不刷新**: 旧代码把 RemoteViews 存在 Provider 的实例字段里, 再用
 *    `times` 计数决定"要不要重建", 换周/换天后经常还在画上一天的课。
 *    现在每次进入 `provideGlance` 都重新取一次数据, 没有缓存可脏。
 *
 * 3. **深色模式下整块变黑**: 见 res/drawable/card_background.xml 的说明。
 */
class DailyLessonReceiver : GlanceAppWidgetReceiver() {
	override val glanceAppWidget: GlanceAppWidget = DailyLesson

	/**
	 * 【第六十轮·修"同步更新还是很慢"】丢掉**内容没变**的尺寸回调。
	 *
	 * `provideGlance` 里的 [DailyLesson.applySizeHint] 会往 `OPTION_APPWIDGET_MIN_HEIGHT`
	 * 写一个"建议最小高度", 启动器收到之后会立刻回一次 `onAppWidgetOptionsChanged` ——
	 * 父类收到就整块重绘。于是"同步一次课表"实际会画两遍: 第一遍是新内容, 第二遍是白画。
	 * 用户盯着看的那块小组件自然就是"慢半拍"。
	 *
	 * 这里只比较**真正影响排版**的那几个尺寸字段; 一个都没变就直接返回, 不重绘。
	 */
	override fun onAppWidgetOptionsChanged(
		context: Context,
		appWidgetManager: AppWidgetManager,
		appWidgetId: Int,
		newOptions: Bundle
	) {
		val key = optionsKey(newOptions)
		if(lastOptions[appWidgetId] == key) return
		lastOptions[appWidgetId] = key
		super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
	}

	override fun onDeleted(context: Context, appWidgetIds: IntArray) {
		for(id in appWidgetIds) lastOptions.remove(id)
		super.onDeleted(context, appWidgetIds)
	}

	private fun optionsKey(b: Bundle): String = buildString {
		append(b.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)).append(',')
		append(b.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)).append(',')
		append(b.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)).append(',')
		append(b.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)).append(',')
		// Android 12+ 还会给一份"所有可能的精确尺寸"列表, 它变了也要重排
		append(b.get(AppWidgetManager.OPTION_APPWIDGET_SIZES)?.toString().orEmpty())
	}

		/** 上一次见过的尺寸(按组件 id) */
		val lastOptions = HashMap<Int, String>()

	/**
	 * 【第四十四轮】软件升级后系统会广播 `MY_PACKAGE_REPLACED`, 但 Glance 1.0.0 不处理它
	 * (反编译确认: onReceive 的 default 分支直接忽略) —— 启动器就会一直显示**旧版本缓存的
	 * 视图**, 新代码(玻璃位图等)永远画不上去。这里自己接管: 当作一次全量 onUpdate 重渲染。
	 */
	override fun onReceive(context: Context, intent: Intent) {
		if(intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
			val manager = AppWidgetManager.getInstance(context)
			onUpdate(context, manager, manager.getAppWidgetIds(ComponentName(context, javaClass)))
			return
		}
		super.onReceive(context, intent)
	}
}


/** 【第六十五轮·终】同 [TermLesson]: object 单例, 所有刷新共用同一份会话表, 避免 REPLACE 互相打断。 */
object DailyLesson : GlanceAppWidget() {

	/** 【第六十五轮·终】刷新计数器, 作用同 [TermLesson.refreshTick] */
	internal val refreshTick = androidx.compose.runtime.mutableStateOf(0L)

	/** 【第七十轮·五】重组合计数 —— 用途见 [TermLesson.composeSeq] 的说明 */
	internal val composeSeq = java.util.concurrent.atomic.AtomicInteger(0)

	/**
	 * 【本轮】必须用 Exact, 否则 `LocalSize` 永远返回配置表里的 minWidth/minHeight,
	 * 卡片被拉大之后我们还按最小尺寸去算"能放几节", 结果就是**只画一两行, 下面全是空白**。
	 */
	override val sizeMode = SizeMode.Exact

	/** 小组件要画的一行课 */
	private data class Item(
		/** 第几节开始(从0开始) */
		val slotIndex: Int,
		/** 这门课占几节 */
		val len: Int,
		val name: String,
		val detail: String,
		val start: String,
		val end: String,
		val status: String,
		val color: Int,
		/** 每一节各自的起止时间(如 08:00~08:45、08:55~09:40), 用于分段显示 */
		val segments: List<Pair<String, String>> = emptyList()
	) {
		/** 界面上显示的节次号(从1开始) */
		val slotNumber: Int get() = slotIndex + 1

		/** 【第五十五轮】节次标签: 1 节 = "第3节", 连堂 = "第1-2节" */
		val slotLabel: String
			get() = if(len <= 1) "第${slotNumber}节" else "第$slotNumber-${slotIndex + len}节"
	}

	/**
	 * 【第五十五轮】按真实时间轴摆好的一块课
	 * @param top    距课程区顶部的偏移(dp)
	 * @param height 块高(dp)
	 */
	private data class Block(val item: Item, val top: Float, val height: Float)

	/** 一次性取好的今日快照(取数放在 IO 里, 组合期只负责画) */
	private data class Snapshot(
		val weekText: String,
		val dayText: String,
		val items: List<Item>,
		/** 【第五十八轮】总节数: 右上角显示"共几节"(2+2=4), 不再是"几门课" */
		val totalSlots: Int = 0
	)

	override suspend fun provideGlance(context: Context, id: GlanceId) {
		// 取数可能读本地课表, 放 IO; 失败也不能让小组件崩, 退回"今天没课"
		val snapshot = withContext(Dispatchers.IO) { runCatching { buildSnapshot() }.getOrElse { Snapshot("", "", emptyList()) } }
		// 【第六十一轮】外观(玻璃/纯色 + 非课程文字颜色)与课表一起在 IO 里取好,
		// 组合期直接读传进来的值 —— 理由见 WidgetStyle 的说明(组合期读盘会拖首帧、也可能读到旧值)。
		val style = withContext(Dispatchers.IO) { runCatching { WidgetStyleStore.load() }.getOrDefault(WidgetStyle()) }
		// 【第四十六轮】渲染指纹: 下次日志能直接看出"渲染发生在何时、显示的是哪天几节课"
		// 【第五十六轮】补上"用的是哪套时间表": 用户反馈"换时间表后小组件时间没变"时,
		// 「设置 → 桌面小组件」自检页能一眼看出小组件实际拿到的是哪一套。
		// 【第七十轮·按要求】"质检功能加检查每日课表时间日志" —— 把时间表**条数、末节起止、
		// 每节课状态**一并写进日志, 「小组件质检」卡直接拿这几行判断"倒计时不动"是
		// 时间表没拿到、还是根本不在上课时段。
		// 【第六十轮·修"每日课表同步更新速度还是很慢"】
		//
		// 以前下面这两件事是 `withContext(IO)` **顺序等在前面**的 —— 它们都做完了才轮到
		// `provideContent`, 也就是说首帧要白等"写一次 AppWidgetOptions(跨进程) + 设一次
		// AlarmManager(跨进程)"。学期课表小组件没有这两步, 所以它总是先动; 用户看到的就是
		// "每日课表比学期课表慢半拍"(他的原话是"可以参考学期课表的同步更新逻辑")。
		//
		// 现在两件事都扔到 IO 上**跟渲染并行**跑, 谁也不挡首帧:
		//  · applySizeHint: 把"本周课最多那天需要的高度"写给启动器当最小高度(见该函数);
		//  · scheduleNextTick: 排"下一次必须重画的时刻"(上课中 -> 下一个整分; 还没上课 ->
		//    下一节的开始时刻; 都上完了 -> 明天 00:05), 也就是"'4 分钟后下课'随时间变化"。
		// 用独立的 scope: 首帧画完 Glance 的 session 可能就收了, 收尾工作不能被一起取消。
		val appContext = context.applicationContext
		CoroutineScope(Dispatchers.IO).launch {
			runCatching { applySizeHint(appContext, busiestDayRows()) }
			runCatching { scheduleNextTick(appContext, snapshot.items) }
		}
		provideContent {
			// 【第六十五轮·终】重组时现读样式与课表, 读 tick 保证重组必然发生
			DailyLesson.refreshTick.value
			DailyLesson.composeSeq.incrementAndGet()
			val liveSnapshot = runCatching { buildSnapshot() }.getOrElse { Snapshot("", "", emptyList()) }
			val liveStyle = runCatching { WidgetStyleStore.load() }.getOrDefault(WidgetStyle())
			// 【第八十四轮】高度来源修正, 与学期课表同款: 优先启动器的 OPTION_APPWIDGET_SIZES。
			// 用户 21:06 截图实测: 5×9 上 Glance 的 LocalSize 报 ≈226dp, 卡片真实高度 ≈283dp ——
			// 直接信 LocalSize, 课程区被压矮 57dp, 卡片下面空出一大截(与"第 11 节显示不全"
			// 同一族病因: 上报尺寸 ≠ 真实视图, 只是这次方向是"报小")。
			val liveH = runCatching { realHeightDp(context, id, LocalSize.current.height.value) }.getOrDefault(0F)
			val liveW = runCatching { realWidthDp(context, id, LocalSize.current.width.value) }.getOrDefault(0F)
			// 【第一百零五轮】渲染诊断: 原始上报值全量记录, 「桌面小组件」自检卡可见(见 WidgetDiag)
			runCatching {
				val awId = GlanceAppWidgetManager(context).getAppWidgetId(id)
				val opts = AppWidgetManager.getInstance(context).getAppWidgetOptions(awId)
				val szs = if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
					opts.getParcelableArrayList<android.util.SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES) else null
				com.qust.helper.utils.WidgetDiag.add(
					"Daily[opts] MIN=${opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)} " +
					"MAX=${opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)} " +
					"SIZES=" + (szs?.joinToString("|") { "${it.width}x${it.height}" } ?: "null") +
					" Local=${LocalSize.current.width.value}x${LocalSize.current.height.value}"
				)
			}
			GlanceTheme { DailyLessonView(context, liveSnapshot, liveStyle, liveH, liveW) }
		}
	}

	/**
	 * 【第八十四轮】读这张卡的真实高度(dp): 优先 `OPTION_APPWIDGET_SIZES`(启动器实测,
	 * 学期课表第八十三轮已验证它在这台 MIUI 上是准的), 读不到退回 [fallback]
	 * (调用方传 Glance 的 LocalSize)。
	 *
	 * **刻意不取 max(MIN_HEIGHT, MAX_HEIGHT)**: [applySizeHint] 会往 MIN_HEIGHT 里写
	 * "本周最满一天需要的高度"(可能大于真实视图), 那个键已经被我们自己污染, 不能再信。
	 */
	private fun realHeightDp(context: Context, id: GlanceId, fallback: Float): Float = runCatching {
		val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
		val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(appWidgetId)
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			val sizes = runCatching {
				options.getParcelableArrayList<android.util.SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
			}.getOrNull()
			// 竖屏候选 = "宽 < 高"的那组; 取其中最高者
			val portraitH = sizes?.filter { it.width < it.height }?.maxOfOrNull { it.height } ?: 0F
			if(portraitH > 0F) return portraitH
		}
		// 【第一百零五轮】SIZES 读不到时, 先试 maxOf(MIN/MAX_HEIGHT)(与学期课表
		// optionsHeightDp 同款优先级), **最后**才退 Glance 的 LocalSize ——
		// 这台 OPPO(ColorOS)上 LocalSize 高报小近一半(250dp vs 真实 ≈400dp),
		// 直接退它, 课程块高度被按虚小值均分, 连堂课的时间只画得出前几节
		// (用户实测"课程到第 11 节, 小组件只显示到第 7 节, 永远差 4 节")。
		// 而 MAX_HEIGHT≈真实×1.09(报大, 可乘系数校准), MIN≈provider 的 minHeight。
		val mm = maxOf(
			options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT),
			options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
		)
		if(mm > 0) return mm.toFloat()
		fallback
	}.getOrDefault(fallback)

	/**
	 * 【第九十三轮】读这张卡的**宽度**(dp): 与 [realHeightDp] 同一套优先级
	 * (SIZES 竖屏组 → Glance 的 LocalSize 回退)。
	 *
	 * 宽度不参与排版, 只服务于 MIUI 渲染密度**分档** —— options 高度相同的两个实例
	 * (5×9 与 4×7 都是 362dp)渲染密度不同, 宽度(274 vs 332)才是唯一能区分两档的信号。
	 */
	private fun realWidthDp(context: Context, id: GlanceId, fallback: Float): Float = runCatching {
		val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
		val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(appWidgetId)
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			val sizes = runCatching {
				options.getParcelableArrayList<android.util.SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
			}.getOrNull()
			// 竖屏候选 = "宽 < 高"的那组; 取其中最宽者(与高度的取法对齐)
			val portraitW = sizes?.filter { it.width < it.height }?.maxOfOrNull { it.width } ?: 0F
			if(portraitW > 0F) return portraitW
		}
		fallback
	}.getOrDefault(fallback)

	/**
	 * 【第五十九轮】算出"下一次必须重画的时刻"并交给 [WidgetTicker]。
	 *
	 * 三档(见 [WidgetTicker] 的说明):
	 *  ① 有课正在上 -> 下一个整分: 倒计时需要每分钟减 1;
	 *  ② 有课还没开始 -> 那一节的开始时刻: 状态要从"未开始"翻成"还有 N 分钟下课";
	 *  ③ 今天没课 / 都上完了 -> 明天 00:05: 跨天要把"第 N 周 / 几月几号"以及"今天有没有课"翻过去。
	 *
	 * 时间解析失败(自定义时间表格式坏了)的课直接跳过 —— `parseTime` 会给 -1, 不能拿它算闹钟。
	 */
	private fun scheduleNextTick(context: Context, items: List<Item>) {
		val now = Calendar.getInstance()
		val nowMin = now[Calendar.HOUR_OF_DAY] * 60 + now[Calendar.MINUTE]
		val nowMs = now.timeInMillis
		var ongoing = false
		var nextStart = -1
		for(item in items) {
			val s = TimeTables.parseTime(item.start)
			val e = TimeTables.parseTime(item.end)
			if(s < 0 || e < 0) continue
			if(nowMin >= s && nowMin < e) ongoing = true
			if(s > nowMin && (nextStart < 0 || s < nextStart)) nextStart = s
		}
		val target = when {
			// ① 下一个整分: 倒计时必须"看得见地"在走
			ongoing -> (nowMs / 60_000L + 1L) * 60_000L
			// ② 下一节上课: 早 1 秒点火, 保证状态一定翻过来
			nextStart >= 0 -> nowMs + (nextStart - nowMin) * 60_000L + 500L
			// ③ 明天 00:00:02
			// 【第九十九轮·修"跨天慢近 6 分钟"】以前排明天 **00:05**(整 5 分钟偏移),
			// 再叠加闹钟/系统调度延迟, 用户看到"第二天的小组件比北京时间慢了近 6 分钟"。
			// 跨天要做的只是"过了 0 点"翻周次/日期, 排 0 点过 2 秒即可(留 2 秒防边界竞争)。
			else -> {
				val c = Calendar.getInstance()
				c.add(Calendar.DAY_OF_MONTH, 1)
				c[Calendar.HOUR_OF_DAY] = 0
				c[Calendar.MINUTE] = 0
				c[Calendar.SECOND] = 2
				c[Calendar.MILLISECOND] = 0
				c.timeInMillis
			}
		}
		WidgetTicker.schedule(context, target)
	}

	/**
	 * 组装今天的课程列表。
	 *
	 * 与课表页共用同一套数据源(`getTodayLessonGroups`), 所以调休、按日期手动改课
	 * 在小组件上也是生效的 —— 这也是旧实现"每天显示的还是上周的课"的另一个原因:
	 * 它读的是 `activeDay` 而不是"今天实际生效的课"。
	 */
	private fun buildSnapshot(): Snapshot {
		LessonTableRepository.updateDate()
		val week = LessonTableRepository.currentWeek.intValue
		val day = LessonTableRepository.dayOfWeek.intValue
		val groups: Array<LessonGroup?> = TodayLessonPage.getTodayLessonGroups()

		val now = Calendar.getInstance()
		val nowMinutes = now[Calendar.HOUR_OF_DAY] * 60 + now[Calendar.MINUTE]
		val starts = TimeTables.currentTimes[0]
		val ends = TimeTables.currentTimes[1]

		val items = ArrayList<Item>(groups.size)
		for(i in groups.indices) {
			val lesson: Lesson = groups[i]?.getCurrentLesson(week) ?: continue
			val endIndex = (i + lesson.len - 1).coerceIn(0, ends.size - 1)
			val startAt = TimeTables.parseTime(starts.getOrNull(i) ?: continue)
			val endAt = TimeTables.parseTime(ends.getOrNull(endIndex) ?: continue)
			// 时间缺失/格式异常时 parseTime 给负数, 这时只显示时间不给状态
			val status = when {
				startAt < 0 || endAt < 0 -> ""
				nowMinutes >= endAt -> "已结束"
				nowMinutes < startAt -> "未开始"
				else -> "${endAt - nowMinutes} 分钟后下课"
			}
			val detail = when {
				lesson.place.isEmpty() -> lesson.teacher
				lesson.teacher.isEmpty() -> lesson.place
				else -> "${lesson.place} · ${lesson.teacher}"
			}
			// 【第五十五轮】连堂课的"每一节"各自的起止时间(中间有 10 分钟课间),
			// 界面上要按段显示: 第1-2节 = 08:00-08:45 / 08:55-09:40
			val segments = (i..endIndex).mapNotNull { s ->
				val st = starts.getOrNull(s)
				val en = ends.getOrNull(s)
				if(st.isNullOrEmpty() || en.isNullOrEmpty()) null else st to en
			}
			items.add(
				Item(
					slotIndex = i,
					// 夹一下: 时间表被改成更少的节数时, 旧的 len 可能已经越界
					len = lesson.len.coerceIn(1, (starts.size - i).coerceAtLeast(1)),
					name = lesson.name,
					detail = detail,
					// 【第二十九轮·修"时间被省略成 10:00 - 1…"】把起止时间拆成两行,
					// 一行只放一个时刻(5 个字符), 再窄的卡片也放得下, 不会被 maxLines 截断。
					start = starts.getOrNull(i).orEmpty(),
					end = ends.getOrNull(endIndex).orEmpty(),
					status = status,
					color = lesson.color.coerceIn(0, LESSON_BACKGROUND_COLORS.size - 1),
					segments = segments
				)
			)
		}

		return Snapshot(
			// 【第三十二轮·修"第 10 周其实是第 9 周"】currentWeek 本身就是**1 基**的(第 9 周就是 9),
			// 这里又 +1 就多算了一周。取课用的是同一个 week, 所以只有显示错了, 内容是对的。
			weekText = "第 $week 周",
			// 【按要求】"月-日"和"周几"前后互换: 日期在前、星期在后
			dayText = "${now[Calendar.MONTH] + 1}-${now[Calendar.DATE]} ${Data.WEEK_STRING.getOrElse(day) { "" }}",
			items = items,
			// 【第五十八轮】"2 节"要改成"4 节": 右上角是今天的**总节数**, 不是"几门课"
			totalSlots = items.sumOf { it.len }
		)
	}

	@Composable
	private fun DailyLessonView(context: Context, snapshot: Snapshot, style: WidgetStyle, optionsH: Float, optionsW: Float = 0F) {
		val size = LocalSize.current
		// 【第一百零五轮·终版】缩放系数的宽度来源修复: LocalSize 宽在这台 OPPO 上是
		// 纯垃圾(实测同一张卡两次渲染分别报 578.7 和 320, 导致 scale 在 1.4/1.0 之间
		// 乱跳 —— 用户看到的"课程越改越少"就是字号跟着垃圾值变化)。改用
		// realWidthDp(OPTION_APPWIDGET_SIZES 竖屏宽, 实测=320 准确), LocalSize 兜底。
		val trueW = if(optionsW > 0F) optionsW else size.width.value
		val scale = (trueW / 320F).coerceIn(0.85F, 1.4F)
		fun fs(base: Float) = (base * scale).sp
		fun ds(base: Float) = (base * scale).dp

		// 【第七十三轮·修"5×9 上'11-3 周二'被第一行课压住"】表头高度跟着系统字号放大:
		// 表头里两行字是 sp、表头高度是 dp, fontScale > 1 时实际文字高度会顶破 40dp 的框;
		// 5×9 这种窄卡 scale 最小、余量最薄, 恰好压线溢出 —— 日期的下半截被课程行盖住。
		// 表头乘 fontScale 之后, 课程区(innerHeight)自动让出这一截。
		val fontScale = (LocalContext.current.resources.configuration.fontScale).coerceIn(0.85F, 1.6F)
		// 【第七十七轮·再修"11-3 周二 被下面课程压住一点"】
		// 表头里是两行 sp 文字(16sp + 11sp ≈ 27sp), 而框高只有 40dp: fontScale 稍微一放大
		// (这台机器 ≈1.05) 两行就要 42~44dp, 第二行(日期)被 Row 的固定高度裁掉半行,
		// 看着就是"被下面的课程压住"。这里按两行文字的实际高度给足(16+11 = 27sp × 1.6),
		// 课程区的高度由 innerHeight 自动让出, 不会因此少显示课程。
		val headerHeight = ds(44F) * fontScale
		val items = snapshot.items

		// 【第五十五轮·按要求】改成**真实时间轴**排版, 不再"每节均分":
		// 以前每一节固定一个高度(上限 46dp), 于是 1-2 节和 5-6 节看起来一样长、
		// 位置也和实际时间对不上(用户原话"根本不够5节课的宽度, 每节课太宽了,
		// 而且每节课的位置也没有对应其时间")。
		// 【第五十六轮】时间轴的上下端点改成**整天**(第一节上课 ~ 最后一节下课),
		// 这样"今天只有第 5-6 节有课"时它才会落在第 5-6 节该在的位置, 而不是被顶到最上面。
		// 【第五十六轮】最后再减 8dp: 上报尺寸通常比实际视图大几 dp,
		// 按它算出来的可用高度会偏大, 课表就会压到卡片下边缘外面。留一点余量兜住。
		// 【第八十四轮】高度优先用 realHeightDp(OPTION_APPWIDGET_SIZES, 启动器实测):
		// 这台 MIUI 上 Glance 的 LocalSize 对 5×9 会报小 ~20%(226 vs 实际 283dp),
		// 直接用它课程区被压矮、卡片下面空出一大截。SIZES 读不到才退回 LocalSize。
		// 【第九十一轮】与学期课表同款的两档校准(原按 options 高度分档: 281/362→1.26, 388/492/495→1.0)。
		// 【第九十三轮·分档依据换成"options 宽度"】用户桌面重排后出现 options=362dp 的今日课表,
		// 与 5×9 今日课表高度完全相同(MIN/MAX/Local 也都相同), 但它按 1:1 渲染(截图实测
		// 卡高 ≈350dp、宽 ≈332dp), 沿用 1.26 底部直接裁掉一节课 —— 高度判据失效。
		// 两个实例唯一的差别是**宽度**(5×9=274、4×7=332)。回看全部标定数据, 宽度完美区分
		// 两档且所有此前正常的实例全部兼容: 宽 274(5×9 学期/今日)→1.26;
		// 宽 332/343/345/351(4×6/4×7)→1.0。边界 295~305 线性过渡, 只认宽度数值。
		val miuiK = when {
			optionsW <= 0F -> 1F
			optionsW <= 295F -> 1.26F
			optionsW >= 305F -> 1.0F
			else -> 1.26F - (optionsW - 295F) / 10F * 0.26F
		}
		val miuiOn = android.os.Build.MANUFACTURER.contains("xiaomi", true) ||
			android.os.Build.BRAND.contains("xiaomi", true) ||
			android.os.Build.BRAND.contains("redmi", true)
		// 【第一百零五轮·终版(渲染诊断数据定案)】这台 OPPO 实测(自检卡"渲染诊断"):
		//   SIZES=578.7x225.3|320x442.7 → 竖屏组 320x442.7 与 MAX=442 **互相印证 = 准确**;
		//   LocalSize 是纯垃圾(同一张卡两次渲染分别报 578x225 / 320x442, 宽高互换乱飘)。
		//   => 非小米: options 原值直用, **不打任何折扣**(之前的 0.92/0.88/0.82 全是在
		//      给一个准确的值乱打折); MIUI 分支不变(miuiK 校准照旧)。
		// 【第一百一十一轮·v238·高度校准】与学期课表同款: ColorOS 改桌面布局后 options 全套
		// 陈旧(内部互相印证、代码无法判别), 校准档位交给用户标定(见 Keys.KEY_WIDGET_HEIGHT_CAL);
		// 默认 100 = 原值直用零影响, 小米路径不叠加。
		val calH = if(miuiOn) 1F else WidgetStyleStore.heightCal() / 100F
		val viewH = (if(optionsH > 0F && miuiOn) optionsH * miuiK else
			if(optionsH > 0F) optionsH else size.height.value) * calH
		com.qust.helper.utils.WidgetDiag.add(
			"Daily[view] optsH=$optionsH localH=${size.height.value} localW=${size.width.value} viewH=$viewH cal=$calH scale=$scale"
		)
		// 【第一百零五轮·v232】时间表原始条目数: 下次一眼分清"时间显示不全"是
		// 数据侧(表里就没有)还是渲染侧(表里有、桌面没画出来)。
		runCatching {
			com.qust.helper.utils.WidgetDiag.add(
				"Daily[tt] slots=${TimeTables.currentSlotCount} starts=${TimeTables.currentTimes[0].size}/${TimeTables.currentTimes[0].lastOrNull().orEmpty()} ends=${TimeTables.currentTimes[1].size}/${TimeTables.currentTimes[1].lastOrNull().orEmpty()}"
			)
		}
		// 【第九十五轮】尾部余量 14F -> 8F: 用户要求"底部留白缩小一半, 让课程同步加高"。
		// 第 93 轮裁行的真凶其实是宽度分档选错(已修), 这 14F 里只有取整误差那一小部分是必要的,
		// 回退到 8F(保留 92 轮之前的基础余量 + 取整误差), 课程区因此能多占 6dp 高度。
		// 之前的安全余量说明: slotBlocks 的块高按 innerHeight 均分, 表头实际渲染高度
		// 与估算值之间有小幅取整误差 —— 8F 足以兜住。
		val innerHeight = (viewH - CardPaddingV * 2F - headerHeight.value - 8F).coerceAtLeast(1F)
		val rowGap = ds(2F).value
		val blocks = slotBlocks(items, innerHeight, rowGap, TimeTables.currentSlotCount)

		// 【第一百零五轮·v236·全天统一时间字号】v233 只给 fill 块(当天最后一块)按块高
		// 撑开字号, 结果一天有两门课时第一门还是 8.5sp、第二门 16sp —— 用户反馈
		// "怎么字号不一样"。改为**全天一个字号**: 取各块"块高 ÷ (标题+时间行数) ÷ 1.40"
		// 的最小值 —— 行数最多的块恰好放满, 其余块同一字号(略有余量), 全卡观感一致。
		var dayTimeFont = 16F
		for(b in blocks) {
			val bh = (if(b === blocks.last()) innerHeight - b.top else b.height) - 3F
			dayTimeFont = minOf(dayTimeFont, bh / ((b.item.segments.size + 1) * 1.40F))
		}
		dayTimeFont = dayTimeFont.coerceIn(7F, 16F)

		// 【第三十九轮】卡底换成真实磨砂玻璃: GlassRoot 先铺一层 card_background 兜底,
		// 再把"糊好的壁纸 + 白蒙版 + 圆角"的位图盖上去(配方与几何见 WidgetGlass 的说明)。
		// 点击挂在最外层 —— 玻璃层本身不可点, 不会挡住内容层。
		GlassRoot(
			style,
			modifier = GlanceModifier
				.fillMaxSize()
				// 【本轮·修复"点击没反应"】原先用 actionRunCallback, 回调里再 context.startActivity。
				// 那个 context 属于 Receiver, Android 10+ 会按"后台启动 Activity"直接拦掉, 于是点了毫无反应。
				// 改用 actionStartActivity: 由系统以"用户点击"的身份直接拉起 Activity, 不受该限制。
				// 【第二十九轮·修"点了白屏"】这里原来写的是字面量 "dailyLesson", 而 Data.Pages 里注册的
				// key 是 Keys.Page.DailyLessonPage("dailyLessonPage") —— 差一个后缀就查不到页面,
				// ComposeActivity 直接落到 "This page is empty."。改成引用常量, 以后改名也不会再错。
				.clickable(actionStartActivity(ComposeActivity.intentFor(context, com.qust.helper.data.Keys.Page.DailyLessonPage)))
		) {
			Column(
				modifier = GlanceModifier
					.fillMaxSize()
					// 【第九十六轮】水平内边距 10dp -> 6dp: 用户要求"课程同步加宽"。
					// 底部留白减半(v3.6.0214)只加高了课程区, 这里再把左右各收 4dp,
					// 课程条/表头整体加宽 8dp; 6dp 仍足够避让 22dp 圆角(圆角水平侵占 ≈22-(22-6)×0.7 ≈ 5dp 内)。
					.padding(horizontal = 6.dp, vertical = CardPaddingV.dp)
			) {
				Row(
				modifier = GlanceModifier
					.fillMaxWidth()
					.height(headerHeight)
					// 【第九十八轮】表头单独再加 6dp 水平内边距: 5×9 网格上"第 N 周"和"6 节"
					// 紧贴卡片左右边缘(外层只有 6dp), 视觉上顶到边; 再收 6dp 后文字离边缘 12dp。
					.padding(horizontal = 6.dp),
				verticalAlignment = Alignment.CenterVertically
			) {
					// 【第三十二轮·按要求】两行互换:「第 N 周」搬到上面、字号变大;
					// 「10-31 周六」搬到下面、字号变小(并且日期在星期前面)。
					Column(modifier = GlanceModifier.defaultWeight()) {
						Text(
							text = snapshot.weekText.ifEmpty { "青科助手" },
							style = TextStyle(color = style.text, fontSize = fs(16F), fontWeight = FontWeight.Medium),
							maxLines = 1
						)
						Text(
							text = snapshot.dayText.ifEmpty { "今天" },
							style = TextStyle(color = style.text, fontSize = fs(11F)),
							maxLines = 1
						)
					}
					Text(
						// 【第五十八轮】总节数(连堂课按实际节数累加): 第1-2节 + 第5-6节 = 4 节
						text = if(items.isEmpty()) "" else "${snapshot.totalSlots} 节",
						style = TextStyle(color = style.text, fontSize = fs(11F)),
						maxLines = 1
					)
				}

				if(blocks.isEmpty()) {
					Box(modifier = GlanceModifier.fillMaxWidth().defaultWeight(), contentAlignment = Alignment.Center) {
						Column(horizontalAlignment = Alignment.CenterHorizontally) {
							Text(
								text = "今天没有课",
								style = TextStyle(color = style.text, fontSize = fs(14F))
							)
							Spacer(modifier = GlanceModifier.height(4.dp))
							Text(
								text = "点击查看今日课表",
								style = TextStyle(color = style.text, fontSize = fs(11F))
							)
						}
					}
				}else{
					Column(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
						// 每块按算好的 top 往下推: 顶部留白 = 与上一块底部的间距,
						// 于是"第 5-6 节"会真的落在它该在的时间位置上
						var cursor = 0F
						for(block in blocks) {
							val gap = block.top - cursor
							if(gap > 0.5F) Spacer(modifier = GlanceModifier.fillMaxWidth().height(gap.dp))
						// 【第七十轮】把内容区总宽传进去: LessonRow 要用"总宽 - 时间列 - 状态列"
						// 估出课名列宽度, 才能做逐字测量(见 LessonRow 的说明)。
						// 【第一百零五轮·v231】最后一个块 fill=true + rowModifier=defaultWeight:
						// 铺满剩余高度, 消灭块下方的米白留白(单课日 = 整块铺满)。
						// defaultWeight 只在 Row/Column 作用域内可见, 必须在这里算好传入。
						LessonRow(
							item = block.item, style = style, scale = scale, rowHeight = block.height,
							contentWidth = size.width.value - CardPaddingV * 2F,
							fill = block === blocks.last(),
							rowModifier = if(block === blocks.last()) GlanceModifier.defaultWeight() else GlanceModifier.height(block.height.dp),
							// 【第一百零五轮·v233】fill 块的真实高度 = 课程区高 - 块顶
							// (defaultWeight 分给它的就是这一段), 用于按块高撑开时间行。
							fillHeight = innerHeight - block.top,
							// 【第一百零五轮·v236】全天统一的时间字号
							timeFont = dayTimeFont
						)
							cursor = block.top + block.height + rowGap
						}
					}
				}
			}
		}
	}

	/**
	 * 把今天的课按"节次格"摆进 [available] 高度里。
	 *
	 * 【第八十四轮·按用户要求回退第八十一轮的"跨度式"】用户原话: "每日课表原来课程宽度不都是
	 * 固定的吗，谁让你这样改了"。恢复第六十七轮定稿的**整天固定格**几何:
	 *   · 跨度永远 = 整套时间表(第 1 节 ~ 末节), **不随今天有几门课伸缩**;
	 *   · 每一节的高度 = 可用高度 / 格数×2, 因此"2 节连堂的课"永远占同样大的块 ——
	 *     今天 2 门课和今天 5 门课, 单个课块一样大(用户 19:30 旧图对比 21:05 新图定的规矩);
	 *   · 节数为奇数(如 11 节)时, 压在最后一节上的课块补到满格(2 unit), 不出现半格矮块。
	 * 第八十一轮"课到哪卡片用到哪"的跨度式删除 —— 它把 2 门课拉成半张卡高的巨块, 用户不认可。
	 */
	private fun slotBlocks(items: List<Item>, available: Float, gap: Float, slotCount: Int): List<Block> {
		if(items.isEmpty()) return emptyList()
		// 节数以时间表为准; 万一某门课的 len 越界(时间表被改小了), 也要保证最后一格画得下
		val slots = slotCount.coerceAtLeast(items.maxOf { it.slotIndex + it.len }).coerceAtLeast(1)
		// 格数 = ceil(节数 / 2), 一格再拆成 2 个 slotUnit -> 总 unit = 格数 × 2
		val cells = (slots + 1) / 2
		val unit = available / (cells * 2).toFloat()
		val oddTail = slots % 2 == 1
		return items.map { item ->
			val top = item.slotIndex * unit
			var height = (item.len * unit - gap).coerceAtLeast(1F)
			if(oddTail && item.slotIndex + item.len >= slots) {
				height = maxOf(height, 2F * unit - gap)
			}
			Block(item, top.coerceIn(0F, (available - height).coerceAtLeast(0F)), height)
		}
	}

	/**
	 * 【第一百零五轮·v234/v235·位图时间列】把"节次标题 + 每节起止时间"画成一张位图。
	 *
	 * v234 用 Typeface.MONOSPACE 画, 但真机截图证明**它也被映射成了比例字体** ——
	 * ColorOS 的全局字体替换连 monospace 家族都不放过("1"窄"0"宽, 冒号/连字符仍漂移)。
	 * 结论: 这台设备上靠任何字体家族都拿不到等宽字形。
	 *
	 * **v235·逐字符定宽网格**: 不再要求字体等宽, 每个字符自己定位 ——
	 *   · 单元格宽 = 时间串里全部字符("0123456789:-")中最宽的步进;
	 *   · 每个字符在自己的格子里**居中**绘制;
	 *   · 冒号永远在第 3 格、连字符永远在第 6 格, x 恒定 → **物理上严格竖直对齐**;
	 *   · 视觉效果与真等宽字体一致(窄字符"1"两侧留均等空隙, 正是等宽字体的排布方式)。
	 * 标题墨迹左缘与时间行首字符墨迹左缘用 getTextBounds 精确对齐(此前标题偏左 ~4px)。
	 *
	 * 行距 = 字号 × 1.40(撑开系数与 v233 一致); 所有行按节次均分。
	 * 尺寸: 本卡 ≈262x800px(ARGB_8888 ≈840KB), GlassRoot 用资源图不占 payload,
	 * 整包仍低于 RemoteViews 的 1MB 限制。
	 *
	 * @param fontSizeSp 时间行字号(sp, 已含 timeFontRatio); 标题同行号
	 * @param colorArgb  文字颜色(ARGB int, 由调用方从 ColorProvider 解析)
	 */
	private fun buildTimeColumnBitmap(
		context: Context, title: String, rows: List<String>,
		fontSizeSp: Float, colorArgb: Int
	): android.graphics.Bitmap {
		val dm = context.resources.displayMetrics
		val fs = context.resources.configuration.fontScale.coerceIn(0.85F, 1.6F)
		val tpx = (fontSizeSp * fs * dm.density).coerceAtLeast(4F)
		val pitch = tpx * 1.40F
		val titlePaint = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
			textSize = tpx
			typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
		}
		val monoPaint = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
			textSize = tpx
			typeface = android.graphics.Typeface.MONOSPACE
		}
		// 定宽网格: 时间串里可能出现的全部字符取最宽步进作单元格
		val cellW = "0123456789:-".maxOf { monoPaint.measureText(it.toString()) }
		// 标题墨迹左缘 = 时间行首字符("0")墨迹左缘
		val rb = android.graphics.Rect()
		monoPaint.getTextBounds("0", 0, 1, rb)
		val rowInkLeft = (cellW - monoPaint.measureText("0")) / 2F + rb.left
		val tb = android.graphics.Rect()
		titlePaint.getTextBounds(title, 0, 1, tb)
		val titleX = (rowInkLeft - tb.left).coerceAtLeast(0F)
		val w = (maxOf(titleX + titlePaint.measureText(title), rows.maxOf { it.length } * cellW).toInt() + 2).coerceAtLeast(1)
		val n = rows.size + 1
		val base0 = -titlePaint.fontMetrics.ascent
		val h = (base0 + (n - 1) * pitch + titlePaint.fontMetrics.descent + 2F).toInt().coerceAtLeast(1)
		val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
		val c = android.graphics.Canvas(bmp)
		titlePaint.color = colorArgb
		monoPaint.color = colorArgb
		c.drawText(title, titleX, base0, titlePaint)
		rows.forEachIndexed { i, r ->
			val y = base0 + (i + 1) * pitch
			var x = 0F
			for(ch in r) {
				val cw = monoPaint.measureText(ch.toString())
				c.drawText(ch.toString(), x + (cellW - cw) / 2F, y, monoPaint)
				x += cellW
			}
		}
		com.qust.helper.utils.WidgetDiag.add("Daily[bmp] ${w}x${h} font=${fontSizeSp} cell=$cellW rows=${rows.size}")
		return bmp
	}

	@Composable
	private fun LessonRow(item: Item, style: WidgetStyle, scale: Float, rowHeight: Float, contentWidth: Float, fill: Boolean = false, rowModifier: GlanceModifier = GlanceModifier, fillHeight: Float = 0F, timeFont: Float = 0F) {
		// ── 【第六十七轮·按要求】"今日课表一节课的课程信息无法全部展示, 可以将信息写在一行当中;
		//    按实际情况设最小字号并缩字" ───────────────────────────────────────────
		//
		// 和「学期课表」同一套思路: **先算这一格真的有多少高度, 再由高度反推字号**。
		// 以前的写法是"字号写死 13/10/8.5sp, 再用 if(rowHeight >= 30dp) 决定要不要画第二行" ——
		// 行高一旦落在阈值下面, 地点/老师整片消失, 而且字一点都不会变小, 只在行里被裁掉。
		//
		// ── 【第七十轮·按要求】"每日课表小组件也要参考这种字号缩小的方法来尽可能显示课程完整内容" ──
		// 在第六十八轮"字号范围"之上再进一步(与「本周课表」完全同款):
		//   ① 先试**两行模式**: 课名(最多折 2 行, Paint 逐字测量真实行数) + 地点·教师(1 行),
		//      从基准字号 1.00 往下按 0.04 一档试到 0.72 —— 第一个装得下的字号就是它;
		//   ② 缩到下限还装不下 -> 退回旧的"合并成一行"路径(课名 地点·老师, 再缩字);
		//   ③ 一行还放不下 -> 按 innerH/需要高度 等比缩, 下限 3.2sp。
		// "网络数据开发技术"这种课名在旧版里 maxLines=1 直接被掐出省略号, 现在先缩字、
		// 再折行, 尽可能把课名完整画出来。
		val baseName = 13F * scale
		val baseDetail = 10F * scale
		val baseSmall = 8.5F * scale
		val lineFactor = 1.28F

		// 【第一百零五轮·终版】块高钳制与 segmentLines 截断**全部删除**(诊断数据定案):
		//   · viewH 已改为 options 原值(MAX=442 与 SIZES 互相印证 = 准确), 块高 10u
		//     不再虚高, 之前防"块超卡"的钳制失去意义;
		//   · segmentLines 的 -1 是"永远差最后一节"的真凶; 且行高的真实值(TextView
		//     含 fontPadding ≈1.46×字号)比 lineFactor=1.28 的估算大, 历史上"segLines=11
		//     却只见 9 行"就是估算偏低导致最后一行被挤出块底。=> 时间行**全量画出**,
		//     字号固定, 块高(时间比例 10u≈291dp)远大于内容(11 行≈136dp), 永不裁切。
		//   · fill=true(当天最后一个块): 用 defaultWeight 铺满标题以下全部空间,
		//     消灭块下方的米白留白(单课日=整块铺满); 权重由系统分配, 永不超卡。
		// 上下各留 1.5dp: 圆角 6dp 在 12dp 的行上会啃掉两个角, 留一点呼吸位
		// 【第一百零五轮·v233】fill 块的真实高度由调用点传入(fillHeight = 课程区高 - 块顶),
		// 替换 v231 的 320F 假定值 —— 短块(如下午最后一节 2 节课)也拿得到准确高度。
		val blockH = if(fill && fillHeight > 0F) fillHeight else rowHeight
		val innerH = (blockH - 3F).coerceAtLeast(3F)

		// 名称列的实际宽度(dp): 总宽 - 左右 padding(4dp×2) - 段间距(3dp) - 时间列 - 状态列
		// 状态列("45 分钟后下课")只在有状态时存在, 按 detail 字号 × 7 字预留。
		// 【第七十二轮】曾用 Paint 实测等宽字串宽度来定时间列宽 —— 真机上 Paint 度量与
		// TextView 实际渲染仍有出入(字体 fallback / 字距), 4×7 反而比 59dp 时代截得更早。
		//
		// 【第七十三轮·修"4×7/5×9 时间显示不全"】换成**结构性**方案: 时间列不再定宽,
		// 让 TextView 按自己的文字量出宽度(wrap content) —— 文字有多宽列就有多宽,
		// 从原理上不可能再被省略号截断, 与设备字体度量彻底无关。
		// 列宽估算(timeColumnEstimate)只剩一个用途: 给课名字号做预算。按 1.3 倍
		// 从**悲观**方向估(真机实测渲染宽 ≈ Paint 值的 1.3 倍), 上限 = 内容区宽的一半
		// —— 极窄的卡上宁可把时间字号等比缩小(timeFontRatio), 也不让课名没地方。
		val fontScale = (LocalContext.current.resources.configuration.fontScale).coerceIn(0.85F, 1.6F)
		val dm = LocalContext.current.resources.displayMetrics
		val baseSmallPx = baseSmall * dm.scaledDensity
		val timeNeedDp = android.graphics.Paint().apply {
			isAntiAlias = true; typeface = android.graphics.Typeface.MONOSPACE; textSize = baseSmallPx
		}.measureText("00:00-00:00") / dm.density
		val labelNeedDp = android.graphics.Paint().apply {
			isAntiAlias = true; textSize = baseSmallPx
		}.measureText(item.slotLabel) / dm.density
		val timeColumnEstimate = maxOf(timeNeedDp * 1.3F, labelNeedDp, 40F)
			.coerceAtMost(contentWidth * 0.5F)
		// 预算被上限压住、时间串按最大字号放不下 -> 时间文字等比缩小, 配合自适应列宽双保险
		val timeFontRatio = if(timeNeedDp > timeColumnEstimate) timeColumnEstimate / timeNeedDp else 1F
		val statusReserve = if(item.status.isNotEmpty()) baseDetail * 7F else 0F
		val nameWidth = (contentWidth - 8F * scale - 26F * scale - timeColumnEstimate - statusReserve).coerceAtLeast(36F)

		var nameLines = 1
		var fit = 1F
		var twoLine = false
		if(item.detail.isNotEmpty()) {
			var f = 1F
			while(f >= WidgetFontRange.MIN_RATIO - 1e-4F) {
				val ns = baseName * f
				val ds = baseDetail * f
				val nl = WidgetFontRange.rawLineCount(item.name, ns, nameWidth).coerceAtMost(2)
				if((nl * ns + ds) * lineFactor <= innerH) {
					nameLines = nl; fit = f; twoLine = true; break
				}
				f -= 0.04F
			}
		}
		if(!twoLine) {
			// 两行模式整段放不下(或没有地点/教师): 退回旧路径 —— 能两行就两行、不行合并一行,
			// 高度再不够就按比例缩(下限 0.42, 单行时给得更低以免合并行也被裁)。
			val twoLineH = (baseName + baseDetail) * lineFactor
			val oneLineH = baseName * lineFactor
			val canTwo = item.detail.isNotEmpty() && innerH >= twoLineH * WidgetFontRange.MIN_RATIO
			val need = if(canTwo) twoLineH else oneLineH
			fit = (innerH / need.coerceAtLeast(0.1F))
				.coerceIn(if(canTwo) WidgetFontRange.MIN_RATIO else 0.42F, 1F)
			nameLines = 1
			twoLine = canTwo
		}

		val nameSize = (baseName * fit).coerceAtLeast(3.2F)
		val detailSize = (baseDetail * fit).coerceAtLeast(2.8F)
		val smallSize = (baseSmall * fit).coerceAtLeast(2.6F)

		// 左列: 一行放得下 "08:00-08:45"(11 字符) —— 卡片窄时跟着 scale 一起缩
		// 【本轮·修"10:45 和 11:40 竖直没对齐"】时间串改用**等宽字体**(Monospace):
		// 默认字体(MIUI 上是 MiSans)的数字宽度不等("1"窄、"0"宽), "10:00-10:45" 里的
		// 连字符和第二段时间的落点会随着数字组合漂移, 上下两行怎么都对不齐。
		// 等宽字体里所有数字/冒号/连字符同宽, 上下两行严格落在同一竖线上。
		// 列宽同步从 56 放宽到 59 —— 等宽字略宽于比例字, 11 个字符留足不省略。
		// 【第一百零五轮·v229~v231·演进定案】v227 的"高度维度缩字 + segmentLines 行数估算"
		// 机制最终整体删除(v231), 原因见下 —— 渲染诊断数据(WidgetDiag, 自检卡可见)定案。
		// 【第一百零五轮·v231·时间行全量画出】heightFit/segmentLines 截断机制**整体删除**:
		// 诊断数据定案 —— ① lineFactor=1.28 低估 TextView 真实行高(含 fontPadding
		// ≈1.46×字号), "segLines=11 却只见 9 行"就是最后一行被挤出块底;
		// ② 时间比例高度 10u 与"标题+10 行文字"总高天然接近, 任何"能放几行"的估算
		// 都会在临界点砍行。=> 字号固定(smallSize), 时间行**有多少画多少**, 块高
		// (10u≈291dp)远大于内容(11 行≈136dp), 从原理上不可能再裁切。
		val showSegments = item.segments.isNotEmpty()
		val showRange = !showSegments && item.start.isNotEmpty() && item.end.isNotEmpty()
		// 【第一百零五轮·v236·全天统一时间字号】时间字号由调用点按"全天最小可容量"算好
		// 传入(dayTimeFont), 所有块同字号 —— 不再是"fill 块撑大、其余块 8.5sp"的两张皮。
		// (v233 的按块撑开逻辑移到了 DailyLessonView 里做全天统一, 见那边注释。)
		val spreadSize = if(showSegments && timeFont > 0F) timeFont else smallSize
		com.qust.helper.utils.WidgetDiag.add(
			"Daily[row] rowH=$rowHeight innerH=$innerH fillH=$fillHeight segs=${item.segments.size} len=${item.len} " +
			"fit=$fit fill=$fill spread=$spreadSize scale=$scale contentW=$contentWidth"
		)

		Row(
			modifier = GlanceModifier
				.fillMaxWidth()
				// 【第一百零五轮·v231】fill=true(当天最后一个块)时调用点(Column 作用域内)
				// 传入 defaultWeight 铺满剩余高度(消灭块下米白留白); 其余块传固定高度。
				// defaultWeight 是 RowScope/ColumnScope 的成员扩展, 本函数体内不可见,
				// 所以由调用点算好后经 rowModifier 传入(Glance 1.0.0 的 API 限制)。
				.then(rowModifier)
				// 【第五十九轮·约束④】与「本周课表」共用同一个配方(WidgetGlass.block):
				// 去饱和 15% + 78% 半透。以前这里是**全不透明**的原色, 两张卡片并排时
				// 一眼就能看出"今日课表的色块更实、更艳", 用户要求"风格统一"。
				.background(ColorProvider(WidgetGlass.block(item.color, style.dark)))
				// 【第六十轮·验收标准⑥"课程色块圆角 10~16px"】12dp 在 2.75x 屏上是 33px,
				// 比课程块自己的高度还接近一半, 显得过圆; 6dp ≈ 16px 正好落在要求区间内。
				.cornerRadius((6F * scale).dp)
				.padding(horizontal = (4F * scale).dp),
			verticalAlignment = Alignment.CenterVertically
		) {
			// 左侧: 节次 + 每一节的起止时间(中间 10 分钟课间按段展示)
			// 【第五十五轮·按要求】节次用"第1-2节"这种区间写法, 时间按节分段:
			// 第1-2节 = 08:00-08:45 / 08:55-09:40
			// 【第七十三轮】不再定宽(wrap content): 列宽 = 文字实际渲染宽,
			// 时间串永远完整显示(截断问题见 timeColumnEstimate 处的说明)。
			// 【第一百零五轮·v232·结构性防丢行】时间行从"每行一个 TextView"合并为
			// **一个多行 TextView**: v231 的诊断数据(segs=11)与桌面实显(9 行)矛盾 ——
			// 数据齐、代码 for 循环全量画出, 但启动器应用到桌面时丢了最后 2 个子视图。
			// 单个 TextView 里 11 行文本"要么全在要么全无", 从结构上杜绝这种玄学丢行;
			// 视觉不变(各行同字号同颜色, 行距同为 TextView 默认行高)。
			Column {
				if(showSegments) {
					// 每一节单独一行(节间 10 分钟不写出来, 但两段之间就能看出来)
					// 【第一百零五轮·v234·位图时间列】把"节次标题 + 全部时间行"画成一张位图。
					// v232/v233 已证实两件事:
					//   ① 多个 TextView 会被这台启动器丢尾部子视图(v231: 12 丢 2), 单 TextView 才稳;
					//   ② 单 TextView 里 Glance 的 Monospace 只是 TypefaceSpan("monospace") 家族名,
					//      这台 OPPO 的字体映射把它落回了比例字体 —— 8.5sp 时错位 1~2px 看不出,
					//      v233 放大到 16sp 后"冒号/连字符不竖直对齐"就暴露了(用户反馈)。
					// 位图彻底绕开这两条: 用 Typeface.MONOSPACE 常量(系统自带等宽字体文件)在
					// Canvas 上逐行绘制 —— x 由我们控制(所有行严格同一竖线), y 按节次均分
					// (严格撑开), 不依赖启动器/系统的任何字体解析。字号随 spreadSize。
					// 尺寸: 本卡最大 ≈250x640px(ARGB_8888 ≈640KB), GlassRoot 用资源图不占
					// payload, 整包仍远低于 RemoteViews 的 1MB 限制。
					val ctx = LocalContext.current
					val bmp = buildTimeColumnBitmap(
						ctx, item.slotLabel,
						item.segments.map { "${it.first}-${it.second}" },
						spreadSize * timeFontRatio,
						style.blockText(item.color).getColor(ctx).toArgb()
					)
					Image(provider = ImageProvider(bmp), contentDescription = null, modifier = GlanceModifier)
				}else{
					Text(
						text = item.slotLabel,
						style = TextStyle(color = style.blockText(item.color), fontSize = spreadSize.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Left),
						maxLines = 1
					)
					if(showRange) {
						Text(
							text = "${item.start}-${item.end}",
							style = TextStyle(color = style.blockText(item.color), fontSize = (smallSize * timeFontRatio).sp, textAlign = TextAlign.Left, fontFamily = FontFamily.Monospace),
							maxLines = 1
						)
					}
				}
			}
			// 中间: 课名 (+ 地点/老师)
			// 【第九十轮·按要求】课名与时间列之间留"两个字"的空间(课名基准 13sp, 两字 ≈26dp)。
			Column(modifier = GlanceModifier.defaultWeight().padding(start = (26F * scale).dp)) {
				if(twoLine) {
					// 【第七十轮】课名允许折到 nameLines 行(1 或 2): 旧版固定 1 行,
					// 长课名只能靠省略号; 现在"缩字 + 折行"两条路都走完才轮到省略号。
					Text(
						text = item.name,
						style = TextStyle(color = style.blockText(item.color), fontSize = nameSize.sp, fontWeight = FontWeight.Medium),
						maxLines = nameLines
					)
					if(item.detail.isNotEmpty()) {
						Text(
							text = item.detail,
							style = TextStyle(color = style.blockText(item.color), fontSize = detailSize.sp),
							maxLines = 1
						)
					}
				}else{
					// 单行模式: 课名与地点/老师合并。
					// 空格而不是"·"分隔 —— 中文课名里本来就可能带"·", 再加一个会分不清哪是分隔符。
					Text(
						text = if(item.detail.isEmpty()) item.name else "${item.name} ${item.detail}",
						style = TextStyle(color = style.blockText(item.color), fontSize = nameSize.sp, fontWeight = FontWeight.Medium),
						maxLines = 1
					)
				}
			}
			// 右侧: 正在上的课才显示倒计时, 其余不占地方
			if(item.status.isNotEmpty()) {
				Text(
					text = item.status,
					style = TextStyle(color = style.text, fontSize = detailSize.sp),
					maxLines = 1,
					modifier = GlanceModifier.padding(start = (4F * scale).dp)
				)
			}
		}
	}

	/**
	 * 【第三十轮】"默认大小以学期课表课程数最多的日期的课程数为准"。
	 *
	 * 卡片默认给多大是启动器按 `res/xml/daily_lesson.xml` 的 targetCellHeight 决定的,
	 * 而"课最多的那天有几节"只有运行时才知道, 所以这里把**本周课最多那天**需要的
	 * 高度写回 `OPTION_APPWIDGET_MIN_HEIGHT`: 启动器(含 MIUI)会把它当作可缩小到的下限,
	 * 于是"缩到课程消失"在系统层面就被挡住了。
	 *
	 * 只在算出来的高度变化时才写一次, 避免 options 变化 → 重绘 → 再写 的循环。
	 */
	private fun applySizeHint(context: Context, units: Int) {
		if(units <= 0) return
		// 【第一百零五轮·修"OPPO 上卡底两角直角"】这个提示只给 MIUI 用:
		// MIUI/HyperOS 的 LocalSize 对 5×9 报小 ~20%(见 provideGlance 处说明), 需要我们
		// 往 MIN_HEIGHT 里写"需要的高度"把它的组合尺寸抬起来;
		// 而 OPPO/ColorOS 会**反过来把我们写大的 MIN_HEIGHT 当成卡片真实尺寸** ——
		// 组合视图因此比真实摆放区域高, 底部整截被启动器裁掉, 卡底圆角直接裁成直角,
		// 课程区也按虚高高度均分, 标题下面空出一大块(用户 2026-11-07 实测)。非小米不写。
		val miuiOn = android.os.Build.MANUFACTURER.contains("xiaomi", true) ||
			android.os.Build.BRAND.contains("xiaomi", true) ||
			android.os.Build.BRAND.contains("redmi", true)
		if(!miuiOn) return
		// 表头 40 + 每节 46 + 上下内边距 20 + 余量 6
		val needed = 40 + units.coerceAtMost(8) * 46 + 26
		val sp = context.getSharedPreferences("widget_size_hint", Context.MODE_PRIVATE)
		if(sp.getInt(KEY_DAILY_MIN_HEIGHT, -1) == needed) return
		// 【第六十轮】写一次 options, 启动器就会回一次尺寸回调(= 再整块重绘一遍)。
		// 同步课表时会连着触发好几次刷新, 这里加一道 60 秒节流: 值没变不写、刚写过也不写,
		// 免得"同步一次课表, 小组件自己又重画两三遍"。
		val now = android.os.SystemClock.elapsedRealtime()
		if(now - sp.getLong(KEY_DAILY_HINT_AT, 0L) < HINT_THROTTLE_MS) return
		runCatching {
			val manager = AppWidgetManager.getInstance(context)
			val ids = manager.getAppWidgetIds(ComponentName(context, DailyLessonReceiver::class.java))
			if(ids.isEmpty()) return
			val options = Bundle()
			options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, needed)
			for(id in ids) manager.updateAppWidgetOptions(id, options)
			sp.edit().putInt(KEY_DAILY_MIN_HEIGHT, needed).putLong(KEY_DAILY_HINT_AT, now).apply()
		}
	}

	/**
	 * 本周"最满的那天"要占到第几节(含课与课之间的空档)。
	 *
	 * 【第三十一轮】以前返回的是"有几门课", 于是"1、2、4 节有课"和"1、2、3 节有课"需要的
	 * 高度被算成一样 —— 按那个高度给卡片, 第 4 节就被挤掉了。现在按**节次**算:
	 * 取最后一门课的结束节次, 空档也算进高度里, 卡片才会一次给够。
	 */
	private fun busiestDayRows(): Int = runCatching {
		val table = LessonTableRepository.lessonTable
		val total = table.totalWeek.coerceAtLeast(1)
		val week0 = (LessonTableRepository.currentWeek.intValue - 1).coerceIn(0, total - 1)
		var max = 0
		for(day in 0 until 7) {
			if(table.isHoliday(table.dateKeyOf(week0, day))) continue
			val groups = table.activeDay(week0, day)
			var units = 0
			for(i in groups.indices) {
				val lesson = groups[i]?.getCurrentLesson(week0 + 1) ?: continue
				val end = i + lesson.len.coerceAtLeast(1)
				if(end > units) units = end
			}
			if(units > max) max = units
		}
		max
	}.getOrDefault(0)

		/** 卡片上下内边距(dp) */
		const val CardPaddingV = 10F

		/** 上次写给启动器的最小高度(dp), 用来避免重复写 options */
		const val KEY_DAILY_MIN_HEIGHT = "dailyMinHeight"

		/** 上次写 options 的时刻(elapsedRealtime), 用来节流(见 applySizeHint) */
		const val KEY_DAILY_HINT_AT = "dailyHintAt"

		/** 写 options 的最小间隔: 每次写都会引来一次回声重绘, 不值得连着写 */
		const val HINT_THROTTLE_MS = 60_000L
}
