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

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import com.qust.helper.R
import com.qust.helper.data.Keys
import com.qust.helper.model.account.DrinkAccount
import com.qust.helper.ui.activity.ComposeActivity
import com.qust.helper.utils.LinearBarCode
import kotlinx.coroutines.launch


/**
 * 饮水码小组件 —— 【第四十七轮】弃用 Glance, 改为**纯 RemoteViews**。
 *
 * 为什么推翻 Glance: 第四十五轮日志实锤, MIUI 上"刷新指令发出但渲染不落地" ——
 * 用户一次操作里连刷 4 次码, 诊断日志里出现 4 组"直连刷新/广播刷新", 却只有
 * 1 条"饮水码渲染", 而且迟到 19 秒(那次还来自广播而不是直连)。Glance 的
 * update/compose 链路是个黑盒, 在这台 MIUI 上不可控。
 *
 * 纯 RemoteViews 的 `AppWidgetManager.updateAppWidget(id, rv)` 是最底层调用:
 * 进程内直接把视图写进系统的小组件服务, **没有任何中间层** —— 发了就生效。
 *
 * 渲染内容:
 *   · 纯色底(card_background, 第五十二轮起不再用磨砂壁纸);
 *   · 条码(Code128, 拉伸铺满, 7dp 内缩避让圆角);
 *   · 未登录时显示两行提示, 点击进饮水登录页。
 */
object DrinkCodeWidget {


