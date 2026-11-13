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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 【本期】「教务系统保存了智慧青科大账号密码」的历史数据清理判据。
 *
 * 只清理"确实是同一份"的副本; 用户自己另外填的教务账号必须保留
 * (账号管理里要能继续通过另一个网址直接登录教务系统)。
 */
class AccountMigrationTest {

	@Test
	fun sameAccountAndPassword_isDuplicated() {
		assertTrue(AccountMigration.isDuplicatedFromIpass("2508030119", "pwd123", "2508030119", "pwd123"))
	}

	@Test
	fun sameAccountDifferentPassword_isNotDuplicated() {
		// 【本轮】教务系统的登录账号与智慧青科大是**同一个学号**, 只看账号会把用户真正保存的
		// 教务系统凭据误删 —— 所以判据改成"只看密码": 密码不同 = 用户自己的教务系统密码, 必须保留。
		assertFalse(AccountMigration.isDuplicatedFromIpass("2508030119", "jwglxtPwd", "2508030119", "pwd123"))
	}

	@Test
	fun samePasswordOnly_isDuplicated() {
		// 账号位被手工改过, 但密码显然是同一份(统一身份密码)
		assertTrue(AccountMigration.isDuplicatedFromIpass("otherAccount", "pwd123", "2508030119", "pwd123"))
	}

	@Test
	fun differentAccountAndPassword_isNotDuplicated() {
		// 用户自己另外填的教务账号: 必须保留
		assertFalse(AccountMigration.isDuplicatedFromIpass("jwglxtUser", "jwglxtPwd", "2508030119", "pwd123"))
	}

	@Test
	fun emptyEasSlot_isNotDuplicated() {
		assertFalse(AccountMigration.isDuplicatedFromIpass("", "", "2508030119", "pwd123"))
	}

	@Test
	fun emptyIpassSlot_isNotDuplicated() {
		// 智慧青科大那边没有账号时, 教务自己的账号当然不是它的副本
		assertFalse(AccountMigration.isDuplicatedFromIpass("jwglxtUser", "jwglxtPwd", "", ""))
	}

	@Test
	fun emptyPasswordOnBothSides_isNotDuplicated() {
		// 空密码不能判成"相同": 否则登录失败留下的空值会被误当成副本清掉账号
		assertFalse(AccountMigration.isDuplicatedFromIpass("jwglxtUser", "", "2508030119", ""))
	}
}
