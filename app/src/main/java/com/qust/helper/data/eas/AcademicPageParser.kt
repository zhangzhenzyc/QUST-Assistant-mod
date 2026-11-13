/*
 * 青科助手（修改版） —— 基于开源项目 QUST-Assistant 修改而成
 *
 * 原项目版权所有 (C) 2891954521  (https://github.com/2891954521/QUST-Assistant)
 * 原始开源许可: GNU General Public License v3.0 (GPL-3.0), 许可原文见 LICENSE 文件, 保持完整未改动。
 *
 * 修改版版权所有 (C) 2026 zhangzhenzyc  (https://github.com/zhangzhenzyc)
 * 本修改版同样以 GPL-3.0 发布; 附加版权声明见 NOTICE.md —— 两份版权声明相互独立、同时生效。
 */

package com.qust.helper.data.eas

import com.qust.helper.data.room.LessonInfo
import java.util.regex.Pattern
import kotlin.math.abs

/**
 * 「学业情况」页面解析(纯函数, 便于单元测试)
 *
 * 页面上的每个 `xfyqjd_id` 对应培养方案里的一个模块(通识必修 / 专业必修 / 通识任选 ...)。
 *
 * 【通识任选显示不出来】这类"学生自由选课"的模块在培养方案里**没有预置课程**,
 * 课程明细往往是空的; 只要解析或过滤稍有偏差, 整组就消失。因此这里:
 * - id 的匹配放宽(不再限制字符范围, 只要不是引号/尖括号/空白即可), 兼容各种写法;
 * - 要求学分兼容 `yxxf` 在前和 `yqzdxf` 在前两种顺序;
 * - 额外提供模块名猜测(取 id 附近的可见中文文本), 供"没有课程、拿不到课程性质"的模块兜底。
 */
object AcademicPageParser {

	/** 模块 id(及它在页面中的位置) */
	private val XFYQJD_ID = Pattern.compile("xfyqjd_id\\s*=\\s*['\"]?([^'\">\\s]+)['\"]?")

	/**
	 * 页面上每个模块的标题行(截图可见格式):
	 * `通识任选 要求学分:4.0 获得学分:11.5 未获得学分:6.0`
	 *
	 * 在**去掉标签后的纯文本**上匹配, 这样不管名称与学分写在同一个标签里还是
	 * 拆成多个 <td>/<span> 都能匹配到; 兼容中英文冒号与繁体字(學分/獲得...)。
	 */
	private val MODULE_HEADER = Pattern.compile(
		"([^:：\\s]{2,20}?)\\s*要求学分\\s*[:：]\\s*([0-9.]+)\\s*" +
			"获得学分\\s*[:：]\\s*([0-9.]+)(?:\\s*未获得学分\\s*[:：]\\s*([0-9.]+))?"
	)

	/** 常见繁体 -> 简体(学业页面偶发繁体导致"要求学分"匹配不到) */
	private val TRADITIONAL_MAP = mapOf(
		'學' to '学', '獲' to '获', '門' to '门', '過' to '过', '課' to '课', '選' to '选',
		'講' to '讲', '導' to '导', '實' to '实', '驗' to '验', '踐' to '践', '業' to '业',
		'專' to '专', '論' to '论', '證' to '证', '書' to '书', '計' to '计', '劃' to '划',
		'質' to '质', '礎' to '础', '師' to '师', '類' to '类', '別' to '别', '習' to '习',
		'題' to '题', '試' to '试', '級' to '级', '養' to '养', '訓' to '训', '練' to '练',
		'創' to '创', '曆' to '历', '屆' to '届', '獎' to '奖', '懲' to '惩', '繳' to '缴',
		'費' to '费', '遲' to '迟', '異' to '异', '復' to '复', '轉' to '转', '讀' to '读',
		'寫' to '写', '審' to '审', '態' to '态', '標' to '标', '簽' to '签', '單' to '单',
		'據' to '据', '數' to '数', '總' to '总', '績' to '绩', '點' to '点', '時' to '时',
		'鐘' to '钟', '錶' to '表', '準' to '准', '確' to '确', '誤' to '误', '錯' to '错',
		'曠' to '旷', '請' to '请', '條' to '条', '碼' to '码', '鈴' to '铃', '聲' to '声',
		'詳' to '详', '細' to '细', '約' to '约', '級' to '级', '記' to '记', '錄' to '录',
		'況' to '况', '區' to '区', '雙' to '双', '動' to '动', '國' to '国', '際' to '际',
		'語' to '语', '文' to '文', '體' to '体', '育' to '育', '測' to '测', '場' to '场',
		'館' to '馆', '曬' to '晒', '覽' to '览', '觀' to '观', '見' to '见', '聞' to '闻',
		'聯' to '联', '網' to '网', '頁' to '页', '開' to '开', '關' to '关', '閉' to '闭',
		'進' to '进', '遠' to '远', '運' to '运', '終' to '终', '始' to '始', '結' to '结',
		'構' to '构', '築' to '筑', '樣' to '样', '機' to '机', '節' to '节', '長' to '长',
		'短' to '短', '高' to '高', '低' to '低', '寬' to '宽', '狀' to '状', '優' to '优',
		'良' to '良', '差' to '差', '壞' to '坏'
	)