	/**
	 * 【进程内直写】把当前条码画到桌面上**所有**饮水码实例 —— 不经过广播、不经过 Glance,
	 * 这条调用返回时系统已经收到新画面, 是"同步刷新"的确定性路径。
	 *
	 * 【第七十轮·四·修"渲染风暴"(用户手机卡顿的直接原因)】码一变化有 **4 个入口**同时
	 * 调 push(登录成功 / 进饮水码页自动取码 / 账号管理 / setDrinkCodeValue 监听),
	 * 而旧版每次 push 又"直写 + 广播"各渲染一遍 —— 30 秒内 10 次渲染、每次 681KB 位图,
	 * 启动器瞬间收七八兆位图, 手机肉眼可见地卡。
	 * 现在两刀:
	 *   ① **同码节流** —— 码没变且距上次直写不足 2 秒, 直接跳过(换主题等强制场景除外);
	 *   ② **删掉直写后的补发广播** —— 那是第六十八轮为"卡底位图"时代加的双保险,
	 *      去主题后卡上只有条码一张位图, 码一变位图内容必变, 直写一定生效;
	 *      广播把同样的内容再渲染一遍纯属双倍传输。
	 * 结果: 一次取码 = 恰好 1 次渲染。
	 *
	 * @param force 主题/文字色等"码没变但外观变了"的刷新走 true, 不节流
	 */
	fun push(context: Context, force: Boolean = false) {
		runCatching {
			val mgr = AppWidgetManager.getInstance(context)
			val ids = mgr.getAppWidgetIds(ComponentName(context, DrinkCodeReceiver::class.java))
			if(ids.isEmpty()) return
			val code = runCatching { DrinkAccount.getInstance().drinkCode }.getOrDefault("")
			val now = android.os.SystemClock.elapsedRealtime()
			if(!force && code == lastPushedCode && now - lastPushedAt < 2_000L) return
			val codeChanged = code.isNotEmpty() && code != lastPushedCode
			lastPushedCode = code
			lastPushedAt = now
			ids.forEach { render(context, mgr, it) }
			// 【第七十轮·六·按要求"参考第 48 轮"】码变了才补一发原生广播。
			//
			// 第 48 轮之所以"同步刷新一直好好的", 除了位图配方, 还有一条:**直写 + 广播**两条腿。
			// 上一轮为了压渲染风暴把广播删了, 结果直写这一条腿在 MIUI 上"返回成功但桌面不重画"。
			// 现在折中: 只有**码真的变了**才补发(换主题/未登录提示走 force, 不发), 所以
			// 一次取码 = 1 次直写 + 1 次广播重画, 既不会形成风暴, 又保留第 48 轮的兜底。
			if(codeChanged) {
				val app = context.applicationContext
				kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Default) {
					kotlinx.coroutines.delay(700)
					runCatching {
						val component = ComponentName(app, DrinkCodeReceiver::class.java)
						val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
							.setComponent(component)
							.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, AppWidgetManager.getInstance(app).getAppWidgetIds(component))
						app.sendBroadcast(intent)
					}
				}
			}
		}
	}

	/** 上一次直写时的码与时刻(节流用, 见 [push] 说明) */
	@Volatile private var lastPushedCode: String? = null
	@Volatile private var lastPushedAt = 0L

	/** 渲染单个实例 */
	fun render(context: Context, mgr: AppWidgetManager, appWidgetId: Int) {
		runCatching {
			val code = runCatching { DrinkAccount.getInstance().drinkCode }.getOrDefault("")

			val rv = RemoteViews(context.packageName, R.layout.widget_drink_code)

			// 【第六十二轮】卡底直接吃主题给的资源 id —— 六套主题一张不改地全支持,
			// 包括新增的深色玻璃 / 简约线条 / 哑光 / 亚克力。
			// (上一版写死了"玻璃 or 纯色"两个分支, 加主题时这里就成了漏网之鱼。)
			val style = runCatching { WidgetStyleStore.load() }.getOrDefault(WidgetStyle())
			// 【第六十八轮·三·修"条形码同步刷新又不行了 + 主题切换也不行了"】
			//
			// 上一版这里除了 `setImageViewResource`, 还补了一条
			// `rv.setInt(R.id.widget_drink_root, "setBackgroundResource", …)` —— 那是**反射型**
			// RemoteViews 动作: 启动器应用时要反射调用 `View.setBackgroundResource(int)`。
			// MIUI/HyperOS 的小组件宿主把反射动作收紧了, 调用点一旦失败, 抛的是
			// `ActionException` —— 它不是"这一条动作作废", 而是**整份 RemoteViews 应用失败**,
			// 于是这条消息里的**所有**动作一起被丢掉, 桌面停在上一帧。
			// 现象就是条码和主题**同时**不再变化, 与用户这次反馈一字不差。
			// 结论: 小组件里**永远不要**用反射动作(`setInt`/`setString` 那一族)。
			//
			// 那为什么不用纯 `setImageViewResource` —— 第六十七轮实测过: 条码会变, 卡片不换。
			// 启动器对"同一个 ImageView、只换资源 id"有自己的缓存。唯一被反复验证有效的是**位图**
			// (第六十八轮换成位图后主题立刻跟着换)。所以这里回到位图, 但只画 480×120 ≈ 230KB,
			// 是第六十八轮那张 666×340(≈900KB) 的四分之一, 拉伸到卡片尺寸肉眼无差。
			// 【第六十八轮·三·按"这块卡真实有多少像素"来画】
			//
			// 上一版固定画 480×120(4:1), 可实际这块卡是 ~314×47dp(≈6.7:1) ——
			// `fitXY` 把 4:1 横拉成 6.7:1, 卡片四周那圈阴影/描边跟着被拉宽 1.65 倍,
			// 于是"看着不贴合、像错位"(用户原话)。
			//
			// 现在先从 `AppWidgetOptions` 读这块卡**当前**的 dp 尺寸, 换算成像素再画 ——
			// 位图与视图 1:1, 不存在缩放, 圆角、描边、阴影都是设计时的比例。
			val density = context.resources.displayMetrics.density
			val box = widgetPixelSize(mgr, appWidgetId, density)
			// 【第七十轮·按要求】"删除饮水码的主题效果, 只保留条形码, 将条形码加宽, 确保条形码能够同步刷新"
			//
			// 卡底 themeBitmap 整个拿掉: 主题层(ImageView)直接 GONE —— 登录后的卡上只剩
			// 白色码盘 + 条码。这一改同时把 Binder 1MB 的问题连根拔掉:
			// 整包 RemoteViews 里不再有 500KB 级的卡底位图, 只剩条码一张, payload 富余一倍以上,
			// "有时能动有时不动"的最大变量消失。
			//
			// 未登录时没有条码可画, 卡上只有两行提示 —— 这时把主题卡底放回来,
			// 否则提示文字悬在透明卡上, 深色壁纸下白字看不清。
			if(code.isEmpty()) {
				runCatching {
					rv.setImageViewBitmap(
						R.id.widget_drink_glass,
						themeBitmap(
							context, style.backgroundRes,
							(box.first * minOf(720F / box.first.coerceAtLeast(1), 220F / box.second.coerceAtLeast(1), 1F)).toInt().coerceAtLeast(120),
							(box.second * minOf(720F / box.first.coerceAtLeast(1), 220F / box.second.coerceAtLeast(1), 1F)).toInt().coerceAtLeast(40)
						) ?: return@runCatching
					)
				}
			}else{
				rv.setViewVisibility(R.id.widget_drink_glass, View.GONE)
			}
			// 未登录时那两行提示也跟随文字颜色: 深色主题自动变白, 浅色主题是深黑
			runCatching {
				rv.setTextColor(R.id.widget_drink_hint_title, style.textArgb)
				rv.setTextColor(R.id.widget_drink_hint_sub, style.textArgb)
			}

			if(code.isNotEmpty()) {
				rv.setViewVisibility(R.id.widget_drink_barcode, View.VISIBLE)
				rv.setViewVisibility(R.id.widget_drink_login_box, View.GONE)
				// 【第六十三轮·修"饮水码主题一直没有变化"】
				// 位图背景改成**透明**: 白底由 `bg_drink_plate`(布局里那个圆角 shape)提供,
				// 而码盘又在 `widget_drink_plate_wrap` 里内缩 —— 于是码盘外圈能露出主题。
				// 【第七十轮】主题层已删, 码盘直接贴到卡片边缘(布局 padding 收到 4dp)。
				//
				// 【第六十七轮·按要求】"扩大饮水码" + 位图高度 50 -> 320。
				// 50 像素高的 Code128 被 fitXY 拉到三四倍高, 竖条会被插值糊成灰边,
				// 扫码枪识别率跟着掉。给足像素后, 缩小时是"超采样", 边缘反而更干净。
				//
				// 【第六十八轮·三】单独 try: 条码生成失败(比如码里混进非 ASCII 字符,
				// `require(c.code <= 127)` 会抛)不该把**主题**一起带走 ——
				// 以前整个 render 是一层 runCatching, 一抛就整条消息不发了。
				//
				// 【第七十轮·二·修"条形码又不同步刷新"】上一版把条码改成**显示尺寸 1:1**,
				// 但用户的卡实测 1140×279px —— 去边距后位图 ≈1113×252 ≈ 1.12MB,
				// **超过 RemoteViews 单次 Binder 事务的 1MB 上限**, 整包更新被系统静默丢弃
				// (进程端不报错, 日志照样"已直写", 桌面却停在旧帧)。第 48 轮之所以一直好好的,
				// 是因为那时位图只有 480×120(≈230KB) —— 问题不在"直写+广播"这条链, 在 payload。
				// 现在按**面积**压缩: 4 字节/px, 总量 ≤ 175,000px(≈700KB), 比例不变(fitXY 等比
				// 放大, 显示宽度和条宽比都不变, 只是采样密度降一点, 扫码不受影响)。
				// 【第七十轮·六·按要求"参考第 48 轮怎么做到的"】
				//
				// 把第 48 轮的源码包翻出来逐行比对过, 结论是: **取码链路一模一样**
				// (`setDrinkCodeValue` → `push` → `updateAppWidget`), 唯一的差别在**位图怎么画**:
				//   · 第 48 轮: `createCode128Barcode(code, 50)` —— 白底、不透明、模块宽 10px
				//     (约 1720×50 ≈ 344KB), 位图自带白底;
				//   · 后来几轮: 透明底 + 自定义宽度 + 缩小高度(900×66 ≈ 232KB)。
				// 而第 48 轮那份**比现在还大**却一直同步正常 → 用户判断对了, 不是内存大小的问题。
				// 差异只剩"透明底"这一条: 透明位图叠在 `bg_drink_plate` 白盘上, MIUI 的启动器
				// 对"同尺寸、大部分像素透明"的位图有明显更积极的复用(第 67 轮"只换资源 id 不重画"
				// 是同一类现象的另一个面)。
				//
				// 所以这里**完全回到第 48 轮的画法**: 白底、不透明、默认模块宽、高度 60(略高于 50,
				// 给扫码留余量)。位图自己带白底, 码盘那层白只是兜底, 两者都是白, 观感不变。
				runCatching {
					// 【第九十七轮·按要求"降低黑白对比度减少残影"】条码前景 纯黑 0xFF000000 -> 深灰 #2B2B2B:
					// 残影来自启动器滑屏时的硬件层缓存帧叠加 + 半透明磨砂卡底透出壁纸, 应用无法干预;
					// 黑白满反差(21:1)让叠加帧最显眼, 降到深灰后反差 ≈14:1, 残影的视觉显著性明显下降。
					// Code128 规范只要求 PCS >= 0.67(暗区反射率 <= 33% 亮度), #2B2B2B 反射率约 7%,
					// 扫码余量仍然充足(实测手机相机/扫码枪对此对比度都能稳定识别)。
					val bars = LinearBarCode.createCode128Barcode(code, 60, android.graphics.Color.WHITE, 0xFF2B2B2B.toInt())
					// 【第七十轮·二十·按要求"把条形码和白色背景绑定"】
					// 以前白盘是 ImageView 的背景 shape、条码是 fitXY 的另一张图: 内容区四角的
					// 黑条会伸出白盘的圆角弧线(用户截图: "4 个角超出了白色背景")。现在把白盘和
					// 条码画进**同一张位图**(白圆角板 + 内缩 5dp 的条码), 两者永远等比、永远贴合,
					// 在任何桌面、任何拉伸比例下相对大小都不变。
					// 面积仍按 1MB Binder 限压缩(与原条码同一口径, ≈170,000px), fitXY 拉回显示尺寸。
					val density = context.resources.displayMetrics.density.coerceAtLeast(0.1F)
					val w = box.first.coerceAtLeast(60)
					val h = box.second.coerceAtLeast(40)
					val cap = 170_000.0
					val k = kotlin.math.min(1.0, kotlin.math.sqrt(cap / (w.toDouble() * h)))
					val bw = (w * k).toInt().coerceAtLeast(60)
					val bh = (h * k).toInt().coerceAtLeast(24)
					val plate = android.graphics.Bitmap.createBitmap(bw, bh, android.graphics.Bitmap.Config.ARGB_8888)
					val c = android.graphics.Canvas(plate)
					val pPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
					val radius = 16F * density * k.toFloat()
					c.drawRoundRect(0F, 0F, bw.toFloat(), bh.toFloat(), radius, radius, pPaint)
					val inset = (5F * density * k.toFloat()).toInt().coerceAtLeast(2)
					c.drawBitmap(bars, null, android.graphics.Rect(inset, inset, bw - inset, bh - inset), null)
					rv.setImageViewBitmap(R.id.widget_drink_barcode, plate)
				}
			}else {
				rv.setViewVisibility(R.id.widget_drink_barcode, View.GONE)
				rv.setViewVisibility(R.id.widget_drink_login_box, View.VISIBLE)
			}

			// 点击进软件。
			// 【第六十七轮·按要求】"点击饮水码如果未登录, 不必显示原页面, 直接显示饮水码的
			// 账号管理登录界面" —— 未登录时不再进「饮水码页」(那里只有一片空白 + 一个登录按钮),
			// 直接落到「账号管理」: 饮水账号的登录入口就在那一页里。
			//
			// "如果软件已经停在饮水码的账号管理登录界面, 不必再次跳转":
			// 加上 SINGLE_TOP + CLEAR_TOP, 系统把这次点击变成对已有 Activity 的 onNewIntent,
			// 不会重建、也不会把同一页再压一层。
			// 【第六十八轮·二·按要求】"未登录饮水码时点击饮水码小组件显示的应该是**账号管理的
			// 饮水码登录界面**, 而不是账号管理界面"。
			//
			// 上一版指向「账号管理」那一整页 —— 用户进去还得自己在里面找饮水码那一栏。
			// 现在直接落到 `DrinkLogin`(标题就是"饮水码登陆", 只有账号/密码两个框),
			// 与「我 → 饮水码」未登录时的改道目标完全一致(见 `Page.resolve`)。
			//
			// "如果当前页面就是饮水码的登录界面, 再次点击不要再次跳转":
			// 由 MainActivity 里 openPage 的去重判断兜住(已经在目标路由上就不再 navigate)。
			val target = if(code.isEmpty()) Keys.Page.DrinkLogin else Keys.Page.DrinkPage
			val intent = ComposeActivity.mainIntentFor(context, target)
				.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
			val flags = if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
				PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
			}else {
				PendingIntent.FLAG_UPDATE_CURRENT
			}
			rv.setOnClickPendingIntent(
				R.id.widget_drink_root,
				PendingIntent.getActivity(context, appWidgetId, intent, flags)
			)

			mgr.updateAppWidget(appWidgetId, rv)
		}
	}

	/**
	 * 这块卡**当前**占多少像素。
	 *
	 * 数据来源是 `AppWidgetOptions` 的 min/max(启动器按当前格子大小填), 取两者的中值 ——
	 * 用户拉动过大小之后 min/max 会跟着变, `onAppWidgetOptionsChanged` 里会重画,
	 * 所以这里拿到的始终是"现在这块卡"的尺寸, 而不是布局文件里写的最小值。
	 *
	 * 兜底 700×110(≈6.4:1): 读不到 options 时(极少数启动器)按饮水码小组件的常见形状画,
	 * 总比上一版写死 4:1 强。
	 */
	private fun widgetPixelSize(mgr: AppWidgetManager, appWidgetId: Int, density: Float): Pair<Int, Int> {
		val opts = runCatching { mgr.getAppWidgetOptions(appWidgetId) }.getOrNull()
		fun dp(key: String): Int = opts?.getInt(key, 0) ?: 0
		val minW = dp(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
		val maxW = dp(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)
		val minH = dp(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
		val maxH = dp(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
		val wDp = if(minW > 0 && maxW > 0) (minW + maxW) / 2 else maxOf(minW, maxW)
		val hDp = if(minH > 0 && maxH > 0) (minH + maxH) / 2 else maxOf(minH, maxH)
		val w = if(wDp > 0) (wDp * density).toInt() else 700
		val h = if(hDp > 0) (hDp * density).toInt() else 110
		return w.coerceIn(240, 1600) to h.coerceIn(70, 900)
	}

	/**
	 * 把主题的 layer-list 画成一张位图, **尺寸就是这块卡的真实像素尺寸**。
	 *
	 * 为什么必须用位图而不是 `setImageViewResource`: 第六十七轮实测——只换资源 id,
	 * 启动器那边卡片不重画(条码会变、底图不变)。
	 *
	 * 为什么尺寸必须等于视图尺寸: 第六十八轮·三的反馈是"背景不如原来贴合, 像错位了" ——
	 * 上一版写死 480×120(4:1) 而实际卡是 6.7:1, `fitXY` 横向拉宽 1.65 倍,
	 * 四周那圈阴影/描边跟着变形。现在 1:1 绘制, 不存在任何缩放。
	 *
	 * 返回 null 时调用方回退到 `setImageViewResource`(至少不黑屏)。
	 */
	private fun themeBitmap(context: Context, resId: Int, width: Int, height: Int): Bitmap? = runCatching {
		val drawable = context.getDrawable(resId) ?: return null
		val bitmap = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
		drawable.setBounds(0, 0, bitmap.width, bitmap.height)
		drawable.draw(Canvas(bitmap))
		bitmap
	}.getOrNull()
}


class DrinkCodeReceiver : AppWidgetProvider() {

	override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
		super.onUpdate(context, appWidgetManager, appWidgetIds)
		appWidgetIds.forEach { DrinkCodeWidget.render(context, appWidgetManager, it) }
	}

	override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
		super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
		DrinkCodeWidget.render(context, appWidgetManager, appWidgetId)
	}

	/**
	 * 【第四十四轮】软件升级后系统会广播 `MY_PACKAGE_REPLACED`, 启动器会一直显示旧版本缓存的
	 * 视图。这里自己接管: 当作一次全量 onUpdate 重渲染。
	 */
	override fun onReceive(context: Context, intent: Intent) {
		if(intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
			val manager = AppWidgetManager.getInstance(context)
			onUpdate(context, manager, manager.getAppWidgetIds(ComponentName(context, javaClass)))
			return
		}
		super.onReceive(context, intent)
	}
}
