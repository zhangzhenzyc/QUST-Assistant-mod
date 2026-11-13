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

import androidx.activity.viewModels
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.qust.helper.R
import com.qust.helper.data.Keys
import com.qust.helper.data.Page
import com.qust.helper.data.eas.Exam
import com.qust.helper.ui.widget.AppWidgets
import com.qust.helper.ui.widget.cardShadow
import com.qust.helper.ui.widget.Texts
import com.qust.helper.ui.widget.Toast
import com.qust.helper.viewmodel.eas.GetExamsViewModel

object GetExams {

	val GetExamsPage = Page(Keys.Page.GetExamsPage, "考试查询", iconRes = R.drawable.ic_insert_invitation) { activity, padding, navController, _ ->
		val viewModel by activity.viewModels<GetExamsViewModel>()
		GetExamsUI(padding, viewModel, activity.toast, navController)
	}

	@Composable
	fun GetExamsUI(padding: PaddingValues, viewModel: GetExamsViewModel, toast: Toast, navController: NavController){
		AppWidgets.CheckEasLogin(viewModel = viewModel, navController = navController)

		// 【修改版】进入即按当前选择的学年/学期查询, 切换学期后重新进入即可看到该学期的考试安排
		LaunchedEffect(Unit){ viewModel.queryExams() }

		val exams by viewModel.exams
		Box(modifier = Modifier.padding(padding)){
			EasQuery(
				pickYear = viewModel.pickYear,
				items = exams,
				itemView = { ExamItem(it) },
				onYearPick = { viewModel.exams.value = viewModel.examData[it] },
				doQuery = { viewModel.queryExams() }
			)
			// 【第七十轮·修"查询中一闪而过、之后什么都没有"】列表为空时把**结果**写在页面上:
			// 查询失败 -> 显示失败原因(未登录/连不上/教务没数据), 查询成功但没考试 -> 显示没考试。
			// 不再只靠一个 1 秒气泡 + 空白列表让用户自己猜。
			if(exams.isEmpty()) {
				Text(
					text = viewModel.queryError.ifEmpty { "本学期没有查到考试安排" },
					modifier = Modifier.align(Alignment.Center).padding(horizontal = 24.dp),
					style = MaterialTheme.typography.bodyMedium,
					color = if(viewModel.queryError.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
					textAlign = TextAlign.Center
				)
			}
		}
		AppWidgets.DialogBar(dialogText = viewModel.dialogText)
		toast.ToastContent(viewModel.toastContent)
	}

	@Composable
	fun ExamItem(exam: Exam){
		// 【第六十八轮·按要求】同一套大范围柔和投影(见 SoftCard.kt)。
		// 圆角保持本卡片原来的 8dp, 所以 cardShadow 的 radiusDp 也跟着传 8。
	Card(
		modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp).cardShadow(radiusDp = 8),
		shape = RoundedCornerShape(8.dp),
		colors = CardDefaults.cardColors(
			containerColor = Color.White,
		),
		elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
		// 【第七十轮】同学业查询页: 关掉 M3 FilledCard 自带的 1dp outline 描边,
		// 它就是"条目下边隐隐约约一条白线/灰线"的真身。
		border = androidx.compose.foundation.BorderStroke(0.dp, Color.Transparent),
	) {

			Column(
				modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp),
				horizontalAlignment = Alignment.CenterHorizontally,
			) {

				// 【本轮·按要求】课程名称过长时显示不全 -> 支持**左右滑动**看全:
				// 外层是横向滚动容器, 名称本身不换行(maxLines=1 + softWrap=false);
				// 内容比卡片窄时仍保持居中(widthIn(min=卡片宽) + textAlign=Center),
				// 内容更宽时就可以左右拖动, 不影响列表本身的上下滚动。
				BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
					val viewport = maxWidth
					Row(
						modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
						verticalAlignment = Alignment.CenterVertically
					) {
						Text(
							text = exam.name,
							style = MaterialTheme.typography.titleMedium,
							maxLines = 1,
							softWrap = false,
							textAlign = TextAlign.Center,
							modifier = Modifier.widthIn(min = viewport).padding(8.dp)
						)
					}
				}
				Row(modifier = Modifier.fillMaxWidth()) {
					Texts.SingleLineText(text = "考试地点: ")
					Texts.SingleLineText(text = exam.place, modifier = Modifier.weight(1F))
				}
				Row(modifier = Modifier.fillMaxWidth()){
					Texts.SingleLineText(text = "考试时间: ")
					Texts.SingleLineText(text = exam.time, modifier = Modifier.weight(1F))
				}
			}
		}
	}
}
