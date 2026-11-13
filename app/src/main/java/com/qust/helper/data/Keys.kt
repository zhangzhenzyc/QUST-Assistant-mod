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

object Keys {

	/**
	 * 是否是第一次使用
	 */
	const val IS_FIRST_USE = "isFirstUse"

	/**
	 * 教务节点
	 */
	const val EA_HOST = "eaHost"

	/**
	 * 入学时间
	 */
	const val ENTRANCE_TIME = "key_entrance_time"

	/**
	 * 【本轮】自定义昵称
	 *
	 * 只在「我」页面显示, 与教务/数字青科的账号是两回事:
	 * 留空(或删除该键)时回退显示账号 / "未登录"。
	 */
	const val NICKNAME = "key_nickname"

	/**
	 * 教务系统(VPN 通道)是否已登录
	 *
	 * 走 WebVPN 时教务与"数字青科"共用一套会话, 必须单独记一个教务自己的状态位,
	 * 否则退出教务后仍能被静默恢复、照样查到课表。
	 */
	const val EAS_SESSION_VALID = "easSessionValid"

	/**
	 * 【本轮】教务当前走的是哪条通道
	 *
	 * `true`  = ① 教务系统原网址(`jwglxt.qust.edu.cn`, 学号 + 教务系统密码);
	 * `false` = ② 数字青科里的「教务管理系统」(WebVPN + SSO)。
	 *
	 * 登录时先试①, ①进不去(被拦 / 网络不通 / 密码不对)再自动改走②; 这个值记录的是
	 * **上一次真正跑通的那条**, 后续查询(课表/成绩/学业/考试/调课)都按它选走线,
	 * 免得每次请求都去试探一遍。
	 */
	const val EAS_DIRECT_CHANNEL = "easDirectChannel"

	/**
	 * 显示所有课程
	 */
	const val KEY_SHOW_ALL_LESSON = "key_show_all_lesson"

	/**
	 * 隐藏教师
	 */
	const val KEY_HIDE_TEACHER = "key_hide_teacher"

	/**
	 * 【第六十一轮·按要求】桌面小组件外观 —— 主题。
	 *
	 * 取值 [com.qust.helper.ui.appwidget.WidgetStyleStore.THEME_GLASS](玻璃) 或
	 * `THEME_SOLID`(纯色)。用户原话:"桌面小组件添加的按钮旁可选择小组件的主题,
	 * 一个是玻璃, 另一个是纯色"。
	 */
	const val KEY_WIDGET_THEME = "key_widget_theme"

	/**
	 * 【第六十一轮·按要求】桌面小组件里**非课程内容**的文字颜色(ARGB)。
	 *
	 * 只作用于"第 N 周 / 日期 / 时间栏 / 空态提示"这些文字; 课程色块里的课名/教室/教师
	 * 仍跟随课程配色(否则一块彩色底上出现黑字, 反而认不出是哪门课)。
	 */
	const val KEY_WIDGET_TEXT_COLOR = "key_widget_text_color"

	/**
	 * 【第一百一十一轮】小组件**高度校准**(百分比整数, 100 = 不校准)。
	 *
	 * 背景(像素级实测 + 渲染诊断双重定案): ColorOS 在用户**更改桌面布局**(如 4×5 → 5×6)后
	 * 不会同步更新已放置小组件的 options(SIZES/MIN/MAX 全部是旧布局的值, 本机实测上报
	 * 320×462dp、物理只有 ≈259×428dp), 且 resizeMode=none 用户无法靠缩放触发刷新 ——
	 * 于是按 462dp 排版的固定行高在 428dp 的物理卡里正好溢出一行, 第 11 节被裁。
	 * 启动器会按物理单元格**重新测量**根布局, 横向的权重列自动适应了(所以列永远看得全),
	 * 但纵向行高是固定 dp, 只能在排版源头上按比例预留 —— 这个百分比就是那个预留旋钮。
	 *
	 * 取值 100/96/92/88(见 WidgetPage 的校准卡); 【第一百一十二轮】按用户实测把默认从 100
	 * 改成 **92**("默认的不行, 92%刚刚好") —— 92% 档在陈旧 7.4% 的卡上正好留出余量,
	 * 尺寸准确的启动器底部会多 ~34dp 空白, 重加小组件后可调回 100%。
	 */
	const val KEY_WIDGET_HEIGHT_CAL = "key_widget_height_cal"

	/**
	 * 课表时间表 0：冬季， 1：夏季
	 */
	const val KEY_TIME_TABLE = "key_time_table"

	/**
	 * 用户是否**手动**切换过时间表
	 *
	 * 没有这个标记时, 保存的 [KEY_TIME_TABLE] 可能是"首次启动自动按季节算出来的默认值",
	 * 升级后需要重新按季节校正一次; 用户一旦自己选过, 之后永远以用户的选择为准。
	 */
	const val KEY_TIME_TABLE_USER_SET = "key_time_table_user_set"

