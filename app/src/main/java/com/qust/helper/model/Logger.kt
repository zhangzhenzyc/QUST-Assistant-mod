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

import android.util.Log
import com.qust.helper.App
import com.qust.helper.data.Setting
import com.qust.helper.utils.DateUtils
import com.qust.helper.utils.FileUtils
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Date

object Logger {

	// Log路径
	var LogFile: String? = null

	private var DEBUG = false

	private var UPLOAD_LOG = false

	/**
	 * 初始化日志模块并处理日志上报
	 */
	fun init(app: App) {
		DEBUG = Setting.getBoolean("key_debug")
		val f = app.getExternalFilesDir("log")
		if(!f!!.exists()) f.mkdirs()
		LogFile = f.toString()
	}

	// 【安全改造】Release 版本一律不输出日志(禁止账号/Cookie/HTML 等敏感信息进入 logcat)
	private val logEnabled: Boolean
		get() = com.qust.helper.BuildConfig.DEBUG

	fun d(msg: String, e: Throwable? = null){
		if(!logEnabled) return
		Log.d("QustHelper", msg, e)
	}

	fun i(msg: String, e: Throwable? = null){
		if(!logEnabled) return
		Log.i("QustHelper", msg, e)
	}

	fun w(msg: String, e: Throwable? = null){
		if(!logEnabled) return
		Log.w("QustHelper", msg, e)
	}

	fun e(msg: String, e: Throwable? = null){
		if(!logEnabled) return
		Log.e("QustHelper", msg, e)
	}

	fun e(e: Throwable){
		if(!logEnabled) return
		Log.e("QustHelper", "", e)
	}

	/**
	 * 调试模式下才会进行输出
	 * @param msg message
	 */
	fun Log(msg: String) {
		if(DEBUG) {
			android.util.Log.i("i", msg)
			debugLog(msg)
		}
	}

	/**
	 * 强制输出到 debug 文件
	 */
	fun debugLog(string: String) {
		FileUtils.appendFile(File(LogFile, "debug.log"), string + "\n")
	}

	/**
	 * Log 输出异常
	 */
	fun Log(e: Throwable) {
		Log(e, UPLOAD_LOG)
	}

	/**
	 * Log 输出异常
	 */
	fun Log(s: String?, e: Throwable) {
		Log(s, e, UPLOAD_LOG)
	}

	/**
	 * Log 输出异常
	 * @param e 异常
	 * @param needUpload 是否需要上报
	 */
	fun Log(e: Throwable, needUpload: Boolean) {
		Log(null, e, needUpload)
	}

	/**
	 * Log 输出异常
	 * @param msg 附加错误信息
	 * @param e 异常
	 * @param needUpload 是否需要上报
	 */
	fun Log(msg: String?, e: Throwable, needUpload: Boolean) {
		val stringWriter = StringWriter()
		e.printStackTrace(PrintWriter(stringWriter))
		var str = stringWriter.toString()
		if(msg != null) str += if(msg.length > 4096) msg.substring(0, 4096) else msg
		val hash = str.hashCode()
		val f = File(LogFile, "$hash.log")
		if(f.exists()) {
			debugLog(DateUtils.YMD_HM.format(Date(System.currentTimeMillis())) + " 发生异常:" + hash)
			if(needUpload) {
				try {
					FileUtils.appendFileNewLine(File(LogFile, "upload.log"), JSONObject().put("hash", hash).toString())
				} catch(ignored: JSONException) {
				}
			}
		} else {
			FileUtils.writeFile(f.toString(), str)
			val message = e.javaClass.name + " : " + e.message
			if(needUpload) {
				try {
					FileUtils.appendFileNewLine(File(LogFile, "upload.log"), JSONObject().put("hash", hash).put("message", message).toString())
				} catch(ignored: JSONException) { }
			}
			debugLog(DateUtils.YMD_HM.format(Date(System.currentTimeMillis())) + " 发生异常:" + hash + "\n" + message)
		}
	}
}