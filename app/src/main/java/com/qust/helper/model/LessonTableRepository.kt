/*
 * 青科助手（修改版） —— 基于开源项目 QUST-Assistant 修改而成
 *
 * 原项目版权所有 (C) 2891954521  (https://github.com/2891954521/QUST-Assistant)
 * 原始开源许可: GNU General Public License v3.0 (GPL-3.0), 许可原文见 LICENSE 文件, 保持完整未改动。
 *
 * 修改版版权所有 (C) 2026 zhangzhenzyc  (https://github.com/zhangzhenzyc)
 * 本修改版同样以 GPL-3.0 发布; 附加版权声明见 NOTICE.md —— 两份版权声明相互独立、同时生效。
 */

package com.qust.helper.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import com.qust.helper.data.Keys
import com.qust.helper.data.Setting
import com.qust.helper.data.api.QustApi
import com.qust.helper.data.lesson.Lesson
import com.qust.helper.data.lesson.LessonGroup
import com.qust.helper.data.lesson.LessonTable
import com.qust.helper.data.lesson.LessonTableQueryResult
import com.qust.helper.data.lesson.TimeTables
import com.qust.helper.model.account.EASAccount
import com.qust.helper.model.account.NeedLoginException
import com.qust.helper.model.account.ServerException
import com.qust.helper.utils.DateUtils
import com.qust.helper.utils.LessonTableDiag
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.coroutines.delay
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import okhttp3.FormBody
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.Calendar
import java.util.Date
import java.util.regex.Pattern


/**
 * 课表功能模块
 */
object LessonTableRepository {

	/**
	 * 当前时间表 0: 时间一 1: 时间二 2: 时间三
	 *
	 * 首次启动(用户从未手动切换过)时按当天日期自动挑选:
	 * 10月1日~次年端午节用"新冬季时间", 其余时间用"夏季时间"(有"新夏季时间"则用新的)。
	 * 用户一旦自己选过, 之后永远以用户的选择为准, 不再自动改。
	 */
	val _currentTimeTable = mutableIntStateOf(resolveInitialTimeTable())
	val currentTimeTable by _currentTimeTable

	private fun resolveInitialTimeTable(): Int {
		val saved = Setting.getInt(Keys.KEY_TIME_TABLE, -1)
		val userSet = Setting.getBoolean(Keys.KEY_TIME_TABLE_USER_SET, false)
		val seasonal = TimeTables.defaultIndexBySeason()
		// 用户手动选过 -> 直接用, 不再按季节改动
		if(saved >= 0 && userSet) {
			// 例外: 选中的是旧的内置时间(如"冬季时间"), 而同季节已经有更新的那套(如"新冬季时间") -> 换成新的
			val upgrade = TimeTables.newerPresetFor(saved, seasonal)
			if(upgrade >= 0) {
				if(upgrade != saved) Setting.edit { it.putInt(Keys.KEY_TIME_TABLE, upgrade) }
				return upgrade
			}
			return saved
		}
		if(saved != seasonal) Setting.edit { it.putInt(Keys.KEY_TIME_TABLE, seasonal) }
		return seasonal
	}

	/** 用户主动切换时间表: 记下"用户选过", 之后不再被季节默认值覆盖 */
	fun setTimeTableValue(value: Int) {
		_currentTimeTable.value = value
		Setting.edit {
			it.putInt(Keys.KEY_TIME_TABLE, value)
			it.putBoolean(Keys.KEY_TIME_TABLE_USER_SET, true)
		}
		// 【第五十五轮·按要求】"时间表改变后, 小组件的时间没有同步刷新":
		// 每日课表小组件是按**真实时间轴**画的, 换一套时间表(节次起止时间变了)就必须立刻重画。
		refreshLessonWidgetsForTimeTable()
	}

	/**
	 * 【第九十八轮·按要求】第 [index] 套时间表能否完整显示当前课表的全部课程。
	 *
	 * 判据: 目标时间表的节数 >= 课表实际占用的最大结束节次([LessonTable.maxUsedEndSlot])。
	 * 不满足时切过去必然有课程落到时间表之外(显示不全), 界面层应据此拦截切换并给出提示。
	 */
	fun timeTableFits(index: Int): Boolean =
		lessonTable.fitsTimeTable(TimeTables.getTimes(index)[0].size)

	/**
	 * 校正"当前使用的时间表"下标:
	 * 时间表可以被新增/删除, 因此下标可能越界; 越界时回退到最后一 套, 避免取不到时间。
	 */
	fun clampTimeTableIndex() {
		val count = TimeTables.size
		if(count <= 0) return
		if(_currentTimeTable.intValue !in 0 until count) {
			setTimeTableValue((count - 1).coerceAtLeast(0))
		}
	}

	/**
	 * 显示所有课程
	 */
	val _showAllLesson = mutableStateOf(Setting.getBoolean(Keys.KEY_SHOW_ALL_LESSON, true))
	val showAllLesson by _showAllLesson
	fun setShowAllLessonValue(value: Boolean) {
		_showAllLesson.value = value
		Setting.edit { it.putBoolean(Keys.KEY_SHOW_ALL_LESSON, value) }
	}

