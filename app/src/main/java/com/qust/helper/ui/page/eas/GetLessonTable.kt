/*
 * 青科助手（修改版） —— 基于开源项目 QUST-Assistant 修改而成
 *
 * 原项目版权所有 (C) 2891954521  (https://github.com/2891954521/QUST-Assistant)
 * 原始开源许可: GNU General Public License v3.0 (GPL-3.0), 许可原文见 LICENSE 文件, 保持完整未改动。
 *
 * 修改版版权所有 (C) 2026 zhangzhenzyc  (https://github.com/zhangzhenzyc)
 * 本修改版同样以 GPL-3.0 发布; 附加版权声明见 NOTICE.md —— 两份版权声明相互独立、同时生效。
 */

package com.qust.helper.ui.page.eas

import android.annotation.SuppressLint
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.qust.helper.R
import com.qust.helper.data.lesson.TimeTables
import com.qust.helper.data.Data
import com.qust.helper.data.Keys
import com.qust.helper.data.Page
import com.qust.helper.model.LessonTableRepository
import com.qust.helper.ui.theme.colorSecondaryText
import com.qust.helper.ui.widget.AppWidgets
import com.qust.helper.ui.widget.LessonRender
import com.qust.helper.ui.widget.LessonTableView
import com.qust.helper.ui.widget.ListPicker
import com.qust.helper.ui.widget.Texts
import com.qust.helper.ui.widget.Texts.SingleLineText
import com.qust.helper.ui.widget.Toast
import com.qust.helper.viewmodel.eas.GetLessonTableViewModel

object GetLessonTable {

	val GetLessonPage = Page(Keys.Page.GetLessonPage, "课表查询", iconRes = R.drawable.ic_lesson_search) { activity, padding, navController, _ ->
		val viewModel by activity.viewModels<GetLessonTableViewModel>()
		GetLessonTable(padding, viewModel, activity.toast, navController)
	}

	@Composable
	fun GetLessonTable(padding: PaddingValues, viewModel: GetLessonTableViewModel, toast: Toast, navController: NavController){
		AppWidgets.CheckEasLogin(viewModel = viewModel, navController = navController)

		Box(modifier = Modifier.padding(padding)){
			GetLessonTableUI(
				termText = viewModel.termText,
				termTimeText = viewModel.termTimeText,
				pickYear = viewModel.pickYear.value,
				needSave = viewModel.needSave,
				lessonRender = viewModel.lessonRender,
				// 【第二十八轮】用户手动选过学期后不再按"当前学期"自动重算
				onYearPick = { viewModel.pickYear.value = it; viewModel.pickYearUserSet = true },
				doQuery = { viewModel.queryLesson() },
				// 保存时必须带上对话框里的两个勾选: 以前没有传参, 导致"保留被修改过的课程"
				// 勾不勾选都一样(课表还是显示改过的课程)
				saveLessonTable = { keepUserLesson, updateStartTime, saveAdjustments ->
					viewModel.saveLessonTable(keepUserLesson, updateStartTime, saveAdjustments)
				},
				// 【第三十五轮】恢复默认课表: 调休前/后 × 调课前/后 四个组合
				restoreDefault = { keepAdjustments, keepEasChanges -> viewModel.restoreDefaultLessonTable(keepAdjustments, keepEasChanges) }
			)
		}
		AppWidgets.DialogBar(dialogText = viewModel.dialogText)
		toast.ToastContent(viewModel.toastContent)
	}

