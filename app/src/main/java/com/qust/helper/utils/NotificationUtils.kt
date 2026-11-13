/*
 * 青科助手（修改版） —— 基于开源项目 QUST-Assistant 修改而成
 *
 * 原项目版权所有 (C) 2891954521  (https://github.com/2891954521/QUST-Assistant)
 * 原始开源许可: GNU General Public License v3.0 (GPL-3.0), 许可原文见 LICENSE 文件, 保持完整未改动。
 *
 * 修改版版权所有 (C) 2026 zhangzhenzyc  (https://github.com/zhangzhenzyc)
 * 本修改版同样以 GPL-3.0 发布; 附加版权声明见 NOTICE.md —— 两份版权声明相互独立、同时生效。
 */

package com.qust.helper.utils

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import androidx.core.app.NotificationCompat
import com.qust.helper.R

object NotificationUtils {

	private const val NOTIFICATION_ID = "push"
	private const val NOTIFICATION_NAME = "推送通知"
	private const val NOTIFICATION_SHOW_AT_MOST = 10

	private var notificationNum = 0

	fun sendNotification(context: Context, title: String?, content: String, page: String? = null) {
		if(notificationNum++ > NOTIFICATION_SHOW_AT_MOST) notificationNum = 0

		val manager: NotificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

		// 检查渠道
		if(Build.VERSION.SDK_INT > Build.VERSION_CODES.O) {
			if(manager.getNotificationChannel(NOTIFICATION_ID) == null) {
				val channel = NotificationChannel(
					NOTIFICATION_ID,
					NOTIFICATION_NAME,
					NotificationManager.IMPORTANCE_HIGH
				)
				manager.createNotificationChannel(channel)
			}
		}

		val builder: NotificationCompat.Builder = NotificationCompat.Builder(context, NOTIFICATION_ID)
			.setAutoCancel(true)
			.setSmallIcon(R.mipmap.ic_launcher)
			.setLargeIcon(BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher))
			.setWhen(System.currentTimeMillis())
			.setDefaults(NotificationCompat.DEFAULT_ALL)
			.setPriority(Notification.PRIORITY_MAX)
			.setContentTitle(title)
			.setContentText(content)
			.setColor(Color.TRANSPARENT)
			.setCategory(NotificationCompat.CATEGORY_MESSAGE)
			.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
		//	.addAction(R.mipmap.ic_avatar, "去看看", pendingIntent)

		if(content.length > 20) {
			builder.setStyle(NotificationCompat.BigTextStyle().bigText(content))
		}

		// 【本期】点通知直接打开对应页面(教务通知 / 成绩查询等); 未指定页面时保持原样(点击无跳转)
		if(!page.isNullOrEmpty()) {
			try {
				val intent = android.content.Intent(context, com.qust.helper.ui.activity.ComposeActivity::class.java)
					.putExtra("page", page)
					.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
				builder.setContentIntent(
					android.app.PendingIntent.getActivity(
						context, 0, intent,
						android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
					)
				)
			}catch(_: Throwable){ }
		}

		manager.notify(notificationNum, builder.build())
	}
}
