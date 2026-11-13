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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.qust.helper.R
import com.qust.helper.data.Data
import com.qust.helper.data.lesson.LessonTable
import com.qust.helper.data.lesson.TimeTables
import com.qust.helper.model.LessonTableRepository
import com.qust.helper.ui.theme.LESSON_TEXT_COLORS
import com.qust.helper.ui.theme.TEXT_ACTION_FILLED_BLUE
import com.qust.helper.ui.theme.TEXT_ACTION_FILLED_GRAY
import com.qust.helper.ui.theme.TEXT_ACTION_H_PADDING
import com.qust.helper.ui.theme.TEXT_ACTION_V_PADDING
import com.qust.helper.ui.theme.tapRipple
import com.qust.helper.ui.theme.textAction
import com.qust.helper.ui.theme.tnum
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import com.qust.helper.ui.widget.Texts.SingleLineText
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Date

object LessonTableView{

	/** 判定"确实拖动过"的最小位移(dp): 长按时的细微抖动不算拖动, 避免长按没反应 */
	private const val DRAG_SLOP_DP = 12f

	/** "再点一次进入编辑"的判定间隔(毫秒) */
	private const val DOUBLE_TAP_TIMEOUT_MS = 400L

	/**
	 * 长按拖动课程的判定时间(毫秒)。
	 * 系统默认约 400~500ms, 拖一节课要等一下才动, 手感偏迟钝; 这里缩短到 220ms,
	 * 按住稍微久一点就能开始拖。只作用于"长按拖动", 不影响单击/双击的判定。
	 */
	private const val DRAG_LONG_PRESS_MS = 220L

	/**
	 * 课表查询模式(longMode)下每一节的固定高度(dp)。
	 * 课程方块保持**尽量短**(只够显示节次与时间), 整张课表按"短单元格 × 节数"绘制、
	 * 由外层纵向滚动查看 —— 表被拉长能展示全部节次时间, 但单个课程方块不被拉高。
	 */
	private const val CELL_HEIGHT_DP = 52f

	/**
	 * 左侧时间栏每一行文字的**固定宽度**。
	 *
	 * 【第五十六轮·修"学期课表整页空白"】上一轮为了让"节次/上课/下课"三行左边缘对齐,
	 * 给三个 Text 都加了 `fillMaxWidth()` —— 后果是灾难性的: 时间栏是在
	 * `SubcomposeLayout` 里**先量**的(用整页的 constraints), `fillMaxWidth` 于是撑到整屏宽,
	 * 时间栏量出来的宽度 = 屏幕宽度, 剩下的课表区宽度变成 0, 课表(连同星期日期条)整块消失,
	 * 只剩左边一列时间孤零零地立着。
	 *
	 * 对齐的正确做法是给同一个**固定宽度**(而不是"占满父宽"), 三行自然落在同一条竖线上,
	 * 时间栏的宽度也就是这个固定值 + 内边距, 不会再抢课表的地盘。
	 * 44dp 能放得下 bodySmall(12sp) 的 "08:00"(系统字号放大到 1.3 倍也不裁字),
	 * 又不至于把 7 个课程列压得太窄。
	 */
	private val TIME_BAR_TEXT_WIDTH = 44.dp

/**
 * 【第七十轮·十六】时间串("08:00")在**当前字号**下的实际宽度。
 *
 * 由 [LessonTimeBar] 量出来后写回, 供日期条的"11月"对齐用。
 * 为什么需要它: 日期条的月份盒子宽度原来是写死的 [TIME_BAR_TEXT_WIDTH](44dp), 而时间串
 * 实际只有 26~30dp —— 盒子比字宽, 居中之后月份就偏右。全屏时差得不多, 悬浮窗里
 * [LessonTimeBar] 会按行高把字号缩到 0.5~1 倍, 时间串更窄, 偏得就更明显
 * (用户 16:08 的悬浮窗截图: "11月"中心比 "08:00" 中心右了约 19px)。
 *
 * 默认值取 44dp = 还没量到时的旧行为, 不会比现在更差。
 */
private var timeTextWidth by mutableStateOf(TIME_BAR_TEXT_WIDTH)

/**
 * 【第七十九轮·按用户要求"课程宽度可以变宽一点, 只要不压到时间表就行"】
 * 时间盒的自适应宽度 = 时间串实测墨迹 + 2dp 呼吸边。
 *
 * 旧的固定 44dp 盒比 "08:00" 的实际墨迹(26~30dp)宽出一截, 多出来的全是白占课程列的宽度;
 * 改成按实测收窄后, 时间栏整条让出约 12dp, 7 个课程列每列多 ~1.7dp,
 * "习概"这类长课名 + 教室在 1.0 字号下更容易完整显示。
 * 时间串是 tnum 定宽字体, 量 "08:00" 一个串即代表全部; 字号缩放时(悬浮窗/小窗)
 * 墨迹跟着缩, 盒宽也跟着缩。
 * 默认值仍取 44dp = 还没量到时的旧行为, 不会比现在更差。
 */
private var timeBoxWidth by mutableStateOf(TIME_BAR_TEXT_WIDTH)

	/**
	 * 节次数字那一行的宽度。
	 *
	 * 【第五十七轮·按要求】"1 2 3 … 11 这 11 个数字的中心应当与「：」竖直对齐, 现在太靠左了":
	 * 时间是 "08:00" 这种定宽串, 冒号大致落在第 2.2 个字符处 —— 12sp 字号下约 15.5dp。
	 * 把数字放在一个宽 [TIME_BAR_NUMBER_WIDTH](= 2 × 16.5dp) 的盒子里**居中**,
	 * 数字中心正好压在那条竖线上, 一眼看过去节次和时间是同一列。
	 */
	private val TIME_BAR_NUMBER_WIDTH = 33.dp

	/**
	 * 课程重合提示状态: 某一时刻被多门课程同时占用
	 */
	private data class Coincide(
		val week: Int,
		val day: Int,
		val occupants: List<LessonTable.Occupant>
	)