	/** 已修学分(在 id 之后的窗口内单独匹配, 不依赖属性顺序/间距) */
	private val ATTR_YXXF = Pattern.compile("yxxf\\s*=\\s*['\"]?([\\d.]+)")

	/** 要求学分 */
	private val ATTR_YQZDXF = Pattern.compile("yqzdxf\\s*=\\s*['\"]?([\\d.]+)")

	/** 从 id 开始向后找学分的窗口长度(属性可能被换行/其他属性隔开) */
	private const val CREDITS_WINDOW = 600

	/** 页面上可见的文本, 形如 `>通识任选<` */
	private val TAG_TEXT = Pattern.compile(">([^<>]{2,30})<")

	/** 明显不是模块名的按钮/说明文字 */
	private val NON_NAME = arrayOf(
		"详情", "查询", "查看", "返回", "说明", "操作", "展开", "收起",
		"合计", "总计", "课程", "学分", "已修", "未修", "要求", "学期", "修读", "门", "模块"
	)

	/** 出现在 id 之前的文本额外增加的"距离惩罚": 模块名通常就在带 id 的那个标签里(即 id 之后) */
	private const val BACKWARD_PENALTY = 250

	/** 课程明细表: 行 / 单元格(单元格级解析, 比"去标签后猜关键字"可靠得多) */
	private val ROW_RE = Pattern.compile("<tr[^>]*>(.*?)</tr>", Pattern.DOTALL or Pattern.CASE_INSENSITIVE)
	private val CELL_RE = Pattern.compile("<t[dh][^>]*>(.*?)</t[dh]", Pattern.DOTALL or Pattern.CASE_INSENSITIVE)

	/** 课程号: 整格就是课程号(字母前缀+数字≥4位, 或纯数字6~12位) */
	private val CODE_RE = Pattern.compile("^\\s*([A-Za-z]{1,3}\\d{4,}|\\d{6,12})\\s*$")

	/** 纯数字格(学分/成绩等) */
	private val NUM_RE = Pattern.compile("^\\s*(\\d+(?:\\.\\d+)?)\\s*$")

	/** 等级制成绩文本 */
	private val GRADE_WORDS = hashSetOf("优", "良", "中", "优秀", "良好", "及格", "不及格", "合格", "不合格", "通过", "未通过", "中等", "免修")

	/**
	 * 页内脚本动态构建的模块标题(该页面真实写法):
	 * `"模块名&nbsp;" + $i18n.get('yqxf')/ *要求学分* / + ":170.0&nbsp;" ... appendTo($("#li{模块id}"))`
	 * 模块标题根本不在可见文本里, 全靠脚本拼接 —— 这是 parseModuleHeaders 抓到 0 行的根因。
	 */
	private val JS_HEADER = Pattern.compile(
		"xfyqjd_id='([0-9A-Fa-f]+)'[\\s\\S]{0,400}?\"([^\"<>]{2,20}?)&nbsp;\"\\s*\\+\\s*\\$\\.i18n\\.get\\('yqxf'\\)[\\s\\S]{0,200}?\":\\s*([0-9.]+)&nbsp;\""
	)

