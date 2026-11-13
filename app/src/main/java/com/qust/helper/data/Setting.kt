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

import android.content.Context
import android.content.SharedPreferences
import java.io.File

object Setting {

	private lateinit var defaultSharedPreferences: SharedPreferences

	lateinit var lessonTableFolder: File

	fun init(context: Context) {
		defaultSharedPreferences = context.getSharedPreferences(context.packageName + "_preferences", Context.MODE_PRIVATE)
		// 【安全改造】初始化密码加密模块(Android Keystore / 设备绑定密钥)
		PwdCrypto.init(context)

		lessonTableFolder = File(context.filesDir, "lessonTables")
	}

	/** 【安全改造】需要加密存储的密码键 */
	private val SECRET_PWD_KEYS = setOf(
		Keys.EAS_PASSWORD, Keys.IPASS_PASSWORD, Keys.DRINK_PASSWORD, Keys.MAIL_PASSWORD
	)

	/** 【安全改造】需要加密存储的会话 Cookie 集合键 */
	// 【本轮】`eaDirectCookie` = 教务系统原网址(直连)通道的会话, 与 `eaCookie` / `ipassCookie` 同级,
	// 漏了它这条通道的 Cookie 读回来是密文、`Cookie.parse` 全部失败 —— 表现为"登录过但重开就要再登一次"。
	private val SECRET_SET_KEYS = setOf("eaCookie", "eaDirectCookie", "ipassCookie", "drinkCookie", "ecard_web", "pay_web")

	/**
	 * 保存数据到 SharedPreferences
	 * @param key 键
	 * @param value 值
	 */
	operator fun set(key: String, value: Any?) {
		val editor = defaultSharedPreferences.edit()
		when(value){
			null -> editor.putString(key, null)
			is Int -> editor.putInt(key, value)
			is Boolean -> editor.putBoolean(key, value)
			is Float -> editor.putFloat(key, value)
			is Long -> editor.putLong(key, value)
			else -> editor.putString(key, value.toString())
		}
		editor.apply()
	}

	/**
	 * 获取 SharedPreferences 保存的值
	 * @param key 键
	 * @return 值
	 */
	operator fun get(key: String): String? {
		val raw = defaultSharedPreferences.getString(key, null) ?: return null
		if(key !in SECRET_PWD_KEYS) return raw
		// 【安全改造】密码: 读取即解密, 仅登录瞬间使用; 旧版本明文自动迁移为密文
		val plain = PwdCrypto.decrypt(raw)
		if(plain != null) return plain
		if(raw.isNotEmpty() && !raw.startsWith("enc1:")){
			// 旧版本明文 -> 迁移为密文
			defaultSharedPreferences.edit().putString(key, PwdCrypto.encrypt(raw)).apply()
			return raw
		}
		// 密文解密失败(如清过数据导致密钥失效): 按空密码处理, 触发重新登录, 不使用垃圾值
		return ""
	}

	fun getString(key: String, defaultVal: String = ""): String {
		return get(key) ?: defaultVal
	}

	fun getStringSet(key: String, defaultVal: Set<String> = emptySet()): Set<String> {
		val raw = defaultSharedPreferences.getStringSet(key, defaultVal)!!
		if(key !in SECRET_SET_KEYS) return raw
		// 【安全改造】会话 Cookie: 读取即解密; 旧版本明文自动迁移为密文
		var migrated = false
		val out = raw.map { item ->
			val plain = PwdCrypto.decrypt(item)
			if(plain != null) plain else { migrated = true; item }
		}
		if(migrated){
			defaultSharedPreferences.edit()
				.putStringSet(key, out.map { PwdCrypto.encrypt(it) }.toHashSet())
				.apply()
		}
		return out.toSet()
	}

	fun getInt(key: String, defaultVal: Int = 0): Int {
		return defaultSharedPreferences.getInt(key, defaultVal)
	}

	/**
	 * 【本期】某个键是否**写过**。
	 *
	 * 用来区分"从没写过(老版本升级上来的设备)"和"写过 false(用户主动退出登录)":
	 * 例如教务会话位 `easSessionValid`, 老版本没有这个键, 升级后不该因为读不到就显示成"未登录"。
	 */
	fun contains(key: String): Boolean {
		return defaultSharedPreferences.contains(key)
	}

	fun getBoolean(key: String, defaultVal: Boolean = false): Boolean {
		return defaultSharedPreferences.getBoolean(key, defaultVal)
	}

	fun getLong(key: String, defaultVal: Long = 0L): Long {
		return defaultSharedPreferences.getLong(key, defaultVal)
	}

	fun edit(block: (SharedPreferences.Editor) -> Unit){
		val editor = defaultSharedPreferences.edit()
		block(editor)
		editor.apply()
	}

	/**
	 * 【第三十九轮】同步提交写入(commit): 用于"账号/密码"等关键信息。
	 * apply() 是异步落盘, 若写入后进程立即异常退出(崩溃/被杀), 数据可能丢失 ——
	 * 这正是"账号管理里已登录的账号莫名丢失、显示未登录"的一类原因。
	 * 账号相关写入改用 commit, 写完即落盘。
	 */
	fun editSync(block: (SharedPreferences.Editor) -> Unit): Boolean {
		val editor = defaultSharedPreferences.edit()
		block(editor)
		// 【登录逻辑】把 commit 结果返回给登录页: 只有真正写盘成功才提示"账号已保存"
		return editor.commit()
	}
}
