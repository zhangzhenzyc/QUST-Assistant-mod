/*
 * 青科助手（修改版） —— 基于开源项目 QUST-Assistant 修改而成
 *
 * 原项目版权所有 (C) 2891954521  (https://github.com/2891954521/QUST-Assistant)
 * 原始开源许可: GNU General Public License v3.0 (GPL-3.0), 许可原文见 LICENSE 文件, 保持完整未改动。
 *
 * 修改版版权所有 (C) 2026 zhangzhenzyc  (https://github.com/zhangzhenzyc)
 * 本修改版同样以 GPL-3.0 发布; 附加版权声明见 NOTICE.md —— 两份版权声明相互独立、同时生效。
 */

package com.qust.helper.ui.page.lesson

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import com.qust.helper.data.Data
import com.qust.helper.data.Setting
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.qust.helper.R
import com.qust.helper.data.Keys
import com.qust.helper.data.Page
import com.qust.helper.data.lesson.Lesson
import com.qust.helper.data.lesson.LessonGroup
import com.qust.helper.data.lesson.LessonTable
import com.qust.helper.data.lesson.LessonTableSaveBuilder
import com.qust.helper.data.lesson.TimeTables
import com.qust.helper.data.eas.Notice
import com.qust.helper.model.LessonTableRepository
import com.qust.helper.model.Logger
import com.qust.helper.model.account.EASAccount
import com.qust.helper.model.account.NeedLoginException
import com.qust.helper.ui.theme.TEXT_ACTION_H_PADDING
import com.qust.helper.ui.theme.TEXT_ACTION_V_PADDING
import com.qust.helper.ui.theme.tapRipple
import com.qust.helper.ui.theme.textActionFilledColors
import com.qust.helper.ui.activity.BaseActivity
import com.qust.helper.ui.widget.Dialogs
import com.qust.helper.utils.DateUtils
import com.qust.helper.utils.Notices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.ArrayList
import org.jsoup.Jsoup
import java.util.Calendar
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 调休管理
 * 1. 从图片(OCR)识别调休安排
 * 2. 从青岛科技大学党委(校长)办公室官网同步放假/调休通知
 * 所有变更先弹出预览列表, 用户确认后才写入课表
 */
object ScheduleAdjust {

	/** 放假清空当日课程 */
	const val ACTION_CLEAR = 0

	// ---------------- 教务通知调课 诊断日志 ----------------
	// 每次同步(按钮点击/启动弹窗/长按日志)都会把全链路过程写进这里(仅内存, 不落盘),
	// 长按「教务通知调课」按钮可查看并一键复制, 用于排查"点了没有变化/没有解析出来"等问题。

	/** 最近一次「教务通知调课」同步的完整日志 */
	val easDebugLog = ArrayList<String>()

	private fun easLogClear(){
		synchronized(easDebugLog){ easDebugLog.clear() }
	}

	private fun easLog(line: String){
		synchronized(easDebugLog){
			easDebugLog.add(line)
			// 防止极端情况下日志无限增长: 最多保留最近 800 行
			while(easDebugLog.size > 800) easDebugLog.removeAt(0)
		}
	}

	data class ChangeItem(
		val dateKey: String,   // yyyy-MM-dd
		val action: Int,       // 0=放假(仅标记), 1-7=调休(按某个星期的课程上课)
		val desc: String,
		/**
		 * 【问题3-④】调休的"源日期"(yyyy-MM-dd)
		 *
		 * 通知里写明"10月18日执行9月28日的教学安排"时, 源日期就是 9月28日;
		 * 只知道"补星期三的课"时为 null, 此时按**目标日期所在周**的那个星期换算成具体日期。
		 * 无论哪种情况, 最终都以"具体日期"为操作对象, 不再直接用星期去复制。
		 */
		val sourceDateKey: String? = null
	)

	/**
	 * 教务通知「调课/补课」的细粒度变更(按"第N周星期W第A‑B节"定位, 精确到单门课/单节次)
	 *
	 * 与 [ChangeItem] 的"整日调休"不同, 教务通知的调课提醒/补课提醒是**节次级**的:
	 * - 调课(改地点): 只改某一门课在某一节次的教室, 其它课程、其它节次、其它周次都不动;
	 * - 补课(加课): 只在某一节次追加一门课, 该日其它节次、其它课程都保留。
	 * 因此用 dayOverrides(按日期的逐节次数组)做局部覆盖, 绝不整日替换。
	 * - 停课(取消课): 把某一节次里"该周次 + 该课程"的那节课从 dayOverride 移除, 只影响这一门、这一周。
	 *
	 * 弹窗范围: 仅处理「调课/补课/停课」三类节次级变更; 放假/考试/纯通知等不在此列。
	 *
	 * @param kind [KIND_LOCATION]=改地点(调课), [KIND_MAKEUP]=加课(补课), [KIND_SUSPEND]=停课
	 * @param week 第几周(从1开始, 与通知原文一致)
	 * @param dayOfWeek 星期几(0=周一, 与 [LessonTable] 一致)
	 * @param slotStart/slotEnd 节次(从1开始, 含两端)
	 * @param oldPlace/newPlace 改地点时的旧/新教室(加课/停课时为空)
	 */
	/** 【第四十八轮】最近一次"教务通知调课"同步到的候选通知原文(供启动弹窗展示"教务通知原文") */
	val lastEASNoticeText = StringBuilder()

	data class EASNoticeChange(
		val dateKey: String,
		val week: Int,
		val dayOfWeek: Int,
		val slotStart: Int,
		val slotEnd: Int,
		val courseName: String,
		val teacher: String,
		val kind: Int,
		val oldPlace: String = "",
		val newPlace: String = "",
		/** 【需求4】该变更所属教务通知的发布时间(来自 Notice.time / cjsj), 用于"一周内"判断与去重 */
		val time: String = ""
	) {
		companion object {
			const val KIND_LOCATION = 1
			const val KIND_MAKEUP = 2
			const val KIND_SUSPEND = 3
		}
	}

	// ---------------- 文本解析 ----------------

