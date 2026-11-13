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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.qust.helper.R
import com.qust.helper.data.Data.TermName
import com.qust.helper.ui.widget.ListPicker

@Composable
fun <T> EasQuery(
	pickYear: MutableState<Int>,
	items: Array<T>,
	termName: Array<String> = TermName,
	itemView: @Composable (T) -> Unit,
	onYearPick: (Int) -> Unit = { },
	doQuery: () -> Unit,
) {
	var pick by pickYear

	Column(
		modifier = Modifier.fillMaxSize()
	) {
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.Center,
			verticalAlignment = Alignment.CenterVertically
		) {

			Text(
				modifier = Modifier.padding(8.dp),
				text = stringResource(id = R.string.text_term)
			)

			Box(modifier = Modifier.padding(16.dp, 0.dp)){
				ListPicker.NumberPicker(
					value = pick,
					range = termName.indices,
					label = { termName[it] },
					onValueChange = {
						pick = it
						onYearPick(it)
					},
					horizontalPadding = 8.dp
				)
			}

			Button(onClick = { doQuery() }) {
				Text(text = stringResource(id = R.string.text_query))
			}
		}

		// 【第六十八轮·按要求】"最下面的条目的下面的阴影不够自然, 范围不够大, 看起来像是有个白线"
		// 根因: LazyColumn 默认没有底部留白, 最后一条卡片的投影正好压在视口边缘被**裁掉**,
		// 于是卡底白边 + 页面浅灰底直接相接, 就是那条"白线"。给底部留 14dp 让投影画得完。
		LazyColumn(contentPadding = PaddingValues(bottom = 14.dp)) {
			items(items.size) { index ->
				itemView(items[index])
			}
		}
	}
}