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
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
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
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.qust.helper.data.Data
import com.qust.helper.data.Keys
import com.qust.helper.data.lesson.Lesson
import com.qust.helper.data.lesson.LessonGroup
import com.qust.helper.data.lesson.LessonTable
import com.qust.helper.data.lesson.TimeTables
import com.qust.helper.model.LessonTableRepository
import com.qust.helper.ui.activity.ComposeActivity
import com.qust.helper.ui.theme.LESSON_TEXT_COLORS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Arrays
import java.util.Calendar
import java.util.Date
import kotlin.math.ceil


/**
 * 尺寸变化回调的**节流**间隔(不是防抖)。
 *
 * 【第三十六轮】当初是为了救"拖动改尺寸时每帧都重绘"。上一版用防抖(手停 160ms 才画),
 * 用户反馈"缩放延迟更大了" —— 防抖期间画面完全不动, 手指在拖、卡片却像冻住。
 * 改成节流后: **第一帧立刻重排, 之后每 96ms 最多一次**, 拖动时画面持续跟着变。
 *
 * 【第三十七轮】用户要求**彻底关掉两个课表小组件的缩放**(见 res/xml/term_lesson.xml 的
 * `resizeMode="none"`), 所以"每帧回调"那条路已经不存在了。
 * 这段节流仍然保留, 理由是从"救延迟"变成"防抖动":
 *  · 部分 ROM 在网格变化、旋转屏、启动器重排时会连着补发好几次 options 回调;
 *  · 每次 `update()` 都是一次完整的 RemoteViews 重建 + 跨进程 IPC, 连发会明显掉帧。
 * 保留它没有任何副作用(正常只触发一次, 直接走"立即重排"分支)。
 */
private const val RESIZE_THROTTLE_MS = 96L


class TermLessonReceiver : GlanceAppWidgetReceiver() {

	override val glanceAppWidget: GlanceAppWidget = TermLesson

	/**
	 * 【第四十四轮】软件升级后系统会广播 `MY_PACKAGE_REPLACED`, 但 Glance 1.0.0 不处理它
	 * (onReceive 的 default 分支直接忽略) —— 启动器就会一直显示**旧版本缓存的视图**,
	 * 新代码(玻璃位图等)永远画不上去。这里自己接管: 当作一次全量 onUpdate 重渲染。
	 */
	override fun onReceive(context: Context, intent: Intent) {
		if(intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
			val manager = AppWidgetManager.getInstance(context)
			onUpdate(context, manager, manager.getAppWidgetIds(ComponentName(context, javaClass)))
			return
		}
		super.onReceive(context, intent)
	}

	/**
	 * 尺寸/参数回调: **节流**(第一帧立刻画, 之后每 [RESIZE_THROTTLE_MS] 最多一次), 不是防抖。
	 *
	 * 【第三十七轮】缩放已关闭(`resizeMode="none"`), 这里不再承担"拖动时跟手"的职责,
	 * 只用于"启动器改了尺寸参数就重排一次"(例如 applySizeHint、网格变化、旋转屏)。
	 *
	 * 三个必须注意的点(都是踩过才知道的):
	 *
	 *  1. **`BroadcastReceiver` 每次广播都是新实例**。所以 handler / 待办任务必须是静态的
	 *     (见 companion), 放实例字段等于每次都是空表, 节流压根不生效。
	 *
	 *  2. **不能在延迟里回头调 `super.onAppWidgetOptionsChanged`**。它内部要 `goAsync()`,
	 *     而那时 `onReceive` 早就返回了, 拿到的是 null, 它 `finally` 里的 `finish()` 会直接 NPE;
	 *     更要紧的是没有 PendingResult 撑着, 进程随时可能被系统回收, 最后那次重排就丢了。
	 *     所以这里改成: **在 onReceive 的调用栈里先 `goAsync()`**, 到点后自己
	 *     `glanceAppWidget.update(...)`。`update()` 拿尺寸同样走
	 *     `AppWidgetManager.getAppWidgetOptions`(系统在回调前已经写好), 效果和父类的 `resize` 一致。
	 *
	 *  3. **被顶掉的那个任务要把它的 PendingResult 收尾**。否则系统会一直等这一次广播,
	 *     超过 10 秒就记一次"广播未完成"(严重时弹 ANR 提示)。
	 */
	override fun onAppWidgetOptionsChanged(
		context: Context,
		appWidgetManager: AppWidgetManager,
		appWidgetId: Int,
		newOptions: Bundle
	) {
		val app = context.applicationContext
		val pending = goAsync()
		// 顶掉排队中的"补帧"任务: 只有最后一帧的尺寸才是最终尺寸
		pendingTask?.let { old ->
			handler.removeCallbacks(old.runnable)
			runCatching { old.pending.finish() }
			pendingTask = null
		}
		val now = SystemClock.elapsedRealtime()
		// 【第六十三轮·修"重绘有时候不管用"】
		// `WidgetStyleStore.refreshAll` 改主题/颜色时会往 options 里塞一个 qust_style_tick,
		// 目的就是让我们走这一次回调。但下面那 96ms 的节流是给"拖动缩放"准备的 ——
		// 用户"点一下主题、马上点一下重绘"正好落在 96ms 之内, 第二次就被排到延迟队列,
		// 而延迟任务又会被下一次回调顶掉(PendingTask 被移除), 于是这一帧**谁也没画**。
		// 认准这个键: 它不是缩放, 一秒钟来十次也该立刻画。
		if(newOptions.containsKey(WidgetStyleStore.KEY_STYLE_TICK)) lastRunAt = 0L
		val since = now - lastRunAt
		if(since >= RESIZE_THROTTLE_MS) {
			lastRunAt = now
			fire(app, appWidgetId, pending)
		}else{
			val task = Runnable {
				pendingTask = null
				lastRunAt = SystemClock.elapsedRealtime()
				fire(app, appWidgetId, pending)
			}
			pendingTask = PendingTask(appWidgetId, pending, task)
			handler.postDelayed(task, (RESIZE_THROTTLE_MS - since).coerceAtLeast(1L))
		}
	}

	/**
	 * 真正跑一次重排。
	 *
	 * 上一次**还没画完**的时候不排队(那会把任务堆成一长串, 松手后要追上好几秒),
	 * 只记一笔"画完再用最新尺寸补一帧" —— 保证松手后的尺寸一定落到屏幕上。
	 */
	private fun fire(app: Context, appWidgetId: Int, pending: BroadcastReceiver.PendingResult?) {
		if(updating) {
			runCatching { pending?.finish() }
			refreshAfterUpdate = true
			return
		}
		updating = true
		try {
			val glanceId = GlanceAppWidgetManager(app).getGlanceIdBy(appWidgetId)
			CoroutineScope(Dispatchers.Default).launch {
				try {
					glanceAppWidget.update(app, glanceId)
				}catch(_: Throwable) {
				}finally {
					updating = false
					runCatching { pending?.finish() }
					if(refreshAfterUpdate) {
						refreshAfterUpdate = false
						handler.post { fire(app, appWidgetId, null) }
					}
				}
			}
		}catch(_: Throwable) {
			updating = false
			runCatching { pending?.finish() }
		}
	}

	/** 组件被删掉时, 别把它的待办任务和 PendingResult 留在系统里 */
	override fun onDeleted(context: Context, appWidgetIds: IntArray) {
		pendingTask?.let { old ->
			// 只清"被删掉的那个组件"的待办: 桌面上可能同时放了两个课表小组件
			if(old.id in appWidgetIds) {
				handler.removeCallbacks(old.runnable)
				runCatching { old.pending.finish() }
				pendingTask = null
			}
		}
		// 这里**不能**自己 goAsync: 父类要靠这一次广播的 PendingResult 去清理 Glance 侧的状态
		super.onDeleted(context, appWidgetIds)
	}

	private class PendingTask(
		val id: Int,
		val pending: BroadcastReceiver.PendingResult,
		val runnable: Runnable
	)

		private val handler: Handler = Handler(Looper.getMainLooper())

		/** 还没到点的"补帧"任务 */
		private var pendingTask: PendingTask? = null

		/** 上一次真正重排的时间(elapsedRealtime), 用来算节流间隔 */
		private var lastRunAt = 0L

		/**
		 * 是否正在重排: 防止快速拖动时把任务堆成一长串。
		 * `@Volatile`: 写在主线程(判断), 清在 Default 线程(update 结束后), 不加会读到旧值。
		 */
		@Volatile
		private var updating = false

		/** 重排期间又来了新尺寸 -> 画完立刻再补一帧(同样跨线程, 要 volatile) */
		@Volatile
		private var refreshAfterUpdate = false
}


/**
 * 【第二十九轮·重写】学期课表小组件。
 *
 * 上一版有两个结构性毛病, 都在"点一下"之后原形毕露:
 *
 * 1. **数据放在 companion 的静态字段里**(`lessonTableData` + `dataReady`)。
 *    谁都能改、改了也不通知别人 —— 点箭头时回调先改静态数据再 `update()`,
 *    而尺寸变化/系统重绘走另一条路径, 两边看到的周次可能不是同一周,
 *    表现就是"点一下之后表格变空 / 周次标题和内容对不上"。
 *    现在**没有任何静态数据**: 每次 `provideGlance` 都自己把
 *    "周次 + 起始日 + 7×节次 的格子" 查一遍(纯内存计算, 开销可忽略), 再直接传给组合函数。
 *
 * 2. **周次下标错一位**。`LessonTableRepository.currentWeek` 是 **1 基**的
 *    (课表页用 `currentWeek - 1` 取下标), 上一版直接 `coerceIn(0, total-1)` 当 0 基用 ——
 *    第一次把小组件拖到桌面时整体错开一周。
 *
	 * 另外: 顶部周次条 / 日期条点一下就能进「学期课表」页; 课表读不出来时给一句
	 * "课表还没准备好 · 点击打开课表", 而不是一块空白。
 */
/**
 * 【第六十五轮·终】改成 **object 单例**。
 *
 * 这不是风格偏好, 是修"主题时好时坏"的核心一步:
 * Glance 1.x 的 `update()` 会话注册表挂在 **GlanceAppWidget 实例**上 —— 每次
 * `TermLesson()` 都是一个新对象, 它的内存里永远"没有正在运行的会话", 于是**每次
 * update 都走"REPLACE 重排队"分支, 把上一个正在 RUNNING 的组合任务中途杀掉**。
 * 组合被砍在半路, RemoteViews 没写出去, 桌面停在旧帧 —— 这正是"闪了一下但没变化,
 * 偶尔才成功"的完整解释(成功的那次只是恰好没被下一次砍)。
 *
 * 改成单例后, receiver / 刷新入口 / 课表仓库全部共用同一个会话表:
 * 第二次 update 会走"复用会话 → session.updateGlance()"分支, 让正在运行的会话
 * **自己重新组合一遍**, 不打断、不重排队。
 */
object TermLesson : GlanceAppWidget() {

	/**
	 * 【第六十五轮·终】刷新计数器。
	 *
	 * Glance 会话是长驻的: 第一次 `provideGlance` 之后, 后续刷新走"复用会话重新组合"。
	 * 重组由 Compose 的 Recomposer 驱动 —— **只有内容 lambda 读到的状态变了才会重跑**。
	 * 以前样式/课表是 provideGlance 时捕获的旧值, 重组画出来的永远是旧画面。
	 * 现在内容 lambda 每次重跑都现读样式和课表, 而刷新入口把这个 tick +1 保证重组必然发生。
	 */
	internal val refreshTick = androidx.compose.runtime.mutableStateOf(0L)

	/**
	 * 【第七十轮·五】"这一轮有没有真的完成一次重组合"的计数器。
	 *
	 * 为什么需要: `LessonTableRepository.refreshLessonWidgets()` 在直连更新之后 250ms 会补发一条
	 * 兜底广播, 而那条广播落回来又是 `onUpdate` → `updateAll()` —— **同一块小组件被连着更新两次**。
	 * Glance 的会话是"后一次顶掉前一次": 前一次刚组合好的 11 行画面还没落地就被打断,
	 * 桌面一直停在更早那一帧(用户看到的"第 11 节整行不见" + "改了半天没变化"就是这个)。
	 *
	 * 现在直连更新只要**真的组合过**, 就不再补发广播; 只有连组合都没发生(Glance 没被唤醒)
	 * 才走兜底广播 —— 兜底还是兜底, 但不再自己打断自己。
	 */
	internal val composeSeq = java.util.concurrent.atomic.AtomicInteger(0)

	/**
	 * 从仓库取到的一份"课表快照"(只读)。
	 *
	 * "现在看第几周"(`week`)和课表一起在 IO 线程取好, 组合阶段直接用 ——
	 * 以前是在组合阶段用 `currentState<Preferences>()` 读的, 那是一次 DataStore 事务,
	 * 会落在"画面要变"的关键路径上。
	 *
	 * 【第三十六轮】翻周删除后, 周次不再是可以被用户改动的持久化状态, 永远等于
	 * `LessonTableRepository.currentWeek`, 所以这里也不需要任何静态变量:
	 * 每次 `provideGlance` 都重新取一份, 不会出现"数据和周次不是同一周"的老毛病。
	 */
	internal class TableSnapshot(
		val table: LessonTable,
		val total: Int,
		val startDay: Long,
		/** 现在看第几周(0 基), 已在 IO 线程里连同课表一起取好 */
		val week: Int,
		/**
		 * 【第五十五轮·按要求】软件里开着"隐藏教师"时, 小组件也要一起隐藏。
		 *
		 * 和 week 一样在 IO 线程里随快照取好: 组合阶段直接读, 不在绘制路径上碰
		 * SharedPreferences(那个对象也是"设置"页改完马上刷新的, 见
		 * LessonTableRepository.setHideTeacherValue)。
		 */
		val hideTeacher: Boolean = false,
		/**
		 * 【第六十五轮】启动器上报的**窗口高度(dp)**, 0 表示没读到。
		 *
		 * `LocalSize` 给的是 Glance 自己换算出来的尺寸, 在这台 MIUI 上报得**又小又不稳定**
		 * (同一张卡先后量到 ≈575dp 和 ≈287dp, 而真实卡高约 480dp)。它一小, 每节就矮,
		 * 表格下方就空出一大片 —— 用户说的"留白太多了"。
		 *
		 * 所以这里直接从系统的 `AppWidgetOptions` 里再读一遍(min/max 取大), 与 `LocalSize`
		 * 取较大者使用: 两个来源谁更接近真实值就用谁, 不会比现在更差。
		 */
		val windowH: Float = 0F,
		/**
		 * 【第九十三轮】启动器上报的**窗口宽度(dp)**, 0 表示没读到。
		 *
		 * 它不参与排版, 只决定 MIUI 的**渲染密度分档**: 第 92 轮用户桌面重排后出现了
		 * options 高度完全相同(362dp)但渲染密度不同的两个实例 —— 5×9(0.79 档, 要 ×1.26)
		 * 和 4×7(1:1 档, ×1.26 会底部裁掉一节课)。两个实例唯一的差别是**宽度**:
		 * 5×9=274dp, 4×7=332dp。分档依据从"高度"换成"宽度"(见 LessonView 的 miuiK)。
		 */
		val windowW: Float = 0F
	)


