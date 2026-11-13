/*
 * 青科助手（修改版） —— 基于开源项目 QUST-Assistant 修改而成
 *
 * 原项目版权所有 (C) 2891954521  (https://github.com/2891954521/QUST-Assistant)
 * 原始开源许可: GNU General Public License v3.0 (GPL-3.0), 许可原文见 LICENSE 文件, 保持完整未改动。
 *
 * 修改版版权所有 (C) 2026 zhangzhenzyc  (https://github.com/zhangzhenzyc)
 * 本修改版同样以 GPL-3.0 发布; 附加版权声明见 NOTICE.md —— 两份版权声明相互独立、同时生效。
 */

package com.qust.helper.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Set of Material typography styles to start with
val Typography = Typography(
	bodyLarge = TextStyle(
		fontFamily = FontFamily.Default,
		fontWeight = FontWeight.Normal,
		fontSize = 16.sp,
		lineHeight = 24.sp,
		letterSpacing = 0.5.sp
	)
	/* Other default text styles to override
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
    */
)

/**
 * 【第五十八轮·修"时间表/课表里的时间有一点点没对齐"】
 *
 * 系统字体(MiSans/Roboto 实测都是)默认用**比例数字**: "1" 比 "0" 窄一点点,
 * 于是 "08:00" 和 "10:00" 虽然字数相同, 冒号却不在同一列 —— 只差 1~2dp,
 * 一列时间排下来就是肉眼可见的"参差不齐"(用户原话"有一点点的小误差")。
 *
 * `tnum`(tabular numbers)把每个数字都换成等宽字形, 冒号、" - " 于是严格对齐;
 * 数字本身的可读性不变, 只是字宽统一。字体不支持该特性时系统会原样忽略, 不会出错。
 */
fun TextStyle.tnum(): TextStyle =
	if(fontFeatureSettings == null || fontFeatureSettings!!.isEmpty()) copy(fontFeatureSettings = "tnum")
	else this