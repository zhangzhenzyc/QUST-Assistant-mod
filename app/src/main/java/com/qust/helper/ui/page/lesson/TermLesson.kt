/*
 * 青科助手（修改版） —— 基于开源项目 QUST-Assistant 修改而成
 *
 * 原项目版权所有 (C) 2891954521  (https://github.com/2891954521/QUST-Assistant)
 * 原始开源许可: GNU General Public License v3.0 (GPL-3.0), 许可原文见 LICENSE 文件, 保持完整未改动。
 *
 * 修改版版权所有 (C) 2026 zhangzhenzyc  (https://github.com/zhangzhenzyc)
 * 本修改版同样以 GPL-3.0 发布; 附加版权声明见 NOTICE.md —— 两份版权声明相互独立、同时生效。
 */

package com.qust.helper.ui.page.lesson

import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.DialogProperties
import com.qust.helper.ui.theme.tapRipple
import com.qust.helper.R
import com.qust.helper.data.Keys
import com.qust.helper.data.Page
import com.qust.helper.data.lesson.Lesson
import com.qust.helper.data.lesson.LessonClipboard
import com.qust.helper.data.lesson.TimeTables
import com.qust.helper.model.LessonTableRepository
import com.qust.helper.ui.activity.ComposeActivity
import com.qust.helper.ui.theme.LESSON_BACKGROUND_COLORS
import com.qust.helper.ui.theme.TEXT_ACTION_H_PADDING
import com.qust.helper.ui.theme.TEXT_ACTION_V_PADDING
import com.qust.helper.ui.theme.textActionFilledColors
import com.qust.helper.ui.theme.textActionFilledGrayColors
import com.qust.helper.ui.widget.Dialogs
import com.qust.helper.ui.widget.LessonRender
import com.qust.helper.ui.widget.LessonTableView.LessonView
import com.qust.helper.ui.widget.Picker
import com.qust.helper.ui.widget.Toast
import com.qust.helper.viewmodel.TermLessonUIEvent
import com.qust.helper.viewmodel.TermLessonUIState
import com.qust.helper.viewmodel.TermLessonViewModel

/**
 * 【第五十五轮·按要求】"单独编辑此次课程 / 多选编辑"不可用时的气泡提示
 *
 * 用户原话: "该页面的气泡弹窗改为'仅编辑课程名称/教师/教室/上课节数/课程颜色后方可点击'"。
 */
private const val NO_CONTENT_CHANGE_TIP = "仅编辑课程名称/教师/教室/上课节数/课程颜色后方可点击"

object TermLesson {

	val TermLessonPage = Page(Keys.Page.TermLessonPage, "学期课表", iconRes = R.drawable.ic_grid_view) { activity, padding, _, _ ->
		// 每次进入课表页都重新计算当前周次, 确保显示当周课表
		LessonTableRepository.updateDate()
		val viewModel by activity.viewModels<TermLessonViewModel>()
		TermLessonUI(padding = padding, uiState = viewModel.uiState, uiEvent = viewModel.uiEvent, toast = activity.toast,
			onDayClick = { week, day -> ComposeActivity.startActivity(activity, Keys.Page.DayEditPage, day, week) })
	}

