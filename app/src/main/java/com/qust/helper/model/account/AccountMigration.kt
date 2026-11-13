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

import com.qust.helper.data.Keys
import com.qust.helper.data.Setting
import com.qust.helper.model.Logger

/**
 * 【本期】清理「数字青科的账号密码被串存到教务系统」的历史数据。
 *
 * 背景:
 * 早期版本直连教务系统(jwglxt), 登录教务时会把账号密码写进 `EAS_ACCOUNT` / `EAS_PASSWORD`;
 * 后来教务改成数字青科的一个业务应用, 这段"再存一份"的逻辑被沿用下来 ——
 * 于是用户在账号管理里只登录过一次数字青科, 「教务系统」那一栏也会显示学号,
 * 看起来就像是"教务系统保存了数字青科的账号和密码"。
 *
 * 现在 [VpnEASAccount.login] 只有在**走教务系统原网址登录成功**时才写教务账号位(那是用户真的填了
 * 教务系统密码), 但**设备上还留着历史版本串存进去的值**, 所以启动时做一次清理(幂等):
 * - 教务密码与数字青科密码相同 => 判定为串存的重复数据, 删除;
 * - 否则(用户自己另外填的教务系统密码) => 原样保留, 下次原网址登录仍能自动带出。
 *
 * 清理后教务功能不受影响: [VpnEASAccount.enterEasSystem] 在教务凭据为空时会回退到
 * 数字青科的统一身份账号, 仍然能"正常通过另一个网址(教务系统登录页)直接登录"。
 */
object AccountMigration {

	/** 启动时调用(Setting 已初始化之后) */
	fun run() {
		try {
			// 【本轮·关键】只做一次: 教务密码与统一身份密码"本来就可能是同一个",
			// 每次启动都比对的话, 会把用户真正保存下来的教务凭据当成串存副本删掉
			// (表现为"明明保存了账号密码, 下次打开又没了")。做过就永久跳过。
			if(Setting.getBoolean(Keys.ACCOUNT_MIGRATION_DONE, false)) return

			// 【本轮·按要求】教务账号密码**恢复保存**(用教务系统原网址登录时保存),
			// 所以这里不能再"一律清空" —— 只清**串存的副本**:
			// 历史版本把「数字青科的统一身份密码」写进了教务密码位, 那种数据留着会让
			// 原网址登录一直用错密码。判据见 [isDuplicatedFromIpass](密码相同才算副本)。
			val easAccount = Setting.getString(Keys.EAS_ACCOUNT, "")
			val easPassword = Setting.getString(Keys.EAS_PASSWORD, "")
			val ipassAccount = Setting.getString(Keys.IPASS_ACCOUNT, "")
			val ipassPassword = Setting.getString(Keys.IPASS_PASSWORD, "")
			if(isDuplicatedFromIpass(easAccount, easPassword, ipassAccount, ipassPassword)){
				Setting.editSync {
					it.remove(Keys.EAS_ACCOUNT)
					it.remove(Keys.EAS_PASSWORD)
				}
				Logger.i("AccountMigration: 已清除串存到教务账号位的数字青科密码(教务密码位留给教务系统自己)")
			}
			// 无论是否清过都记账, 之后启动不再比对(否则会误删后来正常保存的教务凭据)
			Setting.editSync { it.putBoolean(Keys.ACCOUNT_MIGRATION_DONE, true) }
		}catch(t: Throwable) {
			Logger.e("AccountMigration", t)
		}
	}

	/**
	 * 教务账号位里的内容是否就是"数字青科那一份"的副本。
	 *
	 * 【本轮·改判据】只看**密码**是否相同 —— 不能再看账号:
	 * 教务系统的登录账号和数字青科的统一身份账号**是同一个学号**, 按账号判会把
	 * 用户真正保存的教务凭据误删。
	 *
	 * - 密码相同(非空) => 是统一身份密码被串存进来的副本, 删;
	 * - 密码不同 / 任一侧为空 => 是用户自己填的教务系统密码, 必须保留。
	 */
	internal fun isDuplicatedFromIpass(
		easAccount: String,
		easPassword: String,
		ipassAccount: String,
		ipassPassword: String
	): Boolean {
		if(easAccount.isEmpty() || ipassAccount.isEmpty()) return false
		return easPassword.isNotEmpty() && ipassPassword.isNotEmpty() && easPassword == ipassPassword
	}
}
