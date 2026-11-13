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

/**
 * 【第六十七轮·按要求】"上一次教务登录到底为什么没成"。
 *
 * ────────────────────────────────────────────────────────────────────────
 * 为什么需要这个东西
 * ────────────────────────────────────────────────────────────────────────
 * 用户原话: "不是静默登录教务系统, 有前提, 前提是教务系统因为外部原因无法正常登录"。
 *
 * 也就是说"自动用数字青科登录教务"**不能无条件做**:
 *  · 用户只是没登录过教务(没有凭据 / 会话过期) -> 应该正常弹「教务登陆」让他输学号密码;
 *  · 教务**因为外部原因**进不去(服务端故障 / 维护 / 403 / 网络不通 / 页面结构变了) ->
 *    这时输密码也没用, 才该改走"数字青科 → 业务应用 → 教务管理系统"这条已经通了的路。
 *
 * 而 `NeedLoginException` 本身**不携带原因** —— 界面拿到它只知道"要登录", 分不清是
 * "没凭据"还是"服务器坏了"。以前就是不分, 所以只会一律弹登录页。
 *
 * 这个对象补上这一位: 谁做的登录, 谁就把原因记进来; 界面层只读 [external] 一个布尔。
 * ────────────────────────────────────────────────────────────────────────
 */
object EasLoginState {

	/** 上一次教务登录没成的原因 */
	enum class Reason {
		/** 还不知道 / 没失败过 */
		UNKNOWN,

		/** 没有可用的凭据(从没登录过, 或凭据被清掉了) —— 该让用户输 */
		NO_CREDENTIAL,

		/** 凭据被服务端明确拒绝 —— 该让用户改密码 */
		BAD_CREDENTIAL,

		/** **外部原因**: 服务端故障 / 维护 / 403 / 网络不通 / 页面结构变化 —— 输密码也没用 */
		EXTERNAL
	}

	@Volatile
	var lastReason: Reason = Reason.UNKNOWN
		private set

	/** 给日志/气泡用的一句话(英文异常信息会被它替掉, 免得弹给用户看) */
	@Volatile
	var lastDetail: String = ""
		private set

	/** 教务是不是"因为外部原因"登不上 —— 界面层只认这一个判断 */
	val external: Boolean get() = lastReason == Reason.EXTERNAL

	fun mark(reason: Reason, detail: String = "") {
		lastReason = reason
		lastDetail = detail.take(160)
	}

	/** 登录成功后清掉上一条失败原因, 免得下一次误判成"外部原因" */
	fun clear() {
		lastReason = Reason.UNKNOWN
		lastDetail = ""
	}
}