	@Composable
	fun TermLessonUI(padding: PaddingValues, uiState: TermLessonUIState, uiEvent: TermLessonUIEvent, toast: Toast, onDayClick: (Int, Int) -> Unit = { _, _ -> }){
		var showPicker by remember { mutableStateOf(false) }
		// 【修改版】选择器复用同一个渲染对象: 每次打开不再重建, 消除打开卡顿
		val pickRender = remember { LessonRender() }
		// 粘贴位置重合时的覆盖确认: (待粘贴的课程, 将被覆盖的课程)
		var pasteOverlap by remember { mutableStateOf<Pair<Lesson, Lesson>?>(null) }
		// 【本轮】从课表选择课程填充时, 目标位置重合的覆盖确认: (待填充的课程, 将被覆盖的课程)
		var pickOverlap by remember { mutableStateOf<Pair<Lesson, Lesson>?>(null) }

		Box(modifier = Modifier.padding(padding)){
			// 长按不再弹出"编辑/复制/粘贴/删除"菜单: 长按用于拖动课程(移动/调整蓝框),
			// 长按未移动则等同点击直接进入编辑 —— 菜单已取消, 避免长按时而没反应。
			LessonView(uiState.lessonRender, { uiEvent.clickLesson(it) }, onDayClick, weekPickerEnabled = true)
		}

		toast.ToastContent(uiState.toastContent)

		EditLessonSheet(
			uiState = uiState,
			uiEvent = uiEvent,
			onDismissed = { uiEvent.hideEdit() },
			visible = uiState.isShowEdit,
			onPickFromTable = { showPicker = true },
			onCopyLesson = {
				if(uiState.name.isEmpty()){
					toast.toastWarning("空白课程不可复制")
				}else{
					LessonClipboard.copy(currentFormLesson(uiState))
					toast.toastOK("已复制课程")
				}
			},
			onPasteLesson = {
				val pasted = LessonClipboard.paste()
				if(pasted == null){
					toast.toastWarning("剪贴板暂无课程")
				}else{
					// 【修改版】目标位置已有课程 -> 先弹覆盖确认(与拖动覆盖同一个弹窗样式)
					val conflict = pasteConflictAt(uiState, pasted)
				if(conflict == null){
					fillForm(uiState, pasted)
					// 【本轮】粘贴只填表单, **点右上角"完成"才写入课表**(没点完成直接退出不会保存)
					uiState.pasteFillMode = true
					uiState.pickFillMode = false
					toast.toastOK("已粘贴课程")
				}else{
					pasteOverlap = pasted to conflict
				}
				}
			},
			onDeleteLesson = {
				uiEvent.deleteLessonAtWeek()
			},
			// 【第三十五轮】"单独编辑此次课程"与"删除全部课程"(仅学期课表编辑提供)
			// 【本轮】粘贴填充状态下点它 = 把粘贴内容按"覆盖该时段"写入(与点"完成"同口径)
			onSaveThisWeek = {
				if(uiState.pasteFillMode) uiEvent.pasteLessonNow(currentFormLesson(uiState), null)
				else uiEvent.saveLessonThisWeekOnly()
			},
			onDeleteAll = { uiEvent.deleteLessonAllWeeks() },
			// 【本轮】"多选编辑": 勾选的周用当前表单内容拆成独立课程, 原课程保留其余周次
			onMultiEdit = { weeks -> uiEvent.saveLessonMultiWeeks(weeks) }
		)

		// 【修改版】粘贴位置重合 -> 覆盖确认(与拖动"课程重叠"弹窗同一形式)
		pasteOverlap?.let { pair ->
			val pasted = pair.first
			val conflict = pair.second
			Dialogs.AskDialog(
				title = "课程重叠",
				content = "该时间段已有课程，会造成课程重叠，覆盖会导致已有课程删除，是否确认覆盖？\n\n目标位置：" +
					pastePositionText(uiState, pasted.len) + "（已有「${conflict.name}」）",
			onDismiss = { pasteOverlap = null },   // 取消: 什么都不做
			onConfirm = {
				pasteOverlap = null
				// 【本轮】确认覆盖 -> 只填充表单(点"完成"才真正覆盖写入)
				fillForm(uiState, pasted)
				uiState.pasteFillMode = true
				uiState.pickFillMode = false
				toast.toastOK("已粘贴课程")
			}
			)
		}

		// 【本轮】"从课表选择课程填充"位置重合 -> 覆盖确认(与"粘贴课程"完全同一形式与文案)
		pickOverlap?.let { pair ->
			val picked = pair.first
			val conflict = pair.second
			Dialogs.AskDialog(
				title = "课程重叠",
				// 【第九十五轮】节数以被编辑课程为准, 目标区间按被编辑课程的节数展示
				content = "该时间段已有课程，会造成课程重叠，覆盖会导致已有课程删除，是否确认覆盖？\n\n目标位置：" +
					pastePositionText(uiState, uiState.len.coerceAtLeast(1)) + "（已有「${conflict.name}」）",
				onDismiss = { pickOverlap = null },   // 取消: 什么都不做(不填充表单)
				onConfirm = {
					pickOverlap = null
					// 确认覆盖 -> 用所选课程填充表单, 保存时按"单独编辑此次课程"覆盖该时段
					// 【第九十五/九十六轮】节数以被编辑课程为准(含双击空白格新建的课程,
					// 空白课程当前的节数/拖出的选框长度同样不被源课程覆盖)
					fillForm(uiState, picked, keepLen = true)
					uiState.pickFillMode = true
					uiState.pasteFillMode = false
					toast.toastOK("已选择「${picked.name}」")
				}
			)
		}

		// 【本轮·按要求】"未保存"的询问统一挪到编辑弹窗**关闭之前**(见 EditLessonSheet 的
		// requestClose / confirmValueChange): 有改动时先弹窗, 不退出; 选完才退出。
		// 这里原来那套"先退出、再补弹窗"的逻辑已删除, 避免退出后又被弹一次。

		// 从近期课表选择课程(单击即选中, 自动定位当周)
		if(showPicker){
			CoursePickerDialog(
				render = pickRender,
				onDismiss = { showPicker = false },
				onPicked = { lesson ->
					showPicker = false
					// 【本轮】与"粘贴课程"一致: 目标位置已有课程 -> 先弹"课程重叠"确认
					val conflict = pasteConflictAt(uiState, lesson)
					if(conflict == null){
						// 【第九十五/九十六轮】从课表选择=填充: 节数以被编辑课程为准,
						// 空白课程(双击空白格新建)当前表单里的节数同样保留
						fillForm(uiState, lesson, keepLen = true)
						// 【修改版】从课表选择=复制粘贴+当周: 保存时按"单独编辑此次课程"拆分, 其他周不受影响
						uiState.pickFillMode = true
						uiState.pasteFillMode = false
						toast.toastOK("已选择「${lesson.name}」")
					}else{
						pickOverlap = lesson to conflict
					}
				}
			)
		}
	}

	/**
	 * 用给定课程填充编辑表单
	 *
	 * 【粘贴时周次跟随源课程】有周次信息(week != 0)时按位掩码勾选"上课周数",
	 * 不再一律全选; 源课程没有周次信息时保持当前选择。
	 *
	 * @param keepLen 【第九十五轮】为 true 时保留当前表单的节数(以被编辑课程为准):
	 * "从课表选择课程填充" 4 节课程用 2 节课程填充, 结果仍是 4 节。
	 * 粘贴课程等"整门复制"场景仍传 false, 节数跟随源课程。
	 */
	private fun fillForm(uiState: TermLessonUIState, lesson: Lesson, keepLen: Boolean = false){
		uiState.name = lesson.name
		uiState.place = lesson.place
		uiState.teacher = lesson.teacher
		uiState.color = lesson.color
		if(!keepLen) uiState.len = lesson.len.coerceAtLeast(1)
		// 【需求6】粘贴/从课表选择 不复制源课程的上课周数: 周数保持当前编辑上下文所在周,
		// 即"在第几周、上课周数就是第几周", 不会把源课程的所有周次一并带过来。
		// 【修改版】从课表选择 = 复制+粘贴: 上课周数**只保留当周**(相当于单独编辑此次课程)。
		val w = uiState.editContextWeek
		if(w in uiState.week.indices){
			for(i in uiState.week.indices) uiState.week[i] = false
			uiState.week[w] = true
		}
	}