	/**
	 * 锁定课表
	 */
	const val KEY_LOCK_LESSON = "key_lock_lesson"


	/**
	 * 主题跟随系统
	 */
	const val KEY_THEME_FOLLOW_SYSTEM = "key_theme_follow_system"
	/**
	 * 暗色模式
	 */
	const val KEY_THEME_DARK = "key_theme_dark"

	/**
	 * 学业查询缓存版本号
	 *
	 * 学业数据会缓存进 Room, 之后进页面只读缓存; 解析逻辑升级后旧缓存不会自动更新,
	 * 用户会一直看到旧的解析结果("通识任选还是没有"的根因)。
	 * 每次改解析逻辑就把 [com.qust.helper.viewmodel.eas.GetAcademicViewModel] 里的
	 * CURRENT_ACADEMIC_CACHE_VERSION +1, 旧缓存进页面时自动重新查询一次。
	 */
	const val ACADEMIC_CACHE_VERSION = "academicCacheVersion"


	/**
	 * 自动检查更新
	 */
	const val KEY_AUTO_UPDATE = "key_auto_update"

	/**
	 * 上次检查更新的时间
	 */
	const val LAST_UPDATE_TIME = "last_update_time"

	/**
	 * 上一个教务公告的ID
	 */
	const val LAST_NOTICE_ID = "last_notice_id"

	const val EAS_ACCOUNT = "eas_account"
	/** 【修改版】是否已提示过“自启动/后台运行”设置 */
	const val AUTOSTART_PROMPTED = "autostart_prompted"

	const val EAS_PASSWORD = "eas_password"

	const val IPASS_ACCOUNT = "ipass_account"
	const val IPASS_PASSWORD = "ipass_password"

	/**
	 * 【本轮】"串存副本清理"是否已经做过。
	 *
	 * 这个清理只能做**一次**: 教务系统的密码本来就可能与统一身份密码相同,
	 * 每次启动都比对会误删用户真正保存的教务凭据。
	 */
	const val ACCOUNT_MIGRATION_DONE = "account_migration_done"

	const val DRINK_ACCOUNT = "drink_account"
	const val DRINK_PASSWORD = "drink_password"

	/** 【第三十六轮】学校邮箱的独立账号与密码(不与教务/统一身份共用) */
	const val MAIL_ACCOUNT = "mail_account"
	const val MAIL_PASSWORD = "mail_password"

	object Page {

		const val MyPage = "myPage"

		const val DailyLessonPage = "dailyLessonPage"
		const val TermLessonPage = "termLessonPage"
		const val DayEditPage = "dayEditPage"
		const val AdjustPage = "adjustPage"

		/** 时间表管理(新增/重命名/编辑时间/恢复默认/删除) */
		const val TimeTablePage = "timeTablePage"

		const val GetLessonPage = "getLessonPage"
		const val GetMarksPage = "getMarksPage"
		const val GetNoticePage = "getNoticePage"
		const val GetAcademicPage = "getAcademicPage"
		const val GetExamsPage = "getExamsPage"

		const val SportTestPage = "SportTestPage"

		/** 【第二十九轮】座位预约(zyyy.qust.edu.cn, 与智慧体测同款登录) */
		const val SeatReservePage = "seatReservePage"

		const val DrinkPage = "drinkPage"

		/** 【第二十六轮】科大官网(学校官网网页入口) */
		const val OfficialSitePage = "officialSitePage"

		/** 【第三十五轮】学校邮箱(mail.qust.edu.cn, 独立邮箱账号) */
		const val MailPage = "mailPage"

		/** 【第三十六轮】学校邮箱登陆页(账号管理的学校邮箱登录) */
		const val MailLogin = "mailLogin"

		/** 【第四十六轮】饮水码登陆页(账号管理里"更换账号"入口: 只显示登录界面, 不显示饮水码) */
		const val DrinkLogin = "drinkLogin"

		const val EasWebPage = "easWebPage"
		const val IpassWebPage = "ipassWebPage"
		const val XsxkWebPage = "xsxkWebPage"

		const val AccountManager = "accountManager"

		const val EasLogin = "easLogin"
		const val VpnLoginPage = "vpnLoginPage"

		const val SettingPage = "setting"
		/** 课表设置(独立入口, 与"我"/侧边栏栏目共用) */
		const val LessonTableSettingPage = "lessonTableSetting"

		/** 教务查询设置(独立入口, 与"我"/侧边栏栏目共用) */
		const val EasSettingPage = "easSetting"

		/** 【本轮·新增】桌面小组件入口(一键把课表/饮水码固定到桌面) */
		const val WidgetPage = "widgetPage"

		const val UpdatePage = "updatePage"

		const val UserAgreementPage = "userAgreementPage"
		const val PolicyPage = "policyPage"
		const val DisclaimerPage = "disclaimerPage"
		/** 【第七十一轮】开源许可(原项目版权声明 + GPL-3.0 全文)与修改说明 */
		const val LicensePage = "licensePage"
		const val ModificationPage = "modificationPage"

	}
}