	/** 页内脚本里的模块课程门数(服务端直接嵌进脚本): `if('32' != 1){ ... appendTo($("#li{模块id}"))` */
	private val JS_COUNT = Pattern.compile(
		"if\\s*\\(\\s*'\\s*(\\d*)\\s*'\\s*!=\\s*1\\s*\\)\\s*\\{[\\s\\S]{0,200}?appendTo\\(\\\$\\(\"#li([0-9A-Za-z]+)\"\\)\\)"
	)

	/**
	 * 页面上出现的所有模块 id(按出现顺序), value 为该 id 在页面中的位置(用于猜名字)
	 */
	fun parseModuleIds(html: String): LinkedHashMap<String, Int> {
		val result = LinkedHashMap<String, Int>()
		val m = XFYQJD_ID.matcher(html)
		while(m.find()){
			val id = m.group(1) ?: continue
			if(id.isEmpty()) continue
			if(!result.containsKey(id)) result[id] = m.start()
		}
		return result
	}

	/**
	 * 取出某模块在接口A HTML 中的原始 DOM 段(从 xfyqjd_id 起到下一个 xfyqjd_id 或文末),
	 * 用于诊断: 静态 HTML 里到底有没有该模块的课程行(<tr>)。
	 */
	fun getModuleSectionRaw(html: String, moduleId: String): String {
		val idM = XFYQJD_ID.matcher(html)
		var start = -1
		while(idM.find()){
			if(idM.group(1) == moduleId){ start = idM.start(); break }
		}
		if(start < 0) return ""
		val rest = html.substring(start + 1)
		val nextM = XFYQJD_ID.matcher(rest)
		val end = if(nextM.find()) start + 1 + nextM.start() else html.length
		return html.substring(start, end)
	}

	/**
	 * 【JS 动态标题解析】该页面的模块标题/要求学分由页内 <script> 字符串拼接后 appendTo 进 DOM,
	 * 去标签后的可见文本里不存在 "XXX 要求学分:A 获得学分:B" 连续文本 —— parseModuleHeaders
	 * 抓到 0 行、通识任选识别不出来正是这个根因。这里直接解析脚本模板,
	 * 并经 `appendTo($("#li{模块id}"))` 与模块 id 配对; 目标为裸 `$("#li")`(全校汇总行)的自动跳过。
	 */
	fun parseModuleHeadersFromJs(html: String): Map<String, ModuleHeader> {
		val result = LinkedHashMap<String, ModuleHeader>()
		val m = JS_HEADER.matcher(html)
		while(m.find()){
			val id = m.group(1)?.trim() ?: continue
			val name = simplify(m.group(2)?.trim() ?: continue).trim()
			val require = m.group(3)?.toFloatOrNull() ?: 0F
			if(id.isEmpty() || name.isEmpty()) continue
			if(!result.containsKey(id)) result[id] = ModuleHeader(name, require, 0F, 0F)
		}
		return result
	}

	/**
	 * 【JS 模块课程门数】服务端把每个模块的课程数直接嵌进脚本:
	 * `if('32' != 1){ ... appendTo($("#li{模块id}"))` —— 不依赖接口B 即可知道该模块应有几门课。
	 */
	fun parseModuleCountsFromJs(html: String): Map<String, Int> {
		val result = LinkedHashMap<String, Int>()
		val m = JS_COUNT.matcher(html)
		while(m.find()){
			val count = m.group(1)?.toIntOrNull() ?: 0
			val id = m.group(2) ?: continue
			if(id.isNotEmpty() && !result.containsKey(id)) result[id] = count
		}
		return result
	}

	/**
	 * 子模块(xfyqjd_id) -> 它被 appendTo 进的父模块 li 容器 id。
	 * 页面脚本把每个子模块的 HTML 字符串模板 appendTo 到父 <li id='li{父id}'> 里,
	 * 通识任选等自由选课模块的"课程数"服务端计数挂在父容器上、且课程明细接口B 对子模块返回空,
	 * 需要通过父模块才能对齐"服务器课程数"或回捞课程。
	 */
	private val JS_APPEND = Pattern.compile(
		"xfyqjd_id='([0-9A-Fa-f]+)'[\\s\\S]{0,2000}?appendTo\\(\\$\\(\"#li([0-9A-Fa-z]+)[\"']\\)\\)"
	)