	@Composable
	@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
	fun GetLessonTableUI(
		termText: String = "",
		termTimeText: String = "",
		pickYear: Int = 0,
		needSave: Boolean = false,
		lessonRender: LessonRender,
		onYearPick: (Int) -> Unit = { },
		doQuery: () -> Unit = { },
		saveLessonTable: (keepUserLesson: Boolean, updateStartTime: Boolean, saveAdjustments: Boolean) -> Unit = { _, _, _ -> },
		restoreDefault: (keepAdjustments: Boolean, keepEasChanges: Boolean) -> Unit = { _, _ -> }
	){
		var askForSave by remember { mutableStateOf(false) }
		var askRestoreDefault by remember { mutableStateOf(false) }

	Scaffold(
			floatingActionButton = {
				if(needSave){
					FloatingActionButton(onClick = { askForSave = true }) {
						Icon(Icons.Filled.Done, contentDescription = "")
					}
				}
			}
		) { _ ->

			// 【第五十三轮】按要求恢复"整页上下滑动": 页面整体滚动, 课表按节数取固定高度、
			// 不再在内部滚动; 课表内部画布手势关闭(避免与整页滚动/左右翻周互相误触),
			// 左右翻周仍由课表分页器处理, 上下滑=整页滑动(方向区分清晰)。
			Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
				Row(
					modifier = Modifier.fillMaxWidth(),
					horizontalArrangement = Arrangement.Center,
					verticalAlignment = Alignment.CenterVertically
				) {

					Text(text = stringResource(id = R.string.text_term))

					Spacer(Modifier.width(4.dp))

					ListPicker.NumberPicker(
						value = pickYear,
						range = Data.TermName.indices,
						label = { Data.TermName[it] },
						onValueChange = { onYearPick(it) },
						horizontalPadding = 8.dp
					)

					Spacer(Modifier.width(4.dp))

					// 【班级课表功能已删除】各部署返回数据的差异太大, 该功能不稳定, 不再提供

					// 【第二十七轮】"查询"按钮保持普通点击(日志按钮已按要求改到"登录确定"按钮上)
					Button(modifier = Modifier.wrapContentSize().padding(8.dp), onClick = { doQuery() }) {
						Text(text = stringResource(id = R.string.text_query), maxLines = 1)
					}
				}

				SingleLineText(
					text = termText,
					color = colorSecondaryText,
					style = MaterialTheme.typography.bodySmall,
					modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
				)

				// 一键恢复默认课表: 回到最初查询导入的课表, 可选"调休前"或"调休后"。
				// 【课表大小与学期课表一致】这里只保留一个按钮, 把原来占两行的说明文字删掉
				// (调休前/调休后的含义弹窗里已经写得很清楚), 让课表本体尽可能高。
				Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
					TextButton(onClick = { askRestoreDefault = true }) {
						Text(text = "恢复默认课表", maxLines = 1)
					}
				}

				// 【第五十三轮】课表固定高度(按节数), 上下滑动交给整页滚动; 左右翻周照常
				Box(
					modifier = Modifier
						.fillMaxWidth()
						.height((TimeTables.currentSlotCount * 58).dp)
				) {
					LessonTableView.LessonView(lessonRender, longMode = false, previewMode = true, weekSwipeThreshold = 0.1f, strictWeekSwipe = true)
				}
				Spacer(modifier = Modifier.height(24.dp))
			}

			if(askForSave) {
				AskForSaveDialog(
					termTimeText = termTimeText,
					onDismiss = { askForSave = false },
					onConfirm = { keepUserLesson, updateStartTime, saveAdjustments ->
						askForSave = false
						saveLessonTable(keepUserLesson, updateStartTime, saveAdjustments)
					}
				)
			}