	/**
	 * 隐藏教师
	 *
	 * 默认**显示教师**(这里默认 false): 课表里要能直接看到任课老师。
	 * 老版本默认是 true 且已写进配置, 这里做一次性迁移, 之后按用户的选择为准。
	 */
	val _hideTeacher = mutableStateOf(run {
		if(!Setting.getBoolean("key_hide_teacher_migrated", false)) {
			Setting.edit {
				it.putBoolean(Keys.KEY_HIDE_TEACHER, false)
					.putBoolean("key_hide_teacher_migrated", true)
			}
		}
		Setting.getBoolean(Keys.KEY_HIDE_TEACHER, false)
	})
	val hideTeacher by _hideTeacher
	fun setHideTeacherValue(value: Boolean) {
		_hideTeacher.value = value
		Setting.edit { it.putBoolean(Keys.KEY_HIDE_TEACHER, value) }
		// 【第五十五轮·按要求】"软件打开隐藏教师时本周课表小组件也要同步隐藏":
		// 小组件是另一套渲染结果(RemoteViews), 不跟着 Compose 重组, 必须主动让它重画一次。
		refreshLessonWidgetsForTimeTable()
	}

	/**
	 * 【第五十五轮】让桌面上的两个课表小组件立刻重画
	 *
	 * 供"时间表被编辑/切换"、"隐藏教师开关"这类**不影响课表数据版本号**的改动调用
	 * (改课表数据仍然走 [notifyLessonTableChanged], 它自己会刷新)。
	 */
	fun refreshLessonWidgetsForTimeTable() {
		runCatching { refreshLessonWidgets() }
	}

	/**
	 * 锁定课表
	 */
	var _lockLesson = mutableStateOf(Setting.getBoolean(Keys.KEY_LOCK_LESSON, false))
	val lockLesson by _lockLesson
	fun setLockLessonValue(value: Boolean) {
		_lockLesson.value = value
		Setting.edit { it.putBoolean(Keys.KEY_LOCK_LESSON, value) }
	}

	/**
	 * 开学时间, 和LessonTable是同步更新的
	 */
	val _startDay = mutableStateOf(Date())
	val startDay by _startDay
	fun setStartDayValue(value: Date){
		_startDay.value = value
		lessonTable.startDay = value
		saveLessonTable()
	}

	/**
	 * 开学时间, 和LessonTable是同步更新的
	 */
	val _totalWeek = mutableIntStateOf(1)
	val totalWeek by _totalWeek
	fun setTotalWeekValue(value: Int){
		_totalWeek.intValue = value
		lessonTable.totalWeek = value
		saveLessonTable()
	}

	/**
	 * 当前周 (从1开始)
	 */
	var currentWeek = mutableIntStateOf(1)

	/**
	 * 当前星期 ( 0-6, 周一 —— 周日)
	 */
	var dayOfWeek = mutableIntStateOf(0)

	val _lessonTable = mutableStateOf(LessonTable(), neverEqualPolicy())
	val lessonTable by _lessonTable

	/**
	 * 课表数据版本号: 每次 notifyLessonTableChanged 自增, 用于强制界面重建渲染数据
	 * (仅比较对象引用无法感知"原地修改后重新赋值同一个对象", 故引入单调递增版本号)
	 */
	val _lessonTableVersion = mutableIntStateOf(0)
	val lessonTableVersion: Int
		get() = _lessonTableVersion.intValue

	/**
	 * 课表数据在原对象上被修改后, 通知所有观察的界面刷新
	 *
	 * 【第四十四轮·按要求】同时把桌面上的两个课表小组件**同步刷新**:
	 * 以前这里只刷新软件内界面, 桌面小组件要等系统周期更新(最长半小时)才跟着变。
	 * 现在直接发 `APPWIDGET_UPDATE` 广播(带当前的组件实例 id) —— 这条路是系统
	 * 周期更新走的同一条原生路径, 不依赖任何 Glance 内部状态, 最可靠。
	 */
	fun notifyLessonTableChanged() {
		_lessonTable.value = _lessonTable.value
		_lessonTableVersion.intValue++
		refreshLessonWidgets()
	}

	/**
	 * 广播刷新桌面上的课表小组件(每日课表 + 学期课表)
	 *
	 * 【第四十六轮】"直连先行、广播兜底": 直连(进程内直达 Glance)立即渲染, 稍后再发原生广播
	 * (系统周期更新走的就是这条路径)作兜底 —— MIUI 对 Glance 的 update 经常"返回成功但画面不动"。
	 *
	 * 【第五十六轮】改成多轮广播, 兜住"启动器正在重排/息屏, 第一次广播被丢掉"。
	 *
	 * 【第五十七轮·修"每日课表同步刷新速度太慢"】**合并**(debounce)重复请求 + 减少轮次。
	 * 【第五十八轮·还是嫌慢】上一版把 debounce 放在了**最前面**, 于是每一次刷新都要先白等
	 * 150ms 才开始渲染; 而且两个小组件是**串行** `updateAll` 的, 学期课表(7×11 格)算完才轮到
	 * 每日课表 —— 用户盯着看的那块反而最后才动。现在:
	 *  · 直连**立刻开跑**, 不再排队等 debounce;
	 *  · 两个小组件**并行**渲染(`launch` 各跑各的), 慢的那个不再拖住快的;
	 *  · debounce 只用来合并"广播兜底"(250ms 内的重复请求只发一次), 不挡住首帧;
	 *  · 广播自己也提前到 250ms(首帧都发出去了, 兜底没必要等 600ms)。
	 *
	 * 【第五十九轮·修"第一次同步很快, 第二次就很慢"】
	 * 上面那版还有一个洞: **每次调用都自己起一条协程**。同步课表的过程会连着触发好几次
	 * `notifyLessonTableChanged()`(每改一格一次), 于是第一遍只发 1~2 次、第二遍发十几次 ——
	 * 十几次"并行的全量 RemoteViews 重建"挤在一起, 每一次都要抢 Glance 的 session、
	 * 都要跨进程把一整棵视图树塞给启动器。第一遍快、第二遍慢, 就是这么来的。
	 *
	 * 现在改成**单跑者 + 攒批**(actor 模式):
	 *  · 全进程只有一条渲染协程在跑; 后来的请求只把计数 +1, 不再另起一条;
	 *  · 正在渲染时进来的请求会**攒起来**, 当前这一轮跑完再跑**一轮**(不是 N 轮);
	 *  · 手里没活了才停 —— 于是"改 20 格"从"20 次全量重建"变成"2 次", 与改几格无关。
	 */
	private val pendingRefreshes = java.util.concurrent.atomic.AtomicInteger(0)

