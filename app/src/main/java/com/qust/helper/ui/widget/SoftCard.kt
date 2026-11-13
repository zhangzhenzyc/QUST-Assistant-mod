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

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 【第六十八轮·按要求】列表卡片的"柔和大范围阴影"。
 *
 * 用户原话: "考试查询页和学业查询页最下面的条目的下面的阴影不够自然, 范围不够大,
 * 参考时间表条目的样式稍微修改一下, 看起来像是有个白线"。
 *
 * ────────────────────────────────────────────────────────────────────────
 * "像是有个白线" 的两个来源
 * ────────────────────────────────────────────────────────────────────────
 * ① **范围小**: 原来用 `Card(elevation = 6.dp)`。M3 的 elevation 阴影在 6dp 时模糊半径只有
 *    十几像素, 白色卡片 + 近白的页面底色, 看上去就是贴着卡片下沿的一条灰线, 不像"浮起来"。
 * ② **被裁掉**: 列表是 `LazyColumn`, 最后一条正好压在视口底边, 投影画不出去 —— 卡底的
 *    1px 白边直接和页面浅灰底相接, 边缘锐利, 那就是用户说的"白线"。
 *    (对应改动: `EasCompose.EasQuery` 与学业页的 LazyColumn 都加了底部 contentPadding。)
 *
 * ────────────────────────────────────────────────────────────────────────
 * 为什么是这几个参数
 * ────────────────────────────────────────────────────────────────────────
 * · `elevation = 12dp`: Android 的投影**透明度是随高度饱和**的(spot 最高约 19%), 抬高
 *   elevation 主要放大**模糊半径和扩散范围**, 不会越抬越黑。所以"范围不够大"就该抬高度,
 *   而不是加深颜色。
 * · `ambient/spot = 0xB3000000`: 平台会把传入颜色的 alpha 乘到它自己的阴影 alpha 上,
 *   0xB3(70%) 落在比 M3 默认稍淡一点的位置 —— 影更大但更柔, 不会发灰发脏。
 * · `clip = false`: 阴影必须画到卡片边界**外面**才叫阴影(默认 clip 会把它裁掉)。
 *
 * 用法(Card 必须同时把 elevation 设成 0, 否则会和这里叠成两层硬影):
 * ```
 * Card(
 *   modifier = Modifier.padding(8.dp).cardShadow(),
 *   shape = RoundedCornerShape(12.dp),
 *   elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
 * )
 * ```
 * 注意 `shape` 的圆角要和 `radiusDp` 一致, 否则阴影的轮廓和卡片对不上。
 */
fun Modifier.cardShadow(elevationDp: Int = 12, radiusDp: Int = 12): Modifier = this.shadow(
	elevation = elevationDp.dp,
	shape = RoundedCornerShape(radiusDp.dp),
	clip = false,
	ambientColor = Color(0xB3000000),
	spotColor = Color(0xB3000000)
)