	fun parseModuleAppendTargets(html: String): Map<String, String> {
		val result = LinkedHashMap<String, String>()
		val m = JS_APPEND.matcher(html)
		while(m.find()){
			val id = m.group(1)?.trim() ?: continue
			val li = m.group(2)?.trim() ?: continue
			if(id.isNotEmpty() && !result.containsKey(id)) result[id] = li
		}
		return result
	}

	/**
	 * 【通识任选兜底】从学分统计接口A(学业情况查询首页 HTML)解析某模块内绑定的课程条目。
	 *
	 * 仅当课程明细接口B(ACADEMIC_INFO)未返回该课时使用: 接口A 作为该分类课程的权威数据源。
	 * 解析策略(单元格级):
	 * - 取该模块(xfyqjd_id)所在 DOM 段(到下一个 xfyqjd_id 或文末);
	 * - 先找表头行(含"课程代码/课程名称/学分/成绩/学期")建立**列号映射**;
	 * - 数据行按列号取 课程号/课程名/学分/成绩, 没有表头时按格式启发式识别;
	 * - 通识任选课程固定 type/category = "通识任选"(不依赖课程自带 type 字段);
	 * - 0 学分/未结课课程照常返回(不做任何学分过滤);
	 * - [diag] 可选回调, 输出每一步解析细节(跳过原因/列映射/行数), 供应用内诊断面板展示。
	 */
	fun parseModuleCoursesFromHtml(html: String, moduleId: String, entranceTime: Int, group: Int, diag: ((String) -> Unit)? = null): List<LessonInfo> {
		val result = ArrayList<LessonInfo>()
		// 定位模块起点
		val idM = XFYQJD_ID.matcher(html)
		var start = -1
		while(idM.find()){
			if(idM.group(1) == moduleId) { start = idM.start(); break }
		}
		if(start < 0){
			diag?.invoke("  [兜底] 模块[$moduleId] 在接口A HTML中未找到 xfyqjd_id")
			return result
		}
		// 模块段终点 = 下一个 xfyqjd_id 或文末
		val rest = html.substring(start + 1)
		val nextM = XFYQJD_ID.matcher(rest)
		val end = if(nextM.find()) start + 1 + nextM.start() else html.length
		val section = html.substring(start, end)

		// 表头列映射: "code"/"name"/"credit"/"mark"/"term" -> 列下标
		var cols: Map<String, Int> = emptyMap()
		var rowCount = 0
		val rowM = ROW_RE.matcher(section)
		while(rowM.find()){
			rowCount++
			val cells = ArrayList<String>()
			val cellM = CELL_RE.matcher(rowM.group(1) ?: "")
			while(cellM.find()){
				val c = normalize(cellM.group(1) ?: "").trim()
				if(c.isNotEmpty()) cells.add(c)
			}
			if(cells.isEmpty()) continue
			val joined = cells.joinToString(" ")

			// ---- 表头行: 建立/补充列映射 ----
			val isHeaderLike = cells.any { it.contains("课程代码") || it.contains("课程编号") || it.contains("课程名称") || it.contains("课程名") }
			if(isHeaderLike){
				val map = HashMap<String, Int>()
				cells.forEachIndexed { idx, c ->
					when {
						c.contains("课程代码") || c.contains("课程编号") -> if(!map.containsKey("code")) map["code"] = idx
						c.contains("课程名称") || c.contains("课程名") -> if(!map.containsKey("name")) map["name"] = idx
						c == "学分" || (c.contains("学分") && !c.contains("要求") && !c.contains("获得") && !c.contains("未获得")) -> if(!map.containsKey("credit")) map["credit"] = idx
						c.contains("成绩") || c.contains("分数") -> if(!map.containsKey("mark")) map["mark"] = idx
						c.contains("学期") || c.contains("学年") -> if(!map.containsKey("term")) map["term"] = idx
					}
				}
				if(map.containsKey("code") && map.containsKey("name")){
					cols = map
					diag?.invoke("  [兜底] 模块[$moduleId] 表头列映射: $map")
				}
				continue
			}

			// ---- 数据行: 按列映射取值, 缺映射时启发式 ----
			var code = cols["code"]?.let { cells.getOrNull(it) } ?: ""
			var name = cols["name"]?.let { cells.getOrNull(it) } ?: ""
			var credit = cols["credit"]?.let { cells.getOrNull(it)?.toFloatOrNull() ?: 0F } ?: 0F
			var mark = cols["mark"]?.let { cells.getOrNull(it) } ?: ""
			var termText = cols["term"]?.let { cells.getOrNull(it) } ?: ""

			if(code.isEmpty()){
				for(c in cells){
					val mm = CODE_RE.matcher(c)
					if(mm.find()){ code = mm.group(1) ?: ""; break }
				}
			}
			if(name.isEmpty()){
				for(c in cells){
					if(c == "课程名称" || c.contains("学分") || c.contains("成绩")) continue
					if(c.length >= 3 && c.any { it in '一'..'龥' }){ name = c; break }
				}
			}
			if(credit <= 0F){
				for(c in cells){
					val mm = NUM_RE.matcher(c)
					if(mm.find()){
						val v = mm.group(1)?.toFloatOrNull() ?: 0F
						// 学分: 一般 0.5~15 之间的小数/小整数; 排除成绩(≥30)与年份
						if(v in 0.5F..15F && (c.contains(".") || v <= 10F)){ credit = v; break }
					}
				}
			}
			if(mark.isEmpty()){
				for(c in cells){
					if(GRADE_WORDS.contains(c)){ mark = c; break }
					val mm = NUM_RE.matcher(c)
					if(mm.find()){
						val v = mm.group(1)?.toFloatOrNull() ?: 0F
						// 成绩: 百分制(≥30)或等级; 排除已被当作学分的小数值与年份/学期号
						if(v >= 30F){ mark = mm.group(1) ?: ""; break }
					}
				}
			}
			if(termText.isEmpty()){
				val ym = Regex("((?:20)?\\d{2})\\s*[-\\s/]?\\s*(\\d{1,2})\\s*学期").find(joined)
				if(ym != null) termText = ym.value
			}

			if(code.isEmpty() || name.isEmpty()){
				diag?.invoke("  [兜底] 模块[$moduleId] 跳过行#$rowCount(识别不到课程号或课程名): $cells")
				continue
			}

			// 修读状态: 未通过(含"未通过"字样) > 明确通过 > 分数判断 > 默认在修
			val status = when {
				joined.contains("未通过") || joined.contains("不及格") || joined.contains("不合格") -> 2
				joined.contains("已修") || joined.contains("通过") || joined.contains("及格") || joined.contains("合格") -> 4
				mark.toFloatOrNull()?.let { it >= 60F } == true -> 4
				mark.toFloatOrNull() != null -> 2
				else -> 1  // 在修(无成绩、无状态字样)
			}
			// 学年学期 -> 列表分组序号
			val ym = Regex("((?:20)?\\d{2})\\s*[-\\s/]?\\s*(\\d{1,2})\\s*学期").find(if(termText.isNotEmpty()) termText else joined)
			val year = ym?.groupValues?.get(1)?.toIntOrNull() ?: 0
			val term = ym?.groupValues?.get(2)?.toIntOrNull() ?: 0

			diag?.invoke("  [兜底] 模块[$moduleId] 课程行: code=$code name=$name credit=$credit mark=$mark status=$status cells=$cells")
			result.add(LessonInfo(
				kchId = code, name = name, type = "通识任选",
				credit = credit, mark = mark, gpa = 0F, status = status,
				category = "通识任选", content = "",
				index = if(year > 0) (year - entranceTime) * 2 + term - 1 else group, group = group
			))
		}
		diag?.invoke("  [兜底] 模块[$moduleId] 段内共 $rowCount 行 <tr>, 解析出课程 ${result.size} 门")
		return result
	}

