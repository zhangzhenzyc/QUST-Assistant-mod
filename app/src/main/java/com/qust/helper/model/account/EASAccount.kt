/*
 * 青科助手（修改版） —— 基于开源项目 QUST-Assistant 修改而成
 *
 * 原项目版权所有 (C) 2891954521  (https://github.com/2891954521/QUST-Assistant)
 * 原始开源许可: GNU General Public License v3.0 (GPL-3.0), 许可原文见 LICENSE 文件, 保持完整未改动。
 *
 * 修改版版权所有 (C) 2026 zhangzhenzyc  (https://github.com/zhangzhenzyc)
 * 本修改版同样以 GPL-3.0 发布; 附加版权声明见 NOTICE.md —— 两份版权声明相互独立、同时生效。
 */

package com.qust.helper.model.account

import android.util.Base64
import androidx.lifecycle.MutableLiveData
import com.qust.helper.data.Data
import com.qust.helper.data.Keys
import com.qust.helper.data.Setting
import com.qust.helper.data.api.QustApi
import com.qust.helper.data.eas.AcademicPageParser
import com.qust.helper.data.eas.Exam
import com.qust.helper.data.eas.Notice
import com.qust.helper.data.room.LessonInfo
import com.qust.helper.data.room.LessonInfoGroup
import com.qust.helper.data.room.Mark
import com.qust.helper.model.Logger
import com.qust.helper.utils.AcademicDiag
import com.qust.helper.utils.CodeUtils
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.FormBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.math.BigInteger
import java.net.HttpURLConnection
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import java.util.Calendar
import javax.crypto.Cipher

/**
 * 教务系统(原网址直连)通道 —— 业务实现全部在这里
 *
 * @param cookieName 会话 Cookie 的存储键。教务现在有**两条**通道(原网址直连 / 数字青科),
 * 两条通道各自独立保存 Cookie, 否则一边退出登录会把另一边的会话一起清掉。
 */
