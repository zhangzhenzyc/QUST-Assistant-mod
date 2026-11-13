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

import android.graphics.Paint
import com.qust.helper.data.lesson.Lesson
import com.qust.helper.data.lesson.LessonGroup
import com.qust.helper.data.lesson.LessonTable
import java.util.Arrays

/**
 * 课程渲染信息
 */
class LessonRenderData(
	/**
	 * 隐藏教师
	 */
	private val hideTeacher: Boolean,
	private val textPaint: Paint,
	private val cellWidth: Int = 0,
	val lessonTable: LessonTable? = null,
	/**
	 * 【第五十九轮】单节格子的高度与一行文字的高度。
	 *
	 * 用来算"这一格实际能画几行" —— 以前课名能写几行是写死的(`len * 3 + 2` 个槽位),
	 * 与格子多高毫无关系, 于是 2 节的连堂课明明有一倍高度, 课名还是只给 5 行:
	 * "习近平新时代中国特色社会主…" 就这样被硬截断(用户原话"原来能全部展示课程名称的现在却展示不全")。
	 */
	private val cellHeight: Int = 0,
	private val textHeight: Int = 0,
	/**
	 * 【第六十一轮】格子顶部要留给 `A`/`U` 标签的高度(px), 由 [LessonRender] 按本机字体度量算。
	 *
	 * 它同时参与两件事, 缺一不可:
	 *  ① **算容量** —— 少了它, 会按"整格都能写字"去排, 排出来正好压在标签上;
	 *  ② **居中** —— 见 LessonRender.drawView(文字块在"扣掉这一条"的高度里居中)。
	 * 只做②不做①等于把最后一行挤出格子, 只做①不做②等于文字整体偏上。
	 */
	private val labelBand: Int = 0,
	/**
	 * 【第九十三轮】段间距(一行"空行")的实际绘制高度(px), 由 [LessonRender] 传入
	 * (它就是 `linePadding` = 4dp)。
	 *
	 * 旧版阶梯判据把一个空行按**一整行文字**扣, 而绘制端只画 4dp —— 差了 3 倍多:
	 * "课名折 3 行 + 教室 + 教师"实际只占 5 行高 + 2×4dp, 明明放得下,
	 * 却被整行预算判成"7 行 > 容量 6 行", 教师(复变函数与积分变换B / 概率论与数理统计)
	 * 就这样被冤枉地丢掉了。判据改成像素预算后(见 init 里的 [pxNeed]), 这部分"账面留白"
	 * 全部还给内容 —— 这就是用户说的"缩小留白", 字号阶梯本身不动(仍然 1.0 档优先)。
	 */
	private val gapPx: Int = 0
) {

	var lessons: Array<Array<LessonHolder?>> = Array(7) { arrayOfNulls(11) }

	/**
	 * 单日调休后的周数据缓存: 第几周(从0开始) -> 该周的每日课程数据
	 * 无调休的一周不会进入缓存, 直接使用 lessons
	 */
	private val overrideCache = HashMap<Int, Array<Array<LessonHolder?>>>()

	/**
	 * 计算绘制课程的信息
	 */
	fun calcLessonData() {
		overrideCache.clear()
		lessonTable?.let {
			val lessonGroups = it.lessons
			lessons = Array(lessonGroups.size) { arrayOfNulls(lessonGroups[0].size) }
			for(dayOfWeek in lessonGroups.indices) {
				for(timeSlot in lessonGroups[0].indices) {
					lessonGroups[dayOfWeek][timeSlot]?.let{ lessonGroup ->
						lessons[dayOfWeek][timeSlot] = LessonHolder(lessonGroup, it.totalWeek)
					}
				}
			}
		}
	}

	/**
	 * 获取某一周的每日课程数据(已应用单日调休与按日期手动覆盖)
	 * @param week 第几周, 从0开始
	 */
	fun lessonsForWeek(week: Int): Array<Array<LessonHolder?>> {
		val table = lessonTable ?: return lessons
		if(week < 0) return lessons
		if(table.overrides.isEmpty() && table.dayOverrides.isEmpty()) return lessons
		overrideCache[week]?.let { return it }
		val result = computeWeek(week, lessons, table)
		overrideCache[week] = result
		return result
	}

	/**
	 * 纯函数: 应用单日调休与按日期手动覆盖, 不依赖 Paint / 渲染状态, 便于单元测试
	 * 优先级: overrides 补课(1..7) > dayOverrides(按日期手动编辑) > base(每周模板)
	 *
	 * "放假"(overrides == 0) 只做标记, **不清空课程**: 该日期原本的课程照常返回,
	 * 使课程不丢失、仍可被复制, 由渲染层根据 [LessonTable.isHoliday] 显示放假样式。
	 *
	 * @param week 第几周, 从0开始
	 * @param base 基础(按星期索引)课程数据 lessons[星期][节次]
	 * @param table 课表(提供 overrides/dayOverrides 与 dateKeyOf)
	 * @return 该周每日课程数据; 无调整时直接返回 base 引用
	 */
	fun computeWeek(week: Int, base: Array<Array<LessonHolder?>>, table: LessonTable): Array<Array<LessonHolder?>> {
		if(table.overrides.isEmpty() && table.dayOverrides.isEmpty()) return base

		// 该周是否存在"会改变课程内容"的调整(放假日不改变课程内容, 故仅依赖 overrides 的显示层)
		var hasChange = false
		for(day in 0 until 7) {
			val key = table.dateKeyOf(week, day)
			if(table.dayOverrides.containsKey(key)) { hasChange = true; break }
			val action = table.getOverride(key)
			if(action != null && action != 0) { hasChange = true; break }
		}
		if(!hasChange) return base

		return Array(7) { day ->
			val key = table.dateKeyOf(week, day)
			val stored = table.dayOverrides[key]
			if(stored != null) {
				// 手动按日期覆盖: 优先级最高
				// 【防崩溃/防丢课】存储时的节数可能少于当前时间节数(换过时间表等),
				// 直接按 stored.size 建数组会让后面几节凭空消失、绘制时还会下标越界崩溃。
				// 这里把长度补齐到"当天的完整节数", 多出来的节次留空。
				val size = maxOf(stored.size, base[day].size)
				Array(size) { j -> stored.getOrNull(j)?.let { LessonHolder(it, table.totalWeek) } }
			}else{
				val action = table.getOverride(key)
				if(action != null && action in 1..7) {
					base[action - 1]                                  // 补星期 action 的课
				}else{
					// 放假(0)与无调休(null): 保持该日原有课程, 只由 [LessonRender] 画放假标记
					base[day]
				}
			}
		}
	}

	/**
	 * 从用于渲染的LessonHolder中获取课表中实际的Lesson
	 *
	 * 【防崩溃】按日期手动编辑过的某一天, 其数组长度可能与当前时间节数不一致(改过时间表、
	 * 或存储时用的节数更少); 周次也可能越界(查询到新学期后总周数变小)。
	 * 原来这里是无保护的 `!!` 与 `lessons[index[week]]`, 两种情况都会直接抛异常退出应用。
	 * 现在统一做越界保护, 取不到就返回 null, 由调用方处理。
	 *
	 * @return 该格对应的课程; 不存在(空白/越界/本周无课且查不到)时返回 null
	 */
	fun getLessonByHolder(week: Int, dayOfWeek: Int, timeSlot: Int): Lesson? {
		val dayData = lessonsForWeek(week)
		return dayData.getOrNull(dayOfWeek)?.getOrNull(timeSlot)?.getLesson(week)
	}

	/**
	 * 把一整段文字按当前列宽切成若干行(不设上限)。
	 *
	 * @param textSize 本次切分用的字号(px)。传 -1 表示用 paint 当前字号。
	 *   字号越小一行能塞的字越多 —— "放不下就缩字号"那条路要靠它重排。
	 */
	private fun wrap(src: String, textSize: Float = -1F): List<String> {
		if(src.isEmpty() || cellWidth <= 0) return emptyList()
		val old = textPaint.textSize
		if(textSize > 0F) textPaint.textSize = textSize
		try {
			val out = ArrayList<String>()
			var pos = 0
			val n = src.length
			while(pos < n && out.size < 64) {
				val count = textPaint.breakText(src, pos, n, true, cellWidth.toFloat(), null)
				// 切不动(宽度太小/字符异常)就整段塞进去, 避免死循环
				if(count <= 0) { out.add(src.substring(pos)); break }
				out.add(src.substring(pos, pos + count))
				pos += count
			}
			return out
		}finally{
			textPaint.textSize = old
		}
	}

	/** 指定字号下一行文字的基线偏移(与 LessonRender.setMeasureData 的公式一致) */
	private fun baselineFor(textSize: Float): Int {
		val old = textPaint.textSize
		textPaint.textSize = textSize
		val fm = textPaint.fontMetrics
		val baseLine = (textSize / 2 + (fm.descent - fm.ascent) / 2 - fm.descent).toInt()
		textPaint.textSize = old
		return baseLine
	}

	/**
	 * 字符串分行
	 * @param src 要分行的字符串
	 * @param des 分行后的字符串
	 * @param maxWidth 一行最大的长度
	 * @param desPos 目标数组开始的位置
	 * @param maxLine 最大支持的行数
	 */
	private fun splitString(src: String, des: Array<String?>, maxWidth: Int, desPos: Int, maxLine: Int): Int {
		var srcPos = 0
		var lines = 0
		val length = src.length
		var i = 0
		while(i < length) {
			i = srcPos + textPaint.breakText(src, i, length, true, maxWidth.toFloat(), null)
			if(desPos + lines >= des.size) return lines
			des[desPos + lines] = src.substring(srcPos, i)
			srcPos = i
			if(lines++ == maxLine) return lines
		}
		return lines
	}

	/**
	 * 【第三十轮】把一门课的名称/教室/教师按当前列宽**完整**分行, 不设行数上限。
	 *
	 * 平时绘制用的是固定容量的数组(容量 = len×3+2, 名称最多 l-3 行), 单节课的长课程名
	 * 根本放不下, 会被硬截断。用户单击某门课时, 用这个函数拿到全部行, 把课程块原位拉长
	 * 显示完整内容(见 LessonRender.drawExpandedLesson)。
	 *
	 * 【第三十一轮】返回的是"带段落间隔的完整行"(`null` = 课程名/教室/教师之间的段间距),
	 * 排列顺序与普通绘制用的 `LessonData.data` **完全一致** —— 所以前几行的基线坐标不变,
	 * 展开只是把原来被截掉的那几行接着往下补, 不会出现"两段文字叠在一起"。
	 */
	fun fullLineItems(lesson: Lesson, scale: Float = 1F): List<String?> {
		val out = ArrayList<String?>()
		if(cellWidth <= 0) return out
		val textSize = if(scale in 0.3F..1.0F) textPaint.textSize * scale else textPaint.textSize
		appendAllItems(lesson.name, out, 40, textSize)
		if(lesson.place.isNotEmpty()) {
			out.add(null)
			appendAllItems(lesson.place, out, 6, textSize)
		}
		if(!hideTeacher && lesson.teacher.isNotEmpty()) {
			out.add(null)
			appendAllItems(lesson.teacher, out, 6, textSize)
		}
		return out
	}

	/** 把 [src] 按 [cellWidth] 逐行切开, 追加到 [out], 最多 [limit] 行 */
	private fun appendAllItems(src: String, out: MutableList<String?>, limit: Int, textSize: Float = -1F) {
		val old = textPaint.textSize
		if(textSize > 0F) textPaint.textSize = textSize
		try {
			val length = src.length
			var srcPos = 0
			var count = 0
			var i = 0
			while(i < length && count < limit) {
				i = srcPos + textPaint.breakText(src, i, length, true, cellWidth.toFloat(), null)
				// 切不动(宽度太小/字符异常)就整段塞进去, 避免死循环
				if(i <= srcPos) { out.add(src.substring(srcPos)); return }
				out.add(src.substring(srcPos, i))
				srcPos = i
				count++
			}
		}finally{
			textPaint.textSize = old
		}
	}

	/**
	 * 课程组
	 */
	inner class LessonHolder(
		val group: LessonGroup,
		val totalWeek: Int
	) {

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
			val array = ArrayList<LessonData>(group.lessons.size)
			group.lessons.forEachIndexed { i, lesson ->
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

		/** 周次是否合法(总周数可能被查询到的新学期改小, 越界一律按"无课"处理) */
		private fun inRange(week: Int): Boolean = week in 0 until totalWeek

		/**
		 * 获取当前周正在展示的课程
		 *
		 * @return 该格本周正在上的课; 本周没课或周次越界返回 null
		 */
		fun getLesson(week: Int): Lesson? {
			if(!inRange(week)) return null
			val i = index[week]
			return if(i in group.lessons.indices) group.lessons[i] else null
		}

		/** 本周该格同时有几节课(周次越界返回 0) */
		fun countAt(week: Int): Int = if(inRange(week)) lessonCount[week] else 0

		/** 本周正在展示第几节课(周次越界返回 -1) */
		fun indexAt(week: Int): Int = if(inRange(week)) index[week] else -1

		fun current(week: Int): LessonData? {
			if(!inRange(week)) return null
			return if(lessonCount[week] == 0) null else lessonData.getOrNull(index[week])
		}

		/**
		 * 选中该格中的某一节课(切换该周展示/编辑的课程)
		 * @return 是否找到该课程
		 */
		fun focus(week: Int, lesson: Lesson): Boolean {
			if(week !in 0 until totalWeek) return false
			val i = group.lessons.indexOfFirst { it === lesson }
			if(i < 0) return false
			index[week] = i
			return true
		}

		/**
		 * 查找**之后**会上的课程(灰显用)
		 *
		 * 【以教务系统课表为准】只按课程自己的"上课周次"判断本周有没有课:
		 * 本周有课 -> 正常显示; 本周没课但**之后**还会上 -> 灰色显示;
		 * 已经上完(只在前面的周) -> 不显示(教务系统这一周同样没有这门课)。
		 * 之前的版本还会往前翻把已上过的课补进来, 那属于"课表乱加课", 不采用。
		 *
		 * @param week 当前周
		 */
		fun findLesson(week: Int): LessonData? {
			if(!inRange(week)) return null
			if(index[week] != -1) return lessonData.getOrNull(index[week])

			// 只向后(未来)查找课程
			for(i in (week + 1) until lessonCount.size) {
				if(lessonTime[i] > 0) {
					val pos = java.lang.Long.numberOfTrailingZeros(lessonTime[i].toLong())
					index[week] = pos
					return lessonData.getOrNull(pos)
				}
			}
			return null
		}
	}

	/**
	 * 课程信息
	 * @param type 课程类型
	 * @param len 课程长度
	 * @param color 课程颜色
	 */
	inner class LessonData(
		lesson: Lesson,
		val type: Int = lesson.type,
		val len: Int = lesson.len,
		val color: Int = lesson.color,
	) {
		/**
		 * 文本信息的行数(不含段间距)
		 */
		var lines: Int = 0

		/**
		 * [data] 里真正有内容的长度(含段间距的空位)。
		 *
		 * 绘制时只画到这里 —— 以前是按 `data.indices` 全画, 容量用不完的尾部空格会各占
		 * 一个 [LessonRender.linePadding], 文字看上去比居中位置偏上几 dp。
		 */
		var used: Int = 0

		/** 段间距个数: 用来把文字在格子里竖直居中 */
		val gaps: Int get() = (used - lines).coerceAtLeast(0)

		/**
		 * 【第六十轮】这一块**有没有缺内容**(课名/教室/教师 有一个没显示全就是 true)。
		 *
		 * 用"画出来的字数 vs 应有字数"逐字比对得出, 不靠行数猜 —— 上一版用
		 * `完整行数 > 已画行数` 判断, 缩过字号之后两边口径不一致, 于是出现
		 * "明明没缺内容, 点一下却变长"和"真的缺内容, 点一下没反应"两种错判(用户都撞上了)。
		 *
		 * [LessonRender.buildExpandPlan] 只看这一个标志: 能完整展示的**不展开**,
		 * 缺内容的才展开(用户原话:"如果学期课表原长度能够展示应当展示的全部内容点击后无需变长")。
		 */
		var truncated: Boolean = false

		/** 课程文本信息 */
		var data: Array<String?> = emptyArray()

		/**
		 * 【第五十九轮·二】本块实际用的字号缩放(1.0 = 基准 12sp, 0.72 = 最小)。
		 *
		 * 绘制时必须按这个比例设 `paint.textSize`, 否则"算行数时用 10sp、画的时候用 12sp",
		 * 文字会直接溢出格子 —— 这也是"点了变长却看不出变化"的一个来源。
		 */
		var textScale: Float = 1F

		/** 本块实际用的行高(px), 已含缩放 */
		var lineHeight: Int = textHeight

		/** 本块实际用的第一行基线偏移(px), 已含缩放 */
		var baseline: Int = 0

	init {
		// 【第五十九轮】容量按"这一格能画几行"算, 不再固定 len*3+2
		val perLine = textHeight.coerceAtLeast(1)
		val capacity = if(cellWidth > 0 && cellHeight > 0) {
			((cellHeight * len - 6 - labelBand) / perLine).coerceIn(2, 64)
		}else{
			len * 3 + if(hideTeacher) 1 else 2
		}
		if(cellWidth > 0) {
			val baseSize = textPaint.textSize
			val placeSrc = if(lesson.place.isEmpty()) "" else lesson.place
			val teacherSrc = if(hideTeacher || lesson.teacher.isEmpty()) "" else lesson.teacher

			/**
			 * 【第七十八轮·按要求"课程名称和教室正常情况下没点开时, 一定要显示全"】
			 *
			 * v0196 的"字号恒 1.0、装不下就截断"被用户否了: 长课名(习近平新时代…概论)和
			 * 长教室在 1.0 倍下放不下时出现省略号 —— 优先级明确为 **显示全 > 字号 1.0**。
			 *
			 * 【第八十轮·按用户要求"要让它在正常字体下, 不用点击就显示全部的课程名称, 教室和教师"】
			 * 阶梯重排: 上一版在**同一档字号里**先试"三样全画"、再试"丢教师", 于是
			 * "1.0 倍放不下教师"的课(如 积极心理学导论(英语))永远停在"丢教师 + 1.0 大字" ——
			 * 明明缩一档字号(0.94)教师就放得下, 却再也不出现。
			 * 新阶梯:
			 *   ① **跨全部档位**找"课名 + 教室 + 教师"都放得下的, 从 1.0 往下第一档就是它
			 *      (绝大多数课在 1.0 命中, 字号不变; 积极心理在 0.94 命中 —— 字号几乎不变, 教师回来了);
			 *   ② 所有档位都放不下教师 -> 退回"丢教师保课名 + 教室"(教师有独立开关, 本来就是次要信息);
			 *   ③ 下限 0.52 仍放不下 -> 教室尽量完整, 课名末行省略号(点开看全)。
			 */
			val scales = floatArrayOf(1F, 0.94F, 0.88F, 0.82F, 0.76F, 0.70F, 0.64F, 0.58F, 0.52F)
			fun capAt(sc: Float): Int =
				((cellHeight * len - 6 - labelBand) / (perLine * sc)).toInt().coerceIn(2, 64)

			// 【第九十三轮·按要求"课名 ≤12 字要在不点开的情况下显示全部信息(含教师),
			// 且字号和其他课程一样; >12 字的无需一定显示教师; 可以缩小留白来实现"】
			//
			// 容量判据从"整行数"改成**像素预算**: 空行(段间距)在绘制端只占 [gapPx](4dp),
			// 旧判据却按一整行扣 —— "课名 3 行 + 教室 + 教师"账面 7 行 > 容量 6 行,
			// 实际墨迹只有 5×行高 + 2×4dp ≈ 202px, 可用高度 ≈246px, 白白富余 44px。
			// 改成像素预算后这 44px 还给内容: 短课名在 **1.0 档**(与其他课完全同字号)
			// 就能连教师一起放下, 不再需要为塞内容降字号 —— 这正是"缩小留白"的含义。
			//
			// 判据仍然保守: 可用高度沿用"(格高×len - 6px 上下余量 - 标签带)", 而绘制端
			// 实际居中空间是"格高×len - labelInkBottom", 比这里还大 ~20px, 不会贴底。
			val availPx = (cellHeight * len - 6 - labelBand).coerceAtLeast(1)
			fun pxNeed(n: Int, p: Int, t: Int, rowH: Float): Float =
				(n + p + t) * rowH +
					((if(p > 0) 1 else 0) + (if(t > 0) 1 else 0)) * gapPx.coerceAtLeast(0)

			var chosen = scales.first()
			var nameLines: List<String> = wrap(lesson.name, baseSize)
			var placeLines: List<String> = if(placeSrc.isEmpty()) emptyList() else wrap(placeSrc, baseSize)
			var teacherLines: List<String> = if(teacherSrc.isEmpty()) emptyList() else wrap(teacherSrc, baseSize)
			var cap = capAt(chosen)

			// 【第八十九轮·按要求"课程字号要尽量正常, 教师可以不完全显示"】阶梯再重排:
			//   ① 先找"课名 + 教室全显"的**最高档字号** —— 字号优先级高于教师,
			//      上一版"为塞下教师连降几档"(习概缩得很小)的排法废除;
			//   ② 该字号下若还有富余行把教师也放得下, 就把教师一并画上(不再为教师降字号);
			//   ③ 连 0.52 档都放不下课名/教室 -> 教室尽量完整, 课名末行省略号(点开看全)。
			fun fitNamePlace(): Boolean {
				for(sc in scales) {
					val s = baseSize * sc
					val nl = wrap(lesson.name, s)
					val pl = if(placeSrc.isEmpty()) emptyList() else wrap(placeSrc, s)
					if(pxNeed(nl.size, pl.size, 0, perLine * sc) <= availPx) {
						chosen = sc; nameLines = nl; placeLines = pl; teacherLines = emptyList(); cap = capAt(sc)
						return true
					}
				}
				return false
			}
			when {
				fitNamePlace() -> {
					// 该字号下教师放得下就带上(同一字号, 不再为教师降档)。
					// 【第九十三轮】判据同样改像素预算: "课名 3 行 + 教室 + 教师"这类
					// 整行预算下的"假超载"(复变函数与积分变换B / 概率论与数理统计)现在
					// 在 1.0 档就能连教师一起画下, 字号与其他课程完全一致。
					if(teacherSrc.isNotEmpty()) {
						val tl = wrap(teacherSrc, baseSize * chosen)
						// 行高与写盘/绘制取同一舍入口径: (perLine×chosen) 取整
						val rowH = (perLine * chosen).toInt().coerceAtLeast(1).toFloat()
						if(pxNeed(nameLines.size, placeLines.size, tl.size, rowH) <= availPx) teacherLines = tl
					}
				}
				else -> {
					// 下限仍放不下: 教室尽量完整, 课名截断(写盘阶段按剩余行数截)
					chosen = scales.last()
					nameLines = wrap(lesson.name, baseSize * chosen)
					placeLines = if(placeSrc.isEmpty()) emptyList() else wrap(placeSrc, baseSize * chosen)
					teacherLines = emptyList()
					cap = capAt(chosen)
				}
			}
			val size = baseSize * chosen

			textScale = chosen
			lineHeight = (perLine * chosen).toInt().coerceAtLeast(1)
			baseline = baselineFor(size)

			// 【第九十三轮】数组按"像素预算下的实际行数"分配(文字行 + 段间距空行),
			// 与整行容量 cap 取大者 —— 1.0 档像素放得下的布局(文字行数可能比 cap 多 1~2 个
			// 空行槽位)不会被数组截掉; 兜底截断路径仍按 cap 走。
			val rowH = (perLine * chosen).toInt().coerceAtLeast(1)
			val totalRows = nameLines.size + placeLines.size + teacherLines.size +
				(if(placeLines.isNotEmpty()) 1 else 0) + (if(teacherLines.isNotEmpty()) 1 else 0)
			data = arrayOfNulls(maxOf(cap, totalRows))
			// ── 写盘: 课名 -> 空行 -> 教室(完整) -> 空行 -> 教师(若阶梯里保留) ──
			var nameCount = nameLines.size
			var placeTake = placeLines.size
			var teacherTake = teacherLines.size
			// 【第九十三轮】写盘前的安全闸同样改像素口径 —— 旧整行闸会把像素判据
			// 刚救回来的教师再砍掉一遍(复变函数: 像素 202px 放得下, 整行账面 7>6 又删了)。
			if(pxNeed(nameCount, placeTake, teacherTake, rowH.toFloat()) > availPx) teacherTake = 0
			if(pxNeed(nameCount, placeTake, teacherTake, rowH.toFloat()) > availPx) {
				// 课名放不下(物理极限): 教室优先保留完整, 课名只画剩下的行数(末行省略号)
				val reservePx = pxNeed(0, placeTake, teacherTake, rowH.toFloat())
				nameCount = ((availPx - reservePx) / rowH).toInt().coerceAtLeast(1)
			}
			var p = 0
			for(k in 0 until nameCount) {
				if(p >= data.size) break
				// 放不下的最后一行补省略号: 至少能让用户看出"还有字", 而不是莫名其妙断在半句
				val cut = k == nameCount - 1 && nameCount < nameLines.size
				data[p++] = if(cut) nameLines[k] + "…" else nameLines[k]
			}
			// 地点: 只画分配到的行数(阶梯里已算好是 2 行、1 行还是 0 行)
			if(placeTake > 0 && p < data.size - 1) {
				data[p++] = null
				for(k in 0 until placeTake) { if(p < data.size) data[p++] = placeLines[k] }
			}
			// 教师是次要信息: 阶梯里放不下时 teacherTake 已经是 0
			if(teacherTake > 0 && p < data.size - 1) {
				if(p < data.size) data[p++] = null
				for(k in 0 until teacherTake) { if(p < data.size) data[p++] = teacherLines[k] }
			}
			used = p
			lines = (0 until used).count { data[it] != null }

			// 【第六十轮】"到底有没有显示全": 画出来的字数 vs 应有字数(课名 + 教室 + 教师)。
			// 只末尾的省略号不算内容, 从计数里扣掉。
			var shownChars = 0
			for(k in 0 until used) {
				val line = data[k] ?: continue
				shownChars += if(line.endsWith("…")) line.length - 1 else line.length
			}
			truncated = shownChars != (lesson.name.length + placeSrc.length + teacherSrc.length)
		}else{
			// 还没测量到尺寸(首帧): 只给个占位数组, 不画字
			data = arrayOfNulls(capacity)
			lineHeight = perLine
		}
	}
	}
}

/**
 * 计算"下拉扩展选择框"的长度(单位节数)
 *
 * 以 slot0 为起点向下扩展, 最大不超过 maxUnits 个单位, 且不超出剩余节次。
 * 纯函数, 便于单元测试。
 *
 * @param slot0 起点节次(从0开始), 非法返回1
 * @param slot 当前拖拽到的节次
 * @param slotCount 时间总节数
 * @param maxUnits 允许的最大单位数(2、3、4 -> 上限4)
 * @return 选择框覆盖的节数, 至少为1
 */
fun selectionLength(slot0: Int, slot: Int, slotCount: Int, maxUnits: Int = 4): Int {
	if(slot0 < 0 || slotCount <= 0) return 1
	val down = maxOf(slot, slot0) - slot0 + 1
	val limit = minOf(maxUnits, (slotCount - slot0).coerceAtLeast(1))
	return down.coerceIn(1, limit)
}