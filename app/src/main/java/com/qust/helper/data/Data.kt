/*
 * 青科助手（修改版） —— 基于开源项目 QUST-Assistant 修改而成
 *
 * 原项目版权所有 (C) 2891954521  (https://github.com/2891954521/QUST-Assistant)
 * 原始开源许可: GNU General Public License v3.0 (GPL-3.0), 许可原文见 LICENSE 文件, 保持完整未改动。
 *
 * 修改版版权所有 (C) 2026 zhangzhenzyc  (https://github.com/zhangzhenzyc)
 * 本修改版同样以 GPL-3.0 发布; 附加版权声明见 NOTICE.md —— 两份版权声明相互独立、同时生效。
 */

package com.qust.helper.data

import com.qust.helper.R
import com.qust.helper.ui.page.DrinkPage
import com.qust.helper.ui.page.MyPage
import com.qust.helper.ui.page.account.AccountManagerPage
import com.qust.helper.ui.page.account.LoginPage
import com.qust.helper.ui.page.app.AppPage
import com.qust.helper.ui.page.app.SettingPage
import com.qust.helper.ui.page.app.UpdatePage
import com.qust.helper.ui.page.app.WidgetPage
import com.qust.helper.ui.page.eas.GetAcademic
import com.qust.helper.ui.page.eas.GetExams
import com.qust.helper.ui.page.eas.GetLessonTable
import com.qust.helper.ui.page.eas.GetMarks
import com.qust.helper.ui.page.eas.GetNotice
import com.qust.helper.ui.page.lesson.DailyLesson
import com.qust.helper.ui.page.lesson.DayEdit
import com.qust.helper.ui.page.lesson.ScheduleAdjust
import com.qust.helper.ui.page.lesson.TermLesson
import com.qust.helper.ui.page.lesson.TimeTablePage
import com.qust.helper.ui.page.web.SchoolWebPages
import com.qust.helper.ui.page.web.WebPage

object Data {

	// 侧边栏栏目与「我」页面保持一致: 课表 / 教务系统 / 数字青科 / 其他
	// 显式声明为 DrawerPageGroup: 现在全是分组, 否则类型会被推断成 PageGroup 而让抽屉里的分支编译失败
	val pageGroups: Array<DrawerPageGroup> = arrayOf(
		DrawerPageGroup.PageGroup(name = "课表", iconRes = R.drawable.ic_lesson_search, pages = arrayOf(
			Keys.Page.GetLessonPage,
			Keys.Page.LessonTableSettingPage
		)),

		DrawerPageGroup.PageGroup(name = "教务系统", iconRes = R.drawable.ic_school_yellow, pages = arrayOf(
			Keys.Page.EasWebPage,
			Keys.Page.GetNoticePage,
			Keys.Page.GetMarksPage,
			Keys.Page.GetAcademicPage,
			Keys.Page.GetExamsPage,
			Keys.Page.XsxkWebPage,
			Keys.Page.EasSettingPage
		)),

		DrawerPageGroup.PageGroup(name = "数字青科", iconRes = R.drawable.ic_ipass_blue, pages = arrayOf(
			Keys.Page.IpassWebPage,
			Keys.Page.SportTestPage,
			// 【第二十九轮】座位预约
			Keys.Page.SeatReservePage
		)),

		// "设置/饮水码"原来在侧边栏是直接可见的, 合并进分组后默认展开, 避免多点一次
		// 【第三十五轮】科大官网右边新增"学校邮箱"
		DrawerPageGroup.PageGroup(name = "其他", iconRes = R.drawable.ic_grid_view_blue, pages = arrayOf(
			Keys.Page.SettingPage,
			Keys.Page.DrinkPage,
			// 【第六十八轮·二·按要求】与「我 → 其他」宫格顺序严格一致: 饮水码 → 小组件 → 科大官网
			Keys.Page.WidgetPage,
			Keys.Page.OfficialSitePage,
			Keys.Page.MailPage
		)).apply { expand.value = true }
	)

	val Pages = mapOf(
		Keys.Page.MyPage to MyPage.MyPage,

		Keys.Page.DailyLessonPage to DailyLesson.DailyLessonPage,
		Keys.Page.TermLessonPage to TermLesson.TermLessonPage,

		Keys.Page.DayEditPage to DayEdit.DayEditPageObj,
		Keys.Page.AdjustPage to ScheduleAdjust.AdjustPage,
		Keys.Page.TimeTablePage to TimeTablePage.TimeTablePage,

		Keys.Page.GetNoticePage to GetNotice.GetNoticePage,
		Keys.Page.GetLessonPage to GetLessonTable.GetLessonPage,
		Keys.Page.GetMarksPage to GetMarks.GetMarksPage,
		Keys.Page.GetAcademicPage to GetAcademic.GetAcademicPage,
		Keys.Page.GetExamsPage to GetExams.GetExamsPage,

		// 智慧体测: 与数字青科一致, 自动登录 WebVPN(账号管理里的统一身份),
		// 未登录则跳转账号管理登录页
		Keys.Page.SportTestPage to WebPage.TiyuWebPage,

		// 【第二十九轮】座位预约: 与智慧体测同款(统一身份 + WebVPN)
		Keys.Page.SeatReservePage to WebPage.SeatReservePage,

		Keys.Page.DrinkPage to DrinkPage.DrinkPage,

		// 【第二十六轮】科大官网
		Keys.Page.OfficialSitePage to SchoolWebPages.OfficialSitePage,

		// 【第三十五轮】学校邮箱(WebVPN 自动登录)
		Keys.Page.MailPage to WebPage.MailPage,

		Keys.Page.EasWebPage to WebPage.EasWebPage,
		Keys.Page.IpassWebPage to WebPage.IpassWebPage,
		Keys.Page.XsxkWebPage to WebPage.XsxkWebPage,

		Keys.Page.AccountManager to AccountManagerPage.AccountManagerPage,
		Keys.Page.EasLogin to LoginPage.EasLoginPage,
		Keys.Page.VpnLoginPage to LoginPage.VpnLoginPage,
		// 【第三十六轮】学校邮箱登陆(独立邮箱账号)
		Keys.Page.MailLogin to LoginPage.MailLoginPage,

		// 【第四十六轮】饮水码登陆(账号管理"更换账号"入口, 只显示登录界面)
		Keys.Page.DrinkLogin to DrinkPage.DrinkLoginPage,

		Keys.Page.SettingPage to SettingPage.SettingPage,
		// 【本轮·新增】桌面小组件入口
		Keys.Page.WidgetPage to WidgetPage.WidgetPage,
		Keys.Page.LessonTableSettingPage to SettingPage.LessonTableSettingPage,
		Keys.Page.EasSettingPage to SettingPage.EasSettingPage,
		Keys.Page.UpdatePage to UpdatePage.UpdatePage,

		Keys.Page.UserAgreementPage to AppPage.UserAgreementPage,
		Keys.Page.PolicyPage to AppPage.PolicyPage,
		Keys.Page.DisclaimerPage to AppPage.DisclaimerPage,
		Keys.Page.LicensePage to AppPage.LicensePage,
		Keys.Page.ModificationPage to AppPage.ModificationPage,
	)

	val bottomPage = arrayOf(
		TermLesson.TermLessonPage,
		MyPage.MyPage,
	)

	val TermName = arrayOf(
		"大一 上学期", "大一 下学期",
		"大二 上学期", "大二 下学期",
		"大三 上学期", "大三 下学期",
		"大四 上学期", "大四 下学期"
	)

	val WEEK_STRING = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
}

