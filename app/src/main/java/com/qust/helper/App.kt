/*
 * 青科助手（修改版） —— 基于开源项目 QUST-Assistant 修改而成
 *
 * 原项目版权所有 (C) 2891954521  (https://github.com/2891954521/QUST-Assistant)
 * 原始开源许可: GNU General Public License v3.0 (GPL-3.0), 许可原文见 LICENSE 文件, 保持完整未改动。
 *
 * 修改版版权所有 (C) 2026 zhangzhenzyc  (https://github.com/zhangzhenzyc)
 * 本修改版同样以 GPL-3.0 发布; 附加版权声明见 NOTICE.md —— 两份版权声明相互独立、同时生效。
 */

package com.qust.helper

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.qust.helper.data.Setting
import com.qust.helper.model.Logger
import com.qust.helper.utils.UmengUtils


class App: Application() {

	companion object {
		/** 【第四十二轮】全局上下文: 供没有 Context 的底层模型(如 DrinkAccount 同步刷新饮水码小组件)使用 */
		@Volatile
		lateinit var instance: App
			private set
	}

	override fun onCreate() {
		super.onCreate()
		instance = this

		Setting.init(this)
		Logger.init(this)

		// 【第三十五轮·修"桌面小组件翻周/缩放延迟很大"】
		// 桌面点一下小组件的箭头, 如果本进程已经被系统回收, Android 会先跑完 Application.onCreate()
		// 才轮到那次点击 —— 也就是说 **onCreate 里做的每一件重活都算在"翻周延迟"上**。
		// 下面这两件都不影响任何界面的首帧, 没必要挡在冷启动路径上:
		//
		//  · 账号迁移: 只在升级后的第一次启动真正干活(读几个 SharedPreferences 值 + 可能写一次),
		//    放后台线程; 它内部已经 try/catch 全包, 失败也不会影响使用。
		//  · 友盟: `UMConfigure.init` 要读设备信息、拉起统计线程, 是这一串里最慢的一步。
		//    延后 1.2 秒扔到主线程 —— 那时冷启动的第一帧早画完了, 统计照常上报, 只是晚一点点。
		Thread({
			try {
				com.qust.helper.model.account.AccountMigration.run()
			}catch(_: Throwable) {
			}
			// 【第六十八轮·三】时间表"补尾巴"(见 TimeTables.repairOnStart 的说明):
			// 放在这里是为了**装完新包打开一次软件, 桌面上的课表小组件就跟着变** ——
			// 否则数据修好了、桌面还停在旧版本画的那一帧, 用户只能靠"删掉小组件重加"。
			// 只在真的补过东西时才触发刷新, 平时是纯内存比较, 没有副作用。
			try {
				com.qust.helper.data.lesson.TimeTables.repairOnStart()
			}catch(_: Throwable) {
			}
		}, "account-migration").apply { isDaemon = true }.start()

		if(!BuildConfig.DEBUG) {
			Handler(Looper.getMainLooper()).postDelayed({
				try {
					UmengUtils.init(this)
				}catch(_: Throwable) {
				}
			}, 1_200L)
		}

		Thread.setDefaultUncaughtExceptionHandler(ExceptionHandler(this, Thread.getDefaultUncaughtExceptionHandler()))
	}

	fun toast(message: String) {
		object : Thread() {
			override fun run() {
				Looper.prepare()
				Toast.makeText(this@App, message, Toast.LENGTH_LONG).show()
				Looper.loop()
			}
		}.start()
	}
}

private class ExceptionHandler(val app: App, val handler: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
	override fun uncaughtException(thread: Thread, throwable: Throwable) {
		app.toast("应用发生错误，错误类型：" + throwable.javaClass)
		// 【第三十七轮】把完整堆栈写入外部私有目录, 便于远程定位崩溃:
		// 文件管理 → Android/data/com.qust.helpe/files/crash_log.txt
		try {
			val dir = app.getExternalFilesDir(null)
			if(dir != null){
				val file = java.io.File(dir, "crash_log.txt")
				val time = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA).format(java.util.Date())
				file.appendText("\n==== $time ====\n" + android.util.Log.getStackTraceString(throwable) + "\n")
			}
		}catch(_: Throwable){ }
		handler?.uncaughtException(thread, throwable)
	}
}