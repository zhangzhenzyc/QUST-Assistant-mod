/*
 * 青科助手（修改版） —— 基于开源项目 QUST-Assistant 修改而成
 *
 * 原项目版权所有 (C) 2891954521  (https://github.com/2891954521/QUST-Assistant)
 * 原始开源许可: GNU General Public License v3.0 (GPL-3.0), 许可原文见 LICENSE 文件, 保持完整未改动。
 *
 * 修改版版权所有 (C) 2026 zhangzhenzyc  (https://github.com/zhangzhenzyc)
 * 本修改版同样以 GPL-3.0 发布; 附加版权声明见 NOTICE.md —— 两份版权声明相互独立、同时生效。
 */

package com.qust.helper.viewmodel.eas

import android.app.Application
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.qust.helper.data.Data.TermName
import com.qust.helper.data.eas.Exam
import com.qust.helper.model.Logger
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

@OptIn(ExperimentalSerializationApi::class)
class GetExamsViewModel(application: Application) : BaseEasViewModel(application){

	private val markDataPath: File

	var examData: Array<Array<Exam>>

	var exams: MutableState<Array<Exam>>

	init{
		markDataPath = File(application.filesDir, "exam")
		if(markDataPath.exists()) {
			try {
				FileInputStream(markDataPath).use {
					examData = Json.decodeFromStream<Array<Array<Exam>>>(it)
				}
			}catch(_: Exception){
				examData = Array(TermName.size) { Array(0){ Exam() } }
			}
		}else{
			examData = Array(TermName.size) { Array(0){ Exam() } }
		}
		exams = mutableStateOf(examData[yearIndex(pickYear.value)])
	}

	/** 【第七十轮】滚轮位置越界钳制: 存档的学期数组长度变小时(换过账号/旧存档), pickYear 可能越界 —— 直接下标会 ArrayIndexOutOfBounds, 表现为"查询中"一闪而过 + 一条看不懂的英文气泡 */
	private fun yearIndex(index: Int): Int = index.coerceIn(0, examData.size - 1)

	/** 【第七十轮】最近一次查询失败的原因: 列表为空时显示在页面上(不只靠一闪而过的气泡) */
	var queryError by mutableStateOf("")

	fun queryExams() {
		request({
			showDialog("查询中")
			val pair = getYearAndTerm()
			examData[yearIndex(pickYear.intValue)] = easAccount.queryExam(pair.first, pair.second)
			queryError = ""
			exams.value = examData[yearIndex(pickYear.intValue)]
			try {
				FileOutputStream(markDataPath).use {
					Json.encodeToStream(examData, it)
				}
			} catch(e: IOException) {
				Logger.e(e)
			}
		}, { clearDialog() 		}, { e ->
			// 【第七十轮】把失败原因留在页面上: "查询中"一闪而过、气泡也容易错过,
			// 列表为空时页面上必须有一行能读的说明 —— 用户才能自己判断是没登录、
			// 还是网络不通、还是本学期真没排考。
			queryError = e.message?.takeIf { m -> m.isNotBlank() } ?: "查询失败，请稍后再试"
			exams.value = examData[yearIndex(pickYear.intValue)]
		}, {
			// 【v3.6.0180·修"点查询只闪一下、页面上什么都没有"】被拦在"未登录"这一关时
			// 以前不走 onError, 页面上一个字都没有 —— 现在留一句能读的, 并说明后续会自动重查。
			queryError = "还没登录教务系统，登录后会自动重新查询"
			exams.value = examData[yearIndex(pickYear.intValue)]
		})
	}
}


