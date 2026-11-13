/*
 * 青科助手（修改版） —— 基于开源项目 QUST-Assistant 修改而成
 *
 * 原项目版权所有 (C) 2891954521  (https://github.com/2891954521/QUST-Assistant)
 * 原始开源许可: GNU General Public License v3.0 (GPL-3.0), 许可原文见 LICENSE 文件, 保持完整未改动。
 *
 * 修改版版权所有 (C) 2026 zhangzhenzyc  (https://github.com/zhangzhenzyc)
 * 本修改版同样以 GPL-3.0 发布; 附加版权声明见 NOTICE.md —— 两份版权声明相互独立、同时生效。
 */

package com.qust.helper.ui.appwidget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch


/**
 * 【第五十九轮】小组件的"定时自刷新"心跳。
 *
 * ────────────────────────────────────────────────────────────────────────
 * 要解决的问题
 * ────────────────────────────────────────────────────────────────────────
 * 用户原话:"显示的'4分钟后下课'随着时间也没有变化"。
 *
 * 根因很直接: 那句话是在 `provideGlance` 里按"渲染那一刻"算出来的**静态文本**,
 * 而 `provideGlance` 只在"课表变了 / 桌面重排 / 系统周期更新"时才会被调。
 * 系统的周期更新最短也是 30 分钟(`updatePeriodMillis` 的下限), 一节课才 45 分钟 ——
 * 于是"4 分钟后下课"会在桌面上挂到这节课结束才变, 看着就像卡死了。
 *
 * Glance 也没有"每秒自己重绘"的能力(RemoteViews 是**一次性快照**, 不像 View 有 invalidate)。
 * 想要它变, 只能**再渲染一次**, 而渲染只能由一次广播/闹钟触发。
 *
 * 所以这里用 `AlarmManager` 精确排一次"下一次该重画的时刻":
 *  · 正在上课 -> 下一个整分(倒计时每分钟减 1, 这就是用户要的"跟着时间变");
 *  · 还没上课 -> 下一节课的**开始时刻**(那时状态要从"未开始"翻成"还有 N 分钟下课");
 *  · 今天没课/都上完了 -> 明天 00:05(跨天要把"第几周 / 几月几号"翻过去)。
 *
 * 为什么不是"每分钟无条件刷一次": 那样一整天要醒 1440 次, 待机耗电肉眼可见。
 * 只在"有课的时间段"里每分钟醒一次(一天最多几十分钟), 其余时间一天只醒一次。
 *
 * ────────────────────────────────────────────────────────────────────────
 * 几个必须注意的点
 * ────────────────────────────────────────────────────────────────────────
 *  1. **一定要 `goAsync()`**: `onReceive` 返回后进程随时可能被回收, 而 `updateAll` 是异步的;
 *     不持有 PendingResult 的话, 闹钟"响了但什么都没发生"。
	 *  2. **精确闹钟权限**: Android 12+ 需要 `SCHEDULE_EXACT_ALARM`。没授权时 `setExact*` 会抛
	 *     `SecurityException`, 所以先 `canScheduleExactAlarms()` 判断, 不行就退 `setAndAllowWhileIdle`
	 *     (不精确, 但"晚几分钟刷新"对倒计时来说完全可以接受 —— 总比崩了不刷强)。
	 *  3. **同一个 PendingIntent 只保留一个**: 每次 `schedule` 先 `cancel` 再 `set`,
	 *     否则反复渲染会堆出一串闹钟, 越跑越醒。
	 *  4. 组件被全部删掉后, 没有 `provideGlance` 再去排下一次闹钟, 链条**自然终止** —— 不会空转。
	 *
	 * ────────────────────────────────────────────────────────────────────────
	 * 【第七十轮·修"'10分钟后下课'还是不会刷新"】setExactAndAllowWhileIdle -> **setAlarmClock**
	 * ────────────────────────────────────────────────────────────────────────
	 * 第六十七轮的 finally 兜底修的是"链条断掉", 但用户实测倒计时还是长时间不动 ——
	 * 这一次的根因在**闹钟本身**: MIUI/HyperOS 的省电策略会把"普通精确闹钟"
	 * (`setExactAndAllowWhileIdle`) 在 Doze/省电窗口里**批量合并延迟**, 一次延迟就是几分钟,
	 * 于是"下一整分"的点火被推到好几分钟后, 倒计时看起来就是冻住的。
	 *
	 * `setAlarmClock`(用户显式闹钟 API)不受这些限制:
	 *   · Doze/省电白名单逻辑对它一律放行, 到点必响;
	 *   · **不需要** SCHEDULE_EXACT_ALARM 权限(Android 12+ 也可用);
	 *   · 代价: 系统状态栏会短暂显示一个闹钟小图标, 以及 ROM 可能在"下次闹钟"提示里提到它。
	 * 对"倒计时每分钟要走"这个需求来说, 这点代价完全值得。
	 * 无法建立时(极端 ROM)退回原来的 setExactAndAllowWhileIdle 链路。
	 */
	object WidgetTicker {

	/** 闹钟的 action(只在本进程/本 App 内使用, 不需要导出) */
	const val ACTION_TICK = "com.qust.helper.action.WIDGET_TICK"

	/** requestCode: 与饮水码等其它闹钟区分开 */
	private const val REQUEST_CODE = 41

	/** 上一次排的时刻(毫秒): 目标没变就不重复取消/重设, 少一次 binder 与一次唤醒 */
	@Volatile
	private var scheduledAt = Long.MIN_VALUE

	/**
	 * 排下一次刷新。
	 *
	 * @param atMillis 目标时刻(系统时钟毫秒)。早于"现在+1秒"的会被抬到现在+1秒,
	 *                 免得因为调用方算早了几毫秒而被系统立刻触发成死循环。
	 */
	fun schedule(context: Context, atMillis: Long) {
		val at = atMillis.coerceAtLeast(System.currentTimeMillis() + 1_000L)
		if(at == scheduledAt) return
		val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
		val pi = pendingIntent(context, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
		runCatching { am.cancel(pi) }
		runCatching {
			val exact = if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.canScheduleExactAlarms() else true
			when {
				// 【第七十轮】优先走"用户闹钟"通道: MIUI 不会把它合并延迟, 倒计时才真正每分钟走。
				// showIntent 传 null(允许): 上一版传 broadcast PendingIntent 在这台 MIUI 上
				// 触发了异常直接落回普通通道(日志"普通通道"), 倒计时照样被批量延迟。
				Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
					val info = AlarmManager.AlarmClockInfo(at, null)
					am.setAlarmClock(info, pi)
				}
				exact ->
					am.setExact(AlarmManager.RTC_WAKEUP, at, pi)
				else ->
					am.set(AlarmManager.RTC_WAKEUP, at, pi)
			}
			scheduledAt = at
			}.onFailure {
				// setAlarmClock 也失败(极少数 ROM): 退回普通精确闹钟, 至少链条不断。
				runCatching {
				val pi2 = pendingIntent(context, PendingIntent.FLAG_UPDATE_CURRENT) ?: throw it
				val am2 = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: throw it
				val exact = if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am2.canScheduleExactAlarms() else true
				when {
					exact && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
						am2.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi2)
					exact ->
						am2.setExact(AlarmManager.RTC_WAKEUP, at, pi2)
					Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
						am2.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi2)
					else ->
						am2.set(AlarmManager.RTC_WAKEUP, at, pi2)
				}
				scheduledAt = at
			}
		}
	}

	/** 取回同一个 PendingIntent(用 requestCode + action 唯一确定) */
	private fun pendingIntent(context: Context, flags: Int): PendingIntent? = runCatching {
		val intent = Intent(context, LessonTickReceiver::class.java).setAction(ACTION_TICK)
		PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags or PendingIntent.FLAG_IMMUTABLE)
	}.getOrNull()
}