open class EASAccount protected constructor(cookieName: String = "eaCookie"): Account(
	QustApi.EA_HOSTS[Setting.getInt(Keys.EA_HOST, 0)],
	Keys.EAS_ACCOUNT,
	Keys.EAS_PASSWORD,
	scheme = "https",
	cookieName = cookieName
) {

	companion object {
		/** 学业查询整体超时(毫秒): 网络/解析卡住时最多等这么久, 之后自动放弃并提示, 不卡死 UI */
		const val ACADEMIC_QUERY_TIMEOUT = 45_000L

		/**
		 * 【本轮·按要求恢复双通道】教务系统有**两条**登录路径:
		 *   ① 教务系统原网址(`https://jwglxt.qust.edu.cn/jwglxt/...`) —— [DirectEASAccount];
		 *   ② 数字青科里的「教务管理系统」(WebVPN + SSO) —— [VpnEASAccount]。
		 *
		 * 这里返回的是**同时管着两条通道**的那一个实例(全应用共用同一个会话):
		 * 登录时先试①(学号 + 教务系统密码), ①进不去再自动改走②; 之后查询(课表/成绩/学业/
		 * 考试/教务通知调课)自动按"上一次跑通的那条"选走线。
		 * 两条通道共用本类的全部业务实现, 只是走线不同。
		 */
		fun getInstance(): EASAccount {
			return VpnEASAccount.getInstance()
		}
	}

	private var entranceTimeData = MutableLiveData(Setting.getInt(Keys.ENTRANCE_TIME, -1))

	/**
	 * 入学年份信息
	 */
	var entranceTime: Int
		get() {
			val time = entranceTimeData.value
			return time ?: -1
		}
		set(entranceTime) {
			Setting.edit { it.putInt(Keys.ENTRANCE_TIME, entranceTime) }
			entranceTimeData.postValue(entranceTime)
		}

	open fun changeHost(index: Int) {
		if(index >= QustApi.EA_HOSTS.size) return
		isLogin = false
		cookieJar.clearCookies()
		host = QustApi.EA_HOSTS[index]
		Setting.edit { it.putInt(Keys.EA_HOST, index) }
	}

	/**
	 * 【本轮】教务是否"有会话"
	 *
	 * 只用于界面显示「已登录/未登录」, **不参与请求判定**(请求该不该重登由各通道自己的
	 * [isLogin] 决定)。教务现在有两条通道, 会话各自独立保存, 所以这里给出一个与走线无关的
	 * 粗略判断: 本通道登录位有效, 或本通道 Cookie 还在。
	 */
	open val hasSession: Boolean
		get() = isLogin || getCookie().isNotEmpty()

	/**
	 * 【本轮】当前登录走的是哪条通道(日志与界面显示用)
	 *
	 * 本类(直连)固定是"教务系统原网址"; 走数字青科的那个实例会覆写成另一条。
	 */
	open val channelName: String
		get() = "教务系统原网址"

	/**
	 * 【本轮】网页入口(WebView)是否**直接打开教务系统原网址**
	 *
	 * 教务两条通道的网页访问方式不一样:
	 * - ① 原网址直连 -> 直接打开 `https://jwglxt.qust.edu.cn/...`,
	 *   Cookie 注入到教务自己的域名;
	 * - ② 数字青科 -> 地址要经 WebVPN 编码(`https://wvpn.qust.edu.cn/...`),
	 *   Cookie 注入到 wvpn 域名。
	 *
	 * 本类(直连)固定为 true; 走数字青科的那个实例按"当前在走哪条"覆写。
	 */
	open val openWebDirectly: Boolean
		get() = true

	override suspend fun absCheckLogin(): Boolean {
		getNoRedirect("jwglxt/xtgl/index_initMenu.html").use {
			if(it.code != HttpURLConnection.HTTP_OK) return false
			// 有的部署在会话失效时会返回 200 的登录页(而不是 302),
			// 只看状态码会误判成"已登录", 于是第一次查询必然失败、要重新登录一次才行。
			val body = try { it.body?.string() } catch(_: Exception) { null }
			if(body.isNullOrEmpty()) return true
			return !body.contains("login_slogin") && !body.contains("name=\"yhm\"")
		}
	}

	/**
	 * 【本轮·新增】数字青科登录成功后，尝试经「业务应用 → 教务管理系统」建立教务会话。
	 *
	 * 只有「数字青科」那条通道([VpnEASAccount])才有意义；直连通道没有这回事，固定返回 false。
	 * 登录页用它决定"这次登录能不能直接查教务"，进不去就改走「教务登陆」(原网址)。
	 */
	open suspend fun enterEasByVpn(): Boolean = false

	override suspend fun absLogin(account: String, password: String): Boolean {
		cookieJar.clearCookies()

		val html: String
		getNoCheck(QustApi.EA_LOGIN).use {
			// 【错误提示准确】登录页本身返回 5xx / 维护页, 直接判定为教务服务端故障, 不再当网络错误
			if(it.code >= 500) throw ServerException("教务系统服务器异常(HTTP ${it.code})")
			html = it.body?.string() ?: throw IOException("网络错误")
			// 【本期】拿到的是"拒绝访问"拦截页(校方 403): 提示"暂时没有访问权限", 不再报网络异常
			if(isForbiddenBody(html)) throw ServerException("暂时没有访问权限")
		}

		var token: String? = null
		val matcher = "<input (.*?)>".toPattern().matcher(html)
		while(matcher.find()) {
			val input = matcher.group()
			if(input.contains("csrftoken")) {
				token = CodeUtils.matcher("value=\"(.*?)\"".toPattern(), input) ?: throw IOException("无法获取 csrfToken")
			}
		}
		val csrfToken = token ?: throw IOException("无法获取 csrfToken")

		val publicKey: String
		getNoCheck(QustApi.EA_LOGIN_PUBLIC_KEY).use {
			// 公钥接口 5xx 同样视为服务端故障
			if(it.code >= 500) throw ServerException("教务系统服务器异常(HTTP ${it.code})")
			try {
				publicKey = JSONObject(it.body?.string() ?: throw IOException("无法获取 publicKey")).getString("modulus")
			} catch(e: JSONException) {
				throw IOException("无法获取 publicKey", e)
			}
		}

		val rsaPassword = encrypt(password, publicKey) ?: throw IOException("RSA加密出错")

		postNoRedirect(
			QustApi.EA_LOGIN, FormBody.Builder()
				.add("csrftoken", csrfToken)
				.add("yhm", account)
				.add("mm", rsaPassword)
				.build()
		).use {
			val code: Int = it.code
			if(code == HttpURLConnection.HTTP_MOVED_PERM || code == HttpURLConnection.HTTP_MOVED_TEMP || code == 307) {
				return true
			}
			// 【本轮·按要求】"没有跳转"不等于"账号密码错":
			// 教务维护、网关 5xx、被临时拦截, 都会停在这一步而拿不到 302。
			// 只有页面**明确写了**账号/密码不对才判定为凭据错误(返回 false),
			// 其余情况一律抛"暂时无法登录", 避免吓用户去改密码。
			if(code >= 500) throw ServerException("暂时无法登录")
			val body = try { it.body?.string() } catch(_: Exception) { null }
			if(isBadCredentialsBody(body)) return false
			// 【第四十八轮·统一口径】服务端正常(非 5xx)却停回**登录页** = 教务拒绝了这份凭据
			// (教务失败的标准表现就是带着"登录失败"回到登录页), 界面统一报「账号或密码错误」。
			// 维护页/网关错误页不是登录页, 仍走「暂时无法登录」。
			if(isLoginPageBody(body) || body?.contains("登录失败") == true) return false
			throw ServerException("暂时无法登录")
		}
	}

	/**
	 * RSA公钥加密
	 */
	private fun encrypt(str: String, publicKey: String?): String? {
		return try {
			// base64编码的公钥
			val decoded = Base64.decode(publicKey, Base64.DEFAULT)
			val sb = StringBuilder()
			for(b in decoded) {
				val hex = Integer.toHexString(b.toInt() and 0xFF)
				if(hex.length < 2) sb.append(0)
				sb.append(hex)
			}
			val a = BigInteger(sb.toString(), 16)
			val b = BigInteger("65537")
			val pubKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(a, b)) as RSAPublicKey
			val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
			cipher.init(Cipher.ENCRYPT_MODE, pubKey)
			Base64.encodeToString(cipher.doFinal(str.toByteArray()), Base64.DEFAULT)
		} catch(e: Exception) {
			null
		}
	}

	/**
	 * 获取当前年级
	 */
	fun getCurrentGrade(): Int {
		val calendar = Calendar.getInstance()
		val y = calendar[Calendar.YEAR]
		// 【第二十八轮·根因修复】入学年份尚未确定(未登录, Setting 里是 -1)时不能按差值硬算:
		// (2026 - (-1)) * 2 会被 coerceAtMost 截成 7(大四下), 而登录后 pickYear 不再更新 ——
		// 查询就会带 xnm=2028 返回"该学年学期尚无您的课表"。未知时回退"大一上学期"(0),
		// 登录后由 getYearAndTerm() 按真实入学年份重新计算并同步滚轮。
		if(entranceTime <= 0) return 0
		return if(y < entranceTime) {
			0
		} else {
			((y - entranceTime) * 2 - if(calendar[Calendar.MONTH] < Calendar.AUGUST) 1 else 0).coerceAtMost(Data.TermName.size - 1)
		}
	}

	/**
	 * 查询成绩
	 * @param xnm  学年代码 20xx
	 * @param xqm  学期代码 12 | 3
	 */
	suspend fun queryMark(index: Int, xnm: String, xqm: String): List<Mark> {
		// 【切换教务节点后查不出成绩】节点一换, 旧的 jwglxt 会话就失效,
		// 而查询走的是不校验登录的 postNoCheck, 于是会带着失效会话静默返回空。
		// 查询前先确认登录态: 已保存账号密码的非 VPN 账号会自动重新登录,
		// 否则抛 NeedLoginException 交给我们上层的 request{} 提示"请先登录"。
		if(!isLogin) checkLogin()
		var json: String
		try {
			postNoCheck(QustApi.GET_MARK, FormBody.Builder()
					.add("xnm", xnm).add("xqm", xqm)
					.add("queryModel.showCount", "999").build()
			).use { json = it.body!!.string() }
		} catch(_: Exception) {
			return emptyList()
		}

		val markMap = HashMap<String, Mark.Builder>(32)
		try {
			val item: JSONArray = JSONObject(json).getJSONArray("items")
			for(i in 0 until item.length()) {
				val js: JSONObject = item.getJSONObject(i)
				val name = js.getString("kcmc")
				if(markMap.containsKey(name)) continue
				markMap[name] = Mark.createFromJson(js)
			}
		} catch(e: Exception) {
			Logger.e("url=${QustApi.GET_MARK}, body=${json}", e)
		}

		try {
			postNoCheck(QustApi.GET_MARK_DETAIL, FormBody.Builder()
					.add("xnm", xnm).add("xqm", xqm)
					.add("queryModel.showCount", "999").build()
			).use { json = it.body!!.string() }

			val item = JSONObject(json).getJSONArray("items")
			for(i in 0 until item.length()) {
				val js: JSONObject = item.getJSONObject(i)
				val name = js.getString("kcmc")
				var mark = markMap[name]
				if(mark == null) {
					mark = Mark.createFromJson(js)
					markMap[mark.name] = mark
				}
				mark.addItemMark(js)
			}
		} catch(e: Exception) {
			Logger.e("url=${QustApi.GET_MARK_DETAIL}, body=${json}", e)
		}
		return markMap.values.map { it.build(index, true) }
	}

	/**
	 * 查询考试
	 * @param xnm  学年代码 20xx
	 * @param xqm  学期代码 12 | 3
	 */
	suspend fun queryExam(xnm: String, xqm: String): Array<Exam> {
		var json: String
		try {
			postNoCheck(
				QustApi.GET_EXAM, FormBody.Builder()
					.add("xnm", xnm).add("xqm", xqm)
					.add("queryModel.showCount", "999")
					.build()
			).use {
				json = it.body!!.string()
			}
		} catch(e: Exception) {
			// 【本轮·修"考试查询失败一片空白"】以前网络失败会静默返回空数组,
			// 页面上什么都不显示, 用户分不清是"没有考试"还是"查询失败"。
			// 现在把失败抛给 request() —— 气泡会提示具体原因。
			Logger.e("url=${QustApi.GET_EXAM}", e)
			throw IllegalStateException("暂时无法连接教务系统, 请稍后再试")
		}

		// 【本轮·修"考试查询报英文错误"】以前 `getJSONArray("items")` 裸奔在外:
		// 教务返回的如果不是 {"items":[...]}(错误提示 JSON / 登录页 / 系统升级页),
		// 用户会看到一句 "No value for items" 或 "Value <!DOCTYPE of type..." 的英文气泡。
		// 现在: 登录页原样抛出(request() 会按"未登录"处理); 有教务自己的提示就弹提示;
		// 都不是才说"没有返回考试数据"。
		val item = try {
			JSONObject(json).getJSONArray("items")
		} catch(e: Exception) {
			Logger.e("url=${QustApi.GET_EXAM}, body=${json.take(300)}", e)
			if(json.contains("<!DOCTYPE", true) || json.contains("<html", true)) throw e
			val msg = runCatching {
				val js = JSONObject(json)
				listOf("msg", "message").firstNotNullOfOrNull { k ->
					js.optString(k).takeIf { it.isNotBlank() }
				}
			}.getOrNull()
			throw IllegalStateException(msg ?: "教务没有返回考试数据, 可能本学期考试安排还未发布")
		}
		val array = ArrayList<Exam>(item.length())
		for(i in 0 until item.length()){
			array.add(Exam.createFromJson(item.getJSONObject(i)))
		}

		return array.toTypedArray()
	}

	/**
	 * 查询教务通知
	 * @param page         第几页
	 * @param pageSize     每页数量
	 */
	suspend fun queryNotice(page: Int = 1, pageSize: Int = 1): Array<Notice> {
		var json = ""
		try{
			postNoCheck(QustApi.EA_SYSTEM_NOTICE, FormBody.Builder()
					.add("queryModel.showCount", pageSize.toString())
					.add("queryModel.currentPage", page.toString())
					.add("queryModel.sortName", "cjsj")
					.add("queryModel.sortOrder", "desc")
					.build()
			).use { json = it.body!!.string() }
		}catch(e: Exception){
			Logger.e("'url:'${QustApi.EA_SYSTEM_NOTICE}\n$json", e)
			return emptyArray()
		}

		val array: ArrayList<Notice> = ArrayList()
		if(json.startsWith("{") || json.startsWith("[")) {
			val item = JSONObject(json).getJSONArray("items")
			for(i in 0 until item.length()) array.add(Notice.createFromJson(item.getJSONObject(i)))
		}
		return array.toArray(arrayOfNulls<Notice>(0))
	}


		/**
		 * 查询学业情况
		 *
		 * 模块 id 用宽松正则解析(兼容单/双引号、等号两侧空格、属性顺序), 不再因页面写法不同
		 * 漏掉"通识任选"等自由选课模块; 模块名用"包含匹配(容忍空格换行)"识别, 通识任选等
		 * 一定会被保留显示。解析逻辑升级后, 旧 Room 缓存由 GetAcademicViewModel 的缓存版本号强制重查覆盖。
		 */

		/**
		 * 课程类别/性质关键字(覆盖培养方案全部模块)。
		 * 用"包含匹配"而非完全相等, 兼容页面写法(多余空格 / 换行把"通识任选"拆成"通 识 任 选")。
		 */
		private val CATEGORY_KEYWORDS = arrayOf(
			"必修", "限选", "专选(必选)", "专选", "实践",
			"通识限选", "通识必修", "通识任选", "通识-任选", "通识选修课", "公共任选", "讲座与辅导"
		)

		/**
		 * 清洗课程类别文本: 剔除零宽/控制等隐形字符, 全角空格转半角, 各类连字符统一为半角连字符,
		 * 内部换行/制表符转空格并折叠连续空白、去首尾空白。清洗后再做关键字匹配, 避免隐形字符导致匹配失效。
		 */
		private fun cleanCategoryText(raw: String): String {
			if(raw.isEmpty()) return raw
			val sb = StringBuilder(raw.length)
			for(c in raw) {
				when {
					// 内部换行/制表符 -> 半角空格
					c == '\n' || c == '\r' || c == '\t' -> sb.append(' ')
					// 全角空格 -> 半角空格
					c == '\u3000' -> sb.append(' ')
					// 零宽字符 / 软连字符: 直接丢弃
					c == '\u200B' || c == '\u200C' || c == '\u200D' || c == '\uFEFF' || c == '\u00AD' -> { }
					// 各类连字符(含全角减号)统一为半角连字符, 兼容"通识‑任选"等不同写法
					c == '\u2010' || c == '\u2011' || c == '\u2012' || c == '\u2013' || c == '\u2212' || c == '\uFF0D' -> sb.append('-')
					// 其余不可见控制字符(C0/C1): 直接丢弃
					c < '\u0020' || c == '\u007F' || (c in '\u0080'..'\u009F') -> { }
					else -> sb.append(c)
				}
			}
			return sb.toString().replace(Regex("\\s+"), " ").trim()
		}

		/**
		 * 归一化类别文本: 先清洗(剔除隐形字符/全角空格/控制符), 再用清洗后的文本做"包含匹配"已知关键字,
		 * 命中则返回该关键字(优先更具体的), 否则返回清洗后的文本。保证"通识任选"等模块名被稳定识别、不被隐形字符滤掉。
		 */
		private fun normalizeCategoryName(raw: String): String {
			val cleaned = cleanCategoryText(raw)
			if(cleaned.isEmpty()) return raw.trim()
			for(kw in CATEGORY_KEYWORDS.sortedByDescending { it.length }) {
				if(cleaned.contains(kw)) return kw
			}
			return cleaned
		}

		/**
		 * 【第二十四轮】接口B(学业情况-课程明细)在"通识任选/自由选课"类模块上常返回空数组。
		 * 网页端的明细由页面 JS 动态加载, 请求形式可能与直接调用不同 ——
		 * 这里按网页端可能的两种形式重试(补上 gnmkdm、去掉/带上 xh_id), 取第一个非空结果。
		 * @return 非空课程数组; 全部为空/失败返回 null
		 */
		private suspend fun queryAcademicModuleCoursesRetry(moduleId: String): JSONArray? {
			val url = QustApi.ACADEMIC_INFO + "?gnmkdm=N105515"
			val forms = listOf(
				listOf("xfyqjd_id" to moduleId),
				listOf("xfyqjd_id" to moduleId, "xh_id" to accountName)
			)
			for(params in forms){
				try{
					val body = postNoCheck(url, FormBody.Builder().apply {
						params.forEach { (k, v) -> add(k, v) }
					}.build()).use { it.body!!.string() }
					val arr = JSONArray(body)
					AcademicDiag.add("[接口B重试] 模块[$moduleId] 形式=${params.joinToString("+") { it.first }} 课程数=${arr.length()} 前120字=${body.take(120)}")
					if(arr.length() > 0) return arr
				}catch(e: Exception){
					AcademicDiag.add("[接口B重试] 模块[$moduleId] 形式=${params.joinToString("+") { it.first }} 请求/解析失败: ${e.message}")
				}
			}
			return null
		}

		/**
		 * 【第二十四轮】通识任选类课程**不在培养方案(教学执行计划)里** —— 学业情况接口(接口A/B)对这类模块
		 * 结构上就返回空; 学生的实际课程来自"学生选课"记录。这里按**当前学期**抓取"已选课程",
		 * 取出其中的任选/公选/通识类课程(名称/课程号/学分), 供通识任选模块展示。
		 * 任何失败都返回空列表(不影响其它模块的正常显示)。
		 */
		private suspend fun queryCurrentElectives(): List<LessonInfo> {
			// 当前学期代码: 9月~次年1月 = 秋季(xqm=3, 学年为当年/前一年), 2~7月 = 春季(xqm=12, 学年为前一年)
			val c = Calendar.getInstance()
			val year = c[Calendar.YEAR]
			val month = c[Calendar.MONTH] + 1
			val xnm: String
			val xqm: String
			when {
				month >= 9 -> { xnm = year.toString(); xqm = "3" }
				month <= 1 -> { xnm = (year - 1).toString(); xqm = "3" }
				else -> { xnm = (year - 1).toString(); xqm = "12" }
			}
			val body = try{
				postNoCheck(QustApi.CHOOSED_COURSES, FormBody.Builder()
					.add("xkxnm", xnm).add("xkxqm", xqm).build()
				).use { it.body!!.string() }
			}catch(e: Exception){
				AcademicDiag.add("[选课兜底] 请求异常: ${e.message}")
				return emptyList()
			}
			AcademicDiag.add("[选课兜底] 学期 xnm=$xnm xqm=$xqm 响应前200字=${body.take(200)}")
			val out = ArrayList<LessonInfo>()
			try{
				val arr = JSONArray(body)
				for(i in 0 until arr.length()){
					val js = arr.getJSONObject(i)
					val name = js.optString("kcmc", "").trim()
					if(name.isEmpty()) continue
					val category = js.optString("kklxmc", "").trim()
					val credit = js.optString("xf", "").toFloatOrNull() ?: 0F
					val kch = js.optString("kch", "").trim()
					// 只收任选/公选/通识类课程(其它课程在各自模块里自然出现, 避免重复与污染)
					if(!(category.contains("任选") || category.contains("公选") || category.contains("通识"))){
						AcademicDiag.add("[选课兜底] 跳过非任选类: $name（$category）")
						continue
					}
					out.add(LessonInfo(
						kchId = if(kch.isNotEmpty()) kch else "XXK_$name",
						name = name, type = "通识任选", credit = credit, mark = "", gpa = 0F, status = 1,
						category = "通识任选", content = "（本学期已选，成绩未出）", index = -1, group = -1
					))
				}
			}catch(e: Exception){
				AcademicDiag.add("[选课兜底] 解析失败: ${e.message}")
			}
			return out
		}

		suspend fun getAcademic(): Pair<List<LessonInfoGroup>, Array<LessonInfo>> {
			// 【切换教务节点后查不出学业】同 queryMark: 节点切换使旧会话失效,
			// 查询前先确认登录态, 未登录则抛 NeedLoginException 由上层提示重新登录。
			if(!isLogin) checkLogin()
			// 查询到的所有课程
			val lessonInfo = ArrayList<LessonInfo>(64)
			// 储存所有课程的分组
			val lessonGroups = LinkedHashMap<String, LessonInfoGroup.Builder>()

			// 【超时保护】整体查询(网络 + 解析)最多等 ACADEMIC_QUERY_TIMEOUT, 超时返回空, 不卡死 UI
			return withTimeoutOrNull(ACADEMIC_QUERY_TIMEOUT) {
				try {
					AcademicDiag.clear()  // 每次查询重置诊断缓冲(长按重新查询可查看)
					var html: String
					getNoCheck(QustApi.ACADEMIC_PAGE).use { html = it.body!!.string() }
					val pageHtml = html  // 接口A 首页HTML; 循环内 html 会被接口B响应覆盖, 兜底解析需用本副本
					// 【接口A 日志】打印学分统计接口(学业情况查询首页)完整返回, 确认课程对象是否到达客户端
					Logger.d("[学业查询][接口A] 完整返回HTML长度=${pageHtml.length}")
					AcademicDiag.add("[接口A] HTML长度=${pageHtml.length}")
					// 模块网格数据在页面中后部(CSS/JS 占前半), 从首个"要求学分"起截取, 才能看到模块与课程行
				val pageNorm = AcademicPageParser.normalize(pageHtml)
				val gridIdx = pageNorm.indexOf("要求学分")
				val gridStart = if(gridIdx >= 0) (gridIdx - 200).coerceAtLeast(0) else 0
				AcademicDiag.add("[接口A] 模块区纯文本(从'要求学分'起1500字):\n" + pageNorm.substring(gridStart).take(1500))
					pageHtml.chunked(3000).forEachIndexed { i, c -> Logger.d("[学业查询][接口A] HTML#$i:\n$c") }

					// 1) 模块 id: 宽松正则(复用 AcademicPageParser), 兼容各种写法, 不漏模块
				val idMap = AcademicPageParser.parseModuleIds(pageHtml)
				Logger.d("[学业查询] 解析到模块数=${idMap.size}, ids=${idMap.keys.joinToString()}")
					AcademicDiag.add("[解析] 模块数=${idMap.size}, ids=${idMap.keys.joinToString()}")
				// 【模块级诊断】把页面上"可见的模块标题行"也打出来: 若页面有"通识任选"标题但 idMap 没它的 xfyqjd_id,
				// 说明 parseModuleIds 漏抓了模块(渠道/页面结构问题); 若连标题都没有, 说明该课根本不在 N105515 这份数据里。
				val pageHeaders = AcademicPageParser.parseModuleHeaders(pageHtml)
				Logger.d("[学业查询] 页面可见模块标题行(${pageHeaders.size}): ${pageHeaders.joinToString { it.name }}")
					AcademicDiag.add("[解析] 页面标题行(${pageHeaders.size}): ${pageHeaders.joinToString { it.name }}")
					// 【按序对齐】标题行与模块id都按页面顺序排列: 数量相等时1:1配对(最可靠), 否则窗口就近
					val headerList = AcademicPageParser.parseModuleHeadersAligned(pageHtml, idMap)
				val moduleSummary = ArrayList<String>()
				// 【JS 动态标题】页面模块标题由 <script> 拼接构建(可见文本里没有"XX 要求学分:A"), 从脚本模板解析并按 id 配对
				val jsHeaders = AcademicPageParser.parseModuleHeadersFromJs(pageHtml)
				val jsCounts = AcademicPageParser.parseModuleCountsFromJs(pageHtml)
				val appendTargets = AcademicPageParser.parseModuleAppendTargets(pageHtml)
				Logger.d("[学业查询] JS模块标题(${jsHeaders.size}): ${jsHeaders.entries.joinToString { "${it.key.takeLast(8)}=${it.value.name}" }}")
				Logger.d("[学业查询] JS模块课程数(${jsCounts.size}): ${jsCounts.entries.joinToString { "${it.key.takeLast(8)}=${it.value}" }}")
				AcademicDiag.add("[JS标题](${jsHeaders.size}) " + jsHeaders.entries.joinToString { "${it.key.takeLast(8)}=${it.value.name}/要求${it.value.requireCredits}" })
				AcademicDiag.add("[JS课程数](${jsCounts.size}) " + jsCounts.entries.joinToString { "${it.key.takeLast(8)}=${it.value}" })
				// 【上下文】直接抓原始HTML里"通识任选"出现处的上下文, 看该模块及其课程数据是否静态内嵌在脚本里
				run {
					var occ = 0
					var cIdx = pageHtml.indexOf("通识任选")
					while(cIdx >= 0 && occ < 3){
						val from = (cIdx - 200).coerceAtLeast(0)
						val to = (cIdx + 500).coerceAtMost(pageHtml.length)
						AcademicDiag.add("[上下文#$occ '通识任选'@$cIdx]:\n" + pageHtml.substring(from, to))
						occ++
						cIdx = pageHtml.indexOf("通识任选", cIdx + 1)
					}
					if(occ == 0) AcademicDiag.add("[上下文] 静态HTML未出现'通识任选'字样")
				}
				// 【第二十四轮·加载器诊断】学业情况页的课程明细由页面 JS 动态加载(kcxx div 静态为空):
				// 打出页面里所有 .html 地址、script 引入与 "kcxx" 的非模板出现处, 直接定位网页端真正的明细接口。
				run {
					val urls = LinkedHashSet<String>()
					Regex("[A-Za-z0-9_./\\-]+\\.html(?:\\?[^\"'\\s<>]*)?").findAll(pageHtml).forEach { m ->
						if(urls.size < 24) urls.add(m.value)
					}
					AcademicDiag.add("[加载器] 页面内 .html 地址(${urls.size}):\n" + urls.joinToString("\n"))
					val srcs = Regex("<script[^>]+src\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
						.findAll(pageHtml).map { it.groupValues[1] }.distinct().take(20).toList()
					AcademicDiag.add("[加载器] script src(${srcs.size}): " + srcs.joinToString(", "))
					var kOcc = 0
					var kIdx = pageHtml.indexOf("kcxx")
					while(kIdx >= 0 && kOcc < 5){
						val before = pageHtml.substring((kIdx - 60).coerceAtLeast(0), kIdx)
						val isTemplate = before.contains("xfyqjd_id") || before.contains("id='") || before.contains("id ='")
						if(!isTemplate){
							AcademicDiag.add("[加载器] 'kcxx' 非模板出现#$kOcc @$kIdx:\n" + pageHtml.substring((kIdx - 260).coerceAtLeast(0), (kIdx + 260).coerceAtMost(pageHtml.length)))
							kOcc++
						}
						kIdx = pageHtml.indexOf("kcxx", kIdx + 1)
					}
					if(kOcc == 0) AcademicDiag.add("[加载器] 'kcxx' 仅出现在模板字符串中(加载逻辑在外链 js): 见上方 script src 列表")
				}
				for((id, _) in idMap) {
						if(!lessonGroups.containsKey(id)) lessonGroups[id] = LessonInfoGroup.Builder()
					}

					// 2) 学分: 窗口式解析, 兼容属性顺序/引号/间距
					val credits = AcademicPageParser.parseCredits(pageHtml)
					for((id, pair) in credits) {
						lessonGroups[id]?.let { g ->
							if(pair.first > 0F) g.obtainedCredits = pair.first
							if(pair.second > 0F) g.requireCredits = pair.second
						}
					}

				val entranceTime = this@EASAccount.entranceTime
									// 【去重】跨模块/跨接口(B+A)按课程 kchId 去重, 避免重复添加同一门课
				val existingIds = HashSet<String>()
				// 【第二十四轮】本学期已选课程缓存(懒加载, 整个查询只请求一次)
				var electivesCache: List<LessonInfo>? = null
				val rawB = LinkedHashMap<String, String>()
				idMap.entries.forEachIndexed { index, (id, _) ->
					try {
						var fallbackCount = 0
					// 通过模块在页面上的位置取临近标题, 判定是否"通识任选"
					val headerName = jsHeaders[id]?.name ?: headerList.getOrNull(index)?.name ?: ""
					val isElective = run {
						// 非通识任选 保持原逻辑; 通识任选(板块名含"通识"+"任选")走接口A兜底
						val c = cleanCategoryText(headerName)
						val byName = c.contains("通识") && c.contains("任选")
						// 【需求G】通识任选等自由选课模块的标题由 <script> 拼接, 子模块(如长 hex id
						// 3630E18D174F84B1E0630100007F6DFE 挂在某父模块下)标题名常抓不到 ->
						// isElective=false -> 整组被 totalCounts>0 过滤而消失。
						// 兜底: 在"本模块 id 到下一个模块 id"之间的整段页面文本里找"通识任选"字样
						// (不受 JS 拼接距离限制, 长 id 也能命中), 命中即视为通识任选,
						// 保证通识任选模块在学业查询里至少以占位条目呈现(服务器数=1 时显示 1 门)。
						val pos = idMap[id] ?: -1
						val byContext = !byName && pos >= 0 && run {
							val ids = idMap.values.toList()
							val idx = ids.indexOf(pos)
							val nextPos = if(idx >= 0 && idx + 1 < ids.size) ids[idx + 1] else pageHtml.length
							val seg = pageHtml.substring(pos, nextPos.coerceAtMost(pageHtml.length))
							seg.contains("通识任选")
						}
						byName || byContext
					}
						postNoCheck(QustApi.ACADEMIC_INFO, FormBody.Builder().add("xfyqjd_id", id).add("xh_id", accountName).build()).use { html = it.body!!.string() }
						// 【第二十四轮】接口B为空或响应无法解析时, 按网页端可能的请求形式重试(补 gnmkdm / 去掉 xh_id),
						// 取第一个非空结果 —— 通识任选等模块在网页端走的是动态加载, 直接调用可能参数形式不同而返回空。
						var array: JSONArray = try{ JSONArray(html) }catch(_: Exception){ JSONArray() }
						// 只对"通识任选类"与短id特殊模块(其他课程/综合素质选修等)重试;
						// 主修等根节点(长hex id)在培养方案里本就无课程, 不必重试浪费请求。
						if(array.length() == 0 && (isElective || id.length < 30)){
							val retry = queryAcademicModuleCoursesRetry(id)
							if(retry != null){ array = retry; html = retry.toString() }
						}
						rawB[id] = html
						// 【接口B 日志】打印课程明细接口完整返回JSON, 确认课程对象是否到达客户端
						Logger.d("[学业查询][接口B] 模块[$id] 标题=[$headerName] 是否通识任选=$isElective 返回课程数=${array.length()}")
						Logger.d("[学业查询][接口B] 模块[$id] 完整返回JSON:\n$html")
						AcademicDiag.add("[接口B] 模块[$id] 标题=[$headerName] 通识任选=$isElective 课程数=${array.length()} 响应前200字=${html.take(200)}")
						val group = lessonGroups[id]!!
						group.group = index
						if(array.length() > 0) {
							// ===== 分支一: B 接口有数据, 沿用原逻辑(先清洗类别再做分类匹配) =====
							var lastName = ""
							var lastCategory = ""
							for(i in 0 ..< array.length()) {
								val info = LessonInfo.createFromJson(array.getJSONObject(i), entranceTime, index)
								Logger.d("[学业查询] 课程=${info.name} 性质=${info.type} 类别=${info.category} 状态=${info.status}")
								val rawCat = if(info.type.isNotEmpty()) info.type else info.category
								Logger.d("[学业查询][类别清洗] 原始文本=[${rawCat}]")
								Logger.d("[学业查询][类别清洗] 清洗文本=[${cleanCategoryText(rawCat)}]")
								lastName = info.type
								lastCategory = info.category
								if(existingIds.add(info.kchId)) {
									if(info.status == 2) {
										// 统计未过课程的学分
										group.creditNotEarned += info.credit
									} else if(info.status == 4) {
										// 统计已修门数
										group.passedCounts++
									}
									group.totalCounts++
									lessonInfo.add(info)
								} else {
								Logger.d("[学业查询][去重] 课程=${info.name}(${info.kchId}) 已存在, 跳过")
								}
							}
							// 模块名: 通识任选固定归属通识任选; 其余用课程性质/类别归一化
							group.type = if(isElective) "通识任选" else normalizeCategoryName(if(lastName.isNotEmpty()) lastName else lastCategory)
						}
						if(isElective) {
							// ===== 分支二: B 接口无数据 且 为通识任选 -> 直接解析接口A HTML 兜底补充 =====
							Logger.d("[学业查询][分支二] 模块[$id] 通识任选兜底: 接口A作为权威数据源补充(按kchId去重)")
						AcademicDiag.add("[分支二] 模块[$id] 标题=[$headerName] 启动接口A兜底补充")
							val fallback = AcademicPageParser.parseModuleCoursesFromHtml(pageHtml, id, entranceTime, index) { msg -> AcademicDiag.add(msg) }
							Logger.d("[学业查询][分支二] 模块[$id] 接口A解析到课程数=${fallback.size}")
						AcademicDiag.add("[分支二] 模块[$id] 接口A解析到课程=${fallback.size}")
							for(info in fallback) {
								Logger.d("[学业查询][分支二] 课程=${info.name} 学分=${info.credit} 状态=${info.status}")
								if(existingIds.add(info.kchId)) {
									if(info.status == 2) group.creditNotEarned += info.credit
									else if(info.status == 4) group.passedCounts++
									group.totalCounts++
									lessonInfo.add(info)
								} else {
								Logger.d("[学业查询][去重] 课程=${info.name}(${info.kchId}) 已存在, 跳过")
								}
							}
						if(fallback.isNotEmpty()) group.type = "通识任选"
						fallbackCount = fallback.size
						// 【通识任选深度诊断】接口B完整响应 + 接口A段原始HTML, 看课到底在哪个数据源
						AcademicDiag.add("[深度][通识任选 $id] 接口B完整响应(前800):\n" + html.take(800))
						val sect = AcademicPageParser.getModuleSectionRaw(pageHtml, id)
						AcademicDiag.add("[深度][通识任选 $id] 接口A段含<tr>=${sect.contains("<tr", ignoreCase = true)} 原始HTML前1500:\n" + sect.take(1500))
						// 父模块接口B(通识任选的课程数可能挂在父容器上, 服务端计数也挂在父 li 上)
						val parentLi = appendTargets[id]
						val parentJson = parentLi?.let { rawB[it] }
						if(parentJson != null){
							AcademicDiag.add("[深度][通识任选 $id] 父模块[$parentLi] 接口B前400: ${parentJson.take(400)}")
						} else {
							AcademicDiag.add("[深度][通识任选 $id] 未找到父模块映射(appendTo=$parentLi)")
						}
						// 全页搜索疑似课程名, 看通识任选课程是否静态嵌在别处
						run {
							var occ = 0
							var sIdx = pageHtml.indexOf(id)
							var cIdx = if(sIdx >= 0) pageHtml.indexOf("通识任选", sIdx) else -1
							while(cIdx >= 0 && occ < 2){
								val from = (cIdx - 50).coerceAtLeast(0)
								val to = (cIdx + 900).coerceAtMost(pageHtml.length)
								AcademicDiag.add("[深度][通识任选 $id] 附近全文#$occ:\n" + pageHtml.substring(from, to))
								occ++
								cIdx = pageHtml.indexOf("通识任选", cIdx + 1)
							}
						}
						moduleSummary.add("[汇总][通识任选 $id] 接口A段含课程行<tr>=${sect.contains("<tr", ignoreCase = true)} B课程数=${array.length()} 兜底课程数=${fallback.size} 服务器数=${jsCounts[id] ?: appendTargets[id]?.let { jsCounts[it] } ?: -1} 父模块=${parentLi ?: "-"}")
						// 【第二十四轮】通识任选课程不在培养方案里: 接口B/接口A都拿不到时, 用"学生选课"记录兜底
						// (抓本学期已选课程中的任选/公选/通识类课程, 带名称/课程号/学分);
						// 连选课记录也没有时, 才生成占位条目保证"通识任选"模块在学业查询里可见。
						if(fallback.isEmpty()){
							if(electivesCache == null) electivesCache = queryCurrentElectives()
							var added = 0
							for(elective in electivesCache!!){
								val one = elective.copy(index = index, group = index)
								if(existingIds.add(one.kchId)){
									group.totalCounts++
									lessonInfo.add(one)
									added++
								}
							}
							if(added > 0){
								group.type = "通识任选"
								AcademicDiag.add("[分支二][选课兜底] 模块[$id] 从本学期已选课程补入 $added 门")
							}else{
								val ph = LessonInfo(
									kchId = "TXRX_$id", name = if(headerName.isNotEmpty()) headerName else "通识任选",
									type = "通识任选", credit = 0F, mark = "", gpa = 0F, status = 0,
									category = "通识任选", content = "（课程明细服务端未返回, 详见教务系统网页端）",
									index = index, group = index
								)
								if(existingIds.add(ph.kchId)){ group.totalCounts++; lessonInfo.add(ph) }
								group.type = "通识任选"
								AcademicDiag.add("[分支二][占位] 模块[$id] 接口B/接口A/选课兜底均无课程, 生成占位条目以保证可见")
							}
						}
					} else if(array.length() == 0) {
						// 非通识任选 且 接口B无数据: 保持原行为(不兜底, 模块最终被过滤掉)
						Logger.d("[学业查询] 模块[$id] 接口B无数据且非通识任选, 跳过")
					AcademicDiag.add("[跳过] 模块[$id] 标题=[$headerName] 接口B无数据且非通识任选(保持原行为)")
					}
					moduleSummary.add("[汇总] 模块[$id] 标题=[$headerName] 通识任选=$isElective B课程数=${array.length()} 兜底课程数=$fallbackCount 服务器数=${jsCounts[id] ?: appendTargets[id]?.let { jsCounts[it] } ?: -1}")
					} catch(e: Exception) {
							Logger.e("[学业查询] 模块[$id]解析失败: ${e.message}", e)
						}
					}
					// 【汇总】放在循环之后(环形缓冲尾部, 不易被逐课日志冲掉): 一眼看清每个模块B接口/兜底各返回几门
					AcademicDiag.add("[汇总] 全部模块解析结果:\n" + moduleSummary.joinToString("\n"))
					// 只显示"有课程"的模块(有课的一定显示, 通识任选等自由选课模块自然出现)
					Pair(lessonGroups.values.filter { it.totalCounts > 0 }.map { it.build() }, lessonInfo.toTypedArray())
				} catch(e: Exception) {
					Logger.e("[学业查询] 查询失败: ${e.message}", e)
					Pair(emptyList(), emptyArray())
				}
			} ?: run {
				Logger.w("[学业查询] 查询超时(${ACADEMIC_QUERY_TIMEOUT}ms), 返回空")
				Pair(emptyList(), emptyArray())
			}
		}

}