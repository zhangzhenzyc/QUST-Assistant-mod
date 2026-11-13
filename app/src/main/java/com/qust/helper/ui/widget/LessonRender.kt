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

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qust.helper.data.Data
import com.qust.helper.data.lesson.Lesson
import com.qust.helper.data.lesson.LessonTable
import com.qust.helper.data.lesson.TimeTables
import com.qust.helper.model.LessonTableRepository
import com.qust.helper.ui.theme.BACKGROUND_COLORS
import com.qust.helper.ui.theme.BACKGROUND_COLOR_SECOND
import com.qust.helper.ui.theme.TEXT_COLORS
import com.qust.helper.ui.theme.TEXT_COLOR_SECOND
import java.util.Date

/**
 * 总课表界面的渲染器
 */
class LessonRender(
	startDay: MutableState<Date> = LessonTableRepository._startDay,
	totalWeek: MutableIntState = LessonTableRepository._totalWeek,
	lessonTable: MutableState<LessonTable> = LessonTableRepository._lessonTable,
) {

	private var baseLine = 0
	private var textHeight = 0

	/**
	 * 【第六十一轮·修"课程名称和左上角的 A 重合"】
	 *
	 * 左上角那个 `A`/`U` 是课程类型标记, 它的**基线**画在 `y + baseLine + 12` 处,
	 * 墨迹底边就是这条基线(字母没有下伸部)。而课程文字是"整块居中"的:
	 * 格子被文字塞满时, 第一行墨迹顶只落在 `y + 32` 左右 —— 正好钻到 `A` 的墨迹里,
	 * 于是"课名第一个字压在 A 上"。格子越满越明显(长课名 + 教室 + 教师)。
	 *
	 * [labelBand] 就是"文字必须让出的顶部高度": 让第一行墨迹顶永远落在 `A` 下面。
	 * 它在 [setMeasureData] 里按**本机字体度量**算, 不写死像素(不同密度/字体缩放都对)。
	 */
	private var labelBand = 0

	/**
	 * 【第七十轮·二十】A/U 标识**墨迹的下沿**(相对格子顶 y 的像素)。
	 * 块内文字的居中锚点: 文字组在「A 墨迹下沿 → 方块底边」这段空间里垂直居中,
	 * 让 A→文字 与 文字→底边 两段空白严格相等(上一版锚在 [labelBand] 上,
	 * 上边的空白永远比下边多出一条避让带 —— 用户说的"上半部分留白太多")。
	 */
	private var labelInkBottom = 0

	/**
	 * 【第五十九轮·二】基准字号(px)。
	 *
	 * 每个课程块可以有自己的 [LessonRenderData.LessonData.textScale](放不下就缩字),
	 * 绘制时要按块切换 `paintT.textSize`, 画完必须回到这个基准 —— 否则"节次/时间/A·U 标记"
	 * 也会跟着缩, 越画越小。
	 */
	private var baseTextSize = 0F

	/**
	 * 左侧时间和顶部日期的宽度
	 */
	private var timeWidth = 0
	private var dateHeight = 0

	/**
	 * 最小的一节课的大小
	 */
	private var cellWidth = 0
	private var cellHeight = 0

	/**
	 * 不同文本之间的间距
	 */
	private var linePadding = 0

	private var composeWidth: Int = 0
	private var composeHeight: Int = 0

	/** 屏幕密度(用于选框把手的线宽/命中范围换算) */
	private var densityValue: Float = 2f

	/**
	 * 上次测量时的时间节数, 切换时间表后需要重新计算单元格高度
	 */
	private var lastSlotCount = 0

	/**
	 * 【第三十二轮】本次绘制里"被单击展开"的那一格与它的底边(像素)。
	 *
	 * 选框(`drawHighlightBox`)要跟着变长 —— 课程块拉长了, 蓝框却还只包住原来那两节,
	 * 用户看到的就是"框和课对不上"。值在 [drawView] 里刷, 取不到时是 -1。
	 */
	private var expandDay = -1
	private var expandSlot = -1
	private var expandBottom = -1

	private val paint: Paint = Paint(Paint.FILTER_BITMAP_FLAG).also { paint ->
		paint.style = Paint.Style.FILL
		paint.isAntiAlias = true
		paint.strokeWidth = 8f
	}

	private val paintT: Paint = Paint().also { paintT ->
		paintT.isDither = true
		paintT.isAntiAlias = true
		paintT.isSubpixelText = true
		paintT.textAlign = Paint.Align.CENTER
	}

	/**
	 * 放假日期的蒙层/标签绘制(独立 Paint)
	 */
	private val paintHoliday: Paint = Paint().also { p ->
		p.isAntiAlias = true
		p.style = Paint.Style.FILL
	}

	private val paintHolidayText: Paint = Paint().also { p ->
		p.isAntiAlias = true
		p.textAlign = Paint.Align.CENTER
		p.isFakeBoldText = true
	}

	private val hideTeacher by LessonTableRepository._hideTeacher
	private val showAllLesson by LessonTableRepository._showAllLesson

	private var _startDay = startDay
	val startDay: Date
		get() = _startDay.value

	private var _totalWeek = totalWeek
	val totalWeek: Int
		get() = _totalWeek.intValue

	private var _lessonTable = lessonTable
	val lessonTable: LessonTable
		get() = _lessonTable.value

	/**
	 * 某一周"周一"对应的日期, 与 LessonTable.dateKeyOf 共用同一对齐规则
	 */
	fun weekStartDate(week: Int): Date = lessonTable.weekStartDate(week)

	private var lessonRenderData by mutableStateOf(LessonRenderData(hideTeacher, paintT))

	val lockLesson by LessonTableRepository._lockLesson

	/**
	 * 设置 View Measure 数据
	 * 必须调用，不然无法显示
	 */
	fun setMeasureData(measuredWidth: Int, measuredHeight: Int, density: Density) {
		// 【刚进入软件课表空白】首次布局回调可能在数据/尺寸未就绪时发生(甚至拿到 0x0),
		// 如果渲染数据还没带上课表, 即使尺寸没变也必须强制重算, 否则界面一直空白,
		// 要来回切换页面(重建组合)才能显示
		if(measuredWidth == composeWidth && measuredHeight == composeHeight
			&& lastSlotCount == TimeTables.currentSlotCount
			&& lessonRenderData.lessonTable != null) return
		densityValue = density.density
		with(density) {
			linePadding = 4.dp.toPx().toInt()
			paintT.textSize = 12.sp.toPx()
			baseTextSize = paintT.textSize
			baseLine = (paintT.textSize / 2 + (paintT.fontMetrics.descent - paintT.fontMetrics.ascent) / 2 - paintT.fontMetrics.descent).toInt()
			textHeight = (paintT.textSize + 3).toInt()
			// 【第六十一轮】"文字让出 A/U 标签"的高度。
			// 【第七十轮·修"有的字会与 A 部分重合"】旧公式把"无偏移的第一行墨迹顶"算成了
			//   firstInkTop = baseLine + textHeight - |ascent|
			// —— 这隐含"第一行基线 = y + baseLine + textHeight"。但 drawView 里第一行基线
			// 最低只能到 y + labelBand + bsl(bsl 是本块字号下的基线偏移), 墨迹顶还要再往上
			// 伸 |ascent| 一个整量。两相抵扣, 旧 labelBand 实际只有 ~14px(5dp),
			// "标签带"形同虚设: 内容一满(居中偏移被钳 0), 文字就顶进 A 的墨迹里 ——
			// 弹窗小窗口把格子压矮之后, 用户在截图里看到的正是这个。
			//
			// 按基线-墨迹真实关系重推(全部是相对格子顶 y 的量):
			//   第一行墨迹顶(最坏, 居中偏移=0) = labelBand + bsl - |ascent|
			//   A 的墨迹底 ≈ 它的基线 = baseLine + (LESSON_PADDING shl 2)(A 无下伸部)
			//   要求: labelBand + bsl - |ascent| ≥ baseLine + 16 + 4
			//   bsl 与 |ascent| 都随字号线性缩放, 且 (bsl - |ascent|) 随字号变小而更接近 0
			//   —— 所以**全字号**是最坏档, 取:
			//   labelBand = baseLine + 16 + 4 + (|ascent| - bsl_full)
			//   其中 (|ascent| - bsl_full) = (|ascent| + |descent| - 字号) / 2, 为正数。
		val fm = paintT.fontMetrics
		val inkAboveBase = ((-fm.ascent + fm.descent - paintT.textSize) / 2.0).coerceAtLeast(0.0)
		labelInkBottom = kotlin.math.ceil((baseLine + (LESSON_PADDING shl 2)).toDouble()).toInt()
		labelBand = kotlin.math.ceil(labelInkBottom + 4 + inkAboveBase).toInt()
		}
		composeWidth = measuredWidth
		composeHeight = measuredHeight
		cellWidth = (measuredWidth - timeWidth) / 7
		cellHeight = (measuredHeight - dateHeight) / TimeTables.currentSlotCount
		// 【第七十轮】极端矮格(弹窗小窗一格只有几十 px)时标签带不能再占一半以上 ——
		// 那样文字会被整体推出格子。钳到"半格", 宁可让 A 那一小角被压, 也不丢整个内容。
		labelBand = labelBand.coerceAtMost(cellHeight / 2)
		lastSlotCount = TimeTables.currentSlotCount
		// 【第八十轮·按用户要求"课程留白太大"】文字排版宽度从"格子宽 - 16px"放大到
		// "格子宽 - 8px": 方块左缘只缩进 2px、右缘 4px, 旧的两边各留 8px 过于保守,
		// 长课名/长教室凭空少折一行的机会就在这 8px 里。文字始终居中, 不会贴边。
		lessonRenderData = LessonRenderData(hideTeacher, paintT, (cellWidth - (LESSON_PADDING shl 1)), lessonTable, cellHeight, textHeight, labelBand, linePadding)
		lessonRenderData.calcLessonData()
	}

	fun updateLessonTable() {
		if(cellWidth != 0 && cellHeight != 0) {
			lessonRenderData = LessonRenderData(hideTeacher, paintT, (cellWidth - (LESSON_PADDING shl 1)), lessonTable, cellHeight, textHeight, labelBand, linePadding)
			lessonRenderData.calcLessonData()
		}
	}

	/** 是否已完成测量(可进行坐标换算与绘制) */
	val geometryReady: Boolean
		get() = cellWidth > 0 && cellHeight > 0

	/**
	 * 获取点击位置的课程
	 * @param week 当前周(从0开始)
	 * @param downX 点击X坐标
	 * @param downY 点击Y坐标
	 */
	fun getClickLesson(week: Int, downX: Int, downY: Int): SelectLesson {
		if(downX < timeWidth || downY < dateHeight) {
			return SelectLesson(-1, -1, null, 1, week)
		}

		// 计算点击的位置是星期几
		val dayOfWeek = (downX - timeWidth) / cellWidth
		if(dayOfWeek >= Data.WEEK_STRING.size || dayOfWeek < 0) {
			return SelectLesson(-1, -1, null, 1, week)
		}
		var y = downY - dateHeight
		val dayData = lessonRenderData.lessonsForWeek(week)

		for(timeSlot in 0 until TimeTables.currentSlotCount) {
			val column = dayData.getOrNull(dayOfWeek) ?: return SelectLesson(-1, -1, null, 1, week)
			column.getOrNull(timeSlot)?.let{ holder ->
				// 【问题7】本周真的有这门课(current 不为空) vs 只是别的周有(灰色课程)
				val currentData = holder.current(week)
				var lessonData = currentData
				// 灰色课程: 只可能是"之后还会上"的课, 与绘制保持一致
				if(lessonData == null && showAllLesson) {
					lessonData = holder.findLesson(week)
				}
				if(lessonData != null) {
					if(y < lessonData.len.coerceAtLeast(1) * cellHeight) {
						// 选框长度直接取该课程实际占用的节数, 保证选框与课程大小一致(不会出现"半节课"选框)
					return SelectLesson(
						dayOfWeek, timeSlot,
						lessonRenderData.getLessonByHolder(week, dayOfWeek, timeSlot),
						lessonData.len.coerceAtLeast(1), week
					)
					}
				}
			}

			if(y < cellHeight) {
				return SelectLesson(dayOfWeek, timeSlot, null, 1, week)
			}
			y -= cellHeight
		}
		return SelectLesson(-1, -1, null, 1, week)
	}

	/**
	 * 点击位置所在的"原始"节次(不合并长课程)
	 * 用于查找该时刻覆盖的全部课程(可能被上方长课程跨越覆盖)
	 */
	fun rawSlotAt(downY: Int): Int {
		if(!geometryReady) return -1
		val slotCount = TimeTables.currentSlotCount
		if(slotCount <= 0) return -1
		return ((downY - dateHeight) / cellHeight).coerceIn(0, slotCount - 1)
	}

	/**
	 * 找出覆盖该时刻的全部课程(按起始节次升序)
	 * 数量 > 1 表示该时段存在课程重合, 需要提示用户
	 */
	fun occupantsAt(week: Int, dayOfWeek: Int, slot: Int): List<LessonTable.Occupant> {
		return lessonTable.occupantsAt(week, dayOfWeek, slot)
	}

	/**
	 * 把某格展示的课程切换为指定的那一节(用于重合课程之间的切换)
	 */
	fun focusLesson(week: Int, dayOfWeek: Int, slot: Int, lesson: Lesson): Boolean {
		val holder = lessonRenderData.lessonsForWeek(week).getOrNull(dayOfWeek)?.getOrNull(slot) ?: return false
		return holder.focus(week, lesson)
	}

	/**
	 * 绘制View
	 * @param week 绘制第几周，从0开始
	 * @param expanded 被单击展开的课程(为空则完全按原样绘制)
	 */
	fun drawView(canvas: Canvas, week: Int, expanded: ExpandTarget? = null) {
		val dayData = lessonRenderData.lessonsForWeek(week)
		val slotCount = TimeTables.currentSlotCount
		// 【防崩溃】查询到新学期后总周数可能变小, 而翻页还停在原来的周次上;
		// 直接用越界的 week 取 lessonCount/index 会抛异常退出应用, 这里先夹到合法范围
		val w = week.coerceIn(0, (lessonTable.totalWeek - 1).coerceAtLeast(0))
		// 【第三十一轮·按要求】被单击的课程"原位拉长": 先在主循环之前把它算清楚,
		// 主循环跳过那一格(不再按原高度画一遍), 最后只在最上层画**一块**、**一段文字**。
		// 以前是"先画原样、再在上面盖一块高的 + 重画整段文字", 于是两块叠着、文字也是两份。
		val plan = if(expanded != null && expanded.week == w) buildExpandPlan(w, expanded) else null
		expandDay = -1
		expandSlot = -1
		expandBottom = -1
		if(plan != null) {
			expandDay = plan.dayOfWeek
			expandSlot = plan.timeSlot
			expandBottom = plan.bottom
		}
		// 【第九十五轮】两遍绘制: 第一遍只画灰色课程, 第二遍只画本周普通课程 ——
		// 普通课程永远盖在灰色课程上面。之前按格子自上而下逐格画,
		// 下方格子的灰课会把上方普通课程(len>1)的下半截盖住(复变函数被形势与政策遮住就是这种)。
		for(pass in 0..1) {
			var x = timeWidth
			for(i in dayData.indices) {
				// 该日期是否被标记为放假: 只做标记, 不删除课程(课程仍照常绘制, 仅加蒙层与标签)
				val holiday = lessonTable.isHoliday(lessonTable.dateKeyOf(w, i))
				var y = dateHeight
				// 【防崩溃】某一天被手动编辑过后, 它的数组长度可能少于当前时间节数
				val slots = minOf(slotCount, dayData[i].size)
				for(j in 0 until slots) {

					// 展开的那一格交给 drawExpandedLesson 单独画(这里只推进纵坐标, 保持后面的格子对位)
					if(plan != null && plan.dayOfWeek == i && plan.timeSlot == j) { y += cellHeight; continue }

					val holder: LessonRenderData.LessonHolder? = dayData[i][j]
					if(holder == null) { y += cellHeight; continue }

					val current = holder.current(w)
					val gray = if(current == null && showAllLesson) holder.findLesson(w) else null
					if(current == null && gray == null) { y += cellHeight; continue }
					val lesson = current ?: gray!!
					val isGray = current == null
					// 第一遍(pass=0)只画灰课, 第二遍(pass=1)只画普通课
					if(isGray != (pass == 0)) { y += cellHeight; continue }

					if(isGray) {
						paint.color = BACKGROUND_COLOR_SECOND
						paintT.color = TEXT_COLOR_SECOND
					} else {
						paint.color = BACKGROUND_COLORS[lesson.color]
						paintT.color = TEXT_COLORS[lesson.color]
					}

				// 【第八十轮·按用户要求"课程方块宽度可以向左加宽一点, 不要压到时间表"】
				// 方块左内边距 4px -> 2px, 向时间表方向扩 2px(时间栏文字自带 4dp 右内边距,
				// 视觉间隙仍有 ~5dp, 压不到); 右边不动, 不会遮到右边一列(周日)的课程。
				canvas.drawRoundRect((x + 2).toFloat(), (y + LESSON_PADDING).toFloat(), (x + cellWidth - LESSON_PADDING).toFloat(), (y + cellHeight * lesson.len - LESSON_PADDING).toFloat(), 16f, 16f, paint)
				canvas.drawText(if(lesson.type == 0) "A" else "U", (x + (LESSON_PADDING shl 2) + 3).toFloat(), (y + baseLine + (LESSON_PADDING shl 2)).toFloat(), paintT)

				// 同一时段有多门课重合时显示 "第几门/共几门"(周次越界用安全取值)
				val count = holder.countAt(w)
				if(count > 1) {
					canvas.drawText((holder.indexAt(w) + 1).toString() + "/" + count, (x + cellWidth / 2).toFloat(), (y + cellHeight * lesson.len - textHeight + baseLine - (LESSON_PADDING shl 2)).toFloat(), paintT)
				}

				// 【第五十九轮】段间距用 lesson.gaps(实际写进去几个空行), 不再用 hideTeacher 猜;
				// 循环也只画到 used —— 容量用不完的尾部空格不再各占一个行距, 文字才是真正居中的。
				//
				// 【第五十九轮·二】行高/基线/字号全部取**这一块自己的**(可能为了塞下完整课名缩过字号):
				// 用全局的 textHeight 去排"缩过字号"的文字, 每行会多占 2~4px, 几行之后就是"最后一行
				// 被挤出格子" —— 用户的"课名/教室显示不全"有一半是这么来的。
				val lh = lesson.lineHeight
				val bsl = if(lesson.baseline > 0) lesson.baseline else baseLine
				paintT.textSize = baseTextSize * lesson.textScale
				// 【第六十一轮】居中区域从"整格"收到"标签带以下"—— 这是"课名压到 A 上"的根治:
				// 文字块在剩下的高度里居中, 满格时也不会顶到 A 的墨迹里。
				// 【本轮·小窗优化】居中偏移不允许为负: 弹窗小窗口里格子很矮, 内容常比格子还高,
				// 负偏移会把第一行顶回 labelBand 上面, 正好盖住左上角的 A/U 标识 ——
				// 钳到 0 之后文字只向下溢出, A 永远露在外面(配合字号多档下探, 正常不会再溢出)。
				// 【第七十轮·六·按要求】"课程信息离 A 太远, 留白太多, 浪费地方还不美观"
				//
				// 以前这里在"整格高度 − 标签带 − 文字行数"里做**垂直居中**(那个 /2),
				// 一节两节的课块很高、文字只有两三行时, 文字就被推到块中间, 与左上角的 A/U
				// 隔着一大片空白 —— 用户看到的就是"字离 A 很远"。
				// 现在:**紧贴标签带下沿开始排**(只留 1px 呼吸), 信息与 A 成为一组;
				// 不重合由 labelBand 兜住(它按 A 的墨迹下沿算, 见 setMeasureData),
				// 放不下时仍按原有逻辑逐档缩字号 —— 这就是"缩字保信息、绝不压 A"。
				// 【第七十轮·七·按要求】"课程信息和 A 的关系正常了, 但是下方留白变多, 看着奇怪,
				// 最好能保持居中的样子"。
				//
				// 上一版是**死贴**标签带下沿(只留 1px): 一节两节的课块很高时, 文字全挤在顶部,
				// 底下空一大片。现在把 **"A/U 标签 + 文字"当成一整组**, 让这一组在格子里垂直居中 ——
				// 文字与 A 的距离**恒等于 labelBand**(关系不变), 多出来的空间上下**均分**,
				// 所以既贴近 A、又看起来是居中的。
				// 【第七十轮·九·按要求】"课程方块下半部分留白还是太多了" —— 上一版的 slack 用的是
				// **一格**的高度(`cellHeight`), 但一门 2 节的课方块其实是 `cellHeight × len` 高:
				// 按一格算出来的偏移几乎为 0, 文字全挤在方块上半截, 下面空出一整节。
				// 现在改用**整个方块的高度**来均分: 上下的空白一样多, 看着就是居中的。
				var textH = 0
				for(n in 0 until lesson.used) {
					textH += if(lesson.data[n] == null) linePadding else lh
				}
				val blockH = cellHeight * lesson.len
				// 【第七十轮·二十·按要求"上下留白面积保持一致"】
				// 居中区域从 [labelBand, 方块底边] 改为 **[A 的墨迹下沿, 方块底边]**:
				// slack 按 labelInkBottom 算, 上(A→文字)与下(文字→底边)两段空白就严格相等。
				// labelBand(多出 4px + 半个行距)仍然只服务于字号档位的容量计算(宁可保守)。
				val slack = ((blockH - labelInkBottom - textH) / 2F).toInt().coerceAtLeast(0)
				var lineY: Int = y + labelInkBottom + bsl + slack
				for(n in 0 until lesson.used) {
					lineY += if(lesson.data[n] == null) {
						linePadding
					} else {
						canvas.drawText(lesson.data[n]!!, (x + cellWidth / 2).toFloat(), lineY.toFloat(), paintT)
						lh
					}
				}
				paintT.textSize = baseTextSize
				y += cellHeight
			}
			// 放假标记只画一次(第二遍结束时)
			if(holiday && pass == 1) drawHolidayMark(canvas, x)
			x += cellWidth
			}
		}

		// 【第三十一轮】被单击的那门课最后画: 原位拉长、接着往下补字(不叠加第二块/第二段文字)
		plan?.let { drawExpandedLesson(canvas, it) }
	}

	/**
	 * 【第三十一轮·按要求】"单击课程 -> 课程原位变长, 把剩下的文字接着往下显示"。
	 *
	 * 和第三十轮的区别(用户反馈"不要新覆盖一个文字, 看着太生硬"):
	 *  · 以前: 原样画一遍 -> 再在上面盖一块更高的同色块 -> 再从第一行重画整段文字。
	 *    结果就是"两块叠着 + 两份文字", 而且新文字是重新居中的, 和旧文字错开一点点, 看着糊。
	 *  · 现在: **这一格不再按原高度画**(见 [drawView] 里的跳过), 只画一块 —— 高度刚好装下全部文字,
	 *    文字起点仍用原来的公式算出来(第一个字一个像素都不挪), 只是接着往下多画几行。
	 *
	 * 放得下(不需要变长)时返回 null, 界面完全保持原样。
	 */
	private fun buildExpandPlan(week: Int, target: ExpandTarget): ExpandPlan? {
		if(cellWidth <= 0 || cellHeight <= 0) return null
		val dayData = lessonRenderData.lessonsForWeek(week)
		val holder = dayData.getOrNull(target.dayOfWeek)?.getOrNull(target.timeSlot) ?: return null

		var lessonData = holder.current(week)
		if(lessonData == null) {
			// 灰色课程(本周不上、之后会上): 与绘制口径一致, 只在"显示全部课程"时展开
			if(!showAllLesson) return null
			lessonData = holder.findLesson(week) ?: return null
		}
		val lesson = lessonRenderData.getLessonByHolder(week, target.dayOfWeek, target.timeSlot) ?: return null
		if(lessonData.len <= 0) return null

		// 【第六十轮·修"该长的没长、不该长的却长了"】
		//
		// 用户定的规则很明确:**"如果学期课表原长度能够展示应当展示的全部内容, 点击后无需变长;
		// 只有信息展示不全的才应该变长"**。所以这里只认一个标志 —— [LessonData.truncated]
		// (课名/教室/教师逐字比对出来的"有没有缺内容"), 不再用"行数差"或者"字号缩过没有"去猜。
		//
		// 上一版就是因为拿 `lessonData.textScale < 1` 当展开理由, 于是"毛概"(为了塞下多缩了一档、
		// 内容其实一个字没少)点一下就长, 而"习近平概论"(真的缺内容)反而不动 —— 两种都错。
		if(!lessonData.truncated) return null
		// 展开用的字号与平时一致: 这样"接着往下补的那几行"必然能全部排完, 不会展开后还带省略号。
		val items = lessonRenderData.fullLineItems(lesson, lessonData.textScale)
		if(items.isEmpty()) return null

		val y = dateHeight + target.timeSlot * cellHeight
		val len = lessonData.len.coerceAtLeast(1)
		val normalHeight = cellHeight * len
		val inner = linePadding.coerceAtLeast(1)

		// 全部文字排下来一共多高(段间距按 linePadding 算, 与普通绘制一致)
		val lh = lessonData.lineHeight
		val bsl = if(lessonData.baseline > 0) lessonData.baseline else baseLine
		var block = 0
		for(item in items) block += if(item == null) linePadding else lh
		// 已经确认"有内容没显示全", 剩下的只有一种情况不需要变长: 完整内容其实也装得下
		// (比如只是教师那一行被丢了) —— 那就靠展开把它补出来即可, 不必再长高。
		if(block + inner * 2 <= normalHeight && items.size <= lessonData.used) return null

		val top = y + LESSON_PADDING
		// 原来的第一行基线: 与 drawView 里的公式**逐字一致** —— 展开后第一个字不移动。
		//
		// 【第五十九轮】段间距改用 `lessonData.gaps`(实际写进去几个空行), 不再用
		// `if(hideTeacher) 1 else 2` 去猜: 课名把容量吃满时实际段间距是 0, 猜出来是 2,
		// 于是"点一下文字整体往上跳两行行距" —— 用户反馈的"看着生硬"有一半是这个。
		//
		// 【第七十四轮·按要求"点开变长后留白不要太大"】展开后的内容在
		// **[A 的墨迹下沿, 拉长后的底边]** 里垂直居中 —— 不再沿用缩小状态下的顶部起点。
		// 以前顶部起点是"缩小状态居中"的位置, 展开只是往下补行, 于是拉长后
		// 上半截还是原来的那片空白(用户点名的就是它)。现在先按"内容高度"算出
		// 拉长后的块底, 再让全部内容在块里上下均分。
		val limitBottom = composeHeight - LESSON_PADDING
		val baseTop = y + labelInkBottom
		// 拉长后的底边: 至少到原块的底, 需要则随内容往下延; 超出课表就砍在课表底
		val bottomEstimate = maxOf(y + normalHeight - LESSON_PADDING, baseTop + block + inner * 2)
			.coerceAtMost(limitBottom)
		var startY = baseTop + ((bottomEstimate - baseTop) - block).coerceAtLeast(0) / 2
		startY = startY.coerceAtLeast(y + labelBand)
		val shown = ArrayList<String?>(items.size)
		var used = 0
		for(k in items.indices) {
			val step = if(items[k] == null) linePadding else lh
			if(k > 0 && startY + used + step + inner > limitBottom) break
			used += step
			shown.add(items[k])
		}
		// 末尾的段间距没有意义(会白占一块高度), 去掉后再算一次高度
		while(shown.isNotEmpty() && shown.last() == null) {
			shown.removeAt(shown.size - 1)
			used -= linePadding
		}
		if(shown.isEmpty()) return null

		// 底边 = 文字底部 + 内边距; 但不能比原来的块矮(否则"变长"就变成"变短"了)
		var bottom = startY + used + inner
		if(bottom < y + normalHeight - LESSON_PADDING) bottom = y + normalHeight - LESSON_PADDING
		if(bottom > limitBottom) bottom = limitBottom
		if(bottom <= top) return null

		val overlap = holder.countAt(week).let { if(it > 1) "${holder.indexAt(week) + 1}/$it" else null }
		return ExpandPlan(
			dayOfWeek = target.dayOfWeek,
			timeSlot = target.timeSlot,
			x = timeWidth + target.dayOfWeek * cellWidth,
			y = y,
			top = top,
			bottom = bottom,
			color = lessonData.color,
			lessonType = lessonData.type,
			overlap = overlap,
			lines = shown,
			startY = startY,
			textScale = lessonData.textScale
		)
	}

	/** 画"原位拉长"的课程块: 只有一块底、一段文字 */
	private fun drawExpandedLesson(canvas: Canvas, plan: ExpandPlan) {
		// 【第八十轮】与普通绘制同口径: 方块左缘也向左扩 2px
		val left = (plan.x + 2).toFloat()
		val right = (plan.x + cellWidth - LESSON_PADDING).toFloat()

		paint.style = Paint.Style.FILL
		paint.color = BACKGROUND_COLORS[plan.color]
		// 【第五十五轮·按要求】"点击课程加长时课程颜色不要变暗, 保持原色":
		// 原来这里给这一块加了一层投影(setShadowLayer), 渲染出来整块比正常格子显暗。
		// 现在不加任何阴影/滤镜 —— 展开只是"把同一门课画高一点", 底色与旁边完全一致。
		canvas.drawRoundRect(left, plan.top.toFloat(), right, plan.bottom.toFloat(), 16f, 16f, paint)

		paintT.color = TEXT_COLORS[plan.color]
		paintT.textSize = baseTextSize
		canvas.drawText(if(plan.lessonType == 0) "A" else "U", (plan.x + (LESSON_PADDING shl 2) + 3).toFloat(), (plan.y + baseLine + (LESSON_PADDING shl 2)).toFloat(), paintT)

		plan.overlap?.let {
			canvas.drawText(it, (plan.x + cellWidth / 2).toFloat(), (plan.bottom - textHeight + baseLine - (LESSON_PADDING shl 2)).toFloat(), paintT)
		}

		// 【第五十九轮·二】按这一块自己的字号与行高绘制(与平时完全一致, 只是多画了几行)
		val lh = (textHeight * plan.textScale).toInt().coerceAtLeast(1)
		paintT.textSize = baseTextSize * plan.textScale
		var lineY = plan.startY
		for(item in plan.lines) {
			if(item == null) {
				lineY += linePadding
			}else{
				lineY += lh
				canvas.drawText(item, (plan.x + cellWidth / 2).toFloat(), lineY.toFloat(), paintT)
			}
		}
		paintT.textSize = baseTextSize
	}

	/**
	 * 绘制"放假"标记
	 *
	 * 放假只是标记: 不删除该日期的课程, 仅在整列上层叠加半透明蒙层并在顶部显示"放假"标签,
	 * 课程内容仍然可见(便于查看), 也仍然可以被"复制整天课表"复制到其它日期。
	 */
	private fun drawHolidayMark(canvas: Canvas, x: Int) {
		val slotCount = TimeTables.currentSlotCount
		if(cellWidth <= 0 || cellHeight <= 0 || slotCount <= 0) return
		val left = x.toFloat()
		val right = (x + cellWidth).toFloat()
		val top = dateHeight.toFloat()
		val bottom = (dateHeight + cellHeight * slotCount).toFloat()

		// 【问题6】放假日期的背景保持原色: 不再整列蒙一层灰色(原来会盖住整列课程显得发灰),
		// 只在日期列顶部加一个「放假」文字标记。原来那层蒙层保留为可选常量, 默认不使用。
		if(HOLIDAY_SHOW_VEIL){
			paintHoliday.style = Paint.Style.FILL
			paintHoliday.color = HOLIDAY_VEIL
			canvas.drawRect(left, top, right, bottom, paintHoliday)
		}

		// 顶部"放假"文字标记
		paintHolidayText.textSize = paintT.textSize
		val badgeHeight = (paintHolidayText.textSize * 1.7f).coerceAtLeast(1f)
		val badgeTop = top + 2f
		paintHoliday.color = HOLIDAY_BADGE
		canvas.drawRoundRect(left + 2f, badgeTop, right - 2f, badgeTop + badgeHeight, 8f, 8f, paintHoliday)
		paintHolidayText.color = Color.WHITE
		canvas.drawText("放假", (left + right) / 2f, badgeTop + badgeHeight * 0.78f, paintHolidayText)
	}

	/**
	 * 绘制选中高亮框
	 * @param week 星期几
	 * @param count 起始节次
	 * @param len 覆盖的节数(有课程时等于该课程实际占用的节数)
	 * @param color 框颜色(重合课程可用不同颜色区分)
	 */
	fun drawHighlightBox(canvas: Canvas, week: Int, count: Int, len: Int, color: Int = HIGHLIGHT_BLUE, showHandles: Boolean = true) {
		val lineWidth = paint.strokeWidth
		paint.style = Paint.Style.STROKE
		// 【修改版】蓝框重绘: 细描边 + 大圆角 + 轻阴影, 简洁高级
		paint.color = color
		paint.strokeWidth = 3f
		paint.strokeJoin = Paint.Join.ROUND
		paint.setShadowLayer(6f, 0f, 1.5f, 0x33000000)
		val box = selectBoxRect(week, count, len)
		canvas.drawRoundRect(box[0], box[1], box[2], box[3], 28f, 28f, paint)
		paint.clearShadowLayer()

		// 【修改版】把手: 上下各一条细圆头短线(半透明), 克制但可辨识
		if(showHandles && box[3] - box[1] > 8f){
			// 与边框完全重合、圆头收尾: 视觉上是边框上下自然加粗, 不是另加一条线
			paint.strokeWidth = 15f
			paint.strokeCap = Paint.Cap.ROUND
			paint.alpha = 255
			val inset = ((box[2] - box[0]) * 0.20f)
			canvas.drawLine(box[0] + inset, box[1], box[2] - inset, box[1], paint)
			canvas.drawLine(box[0] + inset, box[3], box[2] - inset, box[3], paint)
			paint.alpha = 255
			paint.strokeCap = Paint.Cap.BUTT
		}

		paint.style = Paint.Style.FILL
		paint.strokeWidth = lineWidth
	}

	/** 选框的 (left, top, right, bottom) */
	private fun selectBoxRect(day: Int, slot: Int, len: Int): FloatArray {
		var bottom = (slot * cellHeight + cellHeight * len.coerceAtLeast(1) + dateHeight - LESSON_PADDING).toFloat()
		// 【第三十二轮】这一格正被展开 -> 选框跟着课程块一起变长
		if(day == expandDay && slot == expandSlot && expandBottom > 0) {
			val expanded = expandBottom + LESSON_PADDING
			if(expanded > bottom) bottom = expanded.toFloat()
		}
		return floatArrayOf(
			(day * cellWidth + timeWidth + LESSON_PADDING).toFloat(),
			(slot * cellHeight + dateHeight + LESSON_PADDING).toFloat(),
			(day * cellWidth + cellWidth + timeWidth - LESSON_PADDING).toFloat(),
			bottom
		)
	}

	/** 把手的命中半高(像素): 取格高的 45% 与最小值中较大者, 但不超过框的中线 */
	private fun handleTouchHalfPx(): Float {
		val half = (cellHeight * 0.45f).coerceAtLeast(HANDLE_TOUCH_MIN_DP * densityValue)
		return half.coerceAtMost((cellHeight * 0.5f).coerceAtLeast(1f))
	}

	/**
	 * 判断触摸点落在选框的哪个位置(用于"拖动上下横线扩大/缩小选框")
	 *
	 * @return [HANDLE_TOP] 上边把手 / [HANDLE_BOTTOM] 下边把手 / [HANDLE_INSIDE] 框内 / [HANDLE_NONE] 框外
	 */
	fun hitSelectHandle(day: Int, slot: Int, len: Int, x: Float, y: Float): Int {
		if(!geometryReady || day < 0 || slot < 0) return HANDLE_NONE
		val box = selectBoxRect(day, slot, len)
		if(x < box[0] - HANDLE_TOUCH_EXTRA_X * densityValue || x > box[2] + HANDLE_TOUCH_EXTRA_X * densityValue) return HANDLE_NONE
		val half = handleTouchHalfPx()
		return when {
			y < box[1] - half || y > box[3] + half -> HANDLE_NONE
			y <= box[1] + half -> HANDLE_TOP
			y >= box[3] - half -> HANDLE_BOTTOM
			else -> HANDLE_INSIDE
		}
	}


	data class SelectLesson(
		val dayOfWeek: Int,
		val timeSlot: Int,
		val lesson: Lesson?,
		/**
		 * 选择框覆盖的节数
		 * 有课程时等于该课程实际占用的节数(选框大小与课程一致), 空白处为下拉扩展选择框的单位数
		 */
		val len: Int = 1,
		/** 该选择所属周次(从0开始), 供按日期校验/写入使用 */
		val week: Int = -1
	)


	/**
	 * 【第三十轮】"被单击展开"的课程位置。
	 *
	 * 只记位置不记内容: 绘制时按位置重新取课程, 所以编辑/调休改了数据之后不会画旧内容。
	 */
	class ExpandTarget(val week: Int, val dayOfWeek: Int, val timeSlot: Int)

	/**
	 * 【第三十一轮】"原位拉长"的绘制方案(见 [buildExpandPlan])。
	 *
	 * 先算好再画, 是为了让 [drawView] 的主循环能**跳过**这一格 —— 只画一块底、一段文字,
	 * 而不是"原样画一遍 + 上面再盖一块 + 文字重画一遍"。
	 */
	private class ExpandPlan(
		val dayOfWeek: Int,
		val timeSlot: Int,
		/** 该格左边缘(列的左边界, 不含内边距) */
		val x: Int,
		/** 该格上边缘(节的上边界, 不含内边距) */
		val y: Int,
		/** 拉长后的块上边 */
		val top: Int,
		/** 拉长后的块下边 */
		val bottom: Int,
		val color: Int,
		val lessonType: Int,
		/** 重合课程提示 "第几门/共几门", 不重合为 null */
		val overlap: String?,
		/** 要画的全部行(null = 段间距) */
		val lines: List<String?>,
		/** 第一行文字的基线起点(与普通绘制完全一致: 第一行画在 startY + lineHeight 处) */
		val startY: Int,
		/** 这一块用的字号缩放(见 LessonData.textScale) */
		val textScale: Float = 1F
	)


	companion object {
		/**
		 * 课程间距
		 *
		 * 【本轮·小窗优化】3 -> 4: 课程色块与格子边缘、文字与色块边缘的空隙再给一点,
		 * 小窗里文字缩号之后不会显得贴边拥挤。(块坐标/选框/A 标记全部用这一个常量,
		 * 同步外扩, 几何不会错位。)
		 */
		private const val LESSON_PADDING = 4

		/** 把手命中范围的最小半高(dp): 手指没那么精准, 给足范围(太小会"拖不动") */
		private const val HANDLE_TOUCH_MIN_DP = 24f

		/** 把手命中范围在左右方向上额外放宽(dp) */
		private const val HANDLE_TOUCH_EXTRA_X = 16f

		/** [hitSelectHandle]: 不在选框上 */
		const val HANDLE_NONE = -1
		/** [hitSelectHandle]: 上边把手(拖动可向上扩大/向下缩小) */
		const val HANDLE_TOP = 0
		/** [hitSelectHandle]: 下边把手(拖动可向下扩大/向上缩小) */
		const val HANDLE_BOTTOM = 1
		/** [hitSelectHandle]: 框内(非把手) */
		const val HANDLE_INSIDE = 2

		/** 选中框颜色 */
		val HIGHLIGHT_BLUE: Int = Color.rgb(0, 176, 255)

		/** 重合课程的辅助框颜色 */
		val HIGHLIGHT_AMBER: Int = Color.rgb(255, 193, 7)

		/**
		 * 放假日期的半透明蒙层: 明确"这天放假", 但不遮挡课程内容(课程不被删除)
		 */
		val HOLIDAY_VEIL: Int = Color.argb(64, 128, 128, 128)

		/** 【问题6】是否给放假日期的整列加灰色蒙层(默认关闭: 背景保持原色, 只显示「放假」标记) */
		const val HOLIDAY_SHOW_VEIL: Boolean = false

		/**
		 * 放假标签底色
		 */
		val HOLIDAY_BADGE: Int = Color.rgb(229, 57, 53)
	}
}