			// 一键恢复默认课表: 调休前 / 调休后 两种状态任选
			if(askRestoreDefault) {
				AlertDialog(
					onDismissRequest = { askRestoreDefault = false },
					title = { Text(text = "恢复默认课表") },
					text = {
						Column {
							Text(
								text = "请选择要恢复的状态：",
								style = MaterialTheme.typography.bodySmall,
								color = colorSecondaryText,
								modifier = Modifier.padding(bottom = 8.dp)
							)
							// 【没有"调休后"】以前把"调休前/调休后"两个按钮塞进 confirmButton 的 Row,
							// 【第四十轮】按要求从四个组合改回两个功能: 最新原始课表 / 最新调休课表
							TextButton(
								onClick = {
									askRestoreDefault = false
									// 最新原始课表: 课程与全部调休、教务调课都回到最近一次查询的结果
									restoreDefault(false, false)
								},
								modifier = Modifier.fillMaxWidth()
							) {
								Column(modifier = Modifier.fillMaxWidth()) {
									Text(text = "最新原始课表", style = MaterialTheme.typography.bodyLarge)
									Text(
										text = "课程回到最新课表，并且清除所有调休放假安排与修改过的课程",
										style = MaterialTheme.typography.bodySmall,
										color = colorSecondaryText
									)
								}
							}
							TextButton(
								onClick = {
									askRestoreDefault = false
									// 调休课表: 课程回到刚导入时, 保留现在的调休安排与教务调课(不保留用户手动修改的课程)
									restoreDefault(true, true)
								},
								modifier = Modifier.fillMaxWidth()
							) {
								Column(modifier = Modifier.fillMaxWidth()) {
									Text(text = "最新调休课表", style = MaterialTheme.typography.bodyLarge)
									Text(
										text = "课程回到最新课表，并且保留现在的调休放假安排，清除非调休安排日期的修改过的课程",
										style = MaterialTheme.typography.bodySmall,
										color = colorSecondaryText
									)
								}
							}
							if(!LessonTableRepository.hasOriginalLessonTable()) {
								Text(
									text = "提示：还没有可恢复的默认课表，请先在上方查询课表。",
									style = MaterialTheme.typography.bodySmall,
									color = MaterialTheme.colorScheme.error,
									modifier = Modifier.padding(top = 8.dp)
								)
							}
						}
					},
					confirmButton = { },
					dismissButton = {
						TextButton(onClick = { askRestoreDefault = false }) { Text(text = "取消") }
					}
				)
			}
		}
	}

	@Composable
	@OptIn(ExperimentalMaterial3Api::class)
	fun AskForSaveDialog(
		termTimeText: String = "",
		onDismiss: () -> Unit = { },
		onConfirm: (keepUserLesson: Boolean, updateStartTime: Boolean, saveAdjustments: Boolean) -> Unit = { _, _, _ -> }
	){
		var updateStartTime by remember { mutableStateOf(true) }
		var keepUserLesson by remember { mutableStateOf(true) }
		var saveAdjustments by remember { mutableStateOf(true) }

		ModalBottomSheet(onDismissRequest = { onDismiss() }) {
			Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
				Texts.SingleLineTextNoPadding(
					text = stringResource(id = R.string.text_save_lesson_table),
					style = MaterialTheme.typography.titleLarge,
					modifier = Modifier.fillMaxWidth()
				)

				Row(
					modifier = Modifier.fillMaxWidth(),
					verticalAlignment = Alignment.CenterVertically,
				) {
					Checkbox(checked = updateStartTime, onCheckedChange = { updateStartTime = it },)
					SingleLineText(text = "更新开学时间")
				}

				Text(
					text = termTimeText,
					style = MaterialTheme.typography.bodySmall,
					color = colorSecondaryText,
					modifier = Modifier.padding(start = 16.dp)
				)

				Row(
					modifier = Modifier.fillMaxWidth(),
					verticalAlignment = Alignment.CenterVertically,
				) {
					Checkbox(checked = keepUserLesson && saveAdjustments, onCheckedChange = { keepUserLesson = it; saveAdjustments = it })
					SingleLineText(text = "保存用户修改的课程与调休安排")
				}

				Text(
					text = "保留用户修改过的课程（删除不视为修改），放假与调休安排一并保存",
					style = MaterialTheme.typography.bodySmall,
					color = colorSecondaryText,
					modifier = Modifier.padding(start = 16.dp, end = 16.dp)
				)


				Row(modifier = Modifier.fillMaxWidth()) {
					TextButton(onClick = { onDismiss() }, modifier = Modifier.weight(1F)) {
						SingleLineText(text = stringResource(id = R.string.text_cancel), color = MaterialTheme.colorScheme.error)
					}
					TextButton(onClick = { onConfirm(keepUserLesson, updateStartTime, saveAdjustments) }, modifier = Modifier.weight(1F)) {
						SingleLineText(text = stringResource(id = R.string.text_ok))
					}
				}
				
				Spacer(modifier = Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
			}
		}
	}
}