		/**
		 * 【第三十一轮·按要求】"课程字号再调小一点"。
		 *
		 * 0.88 = 在"软件内 12sp 等比缩放"的基础上再小一档: 一格能多放一行、课名更少被截断,
		 * 但章节数翻倍后仍然认得清楚(下限 12×0.55×0.88 ≈ 5.8sp 已经接近极限, 所以只降这一档)。
		 * 折行位置仍按**未缩小的**基准字号算, 保证"显示到哪一行"与软件内一致(见 LessonBlock)。
		 */
		private const val FONT_SHRINK = 0.88F

		/**
		 * 【第六十八轮】一行文字占几个字号(含 includeFontPadding)。
		 *
		 * 实测约 1.32, 之前只作为字面量散落在 `metricsOf` / `LessonBlock` 两处;
		 * 现在"按字号范围逐档试算"要用同一个数判断"一格能画几行", 提成常量免得两边对不上。
		 */
		private const val LINE_FACTOR = 1.32F

		/**
		 * 表格右侧留的空档(dp)。
		 *
		 * 【第三十三轮】为避开卡片右上/右下圆角, 从 0 加到 3dp。
		 * 【第三十八轮·修"周日的课程右边有点遮挡"】量了用户截图: 周日那列色块的右边缘在
		 * x≈828, 卡片边界在 x≈830 —— 几乎完全贴边, 色块自己的圆角被切平了。
		 *
		 * 根因不是圆角, 而是 **`LocalSize` 报的宽度比真实视图宽了约 3dp**: 它来自启动器上报的
		 * 格子尺寸(按 cell 取整), 不是实测像素。原来只留 3dp, 正好被这个误差吃光。
		 * 现在加到 10dp: 抵掉 3dp 误差后还剩 6~7dp 的可见间隙, 列宽只缩了 0.9dp, 看不出来。
		 */
		private const val RIGHT_INSET = 10F

		/**
		 * 表格左边缘的固定留白(dp)。
		 *
		 * 时间栏的"1 / 08:00"如果贴着卡片左边, 圆角会把数字切掉一角, 而且和卡片的
		 * 圆角边界挤在一起很难看。6dp 是"看起来有呼吸、又不浪费宽度"的值。
		 *
		 * 【第六十三轮】从"写在 mod 里的字面量 6.dp"提成常量 —— 因为现在它要和
		 * [RIGHT_INSET]、[WidgetTheme.bodyInset] 一起参与 `dayCol` 的计算,
		 * 两边只要有一个数对不上, 7 个权重列就会比"算出来的列宽"更宽, 一路顶到右边去。
		 */
		internal const val START_INSET = 6F

		/**
		 * 表格底部留的空档(dp)。
		 *
		 * 【第三十八轮】圆角从 18dp 加到 22dp, 下缘两个圆角吃掉的区域变大, 从 3dp 提到 8dp,
		 * 免得最后一节贴到圆角上被切掉一角。
		 *
		 * 【第六十轮·重写"共用网格"】从 14dp 退回 8dp。
		 *
		 * 上一版把竖直网格建立在"`LocalSize` 估算的可用高度"上, 只能靠加厚余量去兜上报误差
		 * (误差一旦大于余量, 最后一节整行消失)。这一版网格高度是**固定 dp**(见 [Metrics.tableH])，
		 * 时间栏与课程块从同一个数里取, 误差再大也只会压掉最后一节的**底边**, 不可能吞掉一整行 ——
		 * 所以余量只要能盖住"底边"就够, 不必再拿 6% 的高度去换。
		 *
		 * 【第六十一轮·修"本周课表没有显示 19:50 和 20:35"】8dp 不够, 提回 14dp。
		 *
		 * 用户截图里 11 节时间表(新冬季)的**最后一节整行不见了** —— 19:50 / 20:35 正是第 11 节的
		 * 上/下课时间。根因是 `LocalSize` 报的高度比真实视图大了一截(启动器按"格数 × 每格 dp −
		 * 固定值"估出来, 不是实测像素), `tableH` 被算高了 10 来 dp, 于是第 11 行正好落在卡片下缘之外
		 * 被裁掉 —— 而且**只裁掉最后一行**, 上面的 10 行一个不少, 看上去就像"时间表少了两行字"。
		 * 8dp 只够盖住 3 行高的卡(误差小), 高卡误差更大, 所以余量必须跟着卡高走(见下面 13.5%)。
		 *
		 * 【第六十二轮】回到 16dp: 这个值只负责"最后一节的底边不压在 22dp 圆角上"。
		 * 之前一度加到 28dp 去兜"LocalSize 报大了"的假设, 后来实测推翻了这个假设(见 [BOTTOM_RESERVE_RATIO])。
		 *
		 * 【第八十三轮】16dp -> **20dp**: v0202 的两张大卡截图(20:24:23 纯白 vs 20:24:39 磨砂玻璃,
		 * 同一个 ≈495dp 实例、相隔 16 秒)按像素量过: 两帧的内容几何**完全一致**(节距都是 82px,
		 * "20:35" 墨迹底部都在 y≈1215), 但"20:35"到可视卡底的距离一个 10.4dp、一个 16.7dp。
		 * 原因有两层: ① 启动器在两次布局之间把视图实际高度摆得不一样 —— 两帧都按 options≈498dp
		 * 算的(留白 16dp 是按 498 扣的), 而纯白那次视图实际只有 ≈493dp, 5dp 漂移直接吃掉留白的
		 * 三分之一; ② 磨砂玻璃的可视底边有亮白描边 + 半透明延伸, 观感上比纯白"白底到头就停"
		 * 更靠下。①是 MIUI 的行为, 代码控制不了, 只能把固定下限抬到 20dp 把漂移量吃回来:
		 * 最坏情况(视图比 options 矮 5dp)下最后一行距可视底边仍有 ~15dp, 正常情况 ~20dp,
		 * 两个主题观感一致。代价: 每节矮 0.36dp(495dp 卡), 字号公式不受影响。
		 */
		// 【第八十九轮】20F -> 12F: 校准后内容整体按 ×1.26 铺开再缩回, 底部预留的
		// "渲染后高度"只剩 12F×0.79≈9.5dp, 仍够盖住圆角; 再大就是用户点名的"下半截留白"。
		// 【第九十二轮】12F -> 16F: 用户截图按像素量过, "20:35"墨迹底部距可视卡底
		// 5×9 只剩 ≈9.5dp、4×7 也明显贴边快出界。抬回 16dp。
		// 【第九十四轮】16F -> 20F: 用户反馈"各个桌面布局的本周课表 20:35 还是稍微靠边"。
		// 0.79 档渲染后 16dp 只剩 ≈12.6dp, 观感仍然贴圆角。抬到 20F —— 0.79 档渲染后
		// ≈15.8dp、1.0 档足 20dp; 代价每节矮 ≈0.65dp, 十一节的卡共 7dp, 可忽略。
		private const val BOTTOM_INSET = 20F

		/**
		 * 【第一百零五轮·v232·按厂商分流】底部余量: MIUI 上视图实际高度与 options 有
		 * ±5dp 漂移(第八十三轮抬到 20dp 的原因), **保持 20**; 其他系统(OPPO/ColorOS 等)
		 * 的 options 高度已被渲染诊断证实准确(v231: 竖屏组与 MAX 互相印证), 收到 12dp ——
		 * 用户实测 OPPO 上"下面的留白稍微有点大", 收 8dp、每节多 ≈0.7dp。
		 */
		private val bottomInset: Float
			get() = if(android.os.Build.MANUFACTURER.contains("xiaomi", true) ||
				android.os.Build.BRAND.contains("xiaomi", true) ||
				android.os.Build.BRAND.contains("redmi", true)) BOTTOM_INSET else 12F

		/**
		 * 【第五十九轮】底部余量占卡片高度的比例。
		 *
		 * 卡片越高, 启动器上报尺寸的绝对误差越大(它是"格子数 × 每格 dp − 固定值"估出来的),
		 * 所以余量不能只有固定 dp。
		 *
		 * 【第六十轮】6% 降到 3%: 网格已经改成固定高度(见 [BOTTOM_INSET] 的说明),
		 * 余量只负责"别让最后一节的底边压到圆角上", 不再需要吃下全部上报误差 ——
		 * 省下来的高度全部变成每节的字高, 12 节的卡才放得下"节次/上课/下课"三行。
		 *
		 * 【第六十一轮】3% 又提回 5%: 3% 在 320dp 的卡上只有 9.6dp, 而实测误差在 12~16dp 之间,
		 * 于是"最后一节被裁"。5% 在同样高度下是 16dp, 正好盖住; 换来的是每节少 0.5dp ——
		 * 12 节 × 0.5 = 6dp, 远小于"少一行"的代价。
		 *
		 * 【第六十一轮·二】5% 不够, 一度提到 13.5%(当时以为是 `LocalSize` 报大了)。
		 *
		 * 【第六十二轮·推翻重来】把用户的截图按像素重新量过之后, 结论**反了**:
		 * 那个 11 节的时间表里, 表格底部离卡片下缘还有 **20% 以上的空白** ——
		 * 也就是 `LocalSize` 报的高度比真实视图**小**, 不是大。
		 *
		 * 【第六十三轮·量准了, 是"报大 19%"】
		 * 第六十二轮那次量错了卡片下沿(纯色主题的卡片底部有一段被内阴影压到 #E0 左右,
		 * 用"接近纯白"去卡边界会把它当成卡片外的壁纸)。
		 *
		 * 这一次直接扫描像素: 截图 886x1920 = 1080x2340 缩了 0.82, 屏幕 360dp 宽 ->
		 * **2.256 px/dp**。纯色主题(实心白卡, 边界最清楚)量出来:
		 *   · 卡片: 上沿 y=378, 下沿 y=1464 -> 1086px = **481dp**;
		 *   · 截图里能看见 9.5 行, 每行 ≈43dp -> 代码当时算出来的表格高 ≈ **574dp**。
		 * `574 - 481 = 93dp`, 也就是 `LocalSize` 报的高度比真实视图**大 19%**。
		 * (这也解释了"第 11 节只露出半个字号": 表格比卡片高 1.5 行, 从底部被裁。)
		 *
		 * 【第六十五轮·再改】19% -> **4%**。
		 *
		 * 用户这一轮的原话是"留白太多了" —— 上一轮为了防"整行被裁"把余量给到了 19%,
		 * 而 19% × 480dp ≈ 91dp ≈ **两整行课**的空白, 加上缺掉的第 11 行, 底部空了将近三行。
		 * 【第七十轮·十八·按 16:27 的截图重新量了一遍: 4% 又不够了】
		 *
		 * 用户这一轮又报"4*7 布局下时间和课程显示不全"。把截图按像素量出来:
		 * 卡上沿 y=323、下沿 y=1316(886 宽的截图, 2.256 px/dp) -> 卡高 **440dp**;
		 * 而第 11 行(节次 11 / 19:50 / 20:35)只画出了个"11", 后两行压在卡片下缘之外。
		 * 按行距反推, 表格高 ≈ 481dp —— 也就是说启动器上报的**高度比真实卡片大约 9%**
		 * (宽度倒是准的, 7 列一格没歪, 所以只往高度上补)。
		 * 4% 只兜住 19dp, 兜不住这 40dp。本轮按实测提到 9%, 让最后一节的第三行落在卡内。
		 *
		 * 【第七十一轮】9% -> 5.5% -> **7.5%**。
		 *
		 * 关键教训: 启动器上报高度的误差**方向会变** —— v0187 实测是"报大 40dp"(裁行),
		 * 16:53 那次截图却变成"报得准"(改 5.5% 后留白太多), 18:41 又回到"报大 39dp"
		 * (4×7/5×9 最后一行 19:50/20:35 又被裁)。固定比例没法两全, 只能折中:
		 * 7.5% ≈ 33dp(440dp 卡), 裁行时兜得住大部分, 留白也不至于空出一行课。
		 *
		 * 【第七十二轮·按要求"不要太多留白, 直接以最美观的方式显示"】7.5% -> **3%**。
		 *
		 * 用户明确了取舍: 宁可接受"桌面布局改动后最后一行被裁", 重新添加一次小组件
		 * (启动器按当前布局重新上报尺寸, 幻影随之消失), 也不要一块下部空一截的卡片。
		 * 3% 只兜住 ~13dp(440dp 卡), 正常情况下卡片底部只留一行以内的呼吸位;
		 * 16dp 的 [BOTTOM_INSET] 下限继续兜住"最后一节底边", 不让文字贴到圆角上。
		 *
		 * 【第九十二轮】1% -> 1.5%: "20:35"贴边的另一半原因 —— 高卡(≈495dp)按比例
		 * 部分从 4.95dp 提到 7.4dp, 和 16dp 固定下限一起把最后一行再推离圆角一点。
		 *
		 * 【第九十四轮】1.5% -> 2%: 配合 [BOTTOM_INSET] 抬到 20dp, 高卡(495dp)比例部分
		 * 提到 ≈9.9dp, "20:35"离底边的总余量再推远一点。
		 */
		private const val BOTTOM_RESERVE_RATIO = 0.02F

		/**
		 * 【第七十轮·十】"11月"那格占时间列宽度的比例(见 [LessonDateBar] 里的说明)。
		 *
		 * 时间列宽 = 0.8 个课程列, 而"08:00"五个字符的墨迹只占列宽的 ≈68% ——
		 * 月份按这个宽度居中, 中线才和下面的时间串重合(按整列居中会偏右, 按左缘对齐会偏左)。
		 */
		private const val MONTH_BOX_RATIO = 0.68F

