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

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * 【第四十八轮】"软件外"消息提醒(类似QQ/微信通知):
 * 收到教务通知调课等消息时, 在系统通知栏给出提醒, 点击可直接打开软件。
 * 需要通知权限(Android 13+); 未授权时静默跳过, 不打扰。
 */
object Notices {

	/**
	 * 【第三十二轮·按要求】不再单独建「教务通知」通知类别。
	 *
	 * 以前这里会 createNotificationChannel("eas_notice", "教务通知") 并写一句
	 * description「教务通知调课/补课/停课提醒」—— 它就会出现在
	 * 系统设置 → 应用 → 青科助手 → 通知 → 通知类别 里, 成为一条独立条目。
	 * 现在统一走「推送通知」这一个渠道(见 [NotificationUtils]), 老版本已经建出来的
	 * 「教务通知」类别会被主动删除, 列表里只剩「推送通知」。
	 */
	private const val CHANNEL_EAS_LEGACY = "eas_notice"

	/** 与 [NotificationUtils] 里用的是同一个渠道 */
	private const val CHANNEL_PUSH = "push"

	/** 申请通知权限(Android 13+); 已授权/低版本时无操作 */
	fun requestPermission(activity: android.app.Activity, requestCode: Int){
		try {
			if(Build.VERSION.SDK_INT >= 33 &&
				activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED){
				activity.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), requestCode)
			}
		}catch(_: Throwable){ }
	}

	/** 【第三十二轮·按要求】删掉老版本留下的「教务通知」通知类别(系统设置里的通知类别列表随之消失) */
	fun removeLegacyChannel(context: Context){
		try {
			if(Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
			val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
			if(manager.getNotificationChannel(CHANNEL_EAS_LEGACY) != null) {
				manager.deleteNotificationChannel(CHANNEL_EAS_LEGACY)
			}
		}catch(_: Throwable){ }
	}

	fun hasPermission(context: Context): Boolean {
		return Build.VERSION.SDK_INT < 33 ||
			context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
	}

	/** 在系统通知栏弹出一条消息 */
	fun notify(context: Context, id: Int, title: String, content: String){
		try {
			if(!hasPermission(context)) return
			if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O){
				val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
				// 老版本的「教务通知」类别: 删掉, 免得系统设置里还留着「教务通知调课/补课/停课提醒」这一条
				runCatching {
					if(manager.getNotificationChannel(CHANNEL_EAS_LEGACY) != null) {
						manager.deleteNotificationChannel(CHANNEL_EAS_LEGACY)
					}
				}
				if(manager.getNotificationChannel(CHANNEL_PUSH) == null){
					manager.createNotificationChannel(
						NotificationChannel(CHANNEL_PUSH, "推送通知", NotificationManager.IMPORTANCE_HIGH)
					)
				}
			}
			// 【本期】点通知直接进入「教务通知」界面(通知列表), 不再跳到「调休管理」
			val intent = android.content.Intent(context, com.qust.helper.ui.activity.ComposeActivity::class.java)
				.putExtra("page", com.qust.helper.data.Keys.Page.GetNoticePage)
				// CLEAR_TOP: 软件已在后台时不要再叠一层单页宿主; SINGLE_TOP: 已经在「教务通知」页时
				// 复用同一个实例并由 onNewIntent 重新合成内容(ComposeActivity 已处理), 不会出现旧列表。
				.addFlags(
					android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
						android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
						android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
				)
			// 【本期】requestCode 用通知 id: 每条通知各自一个 PendingIntent。
			// 以前固定 0 + FLAG_UPDATE_CURRENT, 多次下发后复用同一个 PendingIntent,
			// 点哪条通知都是同一份(可能已被系统回收/复用的)Intent, 偶发打不开目标页。
			val pending = PendingIntent.getActivity(
				context, id, intent,
				PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
			)
			val notification = NotificationCompat.Builder(context, CHANNEL_PUSH)
				.setSmallIcon(com.qust.helper.R.drawable.ic_insert_invitation)
				.setContentTitle(title)
				.setContentText(content)
				.setStyle(NotificationCompat.BigTextStyle().bigText(content))
				.setAutoCancel(true)
				.setPriority(NotificationCompat.PRIORITY_HIGH)
				.setContentIntent(pending)
				.build()
			NotificationManagerCompat.from(context).notify(id, notification)
		}catch(_: Throwable){ }
	}
}
