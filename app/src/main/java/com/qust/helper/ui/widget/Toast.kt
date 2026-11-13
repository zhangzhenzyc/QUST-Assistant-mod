/*
 * 青科助手（修改版） —— 基于开源项目 QUST-Assistant 修改而成
 *
 * 原项目版权所有 (C) 2891954521  (https://github.com/2891954521/QUST-Assistant)
 * 原始开源许可: GNU General Public License v3.0 (GPL-3.0), 许可原文见 LICENSE 文件, 保持完整未改动。
 *
 * 修改版版权所有 (C) 2026 zhangzhenzyc  (https://github.com/zhangzhenzyc)
 * 本修改版同样以 GPL-3.0 发布; 附加版权声明见 NOTICE.md —— 两份版权声明相互独立、同时生效。
 */

package com.qust.helper.ui.widget

import android.app.Activity
import android.view.Gravity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import com.qust.helper.R


class Toast(private val activity: Activity){

	/** 气泡时长基准: 拖动提示"双击蓝框可添加课程，拖动上下横线可调整时长" —— 该气泡时长保持原样(系统气泡);
	 *  【第六十二轮】其它气泡时长 = 4.5 秒 × 字数/25，限制在 [800ms, 4500ms]。 */
	private val bubbleRefText = "双击蓝框可添加课程，拖动上下横线可调整时长"
	private val bubbleRefMs = 4500L
	private fun bubbleDurationMs(message: String): Long {
		val ratio = message.length.toDouble() / 25.0
		return (bubbleRefMs * ratio).toLong().coerceIn(800L, bubbleRefMs)
	}

	/**
	 * 【第六十二轮】系统原生气泡 + 到时提前 cancel —— 外观与系统气泡完全一致, 只把显示时长按字数缩短
	 * (系统 Toast 只支持 LENGTH_SHORT/LONG, 无法直接指定毫秒, 但可以提前 cancel 来缩短)。
	 */
	private fun systemToast(message: String, durationMs: Long, center: Boolean){
		activity.runOnUiThread {
			try {
				val t = android.widget.Toast.makeText(activity, message, android.widget.Toast.LENGTH_LONG)
				if(center) t.setGravity(Gravity.CENTER, 0, 0)
				t.show()
				android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
					try { t.cancel() }catch(_: Exception){ }
				}, durationMs)
			}catch(_: Exception){ }
		}
	}

	private fun toast(icon: Int, message: String){
		@Suppress("UNUSED_EXPRESSION") icon
		// 【第六十二轮】基准气泡(拖动提示)时长保持原样不变(系统 LENGTH_LONG);
		// 其它气泡: 按字数相对基准**等比缩短**时长, 外观仍是系统原生气泡。
		if(message == bubbleRefText){
			try {
				android.widget.Toast.makeText(activity, message, android.widget.Toast.LENGTH_LONG).show()
			}catch(_: Exception){ }
			return
		}
		systemToast(message, bubbleDurationMs(message), center = false)
	}

	/** 【第五十三轮】屏幕**居中**的气泡提示(用于"暂无新的调休/调课安排"等); 【第六十二轮】时长同样按字数等比缩短 */
	fun toastCenter(message: String){
		if(message == bubbleRefText){
			try {
				val t = android.widget.Toast.makeText(activity, message, android.widget.Toast.LENGTH_LONG)
				t.setGravity(Gravity.CENTER, 0, 0)
				t.show()
			}catch(_: Exception){ }
			return
		}
		systemToast(message, bubbleDurationMs(message), center = true)
	}

	fun toastOK(message: String){ toast(R.drawable.tips_finish, message) }
	fun toastWarning(message: String) { toast(R.drawable.tips_warning, message) }
	fun toastError(message: String) { toast(R.drawable.tips_error, message) }

	companion object {
		private val cache = java.util.WeakHashMap<Activity, Toast>()

		/** 【第四十六轮】按 Activity 复用同一个 Toast 实例(供非 Compose 组件如课表拖动提示调用) */
		fun of(activity: Activity): Toast = cache.getOrPut(activity) { Toast(activity) }
	}

	@Composable
	fun ToastContent(toastContent: MutableState<ToastContent>){
		LaunchedEffect(toastContent.value){
			val v = toastContent.value
			if(v.icon != 0 && v.message.isNotEmpty()){
				toast(v.icon, v.message)
				toastContent.value = ToastContent.EMPTY_TOAST
			}
		}
	}
}

interface ToastAble {
	val toastContent: MutableState<ToastContent>
	fun toastOK(message: String)
	fun toastWarning(message: String)
	fun toastError(message: String)
}

class ToastAbleImpl(override val toastContent: MutableState<ToastContent> = mutableStateOf(ToastContent.EMPTY_TOAST)): ToastAble{
	override fun toastOK(message: String){
		toastContent.value = ToastContent(R.drawable.tips_finish, message)
	}
	override fun toastWarning(message: String) {
		toastContent.value = ToastContent(R.drawable.tips_warning, message)
	}
	override fun toastError(message: String) {
		toastContent.value = ToastContent(R.drawable.tips_error, message)
	}
}

class ToastContent(var icon: Int = 0, val message: String = ""){
	companion object {
		val EMPTY_TOAST: ToastContent = ToastContent()
	}
}