	/**
	 * 粘贴到当前编辑位置时, 该位置是否已经有别的课程
	 *
	 * 判定口径与拖动覆盖完全一致(课表的 occupantsAt): 目标区间内"本周会上的"课程,
	 * 包含从上方延伸下来压住本区间的长课程; 内容相同的同一门课不算冲突。
	 *
	 * @return 会被覆盖的课程; 目标位置没有课程(或就是同一门课)时返回 null
	 */
	fun pasteConflictAt(uiState: TermLessonUIState, pasted: Lesson): Lesson? {
		val week = uiState.editContextWeek
		if(week < 0) return null
		val table = LessonTableRepository.lessonTable
		val day = uiState.editDayOfWeek.coerceIn(0, 6)
		val slot = uiState.editTimeSlot.coerceIn(0, TimeTables.MAX_SLOT_COUNT - 1)
		val end = (slot + pasted.len.coerceAtLeast(1) - 1).coerceAtMost(TimeTables.MAX_SLOT_COUNT - 1)
		for(s in slot..end){
			val hit = table.occupantsAt(week.coerceIn(0, 62), day, s)
				.firstOrNull { !it.lesson.isSameLessonAs(pasted) }
			if(hit != null) return hit.lesson
		}
		return null
	}

	/** 粘贴/覆盖弹窗里的"目标位置"文案: "第N周 星期X 第a~b节" */
	fun pastePositionText(uiState: TermLessonUIState, len: Int): String {
		val week = uiState.editContextWeek.coerceIn(0, 62)
		val day = uiState.editDayOfWeek.coerceIn(0, 6)
		val slot = uiState.editTimeSlot.coerceIn(0, TimeTables.MAX_SLOT_COUNT - 1)
		val from = slot + 1
		val to = (slot + len.coerceAtLeast(1)).coerceAtMost(TimeTables.MAX_SLOT_COUNT)
		return "第${week + 1}周 ${com.qust.helper.data.Data.WEEK_STRING.getOrElse(day){ "" }} 第${from}~${to}节"
	}