	/** 页面上一个模块的标题行: 名称 + 三项学分 */
	data class ModuleHeader(
		val name: String,
		val requireCredits: Float,
		val obtainedCredits: Float,
		val notEarnedCredits: Float
	)

	/** 从 id 位置向前/向后找标题行的窗口长度(原始 HTML 字符数) */
	private const val HEADER_BACK_WINDOW = 1200
	private const val HEADER_FORWARD_WINDOW = 2500

	/**
	 * 在**某个模块 id 附近**找它的标题行(名称 + 要求/已修学分)
	 *
	 * 【模块错位】以前是"第 n 个标题行配第 n 个 id": 页面里只要多一行/少一行
	 * (合计行、说明行……), 后面的模块就整体错位 —— 名字张冠李戴,
	 * 课程也被挂到别的模块下(表现为"通识任选是空的、积极心理导论跑到别的模块里")。
	 * 这里改成按 id 的实际位置取窗口就近匹配, 标题与 id 天然对齐。
	 *
	 * @param html 页面原始 HTML
	 * @param pos  [parseModuleIds] 给出的该 id 在页面中的位置, 负数返回 null
	 */
	fun parseModuleHeadersNear(html: String, pos: Int): ModuleHeader? {
		if(pos < 0 || html.isEmpty()) return null

		val from = (pos - HEADER_BACK_WINDOW).coerceAtLeast(0)
		val to = (pos + HEADER_FORWARD_WINDOW).coerceAtMost(html.length)
		if(from >= to) return null

		// 窗口内"id 之前"的文本长度 ≈ id 在去标签后文本里的位置(用于挑最近的标题行)
		val center = normalize(html.substring(from, pos)).length
		val text = normalize(html.substring(from, to))

		val m = MODULE_HEADER.matcher(text)
		var best: ModuleHeader? = null
		var bestDistance = Int.MAX_VALUE
		while(m.find()){
			val name = m.group(1)?.trim()?.trimEnd(' ', '　') ?: continue
			if(name.isEmpty()) continue
			val distance = abs(m.start() - center)
			if(distance < bestDistance){
				bestDistance = distance
				best = ModuleHeader(
					name = name,
					requireCredits = m.group(2)?.toFloatOrNull() ?: 0F,
					obtainedCredits = m.group(3)?.toFloatOrNull() ?: 0F,
					notEarnedCredits = m.group(4)?.toFloatOrNull() ?: 0F
				)
			}
		}
		return best
	}

