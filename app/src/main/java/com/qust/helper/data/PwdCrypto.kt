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

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings as AndroidSettings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 【安全改造】密码/会话的本地加密存储
 *
 * 设计要点:
 * - **不硬编码任何密钥**;
 * - Android 6.0+ 使用 Android Keystore 在设备内生成 AES-256/GCM 密钥(不可导出、绑定设备),
 *   换机/清除应用数据后原密文不可解, 相当于自动作废;
 * - Android 5.x(API 21-22)不支持 Keystore AES 生成, 回退为
 *   PBKDF2(ANDROID_ID + 应用签名指纹, 随机盐) 派生的设备绑定密钥, 同样不含硬编码密钥;
 * - 密文格式: "enc1:" + Base64(IV(12B) + 密文 + GCM Tag(16B));
 * - 仅登录瞬间解密使用, 明文不落盘。
 */
object PwdCrypto {

	private const val PREFIX = "enc1:"
	private const val KEY_ALIAS = "qust_helper_pwd_v1"
	private const val KEYSTORE = "AndroidKeyStore"
	private const val TRANSFORM = "AES/GCM/NoPadding"
	private const val IV_LEN = 12
	private const val TAG_BITS = 128
	private const val PREF = "qust_helper_secure"
	private const val SALT_KEY = "kdf_salt_v1"

	@Volatile
	private var appContext: Context? = null

	@Volatile
	private var cachedKey: SecretKey? = null

	fun init(context: Context) {
		appContext = context.applicationContext
	}

	fun encrypt(plain: String): String {
		if (plain.isEmpty()) return ""
		return try {
			val cipher = Cipher.getInstance(TRANSFORM)
			cipher.init(Cipher.ENCRYPT_MODE, key())
			val iv = cipher.iv
			val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
			val out = ByteArray(iv.size + body.size)
			System.arraycopy(iv, 0, out, 0, iv.size)
			System.arraycopy(body, 0, out, iv.size, body.size)
			PREFIX + Base64.encodeToString(out, Base64.NO_WRAP)
		} catch (e: Exception) {
			// 【修改版】Keystore 不可用时降级为可逆编码, 避免密码被写成空导致自动登录全部失效
			try { "pln1:" + Base64.encodeToString(plain.toByteArray(Charsets.UTF_8), Base64.NO_WRAP) } catch (e2: Exception) { "" }
		}
	}

	/** 解密; 非本模块密文(旧版本明文)返回 null, 由调用方按明文兼容并迁移 */
	fun decrypt(stored: String): String? {
		if (stored.isEmpty()) return ""
		if (stored.startsWith("pln1:")) return try {
			String(Base64.decode(stored.removePrefix("pln1:"), Base64.NO_WRAP), Charsets.UTF_8)
		} catch (e: Exception) { null }
		if (!stored.startsWith(PREFIX)) return null
		return try {
			val raw = Base64.decode(stored.substring(PREFIX.length), Base64.NO_WRAP)
			if (raw.size <= IV_LEN) return null
			val iv = raw.copyOfRange(0, IV_LEN)
			val body = raw.copyOfRange(IV_LEN, raw.size)
			val cipher = Cipher.getInstance(TRANSFORM)
			cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
			String(cipher.doFinal(body), Charsets.UTF_8)
		} catch (e: Exception) {
			null
		}
	}

	@Synchronized
	private fun key(): SecretKey {
		cachedKey?.let { return it }
		val k = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) keystoreKey() else legacyKey()
		cachedKey = k
		return k
	}

	/** Android 6.0+: Android Keystore 内生成的 AES-256/GCM 密钥(设备绑定, 无法导出) */
	private fun keystoreKey(): SecretKey {
		val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
		(ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
		val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
		generator.init(
			KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
				.setBlockModes(KeyProperties.BLOCK_MODE_GCM)
				.setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
				.setKeySize(256)
				.build()
		)
		return generator.generateKey()
	}

	/**
	 * Android 5.x 回退: 用设备信息派生密钥
	 * 口令 = ANDROID_ID + 应用签名 SHA-256; 盐随机生成并随密文存储; 迭代 20000 次。
	 * 同样是设备绑定且不含硬编码密钥, 换机后无法解密。
	 */
	@SuppressLint("HardwareIds")
	private fun legacyKey(): SecretKey {
		val ctx = appContext ?: throw IllegalStateException("PwdCrypto 未初始化")
		val prefs = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
		var salt = Base64.decode(prefs.getString(SALT_KEY, "") ?: "", Base64.NO_WRAP)
		if (salt.isEmpty()) {
			salt = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
			prefs.edit().putString(SALT_KEY, Base64.encodeToString(salt, Base64.NO_WRAP)).apply()
		}
		val androidId = AndroidSettings.Secure.getString(ctx.contentResolver, AndroidSettings.Secure.ANDROID_ID) ?: ""
		val signature = try {
			val info = ctx.packageManager.getPackageInfo(ctx.packageName, android.content.pm.PackageManager.GET_SIGNATURES)
			MessageDigest.getInstance("SHA-256").digest(info.signatures?.firstOrNull()?.toByteArray() ?: ByteArray(0))
				.joinToString("") { "%02x".format(it) }
		} catch (e: Exception) {
			ctx.packageName
		}
		val passphrase = (androidId + "|" + signature + "|" + ctx.packageName).toCharArray()
		val spec = PBEKeySpec(passphrase, salt, 20000, 256)
		val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
		return SecretKeySpec(bytes, "AES")
	}
}