	/**
	 * 缩短"长按"判定时间的 ViewConfiguration
	 *
	 * [detectDragGesturesAfterLongPress] 内部取 `ViewConfiguration.longPressTimeoutMillis`
	 * (系统默认 400~500ms) 作为长按阈值, 拖动课程偏迟钝。这里把该值换成更小的
	 * [DRAG_LONG_PRESS_MS], 其余(touchSlop / 双击间隔 / 最小触摸目标等)全部沿用系统配置,
	 * 因此单击选框、双击进编辑、左右翻页的手感都不受影响。
	 *
	 * 用 `by base` 委托实现: 新版 Compose 给该接口加成员时也不会编译失败。
	 */
	@OptIn(ExperimentalFoundationApi::class)
	@Composable
	private fun ShortPressViewConfiguration(content: @Composable () -> Unit) {
		val base = LocalViewConfiguration.current
		val fast = remember(base) {
			object : ViewConfiguration by base {
				override val longPressTimeoutMillis: Long = DRAG_LONG_PRESS_MS
			}
		}
		CompositionLocalProvider(LocalViewConfiguration provides fast) {
			content()
		}
	}

	@Composable
	@OptIn(ExperimentalFoundationApi::class)
	fun LessonView(
		lessonRender: LessonRender,
		onLessonClick: (LessonRender.SelectLesson) -> Unit = {},
		onDayClick: (Int, Int) -> Unit = { _, _ -> },
		/**
		 * 选择模式: 单击课程立即回调 onLessonClick(用于"从课表选择课程")
		 */
		pickMode: Boolean = false,
		/**
		 * true = 课表查询模式(行更高、可纵向滚动); false = 学期课表(原版, 铺满一屏不滚动)
		 */
		longMode: Boolean = false,
		/** 【第四十九轮】纯预览模式(课表查询页): 禁用一切内部手势与左右翻周,
		 *  只由外层"整页上下滚动"承载滚动 —— 避免左右滑后上下滑失效/界面抖动 */
		previewMode: Boolean = false,
		/**
		 * 课表查询模式(longMode)底部留白高度(dp): 0 表示不加, 一般取一节课的高度, 用于滚动时底部留白
		 */
		bottomBlankDp: Float = 0f,
		/**
		 * 【第六十四轮】左右翻周的触发门槛(0..1): 值越大越难翻周 —— 需要拖得更远才切到上一/下一周,
		 * 避免上下滚动课表时手一歪就误翻周。课表查询页用 0.8; 学期课表保持默认 0.5(不改)。
		 */
		weekSwipeThreshold: Float = 0.5f,
		/**
		 * 【第六十五轮】严格横向翻周(仅课表查询页): 内建翻周手势关闭, 改由自定义手势驱动 ——
		 * 只有"明显横向"的拖动才翻周, 纵向拖动完全不碰翻周, 消除"翻周后上下滑动左右抖动/难以上下"。
		 */
		strictWeekSwipe: Boolean = false,
		/**
		 * 【本期】是否启用"周次切换": 点顶部的「第 N 周」可选任意周次, 右侧并显示「回到本周」。
		 * 只在学期课表打开(课表查询预览、从课表选择课程等场景保持原样)。
		 */
		weekPickerEnabled: Boolean = false
	){
		val density = LocalDensity.current
		val context = androidx.compose.ui.platform.LocalContext.current
		// 【防崩溃】翻页起始周必须夹在合法范围内: 查询到新学期后总周数可能变小,
		// 而"当周"是按开学时间算出来的, 很容易超出新总周数 -> 翻页/绘制下标越界直接退出应用
		val pagerState = rememberPagerState(
			initialPage = (LessonTableRepository.currentWeek.intValue - 1)
				.coerceIn(0, (lessonRender.totalWeek - 1).coerceAtLeast(0)),
			pageCount = { lessonRender.totalWeek }
		)
		var selectLesson by remember { mutableStateOf(LessonRender.SelectLesson(-1, -1, null)) }

		/**
		 * 【第三十轮】被单击展开的课程: 课名太长、格子里放不下时, 单击就把它画高一点显示全部内容;
		 * 点其它位置(空白/别的课)自动收回。只影响绘制, 不改任何数据。
		 * 用 `remember(page)` 记住 —— 翻到别的周时自然失效。
		 */
		var expandTarget by remember { mutableStateOf<LessonRender.ExpandTarget?>(null) }

		// 【第三十二轮·修"左右切换周后点击变长失效"】以前是 `remember(pagerState.currentPage)`,
		// 只有在"当前页"恰好变化并触发重组合时才会被重置 —— 翻周动画/回弹期间 currentPage 来回变,
		// 这个状态可能被重置成"上一次的页号", 之后点击就永远对不上当前页(条件 `expanded.week == page` 不成立),
		// 看起来就是"这个功能翻过周就坏了"。现在改成: 状态只创建一次, 由下面这个 effect 明确在"换页后"清空。
		LaunchedEffect(pagerState.currentPage) { expandTarget = null }

		/** 上一次点击的时间/位置(用于"再点一次进入编辑"的双击判定) */
		var lastTapTime by remember { mutableStateOf(0L) }
		var lastTapDay by remember { mutableIntStateOf(-1) }

		/** "怎么进编辑"的提示只弹一次 */
		var hintedAdd by remember { mutableStateOf(false) }

		/** 把手拖动开始时的选框(用于判断是否真的改变了大小 -> 松手是否进编辑) */
		var resizeStartTop by remember { mutableIntStateOf(-1) }
		var resizeStartLen by remember { mutableIntStateOf(0) }

		/** 拖动判定的位移阈值(像素) */
		val dragSlop = with(density){ DRAG_SLOP_DP.dp.toPx() }
		/** 点击空白课表时默认的选择框大小: 按大多数课程的时间跨度(至少2格) */
		val defaultSelectLen = remember(LessonTableRepository.lessonTableVersion){
			LessonTableRepository.lessonTable.majorityLessonLength()
		}

		/** 课程重合提示: 该时刻被多门课程同时占用 */
		var coincide by remember { mutableStateOf<Coincide?>(null) }

		LaunchedEffect(LessonTableRepository.lessonTableVersion){
			lessonRender.updateLessonTable()
		}

		// 【第五十五轮·修"开关一次隐藏教师后, 点击课程变长对部分课程失效"】
		// 渲染数据(LessonRenderData)里存着**构建时**的 hideTeacher 快照: 教师那一行有没有,
		// 直接决定"这门课一共要画几行"。开关一变, 快照还是旧的, 于是"展开时算出来的行数/行内容"
		// 和实际绘制对不上 —— 有的课点了没反应(有的又正常, 全看那门课有没有教师名)。
		// 这里订阅开关本身, 一变就按新值重建渲染数据。
		val hideTeacherNow = LessonTableRepository.hideTeacher
		LaunchedEffect(hideTeacherNow){
			lessonRender.updateLessonTable()
		}

		// 【刚进入软件课表空白】冷启动时测量与数据时序不稳定: 等首帧布局测量完成后再
		// 强制重算一次渲染数据, 避免"要来回切换界面才显示"
		LaunchedEffect(lessonRender){
			withFrameNanos { }
			lessonRender.updateLessonTable()
		}

		// 【学期课表显示当周】进入课表页(以及当前周/总周数变化后)都定位到"当周",
		// 确保默认显示的就是这一周的课表, 而不是停在上次翻到的某一周
		LaunchedEffect(LessonTableRepository.currentWeek.intValue, lessonRender.totalWeek){
			val target = (LessonTableRepository.currentWeek.intValue - 1)
				.coerceIn(0, (lessonRender.totalWeek - 1).coerceAtLeast(0))
			if(pagerState.currentPage != target) pagerState.scrollToPage(target)
		}

		/** 拖动结果提示: 让每次拖动都有明确反馈, 不再出现"拖了没反应" */
		fun tip(message: String){
			try {
				val act = context as? android.app.Activity
				if(act != null){
					// 【第四十六轮】改用应用内 Toast(半透明灰, 与整体提示风格一致), 不再是系统黑色气泡
					Toast.of(act).toastWarning(message)
				}else{
					android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
				}
			} catch(_: Exception) { }
		}

		Column {
			// 【第三十一轮】"第N周"标题这一条(课表上方的空白区域)也支持横向滑动翻周:
			// 以前在这块区域右滑只会触发侧边栏、课表不动。这里接管横向拖动直接驱动 Pager,
			// 松手后吸附到最近的一周(与在课表上左右滑动完全一致)。
			val swipeScope = rememberCoroutineScope()
			/** 【本期】点「第 N 周」弹出的周次选择框 */
			var showWeekPicker by remember { mutableStateOf(false) }
			/** 当前周(1 起算, 已夹到合法范围): 「回到本周」的目标 */
			val currentWeekIndex = (LessonTableRepository.currentWeek.intValue - 1)
				.coerceIn(0, (lessonRender.totalWeek - 1).coerceAtLeast(0))
			// 【本期】标题行用 Box 叠放: 「第 N 周」始终**水平居中**且竖直位置不变(高度由它自己撑开),
			// 「回到本周」用普通 Text 叠在右端 —— 换成 TextButton 会把行高撑到 48dp, 标题就会往下移。
			Box(modifier = Modifier.fillMaxWidth()) {
				// 【第九十四轮·按要求"点击'第10周'的反馈动画和按钮实体都缩小到'第10周'周围"】
				// 整行的横向拖动翻周移到独立的**背景层**(matchParentSize, 不承担点击) ——
				// 以前"拖动 + 点击"都挂在 fillMaxWidth 的标题上, textAction 的药丸波纹和
				// 可点热区因此横贯整行, 看着就是一个超大的按钮。现在:
				//   · 横滑翻周: 整行仍可用(背景层);
				//   · 点"第 N 周 ▾"弹选择框: 热区/波纹只包文字 + 自带 8dp 留白。
				Box(
					modifier = Modifier
						.matchParentSize()
						.pointerInput(pagerState) {
							detectHorizontalDragGestures(
								onHorizontalDrag = { change, dragAmount ->
									change.consume()
									pagerState.dispatchRawDelta(-dragAmount)
								},
								onDragEnd = {
									swipeScope.launch { pagerState.animateScrollToPage(pagerState.currentPage) }
								},
								onDragCancel = {
									swipeScope.launch { pagerState.animateScrollToPage(pagerState.currentPage) }
								}
							)
						}
				)
				SingleLineText(
					text = stringResource(id = R.string.text_week, pagerState.currentPage + 1) +
						if(weekPickerEnabled) " ▾" else "",
					modifier = Modifier
						.align(Alignment.Center)
						// 点标题 -> 选择周次(拖动由背景层处理, 超过阈值的滑动会取消点击)
						.then(
							// 【第二十九轮】标题本身是文字按钮, 用统一的 textAction(主题色药丸波纹)
							if(weekPickerEnabled) Modifier.textAction { showWeekPicker = true } else Modifier
						),
					style = MaterialTheme.typography.titleLarge
				)
				// 【本期】快速回到"本周课表": 已经在当周时置灰不可点
				if(weekPickerEnabled){
					// 【第五十三轮·按要求】「回到本周」与「复制课程」等按钮同一套新样式:
					// 蓝色填充 + 白字, 取消灰描边。
					// 【第五十五轮·按要求】"回到本周按钮变成灰色时也按照灰色按钮样式改":
					// 已经在本周(不可用)时不再只把字调暗, 而是换成**灰色实心填充 + 白字**,
					// 与「单独编辑此次课程」「多选编辑」不可用时的样式完全一致。
					val atCurrentWeek = pagerState.currentPage == currentWeekIndex
					Text(
						text = "回到本周",
						modifier = Modifier
							.align(Alignment.CenterEnd)
							.clip(CircleShape)
							.background(if(atCurrentWeek) TEXT_ACTION_FILLED_GRAY else TEXT_ACTION_FILLED_BLUE)
							.textAction(enabled = !atCurrentWeek) {
								swipeScope.launch { pagerState.animateScrollToPage(currentWeekIndex) }
							}
							.padding(horizontal = TEXT_ACTION_H_PADDING, vertical = TEXT_ACTION_V_PADDING),
						style = MaterialTheme.typography.labelLarge,
						color = androidx.compose.ui.graphics.Color.White
					)
				}
			}
			if(weekPickerEnabled && showWeekPicker){
				AlertDialog(
					onDismissRequest = { showWeekPicker = false },
					title = { Text(text = "选择周次") },
					text = {
						Column(
							modifier = Modifier
								.heightIn(max = 380.dp)
								.verticalScroll(rememberScrollState())
						) {
							for(w in 1..lessonRender.totalWeek.coerceAtLeast(1)){
								val isShowing = w == pagerState.currentPage + 1
								val isThisWeek = w == currentWeekIndex + 1
								Text(
									text = "第 $w 周" + if(isThisWeek) "（本周）" else "",
									modifier = Modifier
										.fillMaxWidth()
										.tapRipple {
											showWeekPicker = false
											swipeScope.launch {
												pagerState.animateScrollToPage(
													(w - 1).coerceIn(0, (lessonRender.totalWeek - 1).coerceAtLeast(0))
												)
											}
										}
										.padding(vertical = 12.dp, horizontal = 8.dp),
									style = MaterialTheme.typography.bodyLarge,
									color = if(isShowing) MaterialTheme.colorScheme.primary
										else MaterialTheme.colorScheme.onSurface
								)
							}
						}
					},
					confirmButton = {
						TextButton(onClick = { showWeekPicker = false }) { Text(text = "取消") }
					}
				)
			}
			HorizontalPager(
				state = pagerState,
				// 【第四十九轮】左右翻周**保持可用**(用户要求不能禁用); 该参数仅供特殊场景,
				// 默认 true, 不改动
				modifier = Modifier
					.fillMaxSize()
					.then(
						if(strictWeekSwipe) Modifier.pointerInput(pagerState, weekSwipeThreshold){
							// 【第六十五轮】查询页专用翻周手势: 只有"明显横向"的拖动才翻周;
							// 纵向拖动一概不碰 Pager(交给外层整页上下滚动), 消除翻周后上下滑的左右抖动。
							val slop = viewConfiguration.touchSlop
							awaitEachGesture {
								val down = awaitFirstDown(requireUnconsumed = false)
								var dx = 0f
								var dy = 0f
								var horizontal = false
								while(true){
									val event = awaitPointerEvent()
									val change = event.changes.firstOrNull { it.id == down.id } ?: break
									if(!change.pressed) break
									val delta = change.positionChange()
									if(!horizontal){
										dx += delta.x
										dy += delta.y
										if(kotlin.math.abs(dx) > slop * 2f && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.5f){
											horizontal = true
										}else if(kotlin.math.abs(dy) > slop * 1.5f){
											break   // 判为纵向: 让给外层滚动, 不消费任何事件
										}
									}
									if(horizontal){
										change.consume()
										pagerState.dispatchRawDelta(-delta.x)
									}
								}
								if(horizontal){
									val frac = pagerState.currentPageOffsetFraction
									val target = when {
										frac > weekSwipeThreshold -> pagerState.currentPage + 1
										frac < -weekSwipeThreshold -> pagerState.currentPage - 1
										else -> pagerState.currentPage
									}
									// awaitEachGesture 是受限挂起域, 不能直接调挂起函数 -> 放到页面自己的协程里
									swipeScope.launch { pagerState.animateScrollToPage(target.coerceIn(0, pagerState.pageCount - 1)) }
								}
							}
						}else Modifier
					),
				// 严格模式下关闭内建翻周手势(由上面的自定义手势驱动)
				userScrollEnabled = !strictWeekSwipe,
				// 【第六十四轮】翻周触发门槛: 只有横向拖动超过页宽的一定比例(课表查询页 0.8)才切换周,
				// 防止上下滚动时的轻微横向抖动误触发左右翻周。
				flingBehavior = androidx.compose.foundation.pager.PagerDefaults.flingBehavior(
					state = pagerState,
					snapPositionalThreshold = weekSwipeThreshold
				)
) { page ->
		// 课表查询模式(longMode): 整张课表按固定**短**行高绘制, 高度超过可视区域时
		// 纵向滚动(课程方块尽量短、整表变长, 全部节次时间都能看到);
		// 学期课表(默认)保持原版, 铺满一屏、不滚动。
		// 【长按拖动跟手】整页用"短长按"配置: 长按阈值降到 220ms, 拖课程不用等太久。
			ShortPressViewConfiguration {
				Column(modifier = if(longMode) Modifier.fillMaxSize().verticalScroll(rememberScrollState())
					else Modifier.fillMaxSize()) {
				// 【编辑保存后课表不刷新】用课表数据版本号当 key: 保存/编辑之后强制重建画布,
				// 否则顶部周数栏下面的课表还停在旧内容, 要手动翻一下页才更新
				val tableVersion = LessonTableRepository.lessonTableVersion
				Tab(
					timeBar = {
						LessonTimeBar()
					},
					dateBar = {
						// 课表查询页(previewMode)点星期日期头不做任何事, 也不画涟漪
						LessonDate(lessonRender.weekStartDate(page), page, onDayClick, dayClickEnabled = !previewMode)
					},
					longMode = longMode,
					bottomBlankDp = bottomBlankDp
				) {
				key(tableVersion, page) {
					Canvas(modifier = Modifier.fillMaxSize().onGloballyPositioned { coordinates ->
						lessonRender.setMeasureData(coordinates.size.width, coordinates.size.height, density)
					}.pointerInput(if(pickMode) "pick$page" else page) {
						detectTapGestures(
							onTap = { offset ->
								// 【第四十九轮】纯预览模式不响应点击
								if(previewMode) return@detectTapGestures
								val x = offset.x.toInt()
								val y = offset.y.toInt()
								val select = lessonRender.getClickLesson(page, x, y)
								if(pickMode){
									// 选择模式: 单击有课格子立即回调
									if(select.lesson != null) onLessonClick(select)
									return@detectTapGestures
								}

								// 【第三十轮·按要求】单击课程 -> 把这门课临时画高一点, 显示完整课名/教室/教师;
								// 点到空白或别的课 -> 上一个展开的自动收回。放在下面那些分支之前,
								// 所以"点在蓝框里"这种早退分支也不会漏掉展开。
								expandTarget = if(select.dayOfWeek >= 0 && select.timeSlot >= 0 && select.lesson != null){
									LessonRender.ExpandTarget(page, select.dayOfWeek, select.timeSlot)
								}else{
									null
								}
							if(select.dayOfWeek == -1 || select.timeSlot == -1){
								coincide = null
								selectLesson = select
								return@detectTapGestures
							}

						// ---------- 双击判定(用于"再点一次进入编辑") ----------
								val now = android.os.SystemClock.uptimeMillis()
								// 【双击有时进不了编辑】判定放宽: 同一天、同一"锚点"即可 ——
								// 框内两次点击都记录蓝框的起始格(而不是各自点的格), 点同一门课
								// 的不同行也记录课程起始格, 这样只要还在同一个框/课上就算双击
								val isDoubleTap = now - lastTapTime <= DOUBLE_TAP_TIMEOUT_MS && lastTapDay == select.dayOfWeek
								lastTapTime = now
								lastTapDay = select.dayOfWeek

								// 【单击框内不要再乱动】点在**当前蓝框覆盖范围内**时:
								// 单击 -> 选框完全保持不动(不会下移、也不会误进编辑);
								// 双击 -> 才进入编辑(与"长按进入编辑"一致)
								val cur = selectLesson
								val curLen = cur.len.coerceAtLeast(1)
								val inside = cur.week == page && cur.dayOfWeek == select.dayOfWeek &&
									cur.timeSlot >= 0 && select.timeSlot >= cur.timeSlot &&
									select.timeSlot < cur.timeSlot + curLen
								if(inside){
									// 【粘贴后第一次双击读不到课程】蓝框是"粘贴之前"选中的那一格, 里面存着
									// "这格还是空白"的老快照(lesson == null)。粘贴把课程写进去后渲染数据已经更新,
									// 但蓝框没有人通知 —— 直接把它交给编辑界面, 就是一张空表单(教室/教师为空、
									// 周次全不选), 必须先点一下别的位置让蓝框重新取到课程才正常。
									// 这里以本次点击**实际命中的课程**为准刷新蓝框: 命中到课程就用它;
									// 真正点在空白处(命中不到课程)时仍沿用原框, 保留"双击空白框新建课程"的行为。
									var target = cur
									if(cur.lesson == null && select.lesson != null){
										target = select
										selectLesson = select
									}
									if(isDoubleTap){
										lastTapTime = 0L
										if(!lessonRender.lockLesson) onLessonClick(target)
									}
									return@detectTapGestures
								}

								// 找出覆盖该时刻的全部课程: 数量>1 说明有课程时间重合, 需要提示用户
								val occupants = lessonRender.occupantsAt(page, select.dayOfWeek, lessonRender.rawSlotAt(y))
								if(occupants.size > 1){
									coincide = Coincide(page, select.dayOfWeek, occupants)
									// 选框按各课程实际占用的节数绘制(重合时即"多个框")
									selectLesson = select.copy(len = select.lesson?.len?.coerceAtLeast(1) ?: 1)
									return@detectTapGestures
								}

								// 点在框外: 把选框移到这里。有课程时选框与课程实际节数一致;
								// 空白处默认取大多数课程的时间跨度 ——
								// 【点最底下只显示半个框】靠近课表底部时按剩余节数收缩, 最少 1 格
								val newLen = (select.lesson?.len?.coerceAtLeast(1) ?: defaultSelectLen)
									.coerceAtMost((TimeTables.currentSlotCount - select.timeSlot).coerceAtLeast(1))
									.coerceAtLeast(1)
								selectLesson = select.copy(len = newLen)
								// 【提示只出现一次】只在第一次创建空白框时提醒一次怎么进编辑
								if(select.lesson == null && !hintedAdd && !lessonRender.lockLesson){
									hintedAdd = true
									tip("双击蓝框可添加课程，拖动上下横线可调整时长")
								}
							}
						)
					}.pointerInput(page, pickMode, lessonRender.lockLesson) {
						// 【蓝框上下横线加粗 / 拖动扩大缩小】
						// 上下两条加粗的横线就是把手: 按住它们上下拖即可扩大或缩小选框。
						//
						// 关键: 这里**不能**用 detectDragGestures —— 它在越过滑动阈值时会无条件
						// 消费事件, 结果同一块 Canvas 上的"长按拖动课程"和左右翻周全部失效
						// (滑动切不了周、课程拖不动)。改为手写手势: 只有按到把手时才消费事件,
						// 其它情况一律不消费, 手势照常往下传。
						awaitEachGesture {
							// 【第五十三轮】纯预览模式: 先等待一次事件再退出(避免空转自旋导致卡死/崩溃)
							if(previewMode){
								awaitPointerEvent()
								return@awaitEachGesture
							}
							val down = awaitFirstDown(requireUnconsumed = false)
							val sel = selectLesson
							// 只有空白课程的选框才有把手: 正常课程的蓝框不加粗、不可缩放
							val hit = if(pickMode || lessonRender.lockLesson || sel.lesson != null) LessonRender.HANDLE_NONE
								else lessonRender.hitSelectHandle(sel.dayOfWeek, sel.timeSlot, sel.len, down.position.x, down.position.y)
							if(hit != LessonRender.HANDLE_TOP && hit != LessonRender.HANDLE_BOTTOM){
								// 没按在把手上: 一点都不参与, 等抬手结束本轮(事件继续往下传)
								do { val event = awaitPointerEvent() } while(event.changes.any { it.pressed })
								return@awaitEachGesture
							}

							resizeStartTop = sel.timeSlot
							resizeStartLen = sel.len
							var started = false

							while(true){
								val event = awaitPointerEvent()
								val change = event.changes.firstOrNull { it.id == down.id } ?: break
								if(!change.pressed) break
								// 竖直方向确实拖动了才开始缩放(横向滑动不参与, 交给翻周)
								if(!started && kotlin.math.abs(change.position.y - down.position.y) > dragSlop) started = true
								if(!started) continue

								change.consume()
								val cur = selectLesson
								if(cur.dayOfWeek < 0 || cur.timeSlot < 0) continue
								val slotCount = TimeTables.currentSlotCount
								val top = cur.timeSlot.coerceIn(0, (slotCount - 1).coerceAtLeast(0))
								val bottom = (top + cur.len.coerceAtLeast(1) - 1).coerceIn(top, (slotCount - 1).coerceAtLeast(top))
								val finger = lessonRender.rawSlotAt(change.position.y.toInt())
									.coerceIn(0, (slotCount - 1).coerceAtLeast(0))
								if(hit == LessonRender.HANDLE_TOP){
									// 拖上边: 向上扩大 / 向下缩小, 下边不动
									val newTop = finger.coerceIn(0, bottom)
									selectLesson = cur.copy(timeSlot = newTop, len = bottom - newTop + 1)
								}else{
									// 拖下边: 向下扩大 / 向上缩小, 上边不动
									val newBottom = finger.coerceIn(top, (slotCount - 1).coerceAtLeast(top))
									selectLesson = cur.copy(len = (newBottom - top + 1).coerceAtLeast(1))
								}
							}

							// 【松手自动进入编辑】确实改变了大小时, 松手后直接打开编辑课表界面
							if(started && !lessonRender.lockLesson &&
								(resizeStartTop != selectLesson.timeSlot || resizeStartLen != selectLesson.len) &&
								selectLesson.dayOfWeek >= 0 && selectLesson.timeSlot >= 0){
								onLessonClick(selectLesson)
							}
						}
					}){
						drawIntoCanvas{ canvas ->
							lessonRender.drawView(canvas = canvas.nativeCanvas, page, expandTarget)
							val co = coincide
							// 【不要让蓝框乱动】选框只画在它被选中的那一周, 翻到别的周不再跟着出现
							if(co != null && co.week == page && co.day == selectLesson.dayOfWeek){
								// 重合提示: 每门课程各画一个框, 框的大小等于该课程实际占用的节数
								co.occupants.forEachIndexed { i, occupant ->
									lessonRender.drawHighlightBox(
										canvas.nativeCanvas, co.day, occupant.startSlot, occupant.len,
										if(i == 0) LessonRender.HIGHLIGHT_BLUE else LessonRender.HIGHLIGHT_AMBER,
										showHandles = false
									)
								}
						}else if(selectLesson.dayOfWeek != -1 && selectLesson.timeSlot != -1) {
							// 【只有空白框才有把手】正常课程的蓝框不加粗、不可拖动缩放
						lessonRender.drawHighlightBox(
							canvas.nativeCanvas, selectLesson.dayOfWeek, selectLesson.timeSlot, selectLesson.len,
							showHandles = selectLesson.lesson == null && !lessonRender.lockLesson
						)
						}				}
				}
			}
			}
				if(longMode && bottomBlankDp > 0f) Spacer(Modifier.height(bottomBlankDp.dp))
		}
	}
	}
	}

		// 课程重合提示: 列出该时段覆盖的全部课程, 选择后聚焦该课程并进入编辑
		coincide?.let { co ->
			AlertDialog(
				onDismissRequest = { coincide = null },
				title = { Text(text = "该时段有 ${co.occupants.size} 门课程重合") },
				text = {
					Column {
						Text(
							text = "该时间被多门课程占用，请选择要编辑的课程（选框会按该课程实际节数显示）：",
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.secondary,
							modifier = Modifier.padding(bottom = 8.dp)
						)
						co.occupants.forEach { occupant ->
							Row(
								modifier = Modifier.fillMaxWidth().tapRipple {
									val sel = LessonRender.SelectLesson(co.day, occupant.startSlot, occupant.lesson, occupant.len, co.week)
									lessonRender.focusLesson(co.week, co.day, occupant.startSlot, occupant.lesson)
									coincide = null
									selectLesson = sel
									if(!lessonRender.lockLesson) onLessonClick(sel)
								}.padding(vertical = 10.dp),
								verticalAlignment = Alignment.CenterVertically
							) {
								Text(
									text = "${occupant.lesson.name}（第${occupant.startSlot + 1}~${occupant.startSlot + occupant.len}节）",
									modifier = Modifier.weight(1F),
									style = MaterialTheme.typography.bodyMedium
								)
								Text(text = "编辑", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
							}
						}
					}
				},
				confirmButton = {
					TextButton(onClick = { coincide = null }) { Text(text = "取消") }
				}
			)
		}

	}

	@Composable
	fun Tab(
		timeBar: @Composable () -> Unit,
		dateBar: @Composable () -> Unit,
		/**
		 * true = 课表查询模式: 每一节用固定**短**行高(课程方块尽量短), 整张课表超出屏幕时由外层滚动;
		 * false = 学期课表(默认): 保持原版, 课表按可视高度均分、正好铺满一屏、不滚动。
		 */
		longMode: Boolean = false,
		// 课表查询底部留白高度(dp): 用于底部有一节课大小的空白区域, 0 表示不加
		bottomBlankDp: Float = 0f,
		lessonTable: @Composable () -> Unit,
	) {
		val density = LocalDensity.current
		val slotCount = TimeTables.currentSlotCount.coerceAtLeast(1)
		SubcomposeLayout(measurePolicy = { constraints ->
			val timeWidth: Int
			subcompose(SlotsEnum.Dependent, timeBar).first().let { measureAble ->
				measureAble.measure(constraints).also { timeWidth = it.width }
			}

			val dateHeight: Int
			val dateBars = subcompose(SlotsEnum.Date, dateBar)
			val dateBarPlace = dateBars[1].let{ measureAble ->
				measureAble.measure(constraints.copy(maxWidth = (constraints.maxWidth - timeWidth))).also { dateHeight = it.height }
			}
			val monthBarPlace = dateBars[0].measure(Constraints(timeWidth, timeWidth, dateHeight, dateHeight))

			// 课表内容区高度:
			//  - 课表查询(longMode): 固定短行高 × 节数, 超出屏幕由外层 verticalScroll 滚动;
			//  - 学期课表(原版): 用满可视高度(可视高 - 日期栏), 正好铺满一屏、不滚动。
			val contentHeight = if(longMode) with(density) { CELL_HEIGHT_DP.dp.toPx().toInt() } * slotCount
				else (constraints.maxHeight - dateHeight).coerceAtLeast(0)

			val timeBarPlace = subcompose(SlotsEnum.Time, timeBar).first().measure(
				Constraints.fixed(timeWidth, contentHeight)
			)

			val mainWidth = (constraints.maxWidth - timeWidth).coerceAtLeast(0)
			val lessonTablePlace = subcompose(SlotsEnum.Main, lessonTable).first().measure(
				Constraints.fixed(mainWidth, contentHeight)
			)

			layout(constraints.maxWidth, dateHeight + contentHeight) {
				monthBarPlace.place(0, 0)
				dateBarPlace.place(timeWidth, 0)
				timeBarPlace.place(0, dateHeight)
				lessonTablePlace.place(timeWidth, dateHeight)
			}
		})
	}

	/**
	 * 星期的日期头(周一 26 / 周二 27 …)
	 *
	 * @param dayClickEnabled 【本期】false 时不响应点击(连水波纹也没有) —— 课表查询页只做预览,
	 *   点日期头没有任何功能, 以前虽然回调是空的, 但 clickable 仍会画出涟漪, 看着像"点了有反应却没用"。
	 */
	@Composable
	fun LessonDate(startDate: Date, week: Int, onDayClick: (Int, Int) -> Unit = { _, _ -> }, dayClickEnabled: Boolean = true) {
		val currentDay = Calendar.getInstance()
		val c = Calendar.getInstance().also { it.time = startDate }

		// 【第七十轮·十八·按要求"小窗口和学期课表的 11月 又靠左了"】
		//
		// 十七轮按"盒心 = 时间串中线"整份补了差值(实测 9.6dp), 结果月份贴到卡片左边缘:
		// 截图量出来 "11月" 左沿离内容左边界只剩 6dp, 比下面 "08:00" 还靠外,
		// 看上去就是"整体左移过头"。
		// 本轮把位移**减半**(≈4.8dp) —— 这个量级正好等于「本周课表」小组件上用户没有异议的
		// 偏移(那边时间列窄, 差值只有 4dp)。也就是说: 不追求几何上的绝对居中,
		// 只补掉"列比字宽"的那一口气, 让三处(学期课表 / 悬浮窗 / 小组件)看起来是一条线。
		// 【第七十九轮】月份盒的补偿量改为完整几何解: 月份盒(timeWidth 宽)中心 = timeWidth/2 + shift,
		// 时间串中心 = 4dp padding + 墨迹/2, 而 timeWidth = timeBox + 8dp,
		// 代入得 shift = (timeTextWidth - timeBoxWidth) / 2 时月份中心恰好压在时间串中心上。
		// 以前盒宽是写死的 44dp, 完整补偿会太大(把月份顶到卡片左边缘), 只能补偿一半;
		// 现在盒子按墨迹自适应(timeBox ≈ 墨迹 + 2dp), 差值只剩 ~1dp, 可以放心用完整公式。
		val monthShift = (timeTextWidth - timeBoxWidth) / 2F
		Box(
			modifier = Modifier.fillMaxWidth().offset(x = monthShift),
			contentAlignment = Alignment.Center
		) {
			Text(text = "${c[Calendar.MONTH] + 1}月")
		}

		Row(modifier = Modifier.fillMaxWidth()) {
			repeat(Data.WEEK_STRING.size) {
				val color = if(currentDay[Calendar.DATE] == c[Calendar.DATE] && currentDay[Calendar.MONTH] == c[Calendar.MONTH]) {
					LESSON_TEXT_COLORS[0]
				} else Color.Gray

				Column(
					modifier = Modifier
						.weight(1F)
						.padding(4.dp)
						// 【第二十九轮·按要求】"周一/周二"这类整格点击原来是直角四方形波纹,
						// 统一换成 tapRipple(主题色 + 10dp 倒角), 和小格子形状贴合。
						.then(if(dayClickEnabled) Modifier.tapRipple(shape = RoundedCornerShape(10.dp)) { onDayClick(week, it) } else Modifier),
					horizontalAlignment = Alignment.CenterHorizontally
				) {
					Text(text = Data.WEEK_STRING[it], style = MaterialTheme.typography.bodySmall, color = color)
					Text(text = if(c[Calendar.DATE] == 1) "${c[Calendar.MONTH] + 1}月" else "${c[Calendar.DATE]}", style = MaterialTheme.typography.bodySmall, color = color)
				}
				c[Calendar.DATE] += 1
			}
		}
	}

	/**
	 * 左侧"节次 + 上课时间"栏
	 *
	 * 【课表查询里时间被遮挡】这一栏的高度要被课表区均分成 N 份(N = 时间节数),
	 * 课表查询页顶部还多占了三行(学期选择/说明/恢复默认), 留给每一节的高度更小;
	 * 固定写死三行(节次 + 开始 + 结束)时行高不够, 三行文字会互相叠在一起看着像被遮挡。
	 * 这里按**实际行高**决定显示几行: 高度够就显示 节次/开始/结束,
	 * 不够就只显示 节次 + 开始, 再不够只显示节次, 保证任何节数下都不会挤成一团。
	 *
	 * 【第五十五轮·按要求】"学期课表左边的时间表没有竖直对齐":
	 * 三行原来都是**水平居中**(节次是 1~11、时间是 08:00 这种定宽串, 宽度并不相同),
	 * 居中之后左边缘参差, 看起来就是没对齐。现在改成**左对齐 + 占满同一列宽**,
	 * 节次/上课/下课三行严格落在同一条竖线上, 与右侧日期条的起点也对得上。
	 */
	@Composable
	fun LessonTimeBar() {
		val times = TimeTables.currentTimes
		val slotCount = maxOf(times.getOrNull(0)?.size ?: 0, times.getOrNull(1)?.size ?: 0, 1)
		var totalHeight by remember { mutableIntStateOf(0) }

		// 一行的高度: 没测量到时先按三行显示(首帧后再按实际高度收敛)
		val rowHeight = if(totalHeight > 0) totalHeight / slotCount else 0

		// 【本轮·小窗优化】弹窗小窗口里每节分到的行高只有全屏的一半左右,
		// 三行(节次/上课/下课)按原字号放不下 —— 以前的办法是**少画两行**(上课/下课时间消失),
		// 用户要求"左侧节次时间完整展示, 不截断"。
		// 现在改成按行高预算**整体缩小**字号(下限 0.5 倍): 行高够时一个字都不变,
		// 不够时三行(节次+上课+下课)一起缩, 任何窗口高度下都完整显示三行。
		val timeStyleBase = MaterialTheme.typography.bodySmall.tnum()
		val numberStyleBase = MaterialTheme.typography.labelMedium.tnum()
		val measurer = rememberTextMeasurer()
		val density = LocalDensity.current
		val neededHeight = remember(timeStyleBase, numberStyleBase, measurer) {
			runCatching {
				val hNum = measurer.measure(AnnotatedString("0"), numberStyleBase).size.height
				val hTime = measurer.measure(AnnotatedString("08:00"), timeStyleBase).size.height
				hNum + hTime * 2
			}.getOrElse { 0 }
		}
		val fontScale = if(rowHeight <= 0 || neededHeight <= 0) 1F
			else ((rowHeight * 0.94F) / neededHeight).coerceIn(0.5F, 1F)
		val timeStyle = if(fontScale < 0.999F) {
			val scaled = with(density) { (timeStyleBase.fontSize.toDp() * fontScale).toSp() }
			val scaledLh = with(density) {
				((if(timeStyleBase.lineHeight.isSp) timeStyleBase.lineHeight else timeStyleBase.fontSize)
					.toDp() * fontScale).toSp()
			}
			timeStyleBase.copy(fontSize = scaled, lineHeight = scaledLh)
		} else timeStyleBase
		val numberStyle = if(fontScale < 0.999F) {
			numberStyleBase.copy(
				fontSize = with(density) { (numberStyleBase.fontSize.toDp() * fontScale).toSp() },
				lineHeight = with(density) {
					((if(numberStyleBase.lineHeight.isSp) numberStyleBase.lineHeight else numberStyleBase.fontSize)
						.toDp() * fontScale).toSp()
				}
			)
		} else numberStyleBase
		// 时间串的实际宽度: 与上面 numberWidth 同一个测量器、同一个(已缩放)字号,
		// 所以月份跟着它走之后, 缩字也好、悬浮窗也好, 中线永远压在时间串上。
		val measuredTimeWidth = remember(measurer, timeStyle, density) {
			runCatching {
				with(density) { measurer.measure(AnnotatedString("08:00"), timeStyle).size.width.toDp() }
			}.getOrElse { TIME_BAR_TEXT_WIDTH }
		}
		androidx.compose.runtime.SideEffect { timeTextWidth = measuredTimeWidth }
		// 【第七十九轮】时间盒宽 = 实测墨迹 + 2dp(见 timeBoxWidth 的说明)。
		val timeBox = measuredTimeWidth + 2.dp
		androidx.compose.runtime.SideEffect { timeBoxWidth = timeBox }
		val numberWidth = remember(measurer, timeStyle, density) {
			runCatching {
				val hourWidth = measurer.measure(AnnotatedString("00"), timeStyle).size.width
				val colonWidth = measurer.measure(AnnotatedString(":"), timeStyle).size.width
				with(density) { ((hourWidth + colonWidth / 2F) * 2F).toDp() }
			}.getOrElse { TIME_BAR_NUMBER_WIDTH }
		}

		Column(modifier = Modifier.fillMaxHeight().onSizeChanged { totalHeight = it.height }) {
			repeat(slotCount){
				Column(
					modifier = Modifier.weight(1F).padding(horizontal = 4.dp),
					verticalArrangement = Arrangement.Center,
					horizontalAlignment = Alignment.Start
				) {
					// 三行都按固定宽度排: 节次数字在实测宽度的盒子里居中(中心压在时间串的冒号上),
				// 两行时间左对齐、同宽 -> 左边缘严格重合
				// (绝不能用 fillMaxWidth: 这一栏是先于课表被测量的, 见常量注释)
					Text(
						text = (it + 1).toString(),
						style = numberStyle,
						maxLines = 1,
						softWrap = false,
						textAlign = TextAlign.Center,
					// 【第七十轮·八·撤回】上一版把这里改成 TIME_BAR_TEXT_WIDTH(与时间同宽),
					// 等于**挪动了整条时间栏的排版**, 用户明确说"没让你改这个位置" —— 撤回原值。
					modifier = Modifier.width(numberWidth)
				)
				Text(
					text = times[0].getOrElse(it){ "" },
					style = timeStyle,
					color = Color.Gray,
					maxLines = 1,
					softWrap = false,
					modifier = Modifier.width(timeBox)
				)
				Text(
					text = times[1].getOrElse(it){ "" },
					style = timeStyle,
					color = Color.Gray,
					maxLines = 1,
					softWrap = false,
					modifier = Modifier.width(timeBox)
				)
				}
			}
		}
	}

	enum class SlotsEnum { Dependent, Month, Time, Date, Main }

}