		/**
		 * 【第七十轮·二】options 读不到时对 `LocalSize` 高度打的折扣。
		 *
		 * 依据两次实测: 纯色卡像素量得真实 ≈481dp(报 574, +19%); 21:18 磨砂卡第 11 节
		 * 课块只露出约 10dp(报 495, 排到 474) → 真实可视 ≈451dp(+9%)。
		 * 折后内容底 = 44 + 24 + 表格 + 余量, 必须落在 ≈451 以内:
		 *   ×0.92 → 内容底 ≈436, 留 15dp 余量, 每节 ≈33.5dp、时间字号 ≈8sp ——
		 * 比上一版 0.90(字号 7.6, 用户嫌"变小了")大一号, 又仍保第 11 行完整可见。
		 * 覆盖安装后桌面会停在旧帧(v0174 的 495 排布), 切一次主题/解锁/等心跳即可刷出新帧;
		 * 新帧里第 11 行是一定组合出来的(质检日志"时间栏: 11节"可证)。
		 */
		/**
		 * 【第七十轮·五·推翻"×0.92 折"】options 读不到时, 从 `LocalSize` 里**固定扣掉的边距**(dp)。
		 *
		 * 之前两轮用百分比打折(0.90 / 0.92), 依据是"LocalSize 虚高 9%~19%"。本轮按 23:01 的
		 * 桌面截图**重新逐像素量了一遍**(886×1920 是 1080×2340 的 0.82 倍, 屏幕 393dp 宽
		 * → 2.255 px/dp):
		 *   · 卡片宽 = 785px → 348dp, 而 LocalSize 报 351dp;  ✓ 差 1%
		 *   · 卡片高 = 1088px(上沿 380 / 下沿 1468) → **482dp**, 而 LocalSize 报 495dp; ✓ 差 2.5%
		 * 也就是说 LocalSize **基本是准的**, 之前的"虚高"是拿别的帧(卡片被拖动过大小)量出来的。
		 * 按 0.92 折 → 只用 456dp, 白扔 26dp + 19dp 余量 = **底部空出两行**, 课程字号也被迫小 10%
		 * —— 用户这轮说的"课程变小了, 留白太多了"就是它。
		 *
	 * 现在改回"按 LocalSize 用, 只扣一条固定边距": 卡片本身有圆角/描边, 内容不该顶到边。
	 * 扣 10dp 后内容底 ≈ 475dp, 落在实测的 482dp 以内, 第 11 行完整可见。
	 *
	 * 【第八十二轮·空白诊断实证】"空白课表"小组件像素实测: 启动器给的视图高度 = 上报的
	 * 361dp **分毫不差**(条纹每格 50dp 一格不多)。10dp 安全边成了纯空白 —— 底部防顶边
	 * 由 bottomReserve(在内容内部, 最后一行下面天然留 16dp)继续承担, 这里归零:
	 * 表格多拿 10dp(每节 24.6 → 25.4dp), 底部空白 28dp → 18dp。
	 */
	internal const val CARD_EDGE_INSET = 0F

		/** 【第七十四轮】provider xml 里声明的 minHeight(dp): 卡片实际高度永远 ≥ 它 */
		internal const val PROVIDER_MIN_HEIGHT = 330F

		// 【第七十九轮】STATUS_MAX_RATIO 已删除: 周次条上限改由比例式里的 coerceIn(30F, 52F) 承担。

		// ---- 【第三十六轮】buildWeekData 的缓存(理由见那个函数的注释) ----
		private var cachedCells: Array<Array<LessonHolder?>>? = null
		private var cachedWeek = Int.MIN_VALUE
		private var cachedVersion = Int.MIN_VALUE
		private var cachedTotal = Int.MIN_VALUE

		/** 【第四十六轮】缓存锁: 广播与直连两条刷新路会**并发**跑 provideGlance, 缓存读写必须互斥 */
		private val cacheLock = Any()

		/**
		 * 按"某一周实际生效的课表"构建 7 × 节次 的格子(已应用调休与按日期手动修改)。
		 *
		 * 【第三十六轮·修"缩放延迟"】结果做了缓存。
		 *
		 * 原来每次 `provideGlance` 都要从头重建一遍: 77 个格子, 每个 `LessonHolder` 的 init 里
		 * 又按 20 周建 3 个 IntArray、跑 60 次位移循环 —— 缩放时**每一帧**都白算一次
		 * (十几毫秒 + 一堆短命数组把 GC 也叫醒), 而缩放根本不会改动课表。
		 * 现在按"课表版本 + 周次 + 总周数"缓存; `notifyLessonTableChanged()` 会自增版本号,
		 * 编辑课表 / 调休 / 重新导入都会让缓存自动失效。
		 */
		private fun buildWeekData(table: LessonTable, weekIndex: Int): Array<Array<LessonHolder?>> = synchronized(cacheLock) {
			val version = LessonTableRepository.lessonTableVersion
			@Suppress("UNCHECKED_CAST")
			cachedCells?.let {
				if(cachedWeek == weekIndex && cachedVersion == version && cachedTotal == table.totalWeek) {
					return it as Array<Array<LessonHolder?>>
				}
			}
			val lessons: Array<Array<LessonHolder?>> = Array(7) { arrayOfNulls(TimeTables.MAX_SLOT_COUNT) }
			for(dayOfWeek in 0 until 7) {
				val column = table.effectiveDay(weekIndex, dayOfWeek)
				for(timeSlot in 0 until TimeTables.MAX_SLOT_COUNT) {
					column.getOrNull(timeSlot)?.let { lessonGroup ->
						lessons[dayOfWeek][timeSlot] = LessonHolder(lessonGroup, table.totalWeek)
					}
				}
			}
			cachedCells = lessons
			cachedWeek = weekIndex
			cachedVersion = version
			cachedTotal = table.totalWeek
			lessons
		}

		/**
		 * 打开**软件本身的**「学期课表」页(底部导航 + 侧滑都在), 而不是另开一个单页 Activity。
		 */
		fun openAppAction(context: Context): Action =
			actionStartActivity(ComposeActivity.mainIntentFor(context, Keys.Page.TermLessonPage))

	override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

	override val sizeMode = SizeMode.Exact

