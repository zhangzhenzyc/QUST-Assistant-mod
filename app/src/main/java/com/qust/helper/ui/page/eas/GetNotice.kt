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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.qust.helper.R
import com.qust.helper.data.Keys
import com.qust.helper.data.Page
import com.qust.helper.data.eas.Notice
import com.qust.helper.ui.widget.AppWidgets
import com.qust.helper.ui.widget.Texts
import com.qust.helper.ui.widget.Toast
import com.qust.helper.viewmodel.eas.GetNoticeViewModel

object GetNotice {

	val GetNoticePage = Page(Keys.Page.GetNoticePage, "教务通知", iconRes = R.drawable.ic_notification) { activity, padding, navController, _v ->
		val viewModel by activity.viewModels<GetNoticeViewModel>()
		GetNotice(padding, viewModel, activity.toast, navController)
	}

	@Composable
	fun GetNotice(padding: PaddingValues, viewModel: GetNoticeViewModel, toast: Toast, navController: NavController){
		AppWidgets.CheckEasLogin(viewModel = viewModel, navController = navController)

		// 【本期】每次进入本页都刷新一次(先显示本地缓存, 后台静默更新):
		// 以前这里只在"列表为空"时才查, 而且列表级还有一个同样条件的自动查询 ——
		// 从系统通知点进来(Activity 被复用、ViewModel 还是旧的那一个)时既不查、也不会重新合成,
		// 于是页面一直空着, 必须手动下拉刷新才出现。现在只保留这一处触发, 且总是查。
		LaunchedEffect(Unit){
			viewModel.queryNotice()
		}

		Box(modifier = Modifier.padding(padding)){
			GetNoticeUI(
				hasRefresh = viewModel.hasRefresh,
				refreshing = viewModel.refreshing,
				notices = viewModel.notices.value,
				query = { viewModel.queryNotice() }
			)
		}
		toast.ToastContent(viewModel.toastContent)
	}

	@Composable
	@OptIn(ExperimentalMaterialApi::class)
	fun GetNoticeUI(hasRefresh: Boolean, refreshing: Boolean, notices: Array<Notice>, query: () -> Unit){
		val state = rememberPullRefreshState(refreshing = refreshing, onRefresh = { query() })

		Box(modifier = Modifier.fillMaxSize().pullRefresh(state)){
			LazyColumn(Modifier.fillMaxSize()){
				items(notices.size) { index ->
					NoticeItem(notices[index])
				}
			}
			// 【本期】空列表时给一句提示: 以前是纯白页面, 用户会以为"没加载出来"
			if(notices.isEmpty() && !refreshing){
				Column(
					modifier = Modifier.fillMaxSize().padding(top = 120.dp),
					horizontalAlignment = Alignment.CenterHorizontally
				) {
					Text(
						text = if(hasRefresh) "暂无教务通知，下拉可刷新" else "正在获取教务通知…",
						style = MaterialTheme.typography.bodyMedium,
						color = com.qust.helper.ui.theme.colorSecondaryText
					)
				}
			}
			PullRefreshIndicator(refreshing, state, Modifier.align(Alignment.TopCenter))
		}
	}

	@Composable
	fun NoticeItem(notice: Notice){
		Card(
			modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
			colors = CardDefaults.cardColors(
				containerColor = Color.White,
			),
			elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
		) {

			Column(modifier = Modifier.fillMaxWidth()) {

				Text(
					modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp),
					text = notice.content,
					style = MaterialTheme.typography.bodyMedium,
				)

				Texts.SingleLineText(
					text = notice.time,
					style = MaterialTheme.typography.bodySmall,
					textAlign = TextAlign.End,
					modifier = Modifier.fillMaxWidth()
				)
			}
		}
	}
}