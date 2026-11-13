/*
 * 青科助手（修改版） —— 基于开源项目 QUST-Assistant 修改而成
 *
 * 原项目版权所有 (C) 2891954521  (https://github.com/2891954521/QUST-Assistant)
 * 原始开源许可: GNU General Public License v3.0 (GPL-3.0), 许可原文见 LICENSE 文件, 保持完整未改动。
 *
 * 修改版版权所有 (C) 2026 zhangzhenzyc  (https://github.com/zhangzhenzyc)
 * 本修改版同样以 GPL-3.0 发布; 附加版权声明见 NOTICE.md —— 两份版权声明相互独立、同时生效。
 */

package com.qust.helper.ui.page.app

import android.graphics.Color
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.qust.helper.R
import com.qust.helper.data.Keys
import com.qust.helper.data.Page

object AppPage {

	val UserAgreementPage = Page(Keys.Page.UserAgreementPage, "用户协议") { _, padding, _, _ ->
		Box(modifier = Modifier.padding(padding)){
			AndroidView(
				factory = { context ->
					val webView = WebView(context)
					webView.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
					webView.setBackgroundColor(Color.TRANSPARENT)
					webView.isVerticalScrollBarEnabled = true
					webView.settings.also { webSettings ->
						webSettings.allowFileAccess = false
						webSettings.loadWithOverviewMode = true
						webSettings.setSupportZoom(true)
						webSettings.displayZoomControls = true
					}
					webView.loadUrl("file:///android_asset/userAgreement.html")
					webView
				}
			)
		}
	}

	val PolicyPage = Page(Keys.Page.PolicyPage,"隐私政策") { _, padding, _, _ ->
		// 与「设置 → 隐私政策」保持完全一致的渲染(bodyMedium + 加黑标题 + 同字号),
		// 不再用 WebView(默认字号 16 且与原生不一致)。
		val context = LocalContext.current
		val policyText = remember { SettingPage.loadPolicyPlainText(context) }
		Column(
			modifier = Modifier
				.fillMaxSize()
				.padding(padding)
				.verticalScroll(rememberScrollState())
				.padding(16.dp)
		) {
			Text(
				text = "隐私政策",
				style = MaterialTheme.typography.titleLarge,
				fontWeight = FontWeight.Bold,
				modifier = Modifier.padding(bottom = 12.dp)
			)
			Text(
				text = policyText,
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.secondary
			)
		}
	}

	/** 免责声明独立界面(设置/启动页点击进入, 不再用展开收起) */
	val DisclaimerPage = Page(Keys.Page.DisclaimerPage,"免责声明") { _, padding, _, _ ->
		// 【本轮】字体与字号与「隐私政策」独立界面保持完全一致: bodyMedium + secondary 色, 不加粗
		val text = stringResource(R.string.disclaimer_text)
		Column(
			modifier = Modifier
				.fillMaxSize()
				.padding(padding)
				.verticalScroll(rememberScrollState())
				.padding(16.dp)
		) {
			Text(
				text = "免责声明",
				style = MaterialTheme.typography.titleLarge,
				fontWeight = FontWeight.Bold,
				modifier = Modifier.padding(bottom = 12.dp)
			)
			Text(
				text = text,
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.secondary
			)
		}
	}
	/**
	 * 【第一百轮·按要求】开源许可页分为**三块互不合并**的内容:
	 *  ① 原项目版权声明 —— 保留原样, 不删除、不修改;
	 *  ② GPL-3.0 许可原文(assets/gpl-3.0.txt, 软件初始自带) —— 保持完整, 不做修改、替换或升级;
	 *  ③ 附加版权声明(修改版) —— 独立区域, 与前两者相互分隔: 不替代、不削弱
	 *     原始开源许可以及原作者版权声明的法律效力, 两份版权声明同时生效。
	 */
	val LicensePage = Page(Keys.Page.LicensePage, "开源许可") { _, padding, _, _ ->
		val context = LocalContext.current
		val gplText = remember {
			runCatching { context.assets.open("gpl-3.0.txt").bufferedReader().use { it.readText() } }
				.getOrDefault("（未能读取 GPL-3.0 许可文本，请查看项目中的 LICENSE 文件）")
		}
		Column(
			modifier = Modifier
				.fillMaxSize()
				.padding(padding)
				.verticalScroll(rememberScrollState())
				.padding(16.dp)
		) {
			Text(
				text = "开源许可",
				style = MaterialTheme.typography.titleLarge,
				fontWeight = FontWeight.Bold,
				modifier = Modifier.padding(bottom = 12.dp)
			)
			// —— 第一部分: 原项目版权声明(原样保留) ——
			Text(
				text = "原项目版权声明\n" +
					"本应用基于开源项目 QUST-Assistant（青科助手）修改而成。\n" +
					"版权所有 (C) 2891954521\n" +
					"原项目地址：https://github.com/2891954521/QUST-Assistant\n" +
					"原项目以 GNU General Public License v3.0（GPL-3.0）授权。" +
					"以下为软件初始自带的开源许可原文，保持完整，不做修改、替换或升级：",
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.secondary
			)
			// —— 第二部分: GPL-3.0 许可原文(完整, 原样) ——
			Text(
				text = gplText,
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.secondary,
				modifier = Modifier.padding(top = 12.dp)
			)
			// —— 分隔 ——
			Text(
				text = "————————————",
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.secondary,
				modifier = Modifier.padding(top = 20.dp, bottom = 20.dp)
			)
			// —— 第三部分: 附加版权声明(修改版, 独立区域) ——
			Text(
				text = "附加版权声明（修改版）\n" +
					"本软件为基于上述原项目修改而成的版本：青科助手（修改版）。\n" +
					"版权所有 (C) 2026 zhangzhenzyc（GitHub 账号：https://github.com/zhangzhenzyc）\n\n" +
					"本附加声明为独立声明，与上述原始开源许可、原作者版权声明相互分隔，不合并、不混淆：\n" +
					// 【第一百零二轮·按要求】去掉前三行行首的"·"
					"原始 GPL-3.0 许可条款与原作者版权内容维持原样，未做任何改动；\n" +
					"本附加声明不替代、不削弱原始开源许可以及原作者版权声明的法律效力；\n" +
					"两份版权声明同时生效。",
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.secondary
			)
		}
	}