	private val WEEK_CHAR = mapOf('一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '日' to 7, '天' to 7)

	/** 教务通知"第N周星期W第A‑B节"中的星期汉字 -> 0=周一 */
	private val EAS_WEEK_IDX = mapOf('一' to 0, '二' to 1, '三' to 2, '四' to 3, '五' to 4, '六' to 5, '日' to 6)

	/** 节次连接符字符类: 兼容全角/半角连字符、波浪号、长破折号(教务通知里常是 U+2011 非断开连字符) */
	private const val EAS_SLOT_SEP = "[\\u2011\\-\\u2014~\\uff5e]"

	/**
	 * 调课提醒(改地点):
	 * "X老师于第N周星期W第A‑B节在<旧地点>上的<课程>课程调课到由X老师在第N周星期W第A‑B节<新地点>上课"
	 * 组: 1=周 2=星期 3=起节 4=止节 5=旧地点 6=课程名 7=新地点
	 */
	private val EAS_MOVE = Regex("第(\\d{1,2})周星期([一二三四五六日])第(\\d{1,2})$EAS_SLOT_SEP(\\d{1,2})节在(.+?)上的(.+?)课程调课到.{0,80}?第\\d{1,2}周星期[一二三四五六日]第\\d{1,2}$EAS_SLOT_SEP\\d{1,2}节(.+?)上课")

	/**
	 * 补课提醒(加课):
	 * "X老师将在第N周星期W第A‑B节对课程<课程>进行补课"
	 * 组: 1=周 2=星期 3=起节 4=止节 5=课程名
	 */
	private val EAS_MAKEUP = Regex("第(\\d{1,2})周星期([一二三四五六日])第(\\d{1,2})$EAS_SLOT_SEP(\\d{1,2})节对课程(.+?)进行补课")

	/**
	 * 停课提醒(取消某门课)
	 *
	 * 【第二十二轮·真机日志】教务实际的停课句式是:
	 *   "停课提醒:原定王琳老师在第2周星期一第3-4节于弘E403上的复变函数与积分变换B课程停上，请各位同学相互告知！"
	 * —— 课程名在"上的"与"课程"之间、结尾是"停上"而非"停课", 且"节"与"课程"之间可能隔着地点等很多字符,
	 * 旧版 `.{0,12}?课程(.+?)停课` 完全匹配不到(日志里多条停课提醒"未解析出")。
	 * 同时保留兼容旧句式("对课程X进行停课"/"课程X停课")。
	 * 组: 1=周 2=星期 3=起节 4=止节 5=课程名(新句式) 6=课程名(旧句式, 可能带"进行"后缀)
	 */
	private val EAS_SUSPEND = Regex("第(\\d{1,2})周星期([一二三四五六日])第(\\d{1,2})$EAS_SLOT_SEP(\\d{1,2})节.{0,80}?(?:上的([^，。；;、]{1,40}?)课程停(?:上|课)|课程([^，。；;、]{1,40}?)停(?:上|课))")

	/**
	 * 上课老师(出现在"第N周..."之前): "陈静老师于" / "樊梦迪老师将" / "原定王琳老师在第N周..."
	 * 说明: 不再要求"老师"后必须跟"于/将"(停课通知是"老师在第N周"); 捕获结果再由 [teacherBefore]
	 * 去掉"调课提醒:/补课提醒:"等前缀与"原定"两字。
	 */
	private val EAS_TEACHER = Regex("([^，。、\\s]{1,10})老师")

	/**
	 * 括号里的星期标注(纯注释), 会在 [cleanText] 中被整段删掉, 否则会挡住日期区间的匹配
	 * 覆盖: （周五）/（星期日）/(周六)/【周三】/[周二]
	 *
	 * 结尾括号也允许缺失(OCR 常把右括号认丢), 否则"9月25日（周五至10月7日放假"这类文本
	 * 会因为括注没被清掉而匹配不出日期区间。
	 */
	private val WEEK_ANNOTATION = Regex("[（(【\\[〈<]\\s*(?:星期|周)?\\s*[一二三四五六日天]\\s*[）)】\\]〉>]?")

	/**
	 * 日期区间 —— **"至"表示一个阶段(起止之间的每一天), 不是只有起止两天**
	 *
	 * 支持: 9月25日至10月7日 / 10月1日至7日(省略月份) / 5月1日—5日 / 6月19日到21日
	 *
	 * 日期与连接符之间还允许夹着一段括号注释(OCR 可能没被 [cleanText] 清干净)。
	 */
	private val RANGE_DATE = Regex(
		"(\\d{1,2})月(\\d{1,2})日(?:[（(【\\[〈<][^）)】\\]〉>]{0,8}[）)】\\]〉>]?)?" +
			"[至到圣刭一\\-—―–－~～〜]{1,2}" +
			"(?:(\\d{1,2})月)?(\\d{1,2})日"
	)

	/** 单个日期: 9月25日 */
	private val SINGLE_DATE = Regex("(\\d{1,2})月(\\d{1,2})日")

	/** 判定"放假"的关键词(命中整句即视为放假区间/放假日) */
	private val HOLIDAY_WORDS = arrayOf("放假", "停课", "休假", "假期")

	/** 判定"调课/补课"的关键词 */
	private val MAKEUP_WORDS = arrayOf("执行", "按照", "参照", "依据", "补", "调至", "调到", "调整到", "移至", "移到", "改到", "换到")

	/** 判定"上班/正常上班"(这些日期不应被标为放假) */
	private val WORK_WORDS = arrayOf("上班", "正常上班")

	/** 找"上班"关键词 */
	private val WORK_MATCHER = Regex("上班")

	/**
	 * 计算"上班日列表"所在的位置区间
	 *
	 * 通知里形如"9月20日（周日）、10月10日（周六）…上班"。
	 * **不能按整句收集**: OCR 会把截图里的换行/句号丢掉, 整篇通知会变成"一句话",
	 * 那样只要文中出现过一次"上班", 全篇所有日期(包括假期的最后一天)都会被当成上班日,
	 * 于是放假日期被大量删掉 —— 这正是"图片识别调休有部分识别不出来"。
	 *
	 * 这里以每个"上班"关键词为锚点, 只回看它**紧邻的一小段**(到最近的句末标点或
	 * "放假/调休/执行"等分句标记为止), 只有这一段里的日期才算上班日。
	 */
	private fun workWindows(text: String): List<IntRange> {
		val windows = ArrayList<IntRange>()
		for(m in WORK_MATCHER.findAll(text)) {
			val from = (m.range.first - 240).coerceAtLeast(0)
			val seg = text.substring(from, m.range.first)
			val lastCut = WORK_WINDOW_CUT.findAll(seg).lastOrNull()
			val start = if(lastCut == null) from else from + lastCut.range.last + 1
			windows.add(start until (m.range.last + 1))
		}
		return windows
	}

	/** 回看"上班"时的分句标记: 句末标点, 或另一个陈述的开始 */
	private val WORK_WINDOW_CUT = Regex("[。；;！？!?]|放假|休假|停课|假期|调休|执行|按照|参照")

	/** 一个假期最长天数, 超过则认为是误识别 */
	private const val MAX_HOLIDAY_DAYS = 45

	/** 执行某日教学安排: 10月18日(周日)执行9月28日(周一)的教学安排 */
	// 目标日期与"执行"之间的间隔不允许出现数字, 防止 OCR 丢标点后跨过"上班"列表里的其它日期
	private val EXEC_SCHEDULE = Regex("(\\d{1,2})月(\\d{1,2})日[^。；;\\d]{0,12}?执行(?:星期([一二三四五六日天])|(\\d{1,2})月(\\d{1,2})日)")

	/** 补/上星期几的课: 10月10日(周六)上星期三的课 / 9月28日补星期一的课 */
	private val MAKEUP_CLASS = Regex("(\\d{1,2})月(\\d{1,2})日[^。；;\\d]{0,12}?(?:补|上|按|修)星期([一二三四五六日天])")

	/** 星期几的课调至某日: 星期三的课调整到10月10日 */
	private val MOVE_CLASS = Regex("星期([一二三四五六日天])[^。；;\\d]{0,12}?(?:课程)?(?:调至|调到|调整到|移至|移到|改到|换到|换至)(\\d{1,2})月(\\d{1,2})日")

	/** 按某日的课表上课: 10月11日（周六）按照9月28日（周一）的课表上课 */
	private val FOLLOW_SCHEDULE = Regex("(\\d{1,2})月(\\d{1,2})日[^。；;\\d]{0,16}?(?:按照|参照|依据|按|执行)[^。；;\\d]{0,4}?(\\d{1,2})月(\\d{1,2})日")

	/** 补某日的课: 10月11日（周六）补（上）9月28日（周一）的教学安排 */
	private val MAKEUP_DATE = Regex("(\\d{1,2})月(\\d{1,2})日[^。；;\\d]{0,8}?(?:补上|补|上|借用)[^。；;\\d]{0,4}?(\\d{1,2})月(\\d{1,2})日")

	/** 在某位置之后8个字符内寻找 "星期X" */
	private fun weekDayAfter(text: String, from: Int): Int? {
		val seg = text.substring(from, (from + 10).coerceAtMost(text.length))
		val m = Regex("星期([一二三四五六日天])").find(seg) ?: return null
		return WEEK_CHAR[m.groupValues[1][0]]
	}

	private fun fmt(year: Int, month: Int, day: Int): String? {
		if(month !in 1..12 || day !in 1..31) return null
		val c = Calendar.getInstance()
		c.clear()
		c.set(year, month - 1, day)
		try {
			c.time
		} catch(_: Exception) { return null }
		return DateUtils.YMD.format(c.time)
	}

	private fun weekdayOf(dateKey: String): Int {
		return try {
			val c = Calendar.getInstance()
			c.time = DateUtils.YMD.parse(dateKey)!!
			when(c[Calendar.DAY_OF_WEEK]){
				Calendar.MONDAY -> 1
				Calendar.TUESDAY -> 2
				Calendar.WEDNESDAY -> 3
				Calendar.THURSDAY -> 4
				Calendar.FRIDAY -> 5
				Calendar.SATURDAY -> 6
				else -> 7
			}
		}catch(_: Exception){ 1 }
	}

	/**
	 * 归一化文本
	 *
	 * OCR 识别截图时常见问题都会在这里被抹平:
	 * - 同一句话被拆到多行 / 字与字之间插入空格
	 * - 全角数字、全角括号
	 * - "日"被识别成"曰"/"冃"
	 * - "周X"被写成"礼拜X"
	 * - 日期后面带"（周X）"注释, 会阻断"9月25日至10月7日"这类区间匹配
	 */
	private fun cleanText(raw: String): String {
		return raw
			// 全角数字 -> 半角
			.replace(Regex("[０-９]")) { ('0' + (it.value[0] - '０')).toString() }
			// 去掉所有空白(含换行/制表): 中文通知没有有意义的空格, 直接去掉最稳
			.replace(Regex("\\s+"), "")
			// OCR 常见混淆字
			.replace('曰', '日')
			.replace('冃', '日')
			.replace("號", "号")
			// 【识别不出调休】OCR 常把简体关键字识别成繁体/异体字:
			// "執行"匹配不到"执行"、“調課”匹配不到"调课", 整条调课安排就丢了。
			// 这里把关键词涉及的繁/异体字统一回简体
			.replace('執', '执').replace('調', '调').replace('補', '补').replace('課', '课')
			.replace('學', '学').replace('級', '级').replace('節', '节').replace('間', '间')
			.replace('於', '于').replace('實', '实').replace('現', '现').replace('時', '时')
			.replace('開', '开').replace('師', '师').replace('務', '务').replace('處', '处')
			.replace('週', '周').replace('迴', '周')
			// OCR 把课程名后误识别的乱码(如"大学物理实验知！"的"知！")
			.replace("知！", "").replace("知!", "")
			// "X月X号" 统一成 "X月X日"
			.replace(Regex("(\\d{1,2})月(\\d{1,2})号"), "$1月$2日")
			.replace("礼拜", "星期")
			.replace(Regex("周([一二三四五六日天])"), "星期$1")
			.replace("星期天", "星期日")
			// 去掉括号里的星期标注(纯注释), 否则会挡住日期区间的匹配
			.replace(WEEK_ANNOTATION, "")
			// 【识别不出阶段】有的通知/截图写的是中文数字日期(九月二十五日至十月七日),
			// 数字正则完全匹配不到, 这里先统一成阿拉伯数字
			.replace(CN_DATE) { m ->
				val month = cnNumber(m.groupValues[1])
				val day = cnNumber(m.groupValues[2])
				if(month in 1..12 && day in 1..31) "${month}月${day}日" else m.value
			}
			// "9月25日起" / "10月7日止" 里的起止字会挡住区间匹配
			.replace(Regex("(\\d{1,2}日)(起|开始|为止|止)"), "$1")
	}

	/** 中文数字(〇零一二两三四五六七八九十)组成的日期 */
	private val CN_DATE = Regex("([〇零一二两三四五六七八九十]{1,3})月([〇零一二两三四五六七八九十]{1,3})[日号號]")

	private val CN_DIGIT = mapOf(
		'〇' to 0, '零' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
		'五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9
	)

	/** 把"二十五"这样的中文数字转成 25; 无法解析返回 -1 */
	private fun cnNumber(text: String): Int {
		var tens = 0
		var number = 0
		for(ch in text){
			when {
				ch == '十' -> { tens += (if(number == 0) 1 else number) * 10; number = 0 }
				CN_DIGIT.containsKey(ch) -> number = CN_DIGIT.getValue(ch)
				else -> return -1
			}
		}
		return tens + number
	}

	/**
	 * 展开一个日期区间(起止之间的**每一天**)
	 * @return 实际写入的天数; 0 表示区间异常(误识别)被丢弃
	 */
	private fun expandRange(out: MutableMap<String, Int>, startYear: Int, sm: Int, sd: Int, em: Int, ed: Int): Int {
		val start = Calendar.getInstance(); start.clear(); start.set(startYear, sm - 1, sd)
		// 结束日期小于起始日期说明跨月/跨年
		val endYear = if(em > sm || (em == sm && ed >= sd)) startYear else startYear + 1
		val end = Calendar.getInstance(); end.clear(); end.set(endYear, em - 1, ed)
		val days = ((end.timeInMillis - start.timeInMillis) / 86400000L).toInt() + 1
		if(days <= 0 || days > MAX_HOLIDAY_DAYS) return 0
		var count = 0
		while(!start.after(end)) {
			out[DateUtils.YMD.format(start.time)] = ACTION_CLEAR
			start.add(Calendar.DATE, 1)
			count++
		}
		return count
	}

	/**
	 * 解析通知/OCR文本中的调休信息
	 *
	 * 采用**逐句解析**: 中文通知用 。/；/;/换行 断句, 只有"日期"与"放假"落在同一句里才会被判定为放假,
	 * 这样"9月25日（周五）至10月7日（周三）放假调休，共13天"会被正确地展开为 9/25~10/7 的**全部13天**,
	 * 而不是只取起止两天; 同时"…（周日）上班"这类句子不会被误判成放假。
	 *
	 * @param raw 原始文本
	 * @param refYear 参考年份(当前年份)
	 * @param refMonth 参考月份(当前月份)
	 */
	/**
	 * 应用"放假"规则(作用域可以是整句, 也可以是整段文本)
	 * @return 本次写入的天数
	 */
	private fun applyHolidayRules(
		scope: String,
		out: MutableMap<String, Int>,
		yearOf: (Int) -> Int,
		dateKeyOf: (Int, Int) -> String?,
		/** 是否允许"隐式时间段"兜底(只在逐句解析时开启, 整段兜底时关闭以免把无关日期连成一片) */
		allowImplicitRange: Boolean = false
	): Int {
		var written = 0
		for(m in RANGE_DATE.findAll(scope)) {
			val sm = m.groupValues[1].toInt(); val sd = m.groupValues[2].toInt()
			// 结束日期可能省略月份(如"10月1日至7日"), 省略时沿用起始月份
			val em = m.groupValues[3].ifEmpty { sm.toString() }.toInt()
			val ed = m.groupValues[4].toInt()
			written += expandRange(out, yearOf(sm), sm, sd, em, ed)
		}

		// 逗号/顿号分隔的区间(如"9月25日至10月7日"被OCR拆开: 连接符认不出) → 按首尾日期补成时间段
		if(written == 0 && allowImplicitRange) {
			written += expandImplicitRange(scope, out, yearOf)
		}

		// 没有写区间时才按"单日放假"处理(如"1月1日放假")
		if(written == 0) {
			for(m in SINGLE_DATE.findAll(scope)) {
				val mm = m.groupValues[1].toInt(); val dd = m.groupValues[2].toInt()
				dateKeyOf(mm, dd)?.let{ out[it] = ACTION_CLEAR }
			}
		}
		return written
	}

	private fun inBlocked(spans: List<IntRange>, index: Int): Boolean = spans.any { index >= it.first && index <= it.last }

	/**
	 * 隐式时间段: 句子里只有"日期…日期"而连接符(至/到/—)没被识别出来时,
	 * 把第一个日期到最后一个日期当成**一个阶段**处理
	 *
	 * 例如截图 OCR 把"9月25日至10月7日放假调休"识别成"9月25日10月7日放假调休",
	 * 逐条匹配只能得到两个孤立的日期; 这里补成 9/25~10/7 的整段假期。
	 *
	 * 含"上班/上课"或调课词(执行/补/调至…)的句子不参与, 避免把无关日期连成一片。
	 */
	private fun expandImplicitRange(scope: String, out: MutableMap<String, Int>, yearOf: (Int) -> Int): Int {
		var written = 0
		// 以每个"放假"关键词为中心取窗口(通知里"日期…日期放假"必然挨在一起),
		// 不再要求整句都干净 —— 否则 OCR 把换行吃掉后整篇变成一句, 兜底就永远不会生效。
		for(m in HOLIDAY_WORD_MATCHER.findAll(scope)) {
			val from = (m.range.first - 40).coerceAtLeast(0)
			val to = (m.range.last + 40).coerceAtMost(scope.length - 1)
			val seg = scope.substring(from, to + 1)
			if(WORK_WORDS.any{ seg.contains(it) }) continue
			if(MAKEUP_WORDS.any{ seg.contains(it) }) continue
			val dates = SINGLE_DATE.findAll(seg)
				.map{ it.groupValues[1].toInt() to it.groupValues[2].toInt() }
				.toList()
			// 2~4 个日期才认为是"起止日期", 太多说明窗口跨了内容
			if(dates.size < 2 || dates.size > 4) continue
			val (sm, sd) = dates.first()
			val (em, ed) = dates.last()
			written += expandRange(out, yearOf(sm), sm, sd, em, ed)
		}
		return written
	}

	/** 找"放假/停课/休假/假期"关键词的位置 */
	private val HOLIDAY_WORD_MATCHER = Regex("放假|停课|休假|假期")

	/**
	 * 应用"调课/补课"规则
	 * @return 本次写入的条数
	 */
	private fun applyMakeupRules(
		scope: String,
		out: MutableMap<String, Int>,
		yearOf: (Int) -> Int,
		dateKeyOf: (Int, Int) -> String?,
		/** 目标日期 -> 源日期(通知里写明"执行X月X日"的情况) */
		sourceDates: MutableMap<String, String> = HashMap()
	): Int {
		var written = 0

		// 1) 执行某日教学安排 (官网通知格式): 10月18日执行9月28日的
		for(m in EXEC_SCHEDULE.findAll(scope)) {
			val mm = m.groupValues[1].toInt(); val dd = m.groupValues[2].toInt()
			val dateKey = dateKeyOf(mm, dd) ?: continue
			// 依次: 文中明确的"执行星期X" / 日期后的"星期X" / 目标日期推断星期
			val week: Int? = if(m.groupValues[3].isNotEmpty()) WEEK_CHAR[m.groupValues[3][0]]
			else weekDayAfter(scope, m.range.last + 1) ?: run {
				if(m.groupValues[4].isNotEmpty()){
					val tm = m.groupValues[4].toInt(); val td = m.groupValues[5].toInt()
					fmt(yearOf(tm), tm, td)?.let{ k -> weekdayOf(k) }
				}else null
			}
			if(week == null) continue
			out[dateKey] = week
			// 若文中写明了源日期(执行X月X日的教学安排), 记下来供"按具体日期"复制
			val srcDate = if(m.groupValues[4].isNotEmpty()){
				fmt(yearOf(m.groupValues[4].toInt()), m.groupValues[4].toInt(), m.groupValues[5].toInt())
			}else null
			if(srcDate != null) sourceDates[dateKey] = srcDate
			written++
		}

		// 2) 某日补/上星期几的课
		for(m in MAKEUP_CLASS.findAll(scope)) {
			val mm = m.groupValues[1].toInt(); val dd = m.groupValues[2].toInt()
			val key = dateKeyOf(mm, dd) ?: continue
			out[key] = WEEK_CHAR[m.groupValues[3][0]]!!
			written++
		}

		// 3) 星期几的课调至某日
		for(m in MOVE_CLASS.findAll(scope)) {
			val mm = m.groupValues[2].toInt(); val dd = m.groupValues[3].toInt()
			val key = dateKeyOf(mm, dd) ?: continue
			out[key] = WEEK_CHAR[m.groupValues[1][0]]!!
			written++
		}

		// 4) 按/补 某个具体日期的课表
		for(regex in arrayOf(FOLLOW_SCHEDULE, MAKEUP_DATE)) {
			for(m in regex.findAll(scope)) {
				val mm = m.groupValues[1].toInt(); val dd = m.groupValues[2].toInt()
				val dateKey = dateKeyOf(mm, dd) ?: continue
				val sm = m.groupValues[3].toInt(); val sd = m.groupValues[4].toInt()
				val srcKey = fmt(yearOf(sm), sm, sd) ?: continue
				if(srcKey == dateKey) continue
				out[dateKey] = weekdayOf(srcKey)
				sourceDates[dateKey] = srcKey
				written++
			}
		}
		return written
	}

	/**
	 * 解析通知/OCR文本中的调休信息
	 *
	 * 解析分两个"识别阶段":
	 * 1. **逐句识别**(首选): 只有"日期"与"放假"落在同一句里才判定该句为放假,
	 *    这样"9月25日（周五）至10月7日（周三）放假调休，共13天"会被正确展开为 9/25~10/7 的
	 *    **全部13天**(而不是只取起止两天), 同时"…（周日）上班"这类句子不会被误判成放假。
	 * 2. **整段兜底识别**: 截图 OCR 常把一句话拆得七零八落(甚至把"放假"识别到另一行),
	 *    逐句匹配会漏掉, 这时在整个文本里再扫一遍, 尽量把假期时间段识别出来。
	 *
	 * @param raw 原始文本
	 * @param refYear 参考年份(当前年份)
	 * @param refMonth 参考月份(当前月份)
	 */
	fun parseText(raw: String, refYear: Int, refMonth: Int): List<ChangeItem> {
		// 预处理: 归一化文本(去空白/统一"星期"表述/去掉括号里的星期注释等)
		val text = cleanText(raw)

		// 通知里写明的年份优先(如"关于2026年中秋节、国庆节放假安排的通知")
		val explicitYear = Regex("(2\\d{3})年").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()

		/** 推断年份: 有明确年份直接用; 否则按"8月及以后算当年, 1-7月在下半年发布算次年" */
		val yearOf: (Int) -> Int = { month ->
			if(explicitYear != null) explicitYear
			else if(month >= 8) refYear
			else if(refMonth >= 8) refYear + 1
			else refYear
		}

		val dateKeyOf: (Int, Int) -> String? = { month, day -> fmt(yearOf(month), month, day) }

		val result = LinkedHashMap<String, Int>()   // dateKey -> action
		val workDates = HashSet<String>()           // 明确"上班"的日期(不放假)
		val sourceDates = HashMap<String, String>()  // 目标日期 -> 源日期【问题3-④】

		val clauses = text.split(Regex("[。；;\\n]+")).map{ it.trim() }.filter{ it.isNotEmpty() }

		/** "上班"日列表所在的位置区间(必须在整段兜底之前算好) */
		val workSpans = workWindows(text)

		// ---------- 阶段一: 逐句识别 ----------
		for(clause in clauses) {
			if(HOLIDAY_WORDS.any{ clause.contains(it) }) applyHolidayRules(clause, result, yearOf, dateKeyOf, allowImplicitRange = true)
			if(MAKEUP_WORDS.any{ clause.contains(it) }) applyMakeupRules(clause, result, yearOf, dateKeyOf, sourceDates)
		}

		// ---------- 阶段二: 整段兜底(OCR 断行/断句时补漏) ----------
		// 以前只有在"一条都没识别到"时才兜底, 于是"识别出一部分、漏掉另一部分"的情况永远补不齐
		// —— 这正是"图片识别调休还是有部分没能识别出来"。现在改为**每次都补一遍**:
		//   * 只补尚未识别到的日期, 不覆盖逐句阶段的判定结果;
		//   * 跳过含"上班/上课"的句子所在位置(那些日期本来就不放假)。
		if(HOLIDAY_WORDS.any{ text.contains(it) } || MAKEUP_WORDS.any{ text.contains(it) }) {
			applyMakeupRules(text, result, yearOf, dateKeyOf, sourceDates)

			val blocked = workSpans
			val extra = LinkedHashMap<String, Int>()
			for(m in RANGE_DATE.findAll(text)) {
				if(inBlocked(blocked, m.range.first)) continue
				val sm = m.groupValues[1].toInt(); val sd = m.groupValues[2].toInt()
				val em = m.groupValues[3].ifEmpty { sm.toString() }.toInt()
				val ed = m.groupValues[4].toInt()
				expandRange(extra, yearOf(sm), sm, sd, em, ed)
			}
			// 极端情况: 整段里只剩孤立的日期(连接符被 OCR 丢掉), 且没有任何上班/调课字样时才补阶段
			if(extra.isEmpty() && blocked.isEmpty()
				&& WORK_WORDS.none{ text.contains(it) } && MAKEUP_WORDS.none{ text.contains(it) }) {
				expandImplicitRange(text, extra, yearOf)
			}
			for((key, value) in extra) if(!result.containsKey(key)) result[key] = value
		}

		// ---------- 记录"上班"日(不放假) ----------
		// 只认"上班"关键词紧邻的那一小段里的日期(见 workWindows 的说明)
		for(span in workSpans) {
			for(m in SINGLE_DATE.findAll(text.substring(span.first, span.last + 1))) {
				val mm = m.groupValues[1].toInt(); val dd = m.groupValues[2].toInt()
				dateKeyOf(mm, dd)?.let{ workDates.add(it) }
			}
		}
		for(key in workDates) {
			if(result[key] == ACTION_CLEAR) result.remove(key)
		}

		return result.map{ (k, v) -> ChangeItem(k, v, describe(k, v), sourceDates[k]) }.sortedBy{ it.dateKey }
	}

	/** 生成一条调休安排的说明文字 */
	private fun describe(dateKey: String, action: Int): String {
		val c = Calendar.getInstance(); c.time = DateUtils.YMD.parse(dateKey)!!
		val head = "${c[Calendar.MONTH] + 1}月${c[Calendar.DATE]}日（周${"一二三四五六日"[weekdayOf(dateKey) - 1]}）："
		// 【问题3-②】不再出现"补星期X的课": 调休一律以具体日期展示(实际课程列表由 previewLines 提供)
		return head + when(action) {
			ACTION_CLEAR -> "放假"
			else -> "调休"
		}
	}

	// ---------------- 官网同步 ----------------

	private const val OFFICE_LIST_URL = "https://office.qust.edu.cn/index/zxtz.htm"

	/**
	 * 官网"最新通知"列表里, 哪些标题算"放假/调休通知"
	 *
	 * 只认「放假/调休/节假日」会漏掉《关于2026年国庆节、中秋节放假…》这类把节日名写在前面、
	 * 标题里没有"放假"二字的通知(学校确实这么写过), 因此把常见节日名也算进来。
	 * 命中标题只是"值得打开看看", 真正决定有没有调休的仍是正文解析(parseText)。
	 */
	private val OFFICE_NOTICE_TITLE_WORDS = arrayOf(
		"放假", "调休", "节假日", "国庆", "中秋", "元旦", "春节", "清明", "端午", "劳动节"
	)

	/**
	 * 官网同步只处理"最近"的调休: 早于今天该天数的日期不再同步
	 *
	 * 官网通知列表里同时挂着往年(2022/2024/2025…)的放假通知, 若全部同步会把早已过去的
	 * 假期日期写进课表; 因此只取**最近的一条**通知, 并丢弃超过该天数的历史日期。
	 */
	const val SYNC_MAX_PAST_DAYS = 30

	/**
	 * 过滤掉"时间过久"的调休安排(只保留今天之前的 [SYNC_MAX_PAST_DAYS] 天以内以及将来的日期)
	 */
	fun filterRecentChanges(changes: List<ChangeItem>, now: Calendar = Calendar.getInstance()): List<ChangeItem> {
		val limit = Calendar.getInstance().apply {
			time = now.time
			set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
			add(Calendar.DATE, -SYNC_MAX_PAST_DAYS)
		}.time
		return changes.filter { item ->
			val date = DateUtils.YMD.parse(item.dateKey) ?: return@filter false
			!date.before(limit)
		}
	}

	/**
	 * 从青岛科技大学党委(校长)办公室官网"最新通知"抓取放假/调休通知并解析
	 * 阻塞网络IO, 请在协程 Dispatchers.IO 中调用
	 * @throws Exception 网络异常
	 */
	fun fetchWebChanges(): List<ChangeItem> {
		val client = OkHttpClient.Builder()
			.connectTimeout(10, TimeUnit.SECONDS)
			.readTimeout(15, TimeUnit.SECONDS)
			.build()

		val listHtml = client.newCall(
			okhttp3.Request.Builder().url(OFFICE_LIST_URL).build()
		).execute().use{ response ->
			if(!response.isSuccessful) throw RuntimeException("HTTP ${response.code}")
			response.body!!.string()
		}

		val listDoc = Jsoup.parse(listHtml, OFFICE_LIST_URL)
		val links = listDoc.select("a[href]").filter{ el ->
			val title = el.text()
			el.attr("href").contains("/info/") &&
				OFFICE_NOTICE_TITLE_WORDS.any { title.contains(it) }
		}

		val now = Calendar.getInstance()
		val changes = LinkedHashMap<String, ChangeItem>()
		for(el in links){
			try {
				val articleHtml = client.newCall(
					okhttp3.Request.Builder().url(el.attr("abs:href")).build()
				).execute().use{ r ->
					if(!r.isSuccessful) throw RuntimeException("HTTP ${r.code}")
					r.body!!.string()
				}
				val doc = Jsoup.parse(articleHtml, el.attr("abs:href"))
				// 官网正文容器: 优先 v_news_content, 其次 news_con
				val content = (
					doc.select(".v_news_content").firstOrNull()
						?: doc.select(".news_con").firstOrNull()
						?: doc.body()
					).text()
				val items = parseText(content, now[Calendar.YEAR], now[Calendar.MONTH] + 1)
				if(items.isNotEmpty()) {
					for(item in items) changes[item.dateKey] = item
					// 链接按时间倒序排列: 只处理**最近的一条**通知, 不再往回翻往年通知
					break
				}
			}catch(_: Exception){ }
		}

		// 时间过久的调休不再同步
		val recent = filterRecentChanges(changes.values.toList(), now)
		if(recent.isEmpty()) throw RuntimeException("官网暂无近期的调休安排")
		return recent.sortedBy{ it.dateKey }
	}

	/**
	 * 从教务系统「通知」读取**调课/补课**安排并解析(节次级, 与「科大官网同步调休」按钮体验对齐)
	 *
	 * 教务通知的调课/补课是"第N周星期W第A‑B节"格式、精确到单门课单节次, 因此走独立的
	 * [parseEASNotice](节次解析) + [applyEASChanges](按日期逐节次覆盖) 管线, 与官网的
	 * 整日放假/调休解析(parseText/applyChanges)互不干扰。
	 *
	 * 阻塞网络IO, 请在协程 Dispatchers.IO 中调用
	 * @throws NeedLoginException 未登录教务系统
	 * @throws Exception 网络异常 / 暂无近期调课补课
	 */
	// 【需求10】已移除 webChangeAlreadySatisfied 过度过滤: 其"目标日已与源日一致则视为无需处理"
	// 的判定会把官网调休同步从 18 项误降到 16 项。现恢复为仅按"是否已写入该调休(action)"过滤,
	// 保证所有真实调休都能进入预览, 恢复 18 项识别准确度。

	suspend fun fetchEASNoticeChanges(): List<EASNoticeChange> {
		easLogClear()
		synchronized(lastEASNoticeText){ lastEASNoticeText.clear() }
		val account = EASAccount.getInstance()
		val table = LessonTableRepository.lessonTable
		val now = Calendar.getInstance()
		easLog("==== 教务通知调课 同步日志 ${now[Calendar.YEAR]}-${now[Calendar.MONTH] + 1}-${now[Calendar.DATE]} ${now[Calendar.HOUR_OF_DAY]}:${now[Calendar.MINUTE]} ====")
		easLog("课表: 开学日=${DateUtils.YMD.format(table.startDay)} 总周数=${table.totalWeek} 已有调休=${table.overrides.size}条")
		if(!account.checkLogin()) {
			easLog("登录检查: 未登录 -> 提示先登录教务系统")
			throw NeedLoginException()
		}
		easLog("登录检查: 已登录")
		// 【需求3/需求8】分页抓取全部教务通知: 默认只取前若干条会漏掉"显示不全"的消息。
		// 加大每页条数与页数(最多 15 页 × 30 条 = 450 条), 确保一个月内/本学期的
		// 调课/补课/停课通知都能进入合并解析, 不被分页截断而漏掉。
		val all = ArrayList<Notice>()
		var page = 1
		while(page <= 15) {
			val batch = account.queryNotice(page, 30)
			easLog("第${page}页教务通知: ${batch.size} 条")
			if(batch.isEmpty()) break
			all.addAll(batch)
			if(batch.size < 30) break
			page++
		}
		val notices = all
		if(notices.isEmpty()) {
			easLog("!! 教务通知一条都没取到: 常见原因是教务系统登录态失效(需重新登录)或教务节点网络异常")
			throw RuntimeException("教务通知暂未发现调课/补课/停课安排")
		}
		easLog("共抓取教务通知 ${notices.size} 条")
		// 【需求4】逐条通知独立解析并带上该通知的发布时间(time), 用于"一周内"判断与去重;
		// 同一变更被多条通知重复下发时, 以发布时间较早的那条为准。
		val merged = LinkedHashMap<String, EASNoticeChange>()
		for(notice in notices){
			// 合并最近若干条通知正文(无关通知不含"第N周...节"句式, parseEASNotice 不会误判)
			val parsed = parseEASNotice(notice.content, table)
			// 诊断日志: 可能含调课/补课/停课的通知打印完整正文, 其余仅打印开头, 便于核对句式又不至于日志过大
			val candidate = notice.content.contains("周") || notice.content.contains("调课") ||
				notice.content.contains("补课") || notice.content.contains("停课") || notice.content.contains("节")
			if(candidate){
				// 【第四十八轮】记录候选通知原文(启动弹窗展示用)
				synchronized(lastEASNoticeText){
					lastEASNoticeText.append("[${notice.time}] ${notice.content}\n")
				}
				easLog("[候选通知 ${notice.time}] ${notice.content}")
				if(parsed.isEmpty()) easLog("  └ 未解析出调课/补课/停课：以上正文与当前句式不匹配(请把这段发给开发者)")
			}else{
				easLog("[通知 ${notice.time}] ${notice.content.take(60)}")
			}
			for(p in parsed){
				easLog("  └ 解析: ${describeEAS(p)} | 日期=${p.dateKey}")
				val key = "${p.dateKey}|${p.slotStart}|${p.kind}|${p.courseName}"
				val existing = merged[key]
				if(existing == null){
					merged[key] = p.copy(time = notice.time)
				}else if(notice.time.isNotEmpty() && (existing.time.isEmpty() || notice.time < existing.time)){
					merged[key] = p.copy(time = notice.time)
				}
			}
		}
		if(merged.isEmpty()) {
			easLog("!! 教务通知里没有解析出任何调课/补课/停课(若上方有'未解析出'的候选通知, 请把那段正文发给开发者核对句式)")
			throw RuntimeException("教务通知暂未发现调课/补课/停课安排")
		}
		easLog("解析合并后共 ${merged.size} 条调课/补课/停课")
		// 【需求2】只保留"本学期 + 一个月内"的调课/补课/停课:
		//   - isNoticeTimeStale(time) -> **通知发布时间**超过一个月的一律丢弃(见下方说明);
		//   - weekDayOfDate != null  -> 该日期落在学期范围内(按"教学周"换算, 即本学期, 绝非自然年/今年);
		//   - !isEASStale(dateKey)   -> 目标日期距今不超过一个月;
		//   - parseEASNotice 本身只匹配 调课/补课/停课 三类句式(放假/考试/纯通知不会命中), 其它一概不论。
		//
		// 【第二十二轮·真机日志修复】旧的实现只按"目标日期"过滤, 导致**上学期/往年**的旧通知
		// (如 2026-06 发布的"第16周…"通知)被按**本学期**周次重新换算成 2026-12 / 2027-01 的日期,
		// 因"日期在未来"而全部保留 —— 表现为"获取的年份/日期错了"、预览里塞满陈年调课。
		// 现在以"通知发布时间"为准: 发布时间超过一个月的一律不再处理。
		val recent = ArrayList<EASNoticeChange>()
		for(v in merged.values){
			val oldNotice = isNoticeTimeStale(v.time)
			val inTerm = table.weekDayOfDate(v.dateKey) != null
			val stale = isEASStale(v.dateKey)
			if(oldNotice){
				easLog("过滤: ${describeEAS(v)} | 通知发布时间=${v.time} 超过一个月(往期通知不再处理)")
			}else if(!inTerm){
				easLog("过滤: ${describeEAS(v)} | 日期=${v.dateKey} 不在本学期教学周范围内")
			}else if(stale){
				easLog("过滤: ${describeEAS(v)} | 日期=${v.dateKey} 超过一个月")
			}else{
				recent.add(v)
				easLog("保留: ${describeEAS(v)} | 日期=${v.dateKey} 通知时间=${v.time}")
			}
		}
		if(recent.isEmpty()) easLog("!! 全部条目都被'本学期/一个月内'过滤掉了(见上方过滤原因)")
		return recent.sortedBy { "${it.dateKey}${it.slotStart}" }
	}

	/**
	 * 【第二十二轮·真机日志修复】该条通知的**发布时间**是否已超过一个月。
	 *
	 * 教务通知列表里包含大量往期学期的调课/补课/停课(近一年的历史都在), 旧实现只用
	 * "目标日期是否在近一个月"过滤, 往期通知会按本学期周次换算出**未来日期**从而全部漏过,
	 * 造成"年份错了"、预览里塞满陈年调课。现在直接按通知发布时间过滤: 超过一个月不再处理。
	 * (发布时间缺失/无法解析时保守保留, 由后续学期/日期过滤兜底)
	 */
	private fun isNoticeTimeStale(time: String): Boolean {
		if(time.length < 10) return false
		val d = DateUtils.YMD.parse(time.substring(0, 10)) ?: return false
		val cal = Calendar.getInstance()
		cal.add(Calendar.DAY_OF_MONTH, -7)
		return d.before(cal.time)
	}

	/** 该日期是否已超过一周(相对今天); 超过则不再处理 */
	private fun isEASStale(dateKey: String): Boolean {
		val d = DateUtils.YMD.parse(dateKey) ?: return false
		val cal = Calendar.getInstance()
		cal.add(Calendar.DAY_OF_MONTH, -7)
		return d.before(cal.time)
	}

	/** 【需求4】该变更所属通知是否发布于"最近一周内"(用于进入软件时的弹窗范围) */
	private fun isWithinWeek(time: String): Boolean {
		if(time.length >= 10) {
			val datePart = time.substring(0, 10)
			val d = DateUtils.YMD.parse(datePart)
			if(d != null) {
				val cal = Calendar.getInstance().apply { add(Calendar.DATE, -7) }
				return !d.before(cal.time)
			}
		}
		// 时间无法解析时保守处理: 仍纳入弹窗(用户可在弹窗里选择暂不, 之后会被去重)
		return true
	}

	/** 【需求4】一条教务通知变更的"去重签名"(同一变更多次下发时稳定不变) */
	private fun easChangeSignature(c: EASNoticeChange): String =
		"${c.dateKey}|${c.slotStart}|${c.kind}|${c.courseName}"

	/**
	 * 解析教务通知正文中的调课/补课(节次级)
	 * @param text 合并后的通知正文
	 * @param table 当前课表(用于把"第N周星期W"换算成具体日期)
	 * @return 调课/补课变更列表(已按"日期+节次"去重)
	 */
	fun parseEASNotice(text: String, table: LessonTable): List<EASNoticeChange> {
		val cleaned = cleanText(text)
		val result = LinkedHashMap<String, EASNoticeChange>()
		// 1) 调课提醒(改地点): 只改一门课在某一节次的教室
		for(m in EAS_MOVE.findAll(cleaned)) {
			val week = m.groupValues[1].toIntOrNull() ?: continue
		val wd = EAS_WEEK_IDX[m.groupValues[2].first()] ?: continue
		val s = m.groupValues[3].toIntOrNull() ?: continue
		val e = m.groupValues[4].toIntOrNull() ?: continue
		val oldPlace = m.groupValues[5].trim()
			val course = m.groupValues[6].trim()
			val newPlace = m.groupValues[7].trim()
			val teacher = teacherBefore(cleaned, m.range.first)
			val dateKey = table.dateKeyOf(week - 1, wd)
			if(dateKey.isEmpty()) continue
			result["$dateKey|$s|L"] = EASNoticeChange(dateKey, week, wd, s, e, course, teacher, EASNoticeChange.KIND_LOCATION, oldPlace, newPlace)
		}
		// 2) 补课提醒(加课): 只在某一节次追加一门课
		for(m in EAS_MAKEUP.findAll(cleaned)) {
			val week = m.groupValues[1].toIntOrNull() ?: continue
		val wd = EAS_WEEK_IDX[m.groupValues[2].first()] ?: continue
		val s = m.groupValues[3].toIntOrNull() ?: continue
		val e = m.groupValues[4].toIntOrNull() ?: continue
		val course = m.groupValues[5].trim()
		val teacher = teacherBefore(cleaned, m.range.first)
		val dateKey = table.dateKeyOf(week - 1, wd)
		if(dateKey.isEmpty()) continue
			if(result.containsKey("$dateKey|$s|M")) continue
			result["$dateKey|$s|M"] = EASNoticeChange(dateKey, week, wd, s, e, course, teacher, EASNoticeChange.KIND_MAKEUP, "", "")
		}
		// 3) 停课提醒(取消课): 把某一节次里该课程移除, 只影响这一门、这一周
		for(m in EAS_SUSPEND.findAll(cleaned)) {
			val week = m.groupValues[1].toIntOrNull() ?: continue
			val wd = EAS_WEEK_IDX[m.groupValues[2].first()] ?: continue
			val s = m.groupValues[3].toIntOrNull() ?: continue
			val e = m.groupValues[4].toIntOrNull() ?: continue
			// 课程名: 新句式("…于<地点>上的X课程停上")取组5, 旧句式("课程X停课")取组6; 去掉遗留的"进行"后缀
			val course = (m.groupValues[5].ifEmpty { m.groupValues[6] }).trim().removeSuffix("进行").trim()
			if(course.isEmpty()) continue
			val teacher = teacherBefore(cleaned, m.range.first)
			val dateKey = table.dateKeyOf(week - 1, wd)
			if(dateKey.isEmpty()) continue
			if(result.containsKey("$dateKey|$s|S")) continue
			result["$dateKey|$s|S"] = EASNoticeChange(dateKey, week, wd, s, e, course, teacher, EASNoticeChange.KIND_SUSPEND, "", "")
		}
		return result.values.toList()
	}

	/** 在"第N周..."之前最近的一段里找上课老师名(并去掉"调课提醒:/补课提醒:"等前缀与"原定") */
	private fun teacherBefore(text: String, headerStart: Int): String {
		val from = (headerStart - 24).coerceAtLeast(0)
		val raw = EAS_TEACHER.find(text.substring(from, headerStart))?.groupValues?.getOrNull(1) ?: return ""
		// "调课提醒:陈静老师" -> "陈静"; "原定王琳老师" -> "王琳"
		return raw.substringAfterLast(':').substringAfterLast('：').removePrefix("原定").trim()
	}

	/**
	 * 把教务通知的调课/补课/停课变更写入课表(按日期逐节次局部覆盖)
	 *
	 * - 改地点: 在目标日期的 dayOverride 副本里, 只把"该周次 + 该课程名"那节课的 place 改成新教室,
	 *   其它周次/其它课程/其它节次一律不动 —— 不会出现"改地点却把整门课取消"。
	 * - 加课: 只在对应节次**没有原课程**时加入补课课程("不覆盖，只加入"的原课程位置不加入语义);
	 *   选择"覆盖"时用补课课程替换该节次原有课程。
	 * - 停课: 只移除该周次 + 该课程名的那节课, 其它课程保留。
	 *
	 * @return 实际写入的条数
	 */
	fun applyEASChanges(table: LessonTable, changes: List<EASNoticeChange>, overwrite: Boolean = false): Int {
		val applied = applyEASChangesCore(table, changes, overwrite)
		// 没有真正写入的条目不重排课表(避免"什么都没变"却把调休日期重算一遍)
		if(applied <= 0) return 0
		// 【本期】与「保存课表 + 勾选两个选项」走**完全同一条**路径:
		// 以"最新原始课表"为底, 合并用户修改过的课程, 把空洞补回(通知明确停掉的节次除外),
		// 并把调休借课日期按本周冻结 —— 这样教务调课的结果与手动保存课表的结果一致,
		// 也不会出现"空白课程补不回来"或"保存后多出课程"。
		val original = LessonTableRepository.getOriginalLessonTable()
		if(original != null){
			val saved = LessonTableSaveBuilder.buildKeepUserTable(
				original, table, table.startDay, table.totalWeek, saveAdjustments = true
			)
			table.replaceContentWith(saved)
		}else{
			// 没有原始课表快照(从未查询过)时, 至少保证调休借课日期不会退回整列模板
			LessonTableSaveBuilder.freezeBorrowedDates(table)
		}
		LessonTableRepository.saveLessonTable(table, keepAdjustments = false)
		LessonTableRepository.notifyLessonTableChanged()
		return applied
	}

	fun easChangesWouldChangeTable(table: LessonTable, changes: List<EASNoticeChange>): Boolean {
		if(changes.isEmpty()) return false
		// 存在"补课目标节次已有**其它**课程"的条目时, 需要用户在"课程存在覆盖情况"弹窗里选择
		// 覆盖 / 不覆盖只加入 —— 不能判定为"没有变化", 交由预览弹窗处理。
		// (目标节次已经就是这门课的情形视为"已一致", 不进入弹窗)
		if(changes.any { it.kind == EASNoticeChange.KIND_MAKEUP && easTargetSlotHasLesson(table, it) && !easMakeupAlreadyThere(table, it) }) return true
		val preview = table.deepCopy()
		applyEASChangesCore(preview, changes, overwrite = false)
		for(dateKey in changes.map { it.dateKey }.distinct()) {
			// 逐日比对"预演后"与"当前"的生效课表; 任一天不同即说明调课会带来变化
			if(dayDiffersFromOriginal(preview, table, dateKey)) return true
		}
		return false
	}

	/** 补课目标节次是否"已经就是这门课"(已存在同名课程, 老师相同或未知) —— 已满足, 视作课表一致 */
	private fun easMakeupAlreadyThere(table: LessonTable, ch: EASNoticeChange): Boolean {
		val (w, d) = table.weekDayOfDate(ch.dateKey) ?: return false
		val slotIndex = (ch.slotStart - 1).coerceIn(0, TimeTables.MAX_SLOT_COUNT - 1)
		val mask = 1L shl w.coerceIn(0, 62)
		val g = table.effectiveDay(w, d).getOrNull(slotIndex) ?: return false
		return g.lessons.any {
			it.name == ch.courseName && (ch.teacher.isEmpty() || it.teacher.isEmpty() || it.teacher == ch.teacher) && (it.week and mask != 0L)
		}
	}

	/** [applyEASChanges] 的核心实现(不落盘, 供正式写入与"预演比对"共用) */
	private fun applyEASChangesCore(table: LessonTable, changes: List<EASNoticeChange>, overwrite: Boolean): Int {
		var applied = 0
		for(ch in changes) {
			val weekIndex = ch.week - 1
			if(weekIndex < 0 || weekIndex >= table.totalWeek) continue
			val key = table.dateKeyOf(weekIndex, ch.dayOfWeek)
			if(key.isEmpty()) continue
			val slotIndex = (ch.slotStart - 1).coerceIn(0, TimeTables.MAX_SLOT_COUNT - 1)
			val targetWeekBit = 1L shl weekIndex
			val col = table.editDayOverride(weekIndex, ch.dayOfWeek)
			val appliedBefore = applied
			when(ch.kind) {
				EASNoticeChange.KIND_LOCATION -> {
					val group = col[slotIndex] ?: continue
					// 先按"课程名 + 老师 + 该周次"精确定位, 找不到再退而求其次只按课程名
					val lesson = group.lessons.firstOrNull {
						it.name == ch.courseName && (ch.teacher.isEmpty() || it.teacher == ch.teacher) && (it.week and targetWeekBit != 0L)
					} ?: group.lessons.firstOrNull { it.name == ch.courseName }
					if(lesson != null) {
						lesson.place = ch.newPlace
						applied++
					}
				}
				EASNoticeChange.KIND_MAKEUP -> {
					// 【第二十三轮】"不覆盖，只加入"语义: 保留原课程, 只在**该节次没有原课程**(本教学周不上课)时
					// 加入补课课程; 该节次已有原课程且选择"覆盖"时, 用补课课程替换原课程;
					// 选择"不覆盖"时原课程位置一律不加入(跳过) —— 只保留原课程, 不会两门课同时显示。
					// 【本轮】type = 0: 教务通知写入的课程与查询导入的课程同属"原始(A)", 不再标记为"用户修改(U)"
					val newLesson = Lesson(type = 0, name = ch.courseName, teacher = ch.teacher, place = "", week = targetWeekBit, len = (ch.slotEnd - ch.slotStart + 1).coerceAtLeast(1), kchID = "")

					val existing = col[slotIndex]
					val occupied = existing?.lessons?.any { it.week and targetWeekBit != 0L } == true
					if(!occupied) {
						// 空节次: 加入补课课程(该节次其它周次的历史数据保留)
						col[slotIndex] = if(existing == null) LessonGroup(ch.dayOfWeek + 1, ch.slotStart, arrayOf(newLesson))
							else LessonGroup(existing.week, existing.count, existing.lessons + newLesson)
						// 这一节又被补上了: 之前"停课"的标记要撤掉
						table.easSuspended[key]?.remove(slotIndex)
						applied++
					}else if(overwrite) {
						// 覆盖: 用补课课程替换该节次原有课程
						col[slotIndex] = LessonGroup(existing!!.week, existing.count, arrayOf(newLesson))
						table.easSuspended[key]?.remove(slotIndex)
						applied++
					}
					// else -> 不覆盖，只加入: 原课程位置不加入(保留原课程, 不做任何改动)
				}
				EASNoticeChange.KIND_SUSPEND -> {
					// 停课: 把该周次 + 该课程名(老师)的那节课从 dayOverride 移除; 整组空了则该节次置空(无课)
					val group = col[slotIndex] ?: continue
					val remaining = group.lessons.filterNot {
						it.name == ch.courseName && (ch.teacher.isEmpty() || it.teacher == ch.teacher) && (it.week and targetWeekBit != 0L)
					}
					if(remaining.size != group.lessons.size) {
						col[slotIndex] = if(remaining.isEmpty()) null else LessonGroup(group.week, group.count, remaining.toTypedArray())
						// 记住"这一节是教务通知停掉的": 保存课表时只跳过这几节, 其它空洞照常补回
						table.easSuspended.getOrPut(key){ HashSet() }.add(slotIndex)
						applied++
					}
				}
			}
			// 这一天确实被通知改过: 记进"教务通知调课的日期"。保存课表时这些日期不再按原始课表
			// 补回(见 LessonTableSaveBuilder 第 5 步), 停课/加课的结果才不会被下一次保存改回去。
			if(applied > appliedBefore) table.easDates.add(key)
		}
		return applied
	}

	/**
	 * 【第四十八轮】进行教务通知调课前, 先按"学校当前学期"查询并保存一次最新课表:
	 * 保存时 keepAdjustments=true —— 保留现有的 调休课程 与 用户按日期修改的课程,
	 * 只更新每周课程模板; 之后再走原有的"教务通知调课"全部流程(核对/差异/写入)。
	 * @return 是否成功刷新(失败不阻断原流程)
	 */
	private suspend fun refreshLessonTableBeforeEAS(): Boolean {
		return try {
			val account = EASAccount.getInstance()
			val result = LessonTableRepository.queryLessonTable(account, "", "", useSchoolCurrentTerm = true)
			if(result.error != null) {
				Logger.w("EAS 调课前刷新课表失败: ${result.error}")
				false
			}else if(result.lessonTable.activeSlotCount() == 0) {
				Logger.w("EAS 调课前刷新课表为空, 跳过保存")
				false
			}else{
				// 【本轮】查询到的最新课表同时作为"最新原始课表"快照(与当下课表无关, 每次查询覆盖)
				LessonTableRepository.saveOriginalLessonTable(result.lessonTable)
				// 以查询结果为底, 合并用户修改过的课程; 删除也视为修改, 由 keepAdjustments=true 保留的
				// dayOverrides(用户改过/删过的日期)继续生效。
				val merged = result.lessonTable.mergeUserDefinedLesson(LessonTableRepository.lessonTable)
				LessonTableRepository.saveLessonTable(merged, keepAdjustments = true)
				LessonTableRepository.notifyLessonTableChanged()
				Logger.d("EAS 调课前已重新查询并保存课表(保留调休与用户修改)")
				true
			}
		}catch(e: Exception){
			Logger.e(e)
			false
		}
	}

	/**
	 * 【本轮】「教务通知调课」的完整动作, 供按钮点击与启动弹窗共用:
	 * 1) 按"学校当前学期"查询课表, 并把结果作为最新原始课表;
	 * 2) 保存新课表: 保存调休课程、保存用户修改过的课程(删除也视为修改);
	 * 3) 应用解析出的教务通知调课/补课/停课变更并落盘。
	 *
	 * 【本期】不再做"已应用"判定: 写入本身是幂等的(改地点重复写同值、停课重复写找不到课、
	 * 补课遇到该节次已有课不会覆盖), 是否真的改变了课表由 [easChangesWouldChangeTable] 预演比对,
	 * 因此不需要额外记录"这批通知已经应用过"。
	 * @return 实际写入的调课条数
	 */
	suspend fun applyEASNoticeFlow(changes: List<EASNoticeChange>): Int {
		val refreshed = refreshLessonTableBeforeEAS()
		val table = LessonTableRepository.lessonTable
		val applied = applyEASChanges(table, changes, overwrite = false)
		Logger.d("EAS 调课流程: 刷新=$refreshed 通知=${changes.size} 写入=$applied")
		return applied
	}

	/** 一条教务通知变更(调课/补课/停课)的展示文案 */
	private fun describeEAS(c: EASNoticeChange): String {
		val wd = "一二三四五六日"[c.dayOfWeek]
		val whenTxt = "第${c.week}周星期$wd 第${c.slotStart}-${c.slotEnd}节"
		return when(c.kind) {
			EASNoticeChange.KIND_LOCATION -> "$whenTxt ${c.courseName}：地点 ${c.oldPlace} → ${c.newPlace}"
			EASNoticeChange.KIND_MAKEUP -> "$whenTxt 加课：${c.courseName}${if(c.teacher.isNotEmpty()) "（${c.teacher}老师）" else ""}"
			else -> "$whenTxt 停课：${c.courseName}${if(c.teacher.isNotEmpty()) "（${c.teacher}老师）" else ""}"
		}
	}

	/**
	 * 【本轮】从 yyyy-MM-dd 直接切出月/日。
	 *
	 * 不再依赖 [DateUtils.YMD]: 它是共享的 SimpleDateFormat 实例, 不是线程安全的 ——
	 * 一旦解析失败, 旧写法 `?: return dateKey` 会把 "2026-10-09" 原样显示在弹窗里
	 * (用户看到的就是带年份的日期)。这里纯字符串切分, 只要格式是 yyyy-MM-dd 就一定成功。
	 */
	private fun monthDayOf(dateKey: String): Pair<Int, Int>? {
		val parts = dateKey.trim().split("-")
		if(parts.size != 3) return null
		val m = parts[1].toIntOrNull() ?: return null
		val d = parts[2].take(2).toIntOrNull() ?: return null
		return if(m in 1..12 && d in 1..31) m to d else null
	}

	/**
	 * 【本轮】把文案里残留的 yyyy-MM-dd 全部换成"月日"。
	 *
	 * "课程存在覆盖情况"这类弹窗里, 日期本该由 [dateLabel]/[dateLabelShort] 给出月日;
	 * 但只要有一条数据带着原始日期键(yyyy-MM-dd)漏进文案, 弹窗里就会冒出
	 * "2026-10-09 原课程：…" 这种带年份的写法。这里做最后一道清洗, 保证弹窗内
	 * 无论数据来自哪里都不会出现年份。
	 */
	private fun noYear(s: String): String {
		if(s.indexOf('-') < 0) return s
		return YEAR_MD.replace(s) { m ->
			val mm = m.groupValues[1].toIntOrNull()
			val dd = m.groupValues[2].toIntOrNull()
			if(mm != null && dd != null && mm in 1..12 && dd in 1..31) "${mm}月${dd}日" else m.value
		}
	}

	private val YEAR_MD = Regex("(?:\\d{4})-(\\d{1,2})-(\\d{1,2})")

	/** 简短日期标签(用于"本次安排"展示, 形如 "10月2号"; 不含年份) */
	private fun dateLabelShort(dateKey: String): String {
		val md = monthDayOf(dateKey) ?: return DateUtils.mdChinese(dateKey)
		return "${md.first}月${md.second}号"
	}

	/** 调休(官网/手动)在某差异日期"本次将落地的安排"文案, 含日期, 形如 "10月2号上9月1号的课程" */
	private fun changeArrangementText(table: LessonTable, item: ChangeItem): String {
		val target = dateLabelShort(item.dateKey)
		return when {
			item.action == ACTION_CLEAR -> "$target：放假（当天不上课）"
			item.sourceDateKey != null -> {
				val src = dateLabelShort(item.sourceDateKey)
				"$target 上 $src 的课程"
			}
			else -> {
				val courses = coursesOf(table, item)
				if(courses.isEmpty()) "$target：当天没有安排课程" else "$target：${courses.joinToString("、")}"
			}
		}
	}

	/** 教务通知调课/补课/停课在某差异日期"本次将落地的安排"文案, 含日期, 形如 "10月8号 第3-4节 加课：大学物理" */
	private fun describeEASDated(c: EASNoticeChange): String {
		val head = dateLabelShort(c.dateKey)
		val what = when(c.kind) {
			EASNoticeChange.KIND_LOCATION -> "改地点：${c.courseName} ${c.oldPlace} → ${c.newPlace}"
			EASNoticeChange.KIND_MAKEUP -> "加课：${c.courseName}${if(c.teacher.isNotEmpty()) "（${c.teacher}老师）" else ""}"
			else -> "停课：${c.courseName}${if(c.teacher.isNotEmpty()) "（${c.teacher}老师）" else ""}"
		}
		return "$head 第${c.slotStart}-${c.slotEnd}节 $what"
	}

	/**
	 * 把某周某天"当周实际会上"的课程整理成一行文本, 含节次, 形如 "第3-4节 高等数学；第5节 大学英语"
	 * (连排课按 [Lesson.len] 展开成 "第3-4节"; 无课返回"无课")
	 */
	private fun describeDaySlots(table: LessonTable, week: Int, day: Int): String {
		val mask = 1L shl week.coerceIn(0, 62)
		val col = table.effectiveDay(week, day)
		val parts = ArrayList<String>()
		col.forEachIndexed { i, g ->
			if(g == null) return@forEachIndexed
			val names = g.lessons.filter { it.week and mask != 0L }.joinToString("、") { it.name }
			if(names.isEmpty()) return@forEachIndexed
			val len = g.lessons.firstOrNull()?.len?.coerceAtLeast(1) ?: 1
			parts.add(if(len > 1) "第${i + 1}-${i + len}节 $names" else "第${i + 1}节 $names")
		}
		// 【本轮】每个节次单独一行("第"前面换行), 覆盖弹窗里一眼能看清有哪些课
		return if(parts.isEmpty()) "无课" else "\n" + parts.joinToString("\n")
	}

	/**
	 * 【第二十六轮】两天的"生效课程列"是否一致(逐节次比较课程名/老师/地点/节数)。
	 * 用于"清除该日调休"判断清除前后课表是否一样 —— 一样说明本来就没有可清除的调休。
	 */
	private fun sameDayColumn(a: Array<LessonGroup?>?, b: Array<LessonGroup?>?): Boolean {
		if(a == null || b == null) return a == null && b == null
		val n = if(a.size >= b.size) a.size else b.size
		for(i in 0 until n){
			val g1 = a.getOrNull(i)?.lessons ?: emptyArray()
			val g2 = b.getOrNull(i)?.lessons ?: emptyArray()
			if(g1.size != g2.size) return false
			for(j in g1.indices){
				if(g1[j].name != g2[j].name || g1[j].teacher != g2[j].teacher ||
					g1[j].place != g2[j].place || g1[j].len != g2[j].len) return false
			}
		}
		return true
	}

	/**
	 * 官网/手动调休"课程存在覆盖情况"弹窗的明细行:
	 * 给出目标日期**原课程的节数**与**覆盖调休后的课程**, 形如
	 * "10月8号 原课程：第3-4节 高等数学 / 覆盖调休后：第3-4节 大学物理"
	 */
	private fun webOverwriteLines(table: LessonTable, items: List<ChangeItem>): List<String> {
		val out = ArrayList<String>()
		for(item in items){
			if(item.action == ACTION_CLEAR) continue
			if(!targetDayHasLesson(table, item.dateKey)) continue
			val target = table.weekDayOfDate(item.dateKey) ?: continue
			val source = resolveSource(table, item) ?: continue
			val orig = describeDaySlots(table, target.first, target.second)
			val after = describeDaySlots(table, source.first, source.second)
			out.add(noYear("${dateLabelShort(item.dateKey)} 原课程：$orig\n覆盖调休后：$after"))
		}
		return out
	}

	/**
	 * 教务通知调课"课程存在覆盖情况"弹窗的明细行:
	 * 给出目标节次**原课程**与**覆盖调休后的课程**, 形如
	 * "10月8号 第3-4节 原课程：高等数学（明-302） / 覆盖调休后：大学物理"
	 */
	private fun easOverwriteLines(table: LessonTable, changes: List<EASNoticeChange>): List<String> {
		val out = ArrayList<String>()
		for(ch in changes){
			if(ch.kind != EASNoticeChange.KIND_MAKEUP) continue
			val (w, d) = table.weekDayOfDate(ch.dateKey) ?: continue
			val slotIndex = (ch.slotStart - 1).coerceIn(0, TimeTables.MAX_SLOT_COUNT - 1)
			val mask = 1L shl w.coerceIn(0, 62)
			val origNames = table.effectiveDay(w, d).getOrNull(slotIndex)?.lessons
				?.filter { it.week and mask != 0L }
				?.joinToString("、") { "${it.name}${if(it.place.isNotEmpty()) "（${it.place}）" else ""}" }
				?: ""
			if(origNames.isEmpty()) continue
			val course = "${ch.courseName}${if(ch.teacher.isNotEmpty()) "（${ch.teacher}老师）" else ""}"
			out.add(noYear("${dateLabelShort(ch.dateKey)}\n第${ch.slotStart}-${ch.slotEnd}节\n原课程：$origNames\n覆盖调休后：$course"))
		}
		return out
	}

	/**
	 * 将变更写入课表(覆盖写入 overrides)
	 *
	 * 调休是"日期级"的强规则: 若目标日期此前存在手动按日期修改(dayOverrides, 例如
	 * 之前用"复制整天课表"或拖动课程留下的局部覆盖), 会一并清除, 否则该日期的调休
	 * 会被手动覆盖顶掉, 表现为"部分调休没有正确更改"。
	 *
	 * @return 因本次写入而被清除的手动修改条数
	 */
	/**
	 * 把一批调休安排(ChangeItem)应用到课表
	 * @param save 【第三十五轮】false = 仅预演(用于"新旧课表差异"计算), 不落盘不通知
	 */
	fun applyChanges(table: LessonTable, changes: List<ChangeItem>, merge: Boolean = false, save: Boolean = true): Int {
		var cleared = 0
		for(item in changes){
			// 放假: 只做日期标记(不删课程)
			if(item.action == ACTION_CLEAR){
				if(table.setOverride(item.dateKey, ACTION_CLEAR)) cleared++
				continue
			}

			// 【问题3-④】调休一律以**具体日期**为操作对象:
			// 1. 先把源(具体日期, 或"目标日期所在周的那个星期")换算成 (第几周, 星期几)
			// 2. 再把"源那一天的课程"整列复制到目标日期上(按目标周次过滤)
			// 这样不再依赖"每周同一星期的课都一样"这个错误前提。
			val target = table.weekDayOfDate(item.dateKey) ?: continue
			val source = resolveSource(table, item) ?: continue
			if(merge){
				// 【不覆盖，只加入】保留目标日期原有课程, 只在对应时间加入调休课程。
				// 直接写 overrides 登记调休语义, 不能走 setOverride —— 后者会把刚合并好的
				// dayOverrides(合并结果就存在这里)一并清掉。
				table.overrides[item.dateKey] = item.action
			}else{
				table.setOverride(item.dateKey, item.action)     // 保留调休语义(兼容旧数据)
			}
			table.copyDayByDate(source.first, source.second, target.first, target.second, merge = merge)
		}
		if(save){
			LessonTableRepository.saveLessonTable()
			// 触发界面刷新
			LessonTableRepository.notifyLessonTableChanged()
		}
		return cleared
	}

	/** 把一条调休的"源"换算成 (第几周, 星期几); 优先用文中写明的源日期 */
	private fun resolveSource(table: LessonTable, item: ChangeItem): Pair<Int, Int>? {
		item.sourceDateKey?.let { key ->
			table.weekDayOfDate(key)?.let { return it }
		}
		val target = table.weekDayOfDate(item.dateKey) ?: return null
		val srcDay = item.action - 1
		if(srcDay !in 0..6) return null
		// 只知道"补星期X的课": 取目标日期**所在周**的那个星期(这才是具体日期)
		return target.first to srcDay
	}

	/** 某条调休在该日期"实际会上的课程名"(按目标周次过滤) */
	fun coursesOf(table: LessonTable, item: ChangeItem): List<String> {
		val target = table.weekDayOfDate(item.dateKey) ?: return emptyList()
		val source = if(item.action == ACTION_CLEAR) target else (resolveSource(table, item) ?: return emptyList())
		// 【第三十一轮·修复"识别结果漏课"】掩码改用**源周**(与真正执行调休的 LessonTable.copyDayByDate
		// 完全一致: 它按 fromWeek 过滤源日课程)。以前这里按**目标周**过滤 —— "按某日期的课程"调休时
		// 源日期可能不在目标周, 于是"只在源日那周才上的课"被漏掉:
		// 例: "9月20日（第3周）上10月6日（第6周周二）的教学安排" —— 模拟电路基础只在第6周上(第3周不上),
		// 却被第3周掩码滤掉, 识别结果比实际执行的调休少列了一门课。
		val mask = 1L shl source.first.coerceIn(0, 62)
		val names = LinkedHashSet<String>()
		for(group in table.effectiveDay(source.first, source.second)) {
			group?.lessons?.forEach { lesson ->
				if(lesson.week and mask != 0L) names.add(lesson.name)
			}
		}
		return names.toList()
	}

	/** 需求3: 某条调休的目标日期当天是否已有"会在该周上课"的课程(应用调休时会被复制覆盖) */
	private fun targetDayHasLesson(table: LessonTable, dateKey: String): Boolean {
		val (w, d) = table.weekDayOfDate(dateKey) ?: return false
		val mask = 1L shl w.coerceIn(0, 62)
		return table.effectiveDay(w, d).any { group -> group?.lessons?.any { it.week and mask != 0L } == true }
	}

	/**
	 * 需求3: 某条教务通知补课(加课)的目标节次当前是否已有课程。
	 * 仅用于 KIND_MAKEUP: 若该节次已有课, 应用补课时会发生"覆盖/并入"抉择。
	 */
	private fun easTargetSlotHasLesson(table: LessonTable, ch: EASNoticeChange): Boolean {
		val (w, d) = table.weekDayOfDate(ch.dateKey) ?: return false
		val slotIndex = (ch.slotStart - 1).coerceIn(0, TimeTables.MAX_SLOT_COUNT - 1)
		val mask = 1L shl w.coerceIn(0, 62)
		val g = table.effectiveDay(w, d).getOrNull(slotIndex)
		return g?.lessons?.any { it.week and mask != 0L } == true
	}

	// ===================== 需求H: 调休以最新原始课表为准 =====================

	/**
	 * 判断某日期的"最新课表"与"最新原始课表"是否不一致。
	 * 最新原始课表 = [LessonTableRepository.loadOriginalLessonTable] 快照(仅每周模板, 不含任何调休/放假标记)。
	 * 若该日当前生效的课程(名称/地点/教师)与最新原始课表不同, 则认为不一致, 需要让用户选择基准。
	 */
	fun dayDiffersFromOriginal(table: LessonTable, original: LessonTable, dateKey: String): Boolean {
		val (week, day) = table.weekDayOfDate(dateKey) ?: return false
		val cur = table.effectiveDay(week, day)
		val ori = original.effectiveDay(week, day)
		if(cur.size != ori.size) return true
		for(i in cur.indices){
			val g1 = cur[i]?.lessons ?: emptyArray()
			val g2 = ori[i]?.lessons ?: emptyArray()
			if(g1.size != g2.size) return true
			for(j in g1.indices){
				if(g1[j].name != g2[j].name || g1[j].place != g2[j].place || g1[j].teacher != g2[j].teacher) return true
			}
		}
		return false
	}

	/**
	 * 把某日恢复到**最新原始课表**(最近一次「查询课表」得到的、没有做过任何修改的课表):
	 * 先清掉该日全部调休/放假/手动日期覆盖, 再把这一天按快照写回。
	 *
	 * 【为什么不能只清覆盖】旧实现只清 overrides/dayOverrides, 这一天于是回到
	 * **当前每周模板** —— 而每周模板可能被用户在学期课表里改过(改名/换教室/移动/删除),
	 * 与"最新原始课表"并不相同。用户选「以最新原始课表为准」时必须以快照为准。
	 *
	 * 快照与该星期模板内容一致时不额外冻结这一天(与旧行为完全一致);
	 * 没有快照(从没查询过课表)时同样退回旧行为。
	 */
	fun revertDayToOriginal(dateKey: String){
		val table = LessonTableRepository.lessonTable
		val wd = table.weekDayOfDate(dateKey)
		table.overrides.remove(dateKey)
		table.dayOverrides.remove(dateKey)
		if(wd == null) return
		val original = LessonTableRepository.getOriginalLessonTable() ?: return
		val (week, day) = wd
		val ori = original.effectiveDay(week, day)
		// 快照与当前每周模板一致 -> 清掉覆盖就等于"以最新原始课表为准", 不必再冻结这一天
		if(sameDayColumn(ori, table.effectiveDay(week, day))) return
		val col = table.editDayOverride(week, day)
		for(i in col.indices) col[i] = ori.getOrNull(i)?.deepCopy()
	}

	/** 【第六十二轮】教务通知调课涉及的"具体节次"(按日期): 核对差异/恢复课表都只处理这些节次, 不再整天处理 */
	private fun easAffectedSlots(changes: List<EASNoticeChange>): Map<String, Set<Int>> {
		val map = LinkedHashMap<String, MutableSet<Int>>()
		for(ch in changes){
			val s = (ch.slotStart - 1).coerceIn(0, TimeTables.MAX_SLOT_COUNT - 1)
			val e = (ch.slotEnd - 1).coerceIn(s, TimeTables.MAX_SLOT_COUNT - 1)
			val set = map.getOrPut(ch.dateKey){ LinkedHashSet() }
			for(i in s..e) set.add(i)
		}
		return map
	}

	/**
	 * 【第六十二轮】把某日期"指定节次"恢复为最新原始课表 —— 只动这些节次, 同一天其它节次(与本次调课无关的改动)保持不变。
	 * 例: 10月3号只有 1-2 节需要调课, 3-4 节的变化不属于本次调课 -> 只恢复 1-2 节。
	 */
	fun revertSlotsToOriginal(dateKey: String, slots: Set<Int>){
		val table = LessonTableRepository.lessonTable
		val original = LessonTableRepository.getOriginalLessonTable() ?: return
		val (week, day) = table.weekDayOfDate(dateKey) ?: return
		val ori = original.effectiveDay(week, day)
		val col = table.editDayOverride(week, day)
		for(i in slots){
			if(i < 0 || i >= col.size) continue
			col[i] = ori.getOrNull(i)
		}
	}

	/** "课程名 @ 地点" -> "课程名（地点）", 地点为空则只留课程名 */
	private fun fmtCourse(s: String): String {
		val name = s.substringBefore("@")
		val place = s.substringAfter("@")
		return if(place.isNotEmpty()) "$name（$place）" else name
	}

	/** "10月18日（周日）" —— 标注日期本身是星期几【问题3-②】; 【本轮】只保留月日, 不带年份 */
	private fun dateLabel(dateKey: String): String {
		val md = monthDayOf(dateKey)
		val c = Calendar.getInstance()
		if(md != null){
			// 纯字符串切分拿到月/日, 星期用 Calendar 自己算(不需要再解析一遍日期字符串)
			val year = dateKey.trim().substringBefore("-").toIntOrNull() ?: 2000
			c.clear()
			c.set(year, md.first - 1, md.second)
		}else{
			val date = try { DateUtils.YMD.parse(dateKey) } catch(_: Exception) { null } ?: return DateUtils.mdChinese(dateKey)
			c.time = date
		}
		val dayIndex = when(c[Calendar.DAY_OF_WEEK]) {
			Calendar.MONDAY -> 0
			Calendar.TUESDAY -> 1
			Calendar.WEDNESDAY -> 2
			Calendar.THURSDAY -> 3
			Calendar.FRIDAY -> 4
			Calendar.SATURDAY -> 5
			else -> 6
		}
		return "${c[Calendar.MONTH] + 1}月${c[Calendar.DATE]}日（${Data.WEEK_STRING[dayIndex]}）"
	}

	/**
	 * 【第三十五轮】对比两张课表在某日期的**全部**差异(逐节次, 不做连排合并, 不漏任何一节):
	 * - before = 改动前(旧课表), after = 改动后(新课表);
	 * - 课程按该日期所在周次过滤(与课表界面实际显示一致, 排除"其它周才上"的灰色课程 ——
	 *   旧版差异展示未按周次过滤, 会把灰色课程误报成"原来无课现在有课"这类假差异);
	 * - 每个有差异的节次一行: "第N节 原：… → 现：…", 单侧无课显示"原：无课"/"现：无课",
	 *   同时覆盖"原来无课现在有课"与"原来有课现在无课"两个方向。
	 * - 【第六十二轮】only != null 时只看这些节次(0 基): 教务通知调课核对只核对"相关节次",
	 *   不再把同一天其它节次的用户改动也算进来。
	 */
	/**
	 * 【修改版】筛出"执行后会使课表发生变化"的调休安排(图片识别与官网调休共用同一判定)
	 * - 先在一份深拷贝上模拟应用全部安排, 再逐日比较新旧课表;
	 * - 只返回"前后不一致"日期对应的安排, 避免把已生效/无关的安排一并展示与执行。
	 */
	fun filterEffectiveChanges(changes: List<ChangeItem>): List<ChangeItem> {
		if(changes.isEmpty()) return changes
		val table = LessonTableRepository.lessonTable
		val preview = table.deepCopy()
		applyChanges(preview, changes, save = false)
		val changed = changes.map { it.dateKey }.distinct()
			.filter { diffDayAllSlots(table, preview, it).isNotEmpty() }
			.toSet()
		// 【修改版】只保留"新旧课表不一致"的安排:
		//  - 放假(action==0): 该日期还没有放假标识时才显示(已有标识=无需重复);
		//  - 调休(action 1..7): 模拟执行后该日期课程确实变化才显示。
		return changes.filter { c ->
			if(c.action == 0) LessonTableRepository.lessonTable.getOverride(c.dateKey) != 0
			else c.dateKey in changed
		}
	}

	fun diffDayAllSlots(before: LessonTable, after: LessonTable, dateKey: String, only: Set<Int>? = null): List<String> {
		val (week, day) = after.weekDayOfDate(dateKey) ?: return emptyList()
		val mask = 1L shl week.coerceIn(0, 62)
		val b = before.effectiveDay(week, day)
		val a = after.effectiveDay(week, day)
		// 【第四十轮】逐节次收集差异, 并用课程的 len 展开**完整节数**:
		// 连排课程(如物理 1-2 节、概率论 5-6 节)只存于起始节次, 必须按其 len 显示为
		// "第5-6节"; 旧实现只看起始节, 会出现"只显示第5节"的错误展示。
		val slots = ArrayList<Triple<Int, Int, String>>()   // (起始节索引, 占用节数, 差异文案)
		var i = 0
		while(i < TimeTables.MAX_SLOT_COUNT){
			val bg = b.getOrNull(i)
			val ag = a.getOrNull(i)
			val bs = bg?.lessons?.filter { it.week and mask != 0L }?.map { "${it.name}@${it.place}" }?.toSet() ?: emptySet()
			val as2 = ag?.lessons?.filter { it.week and mask != 0L }?.map { "${it.name}@${it.place}" }?.toSet() ?: emptySet()
			if(bs == as2){ i++; continue }
			val span = maxOf(
				bg?.lessons?.filter { it.week and mask != 0L }?.maxOfOrNull { it.len } ?: 1,
				ag?.lessons?.filter { it.week and mask != 0L }?.maxOfOrNull { it.len } ?: 1,
				1)
			// 【第六十二轮】只看指定节次: 不在范围内的差异直接跳过(按连排跨度前进, 不重复比较)
			if(only != null && i !in only){ i += span; continue }
			val removed = (bs - as2).map { fmtCourse(it) }
			val added = (as2 - bs).map { fmtCourse(it) }
			val parts = ArrayList<String>()
			parts.add(if(removed.isNotEmpty()) "原：${removed.joinToString("、")}" else "原：无课")
			parts.add(if(added.isNotEmpty()) "现：${added.joinToString("、")}" else "现：无课")
			slots.add(Triple(i, span, parts.joinToString(" → ")))
			i += span
		}
		// 合并相邻且文案相同、首尾相接的项(避免出现"第5-6节"+"第7-8节"本可并成一项的情况保持独立)
		val out = ArrayList<String>()
		var idx = 0
		while(idx < slots.size){
			val from = slots[idx].first
			var to = slots[idx].first + slots[idx].second - 1
			var j = idx
			while(j + 1 < slots.size && slots[j + 1].first == to + 1 && slots[j + 1].third == slots[idx].third){
				j++
				to = slots[j].first + slots[j].second - 1
			}
			out.add(if(to > from) "第${from + 1}-${to + 1}节 ${slots[idx].third}" else "第${from + 1}节 ${slots[idx].third}")
			idx = j + 1
		}
		return out
	}

	/**
	 * 【第三十五轮】"调休安排"列表的文案: 直接读取该日期已保存的 dayOverrides 内容
	 * (与课表界面实际显示完全一致)。旧版用"补星期X"的模板列按目标周次过滤 —— 会漏掉
	 * "只在源日期那周才上的课"(如 9月20日补10月6日的课, 模拟电路基础只在第6周上)。
	 */
	fun savedArrangeText(table: LessonTable, dateKey: String, action: Int): String {
		val head = dateLabel(dateKey)
		if(action == ACTION_CLEAR) return "$head：放假"
		val mask = 1L shl (table.weekDayOfDate(dateKey)?.first ?: 0).coerceIn(0, 62)
		val col = table.dayOverrides[dateKey]
		if(col != null){
			val names = LinkedHashSet<String>()
			// 【修改版】执行其他日期的调休安排: 课程名后标注来源日期与星期
			val tail = if(action in 1..7){
				val srcDate = runCatching {
					val c = java.util.Calendar.getInstance()
					c.time = com.qust.helper.utils.DateUtils.YMD.parse(dateKey)!!
					val cur = c.get(java.util.Calendar.DAY_OF_WEEK) // 1=周日
					val curIdx = if(cur == 1) 7 else cur - 1           // 1=周一..7=周日
					c.add(java.util.Calendar.DAY_OF_MONTH, (action - curIdx))
					com.qust.helper.utils.DateUtils.YMD.format(c.time)
				}.getOrNull()
				if(srcDate != null) "（执行${dateLabel(srcDate)}的教学安排）" else ""
			}else ""
			for(group in col) group?.lessons?.forEach { if(it.week and mask != 0L) names.add(it.name) }
			// 【修改版】同一天只显示一次来源说明
			if(names.isNotEmpty()) return "$head：${names.joinToString("、")}$tail"
		}
		// 无按日期副本(纯标记)时退回模板推算
		val courses = coursesOf(table, ChangeItem(dateKey, action, ""))
		return if(courses.isEmpty()) "$head：当天没有安排课程" else "$head：${courses.joinToString("、")}"
	}

	/**
	 * 【问题3-②】调休预览/列表的展示文案
	 *
	 * 不再出现"补星期X的课", 改为列出**该日期当天实际会上的课程**, 并标注这个日期本身是星期几。
	 */
	fun previewLines(table: LessonTable, changes: List<ChangeItem>): List<String> {
		return changes.map { item ->
			val head = dateLabel(item.dateKey)
			when {
				item.action == ACTION_CLEAR -> "$head：放假"
				else -> {
					val courses = coursesOf(table, item)
					val tail = if(item.sourceDateKey != null) "（执行 ${dateLabel(item.sourceDateKey)} 的教学安排）" else ""
					if(courses.isEmpty()) "$head：当天没有安排课程$tail"
					else "$head：${courses.joinToString("、")}$tail"
				}
			}
		}
	}

	// ---------------- UI ----------------

	/**
	 * 调休变更预览确认弹窗
	 */
	@Composable
	fun ChangePreviewDialog(
		/** 每条调休的展示文案: "10月18日（周日）：高等数学、大学物理" */
		lines: List<String>,
		onDismiss: () -> Unit,
		onConfirm: () -> Unit,
		/** 识别阶段读到的原文(截图 OCR 文本), 便于核对识别是否准确 */
		rawText: String = ""
	) {
		var showRaw by remember { mutableStateOf(false) }

		AlertDialog(
			onDismissRequest = { onDismiss() },
			title = { Text(text = "识别结果（${lines.size}项）") },
			text = {
				Column {
					Text(
						text = "以下课表变更将在确认后写入，取消则不做任何改动：",
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.secondary,
						modifier = Modifier.padding(bottom = 8.dp)
					)
					LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
						items(lines.size){ i ->
							Text(
								text = lines[i],
								modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 4.dp),
								style = MaterialTheme.typography.bodyMedium
							)
						}
					}

					// 识别阶段: 展示本次读到的原文, 方便判断日期是否被正确识别
					if(rawText.isNotEmpty()){
						Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
							TextButton(onClick = { showRaw = !showRaw }) {
								Text(text = if(showRaw) "收起识别原文" else "查看识别原文")
							}
						}
						if(showRaw){
							Text(
								text = rawText,
								style = MaterialTheme.typography.bodySmall,
								color = MaterialTheme.colorScheme.secondary,
								modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState())
							)
						}
					}
				}
			},
		confirmButton = {
			TextButton(onClick = { onConfirm() }) { Text(text = "确认修改") }
		},
		dismissButton = {
			TextButton(onClick = { onDismiss() }) { Text(text = "取消") }
		}
	)
}

	/**
	 * 教务通知「调课/补课」预览确认弹窗(节次级, 与 [ChangePreviewDialog] 体验对齐)
	 *
	 * 每条只展示"第N周星期W 第A‑B节 + 课程 + 改地点/加课", 确认后才按日期逐节次写入,
	 * 不会整日覆盖、不会删课。
	 */
	@Composable
	fun EASChangePreviewDialog(
		changes: List<EASNoticeChange>,
		onDismiss: () -> Unit,
		onConfirm: () -> Unit
	) {
		AlertDialog(
			onDismissRequest = { onDismiss() },
			title = { Text(text = "教务通知调课/补课/停课预览（${changes.size}项）") },
			text = {
				Column {
					Text(
						text = "以下为教务通知中的调课/补课/停课，确认后按具体节次写入课表（只改对应课程，不改动其它课程）：",
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.secondary,
						modifier = Modifier.padding(bottom = 8.dp)
					)
					LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
						items(changes.size){ i ->
							Text(
								text = describeEAS(changes[i]),
								modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 4.dp),
								style = MaterialTheme.typography.bodyMedium
							)
						}
					}
				}
			},
			confirmButton = {
				TextButton(onClick = { onConfirm() }) { Text(text = "写入课表") }
			},
			dismissButton = {
				TextButton(onClick = { onDismiss() }) { Text(text = "取消") }
			}
		)
	}

	/**
	 * 调休用的日历工具: 取某个"自然月 1 号"的 Calendar
	 */
	private fun monthStartOf(calendar: Calendar): Calendar = (calendar.clone() as Calendar).apply {
		set(Calendar.DATE, 1)
		set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
	}

	/** 两个自然月相差几个月(都为某月 1 号) */
	private fun monthsBetween(from: Calendar, to: Calendar): Int =
		(to[Calendar.YEAR] - from[Calendar.YEAR]) * 12 + (to[Calendar.MONTH] - from[Calendar.MONTH])

	/** 月历网格中某一天对应的日期(yyyy-MM-dd) */
	private fun dateKeyOfCell(monthStart: Calendar, day: Int): String =
		DateUtils.YMD.format((monthStart.clone() as Calendar).apply { set(Calendar.DATE, day) }.time)

	/**
	 * 手动调休(日历)
	 *
	 * 全程以**具体日期**为操作对象:
	 * - 打开时日历**自动定位到最近日期(今天)所在月份**(不再从开学月翻起), 也可以点「选择日期」跳到任意一天;
	 * - 点某一天可选「放假」/「选择调休」/「清除该日调休」;
	 * - 「选择调休」会打开日历挑选一个**具体日期**, 把那天实际要上的课调到本日期(不再按星期复制);
	 * - 开启「多选」可一次把多天标记为放假。
	 */
	@Composable
	fun ManualAdjustCalendarDialog(activity: BaseActivity, onDismiss: () -> Unit, onMessage: (String) -> Unit) {
		val table = LessonTableRepository.lessonTable
		var refreshTick by remember { mutableIntStateOf(0) }
		var monthOffset by remember { mutableIntStateOf(0) }
		var editingDate by remember { mutableStateOf<String?>(null) }
		/** 「选择调休」: 正在为这个日期挑选"调休来源日期" */
		var pickingSourceFor by remember { mutableStateOf<String?>(null) }
		// 【问题3-③】放假支持多选: 打开"多选"后点日期只是勾选, 再统一标记
		var multiSelect by remember { mutableStateOf(false) }
		var selected by remember { mutableStateOf(setOf<String>()) }
		/** 手动调休分步确认状态: 目标日期 / 源日期 / 是否先还原到最新原始课表 / 当前步骤(0无 1以最新原始课表为准 2是否覆盖原课程) */
		var makeupTarget by remember { mutableStateOf<String?>(null) }
		var makeupSource by remember { mutableStateOf<String?>(null) }
		var makeupRevert by remember { mutableStateOf(false) }
		var makeupStep by remember { mutableIntStateOf(0) }
		/** 【本轮】本次打开日历后是否真的改动过调休(用于"没点完成就退出"时询问是否保存) */
		var dirty by remember { mutableStateOf(false) }
		/** 【本轮】未点"完成"就退出: 询问是否保存(样式与其它"是否保存"弹窗一致) */
		var showSaveConfirm by remember { mutableStateOf(false) }

		/** 以"今天"为基准翻月 —— 打开日历即落在最近日期所在月份 */
		val monthStart = remember(monthOffset, refreshTick) {
			monthStartOf(Calendar.getInstance()).apply { add(Calendar.MONTH, monthOffset) }
		}
		val todayKey = remember(refreshTick) { DateUtils.YMD.format(Calendar.getInstance().time) }

		/** 统一的写盘入口 */
		fun persist(message: String) {
			// 【本轮】记下"本次打开后确实改过调休": 没点"完成"就退出时要据此询问是否保存
			dirty = true
			LessonTableRepository.saveLessonTable()
			LessonTableRepository.notifyLessonTableChanged()
			refreshTick++
			onMessage(message)
		}

		// 【取消按钮】这个日历里每一步操作都是立即写盘的, 所以打开时先备份一份调休数据,
		// 点"取消"时整体还原, 真正做到"这次打开之后的改动都不算数"
		val backupOverrides = remember { LinkedHashMap<String, Int>(table.overrides) }
		val backupDayOverrides = remember {
			LinkedHashMap<String, Array<com.qust.helper.data.lesson.LessonGroup?>>().also { map ->
				table.dayOverrides.forEach { (k, v) -> map[k] = v.copyOf() }
			}
		}

		/** 放弃本次打开后的全部调休改动 */
		fun cancelAll() {
			table.overrides.clear()
			table.overrides.putAll(backupOverrides)
			table.dayOverrides.clear()
			table.dayOverrides.putAll(backupDayOverrides)
			LessonTableRepository.saveLessonTable()
			LessonTableRepository.notifyLessonTableChanged()
			// 【取消无需弹窗】直接关闭日历, 不再弹任何提示(本次打开后的改动仍然会被还原)
			dirty = false
			onDismiss()
		}

		/** 真正执行调休: 把源日期课程复制到目标日期(必要时先还原到最新原始课表)
		 * @param merge 【不覆盖，只加入】true 时保留目标日期原有课程, 只在对应时间加入源日期课程 */
		fun doMakeup(target: String, sourceKey: String, revertFirst: Boolean, merge: Boolean = false) {
			if(revertFirst) revertDayToOriginal(target)
			val src = table.weekDayOfDate(sourceKey) ?: return
			val dst = table.weekDayOfDate(target) ?: return
			table.copyDayByDate(src.first, src.second, dst.first, dst.second, merge = merge)
			// 【修改版】执行其他日期的教学安排: 复制来的课程名追加来源说明(已有后缀不重复)
			val dstKey2 = table.dateKeyOf(dst.first, dst.second)
			table.dayOverrides[dstKey2]?.forEach { g ->
				g?.lessons?.forEach { l ->
					if(!l.name.contains("的教学安排")) l.name = l.name + "（执行" + sourceKey.split("-")[1] + "月" + sourceKey.split("-")[2] + "日的教学安排）"
				}
			}
			// 【根因同类修复】这里同样不能用 setOverride: 它会把刚复制好的 dayOverrides(target) 清掉,
			// 使调休结果退回到"每周课表模板"而不是"源日期当天实际课表"(还会丢掉灰色课程过滤)。
			// 直接写 overrides 登记调休语义即可(日历上仍显示为"调休"), 同时保住复制出的 dayOverrides。
			table.overrides[target] = weekdayOf(sourceKey)
			// 让日历跟着跳到目标日期所在月份, 便于确认结果
			val targetMonth = Calendar.getInstance().apply { time = DateUtils.YMD.parse(target)!! }
			monthOffset += monthsBetween(monthStart, monthStartOf(targetMonth))
			makeupTarget = null; makeupSource = null; makeupRevert = false; makeupStep = 0
			persist("已把 ${dateLabel(sourceKey)} 的课程调到 ${dateLabel(target)}")
		}

		/** 步骤2: 判断目标日当天是否已有课程(会被覆盖), 有则询问是否覆盖原课程 */
		fun checkOverwriteThenApply(target: String, sourceKey: String, revertFirst: Boolean) {
			val (tw, td) = table.weekDayOfDate(target) ?: return
			val hasLesson = table.effectiveDay(tw, td).any { it?.lessons?.any { l -> (l.week and (1L shl tw)) != 0L } == true }
			if(hasLesson) {
				makeupTarget = target; makeupSource = sourceKey; makeupRevert = revertFirst; makeupStep = 2
			} else {
				doMakeup(target, sourceKey, revertFirst)
			}
		}

		/** 手动调休: 把所选"源日期"的课程调到目标日期(分两步确认) */
		fun beginMakeup(target: String, sourceKey: String) {
			if(sourceKey == target) {
				onMessage("不可选择同一天")
				return
			}
			val src = table.weekDayOfDate(sourceKey)
			val dst = table.weekDayOfDate(target)
			if(src == null || dst == null) {
				onMessage("所选日期不在本学期范围内")
				return
			}
			// 需求5: 目标日"最新课表"与"最新原始课表"不一致时, 先让用户选基准(与官网调休一致)
			val original = LessonTableRepository.getOriginalLessonTable()
			if(original != null && dayDiffersFromOriginal(table, original, target)) {
				makeupTarget = target; makeupSource = sourceKey; makeupStep = 1
				return
			}
			checkOverwriteThenApply(target, sourceKey, false)
		}

		// 【本轮】没点"完成"就退出(返回键/点弹窗外): 改动过调休 -> 先问是否保存, 不再静默关闭
		Dialog(onDismissRequest = { if(dirty) showSaveConfirm = true else onDismiss() }) {
			Card(
				modifier = Modifier.fillMaxWidth().padding(12.dp, 36.dp),
				shape = RoundedCornerShape(16.dp),
				elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
			) {
				Column(modifier = Modifier.padding(14.dp)) {
					Text(text = "手动调休", style = MaterialTheme.typography.titleLarge)
					Text(
						text = "点日期可选「放假」、「清除调休」、「选择调休」（选择另一天的课表来上课）；开启多选可一次性把多天标为放假或者清除调休。",
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.secondary,
						modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
					)

					// 月份切换 + 今天 + 选择日期 + 多选
					Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
						TextButton(onClick = { monthOffset-- }) { Text(text = "‹") }
						Text(
							text = "${monthStart[Calendar.YEAR]}年${monthStart[Calendar.MONTH] + 1}月",
							modifier = Modifier.weight(1F),
							style = MaterialTheme.typography.titleMedium,
							textAlign = TextAlign.Center
						)
						TextButton(onClick = { monthOffset++ }) { Text(text = "›") }
					}
					Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
						// 【第二十六轮】"今天"改名"定位今天"; "选择日期"入口已按要求去掉
						TextButton(onClick = { monthOffset = 0 }) { Text(text = "定位今天") }
						Spacer(modifier = Modifier.weight(1F))
						TextButton(onClick = {
							multiSelect = !multiSelect
							if(!multiSelect) selected = emptySet()
						}) { Text(text = if(multiSelect) "退出多选" else "多选") }
					}

					Row(modifier = Modifier.fillMaxWidth()) {
						Data.WEEK_STRING.forEach { name ->
							Text(
								text = name,
								modifier = Modifier.weight(1F),
								style = MaterialTheme.typography.labelMedium,
								color = MaterialTheme.colorScheme.secondary,
								textAlign = TextAlign.Center
							)
						}
					}

					val firstWeekday = (monthStart[Calendar.DAY_OF_WEEK] + 5) % 7   // 周一=0
					val daysInMonth = monthStart.getActualMaximum(Calendar.DATE)
					val cells = ArrayList<Int?>()
					repeat(firstWeekday) { cells.add(null) }
					for(d in 1..daysInMonth) cells.add(d)
					while(cells.size % 7 != 0) cells.add(null)

					Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
						cells.chunked(7).forEach { week ->
							Row(modifier = Modifier.fillMaxWidth()) {
								for(day in week) {
									if(day == null) {
										Spacer(modifier = Modifier.weight(1F).height(44.dp))
										continue
									}
									val dateKey = dateKeyOfCell(monthStart, day)
									val action = table.getOverride(dateKey)
									val isPicked = selected.contains(dateKey)
									val isToday = dateKey == todayKey
									Box(
										modifier = Modifier
											.weight(1F)
											.height(44.dp)
											.padding(2.dp)
											.clip(RoundedCornerShape(8.dp))
											.background(
												when {
													isPicked -> MaterialTheme.colorScheme.primary
													action == ACTION_CLEAR -> MaterialTheme.colorScheme.errorContainer
													action != null -> MaterialTheme.colorScheme.primaryContainer
													isToday -> MaterialTheme.colorScheme.secondaryContainer
													else -> MaterialTheme.colorScheme.surfaceVariant
												}
											)
											// 【第二十九轮·按要求】日历格子统一 8dp 倒角反馈
											.tapRipple(shape = RoundedCornerShape(8.dp)) {
												if(multiSelect) {
													selected = if(isPicked) selected - dateKey else selected + dateKey
												}else{
													editingDate = dateKey
												}
											},
										contentAlignment = Alignment.Center
									) {
										Column(horizontalAlignment = Alignment.CenterHorizontally) {
											Text(
												text = "$day",
												style = MaterialTheme.typography.bodySmall,
												color = if(isPicked) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
											)
											Text(
												text = if(action == ACTION_CLEAR) "放假" else if(action != null) "调休" else "",
												style = MaterialTheme.typography.labelSmall,
												color = if(isPicked) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
											)
										}
									}
								}
							}
						}
					}

					// 多选模式: 批量操作
					if(multiSelect) {
						Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
							Text(
								text = "已选 ${selected.size} 天",
								modifier = Modifier.weight(1F),
								style = MaterialTheme.typography.bodySmall,
								color = MaterialTheme.colorScheme.secondary
							)
							TextButton(onClick = {
								val days = (1..daysInMonth).map { dateKeyOfCell(monthStart, it) }
								selected = if(selected.size == days.size) emptySet() else days.toSet()
							}) { Text(text = "全选本月") }
						}
						Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
							TextButton(
								enabled = selected.isNotEmpty(),
								onClick = {
									selected.forEach { table.setOverride(it, ACTION_CLEAR) }
									val n = selected.size
									selected = emptySet()
									persist("已把 $n 天标记为放假")
								}
							) { Text(text = "标记为放假", color = MaterialTheme.colorScheme.error) }
							TextButton(
								enabled = selected.isNotEmpty(),
								onClick = {
									selected.forEach { key ->
										table.overrides.remove(key)
										table.dayOverrides.remove(key)
									}
									val n = selected.size
									selected = emptySet()
									persist("已清除 $n 天的调休")
								}
							) { Text(text = "清除调休") }
						}
					}

				Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
					// 取消: 还原本次打开后的全部调休改动
					TextButton(onClick = { cancelAll() }) { Text(text = "取消") }
					Spacer(modifier = Modifier.width(8.dp))
					// 【本轮】"完成"= 保留本次改动并关闭, 不再弹询问
					TextButton(onClick = { dirty = false; onDismiss() }) { Text(text = "完成") }
				}
				}
			}
		}

		// 【本轮】未点"完成"就退出: 询问是否保存
		// 标题/文案/按钮与课表编辑界面的"未保存的修改"弹窗完全同款(保存 / 不保存, 无"取消")
		if(showSaveConfirm){
			AlertDialog(
				// 与其它"是否保存"弹窗一致: 点弹窗外不关闭, 必须二选一
				onDismissRequest = { },
				title = { Text(text = "未保存的修改") },
				text = { Text(text = "当前调休安排尚有未保存的修改，是否保存？") },
				confirmButton = {
					TextButton(onClick = {
						showSaveConfirm = false
						dirty = false
						// 调休是即时写盘的: "保存"= 保留本次改动并关闭
						onDismiss()
					}) { Text(text = "保存") }
				},
				dismissButton = {
					TextButton(onClick = {
						showSaveConfirm = false
						// "不保存"= 还原本次打开后的全部调休改动
						cancelAll()
					}) { Text(text = "不保存") }
				}
			)
		}

		// 【问题3-③】选中某个具体日期后的操作(不再提供"选择星期")
		editingDate?.let { dateKey ->
			AlertDialog(
				onDismissRequest = { editingDate = null },
				title = { Text(text = "${dateLabel(dateKey)} 调休设置") },
				text = {
					Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
						val current = table.getOverride(dateKey)
						val options = listOf(
							"放假" to ACTION_CLEAR,
							"选择调休（选择某日期的课程）" to -1,
							"清除该日调休（恢复原课表）" to -2
						)
						options.forEach { (label, act) ->
							Box(
								modifier = Modifier
									.fillMaxWidth()
									.clip(RoundedCornerShape(8.dp))
									.tapRipple(shape = RoundedCornerShape(8.dp)) {
										when(act) {
											-1 -> {
												// 「选择调休」: 打开日历挑选一个具体日期作为调休来源
												editingDate = null
												pickingSourceFor = dateKey
											}
											-2 -> {
												// 【第二十六轮】"清除该日调休"前先判断是否有可清除的内容:
												// 若清除前后课表一致(该日本来就没有调休效果), 与「科大官网同步调休」保持一致的
												// 方式提示"暂无调休安排，无法清除", 且不做任何改动(把刚移除的还原回去)。
												val wd = table.weekDayOfDate(dateKey)
												val before = if(wd != null) table.effectiveDay(wd.first, wd.second) else null
												val beforeHoliday = table.getOverride(dateKey) == ACTION_CLEAR
												val removedOverride = table.overrides.remove(dateKey)
												val removedDayOverride = table.dayOverrides.remove(dateKey)
												val after = if(wd != null) table.effectiveDay(wd.first, wd.second) else null
												editingDate = null
												if(!beforeHoliday && sameDayColumn(before, after)){
													// 前后课表一致: 视为"没有可清除的调休", 还原并提示
													if(removedOverride != null) table.overrides[dateKey] = removedOverride
													if(removedDayOverride != null) table.dayOverrides[dateKey] = removedDayOverride
													onMessage("暂无调休安排，无法清除")
												}else{
													persist("已清除 ${dateLabel(dateKey)} 的调休")
												}
											}
											else -> {
												table.setOverride(dateKey, ACTION_CLEAR)
												editingDate = null
												persist("已把 ${dateLabel(dateKey)} 标记为放假")
											}
										}
									}
									.padding(vertical = 10.dp, horizontal = 4.dp)
							) {
								Text(
									text = if(act == ACTION_CLEAR && current == ACTION_CLEAR) "● $label" else "○ $label",
									style = MaterialTheme.typography.bodyMedium,
									color = MaterialTheme.colorScheme.onSurface
								)
							}
						}
					}
				},
				confirmButton = {
					TextButton(onClick = { editingDate = null }) { Text(text = "取消") }
				}
			)
		}

		// 「选择调休」: 挑选调休来源日期(打开即定位到最近日期)
		pickingSourceFor?.let { target ->
			AdjustDatePickerDialog(
				title = "选择日期（把该日期的课程调到 ${dateLabel(target)}）",
				initialDate = target,
				onDismiss = { pickingSourceFor = null },
				onSelected = { sourceKey ->
					pickingSourceFor = null
					beginMakeup(target, sourceKey)
				}
			)
		}

		// (【第二十六轮】原「选择日期」跳转入口已按要求去掉)

		// 需求5: 手动调休"调休基准课表选择" —— 目标日与最新原始课表不一致时选择基准
		if(makeupStep == 1 && makeupTarget != null){
			AlertDialog(
				onDismissRequest = { makeupTarget = null; makeupSource = null; makeupStep = 0 },
				title = { Text(text = "调休基准课表选择") },
				text = { Text(text = noYear("${dateLabel(makeupTarget!!)} 的当前课表与最新原始课表不一致，本次调休将基于哪份课表？")) },
				// 【本轮】两个按钮互换位置: confirmButton="以当下课表为准", dismissButton="以最新原始课表为准"
				confirmButton = {
					TextButton(onClick = {
						val t = makeupTarget!!; val s = makeupSource!!; makeupStep = 0
						checkOverwriteThenApply(t, s, false)
					}) { Text(text = "以当下课表为准") }
				},
				dismissButton = {
					TextButton(onClick = {
						val t = makeupTarget!!; val s = makeupSource!!; makeupStep = 0
						checkOverwriteThenApply(t, s, true)
					}) { Text(text = "以最新原始课表为准") }
				}
			)
		}

		// 需求3/本轮: 手动调休"课程存在覆盖情况" —— 目标日当天已有课程, 询问覆盖或只加入(给出节次明细)
		if(makeupStep == 2 && makeupTarget != null){
			val t = makeupTarget!!
			val s = makeupSource!!
			val r = makeupRevert
			val tw = table.weekDayOfDate(t)
			val sw = table.weekDayOfDate(s)
			val origTxt = if(tw != null) describeDaySlots(table, tw.first, tw.second) else "无课"
			val afterTxt = if(sw != null) describeDaySlots(table, sw.first, sw.second) else "无课"
			AlertDialog(
				onDismissRequest = { makeupTarget = null; makeupSource = null; makeupRevert = false; makeupStep = 0 },
				title = { Text(text = "课程存在覆盖情况") },
				text = {
					Column {
						Text(text = noYear("${dateLabel(t)} 原课程：$origTxt"), style = MaterialTheme.typography.labelLarge)
						Spacer(modifier = Modifier.height(4.dp))
						Text(text = noYear("覆盖调休后（${dateLabel(s)} 的课程）：$afterTxt"), style = MaterialTheme.typography.labelLarge)
						Spacer(modifier = Modifier.height(8.dp))
						Text(
							text = "「覆盖」：用调休课程替换原课程；\n「不覆盖，只加入」：保留原课程，仅在当天没有课程的节次加入调休课程。",
							style = MaterialTheme.typography.labelLarge
						)
					}
				},
				confirmButton = {
					Column(horizontalAlignment = Alignment.End) {
						TextButton(onClick = { makeupTarget = null; makeupSource = null; makeupRevert = false; makeupStep = 0 }) { Text(text = "取消") }
						TextButton(onClick = {
							makeupTarget = null; makeupSource = null; makeupRevert = false; makeupStep = 0
							doMakeup(t, s, r)
						}) { Text(text = "覆盖") }
						TextButton(onClick = {
							makeupTarget = null; makeupSource = null; makeupRevert = false; makeupStep = 0
							doMakeup(t, s, r, merge = true)
						}) { Text(text = "不覆盖，只加入") }
					}
				}
			)
		}
	}

	/**
	 * 调休用"选择具体日期"日历弹窗
	 *
	 * 打开时自动定位到 [initialDate] 所在月份; 不传时定位到**最近日期(今天)**。
	 */
	@Composable
	fun AdjustDatePickerDialog(
		title: String,
		initialDate: String? = null,
		onlyMonday: Boolean = false,
		onDismiss: () -> Unit,
		onSelected: (String) -> Unit
	) {
		/** 基准月: 传了日期就用该日期所在月, 否则用今天所在月 */
		val base = remember(initialDate) {
			val c = Calendar.getInstance()
			initialDate?.let { key -> try { c.time = DateUtils.YMD.parse(key)!! } catch(_: Exception) { } }
			monthStartOf(c)
		}
		/** 相对基准月的偏移: 打开即为 0, 也就是"最近日期"所在月份 */
		var offset by remember(initialDate) { mutableIntStateOf(0) }
		/** 回到今天需要的偏移量 */
		val todayOffset = remember { monthsBetween(base, monthStartOf(Calendar.getInstance())) }
		val monthStart = remember(offset, base) {
			(base.clone() as Calendar).apply { add(Calendar.MONTH, offset) }
		}

		Dialog(onDismissRequest = { onDismiss() }) {
			Card(
				modifier = Modifier.fillMaxWidth().padding(12.dp, 36.dp),
				shape = RoundedCornerShape(16.dp),
				elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
			) {
				Column(modifier = Modifier.padding(14.dp)) {
					Text(text = title, style = MaterialTheme.typography.titleMedium)
					Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
						TextButton(onClick = { offset-- }) { Text(text = "‹") }
						Text(
							text = "${monthStart[Calendar.YEAR]}年${monthStart[Calendar.MONTH] + 1}月",
							modifier = Modifier.weight(1F),
							style = MaterialTheme.typography.titleMedium,
							textAlign = TextAlign.Center
						)
						TextButton(onClick = { offset++ }) { Text(text = "›") }
					}
					// 【第三十二轮】"今天"改名"定位今天", 且位置安排与「手动调休」日历一致:
					// 单独一行放在月份切换行**下方**、靠左(原先挤在月份行"›"后面)
					Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
						TextButton(onClick = { offset = todayOffset }) { Text(text = "定位今天") }
						Spacer(modifier = Modifier.weight(1F))
					}
					Row(modifier = Modifier.fillMaxWidth()) {
						Data.WEEK_STRING.forEach { name ->
							Text(
								text = name,
								modifier = Modifier.weight(1F),
								style = MaterialTheme.typography.labelMedium,
								color = MaterialTheme.colorScheme.secondary,
								textAlign = TextAlign.Center
							)
						}
					}

					val firstWeekday = (monthStart[Calendar.DAY_OF_WEEK] + 5) % 7
					val daysInMonth = monthStart.getActualMaximum(Calendar.DATE)
					val cells = ArrayList<Int?>()
					repeat(firstWeekday) { cells.add(null) }
					for(d in 1..daysInMonth) cells.add(d)
					while(cells.size % 7 != 0) cells.add(null)
					val todayKey = DateUtils.YMD.format(Calendar.getInstance().time)

					Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
						cells.chunked(7).forEach { week ->
							Row(modifier = Modifier.fillMaxWidth()) {
								for(day in week) {
									if(day == null) {
										Spacer(modifier = Modifier.weight(1F).height(44.dp))
										continue
									}
									val key = dateKeyOfCell(monthStart, day)
									val cellCal = (monthStart.clone() as Calendar).apply { set(Calendar.DATE, day) }
									val isMondayCell = cellCal[Calendar.DAY_OF_WEEK] == Calendar.MONDAY
									val enabled = !onlyMonday || isMondayCell
									Box(
										modifier = Modifier
											.weight(1F)
											.height(44.dp)
											.padding(2.dp)
											.clip(RoundedCornerShape(8.dp))
											.background(
												if(key == todayKey) MaterialTheme.colorScheme.secondaryContainer
												else MaterialTheme.colorScheme.surfaceVariant
											)
											.then(if(enabled) Modifier.tapRipple(shape = RoundedCornerShape(8.dp)) { onSelected(key) } else Modifier),
										contentAlignment = Alignment.Center
									) {
										Text(text = "$day", style = MaterialTheme.typography.bodySmall,
											color = if(enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.secondary)
									}
								}
							}
						}
					}

					Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
						TextButton(onClick = { onDismiss() }) { Text(text = "取消") }
					}
				}
			}
		}
	}

	/**
	 * 调休导入操作区: [从图片导入调休安排] [科大官网同步调休] [手动调休(日历)]
	 * 识别/抓取完成后弹出预览确认
	 */
	/**
	 * 【需求4】App 启动时的教务通知调课弹窗。
	 *
	 * 触发点从"进入调休管理页"改为"软件一打开": 在首页(HomePage)挂载, 进入软件即静默检查
	 * 最近一周内、未应用、且未被用户忽略过的教务通知调课/补课/停课, 有则弹窗询问是否应用。
	 * - 用户点"应用" -> 列表暂存到 [launchPendingEAS] 并跳转到调休管理页, 由那里的预览弹窗接管;
	 * - 用户点"暂不"/关闭 -> 记录签名到 Settings, 下次启动不再弹。
	 */
	var launchPendingEAS: List<EASNoticeChange>? = null

	@Composable
	fun EASLaunchPrompt(activity: BaseActivity) {
		var enterPromptEAS by remember { mutableStateOf<List<EASNoticeChange>?>(null) }
		LaunchedEffect(Unit) {
			runCatching {
				withContext(Dispatchers.IO){ fetchEASNoticeChanges() }
			}.onSuccess { changes ->
				if(changes.isEmpty()) return@onSuccess
				// 【第二十三轮】预演后课表没有实际变化的(全为空操作), 启动时也不再弹窗打扰
				if(!easChangesWouldChangeTable(LessonTableRepository.lessonTable, changes)) return@onSuccess
				val withinWeek = changes.filter { isWithinWeek(it.time) }
				if(withinWeek.isEmpty()) return@onSuccess
				val dismissed = Setting.getStringSet("eas_dismissed_changes")
				val pending = withinWeek.filter { !dismissed.contains(easChangeSignature(it)) }
				if(pending.isNotEmpty()){
					enterPromptEAS = pending
					// 【修改版】一旦弹过就记为已提示: 下次进入不再重复弹出(即使未应用/应用失败)
					try {
						val sigs0 = pending.map { easChangeSignature(it) }.toSet()
						val dis0 = Setting.getStringSet("eas_dismissed_changes").toMutableSet()
						dis0.addAll(sigs0)
						Setting.edit { it.putStringSet("eas_dismissed_changes", dis0) }
					}catch(_: Exception){ }
					// 【修改版】10秒后无论弹窗是否被关闭/退出界面, 都在后台自动执行
					// 【本轮】后台执行与点击「教务通知调课」完全一致:
					// 查询课表 -> 保存新课表(含调休与用户修改) -> 写入通知调课
					kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Default) {
						kotlinx.coroutines.delay(10_000)
						try {
							val cur = enterPromptEAS
							if(cur != null){
								applyEASNoticeFlow(cur)
								val sigs = cur.map { easChangeSignature(it) }.toSet()
								val dis = Setting.getStringSet("eas_dismissed_changes").toMutableSet()
								dis.addAll(sigs)
								Setting.edit { it.putStringSet("eas_dismissed_changes", dis) }
								enterPromptEAS = null
							}
						}catch(e: Exception){ Logger.e(e) }
					}
					// 【第四十八轮】"软件外"消息提醒: 在系统通知栏弹出一条教务通知(类似QQ/微信通知)
					withContext(Dispatchers.Main) {
						if(!Notices.hasPermission(activity)) Notices.requestPermission(activity, 40212)
						val names = pending.take(3).joinToString("、") { describeEASDated(it) }
						Notices.notify(
							activity, 40212,
							"教务通知调课",
							"收到 ${pending.size} 条新的调课/补课/停课安排：$names" + if(pending.size > 3) " 等" else ""
						)
					}
				}
			}
		}
		if(enterPromptEAS != null){
			val list = enterPromptEAS!!
			// 【第二十二轮】把"这一批已提示过"的签名写入 Setting: 无论是"暂不"还是"应用",
			// 第二次进入软件都不会再重复弹窗提示同一批教务通知(应用后课表已写入, 也不会再被判为新)。
			val rememberShown: () -> Unit = {
				val sigs = list.map { easChangeSignature(it) }.toSet()
				val dismissed = Setting.getStringSet("eas_dismissed_changes").toMutableSet()
				dismissed.addAll(sigs)
				Setting.edit { it.putStringSet("eas_dismissed_changes", dismissed) }
			}
			// 【修改版】10秒自动执行: 应用过程在全局协程里后台运行, 关闭弹窗/退出界面也会执行完
			val autoApplyEAS: () -> Unit = {
				rememberShown()
				enterPromptEAS = null
				kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Default) {
					try {
						// 【本轮】与点击「教务通知调课」同一套动作: 查询 -> 保存 -> 写入通知调课
						applyEASNoticeFlow(list)
					}catch(e: Exception){ Logger.e(e) }
				}
			}
			AlertDialog(
				onDismissRequest = { }, // 【修改版】关闭弹窗不取消, 10秒后仍会后台自动执行
				title = { Text(text = "教务通知调课") },
				text = {
					Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
						// 【修改版】只展示教务通知原文
						// 【本轮】原文只显示"本学期 + 最近一周内"发布的、且正文涉及调课/补课/停课的教务通知
						val raw = synchronized(lastEASNoticeText){
							// 学期范围(开学日 ~ 开学日+总周数*7)
							val termStart = com.qust.helper.model.LessonTableRepository.startDay
							val termDays = (com.qust.helper.model.LessonTableRepository.totalWeek.coerceAtLeast(1)) * 7
							val weekAgo = Calendar.getInstance().apply { add(Calendar.DATE, -7) }.time
							lastEASNoticeText.toString().lines().filter { ln ->
								if(!ln.startsWith("[")) return@filter false
								val body = ln.substringAfter("] ", "")
								// 只保留调课/补课/停课相关通知
								if(!(body.contains("调课") || body.contains("补课") || body.contains("停课"))) return@filter false
								val t = ln.removePrefix("[").substringBefore("]").trim().take(10)
								val d = try { com.qust.helper.utils.DateUtils.YMD.parse(t) } catch(_: Exception){ null } ?: return@filter false
								// 最近一周内发布
								if(d.before(weekAgo)) return@filter false
								try {
									val end = termStart.clone() as java.util.Date
									end.time = termStart.time + termDays * 24L * 3600L * 1000L
									!d.before(termStart) && !d.after(end)
								} catch(_: Exception){ true }
							}.let { lines ->
								// 【本期】"通知原文重复"修复: 教务对同一次调课会下发两条措辞几乎一样的通知
								// (取课程名的字段不同, 发布时间连秒都一样), 展示前按"同时间 + 同数字 + 高度相似"
								// 合并, 只留信息更全的一条。
								com.qust.helper.utils.NoticeTextUtils.dedupeNotices(lines)
							}.joinToString("\n")
						}
						if(raw.isNotEmpty()){
							Spacer(modifier = Modifier.height(8.dp))
							Text(text = "教务通知原文：", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
							Text(text = raw.trim(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
						}
					}
				},
				confirmButton = {
					TextButton(onClick = {
						// 点"应用"立即在后台执行(与倒计时自动执行相同路径)
						autoApplyEAS()
					}) { Text(text = "立即应用") }
				},
			)
		}
	}

	// ---------------- 考试提醒(考试前一天) ----------------

	/** 教务通知里解析出的一条考试安排 */
	data class ExamNotice(
		val dateKey: String,      // 考试日期 yyyy-MM-dd
		val name: String,         // 课程名称
		val timeRange: String,    // 考试时段(形如 "09:00-11:00")
		val place: String         // 考试地点
	) {
		/** 同一场考试的去重签名(一场考试只提醒一次) */
		val signature: String get() = "$dateKey|$name"
	}

	private val EXAM_DATE = Regex("(\\d{4})-(\\d{1,2})-(\\d{1,2})")
	private val EXAM_TIME_RANGE = Regex("[（(]([^）)]*)[）)]")

	/**
	 * 解析教务通知里的考试提醒正文, 形如:
	 * "您有一场考试需要参加！考试科目：D206060121/大学物理B1;考试时间：2026-07-15(09:00-11:00);考试地点：明德楼302"
	 * @return 解析成功返回 [ExamNotice]; 非考试通知/缺少考试日期返回 null
	 */
	fun parseExamNotice(content: String): ExamNotice? {
		if(!content.contains("考试")) return null
		var name = ""; var dateKey = ""; var timeRange = ""; var place = ""
		for(seg in content.split(Regex("[;；]"))) {
			val s = seg.trim()
			when {
				s.contains("考试科目") -> name = s.substringAfter("考试科目").trimStart(':', '：').trim()
				s.contains("考试时间") -> {
					val t = s.substringAfter("考试时间").trimStart(':', '：').trim()
					dateKey = EXAM_DATE.find(t)?.let { m ->
						"${m.groupValues[1]}-${m.groupValues[2].padStart(2, '0')}-${m.groupValues[3].padStart(2, '0')}"
					} ?: ""
					timeRange = EXAM_TIME_RANGE.find(t)?.groupValues?.getOrNull(1)?.trim() ?: ""
				}
				s.contains("考试地点") -> place = s.substringAfter("考试地点").trimStart(':', '：').trim()
			}
		}
		if(dateKey.isEmpty()) return null
		// 课程名称形如 "D206060121/大学物理B1": 去掉课程代码, 只留课程名
		if(name.contains('/')) name = name.substringAfterLast('/').trim()
		return ExamNotice(dateKey, name, timeRange, place)
	}

	/**
	 * 抓取"明天有考试"的教务通知(教务处会在**考试前一天 18:00** 下发"您有一场考试需要参加！"通知)。
	 * 只保留考试日期=明天的条目; 未登录/网络异常返回空列表(静默跳过, 不打扰用户)。
	 * 阻塞网络IO, 请在 Dispatchers.IO 中调用。
	 */
	suspend fun fetchTomorrowExamNotices(): List<ExamNotice> {
		val account = EASAccount.getInstance()
		if(!account.checkLogin()) return emptyList()
		// 考试通知在前一天 18:00 下发, 位于通知列表最前面; 抓 2 页(60 条)足够
		val all = ArrayList<Notice>()
		var page = 1
		while(page <= 2) {
			val batch = account.queryNotice(page, 30)
			if(batch.isEmpty()) break
			all.addAll(batch)
			if(batch.size < 30) break
			page++
		}
		val tomorrow = Calendar.getInstance().apply { add(Calendar.DATE, 1) }
		val tomorrowKey = DateUtils.YMD.format(tomorrow.time)
		val out = ArrayList<ExamNotice>()
		for(notice in all) {
			val exam = parseExamNotice(notice.content) ?: continue
			if(exam.dateKey == tomorrowKey) out.add(exam)
		}
		// 同一场考试可能有多条通知, 按签名去重
		return out.distinctBy { it.signature }
	}

	/** 记录"已提醒过"的考试签名(同一场考试只弹一次; 顺带清掉两周前的旧记录防止无限增长) */
	private fun rememberExamReminded(list: List<ExamNotice>) {
		val cutoff = Calendar.getInstance().apply { add(Calendar.DATE, -14) }.time
		val kept = Setting.getStringSet("exam_reminded").filter { sig ->
			val d = DateUtils.YMD.parse(sig.substringBefore('|'))
			d == null || !d.before(cutoff)
		}.toMutableSet()
		kept.addAll(list.map { it.signature })
		Setting.edit { it.putStringSet("exam_reminded", kept) }
	}

	/**
	 * 【第二十二轮·需求】考试前一天提醒: 打开软件时检查"明天是否有考试"
	 * (来源=教务通知里的考试提醒), 有则弹窗列出科目/时间/地点; 同一场考试只提醒一次。
	 */
	@Composable
	fun ExamReminderPrompt() {
		var exams by remember { mutableStateOf<List<ExamNotice>?>(null) }
		LaunchedEffect(Unit) {
			runCatching {
				withContext(Dispatchers.IO){ fetchTomorrowExamNotices() }
			}.onSuccess { list ->
				if(list.isEmpty()) return@onSuccess
				val reminded = Setting.getStringSet("exam_reminded")
				val pending = list.filter { !reminded.contains(it.signature) }
				if(pending.isNotEmpty()) exams = pending
			}
		}
		val list = exams ?: return
		AlertDialog(
			onDismissRequest = { rememberExamReminded(list); exams = null },
			title = { Text(text = "考试提醒（明天有考试）") },
			text = {
				Column {
					Text(
						text = "明天共有 ${list.size} 场考试，请提前做好准备：",
						style = MaterialTheme.typography.bodyMedium
					)
					Spacer(modifier = Modifier.height(8.dp))
					list.forEach { e ->
						Text(
							text = "· ${e.name}${if(e.timeRange.isNotEmpty()) "（${e.timeRange}）" else ""}" +
								if(e.place.isNotEmpty()) "\n    地点：${e.place}" else "",
							style = MaterialTheme.typography.bodySmall
						)
						Spacer(modifier = Modifier.height(4.dp))
					}
				}
			},
			confirmButton = {
				TextButton(onClick = { rememberExamReminded(list); exams = null }) { Text(text = "知道了") }
			}
		)
	}

	@Composable
	fun ScheduleAdjustActions(activity: BaseActivity, onMessage: (String) -> Unit, locked: Boolean = false) {
		val context = LocalContext.current
		val scope = rememberCoroutineScope()
		var showProgress by remember { mutableStateOf(false) }
		var progressText by remember { mutableStateOf("") }
		var pendingChanges by remember { mutableStateOf<List<ChangeItem>>(emptyList()) }
		/** 教务通知调课/补课(节次级)的预览列表 */
		var pendingEAS by remember { mutableStateOf<List<EASNoticeChange>>(emptyList()) }
		/** 需求H: 调休应用前发现"最新课表与最新原始课表不一致"时的冲突日期(官网调休) */
		var conflictChanges by remember { mutableStateOf<List<ChangeItem>?>(null) }
		/** 需求H: 同上, 但针对教务通知调课 */
		var conflictEAS by remember { mutableStateOf<List<EASNoticeChange>?>(null) }
		/** 需求3: 官网调休应用前, 目标日当天已有课程会被覆盖, 需先询问是否覆盖原课程 */
		var overwriteWebChanges by remember { mutableStateOf<List<ChangeItem>?>(null) }
		/** 需求3: 教务通知调课应用前, 补课目标节次已有课程会被覆盖, 需先询问是否覆盖原课程 */
		var overwriteEAS by remember { mutableStateOf<List<EASNoticeChange>?>(null) }
		// (需求4 弹窗触发点已移至 App 启动: 见 EASLaunchPrompt; 进入调休管理页不再自动弹)
		// 【需求4】弹窗触发点已移至 App 启动(见 EASLaunchPrompt): 进入调休管理页不再自动弹。
		// 若用户是从启动弹窗点"应用"跳转过来的, 这里把暂存的列表接过来, 直接进预览。
		// 【第五十九轮】启动弹窗点"应用"后: 先查询并保存最新课表(保留调休调课, 效果=课表查询勾选3项保存),
		// 再判断"执行这些调课后相关日期是否发生变化": 全部无变化 -> "暂无新的调课安排"; 有变化 -> 只预览会变化的调整。
		LaunchedEffect(Unit) {
			launchPendingEAS?.let { incoming ->
				launchPendingEAS = null
				showProgress = true
				progressText = "正在查询并保存最新课表..."
				// 【本轮】与点击「教务通知调课」一致: 查询 -> 保存(含调休与用户修改) -> 写入通知调课
				val applied = withContext(Dispatchers.IO){
					runCatching { applyEASNoticeFlow(incoming) }.getOrDefault(0)
				}
				showProgress = false
				onMessage(if(applied > 0) "已完成教务通知调课" else "已查询并保存课表，暂无新的调课安排")
			}
		}
		/** 本次识别的原文(截图 OCR 文本 / 官网正文), 在识别结果弹窗里可展开核对 */
		var recognizedText by remember { mutableStateOf("") }
		/** 没解析出内容时的原文, 单独展示 */
		var failedText by remember { mutableStateOf("") }
		/** 是否展开"手动调休(日历)" */
		var showCalendar by remember { mutableStateOf(false) }

		val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){ uri ->
			if(uri == null) return@rememberLauncherForActivityResult
			showProgress = true
			progressText = "正在识别图片..."
			scope.launch {
				val result = runCatching {
					withContext(Dispatchers.IO){
						val image = InputImage.fromFilePath(context, uri)
						val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
						val text = suspendCancellableCoroutine<String>{ cont ->
							recognizer.process(image)
								.addOnSuccessListener{ cont.resume(it.text) }
								.addOnFailureListener{ cont.resumeWithException(it) }
						}
						recognizer.close()
						text
					}
				}
				showProgress = false
				result.onSuccess{ text ->
					val now = Calendar.getInstance()
					val changes = parseText(text, now[Calendar.YEAR], now[Calendar.MONTH] + 1)
					// 识别阶段: 原文始终保留, 供"识别结果"弹窗里展开核对
					recognizedText = text
					// 【第四十九轮】恢复原逻辑(用户明确要求): 图片导入每次都展示识别到的调休结果,
					// 不再做"已写入过就不再显示"的过滤(第二次导入同样进入预览)。
					if(changes.isEmpty()){
						failedText = text
						onMessage("未识别到调休信息")
					}else{
						// 【按要求保留】只显示"执行后前后不一致"的条目(已执行过的调休不再重复出现)
						val effective = filterEffectiveChanges(changes)
						if(effective.isNotEmpty()) pendingChanges = effective
						else onMessage("暂无新的调休安排")
					}
				}.onFailure{ e ->
					onMessage("图片识别失败：${e.message ?: "未知错误"}")
				}
			}
		}

		// 2x2 调休导入操作区:
		// [从图片导入调休安排] [科大官网同步调休]
		// [手动调休]           [教务通知调课]
		//
		// 【第三十二轮】两栏各占半屏, 按钮在自己那半栏里居中(左栏中心 = 1/4, 右栏中心 = 3/4)。
		// 【第三十三轮·按要求】第一行两个按钮**位置保持不变**; 只调第二行:
		//   「手动调休」的左缘要对齐「从图片导入调休安排」的左缘,
		//   「教务通知调课」的右缘要对齐「科大官网同步调休」的右缘。
		// 两行按钮字数不同("手动调休"4 字 vs "从图片导入调休安排"8 字), 宽度天然不等,
		// 光靠"居中/靠边"对不齐 —— 所以把第一行按钮在**本栏内的左右留白**量出来,
		// 第二行原样沿用同一份留白(按钮自身宽度不变, 只挪位置)。
		var leftInset by remember { mutableIntStateOf(0) }
		var rightInset by remember { mutableIntStateOf(0) }
		val density = LocalDensity.current
		val leftInsetDp = with(density) { leftInset.toDp() }
		val rightInsetDp = with(density) { rightInset.toDp() }

		Row(modifier = Modifier.fillMaxWidth()) {
			Box(modifier = Modifier.weight(1F), contentAlignment = Alignment.Center) {
			TextButton(onClick = { imagePicker.launch("image/*") }, enabled = !locked,
				modifier = Modifier.onGloballyPositioned{ c ->
					val x = c.positionInParent().x.roundToInt()
					if(x != leftInset) leftInset = x
				},
				colors = textActionFilledColors(),
				contentPadding = PaddingValues(horizontal = TEXT_ACTION_H_PADDING, vertical = TEXT_ACTION_V_PADDING)) {
				Text(text = "从图片导入调休安排", maxLines = 1)
			}
			}
			Box(modifier = Modifier.weight(1F), contentAlignment = Alignment.Center) {
			TextButton(onClick = {
				showProgress = true
				progressText = "正在访问科大官网..."
				scope.launch {
					val result = runCatching {
						withContext(Dispatchers.IO){ fetchWebChanges() }
					}
					result.onSuccess{ changes ->
						if(changes.isEmpty()){ onMessage("官网暂未发现新的调休通知"); return@onSuccess }
						// 【按要求保留】与图片识别一致: 只显示"执行后前后不一致"的官网调休安排
						val effective = filterEffectiveChanges(changes)
						if(effective.isEmpty()) onMessage("暂无新的调休安排")
						else pendingChanges = effective
					}.onFailure{ e ->
						// 我们主动抛出的中文提示(如"官网暂无近期的调休安排")直接展示, 其它按网络异常处理
						val msg = e.message
						onMessage(if(msg != null && msg.any { it.code in 0x4E00..0x9FFF }) msg else "网络异常，请手动导入截图")
					}
					showProgress = false
				}
			}, enabled = !locked,
				modifier = Modifier.onGloballyPositioned{ c ->
					// 右栏按钮在本栏内的左留白 = 本栏宽度 - 按钮宽度 - 左留白, 也就是"距本栏右缘的留白"
					val x = c.positionInParent().x.roundToInt()
					val right = (c.parentLayoutCoordinates?.size?.width ?: return@onGloballyPositioned) - x - c.size.width
					if(right != rightInset) rightInset = right
				},
				colors = textActionFilledColors(),
				contentPadding = PaddingValues(horizontal = TEXT_ACTION_H_PADDING, vertical = TEXT_ACTION_V_PADDING)) {
				Text(text = "科大官网同步调休", maxLines = 1)
			}
			}
		}

		Row(modifier = Modifier.fillMaxWidth()) {
			Box(
				modifier = Modifier.weight(1F).padding(start = leftInsetDp),
				contentAlignment = Alignment.CenterStart
			) {
			TextButton(onClick = { showCalendar = true }, enabled = !locked,
				colors = textActionFilledColors(),
				contentPadding = PaddingValues(horizontal = TEXT_ACTION_H_PADDING, vertical = TEXT_ACTION_V_PADDING)) {
				Text(text = "手动调休", maxLines = 1)
			}
			}
			Box(
				modifier = Modifier.weight(1F).padding(end = rightInsetDp),
				contentAlignment = Alignment.CenterEnd
			) {
			// 【第五十九/六十轮】点击后: 读取教务通知 -> 只预览"执行后会产生变化"的调整; 全无变化提示"暂无新的调课安排"
			TextButton(onClick = {
				showProgress = true
				progressText = "查询中"
				scope.launch {
					val result = runCatching {
						withContext(Dispatchers.IO){ fetchEASNoticeChanges() }
					}
					showProgress = false
					result.onSuccess{ changes ->
						if(changes.isEmpty()){ onMessage("教务通知暂未发现调课/补课/停课安排"); return@onSuccess }
						// 【本轮】点击即执行: 查询课表 -> 保存新课表(含调休与用户修改) -> 写入教务通知调课
						showProgress = true
						progressText = "正在查询并保存课表…"
						scope.launch {
							val applied = runCatching {
								withContext(Dispatchers.IO){ applyEASNoticeFlow(changes) }
							}
							showProgress = false
							applied.onSuccess{ n ->
								onMessage(
									if(n > 0) "已完成教务通知调课"
									else "已查询并保存课表，暂无新的调课安排"
								)
							}.onFailure{ e ->
								onMessage(
									when {
										e is NeedLoginException -> "请先在教务系统登录后再同步"
										e.message != null && e.message!!.any { it.code in 0x4E00..0x9FFF } -> e.message!!
										else -> "读取教务通知失败，请检查登录或网络"
									}
								)
							}
						}
					}.onFailure{ e ->
						onMessage(
							when {
								e is NeedLoginException -> "请先在教务系统登录后再同步"
								e.message != null && e.message!!.any { it.code in 0x4E00..0x9FFF } -> e.message!!
								else -> "读取教务通知失败，请检查登录或网络"
							}
						)
					}
				}
			}, enabled = !locked,
				colors = textActionFilledColors(),
				contentPadding = PaddingValues(horizontal = TEXT_ACTION_H_PADDING, vertical = TEXT_ACTION_V_PADDING)) {
				Text(text = "教务通知调课", maxLines = 1)
			}
			}
		}

		if(showCalendar){
			ManualAdjustCalendarDialog(
				activity = activity,
				onDismiss = { showCalendar = false },
				onMessage = { onMessage(it) }
			)
		}

		if(showProgress){
			Dialogs.IndeterminateProgressDialog(dialogText = progressText, onDismissRequest = { })
		}

		if(pendingChanges.isNotEmpty()){
			val list = pendingChanges
			ChangePreviewDialog(
				lines = previewLines(LessonTableRepository.lessonTable, list),
				rawText = recognizedText,
				onDismiss = { pendingChanges = emptyList() },
				onConfirm = {
					// 【第三十六轮·口径修正】差异 = 相关日期"旧课表(最初查询到的课表)与当下课表"的区别;
					// 前后一致(无差异)时不弹基准选择, 直接进入覆盖检查/写入。
					val before = LessonTableRepository.getOriginalLessonTable() ?: LessonTableRepository.lessonTable
					val hasDiff = list.map { it.dateKey }.distinct().any { diffDayAllSlots(before, LessonTableRepository.lessonTable, it).isNotEmpty() }
					if(hasDiff){
						// 先关预览弹窗, 改弹"最初/最新"选择框(内含全部差异)
						pendingChanges = emptyList()
						conflictChanges = list
					} else {
						// 需求3: 目标日当天已有课程会被覆盖, 先询问是否覆盖原课程
						val overwrite = list.filter { it.action != ACTION_CLEAR && targetDayHasLesson(LessonTableRepository.lessonTable, it.dateKey) }
						if(overwrite.isNotEmpty()){
							pendingChanges = emptyList()
							overwriteWebChanges = list
						} else {
							val cleared = applyChanges(LessonTableRepository.lessonTable, list)
							pendingChanges = emptyList()
							onMessage(
								if(cleared > 0) "已写入${list.size}条调休安排（其中${cleared}天另有手动改课，已按调休生效）"
								else "已写入${list.size}条调休安排"
							)
						}
					}
				}
			)
		}

		// 教务通知调课/补课(节次级)预览确认
		if(pendingEAS.isNotEmpty()){
			val list = pendingEAS
			EASChangePreviewDialog(
				changes = list,
				onDismiss = { pendingEAS = emptyList() },
				onConfirm = {
					// 【第五十四轮】查询/保存最新课表已移到"启动弹窗点应用后"(按需求),
					// 这里保持原流程: 先判断相关日期"最先课表 vs 当下课表"的差异, 有差异弹核对, 无差异执行调课
					scope.launch {
					// 【第三十六轮·口径修正】差异 = 相关日期"旧课表(最初查询到的课表)与当下课表"的区别;
					// 【第六十二轮】按节次核对: 只核对"本次调课涉及的具体节次"(如只调 1-2 节, 就只看 1-2 节),
					// 同一天其它节次的改动不参与核对; 恢复课表同样只恢复这些节次。
					// 另: 仅"调整教室"的调课默认以最新原始课表为准, 无需弹窗选择。
					val table = LessonTableRepository.lessonTable
					val before = LessonTableRepository.getOriginalLessonTable() ?: table
					val affected = easAffectedSlots(list)
					val onlyLocation = list.all { it.kind == EASNoticeChange.KIND_LOCATION }
					val hasDiff = affected.any { (dk, slots) -> diffDayAllSlots(before, table, dk, slots).isNotEmpty() }
					if(onlyLocation && hasDiff){
						// 仅调整教室: 默认以最新原始课表为准, 只恢复相关日期的相关节次, 然后直接执行
						affected.forEach { (dk, slots) -> revertSlotsToOriginal(dk, slots) }
						val applied = applyEASChanges(LessonTableRepository.lessonTable, list)
						pendingEAS = emptyList()
						onMessage(
							if(applied > 0) "已按最新原始课表写入${applied}条调课安排（仅调整教室，只恢复相关节次）"
							else "没有可写入的调课安排（课程可能不在该节次）"
						)
					}else if(!hasDiff){
						// 需求3: 无基准冲突时, 若补课目标节次已有课程, 先询问是否覆盖原课程
						val ov = list.filter { it.kind == EASNoticeChange.KIND_MAKEUP && easTargetSlotHasLesson(LessonTableRepository.lessonTable, it) }
						if(ov.isNotEmpty()){
							pendingEAS = emptyList()
							overwriteEAS = list
						}else{
							val applied = applyEASChanges(LessonTableRepository.lessonTable, list)
							pendingEAS = emptyList()
							onMessage(
							if(applied > 0) "已写入${applied}条教务通知调课/补课/停课（改地点/加课/停课，未改动其它课程）"
							else "没有可写入的调课/补课/停课（课程可能不在该节次）"
							)
						}
					}else{
						pendingEAS = emptyList()
						conflictEAS = list
					}
					}
				}
			)
		}

		// 需求H: 调休基准课表选择 —— 最新课表与最新原始课表不一致时, 让用户选基准
		if(conflictChanges != null || conflictEAS != null){
			val dates = (conflictChanges?.map { it.dateKey } ?: emptyList()) +
				(conflictEAS?.map { it.dateKey } ?: emptyList())
			// 【第六十二轮】教务通知调课按"具体节次"核对/恢复: 每天只处理本次调课涉及的节次
			val easAffected = conflictEAS?.let { easAffectedSlots(it) }
			AlertDialog(
				onDismissRequest = { conflictChanges = null; conflictEAS = null },
				title = { Text(text = "调休基准课表选择") },
			text = {
				// 【第三十六轮·口径修正】调休识别到的差异 = 相关日期**旧课表(最初查询到的课表)与当下课表**的
				// 区别, 不是"应用调休后"与旧课表的差异。
				val curTable = LessonTableRepository.lessonTable
				val beforeTable = LessonTableRepository.getOriginalLessonTable() ?: curTable
				Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
					Text(
						text = "以下日期的最新原始课表与当下课表不一致（原 → 现），调休将基于哪份课表？",
						style = MaterialTheme.typography.bodyMedium
					)
					Spacer(modifier = Modifier.height(8.dp))
					// 只显示确实存在新旧差异的日期, 且日期前不再显示圆点
					val diffDates = dates.distinct().filter { dk ->
						diffDayAllSlots(beforeTable, curTable, dk, easAffected?.get(dk)).isNotEmpty()
					}
						if(diffDates.isEmpty()){
							Text(
								text = "相关日期的新旧课表已一致，无需选择基准，将直接应用调休/调课安排。",
								style = MaterialTheme.typography.bodyMedium
							)
						}else{
							diffDates.forEach { dk ->
								val label = dateLabel(dk)
								Text(text = noYear(label), style = MaterialTheme.typography.bodyMedium)
								diffDayAllSlots(beforeTable, curTable, dk, easAffected?.get(dk)).forEach { line ->
									Text(text = noYear("    差异：$line"), style = MaterialTheme.typography.bodySmall)
								}
							// 需求4: 同时列出本次将应用到该日期的调休/调课安排(含日期, 形如 "10月2号上9月1号的课程")
							val arr = ArrayList<String>()
							conflictChanges?.filter { it.dateKey == dk }?.forEach { item ->
								arr.add(changeArrangementText(LessonTableRepository.lessonTable, item))
							}
							conflictEAS?.filter { it.dateKey == dk }?.forEach { ch ->
								arr.add(describeEASDated(ch))
							}
							if(arr.isNotEmpty()){
								Text(text = noYear("    本次安排：${arr.joinToString("；")}"), style = MaterialTheme.typography.bodySmall)
							}
								Spacer(modifier = Modifier.height(4.dp))
							}
						}
				}
			},
				// 【本轮】两个按钮互换位置: confirmButton="以当下课表为准", dismissButton="以最新原始课表为准"
				confirmButton = {
					TextButton(onClick = {
						// 以最新课表为准: 官网/手动直接写; 教务通知调课需先检查覆盖
						val cc = conflictChanges
						val ce = conflictEAS
						conflictChanges = null; conflictEAS = null
						if(ce != null){
							// 需求3: 以最新课表为准且补课目标节次已有课程 -> 先询问是否覆盖原课程
							val ov = ce.filter { it.kind == EASNoticeChange.KIND_MAKEUP && easTargetSlotHasLesson(LessonTableRepository.lessonTable, it) }
							if(ov.isNotEmpty()){
								overwriteEAS = ce
							}else{
								val applied = applyEASChanges(LessonTableRepository.lessonTable, ce)
								onMessage(if(applied > 0) "已按最新课表应用${applied}条教务通知调课/补课/停课" else "已按最新课表应用${ce.size}条教务通知调课/补课/停课")
							}
						}else if(cc != null){
							// 需求3: 以最新课表为准且目标日当天已有课程 -> 先询问是否覆盖原课程(与教务通知调课一致)
							val ov = cc.filter { it.action != ACTION_CLEAR && targetDayHasLesson(LessonTableRepository.lessonTable, it.dateKey) }
							if(ov.isNotEmpty()){
								overwriteWebChanges = cc
							}else{
								val cleared = applyChanges(LessonTableRepository.lessonTable, cc)
								onMessage(
									if(cleared > 0) "已写入${cc.size}条调休安排（其中${cleared}天另有手动改课，已按调休生效）"
									else "已写入${cc.size}条调休安排"
								)
							}
						}
					}) { Text(text = "以当下课表为准") }
				},
				dismissButton = {
					TextButton(onClick = {
						// 以最新原始课表为准: 官网/手动调休按整天还原; 【第六十二轮】教务通知调课只还原"相关日期的相关节次"
						conflictChanges?.map { it.dateKey }?.distinct()?.forEach { revertDayToOriginal(it) }
						easAffected?.forEach { (dk, slots) -> revertSlotsToOriginal(dk, slots) }
						conflictChanges?.let { applyChanges(LessonTableRepository.lessonTable, it) }
						conflictEAS?.let { applyEASChanges(LessonTableRepository.lessonTable, it) }
						val n = (conflictChanges?.size ?: 0) + (conflictEAS?.size ?: 0)
						conflictChanges = null; conflictEAS = null
						onMessage("已按最新原始课表应用${n}条调休/调课安排")
					}) { Text(text = "以最新原始课表为准") }
				}
			)
		}

		// 需求3/本轮: 官网调休"课程存在覆盖情况"弹窗 —— 目标日当天已有课程, 给出原课程节次与覆盖后课程
		if(overwriteWebChanges != null){
			val list = overwriteWebChanges!!
			val lines = remember(list) { webOverwriteLines(LessonTableRepository.lessonTable, list) }
			AlertDialog(
				onDismissRequest = { overwriteWebChanges = null },
				title = { Text(text = "课程存在覆盖情况") },
				text = {
					Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
						Text(
							text = "以下调休的目标日期当天已有课程，覆盖情况如下：",
							style = MaterialTheme.typography.bodyMedium
						)
						Spacer(modifier = Modifier.height(8.dp))
						lines.forEach { line ->
							Text(text = line, style = MaterialTheme.typography.labelLarge)
							Spacer(modifier = Modifier.height(4.dp))
						}
						Text(
							text = "「覆盖」：用调休课程替换原课程；\n「不覆盖，只加入」：保留原课程，仅在当天没有课程的节次加入调休课程。",
							style = MaterialTheme.typography.labelLarge
						)
					}
				},
				confirmButton = {
					Column(horizontalAlignment = Alignment.End) {
						TextButton(onClick = { overwriteWebChanges = null }) { Text(text = "取消") }
						TextButton(onClick = {
							val cleared = applyChanges(LessonTableRepository.lessonTable, list)
							overwriteWebChanges = null
							onMessage(
								if(cleared > 0) "已覆盖并写入${list.size}条调休安排（其中${cleared}天另有手动改课，已按调休生效）"
								else "已覆盖并写入${list.size}条调休安排"
							)
						}) { Text(text = "覆盖") }
						TextButton(onClick = {
							// 【不覆盖，只加入】保留目标日原有课程, 只在没有课程的节次加入调休课程
							applyChanges(LessonTableRepository.lessonTable, list, merge = true)
							overwriteWebChanges = null
							onMessage("已写入${list.size}条调休安排（保留原课程，只在没有课程的节次加入调休课程）")
						}) { Text(text = "不覆盖，只加入") }
					}
				}
			)
		}

		// 需求3/本轮: 教务通知调课"课程存在覆盖情况"弹窗 —— 补课目标节次已有课程, 给出原课程与覆盖后课程
		if(overwriteEAS != null){
			val list = overwriteEAS!!
			val lines = remember(list) { easOverwriteLines(LessonTableRepository.lessonTable, list) }
			AlertDialog(
				onDismissRequest = { overwriteEAS = null },
				title = { Text(text = "课程存在覆盖情况") },
				text = {
					Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
						Text(
							text = "以下教务通知调课/补课的目标节次已有课程，覆盖情况如下：",
							style = MaterialTheme.typography.bodyMedium
						)
						Spacer(modifier = Modifier.height(8.dp))
						lines.forEach { line ->
							Text(text = line, style = MaterialTheme.typography.bodySmall)
							Spacer(modifier = Modifier.height(4.dp))
						}
						Text(
							text = "「覆盖并继续」：用调课/补课课程替换原课程；\n「不覆盖，只加入」：保留原课程，仅在对应节次没有课程时加入调课/补课课程。",
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.secondary
						)
					}
				},
				confirmButton = {
					Column(horizontalAlignment = Alignment.End) {
						TextButton(onClick = { overwriteEAS = null }) { Text(text = "取消") }
						TextButton(onClick = {
							val applied = applyEASChanges(LessonTableRepository.lessonTable, list, overwrite = true)
							overwriteEAS = null
							onMessage(if(applied > 0) "已覆盖并写入${applied}条教务通知调课/补课/停课" else "已写入${list.size}条教务通知调课/补课/停课")
						}) { Text(text = "覆盖") }
						TextButton(onClick = {
							// 【不覆盖，只加入】保留原课程, 只在对应节次没有课程时加入调课/补课课程
							val applied = applyEASChanges(LessonTableRepository.lessonTable, list, overwrite = false)
							overwriteEAS = null
							onMessage(if(applied > 0) "已加入${applied}条教务通知调课/补课/停课（原课程位置不加入，保留原有课程）" else "未加入新的课程（对应节次已有原课程，已保留原课程）")
						}) { Text(text = "不覆盖，只加入") }
					}
				}
			)
		}

		// (需求4 弹窗已移至 App 启动: 见 EASLaunchPrompt)

		// (【第二十三轮】"已调课补课停课成功，暂无新的调课补课停课安排" 改为与「科大官网同步调休」一致,
		//  直接用 toast 提示: 见按钮 onClick 内的预演检验, 不再使用弹窗)

		// (【第二十六轮】原"长按「教务通知调课」查看/复制日志"功能已按要求删除)

		// 未解析出调休: 展示 OCR 识别到的原文, 便于用户判断问题出在哪里
		if(failedText.isNotEmpty()){
			AlertDialog(
				onDismissRequest = { failedText = "" },
				title = { Text(text = "未识别到调休信息") },
				text = {
					Column {
						Text(
							text = "可能是截图不含调休信息，或句式暂未支持。以下是本次识别到的文字：",
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.secondary,
							modifier = Modifier.padding(bottom = 8.dp)
						)
						Text(
							text = failedText,
							style = MaterialTheme.typography.bodySmall,
							modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())
						)
					}
				},
				confirmButton = {
					TextButton(onClick = { failedText = "" }) { Text(text = "知道了") }
				}
			)
		}
	}

	// ---------------- 调休管理页 ----------------

	val AdjustPage = Page(Keys.Page.AdjustPage, "调休管理", iconRes = R.drawable.ic_insert_invitation) { activity, padding, _, _ ->
		val toast = activity.toast
		var refreshTick by remember { mutableStateOf(0) }
		/** 【第四十八轮】多选删除: 选中日期集合 + 单条删除确认 + 批量删除确认 */
		var multiSelect by remember { mutableStateOf(false) }
		val selectedKeys = remember { mutableStateListOf<String>() }
		var askDeleteOne by remember { mutableStateOf<String?>(null) }
		var askDeleteSelected by remember { mutableStateOf(false) }

		/** 删除某一条调休安排 */
		fun deleteArrange(dateKey: String){
			LessonTableRepository.lessonTable.overrides.remove(dateKey)
			LessonTableRepository.lessonTable.dayOverrides.remove(dateKey)
			LessonTableRepository.lessonTable.easDates.remove(dateKey)
			LessonTableRepository.lessonTable.easSuspended.remove(dateKey)
			LessonTableRepository.saveLessonTable()
			LessonTableRepository.notifyLessonTableChanged()
			selectedKeys.remove(dateKey)
			refreshTick++
		}

		Column(modifier = Modifier.padding(padding).verticalScroll(rememberScrollState())) {

		ScheduleAdjustActions(activity, { message ->
				refreshTick++
				// 【第五十三轮】"暂无新的调休/调课安排"放到屏幕中间(其它提示保持底部系统气泡)
				if(message.startsWith("暂无新的")) toast.toastCenter(message)
				else toast.toastOK(message)
			})

			// 【第五十轮】所有提示统一放在"调休安排"**上方**
			Text(
				text = if(multiSelect) "提示：点击条目勾选/取消，底部可批量删除；点右上角「退出多选」结束"
					else "提示：点击条目可删除该调休安排；点右上角「多选」可批量删除",
				modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 2.dp),
				style = MaterialTheme.typography.labelLarge,
				color = MaterialTheme.colorScheme.secondary
			)
			Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
				Text(
					text = "调休安排",
					modifier = Modifier.weight(1F).padding(start = 16.dp, top = 6.dp, bottom = 4.dp),
					style = MaterialTheme.typography.titleMedium
				)
				// 【第四十八轮】多选开关: 进入多选后点击条目为勾选, 底部可批量删除
				// 【第五十五轮·按要求】与其它文字按钮统一成"浅蓝填充 + 白字"(取消灰框)
				TextButton(onClick = {
					multiSelect = !multiSelect
					if(!multiSelect) selectedKeys.clear()
				}, colors = textActionFilledColors()) { Text(text = if(multiSelect) "退出多选" else "多选") }
			}

			// 依赖refreshTick在删除/写入后刷新
			// 【本期】教务通知调课不再登记进"调休安排", 这里只展示 放假/调休(overrides 0..7)。
			// 展示文案直接读取该日期已保存的 dayOverrides 内容(与课表实际显示一致, 修复"漏课")。
			remember(refreshTick) { LessonTableRepository.lessonTable.overrides.toSortedMap() }.let{ overrides ->
				@Suppress("UNUSED_EXPRESSION") refreshTick
				val table = LessonTableRepository.lessonTable

				if(overrides.isEmpty()){
					Text(
						text = "暂无调休安排",
						modifier = Modifier.padding(16.dp),
						color = MaterialTheme.colorScheme.secondary
					)
				}else{
					overrides.forEach { (dateKey, action) ->
						val line = savedArrangeText(table, dateKey, action)
						val checked = selectedKeys.contains(dateKey)
						// 条目整体可点(带水波纹): 普通模式点条目 = 删除(弹确认框); 多选模式点条目 = 勾选/取消
						// 【第二十九轮·按要求】反馈统一成 tapRipple(主题色 + 12dp 倒角, 与 Card 自身圆角一致),
						// 原来裸 clickable 是"直角黑波纹", 压在圆角卡片上四个角会露出方角。
						// 【第五十七轮·按要求】条目外观与「时间表管理」的卡片统一: 白底 + 6dp 阴影
						// 【第五十八轮·修"该页面的条目没有按照时间表的样式修改"】
						//   上一版把 `tapRipple` 挂在 Card **外面** —— 而 tapRipple 的第一件事就是
						//   `clip(RoundedCornerShape(12.dp))`, 它会把 Card 自己那圈 6dp 的投影一起裁掉,
						//   于是白底还在、阴影没了, 看着比时间表的卡片"扁"一层。
						//   现在把点击反馈挪进 Card 内部(铺满内容区的 Row 上), 阴影照常画在卡片外面,
						//   水波纹也仍然被 12dp 圆角裁住, 不会露出方角。
						Card(
							modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
							colors = CardDefaults.cardColors(containerColor = Color.White),
							elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
						) {
							// 【本期】条目尺寸在多选前后必须完全一致:
							// 行高固定下限 56dp, 右侧永远留出同一格 48dp。
							// 普通模式这一格**什么都不放**(删除改为点条目, 由提示文案说明),
							// 多选模式放一个自绘的 22dp 方框 —— 不能用 Material3 的 Checkbox:
							// 它自带 48dp「最小交互尺寸」, 会把整行从 ~56dp 撑到 ~80dp,
							// 于是多选前后条目宽高看起来都变了(用户看到的"宽度变化"其实主要是高度)。
							Row(
								modifier = Modifier
									.fillMaxWidth()
									.tapRipple {
										if(multiSelect){
											if(checked) selectedKeys.remove(dateKey) else selectedKeys.add(dateKey)
										}else{
											// 【第四十八轮】删除前弹窗确认
											askDeleteOne = dateKey
										}
									}
									.heightIn(min = 56.dp)
									.padding(horizontal = 16.dp),
								verticalAlignment = Alignment.CenterVertically
							) {
								Text(text = line, modifier = Modifier.weight(1F), style = MaterialTheme.typography.bodyMedium)
								Box(modifier = Modifier.width(48.dp), contentAlignment = Alignment.Center) {
									if(multiSelect){
										val boxShape = RoundedCornerShape(4.dp)
										Box(
											modifier = Modifier.size(22.dp)
												.clip(boxShape)
												.then(
													if(checked) Modifier.background(MaterialTheme.colorScheme.primary, boxShape)
													else Modifier
												)
												.border(2.dp, if(checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, boxShape),
											contentAlignment = Alignment.Center
										) {
											if(checked){
												// 用文字画对勾: 不引入图标依赖, 也不会改变方框尺寸
												Text(
													text = "✓",
													color = MaterialTheme.colorScheme.onPrimary,
													style = MaterialTheme.typography.labelMedium
												)
											}
										}
									}
								}
							}
						}
					}
				}
			}

			// 【第四十八轮】多选模式下的批量删除按钮
			if(multiSelect && selectedKeys.isNotEmpty()){
				Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.Center) {
					// 【第五十五轮·按要求】浅蓝填充 + **红字**(蓝框里的红字改回红色)
					TextButton(onClick = { askDeleteSelected = true }, colors = textActionFilledColors()) {
						Text(text = "删除选中（${selectedKeys.size}项）", color = MaterialTheme.colorScheme.error)
					}
				}
			}
			Spacer(modifier = Modifier.height(24.dp))
		}

		// 【第四十八轮】单条删除确认(红色"删除")
		askDeleteOne?.let { k ->
			Dialogs.AskDialog(
				title = "删除调休安排",
				content = "是否删除${dateLabel(k)}的调休安排？",
				onDismiss = { askDeleteOne = null },
				confirmText = "删除",
				danger = true,
				onConfirm = {
					deleteArrange(k)
					askDeleteOne = null
					toast.toastOK("已删除该条调休")
				}
			)
		}

		// 【第四十八轮】批量删除确认(红色"删除")
		if(askDeleteSelected){
			val n = selectedKeys.size
			Dialogs.AskDialog(
				title = "删除调休安排",
				content = "是否删除选中的${n}条安排？",
				onDismiss = { askDeleteSelected = false },
				confirmText = "删除",
				danger = true,
				onConfirm = {
					val keys = selectedKeys.toList()
					keys.forEach { deleteArrange(it) }
					askDeleteSelected = false
					toast.toastOK("已删除${keys.size}条安排")
				}
			)
		}
	}
}
