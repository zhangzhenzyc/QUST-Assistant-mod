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

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.qust.helper.R

object Dialogs {

	@Composable
	fun AskDialog(
		title: String = "",
		content: String = "",
		onDismiss: () -> Unit = { },
		onConfirm: () -> Unit = { }
	){
		AskDialog(title, content, onDismiss, onConfirm, null, false)
	}

	/**
	 * 【第四十八轮】带"确认按钮文字/危险(红色)"的确认弹窗 —— 删除类弹窗统一样式:
	 * 确认按钮为红色"删除"。原有 AskDialog(4参) 调用完全不受影响。
	 */
	@Composable
	fun AskDialog(
		title: String,
		content: String,
		onDismiss: () -> Unit,
		onConfirm: () -> Unit,
		confirmText: String?,
		danger: Boolean
	){
		AlertDialog(
			onDismissRequest = { onDismiss() },
			title = { Text(text = title) },
			text = { Text(content) },
			confirmButton = {
				TextButton(onClick = { onConfirm() }) {
					Text(
						text = confirmText ?: stringResource(id = R.string.text_ok),
						color = if(danger) MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified
					)
				}
			},
			dismissButton = {
				TextButton(onClick = { onDismiss() }) {
					Text(stringResource(id = R.string.text_cancel))
				}
			},
			properties = DialogProperties(dismissOnClickOutside = false)
		)
	}

	@Composable
	fun InputDialog(
		title: String = "",
		content: String = "",
		keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
		onDismiss: () -> Unit = { },
		onConfirm: (String) -> Unit = { }
	){
		var input by remember { mutableStateOf(content) }
		AlertDialog(
			onDismissRequest = { onDismiss() },
			title = { Text(text = title) },
			text = {
				OutlinedTextField(
					value = input,
					onValueChange = { input = it },
					modifier = Modifier.fillMaxWidth(),
					singleLine = true,
					keyboardOptions = keyboardOptions,
					maxLines = 1
				)
			},
			confirmButton = {
				TextButton(onClick = { onConfirm(input) }) {
					Text(stringResource(id = R.string.text_ok))
				}
			},
			dismissButton = {
				TextButton(onClick = { onDismiss() }) {
					Text(stringResource(id = R.string.text_cancel))
				}
			}
		)
	}

	@Composable
	fun IndeterminateProgressDialog(
		dialogText: String = "Loading",
		onDismissRequest: () -> Unit = { }
	) {
		// 【第四十七轮】不再使用系统 Dialog 窗口(它会吞掉返回键, 导致"正在登录时退不出去"),
		// 改为页面内**非模态覆盖层**: 返回键始终可以退出当前界面(后台登录/加载任务照常继续)。
		@Suppress("UNUSED_EXPRESSION") onDismissRequest
		// 【第五十四轮】改为**相对整个屏幕(窗口)居中**的悬浮层:
		// 之前 alignment=Center 是相对"父布局"居中, 页面里有其它内容时看着偏下/偏侧;
		// 现在用自定义 PopupPositionProvider 按窗口尺寸居中。
		// 非模态: 不挤占布局、不抢返回键(加载/登录中仍可返回退出)。
		androidx.compose.ui.window.Popup(
			popupPositionProvider = object : androidx.compose.ui.window.PopupPositionProvider {
				override fun calculatePosition(
					anchorBounds: androidx.compose.ui.unit.IntRect,
					windowSize: androidx.compose.ui.unit.IntSize,
					layoutDirection: androidx.compose.ui.unit.LayoutDirection,
					popupContentSize: androidx.compose.ui.unit.IntSize
				): androidx.compose.ui.unit.IntOffset {
					return androidx.compose.ui.unit.IntOffset(
						(windowSize.width - popupContentSize.width) / 2,
						(windowSize.height - popupContentSize.height) / 2
					)
				}
			},
			properties = androidx.compose.ui.window.PopupProperties(focusable = false)
		){
		Box(
			modifier = Modifier
		){
			Card(
				modifier = Modifier.fillMaxWidth().padding(32.dp, 0.dp),
				shape = RoundedCornerShape(16.dp),
				elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
			) {

				Row(
					horizontalArrangement = Arrangement.Center,
					verticalAlignment = Alignment.CenterVertically,
					modifier = Modifier.padding(16.dp)
				) {

					CircularProgressIndicator(
						color = MaterialTheme.colorScheme.secondary,
						trackColor = MaterialTheme.colorScheme.surfaceVariant,
					)
					Text(
						text = dialogText,
						modifier = Modifier.padding(8.dp, 0.dp, 0.dp, 0.dp),
					)
				}
			}
		}
		}
	}