	/**
	 * 【按序对齐】为 [parseModuleIds] 的每个 id 找对应标题行
	 *
	 * 页面上标题行(要求学分/获得学分)与模块 id 都是按出现顺序排列的:
	 * 数量相等时按序一一配对(最可靠); 数量不等(页面有干扰行)时才退回窗口就近匹配。
	 *
	 * @return 与 idMap.keys 同序的标题行列表(找不到为 null)
	 */
	fun parseModuleHeadersAligned(html: String, idMap: LinkedHashMap<String, Int>): List<ModuleHeader?> {
		val headers = parseModuleHeaders(html)
		val ids = idMap.keys.toList()
		if(ids.isNotEmpty() && headers.size == ids.size) return headers
		return ids.map { id -> parseModuleHeadersNear(html, idMap[id] ?: -1) }
	}

	/**
	 * 解析页面上全部模块标题行(按出现顺序)
	 *
	 * 这是【通识任选识别不出来】的关键兜底: 名称与学分直接取自页面上可见的
	 * "XXX 要求学分:A 获得学分:B 未获得学分:C" 文本, 不依赖课程明细与属性写法。
	 */
	fun parseModuleHeaders(html: String): List<ModuleHeader> {
		val text = normalize(html)
		val result = ArrayList<ModuleHeader>()
		val m = MODULE_HEADER.matcher(text)
		while(m.find()){
			val rawName = m.group(1)?.trim() ?: continue
			val name = rawName.trimEnd(' ', '　')
			if(name.isEmpty()) continue
			result.add(ModuleHeader(
				name = name,
				requireCredits = m.group(2)?.toFloatOrNull() ?: 0F,
				obtainedCredits = m.group(3)?.toFloatOrNull() ?: 0F,
				notEarnedCredits = m.group(4)?.toFloatOrNull() ?: 0F
			))
		}
		return result
	}

