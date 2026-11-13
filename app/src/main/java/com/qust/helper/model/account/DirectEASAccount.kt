/*
 * 青科助手（修改版） —— 基于开源项目 QUST-Assistant 修改而成
 *
 * 原项目版权所有 (C) 2891954521  (https://github.com/2891954521/QUST-Assistant)
 * 原始开源许可: GNU General Public License v3.0 (GPL-3.0), 许可原文见 LICENSE 文件, 保持完整未改动。
 *
 * 修改版版权所有 (C) 2026 zhangzhenzyc  (https://github.com/zhangzhenzyc)
 * 本修改版同样以 GPL-3.0 发布; 附加版权声明见 NOTICE.md —— 两份版权声明相互独立、同时生效。
 */

package com.qust.helper.model.account

import com.qust.helper.data.api.QustApi

/**
 * 【本轮】教务系统**原网址**（直连 jwglxt）通道
 *
 * 教务有两条登录路径：
 * 1. 教务系统原网址：`https://jwglxt.qust.edu.cn/jwglxt/...`（本类，学号 + 教务系统密码）；
 * 2. 数字青科里的「教务管理系统」：[VpnEASAccount]（统一身份 + WebVPN + SSO）。
 *
 * 两条路都保留：登录时先试①（原网址），①进不去（被拦 / 网络 / 密码不对）再自动改走②，
 * 用户不需要自己判断该用哪一条。
 *
 * 业务实现全部继承自 [EASAccount]，本类只换一套独立的 Cookie 存储键
 * （`eaDirectCookie`）—— 以前两条通道共用 `eaCookie`，一边退出登录会把另一边的会话一起清掉。
 */
class DirectEASAccount private constructor(): EASAccount("eaDirectCookie") {

	companion object {
		fun getInstance(): DirectEASAccount {
			return Instance.INSTANCE
		}
	}

	private object Instance {
		val INSTANCE = DirectEASAccount()
	}

	/**
	 * 切换教务节点（jwglxt / jwglxt1...）时直连通道也要跟着换主机，
	 * 否则用户换了节点，直连还在打旧地址。
	 *
	 * 只动直连自己的会话位与 Cookie，不影响数字青科通道；`EA_HOST` 已由父类/另一条通道写盘，
	 * 这里不重复写，避免两边互相覆盖。
	 */
	override fun changeHost(index: Int) {
		if(index >= QustApi.EA_HOSTS.size) return
		isLogin = false
		cookieJar.clearCookies()
		host = QustApi.EA_HOSTS[index]
	}
}