	override suspend fun provideGlance(context: Context, id: GlanceId) {
		// 【第三十三轮·修"翻周反应迟钝"】上一版认为"课表是内存对象, 直接取就行", 去掉了 IO 线程 ——
		// 忽略了 `LessonTableRepository` 是 object, **第一次访问**会在 init 里 loadLesson():
		// 读本地文件 + JSON 反序列化。桌面点一下箭头会拉起(或唤醒)本进程, 这一步就正好落在
		// 组合线程(主线程)上, 桌面表现就是"点了很久才翻过去"。
		// 现在显式放回 IO 线程: 线程切换的开销可以忽略, 但再也不会卡主线程。
		val windowH = withContext(Dispatchers.IO) { optionsHeightDp(context, id) }
		val windowW = withContext(Dispatchers.IO) { optionsWidthDp(context, id) }
		val snapshot = withContext(Dispatchers.IO) { loadTable(context, windowH, windowW) }
		// 【第六十一轮】外观(玻璃/纯色 + 非课程文字颜色), 与课表一起在 IO 里取好
		val style = withContext(Dispatchers.IO) { runCatching { WidgetStyleStore.load() }.getOrDefault(WidgetStyle()) }
		// 【第四十六轮】渲染指纹: 下次日志能直接看出"渲染发生在何时、显示的是第几周、哪个课表版本"
		// 【第五十六轮】补上"用的是哪套时间表"和"隐藏教师开没开" —— 用户反馈这两个不同步时,
		// 「设置 → 桌面小组件」自检页能一眼看出小组件到底拿到了什么状态。
		provideContent {
			// 【第六十五轮·终】内容 lambda **每次重组都现读**样式与课表 —— 不再捕获旧值。
			// 读 tick 是关键: 它让 Compose 把本 lambda 记为"依赖刷新计数",
			// 刷新入口把 tick +1 后 Recomposer 必然重跑这里, 桌面立刻拿到新主题/新数据。
			TermLesson.refreshTick.value
			TermLesson.composeSeq.incrementAndGet()
			// 【第七十四轮·修"5×9 上报值永远到不了渲染"】重组路径的 loadTable 之前
			// **没传 windowH**(默认 0F) —— options 里读到的真实高度只在第一帧生效,
			// 后面每次重组都退回"只信 LocalSize"。现在同样现读 options。
			val liveWindowH = runCatching { optionsHeightDp(context, id) }.getOrDefault(0F)
			val liveWindowW = runCatching { optionsWidthDp(context, id) }.getOrDefault(0F)
			// 【第一百零五轮·v237】学期卡同款原始值日志: 5×6 布局被裁的排查需要
			// MIN/MAX/SIZES 全量 —— 判断"布局变更后 SIZES 是否残留旧值"。
			runCatching {
				val awId = GlanceAppWidgetManager(context).getAppWidgetId(id)
				val opts = AppWidgetManager.getInstance(context).getAppWidgetOptions(awId)
				val szs = if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
					opts.getParcelableArrayList<android.util.SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES) else null
				com.qust.helper.utils.WidgetDiag.add(
					"Term[opts] MIN=${opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)} " +
					"MAX=${opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)} " +
					"MINW=${opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)} " +
					"MAXW=${opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)} " +
					"SIZES=" + (szs?.joinToString("|") { "${it.width}x${it.height}" } ?: "null") +
					" Local=${LocalSize.current.width.value}x${LocalSize.current.height.value}"
				)
			}
			val liveSnapshot = runCatching { loadTable(context, liveWindowH, liveWindowW) }.getOrNull()
			val liveStyle = runCatching { WidgetStyleStore.load() }.getOrDefault(WidgetStyle())
			GlanceTheme {
				if(liveSnapshot != null) LessonView(context, liveSnapshot, liveStyle) else LoadFailedView(context, liveStyle)
			}
		}
	}

	/**
	 * 只负责"课表本身": 先 `updateDate()` 把今天属于第几周算出来(1 基), 再转成 0 基下标。
	 * 读不到就返回 null, 由界面上那句"课表还没准备好"兜底, 不让小组件直接崩掉。
	 *
	 * 【第三十六轮】删掉翻周之后, "看第几周"不再是一个需要持久化的用户状态 ——
	 * 永远是 `currentWeek`(由课表起始日和今天算出来)。所以这里只读课表, 不碰 SharedPreferences。
	 */
	internal fun loadTable(context: Context, windowH: Float = 0F, windowW: Float = 0F): TableSnapshot? = runCatching {
		LessonTableRepository.updateDate()
		val table = LessonTableRepository.lessonTable
		val total = table.totalWeek.coerceAtLeast(1)
		// 课表页用 currentWeek - 1 取下标, 小组件必须一致, 否则第一次渲染会整体错一周
		val week = (LessonTableRepository.currentWeek.intValue - 1).coerceIn(0, total - 1)
		// 【第五十六轮·修"隐藏教师只同步了一次"】这里以前读 `LessonTableRepository.hideTeacher`
		// (一个 Compose 快照状态)。小组件渲染是从广播/Glance 回调里跑起来的, 拿到的不一定
		// 是"最新提交"的那个快照 —— 表现就是第一次开关跟上了、后面几次都不动。
		// 改成直接读落盘的配置(Setting 的 apply 是同步更新内存映射的), 永远是用户最后设的那个值。
		val hideTeacher = com.qust.helper.data.Setting.getBoolean(Keys.KEY_HIDE_TEACHER, false)
		TableSnapshot(table, total, table.startDay.time, week, hideTeacher, windowH, windowW)
	}.getOrNull()

	/**
	 * 【第六十五轮】从系统的 `AppWidgetOptions` 里读一次真实高度(dp), 0 = 读不到。
	 *
	 * 为什么不信 `LocalSize`: 这台 MIUI 上报给 Glance 的尺寸飘得很厉害 —— 同一张卡,
	 * 两次渲染分别量到 ≈575dp 和 ≈287dp, 而屏幕像素量出来是 **≈480dp**。
	 * 报小了, 每节就矮, 表格下面空一大片(用户说的"留白太多了"); 报大了, 最后一行被裁。
	 *
	 * 【第六十八轮·六·修「改成 12 节的时间表还是只显示 10 行」】
	 *
	 * 把 16:12 那张桌面截图重新按像素量了一遍, 结论和第六十五轮"数据只有 10 条"**不一样**:
	 * 时间列第 10 行(18:55/19:40)的文字**被卡片下缘裁掉了半截**, 第 11 行整行不可见 ——
	 * 数据是齐的(软件内「学期课表」同刻 11 行都在), 是**布局把表格画高了**: 总高 =
	 * 周次条 + 日期条 + tableH, 而 tableH 用的是 `maxOf(LocalSize, options)` 里那个
	 * 报大了约 19% 的 LocalSize(≈575dp), 于是底部 1.5 行落到卡片外面被裁。
	 * 第六十五轮把余量从 20% 退回 4%, 是因为当时以为病根只是"数据少一条";
	 * 数据补齐之后, "报大 19%"这个老病立刻复发 —— 两者其实是同一个症状的两层皮。
	 *
	 * 修复: **options 可用时就只用 options**, LocalSize 降级为"options 读不到才用的回退"。
	 * 上一版取 `maxOf(两者)` 在"LocalSize 飙到 575dp"时等于没防。另外取值改成
	 * **按方向取**: 竖屏用 MIN_HEIGHT、横屏用 MAX_HEIGHT(Android 约定: MIN/MAX 分别是
	 * 竖屏/横屏下的高度), 某一边没给(0)就退回另一边 —— 不再无脑取大者。
	 */
	internal fun optionsHeightDp(context: Context, id: GlanceId): Float = runCatching {
		val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
		val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(appWidgetId)
		val minH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
		val maxH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
		// 【第七十四轮·5×9 三个来源全报小】实测这台 MIUI 在 5×9 布局上 MIN_HEIGHT、
		// MAX_HEIGHT、Glance 的 LocalSize 三个全报 ≈280dp, 而真实卡高 ≈379dp(74%)。
		// 上一轮"取大者"因此无效 —— 两个键填的是同一个错值。按优先级换更可靠来源:
		//
		// ① OPTION_APPWIDGET_SIZES(API 31+): 启动器填的**当前精确尺寸**(竖屏+横屏各一份,
		//    SizeF 单位 dp)。这是唯一"按当前布局实测"的来源 —— 有就直接用竖屏那组的高。
		// ② maxOf(minH, maxH): 文档约定竖屏高 = MAX_HEIGHT; MIUI 习惯竖屏高 = MIN_HEIGHT。
		//    取大者对两种填法都取到竖屏高度。
		// ③ 都拿不到(0)返回 0, 由 LessonView 回退 LocalSize。
		//  另外 LessonView 里还有一道 [PROVIDER_MIN_HEIGHT] 硬下限(provider 声明的
		//  minHeight=330dp, resizeMode=none, 启动器不可能把卡片放得比它矮)。
		var h = 0F
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			val sizes = runCatching {
				options.getParcelableArrayList<android.util.SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
			}.getOrNull()
			// 竖屏候选 = "宽 < 高"的那组; 取其中最高者(横屏组的高比竖屏小)
			val portraitH = sizes?.filter { it.width < it.height }?.maxOfOrNull { it.height } ?: 0F
			if(portraitH > 0F) h = portraitH
		}
		if(h <= 0F) h = maxOf(minH, maxH).coerceAtLeast(0).toFloat()
		// 【第一百零五轮·v237·修"5×6 布局课程显示不完全"】像素实测: 用户把桌面布局改成
		// 5×6 后, 学期卡实际视图 ≈257×426dp, 而 SIZES/LocalSize **仍报 320×462**(布局变更后
		// ColorOS 没同步更新 SIZES, 旧值残留) → 按 462 排版的表格底下 36dp 被物理裁掉,
		// 第 11 行整行消失。同一台设备上 4×5 实例的 SIZES 与实际**完全一致**, 不能全局打折。
		// 折中: SIZES 与 MIN/MAX_HEIGHT **都拿到且相差 >8%** 时取小者 —— 宁可底部留白,
		// 不可裁掉课程行; 两来源一致(≤8%)时维持 SIZES, 4×5 等精确实例零影响。
		val mmH = maxOf(minH, maxH)
		if(h > 0F && mmH > 0 && h > mmH * 1.08F) h = mmH.toFloat()
		h
	}.getOrDefault(0F)

	/**
	 * 【第九十三轮】从系统的 `AppWidgetOptions` 里读这张卡的**宽度**(dp), 0 = 读不到。
	 *
	 * 与 [optionsHeightDp] 同一套优先级(SIZES 竖屏组 → MIN/MAX_WIDTH 回退),
	 * 但它不参与排版, 只服务于 MIUI 渲染密度**分档**: 第 92 轮的日志证明,
	 * options 高度相同的两个实例(5×9 与 4×7 都是 362dp)渲染密度不同,
	 * 而宽度不同(274 vs 332) —— 宽度才是能区分两档的唯一信号(见 LessonView 的 miuiK)。
	 */
	internal fun optionsWidthDp(context: Context, id: GlanceId): Float = runCatching {
		val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
		val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(appWidgetId)
		var w = 0F
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			val sizes = runCatching {
				options.getParcelableArrayList<android.util.SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
			}.getOrNull()
			// 竖屏候选 = "宽 < 高"的那组; 取其中最宽者(与高度的取法对齐)
			val portraitW = sizes?.filter { it.width < it.height }?.maxOfOrNull { it.width } ?: 0F
			if(portraitW > 0F) w = portraitW
		}
		if(w <= 0F) {
			// Android 约定: MIN_WIDTH 是竖屏下的宽度; 取小者对两种填法都更接近竖屏宽
			val minW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
			val maxW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)
			if(minW > 0 || maxW > 0) w = minOf(minW, maxW).coerceAtLeast(0).toFloat()
		}
		// 【第一百零五轮·v237】宽度同款交叉校验(5×6 布局下 SIZES 残留 320, 实际 ≈257,
		// 右侧课程列被裁) — SIZES 与 MIN/MAX_WIDTH 相差 >8% 时取小者。
		val minW2 = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
		val maxW2 = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)
		val mmW = if(minW2 > 0 && maxW2 > 0) minOf(minW2, maxW2) else maxOf(minW2, maxW2)
		if(w > 0F && mmW > 0 && w > mmW * 1.08F) w = mmW.toFloat()
		w
	}.getOrDefault(0F)

	/** 课表读不出来时给一个能点的提示, 而不是一块空白 */
	@Composable
	private fun LoadFailedView(context: Context, style: WidgetStyle) {
		// 【第三十九轮】底换成真实磨砂玻璃(GlassRoot 内部会先铺一层 card_background 兜底)
		GlassRoot(
			style,
			modifier = GlanceModifier
				.fillMaxSize()
				.padding(4.dp)
				.clickable(openAppAction(context)),
			contentAlignment = Alignment.Center
		) {
			Column(horizontalAlignment = Alignment.CenterHorizontally) {
				Text(
					text = "课表还没准备好",
					style = TextStyle(color = style.text, fontSize = 14.sp),
					maxLines = 1
				)
				Text(
					text = "点击打开课表",
					style = TextStyle(color = style.text, fontSize = 11.sp),
					maxLines = 1
				)
			}
		}
	}

	/**
	 * 【第三十轮】整张课表的度量 —— 以软件内「学期课表」为基准做**整体等比缩放**。
	 *
	 * 用户原话: "确保展现的学期课表和软件内的学期课表展现的一致, 在软件里面的学期课表基础上
	 * 进行整个页面的等比缩放展示小组件, 确保不缺失任何东西"。
	 *
	 * 软件内(`LessonRender` + `LessonTableView.Tab`)的排版规则是:
	 *  · 横向: 7 个课程列等宽, 左边时间列 = 0.8 个课程列宽(实测 33dp / 42dp), 即总宽 = 7.8 列;
	 *  · 纵向: 顶部日期条(周几 + 日期两行) + 每一节均分剩余高度;
	 *  · 文字: 课程名/地点/教师/日期/时间 **全部 12sp**, 一列能放约 (列宽 / 字号) 个汉字。
	 *
	 * 所以这里只用一个缩放系数 `scale = 小组件课程列宽 / 软件内课程列宽`:
	 *  · 字号 = 12sp × scale → 一行能放几个字、课程名折到第几行、地点/教师放不放得下,
	 *    和软件里完全一致, 只是整体等比缩小(字号上限 1.0 倍, 不会比软件里更大);
	 *  · 列宽同样按 7.8 分, 时间列 0.8 列 → 列比例和软件里一致, 7 天 + 时间列都完整;
	 *  · 竖向先扣掉"周次条 + 日期条", 剩下的按**整像素**均分给每一节(见 `metricsOf`),
	 *    课程方块高度 = 每节高度 × 节数, 所以 11 节、13 节都不会因为四舍五入
	 *    把最后一节挤出卡片(上一版就是这么把第 11 节弄丢的)。
	 */
	internal class Metrics(
		val dayCol: Dp,
		val timeCol: Dp,
		val slotH: Dp,
		val statusH: Dp,
		val dateH: Dp,
		/** 相对软件内课表的缩放系数(0.55 ~ 1.0) */
		val scale: Float,
		/** 课程文字字号(sp): 软件内 12sp × scale, 再小一档(见 FONT_SHRINK) */
		val font: Float,
		/** "软件内等价字号": 只用来算"一行能放几个字 / 折几行", 与软件内口径保持一致 */
		val appFont: Float,
		/** 一行文字占的高度(dp), 用来判断一格能塞几行 */
		val lineH: Float,
		/** 当前时间表的节数(第七十八轮起恢复"永远画满整套时间表") */
		val slots: Int = 1,
		/**
		 * 表格左右两端的固定留白(dp) = 左边 6dp + 右边 [RIGHT_INSET] + 主题内缩。
		 * 两侧各放一个这么宽的 Spacer, 7 个权重列就只会分到剩余的宽度(见 [metricsOf])。
		 */
		val inset: Dp = 0.dp
	) {
		/** 课程方块圆角: 软件内 16px ≈ 0.5 个字宽 */
		val radius: Dp get() = (font * 0.5F).dp

		/**
		 * 【第六十轮】原为表格区的固定排版高度。
		 * 【第一百一十三轮·v240】表格已改权重自适应(行高由启动器按物理高度分配),
		 * 这个值现在只作**时间栏位图的绘制尺寸**(位图按它画, 显示时 FillBounds 拉伸
		 * 到物理表格高, 见 [LessonTimeBar]) —— 它准不准只影响位图清晰度/拉伸比例,
		 * 不再决定行会不会被裁。
		 */
		val tableH: Dp get() = slotH * slots
	}

	internal fun metricsOf(context: Context, width: Float, height: Float, slots: Int, themeInset: Float): Metrics {
		// 7 个课程列 + 0.8 个时间列 = 7.8 列;
		// 再扣掉两段固定的留白: 左边的 [START_INSET] 和右边的 [RIGHT_INSET] + 主题内缩。
		//
		// 【第六十三轮·修"周日的课程超出了底色的范围"】
		// 上一版只扣了 RIGHT_INSET, 但**右侧 7 列用的是 `defaultWeight()`** ——
		// 权重列会把 Row 剩下的宽度全部分掉, 于是最右边那一列的右边缘**永远落在 Row 的右边界**,
		// RIGHT_INSET 根本没起作用。而纯色/哑光/亚克力这三套主题的卡片本体是**右缩 2dp** 的
		// (为了让底下的暗色层露出来当外阴影), 那 2dp 露出来的是壁纸本色, 不是卡片 ——
		// 课块正好压在上面, 看起来就是"周日的课超出去了"。
		//
		// 现在的做法: 在 Row 的最右边**显式放一个宽 = 留白**的 Spacer, 让 7 个权重列
		// 只能分到"扣掉留白之后"的宽度; dayCol 也按同一个口径算, 两边严格一致。
		val inset = RIGHT_INSET + themeInset
		val dayCol = ((width - START_INSET - inset) / 7.8F).coerceAtLeast(1F)
		val timeCol = dayCol * 0.8F
		// 软件内的课程列宽(按本机屏幕宽度算, 软件内时间列同样占 0.8 列)
		val dm = context.resources.displayMetrics
		val screenWidth = (dm.widthPixels / dm.density).coerceAtLeast(1F)
		val appDayCol = (screenWidth / 7.8F).coerceAtLeast(1F)
		// 只往"比软件内小"的方向缩放: 上限 1.0(不比软件里更大), 下限 0.55(再小就看不清了)
		val scale = (dayCol / appDayCol).coerceIn(0.55F, 1F)
		// 软件内课表里所有文字都是 12sp —— 这是"和软件里保持一致"的基准字号
		val appFont = 12F * scale
		// 【第三十一轮·按要求】"课程字号再调小一点": 上一版直接等于基准字号, 一格塞不下两行课名,
		// 看着比软件里还挤。这里整体再乘 FONT_SHRINK(0.88) —— 折行按 appFont 算(见 LessonBlock),
		// 所以"最多显示几行、显示到地点还是教师"仍与软件内一致, 只是每个字更小、更省地方。
		// 【第七十九轮】原"提示字号"fontHint/lineHHint 已删除: 周次条/日期条高度改为按卡片
		// 高度取百分比(见下面 statusH/dateH), 不再由字号反推。
		// 【第三十四轮·按要求】"第 N 周"竖直加宽: 2 个字号(约 18dp)的手指目标太小,
		// 上一版加到 2.8 个字号(26dp)仍然嫌窄 —— 本轮按"现在宽度的 2 倍"直接翻倍到
		// 5.6 个字号 / 不少于 52dp。【第三十六轮】翻周箭头删掉后, 整条周次栏本身仍然可点
		// (进「学期课表」页), 所以这个高度现在算的是"整条的手指目标", 不再是小箭头的身高。
		//
		// 但必须给下面的课表留活路: 面板被拉得很矮时(3 行高的小组件), 52dp 会吃掉近一半高度,
		// 课程文字就没地方写了。所以再加一道上限 —— 周次条最多占面板高度的 22%
		// (翻倍前它约占 8%, 22% 已经非常充裕), 只在面板特别矮时才会真正生效。
		// 【第六十轮】从 5.6 个字号 / 52dp 收到 4.6 个字号 / 44dp:
		// 周次条是"整条可点"的手指目标, 44dp 已经远超无障碍下限; 而它每省下 8dp,
		// 12 节的卡每节就多 0.7dp —— 直接决定"节次/上课/下课"三行放不放得下。
		// 【第七十九轮·按用户要求"把固定相对位置改成按比例确定的相对位置"】
		// 周次条/日期条不再由字号反推, 而是直接取卡片高度的固定百分比:
		// 周次条 = 卡高 × 12.5%, 日期条 = 卡高 × 5.2%。
		// 在 352dp 的 5×9 卡上正好等于原值(44dp / 18.3dp), 观感不变;
		// 换任何布局后"周次条:日期条:表格"三段比例恒定, 文字相对位置不再漂移。
		// 上下限只是极端小卡(3 行高)和大卡的保护, 正常布局碰不到。
		val statusH = (height * 0.125F).coerceIn(30F, 52F)
		val dateH = (height * 0.052F).coerceIn(12F, 22F)
		// 【第六十轮】表格高度与每节高度:
		//
		// 先把"卡片高度 − 周次条 − 日期条 − 底部余量"换成**整像素**, 再除节数(整除, 余数丢掉)。
		// 这样 `slots × slotH` 恰好等于表格区的高度, 时间栏按 `defaultWeight()` 分到每一行的
		// 也是这个数 —— 两套布局算法落到的是同一个像素, 不会出现"越往下越错位"。
		//
		// 底部余量只要盖住"最后一节的底边": 网格改成固定高度之后, 上报尺寸偏大最多压掉
		// 最后一节的下沿, 不可能整行消失(见 [BOTTOM_INSET] 的说明)。
		val density = dm.density.coerceAtLeast(0.1F)
		val bottomReserve = bottomInset.coerceAtLeast(height * BOTTOM_RESERVE_RATIO)
		// 【第七十轮·十·撤回上一版的 0.94 折扣】用户明确说"不是高度的问题, 从一开始就没显示过第 11 节",
		// 而且 14:53 截图里表格**下方还空着一大截** —— 高度富余得很, 打折只是白留白。
		// 真正的原因是时间栏那个 Column 用了 `fillMaxHeight()`(match_parent): 父级给矮一点,
		// 里面固定高的行就从底部整行被裁。已改成 `wrapContentHeight()`(见 [LessonTimeBar])。
		val avail = (height - statusH - dateH - bottomReserve).coerceAtLeast(1F)
		// 【第七十八轮·回退"用到几节画几节"】裁剪行数在 5×9 上把第 11 节时间行整行弄丢了,
		// 与"时间表要和学期课表完全一致"冲突 —— 回到"按传入的 slots(整套时间表)画满"。
		// 课块自己的高度由 slotH × span 决定, 空行只是时间列多几行, 不会"空一块"。
		val tablePx = (avail * density).toInt().coerceAtLeast(slots)
		// 【第六十轮】每节取**整像素**: `floor(表格高度 / 节数)`, 除不尽的余数直接丢掉。
		//
		// 必须整除取整, 不能各自四舍五入 —— 时间栏的一行是 `defaultWeight()`, 布局引擎分给它的
		// 是 `表格高度 / 节数`, 只有和这里的 `slotPx` 完全相等, 两边的第 N 行才会是同一个像素。
		// (dp -> px 是 `(dp * density).roundToInt()`; 反算 `slotPx / density` 再乘回去, 浮点误差
		//  在 1e-4 量级, 一定回到 slotPx 本身。)
		val slotPx = (tablePx / slots).coerceAtLeast(1)
		val slotH0 = slotPx / density
		// 【第六十三轮】第二遍: **保证一格一定放得下 3 行**。
		//
		// 节数一多(11、12 节), 每格只剩 35dp 左右, 而 `font × 1.32` 一行就要 13dp ——
		// 3 行要 39dp, 放不下就只画 2 行, 于是"教室/教师"整片消失(用户说的"不缺失任何东西"
		// 就是这一条)。这里按格高反推字号: 3 行 = 3 × font × 1.32 ≤ 格高 × 0.92(留 2dp 给圆角),
		// 于是 font ≤ 格高 × 0.2323。卡高时这个上限不起作用, 只有节数多的卡才会真的缩小字号。
		// 【第七十轮·十八·修"5*9 布局里时间和课程信息都显示不全"】
		//
		// 系统字号放大(设置里把字体调到"大"/"特大")时: 字号单位是 sp, 渲染出来的**像素高/宽**
		// 会按 fontScale 放大, 而格高/列宽全是 dp —— 于是一格塞不下三行、时间串顶出右边界。
		// 之前所有"按格高反推字号"的公式都默认 fontScale = 1, 用户在 5*9 那台机器上
		// fontScale ≈ 1.05~1.1, 正好把这些公式全部顶破。
		// 现在把 fontScale 显式并进"一格要放下 3 行"的上限里, 公式里的 dp 才是真的 dp。
		val fontScale = (dm.scaledDensity / dm.density).coerceIn(0.85F, 1.6F)
		val font = (appFont * FONT_SHRINK)
			.coerceAtMost(slotH0 * 0.92F / (3F * LINE_FACTOR * fontScale))
		val lineH = font * LINE_FACTOR * fontScale
		return Metrics(dayCol.dp, timeCol.dp, slotH0.dp, statusH.dp, dateH.dp, scale, font, appFont, lineH, slots, inset.dp)
	}

	@Composable
	private fun LessonView(context: Context, snapshot: TableSnapshot, style: WidgetStyle) {
		// 【第三十六轮·按要求】删掉翻周之后, 这里永远显示"本周"(由 updateDate() 算出来的那一周)。
		// 不再有"用户翻到第几周"这个状态, 也就不存在"数据与周次不是同一周"的可能。
		val week = snapshot.week
		// 按周次取格子(带缓存, 缩放时一帧都不用重算 —— 见 buildWeekData)
		val cells = buildWeekData(snapshot.table, week)

		val size = LocalSize.current
		// 【第六十二轮】上限夹到 MAX_SLOT_COUNT: 自定义时间表可以填到十几二十节,
		// 而格子数组只有 MAX_SLOT_COUNT 长 —— 不夹住的话时间栏会画出一堆没有课的空行,
		// 把整个表格顶出卡片。
		//
		// 【第六十五轮·修「第 11 节课的时间不显示」】真正的病根在数据(存档里那套时间表只有 10 条,
		// 见 TimeTables.normalizeEntry), 这里再加一道保险: 如果**本周真的有课**落在第 11、12 节,
		// 即使时间表比它短, 也要把那一行画出来 —— 宁可时间是空的, 也不能把课藏掉。
		//
		// 【第七十五轮】这个数现在只代表"本周最深的一门课到第几节", **不再**直接等于要画的行数:
		// 行数由 [metricsOf] 按"用到的节数 / 时间表节数 / 卡片高度"三者取出来(见那里的说明),
		// 结果放在 [Metrics.slots] 里。
		// 【第七十五轮】顺手把这里从"起始节 +1"改成"起始节 + 这门课的长度":
		// 行数若按"用到几节画几节"裁, 只算起始节会让"最后一门 2 节连堂"被砍成 1 节。
		// 【第七十八轮·按要求回退"用到几节画几节"】用户原话: "第11节课的课程和时间怎么叫你改没了"
		// —— 裁剪后 5×9 上时间栏只剩 10 行(19:50/20:35 整行消失), 与软件内学期课表不再一致。
		// 恢复 v0196 口径: **永远画满整套时间表**(第 11 节时间行常驻), 行数只增不减;
		// usedByLesson 只负责"时间表比课短时, 把课所在的行补出来"。
		var usedByLesson = 0
		for(d in 0..6) {
			for(i in cells[d].indices) {
				val lesson = cells[d][i]?.current(week) ?: continue
				usedByLesson = maxOf(usedByLesson, i + lesson.len.coerceAtLeast(1))
			}
		}
		val slots = maxOf(TimeTables.currentSlotCount, usedByLesson).coerceIn(1, TimeTables.MAX_SLOT_COUNT)
		// 【第六十三轮】把主题的"卡片本体内缩"一起交给 metricsOf: 纯色/哑光/亚克力右缩 2dp,
		// 那 2dp 露出来的是壁纸, 课块不能再压上去(见 metricsOf 里的说明)。
		// 【第六十五轮】把启动器原始 options 引进来, 救"LocalSize 报小"的场。
		// 【第六十八轮·六】改成 **options 优先**: options 读到(>0)就只信 options ——
		// 它是启动器真实布局用的那份数据; LocalSize 在这台 MIUI 上会飘(同一张卡量到过
		// 575dp 和 287dp, 真实 ≈481dp), 取 maxOf 时只要它一报大, 表格底部就整行被裁
		// (16:12 截图: 第 10 行被裁半截、第 11 行整行不可见, 见 optionsHeightDp 的说明)。
		// LocalSize 只在 options 读不到(个别启动器不填)时回退使用。
		//
		// 【第七十轮·二·修"第 11 行还是看不见"】options=0(MIUI 不填)时只能信 LocalSize,
		// 但它仍然**报大**: 21:18 截图实测 — 上报 495dp, 时间栏 11 行(44+24+406=474dp)
		// 却只有约 10.3 行可见(第 11 节课块只露出一个小头), 反推真实可视高度 ≈450~470dp,
		// 虚高 5~9%。给回退分支打 **0.90 折**(495 → 445): 宁可底部留一条空白,
		// 也不能让最后一节的时间被卡底吃掉 —— "显示不全"是用户点名的问题,
		// "底部留白"只是不完美。options 有值的启动器不受影响(它们报的是准数)。
		// 【第七十轮·五】不再按百分比打折: LocalSize 实测是准的(482 vs 495), 只扣一条固定边距。
		// 【第七十轮·七·修"第 11 节还是看不见"】两个高度来源**取小的那个**。
		//
		// 上一版是"options 有值就只用 options"。13:47 截图实测: 卡片画到第 10 节
		// (18:55-19:40) 就到底了, 第 11 节(19:50-20:35)整行落在卡片外 —— 说明 options
		// 报的高度比启动器真正留给 RemoteViews 的高度**大了一整行**。
		// 而 LocalSize 是布局阶段交给这次组合的尺寸, 它才是"画出来会不会被裁"的判据。
		// 两个都可能不准, 但**取小**永远安全: 宁可底部多一条空白, 也不能把最后一节吃掉。
		val localH = size.height.value
		// 【第九十一轮·按实测日志标定】这台 HyperOS 渲染小组件内容的有效密度**分两档**
		// (时间栏行距按像素反推, 每个实例都有日志+截图双验证):
		// 【第九十三轮·分档依据换成"options 宽度"】第 92 轮用户桌面重排后, 今日课表出现
		// options=362dp 的实例 —— 与 5×9 今日课表高度完全相同(MIN/MAX/Local 也都相同),
		// 但它按 1:1 渲染(截图实测卡高 ≈350dp), 沿用 1.26 直接底部裁掉一节课。
		// 两个实例唯一的差别是**宽度**: 5×9=274dp、4×7=332dp。回看全部标定数据,
		// 宽度完美区分两档, 且此前按高度分档的所有正常实例全部兼容:
		//   宽 274dp(5×9 学期/今日)                     → 0.79 档, 系数 1.26;
		//   宽 332/343/345/351dp(4×6/4×7 学期/今日)     → 1:1 档, 系数 1.0。
		// 分档边界取 295~305 线性过渡; 只以读到的 options 宽度为依据, 不判断厂商系统版本。
		val miuiK = when {
			snapshot.windowW <= 0F -> 1F
			snapshot.windowW <= 295F -> 1.26F
			snapshot.windowW >= 305F -> 1.0F
			else -> 1.26F - (snapshot.windowW - 295F) / 10F * 0.26F
		}
		val miuiOn = android.os.Build.MANUFACTURER.contains("xiaomi", true) ||
			android.os.Build.BRAND.contains("xiaomi", true) ||
			android.os.Build.BRAND.contains("redmi", true)
		val picked = if(miuiOn && miuiK > 1F && snapshot.windowH > 0F){
			snapshot.windowH * miuiK
		}else{
			val cands = ArrayList<Float>(2)
			if(snapshot.windowH > 0F) cands.add(snapshot.windowH)
			if(localH > 0F) cands.add(localH)
			// 【第一百零五轮·OPPO 适配·渲染诊断定案(v231)】此前 v224~v226 的 0.85/0.92/0.88
			// 系数全部是在给**准确的值乱打折** —— 诊断数据(SIZES=578.7x225.3|320.0x442.7)
			// 证明: 竖屏组 320×442.7 就是真实尺寸, 且 MAX_HEIGHT=442 与之互相印证;
			// LocalSize(578.7x225.3, 宽高互换乱飘)才是垃圾来源。此前"底部裁行/留白"
			// 是 0.88 打折后比真实矮, 11 节画不下 —— 现在原值直用, 不打任何折扣。
			//   => 非小米: options 高/宽**原值直用**; options 读不到(0)才退 LocalSize。
			//   MIUI/HyperOS 维持原路: 5×9 走 miuiK 系数, 其余取 min(options 报大 19%)。
			if(cands.isEmpty()) 200F
			else if(miuiOn) cands.minOrNull() ?: 200F
			else if(snapshot.windowH > 0F) snapshot.windowH
			else localH
		}
		// 【第七十四轮】provider 的 minHeight(330dp)是硬下限: resizeMode=none, 启动器
		// 分给卡片的实际高度**不可能**低于它 —— 5×9 上三个来源全报 280dp 时, 取小/取大
		// 都救不回来, 这里直接抬到 330(真实 ≈379, 剩余误差已不到一行的 1/4)。
		// 正常布局(4×6 等)上报 ≥ 400dp, 这个下限不参与。
		// 【第一百一十一轮·v238·高度校准】像素级定案(5×6 布局截图 + 渲染诊断交叉验证):
		// ColorOS 改桌面布局后 options 全套陈旧(上报 320×462, 物理实测 ≈259×428 @density 3.0),
		// 且 MIN/MAX 与 SIZES 同源同旧、内部互相印证, **代码无法判别陈旧**; resizeMode=none
		// 用户也无法靠缩放触发刷新。启动器会按物理单元格重新测量根布局 —— 横向权重列自动
		// 适应了, 纵向固定行高(slotH×11 = 374dp)在 428dp 里正好溢出一行, 第 11 节被裁。
		// 校准档位(WidgetStyleStore.heightCal)在排版源头按比例收缩, 用户可调;
		// 默认 100 = 原值直用, 尺寸准确的启动器零影响; 小米路径有自己的 miuiK, 不叠加。
		val calH = if(miuiOn) 1F else WidgetStyleStore.heightCal() / 100F
		val pickedEff = picked * calH
		val reportedH = (pickedEff - CARD_EDGE_INSET)
			.coerceAtLeast(PROVIDER_MIN_HEIGHT - CARD_EDGE_INSET)
			.coerceAtLeast(160F)
		// 【第一百零五轮】宽度同样按厂商分流: 这台 OPPO 上 LocalSize 宽**报大一半以上**
		// (诊断: LocalSize=578.7 宽 vs options=320 真实), dayCol/timeCol 按它算会把
		// 时间列撑宽, 周一列被推得离时间列很远(用户实测)。
		// 【v231·渲染诊断定案】options 宽原值直用(320=真实), 不再 ×0.92 打折;
		// MIUI 维持 LocalSize(它的 miuiK 标定基于现状, 不能动)。
		val layoutW = if(miuiOn) size.width.value
			else if(snapshot.windowW > 0F) snapshot.windowW
			else size.width.value
		val m = metricsOf(context, layoutW, reportedH, slots, style.theme.bodyInset)
		// 【第一百零五轮】渲染诊断: 原始上报值与最终取值全量记录(见 WidgetDiag)
		com.qust.helper.utils.WidgetDiag.add(
			"Term[view] winH=${snapshot.windowH} winW=${snapshot.windowW} localH=$localH " +
			"localW=${size.width.value} picked=$picked cal=$calH pickedEff=$pickedEff layoutW=$layoutW slotH=${m.slotH.value} slots=$slots"
		)
		val openApp = openAppAction(context)

		// 【第七十八轮】行数恢复"永远画满整套时间表"(第 11 节时间行常驻):
		//   画 = max(时间表节数, 课表用到) —— 只增不减。

		// 【第三十九轮】卡底换成真实磨砂玻璃: GlassRoot 先铺一层 card_background 兜底,
		// 再把"糊好的壁纸 + 白蒙版 + 圆角"的位图盖上去(配方与几何见 WidgetGlass 的说明)。
		// 【第一百零五轮·v233·修"点小组件没反应"】以前只有顶部"第 N 周"条和日期条可点,
		// 用户点表格区域(占卡片九成面积)毫无反应 —— 与「今日课表」同款, 整卡可点进 App。
		GlassRoot(
			style,
			modifier = GlanceModifier
				.fillMaxSize()
				.clickable(openApp)
		) {
			Column(
				modifier = GlanceModifier.fillMaxSize(),
				horizontalAlignment = Alignment.Start
			) {
				// 【第三十六轮·按要求】"删除学期课表小组件切换周的功能": 顶部只留"第 N 周"这行字,
				// 两个翻周箭头(以及它们的 UpdateActionCallback)一起去掉。
				LessonStatusBar(week = week, m = m, style = style, openApp = openApp)

				// 日期条点一下进 App
				LessonDateBar(startDay = Date(snapshot.startDay), week = week, m = m, style = style, openApp = openApp)

				// ── 【第一百一十三轮·v240·表格纵向自适应】──
				//
				// 【v111 的像素级定案(为什么必须这么做)】启动器会按物理单元格**重新测量**
				// RemoteViews 根布局 —— 横向 7 列是 defaultWeight() 权重列, 任何布局下都自动
				// 贴合物理宽度(所以课程列永远看得全); 而纵向行高是固定 dp(slotH × slots),
				// options 一陈旧(换桌面布局后 ColorOS 不更新 options, 且 5×6 与 4×6 上报的
				// 数值**完全相同**)就整行溢出被裁或大片留白 —— 5×6 要 92%、4×6 要 100%,
				// 手动校准档位永远追不上布局切换。
				//
				// 【解法: 把纵向也交还给权重系统】
				//  · 表格行不再 .height(tableH), 改 defaultWeight() —— 吃掉"周次条+日期条"
				//    以下的**全部物理高度**, 节数自动均分, 上报准不准都无所谓;
				//  · 时间栏是 ALPHA_8 位图 + FillBounds(第七十轮), 容器改 fillMaxHeight 后
				//    随物理高度**等比拉伸**, 行距与右侧课程格天然对齐(两者都是"表格高÷节数");
				//  · 课程块**按块渲染、逐格拼接**(见 [LessonBlockCell]): 一门 n 节的课 =
				//    连续 n 个权重格(首末格圆角、中间格方角、格间零缝)拼成一个无缝整块;
				//    文字行进统一的行高网格, 按格切片铺满整块并垂直居中(v243 回归小米式
				//    观感, 取代 v240"文字只画首格"的方案 —— 用户要求撤销)。
				//  · 底部留 4dp(v242) 防时间栏末行贴卡底被裁。
				//
				// 【第一百一十四轮·v241→v242·"权重格"的正确认知(反编译 Glance 1.0.0 布局模板实锤)】
				// Glance 的 defaultWeight() 落到 LinearLayout 是 **layout_height=0dp +
				// layout_weight=1** —— 这本来就是 LinearLayout 的"严格均分"模式(与内容
				// 无关), v241 担心的"先按内容量再分剩余"并不存在于这条路径上。真正的问题:
				// v241 给格子加的 .height(slotH) 会被 Glance 的 normalize 阶段抽出来
				// (ExtractedSizeModifiers), 在 weight 容器**里面**再套一个 34dp 固定高的
				// SizeBox —— 格子分到 ~40dp 时内容只有 34dp 且顶对齐, **每格底部空一截**,
				// 相邻格拼起来就是周期性白缝(连堂课"分成两部分"的真凶), 真机截图 8px/格。
				// 教训: **weight 格子上永远不要再挂固定尺寸**, 内容用 fillMaxSize 吃满格子。
				//
				// 【v242·底部余量】表格吃满卡片后, 时间栏位图(等比拉伸)最后一行时间文字
				// 会画到槽底, 贴着卡片物理底缘被圆角裁掉半个字("20:35 显示不全"的真凶)。
				// 表格底部留 4dp, 时间栏与课程格一起抬起来, 不再贴边。
				//
				// (历史: 第六十~七十轮的"固定网格 + 底部余量"方案及其数轮调参, 被本轮整体取代。)
				Row(
					modifier = GlanceModifier
						.fillMaxWidth()
						.defaultWeight()
						.padding(start = START_INSET.dp, bottom = 6.dp)
				) {
					LessonTimeBar(TimeTables.currentTimes, m, style, slots)

					// 【v243】系统字体缩放进文字预算(与旧 LessonBlock 同口径)
					val fontScale = (LocalContext.current.resources.configuration.fontScale).coerceIn(0.85F, 1.6F)

					for(day in 0..6) {
						Column(modifier = GlanceModifier.fillMaxHeight().defaultWeight()) {
							// 逐格归属映射(与旧"块堆叠"循环同一语义: 起始格挂课, 中间格标延续)。
							// 【v242】上限一律用 slots(cells[day] 长度=MAX_SLOT_COUNT≥slots,
							// 旧代码 min(cells.size, slots) 的截断在 len 跨到 max 之外时会把
							// 尾节的延续格吞掉), 索引用 getOrNull 兜底。
							val starts = arrayOfNulls<LessonData>(slots)
							val conts = arrayOfNulls<LessonData>(slots)
							var i = 0
							while(i < slots) {
								val lesson = cells[day].getOrNull(i)?.current(week)
								if(lesson == null) { i++; continue }
								val span = lesson.len.coerceAtLeast(1).coerceAtMost(slots - i)
								starts[i] = lesson
								for(j in 1 until span) conts[i + j] = lesson
								i += span
							}
							var s = 0
							while(s < slots) {
								val start = starts[s]
								if(start != null) {
									// 【第一百一十五轮·v243·连堂课恢复"整块 + 文字纵向居中分布"】
									// v240 的"逐格渲染"(文字只画首格)被用户否决 —— "课程信息挤在
									// 一节课, 另一节课都是空白"。现在回归 v239 前的观感(也是小米
									// 小组件的样式): 一门 n 节的课仍是一个完整大块, 文字行按统一
									// 行高网格**铺满整块的所有格子**(垂直居中), 每格只画分到自己
									// 头上的那几行。字号预算也按整块高度算(连堂课恢复大字号多行)。
									// 纵向自适应不受影响: 每格还是 defaultWeight, 行盒固定行高,
									// 格子拉伸时行分布随之拉开(与时间栏位图的拉伸同理)。
									var span = 1
									while(s + span < slots && conts[s + span] === start) span++
									val bl = layoutBlock(
										start, m, span,
										showTeacher = !snapshot.hideTeacher,
										fontScale = fontScale
									)
									for(j in 0 until span) LessonBlockCell(start, bl, m, style, j, span)
									s += span
								}else{
									// Glance 的 Box 没有默认 content 参数, 空格子也要传空 lambda
									Box(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {}
									s++
								}
							}
						}
					}

					// 右端留白(见 metricsOf): 7 个权重列到此为止, 不会顶到卡片外沿
					Spacer(modifier = GlanceModifier.width(m.inset).fillMaxHeight())
				}
			}
		}
	}

	/**
	 * 一门课的**整块文字布局**(第一百一十五轮·v243) —— 非组合态的纯计算。
	 *
	 * 把"课名(真实折行) + 地点 + 教师"排进一个统一行高的**行网格**, 网格总槽位 =
	 * span 格 × 每格容量, 文字行整体**垂直居中**填入(前后留空槽)。字号预算按整块
	 * 高度算(span × slotH) —— 连堂课恢复 v239 前的"大字号多行"待遇(用户要求回归
	 * 小米式观感, 见调用处的 v243 注释)。
	 *
	 * 每格渲染时按自己的槽位切片画(见 [LessonBlockCell]), 空槽用等行高的占位撑住,
	 * 保证跨格的行距与格内完全一致 —— 视觉上就是"一块, 文字在中间"。
	 */
	private class BlockLayout(
		val font: Float,
		val lineH: Float,
		val capPerSlot: Int,
		val slotLines: Array<String?>
	)

	private fun layoutBlock(
		lesson: LessonData,
		m: Metrics,
		span: Int,
		showTeacher: Boolean,
		fontScale: Float
	): BlockLayout {
		val innerW = m.dayCol.value - 3F
		val fit = fitBlockText(
			name = lesson.name,
			place = lesson.place,
			teacher = lesson.teacher,
			innerH = span * m.slotH.value - 2F,
			innerW = innerW,
			baseFont = m.font,
			maxNameLines = if(span == 1) 4 else 6,
			wantTeacher = showTeacher,
			fontScale = fontScale,
			capLimit = 6 * span
		)
		val lineH = (fit.font * LINE_FACTOR * fontScale).coerceAtLeast(0.1F)
		// 行序列: 课名按真实宽度折行(与 rawLineCount 同一套 breakText 口径),
		// 折行段超过预算行数时最后一行补省略号(替代旧 TextView 的 ellipsize)。
		val rows = ArrayList<String>()
		val wrapped = wrapText(lesson.name, fit.font * fontScale, innerW)
		val nameLines = fit.nameLines.coerceAtMost(wrapped.size).coerceAtLeast(1)
		for(i in 0 until nameLines) {
			val tail = if(i == nameLines - 1 && wrapped.size > nameLines) "…" else ""
			rows.add(wrapped[i] + tail)
		}
		if(fit.showPlace) rows.add(lesson.place)
		if(fit.showTeacher) rows.add(lesson.teacher)
		// 【第一百一十六轮·v244】每格行数 n: 行盒改为**权重均分实际格高**(见
		// LessonBlockCell), n 只决定网格密度 —— 取"预算格高能放下几行"(不扣边距,
		// 行盒内容居中自带余量), 字体放大时 n 自动变小、行距自适应。
		val n = (m.slotH.value / lineH).toInt().coerceIn(1, 6)
		val total = n * span
		val lead = ((total - rows.size) / 2).coerceAtLeast(0)
		val slotLines = arrayOfNulls<String>(total)
		rows.take((total - lead).coerceAtLeast(0)).forEachIndexed { idx, t -> slotLines[lead + idx] = t }
		return BlockLayout(fit.font, lineH, n, slotLines)
	}

	/** 课名的真实折行(与 [WidgetFontRange.rawLineCount] 同一套 Paint.breakText 口径, 但返回每行文本)。 */
	private fun wrapText(text: String, font: Float, width: Float): List<String> {
		if(text.isEmpty()) return emptyList()
		if(width <= 0F || font <= 0F) return listOf(text)
		val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = font.coerceAtLeast(0.1F) }
		val out = ArrayList<String>()
		var pos = 0
		val n = text.length
		while(pos < n) {
			var count = paint.breakText(text, pos, n, true, width, null)
			if(count <= 0) count = 1
			val end = (pos + count).coerceAtMost(n)
			out.add(text.substring(pos, end))
			pos = end
		}
		return out
	}

	/**
	 * 一门课的**第 index 格**(第一百一十六轮·v244)。
	 *
	 * 一个 span 格的课 = 连续 span 个这样的格子拼成无缝整块:
	 *  · **色块** = 课色直接画进位图(ARGB, 四角独立圆角) —— 首格只上圆角、末格只
	 *    下圆角、中间格全直角, 拼接处不再有 Glance cornerRadius 四角统一造成的
	 *    "月牙截断感"(v243 用户实证); v245 弃用 ALPHA_8+tint(ColorOS 上半透色
	 *    经 tint 后颜色丢失, 块几乎全白), 课色直接 Paint 进位图最可靠。
	 *  · **文字** = 每格 n 个**权重行盒**(defaultWeight 均分实际格高) —— 行位置
	 *    随物理格高自适应, 跨格行距与格内严格一致(都是"块高÷总行数"), 不再出现
	 *    "预算格高 vs 实际格高"的偏差导致的整体偏上(v243 用户实证"和时间对不上")。
	 */
	@Composable
	private fun ColumnScope.LessonBlockCell(
		lesson: LessonData,
		bl: BlockLayout,
		m: Metrics,
		style: WidgetStyle,
		index: Int,
		span: Int
	) {
		val bg = WidgetGlass.block(lesson.color, style.dark)
		val fg = style.blockText(lesson.color)
		val isFirst = index == 0
		val isLast = index == span - 1
		// 高度 = 本格的全部(权重分配); 缝只出现在块的四周(0.5dp, 与旧版"块间 1dp"一致),
		// 块内部格与格之间零缝。
		Box(
			modifier = GlanceModifier
				.fillMaxWidth()
				.defaultWeight()
				.padding(
					start = 0.5.dp, end = 0.5.dp,
					top = if(isFirst) 0.5.dp else 0.dp,
					bottom = if(isLast) 0.5.dp else 0.dp
				)
		) {
			// 色块层: 课色直接画进位图(四角独立圆角; v245 弃用 tint, 见 blockCellBitmap 注释)
			Image(
				provider = ImageProvider(blockCellBitmap(m, bg, isFirst, isLast, span)),
				contentDescription = null,
				modifier = GlanceModifier.fillMaxSize(),
				contentScale = ContentScale.FillBounds
			)
			// 文字层: n 个权重行盒均分实际格高, 槽位有文字就画、没有就空盒撑网格
			Column(
				modifier = GlanceModifier
					.fillMaxSize()
					// 【第六十七轮】左右内边距 0.5dp: 长课名正好卡在半个字的宽度上被省略
					.padding(horizontal = 0.5.dp)
			) {
				val from = index * bl.capPerSlot
				for(p in from until from + bl.capPerSlot) {
					Box(
						modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
						contentAlignment = Alignment.Center
					) {
						val line = bl.slotLines.getOrNull(p)
						if(line != null) {
							Text(
								text = line,
								style = TextStyle(color = fg, fontSize = bl.font.sp, textAlign = TextAlign.Center),
								maxLines = 1
							)
						}
					}
				}
			}
		}
	}

	/** 块格形状的四种类型(四角独立圆角, 见 [blockCellBitmap])。 */
	private const val SHAPE_SINGLE = 0    // 单格块: 四角圆
	private const val SHAPE_CAP_TOP = 1   // 整块首格: 上两角圆, 下两角直(与延续格拼接)
	private const val SHAPE_MIDDLE = 2    // 整块中间格: 四角全直
	private const val SHAPE_CAP_BOTTOM = 3 // 整块末格: 下两角圆, 上两角直

	/** 色块位图缓存(key = 课色+形状+尺寸; 一周典型 ~15-25 个组合, @2x 约 22KB/张)。 */
	private val shapeMasks = HashMap<String, Bitmap>()

	/**
	 * 取(或画)一张格子色块位图: **课色直接画进位图**(ARGB_8888, 四角独立圆角)。
	 *
	 * 【第一百一十八轮·v245】v244 用的"ALPHA_8 形状模板 + ColorFilter.tint(半透课色)"
	 * 在这台 ColorOS 上真机实证**颜色丢失**(块几乎全白, 只有文字有色) —— 半透色经
	 * PorterDuff tint 后在启动器的 ImageView 上表现不可靠。改为把半透课色直接
	 * `Paint.color` 画进 ARGB 位图 —— 与时间栏不同(时间栏是单色深字, tint 可靠),
	 * 课程块是多彩半透色, 必须自带颜色。
	 *
	 * 按 2x 密度绘制(fillMaxSize + FillBounds 拉伸到实际格尺寸): 纯色 + 圆角
	 * 对分辨率不敏感, 2x 足够; 内存 = 尺寸 80×68×4B ≈ 22KB × 一周的组合数。
	 */
	private fun blockCellBitmap(m: Metrics, color: androidx.compose.ui.graphics.Color, isFirst: Boolean, isLast: Boolean, span: Int): Bitmap {
		val shape = when {
			isFirst && isLast -> SHAPE_SINGLE
			isFirst -> SHAPE_CAP_TOP
			isLast -> SHAPE_CAP_BOTTOM
			else -> SHAPE_MIDDLE
		}
		val density = (android.content.res.Resources.getSystem().displayMetrics.density * 2F / 3F).coerceAtLeast(0.1F)
		val w = ceil(m.dayCol.value * density).toInt().coerceAtLeast(1)
		val h = ceil(m.slotH.value * density).toInt().coerceAtLeast(1)
		val r = (m.radius.value * density).toInt().coerceIn(0, minOf(w, h) / 2)
		val argb = color.toArgb()
		val key = "$argb-$shape-$w-$h-$r"
		synchronized(shapeMasks) {
			shapeMasks[key]?.let { return it }
			val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
			val canvas = Canvas(bmp)
			// 四角独立半径: [左上x,左上y, 右上x,右上y, 右下x,右下y, 左下x,左下y]
			val radii = FloatArray(8)
			when(shape) {
				SHAPE_SINGLE -> Arrays.fill(radii, r.toFloat())
				SHAPE_CAP_TOP -> { radii[0] = r.toFloat(); radii[1] = r.toFloat(); radii[2] = r.toFloat(); radii[3] = r.toFloat() }
				SHAPE_CAP_BOTTOM -> { radii[4] = r.toFloat(); radii[5] = r.toFloat(); radii[6] = r.toFloat(); radii[7] = r.toFloat() }
				// SHAPE_MIDDLE: 全 0(直角)
			}
			val path = android.graphics.Path()
			val rect = android.graphics.RectF(0F, 0F, w.toFloat(), h.toFloat())
			path.addRoundRect(rect, radii, android.graphics.Path.Direction.CW)
			canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = argb })
			if(shapeMasks.size > 40) shapeMasks.clear()  // 防(色×形×规格)组合无限增长挤爆 RemoteViews
			shapeMasks[key] = bmp
			return bmp
		}
	}

	/**
	 * 【第六十八轮】在"字号范围"里挑一个能把课程信息画得**最全**的字号。
	 *
	 * 判据完全按"这一格到底装得下几行"来算, 不再用固定阈值:
	 *  · 一格能画的行数 `cap = 格高 / (字号 × [LINE_FACTOR])`;
	 *  · 课名要几行 `needed`(按**当前字号**算, 字小了自然折得少, 这正是"缩小换信息"的来源);
	 *  · 课名 + 地点 + 教师 的总行数 ≤ `cap` 才算"全都在"。
	 *
	 * 三轮退让(和软件内「学期课表」同一套顺序):
	 *  ① 全都要 -> ② 只保课名 + 地点 -> ③ 只保课名不被省略号吃 -> ④ 都不行就用基准字号。
	 * 之所以把"课名完整"贯穿在三轮里: 用户反复提过"课程名被压成一个字 + 省略号", 那是第一优先级。
	 */
	private fun fitBlockText(
		name: String,
		place: String,
		teacher: String,
		innerH: Float,
		innerW: Float,
		baseFont: Float,
		maxNameLines: Int,
		wantTeacher: Boolean,
		fontScale: Float = 1F,
		capLimit: Int = 6
	): BlockFit {
		val range = WidgetFontRange.around(baseFont)
		val hasPlace = place.isNotEmpty()
		val hasTeacher = wantTeacher && teacher.isNotEmpty()

		fun at(font: Float): BlockFit {
			// 【第七十轮·十九】fontScale 进预算: sp 字号的实际渲染行高 = font × 行距 × fontScale
			val lineH = (font * LINE_FACTOR * fontScale).coerceAtLeast(0.1F)
			// 【第一百一十五轮·v243】cap 上限改为参数: 连堂块按整块高算预算时,
			// 一块的行数可以远超 6(旧的单格上限), 上限 = 6 行/格 × span 格。
			val cap = (innerH / lineH).toInt().coerceIn(1, capLimit)
			// 【第七十轮·修"文字少的课名却显示不全"】课名要几行 / 放不放得下,
			// 都改成 Paint 逐字测量的**真实行数**(见 WidgetFontRange.rawLineCount):
			// 旧版"字数 ≤ 估的每行字数 × 最大行数"的乘法判据在边界上会误判放得下,
			// "网络数据开发技术"(8 字)就这样在基准字号下被判过、渲染时被 maxLines 掐出省略号。
			// 现在真实行数 > maxNameLines 就算放不下 -> 往下缩字号, 直到真的装得下。
			//
			// 【第九十四轮·修"5×9 课名还是出省略号"】测量字号也要乘 fontScale!
			// TextView 实际按 font×fontScale 排版(系统字体缩放同时放大**字宽**), 旧版
			// 预算按裸 font 测 —— 每行会多估一个字: 5×9 上"习近平新时代…概论"(18 字)
			// 预算判 4 行(5 字/行), TextView 实际 4 字/行要 5 行, 被 maxLines=4 掐出
			// "主义思…"(用户 11-6 截图实证)。预算与渲染同口径后, nameFits ⇒ 必不省略。
			val neededRaw = WidgetFontRange.rawLineCount(name, font * fontScale, innerW)
			val needed = neededRaw.coerceAtMost(maxNameLines)
			val nameFits = neededRaw <= maxNameLines
			val placeOk = !hasPlace || cap >= needed + 1
			val teacherOk = !hasTeacher || cap >= needed + (if(hasPlace) 1 else 0) + 1
			return BlockFit(
				font = font,
				nameLines = needed,
				showPlace = hasPlace && placeOk,
				showTeacher = hasTeacher && teacherOk,
				nameFits = nameFits,
				placeOk = placeOk,
				teacherOk = teacherOk
			)
		}

		val step = range.endInclusive * WidgetFontRange.STEP_RATIO
		WidgetFontRange.largest(range, step) { f -> at(f).let { it.nameFits && it.placeOk && it.teacherOk } }
			?.let { return at(it) }

		WidgetFontRange.largest(range, step) { f -> at(f).let { it.nameFits && it.placeOk } }
			?.let { return at(it) }

		WidgetFontRange.largest(range, step) { f -> at(f).nameFits }
			?.let { return at(it) }

		// 【第九十四轮】兜底改判: 旧版"全档位都放不下课名 -> 回基准字号 + 省略号"
		// (第七十六轮的原则"再缩只会糊"), 但用户这轮明确点名 5×9 的省略号还要治 ——
		// 取舍反转: **字小但完整 > 字大被省略号吃**。改成"最小字号 + 课名画到格子的
		// 物理极限(cap)行", 地点/教师让位; 只有物理极限也装不下整个课名时才真省略。
		val minFont = range.start
		val rawMin = WidgetFontRange.rawLineCount(name, minFont * fontScale, innerW)
		val capMin = (innerH / (minFont * LINE_FACTOR * fontScale).coerceAtLeast(0.1F)).toInt().coerceIn(1, capLimit)
		val linesMin = rawMin.coerceAtMost(maxOf(maxNameLines, capMin))
		return BlockFit(
			font = minFont,
			nameLines = linesMin,
			showPlace = false,
			showTeacher = false,
			nameFits = rawMin <= linesMin,
			placeOk = false,
			teacherOk = false
		)
	}

	/**
	 * 顶部"第 N 周"。
	 *
	 * 【第三十六轮·按要求】"删除学期课表小组件切换周的功能" —— 左右两个翻周箭头
	 * (以及它们背后的 `UpdateActionCallback`、周次的持久化)全部去掉, 现在这里只是**一行标题**,
	 * 并且整条都可以点开「学期课表」页。
	 *
	 * 顺带也解决了一个老毛病: 翻周是"点一下 -> 起一个广播 -> 拉起 ActionCallbackService ->
	 * 读课表 -> 写状态 -> 全量重绘"的一整条链路, 在桌面上至少要等半秒。
	 * 现在这条路不存在了, 少了一个能卡住的地方。
	 */
	@Composable
	internal fun LessonStatusBar(week: Int, m: Metrics, style: WidgetStyle, openApp: Action) {
		Row(
			modifier = GlanceModifier.fillMaxWidth().height(m.statusH).clickable(openApp),
			horizontalAlignment = Alignment.CenterHorizontally,
			verticalAlignment = Alignment.Vertical.CenterVertically
		) {
			// 卡片是浅色底, 文字一律用小组件专用固定色, 不走 GlanceTheme(深色模式下会变浅、糊在浅底上)
			Box(
				modifier = GlanceModifier.fillMaxSize(),
				contentAlignment = Alignment.Center
			) {
				Text(
					text = "第 ${week + 1} 周",
					style = TextStyle(color = style.text, fontSize = (m.font * 1.35F).sp, fontWeight = FontWeight.Medium),
					maxLines = 1
				)
			}
		}
	}

	@Composable
	internal fun LessonDateBar(startDay: Date, week: Int, m: Metrics, style: WidgetStyle, openApp: Action) {
		val currentDay = Calendar.getInstance()
		val c = Calendar.getInstance().also {
			it.time = startDay
			it.firstDayOfWeek = Calendar.MONDAY
			// 对齐到所在周的周一, 再偏移 week 周; 与课表页 weekStartDate 使用同一规则,
			// 避免跨年/非周一开学时日期错位(错位会连带使调休看起来没有生效)
			it.add(Calendar.DATE, -((it.get(Calendar.DAY_OF_WEEK) + 5) % 7))
			it.add(Calendar.DATE, week * 7)
		}

		val todayColor = ColorProvider(LESSON_TEXT_COLORS[0])

		// 【第七十轮·十五·按要求"本周课表和小窗口的 11月 还是不对"】
		//
		// 上一版把"11月"放在 `width(m.timeCol)` 的盒子里居中, 也就是**整条时间列的正中间**。
		// 用户截图(15:47)按像素量过: 卡片左边 57px, 时间串 "08:00" 占 68..118px(中心 93),
		// 而时间列宽 34dp=77px(中心 109) —— 月份落在 90..127, 中心 108。
		// 也就是说它**确实**在列的正中间, 但列比时间串宽(右侧那 10dp 是空的),
		// 肉眼看就是"月份比时间串偏右"。用户要的是**对着时间串居中**, 不是对着空列居中。
		//
		// 所以这里不再用列宽, 而是按时间串的**实际墨迹宽度**取盒宽: 用 Paint 量一遍
		// "08:00" 这类宽串(取 08:00 / 09:45 / 20:35 三者最大, 数字字形宽窄不同),
		// 盒子只有这么宽, 再让月份在里面居中 —— 中线就压时间串的中线上了。
		val context = LocalContext.current
		val monthBoxWidth = remember(m.timeCol.value, m.slotH.value, m.font) {
			val timeFont = minOf(m.slotH.value / (3F * 1.42F), m.font).coerceAtLeast(2.4F)
			val dm = context.resources.displayMetrics
			val paint = Paint().apply { textSize = timeFont * dm.scaledDensity.coerceAtLeast(0.1F) }
			val widest = maxOf(paint.measureText("08:00"), paint.measureText("09:45"), paint.measureText("20:35"))
			(widest / dm.density.coerceAtLeast(0.1F)).dp
		}

		// 和软件内一样的日期条: 左上角"几月", 右边 7 格"周几 + 日期(1 号显示月份)"
		//
		// 【第五十六轮】7 个日期格同样改成 `defaultWeight()`, 与下面课程表的列**同一套分宽规则**,
		// 否则日期条按"计算宽度"排、课表按"实际宽度"排, 两者会从左到右越来越错位。
		Row(
			modifier = GlanceModifier.fillMaxWidth().height(m.dateH).padding(start = START_INSET.dp).clickable(openApp),
			verticalAlignment = Alignment.Vertical.CenterVertically
		) {
			// 盒宽 = 时间串的实际墨迹宽(见上面 monthBoxWidth 的说明), 月份在其中居中
			Box(modifier = GlanceModifier.width(monthBoxWidth).fillMaxHeight(), contentAlignment = Alignment.Center) {
				Text(
					text = "${c[Calendar.MONTH] + 1}月",
					style = TextStyle(color = style.aux, fontSize = (m.font * 1.15F).sp),
					maxLines = 1
				)
			}
			// 【第八十轮·修"星期和课程没有居中对齐"】月份盒只有时间串墨迹那么宽(≈16dp),
			// 比时间列(≈26dp)窄一截 —— 这一截会被 7 个日期格均摊, 每列比课程格宽 ~1.4dp,
			// 周一的"周一"标签因此比下面的课块中心偏左约 9dp(用户 14:20 截图实测 27px)。
			// 补一个"补齐到时间列宽"的 Spacer, 让 7 个日期格与课程格**严格同宽同位**;
			// 月份盒本身不动, 仍压在时间串的中线上。
			Spacer(
				modifier = GlanceModifier
					.width((m.timeCol - monthBoxWidth).coerceAtLeast(0.dp))
					.fillMaxHeight()
			)
			repeat(Data.WEEK_STRING.size) {
				// 【第六十一轮】"今天"仍然用课程主色高亮(这是**强调**, 不跟着"非课程文字颜色"走),
				// 其余各列按列取色 —— 炫彩模式下就是一条横向彩虹。
			// 【第七十九轮】抽出 isToday 供取色和垫底矩形共用
			val isToday = currentDay[Calendar.DATE] == c[Calendar.DATE] && currentDay[Calendar.MONTH] == c[Calendar.MONTH]
			val color = if(isToday) todayColor else style.aux
				val dateText = if(c[Calendar.DATE] == 1) "${c[Calendar.MONTH] + 1}月" else "${c[Calendar.DATE]}"
				// 不要在这里加 width: 它和 defaultWeight 同时写时 Glance 会按权重重分配列宽,
				// 累积到第 7 天就和下面的课程格错位。
				//
				// 【第三十六轮·减视图】原来是"Column + 两个 Text"(每周 3 个 View, 7 天共 21 个),
				// 现在合成**一个 Text + 一个换行符**(每周 2 个, 共 14 个)。少 7 个 View,
				// 缩放时 RemoteViews 的 inflate 就少铺 7 份; 观感上两行文字本来就紧挨着, 没有区别。
				Box(
					modifier = GlanceModifier.defaultWeight().fillMaxHeight(),
					contentAlignment = Alignment.Center
				) {
					Text(
						text = "${Data.WEEK_STRING[it]}\n$dateText",
						style = TextStyle(color = color, fontSize = m.font.sp, textAlign = TextAlign.Center),
						maxLines = 2
					)
				}
				c[Calendar.DATE] += 1
			}
			// 【第六十三轮】和下面课表的 7 个权重列同口径: 右端也留出 m.inset,
			// 否则日期条的 7 格会比课程列宽一点点, "周日 31" 会偏到课块右边去。
			Spacer(modifier = GlanceModifier.width(m.inset).fillMaxHeight())
		}
	}

	/**
	 * 左侧"节次 + 上课 + 下课"栏 —— 与软件内「学期课表」**同一套排版**。
	 *
	 * 三行的顺序、内容、左边界都与 `LessonTableView.LessonTimeBar` 一致:
	 *  ① 节次数字, 居中压在上课时间的**冒号**上(盒宽 = 2.44em: 冒号中心在 1.218em 处,
	 *     盒宽取 2 倍, 居中后中心正好落在冒号上, 见第五十九轮的推导);
	 *  ② 上课时间(左对齐);
	 *  ③ 下课时间(左对齐)。
	 *
	 * 【第六十轮·修"时间表缺失更多了 / 没竖直对齐"】两处改动:
	 *
	 *  ① **不再按"放不放得下"去砍行**。上一版是 `showEnd = lineBudget >= 3`, 而 lineBudget 用
	 *     的是**课程**字号的整行高度 —— 节数一多(12 节)每格变矮, 整列的"下课时间"会**一起消失**,
	 *     用户看到的就是"时间表缺失更多了"。现在改成**按一格的高度反推字号**:
	 *         font = slotH / 3 / 1.38
	 *     三行永远都在, 只是卡片矮的时候字跟着小 —— 这才是"完全按照学期课表的时间表"。
	 *
	 *  ② 每一行改成 `defaultWeight()`: 高度 = 表格总高 / 节数, 与右侧课程块的 [Metrics.slotH]
	 *     **是同一个像素**(表格总高 = slots × slotH, 见 [Metrics.tableH]),
	 *     所以第 N 行的"上课时间"永远正对第 N 节课的色块上沿。
	 */
	@Composable
	internal fun LessonTimeBar(time: Array<Array<String>>, m: Metrics, style: WidgetStyle, slots: Int) {
		// 【第六十一轮·按要求"禁止浅灰"】节次数字与两个时间**同一个颜色**(默认 #1A1A1A),
		// 层次感改由字号/字重区分 —— 上一版这里是 #1F2124 + #6E7681 两档灰, 在玻璃底上看不清。
		// 【第六十一轮】颜色改成**按行取**(`style.textAt`): 普通颜色时每行都一样,
		// 选中"炫彩"时 11 行铺成一条自上而下的彩虹 —— 见 WidgetStyle.textAt 的说明。
		// 一行文字实际占 1.42 个字号(含 includeFontPadding, 实测约 1.32, 这里多留 7% 余量),
		// 三行就是 4.26 个字号。取 min(课程字号 × 0.94, 一格高度 / 4.26):
		// 卡高时跟着课程字走, 卡矮时自动缩到"三行一定塞得下", 不会再被裁。
		// 【第六十轮·再修"时间和学期课表对不上"】
		// 上一版还套了个 `课程字号 × 0.94` 的上限, 于是三行只占了格子中间一半的高度、
		// 上下各空一大截 —— 视觉上"08:00"落在两节课中间, 看着就和一个节次的课块对不上。
		// 现在**只用格高反推**, 三行正好填满一格(上限不超过课程字号, 免得时间比课名还大)。
		// 【第六十一轮】下限从 3.2sp 降到 2.4sp。
		// 3.2sp × 3 行 × 1.42 ≈ 13.6dp —— 格子比这矮时(11 节 + 矮卡), 三行**塞不进一格**,
		// 多出来的部分被行高裁掉, 表现就是"最后一行的下课时间不见了"。宁可字小一点,
		// 也要保证"节次 / 上课 / 下课"三行**都在**格子里(用户要求: 时间表要和学期课表完全一致)。
		val timeFont = minOf(m.slotH.value / (3F * 1.42F), m.font).coerceAtLeast(2.4F)
		val start = time.getOrNull(0)
		val end = time.getOrNull(1)
		val context = LocalContext.current

		// 【第七十轮·十四·重写「第 11 行永远画不出来」】
		//
		// 空白课表小组件(同一套几何、一门课都不画)里**同样只有 10 行**, 而卡片底部的自检行
		// "卡495 采485 底464 11节" 是可见的 —— 说明卡片真实高度(实测 482dp)大于表底(464dp),
		// 第 11 行(428~464dp)本来就该落在卡片**里面**。按像素量: 节次数字的行距精确是 36.0dp,
		// 第 10 行在 408.5dp, 第 11 行该在 444.5dp —— 那里是空的, 空出来的正好也是一整行(36dp)。
		// 也就是说: **布局没错、高度没错、数据没错(slots=11 就印在卡片上), 但第 11 个行视图
		// 根本没进到 RemoteViews 里**。前面试过的 fillMaxHeight / 权重 / 高度折扣全都不对症。
		//
		// 与其继续猜"为什么最后一个子视图会消失", 不如把它**从"11 个子视图"变成"1 个子视图"**:
		// 整条时间栏一次性画进一张 ALPHA_8 位图(94×1089px, 约 100KB), 由一个 ImageView 承载。
		//   · 11 行是 Canvas 画的, 不存在"少一个子视图"的可能;
		//   · 位置永远和 `slotH` 对齐(每行 i×slotH 像素), 与右侧课程块的网格是同一个数;
		//   · 视图数从 44 个(11 行×(1 容器+3 文字))降到 1 个, 顺带把每次刷新的开销也砍掉。
		// 颜色不画进位图: 位图只留透明度, 由 `ColorFilter.tint` 上色 —— 这样换主题/换配色
		// 只要重涂一次, 不用重画位图(也避免 ARGB_8888 那 400KB 撑爆 RemoteViews)。
		val barBitmap = remember(m.timeCol.value, m.tableH.value, slots, timeFont, start, end) {
			renderTimeBar(context, m, slots, timeFont, start, end)
		}
		Image(
			provider = ImageProvider(barBitmap),
			contentDescription = null,
			// 【第一百一十三轮·v240】表格改权重自适应后, 这里也改 fillMaxHeight:
			// 位图按估算高度绘制, 显示时 FillBounds **拉伸到物理表格高** —— 启动器
			// 重新测量后时间栏自动贴合任何布局, 11 行行距与右侧课程格天然对齐
			// (两者都是"表格高 ÷ 节数")。拉伸比例实测 1.00~1.13(428→483dp), 观感无碍。
			modifier = GlanceModifier.width(m.timeCol).fillMaxHeight(),
			contentScale = ContentScale.FillBounds,
			colorFilter = androidx.glance.ColorFilter.tint(style.aux)
		)
	}

	/**
	 * 把整条时间栏画进一张位图(为什么不用 11 个子视图, 见 [LessonTimeBar] 顶部那段说明)。
	 *
	 * 关键点:
	 *  · **ALPHA_8**: 只存透明度, 94×1089px 约 100KB; 颜色交给 `ColorFilter.tint` 上色,
	 *    换主题只需重涂, 不用重画; 若用 ARGB_8888 这一栏要 400KB, 会把 RemoteViews 的
	 *    1MB 预算吃掉一大半(见 WidgetGlass 里 TransactionTooLargeException 的教训)。
	 *  · 尺寸严格等于 `timeCol × tableH` 的**像素**大小, ImageView 以 FillBounds 1:1 贴上,
	 *    所以文字位置和原来的 TextView 版本是同一套坐标, 不会变虚也不会位移。
	 *  · 每行的三行文字整体在一格里垂直居中, 行距 ×3 ≤ 一格(由 timeFont 的推导保证)。
	 */
	private fun renderTimeBar(
		context: Context,
		m: Metrics,
		slots: Int,
		timeFontSp: Float,
		start: Array<String>?,
		end: Array<String>?
	): Bitmap {
		val dm = context.resources.displayMetrics
		val density = dm.density.coerceAtLeast(0.1F)
		val scaled = dm.scaledDensity.coerceAtLeast(0.1F)
		val width = ceil(m.timeCol.value * density).toInt().coerceAtLeast(1)
		val height = ceil(m.tableH.value * density).toInt().coerceAtLeast(1)
		val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
		val canvas = Canvas(bitmap)
		val timePx0 = timeFontSp * scaled
		// 【第七十轮·十八·修"5*9 布局时间显示不全"】
		//
		// 位图宽度就是 `timeCol`(dp→px), 而字宽是 sp→px —— 系统字体放大后
		// "08:00" 会**比这一列还宽**, 超出的部分直接被位图裁掉(截图里 "08:4" 断在右边)。
		// 这里先量一遍: 放不下就整栏等比缩到刚好放下, 宁可字小一点, 不能少半个字。
		val probe = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			typeface = Typeface.DEFAULT
			textSize = timePx0
		}
		val needW = probe.measureText("08:00")
		val fitRatio = if(needW > width * 0.98F) width * 0.98F / needW else 1F
		val timePx = timePx0 * fitRatio
		val numberPx = timePx * 1.12F
		val lineH = timePx * 1.30F
		// 节次数字的盒子: 与软件内同一套算法 —— 冒号中心 ≈ 1.218em, 盒宽取 2 倍(2.44em),
		// 数字在这个盒子里居中, 中心正好压在上课时间的冒号上。
		val numberWidth = (numberPx * 2.44F).coerceAtMost(width * 0.92F)
		val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
			color = android.graphics.Color.BLACK
			typeface = Typeface.DEFAULT
			textSize = timePx
			textAlign = Paint.Align.LEFT
		}
		val numberPaint = Paint(textPaint).apply {
			textSize = numberPx
			typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
			textAlign = Paint.Align.CENTER
		}
		val fm = textPaint.fontMetrics
		val slotPx = m.slotH.value * density
		val blockH = lineH * 3F
		// 一行文字在 lineH 这个行盒里居中时的基线偏移(与 TextView 的排版一致)
		val baselineInLine = (lineH - (fm.descent - fm.ascent)) / 2F - fm.ascent
		for(i in 0 until slots) {
			val top = i * slotPx
			val lineTop = top + (slotPx - blockH) / 2F
			val firstBaseline = lineTop + baselineInLine
			// 【第七十七轮·按要求"节次号 1-11 没和冒号竖直对齐, 有点靠右"】
			// 上一版把节次数字画在 `numberWidth / 2`(2.44em 的盒中心 ≈ 1.37em),
			// 而 "08:00" 里的冒号在 ≈1.22em —— 数字整体偏右 0.15em。
			// 现在直接量这一行时间串的冒号位置, 让数字的**中心**压在冒号上(逐行量, 不靠常数猜)。
			val startText = start?.getOrNull(i).orEmpty()
			val colon = startText.indexOf(':')
			val numberCx = if(colon > 0) {
				textPaint.measureText(startText, 0, colon) + textPaint.measureText(":") / 2F
			}else{
				numberWidth / 2F
			}
			canvas.drawText((i + 1).toString(), numberCx, firstBaseline, numberPaint)
			canvas.drawText(startText, 0F, firstBaseline + lineH, textPaint)
			canvas.drawText(end?.getOrNull(i).orEmpty(), 0F, firstBaseline + lineH * 2F, textPaint)
		}
		return bitmap
	}

}


