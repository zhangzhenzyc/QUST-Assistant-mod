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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.qust.helper.ui.theme.tapRipple
import com.qust.helper.ui.widget.cardShadow
import com.qust.helper.R
import com.qust.helper.data.Keys
import com.qust.helper.data.Page
import com.qust.helper.data.room.LessonInfo
import com.qust.helper.data.room.Mark
import com.qust.helper.ui.theme.colorSecondaryText
import com.qust.helper.ui.widget.AppWidgets
import com.qust.helper.ui.widget.Texts
import com.qust.helper.ui.widget.Toast
import com.qust.helper.ui.widget.TripleProgressBar
import com.qust.helper.utils.DateUtils
import com.qust.helper.viewmodel.eas.AcademicGroup
import com.qust.helper.viewmodel.eas.AcademicUIEvent
import com.qust.helper.viewmodel.eas.GetAcademicViewModel

object GetAcademic {

	val GetAcademicPage = Page(Keys.Page.GetAcademicPage, "学业查询", iconRes = R.drawable.ic_academic_search) { activity, padding, navController, _ ->
		val viewModel by activity.viewModels<GetAcademicViewModel>()
		GetAcademic(padding, viewModel, activity.toast, navController)
	}

	val LESSON_TYPE = arrayOf(
		"", "在修", "未过", "未修", "已修",
		"校内被替代课程",
		"校内课程替代",
		"校内课程替代节点",
		"校外课程替换节点/校外认定课程",
		"校内被认定课程",
		"学业预警不审核课程"
	)

	@Composable
	fun GetAcademic(padding: PaddingValues, viewModel: GetAcademicViewModel, toast: Toast, navController: NavController){
		// 【本轮·按要求】未登录时先去「数字青科登陆」(账号管理的同一个界面):
		// 先试教务管理系统, 那条路进不去才由登录页接着给「教务登陆」走原网址。
		AppWidgets.CheckEasLogin(viewModel = viewModel, navController = navController)
		// 进入页面(以及从登录页返回后重新组合)都会查一次
		LaunchedEffect(Unit){ viewModel.loadData() }
		Box(modifier = Modifier.padding(padding)){
			GetAcademicUI(showMode = viewModel.uiState.showMode, groups = viewModel.uiState.lessonGroups, uiEvent = viewModel.uiEvent)
		}
		AppWidgets.DialogBar(dialogText = viewModel.dialogText)
		toast.ToastContent(viewModel.toastContent)
	}