	/**
	 * 归一化页面: 去掉全部标签(只留可见文本) + 常见繁体转简体
	 *
	 * 归一化后的文本用于模块标题行匹配, 避免
	 * "获得学 分"被标签打断、繁体"學分"匹配不到 这类识别问题。
	 * 注意: 属性(xfyqjd_id 等)都写在标签里, 会被去掉 —— 属性类正则请用原始 HTML。
	 */
	fun normalize(html: String): String {
		if(html.isEmpty()) return html
		val sb = StringBuilder(html.length)
		var inTag = false
		for(c in html){
			when {
				c == '<' -> inTag = true
				c == '>' -> inTag = false
				!inTag -> sb.append(TRADITIONAL_MAP[c] ?: c)
			}
		}
		return sb.toString()
	}

	/** 繁体转简体(不删标签, 用于模块名等可见文本) */
	fun simplify(text: String): String {
		if(text.isEmpty()) return text
		var changed = false
		for(c in text) if(TRADITIONAL_MAP.containsKey(c)){ changed = true; break }
		if(!changed) return text
		val sb = StringBuilder(text.length)
		for(c in text) sb.append(TRADITIONAL_MAP[c] ?: c)
		return sb.toString()
	}

	/**
	 * 每个模块的学分: id -> (已修学分, 要求学分)
	 *
	 * 窗口式解析: 先在原始 HTML 里定位每个 `xfyqjd_id`, 再从该位置向后
	 * [CREDITS_WINDOW] 个字符内分别找 `yxxf` / `yqzdxf`。
	 * 不依赖两个属性的先后顺序、间距、是否被其他属性/换行隔开,
	 * 兼容各种部署写法(这是之前严格正则匹配不到、模块学分全变 0 的根因)。
	 */
	fun parseCredits(html: String): Map<String, Pair<Float, Float>> {
		val result = HashMap<String, Pair<Float, Float>>()

		val idMatcher = XFYQJD_ID.matcher(html)
		while(idMatcher.find()){
			val id = idMatcher.group(1) ?: continue
			if(id.isEmpty() || result.containsKey(id)) continue

			val from = idMatcher.start()
			val to = (from + CREDITS_WINDOW).coerceAtMost(html.length)
			val window = html.substring(from, to)

			val yxxfM = ATTR_YXXF.matcher(window)
			val yqzdxfM = ATTR_YQZDXF.matcher(window)
			if(!yxxfM.find() || !yqzdxfM.find()) continue

			val yxxf = yxxfM.group(1)?.toFloatOrNull() ?: 0F
			val yqzdxf = yqzdxfM.group(1)?.toFloatOrNull() ?: 0F
			result[id] = Pair(yxxf, yqzdxf)
		}
		return result
	}

	/**
	 * 猜测模块在页面上的名称
	 *
	 * 用于"课程明细为空、拿不到课程性质"的模块(如通识任选): 在 id 出现的附近
	 * 找一段**离它最近**的中文可见文本作为模块名。
	 *
	 * @param pos [parseModuleIds] 给出的位置, 负数返回空串
	 */
	fun guessModuleName(html: String, pos: Int): String {
		if(pos < 0 || html.isEmpty()) return ""
		val from = (pos - 500).coerceAtLeast(0)
		val to = (pos + 500).coerceAtMost(html.length)
		if(from >= to) return ""

		val window = html.substring(from, to)
		val center = pos - from
		val m = TAG_TEXT.matcher(window)
		var best = ""
		var bestDistance = Int.MAX_VALUE
		while(m.find()){
			val text = simplify(m.group(1)?.trim() ?: continue)
			if(text.isEmpty() || text.length > 20) continue
			if(!text.any { it in '一'..'龥' }) continue
			if(NON_NAME.any { text.contains(it) }) continue
			val raw = abs(m.start() - center)
			val distance = if(m.start() >= center) raw else raw + BACKWARD_PENALTY
			if(distance < bestDistance){
				bestDistance = distance
				best = text
			}
		}
		return best
	}
}