	/** 【第七十一轮】修改说明: 相对原项目 v3.6.0127 的主要修改内容 */
	val ModificationPage = Page(Keys.Page.ModificationPage, "修改说明") { _, padding, _, _ ->
		Column(
			modifier = Modifier
				.fillMaxSize()
				.padding(padding)
				.verticalScroll(rememberScrollState())
				.padding(16.dp)
		) {
			Text(
				text = "修改说明",
				style = MaterialTheme.typography.titleLarge,
				fontWeight = FontWeight.Bold,
				modifier = Modifier.padding(bottom = 12.dp)
			)
			Text(
				text = MODIFICATION_TEXT,
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.secondary
			)
		}
	}

	private const val MODIFICATION_TEXT = "本应用基于开源项目 QUST-Assistant（青科助手）v3.6.0127 修改而成，主要修改内容如下：\n\n" +
		"一、账号与自动登录\n" +
		"1. 教务系统/数字青科登录流程与网页浏览体验优化（教务保留两条登录路径：① 教务系统原网址「学号 + 教务系统密码」；② 数字青科里的「教务系统」，①进不去自动改走②；\n" +
		"2. 学校邮箱支持半自动登录：自动填充账号密码，手动二次验证；\n\n" +
		"二、课表功能\n" +
		"3. 课表查询/学期课表显示与手势优化；\n" +
		"4. 图片识别调休、官网调休、教务通知调课；\n" +
		"5. 恢复默认课表可选\u201c最新原始课表 / 最新调休课表\u201d；\n" +
		"6. 编辑课表：从课表选择课程填充、复制粘贴课程、单独编辑此次课程、多选编辑（勾选多个上课周拆分）、删除全部课程、删除课程等；\n\n" +
		"三、界面与体验\n" +
		"7. 网页加载进度条与\u201c正在登录\u201d提示样式统一；\n" +
		"8. 提示气泡时长按字数等比缩短（基准 4.5 秒 / 25字）；\n" +
		"9. 课表查询/成绩查询/学业查询/自主选课/座位预约/科大官网/学校邮箱等页面图标重新绘制与配色；\n\n" +
		"四、其他\n" +
		"10. 设置中新增\u201c免责声明\u201d；\n" +
		"11. 新增时间表一系列功能；\n" +
		"12. 新增更换头像功能；\n" +
		"13. 新增更换昵称功能；\n" +
		"14. 新增小组件功能；\n" +
		"15. 删除原软件部分功能；\n\n" +
		"本修改版遵循原项目 GPL-3.0 许可证开源，详见\u201c开源许可\u201d。"

}