	/**
	 * 从近期课表选择课程: 全屏复用课表视图, 单击课程即选中
	 * 默认定位到"当周", 可左右滑动查看前后周
	 */
	@OptIn(ExperimentalMaterial3Api::class)
	@Composable
	fun CoursePickerDialog(render: LessonRender = remember { LessonRender() }, onDismiss: () -> Unit, onPicked: (Lesson) -> Unit) {
		Dialog(
			onDismissRequest = { onDismiss() },
			properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
		) {
			Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
				Column(modifier = Modifier.fillMaxSize()) {
					Row(
						modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
						verticalAlignment = Alignment.CenterVertically
					) {
						Text(
							text = "点击要复制的课程（可左右滑动切换周）",
							modifier = Modifier.weight(1F).padding(8.dp),
							style = MaterialTheme.typography.bodyMedium
						)
						TextButton(onClick = { onDismiss() }) { Text(text = "取消") }
					}
					Box(modifier = Modifier.weight(1F)) {
						LessonView(
							lessonRender = render,
							onLessonClick = { select -> select.lesson?.let { onPicked(it) } },
							pickMode = true
						)
					}
				}
			}
		}
	}

	/**
	 * 编辑课程弹窗
	 *
	 * 【本轮·按要求】**界面设计保持原样**(还是原来那个贴底、上圆角、带拖动条的弹窗), 只是让它**不动**:
	 * 以前是 `ModalBottomSheet`, 有三个"动"的毛病 ——
	 * ① 内容高度一变(提示行出现/消失、"上课周数"展开收起)整块面板跟着上下滑;
	 * ② 手指在表单上上下滑会被抽屉拖动抢走, 面板被拖起来再弹回;
	 * ③ 输入法弹出时抽屉要重新定位, 边打字边晃。
	 * 现在用固定高度的静态容器复刻同一套外观(位置/圆角/底色/拖动条都不变), 尺寸不随内容变化、也拖不动。
	 *
	 * 退出拦截: 系统返回键 / 左上角"取消" / 点遮罩, 只要表单被改过就一律先弹"保存 / 不保存"
	 * 两按钮弹窗(无"取消"), 编辑界面保持原样不动 —— 绝不会"先退出后弹窗"。
	 */
	@Composable
	@OptIn(ExperimentalMaterial3Api::class)
	fun EditLessonSheet(
		uiState: TermLessonUIState,
		uiEvent: TermLessonUIEvent,
		onDismissed: () -> Unit,
		visible: Boolean = true,
		hideWeekPicker: Boolean = false,
		onPickFromTable: (() -> Unit)? = null,
		onCopyLesson: (() -> Unit)? = null,
		onPasteLesson: (() -> Unit)? = null,
		onDeleteLesson: (() -> Unit)? = null,
		onSaveThisWeek: (() -> Unit)? = null,
		onDeleteAll: (() -> Unit)? = null,
		/** 【本轮】"多选编辑": 参数为勾选的上课周位掩码(第1周 = bit0) */
		onMultiEdit: ((Long) -> Unit)? = null
	) {
		if(!visible) return

		/** 是否显示"保存 / 不保存 / 取消"三按钮弹窗 */
		var showSaveDialog by remember { mutableStateOf(false) }

		/** 真正关闭编辑弹窗(父级移除本弹窗) */
		fun closeSheet() { onDismissed() }

		/**
		 * 请求退出(返回键 / 取消按钮 / 点遮罩):
		 * 【本轮·按要求】有未保存改动 -> **不退出**, 先弹"是否保存"; 选完才真正关闭。
		 * 无改动 -> 直接关闭。与「手动调休」的未保存行为完全一致。
		 */
		fun requestClose() {
			if(uiState.needConfirmExit()) showSaveDialog = true else closeSheet()
		}

		// 【修改版】返回键统一入口: 有未保存修改 -> 界面不动(无动画)只弹窗; 无修改 -> 正常退出
		androidx.activity.compose.BackHandler(enabled = uiState.needConfirmExit()){
			showSaveDialog = true
		}

		// 【本轮·按要求】**外观与之前版本一致、只是不动**:
		// 仍然是原来底部弹窗的样子(贴底、上圆角、同一个底色/遮罩深浅), 但换成静态容器 ——
		// 不能拖动、高度固定, 内容变化(提示行/周数展开收起)不会再让面板上下滑,
		// 输入法弹出也不再拖着面板跑; 没有改动时和「手动调休」一样一动不动。
		//
		// 【本轮·按要求】顶部那条拖动条去掉了(它原来只是外观, 现在也拖不动, 留着只是多余的一条灰线)。
		androidx.compose.ui.window.Dialog(
			onDismissRequest = { requestClose() },
			properties = androidx.compose.ui.window.DialogProperties(
				usePlatformDefaultWidth = false,
				// 点遮罩由下面 Box 的 detectTapGestures 处理(面板铺满窗口, 交给系统反而收不到)
				dismissOnClickOutside = false,
				dismissOnBackPress = true,
				// 自己处理系统栏/输入法, 否则键盘弹出会挡住表单
				decorFitsSystemWindows = false
			)
		) {
			// 【本轮】遮罩深浅恢复成原来底部弹窗那一档(ScrimColor = 32%): 系统对话框默认更黑, 看着像"底色变了"
			val dialogView = LocalView.current
			DisposableEffect(Unit) {
				(dialogView.parent as? DialogWindowProvider)?.window?.setDimAmount(0.32f)
				onDispose { }
			}

			Box(
				modifier = Modifier
					.fillMaxSize()
					.systemBarsPadding()
					.imePadding()
					// 【本轮】点"遮罩"(面板外的空白) = 请求退出: 有未保存改动时只弹窗, 面板一动不动
					.pointerInput(Unit) { detectTapGestures { requestClose() } },
				contentAlignment = Alignment.BottomCenter
			) {
				Surface(
					// 【关键】高度写死: 内容多少都不改尺寸 -> 一动不动
					modifier = Modifier
						.fillMaxWidth()
						.fillMaxHeight()
						// 面板里的空白处**不算**"点遮罩": 把落在这层的点击吃掉, 否则点表单空白会误退出
						.pointerInput(Unit) { detectTapGestures { } },
					// 与之前的底部弹窗视觉一致: 上圆角 28dp + surface 底色 + 1dp 的层次(=原弹窗参数)
					shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
					color = MaterialTheme.colorScheme.surface,
					tonalElevation = 1.dp
				) {
					Column(modifier = Modifier.fillMaxSize()) {
						// 原来拖动条占的 48dp(上下各 22dp + 条高 4dp)保留为空隙, 其余控件位置一动不动
						Spacer(modifier = Modifier.height(48.dp))
						Column(modifier = Modifier.weight(1F)) {
							EditLessonUI(
								uiState = uiState,
								uiEvent = uiEvent,
								onModified = { },
								onDismiss = { requestClose() },
								hideWeekPicker = hideWeekPicker,
								onPickFromTable = onPickFromTable,
								onCopyLesson = onCopyLesson,
								onPasteLesson = onPasteLesson,
								onDeleteLesson = onDeleteLesson,
								onSaveThisWeek = onSaveThisWeek,
								onDeleteAll = onDeleteAll,
								onMultiEdit = onMultiEdit
							)
						}
					}
				}
			}
		}

		if(showSaveDialog){
			AlertDialog(
				// 无"取消": 点击对话框外不关闭, 必须二选一(保存 / 不保存)
				onDismissRequest = { },
				title = { Text(text = "未保存的修改") },
				text = { Text(text = "当前课程尚有未保存的修改，是否保存？") },
				// ① 保存: 执行原保存逻辑, 成功后编辑界面自行关闭、课表主页刷新; 被重合提示拦下则保持打开
				confirmButton = {
					TextButton(onClick = {
						showSaveDialog = false
						// 【本轮】粘贴课程: 保存 = 真正写入课表(覆盖该时段)
						if(uiState.pasteFillMode) uiEvent.pasteLessonNow(currentFormLesson(uiState), null)
						// 【修改版】从课表选择课程填充: 保存走"单独编辑此次课程"
						else if(uiState.pickFillMode) uiEvent.saveLessonThisWeekOnly() else uiEvent.saveLesson()
						uiState.takeSnapshot()
						if(uiState.isShowEdit) {
							// 被"时间重合"提示拦下: 保持打开, 让用户处理
						}
					}) { Text(text = "保存") }
				},
				// ② 不保存: 丢弃修改, 直接关闭(弹窗无"取消"按钮)
				dismissButton = {
					TextButton(onClick = { showSaveDialog = false; onDismissed() }) { Text(text = "不保存") }
				}
			)
		}
	}


	/**
	 * 编辑课程表单
	 *
	 * 【可以往上拉】底部弹窗里的内容超出了屏幕(尤其是"上课周数"网格占了很大一块),
	 * 以前滚动容器没有限定高度 → 屏幕外的"复制课程/粘贴课程"等按钮根本滚不到。
	 * 这里让内容区占据弹窗的剩余高度([ColumnScope]), 滚动才真正生效;
	 * 底部另留一段空白, 保证最后一个按钮能被完整滚出来、不被系统手势区压住。
	 */
	@Composable
	fun ColumnScope.EditLessonUI(
		uiState: TermLessonUIState,
		uiEvent: TermLessonUIEvent,
		onModified: () -> Unit = { },
		onDismiss: () -> Unit = { },
		hideWeekPicker: Boolean = false,
		onPickFromTable: (() -> Unit)? = null,
		onCopyLesson: (() -> Unit)? = null,
		onPasteLesson: (() -> Unit)? = null,
		onDeleteLesson: (() -> Unit)? = null,
		onSaveThisWeek: (() -> Unit)? = null,
		onDeleteAll: (() -> Unit)? = null,
		onMultiEdit: ((Long) -> Unit)? = null
	){
		val scrollState = rememberScrollState()
		var showDeleteConfirm by remember { mutableStateOf(false) }
		/** 【第三十五轮】"删除全部课程"确认弹窗 */
		var showDeleteAllConfirm by remember { mutableStateOf(false) }
		/** 【本轮】"多选编辑"弹窗与勾选的周次 */
		var showMultiEdit by remember { mutableStateOf(false) }
		var multiWeeks by remember { mutableStateOf<Set<Int>>(emptySet()) }
		Column(modifier = Modifier.fillMaxSize()) {

			Box(modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp)) {
				TextButton(modifier = Modifier.clip(CircleShape).align(Alignment.CenterStart), onClick = { onDismiss() }, colors = textActionFilledColors()) {
					// 【第五十四轮·按要求】蓝底上的红字统一改白
					Text(text = "取消")
				}
				Text(
					text = "编辑课程",
					modifier = Modifier.padding(bottom = 8.dp).align(Alignment.Center),
					style = MaterialTheme.typography.titleLarge,
				)
				TextButton(modifier = Modifier.clip(CircleShape).align(Alignment.CenterEnd), onClick = {
					if(uiState.name.isEmpty()){
						uiEvent.toastWarning("请输入课程名称")
						return@TextButton
					}
					// 【本轮】粘贴课程: 点"完成"才真正写入课表(覆盖该时段), 不点完成直接退出则不保存
					if(uiState.pasteFillMode) uiEvent.pasteLessonNow(currentFormLesson(uiState), null)
					// 【修改版】从课表选择课程填充: 保存 = 单独编辑此次课程(只改当周, 原课程拆成两部分)
					else if(uiState.pickFillMode) uiEvent.saveLessonThisWeekOnly() else uiEvent.saveLesson()
					// 保存成功后编辑界面会自行关闭; 这里给一句结果反馈,
					// 被"时间重合"提示拦下时保持打开, 不提示
					if(!uiState.isShowEdit){ uiState.takeSnapshot(); uiEvent.toastOK("已保存") }
				}, colors = textActionFilledColors()) {
					// 【右上角"完成"】点一下即保存并退出编辑界面
					Text(text = "完成")
				}
			}

			Column(
				modifier = Modifier
					.weight(1F)
					.padding(horizontal = 16.dp)
					.verticalScroll(scrollState)
					.padding(bottom = 32.dp),
				verticalArrangement = Arrangement.spacedBy(4.dp)
			) {

			OutlinedTextField(
				value = uiState.name,
				onValueChange = { uiState.name = it; onModified() },
				label = { Text(text = "课程名称") },
				isError = uiState.name.isEmpty(),
				modifier = Modifier.fillMaxWidth()
			)

			OutlinedTextField(
				value = uiState.place,
				onValueChange = { uiState.place = it; onModified() },
				label = { Text(text = "教室") },
				modifier = Modifier.fillMaxWidth()
			)

			OutlinedTextField(
				value = uiState.teacher,
				onValueChange = { uiState.teacher = it; onModified() },
				label = { Text(text = "教师") },
					modifier = Modifier.fillMaxWidth()
				)

				Row(
					modifier = Modifier.fillMaxWidth(),
					verticalAlignment = Alignment.CenterVertically
				) {
					Column(modifier = Modifier.weight(1F)) {
						Text(text = "上课节数")
						Text(
							text = "最多 ${uiState.maxLen} 节（不得超过时间末尾）",
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.secondary
						)
					}
					Picker.AnimNumberPicker(
						number = uiState.len,
						onAdd = { if(uiState.len < uiState.maxLen){ uiState.len++; onModified() } },
						onSub = { if(uiState.len > 1){ uiState.len--; onModified() } }
					)
				}

				if(!hideWeekPicker){
					// 【第三十五轮】上课周数被"拖动选择"修改后同样视为"已修改":
					// 退出(返回/下滑/点遮罩)时与其它修改一样先提示"是否保存"。
					var weekDragInit by remember { mutableStateOf(true) }
					LaunchedEffect(uiState.week.toList()){
						if(weekDragInit){ weekDragInit = false; return@LaunchedEffect }
						onModified()
					}
					// 【默认折叠】周数网格几乎占满整个弹窗: 想上滑到底部的按钮时, 手指落在网格上会
					// 误选/误取消周次, 而且很难滑过去。默认只保留一行标题(显示已选周数), 点一下才展开。
					var weekExpanded by remember { mutableStateOf(false) }
					val selectedCount = remember(uiState.week.toList()) { uiState.week.count { it } }

					Row(
						modifier = Modifier
							.fillMaxWidth()
							.clip(RoundedCornerShape(8.dp))
							.tapRipple(shape = RoundedCornerShape(8.dp)) { weekExpanded = !weekExpanded }
							.padding(vertical = 8.dp),
						verticalAlignment = Alignment.CenterVertically
					) {
						Column(modifier = Modifier.weight(1F)) {
							val weekHint = if(uiState.editContextWeek >= 0) "（本课程在第${uiState.editContextWeek + 1}周）" else ""
							Text(text = "上课周数$weekHint")
							Text(
								text = if(weekExpanded) "点击收起周数表" else "已选 ${selectedCount} 周，点击展开选择",
								style = MaterialTheme.typography.bodySmall,
								color = MaterialTheme.colorScheme.secondary
							)
						}
						Text(
							text = if(weekExpanded) "收起" else "展开",
							style = MaterialTheme.typography.bodyMedium,
							color = MaterialTheme.colorScheme.primary
						)
					}

					if(weekExpanded){
						Row(
							modifier = Modifier.fillMaxWidth(),
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.spacedBy(4.dp)
						) {
						TextButton(onClick = { for(i in 0 ..< uiState.totalWeek) uiState.week[i] = true; onModified() }) {
							Text(text = "全选")
						}
						TextButton(onClick = { for(i in 0 ..< uiState.totalWeek) uiState.week[i] = i % 2 == 0; onModified() }) {
							Text(text = "单周")
						}
						TextButton(onClick = { for(i in 0 ..< uiState.totalWeek) uiState.week[i] = i % 2 == 1; onModified() }) {
							Text(text = "双周")
						}
						}

						Picker.DragAblePicker(counts = uiState.totalWeek, rows = 6, uiState.week)

						// 上课周数的时间冲突提示: 所选周次里该时段已经被别的课程占用
						val weekMask = remember(uiState.week.toList()) {
							var mask = 0L
							for(i in uiState.week.indices){ if(uiState.week[i]) mask = mask or (1L shl i) }
							mask
						}
						val weekConflicts = remember(weekMask, uiState.len, uiState.editDayOfWeek, uiState.editTimeSlot, LessonTableRepository.lessonTableVersion){
							LessonTableRepository.lessonTable.conflictingWeeks(
								uiState.editDayOfWeek, uiState.editTimeSlot, uiState.len, weekMask,
								uiState.editingLesson, uiState.totalWeek
							)
						}
						if(weekConflicts.isNotEmpty()){
							Text(
								text = "⚠ 所选周数中这些周的时间已被占用：",
								style = MaterialTheme.typography.bodySmall,
								color = MaterialTheme.colorScheme.error,
								modifier = Modifier.padding(top = 4.dp)
							)
							Text(
								text = weekConflicts.take(12).joinToString("、"){ "第${it.first}周「${it.second}」" } +
									if(weekConflicts.size > 12) " 等 ${weekConflicts.size} 周" else "",
								style = MaterialTheme.typography.bodySmall,
								color = MaterialTheme.colorScheme.error
							)
							Text(
								text = "保存时会再次提示；可改用其它周次，或继续保存让这两门课在该周重叠。",
								style = MaterialTheme.typography.bodySmall,
								color = MaterialTheme.colorScheme.secondary
							)
						}
					}
				}

				Text(text = "课程颜色")

				Picker.ColorPicker(colors = LESSON_BACKGROUND_COLORS, select = uiState.color, rows = 6, onClick = { uiState.color = it; onModified() })

				// 【第三十五轮】"快速填充"改名为"课程的填充、复制、粘贴、编辑、删除"(本轮按需求定稿),
				// 并在其下提供"单独编辑此次课程"、"多选编辑"与"删除全部课程"
				if(onPickFromTable != null || onCopyLesson != null || onPasteLesson != null || onDeleteLesson != null || onSaveThisWeek != null || onDeleteAll != null){
					Text(text = "课程的填充、复制、粘贴、编辑、删除")

					// 【两行按钮的排布口径】按钮宽度一律取"文字本身的宽度"(汉字全角 = 1 个字宽,
					// 按与文字相同的字号换算, 换系统字体大小也跟着变), 文字在按钮里既不裁字也不留白,
					// 于是可以直接按字符数算位置:
					//   下一行: "单独编辑此次课程"贴左边框, "删除课程"贴右边框, 中间两段间距均分
					//           → 两端距边框一致, "单独…"与"删除全部…"、"删除全部…"与"删除课程"的间距也一致;
					//   上一行: "从课表选择课程填充"贴左边框(位置不动), "粘贴课程"贴右边框(与"删除课程"完全同宽同位),
					//           "复制课程"的中心对准"删除全部课程"的中心。
					// 字号、行高、上下内边距都没动, 所以行高与上下两行的相对位置完全不变。
					BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
						val density = LocalDensity.current
						val labelSize = MaterialTheme.typography.labelLarge.fontSize.value.let { if(it > 0f) it else 14f }
						/** n 个汉字的宽度(与按钮文字同字号; sp→dp 已含系统字体大小) */
						fun textWidth(n: Int) = with(density) { (labelSize * n).sp.toDp() }
						// 【本轮·按要求】按钮的点击反馈(蓝色波纹)只画在按钮自身边界内, 而文字是
						// fillMaxWidth + 左对齐: 宽度正好等于"字形宽度"时, 最后一个字的右边缘会贴边,
						// 再加上 labelLarge 自带的一点字距, 波纹看起来"差一点没盖住文字"。
						// 这里给每个按钮左右各留 4dp 内边距(共 8dp) + 1dp 余量, 波纹就能完整包住文字。
						// 文字的起点仍是各行同一口径, 六个按钮统一加宽, 相对位置不变。
						// 【第三十二轮·按要求】留白与「回到本周」对齐(左右 12dp), 并加同形状的灰色描边
						val btnPad = TEXT_ACTION_H_PADDING
						/** n 个汉字的**按钮**宽度 = 文字宽 + 左右内边距 + 1dp 余量 */
						fun btnWidth(n: Int) = textWidth(n) + btnPad * 2 + 1.dp
						val wPick = btnWidth(9)           // 从课表选择课程填充
						val wCopy = btnWidth(4)           // 复制课程 / 粘贴课程
						val wSaveOne = btnWidth(8)        // 单独编辑此次课程
						val wDeleteAll = btnWidth(6)      // 删除全部课程
						val wDelete = btnWidth(4)         // 删除课程
						val wMulti = btnWidth(4)          // 多选编辑
						// 下一行的单段间距: 剩下的宽度平均分给两段
						val rowGap = ((maxWidth - wSaveOne - wDeleteAll - wDelete) / 2f).coerceAtLeast(4.dp)
						// "复制课程"的起点: 中心落在"删除全部课程"的中心上
						val copyStart = wSaveOne + rowGap + (wDeleteAll - wCopy) / 2f
						val firstGap = (copyStart - wPick).coerceAtLeast(4.dp)

						// 【本轮】BoxWithConstraints 本质是 Box, 子项会叠在一起(上一版两行因此重合),
						// 这里用 Column 把它们按上下两行排起来; 宽度/间距公式与字号一字未改。
						// 两行之间仍是 4dp(与父 Column 的 Arrangement.spacedBy(4.dp) 一致),
						// 所以上下位置与"两个 Row 直接放在父 Column 里"完全一样。
						Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
							Row(modifier = Modifier.fillMaxWidth()) {
						onPickFromTable?.let { action ->
							// 【修改版】仅点击按钮但未选择课程时不算修改(不弹保存询问);
							// 真正选中课程后表单内容变化, 由 needConfirmExit 判定为已修改。
							// 【本轮】宽度 = 9 个汉字 + 左右各 4dp 内边距, 文字左对齐 → "从"距左边框 4dp
							// (这 4dp 是给点击波纹留的, 否则波纹右缘盖不住最后一个字)
							TextButton(
								onClick = { action() },
								modifier = Modifier.width(wPick),
								colors = textActionFilledColors(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = btnPad, vertical = TEXT_ACTION_V_PADDING)
							) {
								Text(
									text = "从课表选择课程填充",
									maxLines = 1,
									textAlign = TextAlign.Start,
									modifier = Modifier.fillMaxWidth(),
									style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 0.sp)
								)
							}
						}
						onCopyLesson?.let { action ->
							// 【本轮】宽度 = 4 个汉字, 起点按"中心对齐删除全部课程"算好, 中间用定长留白
							Spacer(modifier = Modifier.width(firstGap))
							TextButton(
								onClick = action,
								modifier = Modifier.width(wCopy),
								colors = textActionFilledColors(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = btnPad, vertical = TEXT_ACTION_V_PADDING)
							) { Text(text = "复制课程", maxLines = 1, textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth()) }
						}
						onPasteLesson?.let { action ->
							// 【本轮】前面垫弹性留白把它顶到行尾: 右缘 = 右边框(与"删除课程"同一位置、同一宽度)
							Spacer(modifier = Modifier.weight(1F))
							TextButton(
								onClick = { action(); onModified() },
								modifier = Modifier.width(wCopy),
								colors = textActionFilledColors(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = btnPad, vertical = TEXT_ACTION_V_PADDING)
							) { Text(text = "粘贴课程", maxLines = 1, textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth()) }
						}
							}

						// 【修改版】"删除课程"也在此行(日课表只提供删除课程时, 这一行仍需显示)
						if(onSaveThisWeek != null || onDeleteAll != null || onDeleteLesson != null){
							// 【本轮】与上一行同一口径: 三个按钮各按文字宽度, 用两段等分留白排开 →
							// "单独编辑此次课程"贴左、"删除课程"贴右、两段间距一致;
							// 只剩一个按钮时(日课表页)两侧留白相等, 它仍在整行居中, 与原来一致。
							Row(modifier = Modifier.fillMaxWidth()) {
								onSaveThisWeek?.let { action ->
									// 【第四十二轮】按要求恢复: 未修改时灰色不可点; 修改后点击 =
									// 保存编辑结果并按"单独编辑此次课程"的拆分逻辑拆分(当周独立+原课程去掉该周)。
									// 【第六十二/六十三轮】新口径: 只有"课程名称/教师/教室/课程颜色"改动、且**没有改过上课周数**时才可点;
									// 改了上课周数(即使同时改了名称/教师)也置灰, 点击给出气泡提示。
									// 【第五十五轮·按要求】不可用时按钮改成**灰色填充**(不再只是字变淡),
									// 气泡文案统一为"仅编辑课程名称/教师/教室/上课节数/课程颜色后方可点击"。
									val contentModified = uiState.contentChange() && !uiState.weekChange()
									TextButton(onClick = {
										if(!contentModified){
											uiEvent.toastWarning(NO_CONTENT_CHANGE_TIP)
										}else{
											action()
										}
									}, modifier = Modifier.width(wSaveOne),
										colors = if(contentModified) textActionFilledColors() else textActionFilledGrayColors(),
										contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = btnPad, vertical = TEXT_ACTION_V_PADDING)) {
									Text(
										text = "单独编辑此次课程",
										maxLines = 1,
										textAlign = TextAlign.Start,
										modifier = Modifier.fillMaxWidth()
									)
									}
								}
								Spacer(modifier = Modifier.weight(1F))
								// 【修改版】顺序: 单独编辑此次课程 → 删除全部课程 → 删除课程
								onDeleteAll?.let { action ->
									TextButton(onClick = {
										// 【本轮修复】空白课程/灰色课程不进入确认框, 直接给气泡提示
										val blocked = uiEvent.deleteBlockedMessage()
										if(blocked != null) uiEvent.toastWarning(blocked) else showDeleteAllConfirm = true
									}, modifier = Modifier.width(wDeleteAll),
										colors = textActionFilledColors(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = btnPad, vertical = TEXT_ACTION_V_PADDING)) {
										// 【第五十五轮·按要求】浅蓝填充上的红字改回红色
										Text(text = "删除全部课程", maxLines = 1, color = MaterialTheme.colorScheme.error,
											textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth())
									}
								}
								Spacer(modifier = Modifier.weight(1F))
								onDeleteLesson?.let {
									TextButton(onClick = {
										// 【本轮修复】空白课程 -> "没有可删除的课程"; 灰色课程 -> "该课程本周不上课，不能删除"
										val blocked = uiEvent.deleteBlockedMessage()
										if(blocked != null) uiEvent.toastWarning(blocked) else showDeleteConfirm = true
									}, modifier = Modifier.width(wDelete),
										colors = textActionFilledColors(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = btnPad, vertical = TEXT_ACTION_V_PADDING)) {
										// 【第五十五轮·按要求】浅蓝填充上的红字改回红色
										Text(text = "删除课程", maxLines = 1, color = MaterialTheme.colorScheme.error,
											textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth())
									}
								}
							}
						}
						// 【本轮·按要求】"多选编辑"放在"单独编辑此次课程"**正下方**(左边框对齐):
						// 点开后可勾选本课程有课的若干周, 保存 = 把这几周用新内容拆成独立课程,
						// 原课程保留其余周次(与"单独编辑此次课程"同一口径, 只是从"一周"变成"多周")。
						if(onMultiEdit != null){
							val multiReady = uiState.contentChange() && !uiState.weekChange()
							val ownedWeekCount = (0 until uiState.totalWeek.coerceAtMost(62))
								.count { uiState.originWeekMask and (1L shl it) != 0L }
							Row(modifier = Modifier.fillMaxWidth()) {
								TextButton(onClick = {
									if(!multiReady){
										uiEvent.toastWarning(NO_CONTENT_CHANGE_TIP)
									}else if(ownedWeekCount < 2){
										uiEvent.toastWarning("该课程只有一周，无法多选编辑")
									}else{
										// 【本轮·按要求】默认只勾选**点开这门课的这周**:
										// 比如在第3周的课表里点开物理课, 就只默认选第3周,
										// 本课程其它有课的周一律不默认选中(要拆几周由用户自己勾)。
										val cw = uiState.editContextWeek
										multiWeeks = if(cw >= 0 && (uiState.originWeekMask and (1L shl cw)) != 0L) setOf(cw) else emptySet()
										showMultiEdit = true
									}
								}, modifier = Modifier.width(wMulti),
									// 【第五十五轮·按要求】不可用时灰色填充
									colors = if(multiReady) textActionFilledColors() else textActionFilledGrayColors(),
									contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = btnPad, vertical = TEXT_ACTION_V_PADDING)) {
								Text(
									text = "多选编辑",
									maxLines = 1,
									textAlign = TextAlign.Start,
									modifier = Modifier.fillMaxWidth()
								)
								}
							}
						}
						}
					}
				}
			}
		}

		// 保存时的"课程重叠"提示: 确认后强制保存(【第九十五轮】确认=覆盖, 已有课程会被删除)
		uiState.overlapPrompt?.let { message ->
			Dialogs.AskDialog(
				title = "课程重叠",
				content = message,
				onDismiss = { uiState.overlapPrompt = null },
				onConfirm = {
					uiState.overlapPrompt = null
					uiEvent.saveLesson(true)
					// 【本轮】确认"继续保存"后同样复位"未保存"基准,
					// 否则编辑框一关就会紧接着再弹一次"未保存的修改"(学期课表/日课表共用此处)
					if(!uiState.isShowEdit) uiState.takeSnapshot()
				}
			)
		}

		// 需求6: 删除课程前先确认(确认后才执行删除, 取消则不动)
		if(showDeleteConfirm){
			AlertDialog(
				onDismissRequest = { showDeleteConfirm = false },
				title = { Text(text = "删除课程") },
				text = { Text(text = "确定删除当周该课程吗？此操作不可撤销。") },
				confirmButton = {
					TextButton(onClick = {
						showDeleteConfirm = false
						// 【第三十七轮】删除动作自身会关闭编辑界面, 不再标记"已修改" ——
						// 否则底部弹窗收起时会误弹"未保存的修改"提示
						onDeleteLesson?.invoke()
					}) { Text(text = "删除", color = MaterialTheme.colorScheme.error) }
				},
				dismissButton = {
					TextButton(onClick = { showDeleteConfirm = false }) { Text(text = "取消") }
				}
			)
		}

		// 【第三十五轮】"删除全部课程"前先确认(所有周次一起删, 不可撤销)
		if(showDeleteAllConfirm){
			AlertDialog(
				onDismissRequest = { showDeleteAllConfirm = false },
				title = { Text(text = "删除全部课程") },
				text = { Text(text = "确定删除该课程的所有周次吗？此操作不可撤销。") },
				confirmButton = {
					TextButton(onClick = {
						showDeleteAllConfirm = false
						onDeleteAll?.invoke()
					}) { Text(text = "删除", color = MaterialTheme.colorScheme.error) }
				},
				dismissButton = {
					TextButton(onClick = { showDeleteAllConfirm = false }) { Text(text = "取消") }
				}
			)
		}

		// 【本轮】"多选编辑": 只列出**本课程有课**的周; 默认勾选"点开这门课的那一周"
		if(showMultiEdit && onMultiEdit != null){
			val ownedWeeks = (0 until uiState.totalWeek.coerceAtMost(62))
				.filter { uiState.originWeekMask and (1L shl it) != 0L }
			AlertDialog(
				onDismissRequest = { showMultiEdit = false },
				title = { Text(text = "多选编辑") },
				text = {
					Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
						Text(
							text = "选择需要编辑的周数",
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.secondary
						)
						Spacer(modifier = Modifier.height(8.dp))
						for(row in ownedWeeks.chunked(6)){
							Row(
								modifier = Modifier.padding(bottom = 6.dp),
								horizontalArrangement = Arrangement.spacedBy(6.dp)
							) {
								for(w in row){
									val on = multiWeeks.contains(w)
									Box(
										modifier = Modifier
											.size(40.dp)
											.clip(RoundedCornerShape(8.dp))
											.background(if(on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
											.tapRipple(shape = RoundedCornerShape(8.dp)) { multiWeeks = if(on) multiWeeks - w else multiWeeks + w },
										contentAlignment = Alignment.Center
									) {
										Text(
											text = "${w + 1}",
											color = if(on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
										)
									}
								}
							}
						}
						Text(
							text = "已选 ${multiWeeks.size} 周",
							style = MaterialTheme.typography.bodySmall
						)
					}
				},
				confirmButton = {
					TextButton(onClick = {
						if(multiWeeks.isEmpty()){
							uiEvent.toastWarning("请至少选择一周")
							return@TextButton
						}
						var mask = 0L
						for(w in multiWeeks) mask = mask or (1L shl w)
						showMultiEdit = false
						onMultiEdit.invoke(mask)
					}) { Text(text = "保存") }
				},
				dismissButton = {
					TextButton(onClick = { showMultiEdit = false }) { Text(text = "取消") }
				}
			)
		}
	}
}

/**
 * 【本轮】用当前编辑表单内容构造一节临时课程
 *
 * 原来是 [TermLesson] 内部的局部函数, "编辑课表"(日课表)也要用同一份口径,
 * 因此提到文件顶层, 两个界面共用。
 *
 * 【带上上课周数】必须一起复制 [TermLessonUIState.weekMask], 否则粘贴时拿不到源课程的
 * 周次, 只能退回"全选", 出现"19个周全选"的问题。
 */
fun currentFormLesson(uiState: TermLessonUIState): Lesson = Lesson(
	type = 1,
	color = uiState.color,
	len = uiState.len.coerceAtLeast(1),
	name = uiState.name,
	place = uiState.place,
	teacher = uiState.teacher,
	week = uiState.weekMask()
)