/**
 * 【第六十八轮】某一格文字在**某个字号**下的可行性 —— `fitBlockText` 的返回值。
 *
 *  · [font]: 最终要用的字号(sp);
 *  · [nameLines]: 课名要折几行;
 *  · [showPlace] / [showTeacher]: 这一档字号下地点/教师放不放得下;
 *  · [nameFits] / [placeOk] / [teacherOk]: 三轮退让的判据(字段本身为空时恒为 true)。
 */
private class BlockFit(
	val font: Float,
	val nameLines: Int,
	val showPlace: Boolean,
	val showTeacher: Boolean,
	val nameFits: Boolean,
	val placeOk: Boolean,
	val teacherOk: Boolean
)

/**
 * 一门课在小组件里要用的字段(只挑展示需要的, 不持有 `Lesson` 本体)。
 *
 * 【第三十六轮】从 `UpdateActionCallback` 里搬出来成了文件级的类 —— 那个 ActionCallback
 * 是给"翻周箭头"用的, 本轮删掉翻周功能后它就没了存在的理由。
 */
private class LessonData(
	val name: String,
	val place: String,
	val teacher: String,
	val len: Int,
	val color: Int,
){
	constructor(lesson: Lesson) : this(
		name = lesson.name,
		place = lesson.place,
		teacher = lesson.teacher,
		len = lesson.len,
		color = lesson.color
	)
}

