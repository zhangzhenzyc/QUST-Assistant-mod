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

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.qust.helper.data.Keys
import com.qust.helper.data.Setting
import com.qust.helper.data.room.LessonDatabase
import com.qust.helper.model.account.EASAccount
import com.qust.helper.model.account.NeedLoginException
import com.qust.helper.utils.NotificationUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

class AutoQueryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

	override suspend fun doWork(): Result {
		return try {
			withContext(Dispatchers.IO){
				try {
					val lessonData = LessonDatabase.getInstance(applicationContext)

					val easAccount = EASAccount.getInstance()
					if(easAccount.checkLogin()) {

						val notices = easAccount.queryNotice(1, 1)
						if(notices.isNotEmpty()){
							val notice = notices.first()
							val id = Setting[Keys.LAST_NOTICE_ID]
							if(id != notice.id){
								Setting.edit { it.putString(Keys.LAST_NOTICE_ID, notice.id) }
								// 【本期】点击通知进入「教务通知」页(不再无跳转)
								NotificationUtils.sendNotification(
									applicationContext, "教务通知", notices.first().content,
									Keys.Page.GetNoticePage
								)
							}
						}

						val currentYear = easAccount.getCurrentGrade()
						val xnm = (currentYear / 2 + easAccount.entranceTime).toString()
						val xqm = if(currentYear % 2 == 0) "3" else "12"

						val newMarks = easAccount.queryMark(currentYear, xnm, xqm)
						if(newMarks.isNotEmpty() && newMarks.size > lessonData.markDao().countByIndex(currentYear)) {
							NotificationUtils.sendNotification(applicationContext, "成绩通知", "已查询到新的成绩！", Keys.Page.GetMarksPage)
						}

					}
				} catch(ignore: NeedLoginException) {
				} catch(ignore: IOException) {
				}
			}
			Result.success()
		} catch (throwable: Throwable) {
			Result.failure()
		}
	}

}