/**
 * 心跳闹钟的接收者: 醒一次 -> 重画一次「今日课表」-> 由新的渲染再排下一次。
 *
 * 只刷「今日课表」: 「本周课表」上没有任何随时间变化的文字(第 N 周 / 日期 / 课程),
 * 跟着一起重画纯属浪费(那是 7×12 个格子的完整 RemoteViews 重建)。
 */
class LessonTickReceiver : BroadcastReceiver() {

	override fun onReceive(context: Context, intent: Intent) {
		// 新实例: 每次都从头来, 不存在"上次的协程还在跑"的问题
		val app = context.applicationContext
		val pending = goAsync()
		CoroutineScope(Dispatchers.Default).launch {
			try {
				// 【第六十五轮·终】内容 lambda 依赖 refreshTick, 先 +1 才能保证 Recomposer 真的重跑
				DailyLesson.refreshTick.value++
				DailyLesson.updateAll(app)
				// 【第七十轮·二】每周课表**每次心跳都跟着刷**(以前只有开机/解锁才刷):
				// MIUI 修改桌面布局(拉大缩小/换分页)时对第三方**不发任何回调也不发 update**,
				// 卡片停在旧尺寸的旧帧上(用户 21:22 截图: 卡片变矮了、内容还按旧高度排,
				// 底部整行被裁)。心跳在上课时段每分钟都活着 —— 顺带刷一次 TermLesson,
				// 改布局后最多 1 分钟就按新的 LocalSize 重排。内容没变时 MIUI 会自己做
				// RemoteViews diff, 多刷的代价很小。
				TermLesson.refreshTick.value++
				TermLesson.updateAll(app)
			}catch(_: Throwable) {
				// 刷新失败也不能让 receiver 抛出去(系统会记一次广播异常)
			}finally {
				// 【本轮·修"'17 分钟后下课'长时间不动"】闹钟链原来的续法是"渲染成功后
				// 由 provideGlance 里的 scheduleNextTick 排下一次" —— 只要某次 updateAll
				// 失败(MIUI 偶发拒绝应用 RemoteViews / 组合异常), 下一次闹钟就**没人排了**,
				// 链条当场断掉, 倒计时冻在最后一次渲染的文字上, 直到下次解锁/打开软件。
				// 这里在收尾处兜底: 只要"现在正处在某节课的上课时段", 就把下一个整分补排上。
				// 渲染成功时 provideGlance 会排同一时刻, WidgetTicker 的 scheduledAt 去重
				// 让这次补排成为空操作 —— 两条路谁先谁后、谁成谁败, 链条都不断。
				runCatching {
					val times = com.qust.helper.data.lesson.TimeTables.currentTimes
					val start = times.getOrNull(0)
					val end = times.getOrNull(1)
					if(start != null && end != null && start.isNotEmpty() && start.size == end.size) {
						val now = java.util.Calendar.getInstance()
						val nowMin = now[java.util.Calendar.HOUR_OF_DAY] * 60 + now[java.util.Calendar.MINUTE]
						val ongoing = start.indices.any { i ->
							val s = com.qust.helper.data.lesson.TimeTables.parseTime(start[i])
							val e = com.qust.helper.data.lesson.TimeTables.parseTime(end[i])
							s >= 0 && e >= 0 && nowMin >= s && nowMin < e
						}
						if(ongoing) {
							WidgetTicker.schedule(app, (now.timeInMillis / 60_000L + 1L) * 60_000L)
						}
					}
				}
				runCatching { pending.finish() }
			}
		}
	}
}