/**
 * 同一个"星期几 + 第几节"上叠着的多门课(不同周次上不同的课)。
 *
 * 构造时就把"每一周上第几门"算成三个定长数组, `current(week)` 是 O(1) 查表。
 * 所以 [TermLesson.buildWeekData] 会把结果缓存起来 —— 这个构造并不便宜。
 */
private class LessonHolder(lessonGroup: LessonGroup, totalWeek: Int) {

	/**
	 * 某一周的这个时间点同时有几节课
	 */
	var lessonCount = IntArray(totalWeek)

	/**
	 * 当前正在展示的是第几节课，从0开始
	 */
	var index = IntArray(totalWeek).also { Arrays.fill(it, -1) }

	/**
	 * 某一周的这个时间点同时有课的课
	 * 5 = 101，表示当第1，3课会上，第2课不会上
	 */
	private var lessonTime = IntArray(totalWeek)

	private var lessonData: Array<LessonData>

	init {
		var offset = 1
		val array = ArrayList<LessonData>(lessonGroup.lessons.size)
		lessonGroup.lessons.forEachIndexed { i, lesson ->
			array.add(LessonData(lesson))
			var week = 1L
			for(j in 0 until totalWeek) {
				if(lesson.week and week > 0) {
					if(index[j] == -1) index[j] = i
					lessonTime[j] = lessonTime[j] or offset
					lessonCount[j]++
				}
				week = week shl 1
			}
			offset = offset shl 1
		}
		lessonData = array.toTypedArray()
	}

	fun current(week: Int): LessonData? {
		// 越界保护: 课表可能被重新导入成更短的学期
		if(week !in lessonCount.indices) return null
		return if(lessonCount[week] == 0) null else lessonData.getOrNull(index[week])
	}
}
