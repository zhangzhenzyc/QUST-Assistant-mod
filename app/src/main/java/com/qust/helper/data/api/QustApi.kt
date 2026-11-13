/*
 * 青科助手（修改版） —— 基于开源项目 QUST-Assistant 修改而成
 *
 * 原项目版权所有 (C) 2891954521  (https://github.com/2891954521/QUST-Assistant)
 * 原始开源许可: GNU General Public License v3.0 (GPL-3.0), 许可原文见 LICENSE 文件, 保持完整未改动。
 *
 * 修改版版权所有 (C) 2026 zhangzhenzyc  (https://github.com/zhangzhenzyc)
 * 本修改版同样以 GPL-3.0 发布; 附加版权声明见 NOTICE.md —— 两份版权声明相互独立、同时生效。
 */

package com.qust.helper.data.api

object QustApi {
	/**
	 * 数字青科VPN HOST
	 */
	const val VPN_HOST = "wvpn.qust.edu.cn"

	/**
	 * 教务系统HOST
	 */
	val EA_HOSTS = arrayOf(
		"jwglxt.qust.edu.cn",
		"jwglxt1.qust.edu.cn",
		"jwglxt2.qust.edu.cn",
		"jwglxt3.qust.edu.cn",
		"jwglxt4.qust.edu.cn",
		"jwglxt5.qust.edu.cn",
		"jwglxt6.qust.edu.cn"
	)


	/**
	 * 教务登录
	 *
	 * Get:
	 * - 登录界面
	 *
	 * Post:
	 * - csrftoken: HTML里拿
	 * - language: 	zh_CN
	 * - yhm:		用户名
	 * - mm:		RSA加密后的密码
	 */
	const val EA_LOGIN = "jwglxt/xtgl/login_slogin.html"

	/**
	 * 教务登录，获取RSA公钥
	 */
	const val EA_LOGIN_PUBLIC_KEY = "jwglxt/xtgl/login_getPublicKey.html"

	/**
	 *
	 */
	const val EA_MAIN_MENU = "jwglxt/xtgl/index_initMenu.html"

	/**
	 * 教务系统消息查询
	 *
	 * Post:
	 * - queryModel.showCount: 一页显示几条
	 * - queryModel.currentPage: 第几页
	 * - queryModel.sortName: cjsj
	 * - queryModel.sortOrder: desc
	 */
	const val EA_SYSTEM_NOTICE = "jwglxt/xtgl/index_cxDbsy.html?doType=query"

	/**
	 * 学年信息
	 */
	const val EA_YEAR_DATA = "jwglxt/xtgl/index_cxAreaFive.html?localeKey=zh_CN&gnmkdm=index"

	/**
	 * 查询学生课表
	 */
	const val GET_LESSON_TABLE = "jwglxt/kbcx/xskbcx_cxXsgrkb.html"

	/**
	 * 成绩查询
	 */
	const val GET_MARK = "jwglxt/cjcx/cjcx_cxXsgrcj.html?doType=query"

	/**
	 * 成绩明细查询
	 */
	const val GET_MARK_DETAIL = "jwglxt/cjcx/cjcx_cxXsKccjList.html"

	/**
	 * 考试查询
	 */
	const val GET_EXAM = "jwglxt/kwgl/kscx_cxXsksxxIndex.html?doType=query"

	/**
	 * 学业情况查询界面
	 */
	const val ACADEMIC_PAGE = "jwglxt/xsxy/xsxyqk_cxXsxyqkIndex.html?gnmkdm=N105515&layout=default"

	/**
	 * 学业情况查询 - 课程信息
	 */
	const val ACADEMIC_INFO = "jwglxt/xsxy/xsxyqk_cxJxzxjhxfyqKcxx.html"

	/**
	 * 【第二十四轮】学生已选课程(自主选课结果)
	 *
	 * 通识任选类课程不在培养方案(教学执行计划)里, 学业情况接口对这类模块返回空;
	 * 学生的实际课程来自"学生选课"记录 —— 用该接口按学期查询已选课程。
	 *
	 * Post:
	 * - xkxnm: 学年代码(如 2026)
	 * - xkxqm: 学期代码(3=秋季, 12=春季)
	 */
	const val CHOOSED_COURSES = "jwglxt/xsxk/zzxkyzb_cxZzxkYzbChoosedDisplay.html?gnmkdm=N253512"

}
