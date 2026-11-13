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
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit


/**
 * 自动查询工具类
 */
object AutoQueryRepository {

	/**
	 * 开始自动查询
	 */
	fun startAutoQuery(context: Context) {
		WorkManager.getInstance(context).enqueueUniquePeriodicWork(
			"AutoQuery",
			// 【第八十九轮】1 小时 + 15 分钟太慢(用户反馈"软件外的消息提醒很不及时"):
			// 改 30 分钟周期 + 5 分钟弹性。KEEP 不会更新已排队的同名额任务, 必须 UPDATE 才生效。
			ExistingPeriodicWorkPolicy.UPDATE,
			PeriodicWorkRequestBuilder<AutoQueryWorker>(30, TimeUnit.MINUTES, 5, TimeUnit.MINUTES)
				.setConstraints(
					Constraints.Builder()
//					.setRequiredNetworkType(NetworkType.UNMETERED)
					.setRequiresBatteryNotLow(true)
					.build()
			).build()
		)
	}
}