	@Composable
	fun ProgressDialog(
		title: String = "",
		progress: Float = 0F,
		progressText: String = "",
		onDismissRequest: () -> Unit = { }
	) {
		Dialog(onDismissRequest = { onDismissRequest() }){
			Card(
				modifier = Modifier.fillMaxWidth(),
				shape = RoundedCornerShape(16.dp),
				elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
			) {

				val animatedProgress by animateFloatAsState(
					label = "",
					targetValue = progress,
					animationSpec = ProgressIndicatorDefaults.ProgressAnimationSpec
				)

				Column(
					modifier = Modifier.fillMaxWidth().padding(16.dp)
				) {
					Text(text = title)

					Row(
						horizontalArrangement = Arrangement.Center,
						verticalAlignment = Alignment.CenterVertically,
					) {

						LinearProgressIndicator(
							progress = { animatedProgress },
							modifier = Modifier.weight(1F),
							color = MaterialTheme.colorScheme.secondary,
							trackColor = MaterialTheme.colorScheme.surfaceVariant,
						)

						Text(
							text = progressText,
							modifier = Modifier.padding(start = 8.dp),
							maxLines = 1
						)
					}
				}

			}
		}
	}

	@Composable
	fun ListDialog(
		title: String = "Choose",
		items: Array<String> = emptyArray(),
		onDismiss: () -> Unit = { },
		onItemChoose: (Array<String>, Int) -> Unit = { _, _ -> },
	) {
		Dialog(onDismissRequest = { onDismiss() }){
			Card(
				// 【第三十二轮·按要求】弹窗调宽: 左右留白 32dp -> 12dp, 列表项文字允许折两行,
				// 免得像「jwglxt.qust.edu.cn（当前使用）」这样被截成「（当前使」。
				modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
				shape = RoundedCornerShape(16.dp),
				elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
			) {
				Column(modifier = Modifier.padding(16.dp)) {
					Texts.SingleLineTextNoPadding(
						text = title,
						style = MaterialTheme.typography.titleLarge,
						modifier = Modifier.fillMaxWidth()
					)
					LazyColumn(Modifier.wrapContentSize().padding(horizontal= 16.dp)){
						items(items.size){
							Box(modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onItemChoose(items, it); onDismiss() }){
								Text(
									text = items[it],
									textAlign = TextAlign.Start,
									maxLines = 2,
									overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
									modifier = Modifier.fillMaxWidth().padding(8.dp)
								)
							}
						}
					}
					Box(modifier = Modifier.fillMaxWidth()){
						TextButton(modifier = Modifier.align(Alignment.CenterEnd), onClick = { onDismiss() }) {
							Text(text = stringResource(id = R.string.text_cancel))
						}
					}
				}
			}
		}
	}


}

interface DialogAble {
	val _dialogText: MutableState<String>
	val dialogText: String
	fun showDialog(message: String)
	fun clearDialog()
}

class DialogAbleImpl(override val _dialogText: MutableState<String> = mutableStateOf("")): DialogAble{
	override val dialogText: String
		get() = _dialogText.value

	override fun showDialog(message: String) {
		_dialogText.value = message
	}
	override fun clearDialog() {
		_dialogText.value = ""
	}
}