	/** 是否已有渲染协程在跑(`AtomicBoolean`: 写在 IO 线程, 判在主线程) */
	private val rendering = java.util.concurrent.atomic.AtomicBoolean(false)

	private fun refreshLessonWidgets() {
		pendingRefreshes.incrementAndGet()
		// 已经有人在跑: 它跑完会把这一笔一起带走, 这里直接返回
		if(!rendering.compareAndSet(false, true)) return
		kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
			val ctx = runCatching { com.qust.helper.App.instance }.getOrNull()
			// 【第七十轮·五】"这一轮直连更新到底有没有真的重组合过" —— 见下面兜底广播前的判断
			var composeBefore = 0
			try {
				while(ctx != null) {
					// 把攒下的请求一次性取走(取到 0 说明没活了, 收工)
					if(pendingRefreshes.getAndSet(0) == 0) break
					val t0 = android.os.SystemClock.elapsedRealtime()
					composeBefore = com.qust.helper.ui.appwidget.TermLesson.composeSeq.get() +
						com.qust.helper.ui.appwidget.DailyLesson.composeSeq.get()
					// ① 进程内直达: **立刻**渲染; 两块课表并行, 谁先算完谁先上屏。
					runCatching {
						kotlinx.coroutines.coroutineScope {
							// 【第六十五轮·终】内容 lambda 现读数据, 但重组靠 tick 状态驱动 —— 先 +1
							launch {
								com.qust.helper.ui.appwidget.DailyLesson.refreshTick.value++
								com.qust.helper.ui.appwidget.DailyLesson.updateAll(ctx)
							}
							launch {
								com.qust.helper.ui.appwidget.TermLesson.refreshTick.value++
								com.qust.helper.ui.appwidget.TermLesson.updateAll(ctx)
							}
						}
					}
				}
				if(ctx != null) {
					// ② 广播兜底: 等一小会儿, 期间没有新请求才发, 避免重复广播。
					//
					// 【第七十轮·五·修"第 11 节整行不见 / 改完没反应"】这里以前**无条件**补发,
					// 而兜底广播落回来是 `onUpdate` → `updateAll()`, 等于同一块小组件被连着更新两次。
					// Glance 的会话是"后一次顶掉前一次": 直连那次刚组合出的 11 行画面还没送到桌面
					// 就被广播这次打断了 —— 日志里"兜底广播已发出"正好夹在"进入 provideGlance"和
					// "重组合"之间(22:56:32), 而桌面一直停在更早的 10 行帧, 就是这个原因。
					//
					// 现在: 直连那次**只要真的重组合过**, 就不再补发; 只有连组合都没发生
					// (Glance 没被唤醒/会话死了)才走兜底 —— 兜底还是兜底, 但不再自己打断自己。
					kotlinx.coroutines.delay(BROADCAST_DELAY_MS)
					val composed = com.qust.helper.ui.appwidget.TermLesson.composeSeq.get() +
						com.qust.helper.ui.appwidget.DailyLesson.composeSeq.get() > composeBefore
					if(pendingRefreshes.get() == 0 && !composed) {
						runCatching { broadcastWidgetUpdate(ctx) }
					}
				}
			}finally {
				rendering.set(false)
				// 收尾瞬间又来了请求: 交接给下一轮(少了这一句会漏刷)
				if(pendingRefreshes.get() > 0) refreshLessonWidgets()
			}
		}
	}

	/**
	 * 直连之后补一发原生广播的**等待时间**(毫秒)。
	 *
	 * 【第七十轮·五】250 → 1200: 现在这条广播只在"直连根本没组合"时才发,
	 * 判断需要给 Glance 一次完整的组合机会(实测同一秒内就完成, 1200ms 足够两轮)。
	 * 兜底来得晚一点没关系 —— 它兜的是"一次都没画"的极端情况, 不是常态。
	 */
	private const val BROADCAST_DELAY_MS = 1200L

	/** 给两个课表小组件的所有实例发一次原生 `ACTION_APPWIDGET_UPDATE`(系统周期更新走的就是这条路) */
	private fun broadcastWidgetUpdate(ctx: android.content.Context) {
		val mgr = android.appwidget.AppWidgetManager.getInstance(ctx)
		listOf(
			com.qust.helper.ui.appwidget.TermLessonReceiver::class.java,
			com.qust.helper.ui.appwidget.DailyLessonReceiver::class.java
		).forEach { cls ->
			runCatching {
				val cn = android.content.ComponentName(ctx, cls)
				val ids = mgr.getAppWidgetIds(cn)
				if(ids.isNotEmpty()) {
					ctx.sendBroadcast(
						android.content.Intent(ctx, cls)
							.setAction(android.appwidget.AppWidgetManager.ACTION_APPWIDGET_UPDATE)
							.putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
					)
				}
			}
		}
	}

	init {
		loadLesson()
		updateDate()
	}

	/**
	 * 更新日期信息(以周一为一周起点, 按天数差计算周次, 不受跨年 WEEK_OF_YEAR 边界影响)
	 */
	fun updateDate() {
		val today = Calendar.getInstance()
		dayOfWeek.intValue = today[Calendar.DAY_OF_WEEK].let { if(it == Calendar.SUNDAY) 6 else it - 2 }

		val todayMonday = (today.clone() as Calendar).apply {
			firstDayOfWeek = Calendar.MONDAY
			add(Calendar.DATE, if(get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) -6 else -(get(Calendar.DAY_OF_WEEK) - 2))
			set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
		}
		val startMonday = Calendar.getInstance().apply {
			time = startDay
			firstDayOfWeek = Calendar.MONDAY
			add(Calendar.DATE, if(get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) -6 else -(get(Calendar.DAY_OF_WEEK) - 2))
			set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
		}
		val diffDays = ((todayMonday.timeInMillis - startMonday.timeInMillis) / 86400000L).toInt()
		currentWeek.intValue = (diffDays / 7 + 1).coerceIn(1, totalWeek.coerceAtLeast(1))
	}

	/**
	 * 从本地文件初始化课表
	 */
	@OptIn(ExperimentalSerializationApi::class)
	private fun loadLesson() {
		if(!Setting.lessonTableFolder.exists()) {
			Setting.lessonTableFolder.mkdirs()
			return
		}

		// 【重启后课表不对 / 内容缺失】必须只读"课表本体"这一个文件!
		//
		// 该目录里除了 lessonTable, 还有 lessonTableOriginal("最新原始课表"快照)。
		// 以前是 listFiles() 取"第一个能解析的文件", 而 File.listFiles() 的返回顺序是不确定的,
		// 于是重启后有可能加载到那份快照 —— 表现就是"第二天打开软件课表变成最初导入的样子,
		// 调休、按日期修改的课程全都不见了"。
		val primary = File(Setting.lessonTableFolder, "lessonTable")
		val candidates: List<File> = if(primary.exists()) {
			listOf(primary)
		}else{
			// 兼容历史遗留: 本体不存在时才扫目录, 且必须排除所有"快照/备份"文件
			Setting.lessonTableFolder.listFiles()
				?.filter { it.isFile && it.name !in NON_LESSON_TABLE_FILES }
				?.sortedBy { it.name }
				?: emptyList()
		}

		for(file in candidates) {
			try {
				val loaded = FileInputStream(file).use { stream -> Json.decodeFromStream<LessonTable>(stream) }
				// 兼容旧版10节网格, 补齐到11节(含按日期修改的覆盖数据)
				loaded.normalize()
				_lessonTable.value = loaded
				_startDay.value = loaded.startDay
				_totalWeek.intValue = loaded.totalWeek
				// 【上课周数只剩 1 周】存储的"总周数"被意外写成 1(课程本身其实横跨多周),
				// 会导致整张课表翻不动周、编辑课程时也只有"第1周"可选。这里用**课程实际覆盖的
				// 最大周次**回填, 把"前几个版本"正常的周数恢复回来(课程数据没丢, 只是标量写错了)。
				if(loaded.totalWeek < 2) {
					val recovered = maxWeekOf(loaded)
					if(recovered > loaded.totalWeek) {
						loaded.totalWeek = recovered
						_totalWeek.intValue = recovered
					}
				}
				break
			}catch(_: Exception) { }
		}
	}

	/**
	 * 课程实际覆盖的最大周次(1-based); 没有任何课程返回 0。
	 * 用于"总周数被意外写成 1"时, 从课程自身的周次位掩码还原正确的学期周数。
	 */
	private fun maxWeekOf(table: LessonTable): Int {
		var max = 0
		for(daily in table.lessons) {
			for(group in daily) {
				group?.lessons?.forEach { lesson ->
					if(lesson.week != 0L) {
						// 最高置位 bit 的位置 + 1(位掩码第 i 位代表第 i+1 周)
						var w = lesson.week
						var bits = 0
						while(w != 0L) { bits++; w = w ushr 1 }
						if(bits > max) max = bits
					}
				}
			}
		}
		return max
	}

	/** 课表目录里"不是课表本体"的文件(默认课表快照等), 加载时必须排除 */
	private val NON_LESSON_TABLE_FILES = setOf("lessonTableOriginal")

	@OptIn(ExperimentalSerializationApi::class)
	fun saveLessonTable(newLessonTable: LessonTable? = null, keepAdjustments: Boolean = true): Boolean{
		if(newLessonTable != null){
			if(keepAdjustments){
				// 保留已有课表的调休安排与按日期手动修改, 避免重新导入课程时丢失
				// (撤销"删除学期课表"时必须显式传 keepAdjustments = false, 否则备份里的
				//  调休/按日期修改会被当前(已清空)的数据覆盖掉)
				newLessonTable.overrides = _lessonTable.value.overrides
				newLessonTable.dayOverrides = _lessonTable.value.dayOverrides
				// 【本期】"教务通知调课写过的日期"必须一起保留: 刷新课表时若丢掉这些标记,
				// 下次"保存课表"就会把教务停掉的课当成"用户删除"补回来。
				newLessonTable.easDates = _lessonTable.value.easDates
				newLessonTable.easSuspended = _lessonTable.value.easSuspended
			}
			newLessonTable.normalize()
			_lessonTable.value = newLessonTable
		}

		// 去除上课周数为0的课程
		val lessonGroups: Array<Array<LessonGroup?>> = lessonTable.lessons
		for(dailyLesson in lessonGroups) {
			for(timeSlot in dailyLesson.indices) {
				val group: LessonGroup = dailyLesson[timeSlot] ?: continue
				if(group.lessons.isEmpty()){
					dailyLesson[timeSlot] = null
					continue
				}
				group.lessons = group.lessons.filter { it.week != 0L }.toTypedArray()
			}
		}

		// 【本期】按日期副本里也不能留"空课程组": 渲染时它会被画成一张没有文字的空白卡片,
		// 点开还是空表单(用户说的"空白课程")。这里统一清成 null(该节次没有课)。
		for(column in lessonTable.dayOverrides.values) {
			for(i in column.indices) {
				val group: LessonGroup = column[i] ?: continue
				if(group.lessons.isEmpty()) column[i] = null
			}
		}

		_startDay.value = lessonTable.startDay
		_totalWeek.intValue = lessonTable.totalWeek

		if(!Setting.lessonTableFolder.exists()) Setting.lessonTableFolder.mkdirs()
		val file = File(Setting.lessonTableFolder, "lessonTable")
		return try{
			FileOutputStream(file).use { stream ->
				Json.encodeToStream<LessonTable>(lessonTable, stream)
			}
			true
		}catch(_: Exception) {
			false
		}
	}


	/**
	 * 删除某一具体日期的课表(仅该日期)
	 * @return 是否成功
	 */
	fun deleteDayLessonTable(week: Int, dayOfWeek: Int): Boolean {
		return try {
			lessonTable.clearDayByDate(week, dayOfWeek)
			saveLessonTable()
			notifyLessonTableChanged()
			true
		}catch(e: Exception) {
			Logger.e(e)
			false
		}
	}

	/**
	 * 全部课表恢复默认
	 *
	 * 清除**所有的调休/放假标记**与**按日期手动修改过的课程**, 让课表回到"每周课表模板"的
	 * 原始状态(每周课程本身保留, 不会丢课)。重置前自动备份, 可通过 [restoreDeletedLessonTable] 撤销。
	 *
	 * @return 是否成功
	 */
	fun resetLessonTableToDefault(): Boolean {
		return try {
			deletedBackup = lessonTable.deepCopy()
			lessonTable.overrides.clear()
			lessonTable.dayOverrides.clear()
			lessonTable.easDates.clear()
			lessonTable.easSuspended.clear()
			saveLessonTable()
			notifyLessonTableChanged()
			true
		}catch(e: Exception) {
			Logger.e(e)
			false
		}
	}

	/**
	 * "最新原始课表"快照文件: 只保存每周课程本身, 不含调休与按日期修改。
	 * 每次「查询课表」成功都会整体覆盖它, 因此它永远是"最近一次查询到的、没做过任何修改的课表";
	 * 从未查询过时不会有这个文件。
	 */
	private val originalLessonTableFile: File
		get() = File(Setting.lessonTableFolder, "lessonTableOriginal")

	/** 是否存在可以一键恢复的"最新原始课表" */
	fun hasOriginalLessonTable(): Boolean = originalLessonTableFile.exists()

	/**
	 * 读取"最新原始课表"快照(只读, 不修改文件)。
	 * 用于"当前课表 vs 最新原始课表"比对 —— 调休应用前判断某日是否与最新原始课表不一致。
	 */
	fun getOriginalLessonTable(): LessonTable? = loadOriginalLessonTable()

	/**
	 * 切换账号登录成功后清空旧账号的课表缓存文件(每周课表 + 默认课表快照),
	 * 避免串号: 新账号打开课表时不会看到上一个账号的课表。
	 */
	fun clearSavedLessonTableForNewAccount(){
		try {
			// 【修改版】最新原始课表快照长久保存: 切换账号只清每周课表缓存, 不再删除"最新原始课表"文件
			File(Setting.lessonTableFolder, "lessonTable").delete()
		}catch(e: Exception){ Logger.e(e) }
	}

	/**
	 * 保存"最新原始课表"快照(每次「查询课表」成功后调用, **整体覆盖**上一次的快照)
	 *
	 * 快照只包含**每周课程本身**, 不含调休/放假标记与按日期修改, 因此可以分别恢复出
	 * "调休前"与"调休后"两套课表。
	 */
	@OptIn(ExperimentalSerializationApi::class)
	fun saveOriginalLessonTable(table: LessonTable = lessonTable): Boolean {
		return try {
			if(!Setting.lessonTableFolder.exists()) Setting.lessonTableFolder.mkdirs()
			val copy = table.deepCopy()
			copy.overrides = HashMap()
			copy.dayOverrides = HashMap()
			FileOutputStream(originalLessonTableFile).use { stream ->
				Json.encodeToStream<LessonTable>(copy, stream)
			}
			true
		}catch(e: Exception) {
			Logger.e(e)
			false
		}
	}

	/** 读取"最新原始课表"快照 */
	@OptIn(ExperimentalSerializationApi::class)
	private fun loadOriginalLessonTable(): LessonTable? {
		return try {
			if(!originalLessonTableFile.exists()) return null
			FileInputStream(originalLessonTableFile).use { stream ->
				Json.decodeFromStream<LessonTable>(stream)
			}
		}catch(e: Exception) {
			Logger.e(e)
			null
		}
	}

	/**
	 * 【第九十五轮】判断课程除课程颜色外, 是否与"最新原始课表"(最近一次查询导入的课表,
	 * 见 [saveOriginalLessonTable])中同一时段的原始课程完全一致 —— 用于"U 恢复成 A"。
	 *
	 * 比对内容: 课程名称/教师/教室/上课节数/上课周数(位掩码完全相等), **不比对课程颜色**。
	 * 原始课表里同一格可能有多门课(重合导入), 与其中任意一门完全一致即算恢复。
	 */
	fun matchesOriginalLesson(dayOfWeek: Int, slot: Int, lesson: Lesson): Boolean {
		if(lesson.name.isEmpty()) return false
		val original = loadOriginalLessonTable() ?: return false
		val group = original.lessons.getOrNull(dayOfWeek)?.getOrNull(slot) ?: return false
		return group.lessons.any { l ->
			l.type == 0 && l.name == lesson.name && l.place == lesson.place &&
				l.teacher == lesson.teacher && l.len == lesson.len && l.week == lesson.week
		}
	}

	/**
	 * 一键恢复默认课表(即最近一次查询到的、未做任何修改的课表, 见 [saveOriginalLessonTable])
	 *
	 * 【第三十五轮】扩展为四个组合(调休前/后 × 调课前/后):
	 * - keepAdjustments = true(**调休后**): 保留现有 调休/放假 标记(overrides 0..8)及其按日期副本;
	 *                       false(**调休前**): 调休/放假标记与副本一并清除, 回到刚导入的样子;
	 * - keepEasChanges  = true(**调课后**): 保留 旧版"教务通知调课"标记(overrides==9)及其按日期副本
	 *                       【本期】教务调课已不再写该标记, 此项仅用于兼容历史数据;
	 *                       false(**调课前**): 教务调课与相关按日期副本一并清除。
	 * 【第六十轮】按要求: 恢复"调休课表"时**不再保留"用户手动修改的课程"**
	 * (编辑/拖动课表产生的、无任何调休/调课标记的按日期修改), 只保留调休安排与教务通知调课。
	 * @return 是否成功(没有快照时返回 false)
	 */
	fun restoreOriginalLessonTable(keepAdjustments: Boolean, keepEasChanges: Boolean): Boolean {
		val original = loadOriginalLessonTable() ?: return false
		return try {
			deletedBackup = lessonTable.deepCopy()
			val newOverrides = LinkedHashMap<String, Int>()
			val newDayOverrides = HashMap<String, Array<LessonGroup?>>()
			// 按标记类型分组保留: 9 = 旧版"教务通知调课"标记(仅兼容历史数据); 0..8 = 放假/补星期等调休安排
			for((k, v) in lessonTable.overrides){
				val dayCopy = lessonTable.dayOverrides[k]
				if(v == 9){
					if(keepEasChanges){
						newOverrides[k] = v
						dayCopy?.let { newDayOverrides[k] = it }
					}
				}else{
					if(keepAdjustments){
						newOverrides[k] = v
						// 【修改版】放假(0)日期: 保留放假标记, 但课表内容恢复到最新课表(不带按日期副本)
						if(v != 0) dayCopy?.let { newDayOverrides[k] = it }
					}
				}
			}
			// 【第六十轮】按要求: "用户手动修改的课程"(无任何标记的按日期修改)不再随"调休课表"保留 ——
			// 恢复后这些日期回到最初查询导入的课程; 只保留上面的 调休/放假 与 教务通知调课。
			original.overrides = newOverrides
			original.dayOverrides = newDayOverrides
			saveLessonTable(original, keepAdjustments = false)
			notifyLessonTableChanged()
			true
		}catch(e: Exception) {
			Logger.e(e)
			false
		}
	}

	/**
	 * 撤销"删除学期课表"或"全部课表恢复默认"(恢复操作前的数据)
	 * @return 是否成功
	 */
	fun restoreDeletedLessonTable(): Boolean {
		val backup = deletedBackup ?: return false
		return try {
			saveLessonTable(backup, keepAdjustments = false)
			notifyLessonTableChanged()
			deletedBackup = null
			true
		}catch(e: Exception) {
			Logger.e(e)
			false
		}
	}

	/**
	 * 是否存在可撤销的"删除学期课表"备份
	 */
	fun hasDeletedBackup(): Boolean = deletedBackup != null

	/**
	 * 删除学期课表前的备份(仅内存, 用于同一次运行内的撤销)
	 */
	private var deletedBackup: LessonTable? = null

	/**
	 * 复制学年/学期信息(重试时用于构造新的结果对象)
	 */
	private fun LessonTableQueryResult.copyInfoFrom(from: LessonTableQueryResult): LessonTableQueryResult {
		termText = from.termText
		lessonTable.startDay = from.lessonTable.startDay
		lessonTable.totalWeek = from.lessonTable.totalWeek
		return this
	}

	/**
	 * 查询课表前的登录保障
	 *
	 * 教务系统的会话很短, 过期后直接请求只会拿到登录页 HTML, 解析时抛异常并最终只报一句
	 * "获取课表失败" —— 这就是"有时候会查询失败"的根因。这里先确认登录状态(必要时用保存的
	 * 账号密码自动登录), 失败时外层会重新登录并重试一次。
	 */
	private suspend fun ensureEasLogin(account: EASAccount, result: LessonTableQueryResult): Boolean {
		return try {
			account.checkLogin()
			true
		}catch(_: NeedLoginException){
			result.error = "请先登录教务系统"
			false
		}catch(e: Exception){
			Logger.e(e)
			// 【第三十九轮】站点不可达(网络/服务端维护)与"需要登录"区分: 前者提示稍后再试, 不引导去登录
			val m = e.message ?: ""
			result.error = if(m.contains("暂时没有访问权限") || m.contains("访问被拒绝(403)"))
				"暂时没有访问权限"
			else if(m.contains("网络或者远端服务器无响应") || m.contains("接口地址不存在(404)") ||
				m.contains("后端服务暂时不可用") || m.contains("服务正在维护或者过载") || m.contains("请求超时"))
				"「教务系统」暂时访问失败，请稍后重试"
			else "登录教务系统失败，请检查网络"
			false
		}
	}

	/**
	 * 业务性明确错误(无注册信息 / 未开放 / 需缴费 / 解析失败): 这些与"会话是否就绪"无关,
	 * 重试没有意义, 直接透出给用户。
	 */
	private fun isBusinessLessonError(msg: String?): Boolean {
		if(msg == null) return false
		return msg.contains("不允许查看") || msg.contains("缴费") || msg.contains("未开放") ||
			msg.contains("无注册信息") || msg.contains("解析课表信息失败") || msg.contains("无您的注册信息")
	}

	/**
	 * 查询课表信息
	 *
	 * 时序控制: 登录后调用方(见 EasAccountViewModel.login)会先做会话就绪校验, 确认会话生效才进来。
	 * 但这里仍保留"静默重试"作为兜底 —— 登录后初始化阶段偶尔会话 Cookie 尚未完全生效,
	 * 第一次请求会返回空课表 / 登录页 HTML; 此时**后台静默重试最多 2 次**(每次间隔 600ms),
	 * 不弹窗、不让用户重新登录。只有全部重试失败, 才把错误透出给上层弹窗。
	 *
	 * 5xx / 维护等真实服务端故障、以及业务性明确错误不重试。
	 *
	 * @param year 学年
	 * @param term 学期
	 */
	suspend fun queryLessonTable(easAccount: EASAccount, year: String, term: String, useSchoolCurrentTerm: Boolean = false): LessonTableQueryResult {
		// 【第二十七轮】课表查询日志: 追加到同一缓冲区(登录日志在前), 登录页长按"确定"可查看完整链路
		LessonTableDiag.add("==== 课表查询 ${LessonTableDiag.now()} ====")
		LessonTableDiag.add("参数: 学年代码=$year 学期代码=$term 教务节点=${easAccount.host}")

		val info = getSchoolYearData(easAccount)

		// 【第二十九轮】"按学校当前学期查询"(登录时使用): 从接口返回的学年信息(如"2026-2027学年1学期")
		// 解析出学校认定的当前学年/学期, 覆盖本地推算的参数 —— 保证取到该用户"最新"的课表,
		// 不再依赖"入学年份推算"的固定值(推算方法在留级/小学期等情况下可能不准)。
		var queryYear = year
		var queryTerm = term
		if(useSchoolCurrentTerm){
			val school = parseSchoolTerm(info.termText)
			if(school != null){
				queryYear = school.first
				queryTerm = school.second
				LessonTableDiag.add("按学校当前学期查询: xnm=$queryYear xqm=$queryTerm (来源: [${info.termText}])")
			}else{
				LessonTableDiag.add("学年信息无法解析, 沿用参数: xnm=$queryYear xqm=$queryTerm")
			}
		}
		var lastError = "获取课表失败"

		repeat(3){ attempt ->
			LessonTableDiag.add("-- 第${attempt + 1}次尝试 --")
			val result = LessonTableQueryResult().copyInfoFrom(info)
			if(!ensureEasLogin(easAccount, result)) {
				LessonTableDiag.add("登录态检查: 失败 -> ${result.error}")
				return result
			}
			LessonTableDiag.add("登录态检查: 正常")
			try {
			doQueryLessonTable(easAccount, queryYear, queryTerm, result)
			val err = result.error
			if(err == null) {
				val slotCount = result.lessonTable.activeSlotCount()
				LessonTableDiag.add("查询成功: 解析到 $slotCount 个有课时段, 开学时间=${DateUtils.YMD.format(result.lessonTable.startDay)}, 总周数=${result.lessonTable.totalWeek}")
				return result                       // 成功
			}
			LessonTableDiag.add("查询返回错误: $err")
			if(isBusinessLessonError(err)) return result        // 业务性错误不重试
			lastError = err                                     // 疑似会话未就绪导致的空结果: 静默重试
			}catch(e: ServerException){
				// 5xx 真实服务端故障: 不重试, 直接透出
				if(e.message?.contains("HTTP 5", ignoreCase = true) == true){
					Logger.e(e)
					LessonTableDiag.add("服务端故障(不重试): ${e.message}")
					return LessonTableQueryResult().copyInfoFrom(info).also {
						it.error = e.message ?: "教务系统服务器异常/正在维护，请稍后再尝试"
					}
				}
				// 否则多半是"会话未生效返回的登录页", 静默重试
				Logger.w("课表查询会话未就绪, 静默重试(${attempt + 1}/3)")
				LessonTableDiag.add("会话未就绪, 静默重试(${attempt + 1}/3): ${e.message}")
				lastError = e.message ?: lastError
			}catch(e: IOException){
				Logger.e(e)
				LessonTableDiag.add("网络异常: ${e.message}")
				lastError = "获取课表失败, 网络异常"
			}catch(e: Exception){
				Logger.e(e)
				LessonTableDiag.add("查询异常: ${e.message}")
				lastError = "获取课表失败"
			}
			if(attempt < 2) delay(600L)
		}

		LessonTableDiag.add("全部尝试失败: $lastError")
		return LessonTableQueryResult().copyInfoFrom(info).also { it.error = lastError }
	}

	/**
	 * 实际执行"个人课表"查询(结果写入 [result])
	 */
	private suspend fun doQueryLessonTable(
		easAccount: EASAccount, year: String, term: String, result: LessonTableQueryResult
	) {
		LessonTableDiag.add("POST ${QustApi.GET_LESSON_TABLE} (xnm=$year&xqm=$term&kzlx=ck)")
		easAccount.postNoCheck(
			QustApi.GET_LESSON_TABLE,
			FormBody.Builder()
				.add("xnm", year)
				.add("xqm", term)
				.add("kzlx", "ck")
				.build()
		).use { response ->
			LessonTableDiag.add("HTTP 状态码=${response.code}")
			// 【服务端故障】课表接口 5xx / 返回报错页, 直接抛 ServerException, 由上层决定不重试
			if(response.code >= 500) {
				LessonTableDiag.add("服务端 5xx, 不重试")
				throw ServerException("教务系统服务器异常(HTTP ${response.code})")
			}
			val html: String = response.body!!.string()
			LessonTableDiag.add("响应体长度=${html.length}, 前120字=${html.take(120).replace("\n", " ")}")
			if(html.trim().startsWith("<", ignoreCase = true) && !html.contains("xsxx")) {
				throw ServerException("教务系统服务器异常/正在维护，请稍后再尝试")
			}
			val js = JSONObject(html)
			if(!js.has("xsxx")) {
				LessonTableDiag.add("响应缺少 xsxx 字段(该学期无注册信息)")
				result.error = "获取课表失败：该学年学期无您的注册信息"
			}else if(js.optString("xnxqsfkz").toBoolean()) {
				LessonTableDiag.add("xnxqsfkz=true(该学期课表当前不允许查看)")
				result.error = "获取课表失败：该学年学期课表当前时间段不允许查看"
			}else{
				val kblen: Int = js.getJSONArray("kbList").length()
				val sjklen: Int = js.getJSONArray("sjkList").length()
				val jxhjkclen: Int = js.getJSONArray("jxhjkcList").length()
				val xkkg: Boolean = js.optBoolean("xkkg", false) // 选课开关
				val jfckbkg: Boolean = js.optBoolean("jfckbkg", false) // 缴费查课表开关
				LessonTableDiag.add("kbList=$kblen sjkList=$sjklen jxhjkcList=$jxhjkclen xkkg=$xkkg jfckbkg=$jfckbkg")
				if(kblen == 0 && sjklen == 0 && jxhjkclen == 0 && xkkg && jfckbkg) {
					result.error = "获取课表失败：该学年学期尚无您的课表"
				} else if(!xkkg) {
					result.error = "获取课表失败：该学年学期的课表尚未开放"
				} else if(!jfckbkg) {
					result.error = "获取课表失败：缴费后可查询"
				} else if(!result.lessonTable.loadFromJson(js)) {
					result.error = "解析课表信息失败"
				}
			}
		}
	}

	/**
	 * 匹配学年信息
	 */
	private val TIME_MATCHER = Pattern.compile("([0-9]{4}-[0-9]{4})学年([0-9])学期\\((\\d{4}-\\d{2}-\\d{2})至(\\d{4}-\\d{2}-\\d{2})\\)")

	/**
	 * 【第二十九轮】学年信息文本里的正式学年/学期("2026-2027学年1学期" -> xnm=2026, xqm=3)
	 */
	private val SCHOOL_TERM_PATTERN = Pattern.compile("(\\d{4})-(\\d{4})学年([12])学期")

	/**
	 * 【第二十九轮】从学年信息文本解析学校认定的"当前学年/学期"查询参数:
	 * "2026-2027学年1学期" -> ("2026", "3");  "…学年2学期" -> ("…", "12")。
	 * 解析不出返回 null(调用方沿用原参数)。
	 */
	private fun parseSchoolTerm(text: String): Pair<String, String>? {
		if(text.isEmpty()) return null
		val m = SCHOOL_TERM_PATTERN.matcher(text)
		if(!m.find()) return null
		val year = m.group(1) ?: return null
		val term = if(m.group(3) == "2") "12" else "3"
		return year to term
	}

	/**
	 * 获取学年信息
	 */
	private suspend fun getSchoolYearData(account: EASAccount, result: LessonTableQueryResult = LessonTableQueryResult()): LessonTableQueryResult {
		try {
			// 从教务获取本学年信息
			val response = account.getNoCheck(QustApi.EA_YEAR_DATA).use { it.body!!.string() }

			// 学年信息
			val matcher = TIME_MATCHER.matcher(response)
			if(matcher.find()) {
				val parsedStart = try { DateUtils.YMD.parse(matcher.group(3)!!)!! } catch(_: Exception) { null }
				val parsedEnd = try { DateUtils.YMD.parse(matcher.group(4)!!)!! } catch(_: Exception){ null }
				if(parsedStart != null && parsedEnd != null) {
					result.termText = matcher.group()
					result.lessonTable.startDay = parsedStart
					result.lessonTable.totalWeek = DateUtils.calcWeekOffset(parsedStart, parsedEnd).coerceAtLeast(1)
				} else {
					// 学年起止日期解析失败: 沿用已保存的开学时间与总周数, 避免被默认(当天/1周)覆盖
					result.lessonTable.startDay = startDay
					result.lessonTable.totalWeek = totalWeek
				}
			} else {
				// 没匹配到学年信息: 沿用已保存的值, 不让开学时间莫名变成当天、总周数变成 1
				result.lessonTable.startDay = startDay
				result.lessonTable.totalWeek = totalWeek
			}

			val doc: Document = Jsoup.parse(response)

			// 从thead里找出第1周是第几列
			var count = 0
			doc.getElementsByClass("tab-th-2").first().children().find {
				if(it.text() == "1") return@find true
				count++
				return@find false
			}

			// 从tbody的第一个tr标签（就是第一行）找到第n个td，其id就是需要的日期
			var find = 0
			doc.getElementsByTag("tbody").first().child(0).children().find{
				if(it.tagName() == "td"){
					if(find == count){
						result.lessonTable.startDay = DateUtils.YMD.parse(it.attr("id"))!!
						return@find true
					}
					find++
				}
				return@find false
			}
		} catch(e: Exception) {
			Logger.e(e)
			LessonTableDiag.add("学年数据解析异常: ${e.message}")
		}
		LessonTableDiag.add("学年信息: 学期文本=[${result.termText}] 开学时间=${DateUtils.YMD.format(result.lessonTable.startDay)} 总周数=${result.lessonTable.totalWeek}")
		return result
	}
}