	// 【第二十六轮】注: 长按获取日志功能已删除; 这里的 OptIn 是 LazyColumn 的 stickyHeader 所需
	@OptIn(ExperimentalFoundationApi::class)
	@Composable
	fun GetAcademicUI(showMode: Int, groups: List<AcademicGroup>, uiEvent: AcademicUIEvent) {
		Column {
			Row(modifier = Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {

				Text(text = "展示方式", modifier = Modifier.padding(8.dp))

				Row(modifier = Modifier.clickable { uiEvent.changeGroup() }, verticalAlignment = Alignment.CenterVertically){
					Text(text = if(showMode == 0) "课程类型" else "学期", modifier = Modifier.padding(8.dp), color = colorSecondaryText)
					Icon(imageVector = Icons.Rounded.ArrowDropDown, contentDescription = null, tint = colorSecondaryText)
				}

				Spacer(modifier = Modifier.fillMaxWidth().weight(1F))

				// 【第二十六轮】按要求去掉"长按获取日志"功能: 恢复为普通点击"重新查询"
				Row(modifier = Modifier.clickable { uiEvent.queryData() }, verticalAlignment = Alignment.CenterVertically){
					Text(text = "重新查询", modifier = Modifier.padding(8.dp), color = colorSecondaryText)
					Icon(imageVector = Icons.Rounded.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
				}
			}

			// 【第六十八轮】底部留白 14dp: 否则最后一条卡片的投影被视口裁掉, 卡底白边直接
			// 贴着页面浅灰底, 就是用户说的"像是有个白线"。
			LazyColumn(contentPadding = PaddingValues(bottom = 14.dp)) {
				groups.forEachIndexed { i, dataItem ->
					stickyHeader {
						GroupUI(dataItem,
							onClick = { uiEvent.clickGroup(i) },
							sortBy = { uiEvent.sortBy(i, it) },
							sortType = { uiEvent.sortType(i) }
						)
					}
					if(dataItem.isExpand) {
						items(dataItem.lessons.size) { row ->
							ItemUI(
								dataItem.lessons[row],
								dataItem.lessonMarks[row],
								dataItem.isLessonExpand[row]
							) { uiEvent.clickLesson(i, row) }
						}
					}
				}
			}
		}
	}

	@Composable
	fun GroupUI(group: AcademicGroup, onClick: () -> Unit = { }, sortBy: (Int) -> Unit = { }, sortType: () -> Unit = { }){
		val lessonGroup = group.groupInfo
		var showPop by remember { mutableStateOf(false) }

		// 【第六十八轮·按要求】"阴影不够自然, 范围不够大, 看起来像是有个白线"
		// 改法见 SoftCard.kt: 大范围柔和投影(clip=false) + Card 自身 elevation 归零,
		// 形状保持 M3 默认的 12dp 圆角(和时间表条目的观感一致)。
		// 【第七十轮·修"条目下边还是能隐隐约约看见白线"】那圈白线的真身是 **M3 FilledCard
		// 自带的 1dp outline 描边**(浅灰, 平时几乎看不见, 但在纯白卡 + 浅灰页底的对比下,
		// 卡片下缘那一条就会"隐隐约约"). 显式传 border = BorderStroke(0.dp, Transparent)
		// 把它关掉 —— 白底就是纯白到底, 没有任何描边。
		Card(
			modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp).cardShadow(),
			shape = RoundedCornerShape(12.dp),
			colors = CardDefaults.cardColors(containerColor = Color.White),
			elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
			border = androidx.compose.foundation.BorderStroke(0.dp, Color.Transparent),
		) {
			Box(Modifier.clickable { onClick() }) {
				Column(Modifier.padding(8.dp)) {

					Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
						Text(text = lessonGroup.type, style = MaterialTheme.typography.titleMedium, modifier = Modifier)

						Text(
							text = "共 ${lessonGroup.totalCounts} 门，通过 ${lessonGroup.passedCounts} 门，要求学分 ${lessonGroup.requireCredits}，已修 ${lessonGroup.obtainedCredits}",
							style = MaterialTheme.typography.bodySmall,
							color = colorSecondaryText,
							modifier = Modifier.fillMaxWidth(),
							textAlign = TextAlign.End
						)
					}

					Spacer(modifier = Modifier.height(8.dp))

					// 要求学分为 0 时(模块没有预置课程、学分也没解析出来)不做除法, 否则进度条是 NaN
					val require = lessonGroup.requireCredits
					TripleProgressBar.TripleProgressBar(
						redValue = if(require > 0F) ((lessonGroup.obtainedCredits + lessonGroup.creditNotEarned) / require).coerceAtMost(1F) else 0F,
						greenValue = if(require > 0F) (lessonGroup.obtainedCredits / require).coerceAtMost(1F) else 0F,
						height = 5.dp
					)

					if(group.isExpand){
						Row(modifier = Modifier.padding(start = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
							Text(text = "排序方式", style = MaterialTheme.typography.bodySmall)

							Box{
								Texts.IconText(
									text = when(group.sortBy){ 0 -> "成绩排序"; 1 -> "学分排序"; 2 -> "修读状态"; else -> "" },
									icon = Icons.Rounded.ArrowDropDown,
									modifier = Modifier.padding(start = 8.dp).clickable { showPop = true }
								)

								DropdownMenu(expanded = showPop, onDismissRequest = { showPop = false }) {
									Box(Modifier.clickable{ showPop = false; sortBy(0) }){
										Text(text = "成绩排序", modifier = Modifier.padding(16.dp, 8.dp))
									}
									Box(Modifier.clickable{ showPop = false; sortBy(1) }){
										Text(text = "学分排序", modifier = Modifier.padding(16.dp, 8.dp))
									}
									Box(Modifier.clickable{ showPop = false; sortBy(2) }){
										Text(text = "修读状态", modifier = Modifier.padding(16.dp, 8.dp))
									}
								}
							}

							Texts.IconText(
								text = if(group.sortType == 1) "升序" else "降序",
								icon = Icons.Rounded.ArrowDropDown,
								modifier = Modifier.padding(start = 8.dp).clickable { sortType() },
								iconModifier = Modifier.rotate(if(group.sortType == 1) 0F else 180F)
							)
						}
					}
				}
			}
		}
	}

	@Composable
	fun ItemUI(lessonInfo: LessonInfo, lessonMark: Mark, isExpanded: Boolean, onClick: () -> Unit){
		// 【第五十八轮·修"条目没有按照时间表的样式修改(阴影没了)"】
		// `tapRipple` 内部第一步是 `clip(12dp 圆角)`, 挂在 Card **外面**时会把 Card 的 6dp
		// 投影一起裁掉 —— 白底还在、阴影消失, 看上去就比时间表的卡片扁一层。
		// 现改为: Card 只负责外观(白底 + 6dp), 点击反馈下放到内部铺满内容区的 Column。
		// 【第六十八轮·按要求】"阴影不够自然, 范围不够大, 看起来像是有个白线"
		// 改法见 SoftCard.kt: 大范围柔和投影(clip=false) + Card 自身 elevation 归零,
		// 形状保持 M3 默认的 12dp 圆角(和时间表条目的观感一致)。
		// 【第七十轮】同 GroupUI: 显式关掉 M3 FilledCard 的 outline 描边(白线真身)。
		Card(
			modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp).cardShadow(),
			shape = RoundedCornerShape(12.dp),
			colors = CardDefaults.cardColors(containerColor = Color.White),
			elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
			border = androidx.compose.foundation.BorderStroke(0.dp, Color.Transparent),
		) {
			Column(
				modifier = Modifier
					.fillMaxWidth()
					.tapRipple { onClick() }
					.padding(horizontal = 16.dp, vertical = 4.dp),
				verticalArrangement = Arrangement.spacedBy(4.dp)
			) {

				Row(Modifier.height(IntrinsicSize.Min)){
					Text(
						text = lessonInfo.name,
						modifier = Modifier.weight(1F),
						style = MaterialTheme.typography.titleMedium,
						textAlign = TextAlign.Start
					)
					Texts.SingleLineTextNoPadding(
						text = if(lessonInfo.status == 4) "成绩: ${lessonInfo.mark}" else LESSON_TYPE[lessonInfo.status],
						modifier = Modifier.fillMaxHeight(),
						textAlign = TextAlign.Center
					)
				}

				Row {
					Text(
						text = "${lessonInfo.category} | ${lessonInfo.content}",
						style = MaterialTheme.typography.bodySmall,
						color = colorSecondaryText,
					)

					Spacer(modifier = Modifier.fillMaxWidth().weight(1F))

					Text(
						text = "学分: ${lessonInfo.credit}",
						style = MaterialTheme.typography.bodySmall,
						color = colorSecondaryText,
					)
				}

				AnimatedVisibility(visible = isExpanded){
					GetMarks.MarkItems(mark = lessonMark){
						if(lessonMark !== Mark.EMPTY_MARK){
							Text(
								text = "发布时间: ${DateUtils.YMD_HMS.format(lessonMark.time)}",
								modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
								style = MaterialTheme.typography.bodySmall,
								color = colorSecondaryText
							)
						}
					}
				}
			}
		}
	}
}