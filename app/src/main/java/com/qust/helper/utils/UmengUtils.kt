/*
 * 青科助手（修改版） —— 基于开源项目 QUST-Assistant 修改而成
 *
 * 原项目版权所有 (C) 2891954521  (https://github.com/2891954521/QUST-Assistant)
 * 原始开源许可: GNU General Public License v3.0 (GPL-3.0), 许可原文见 LICENSE 文件, 保持完整未改动。
 *
 * 修改版版权所有 (C) 2026 zhangzhenzyc  (https://github.com/zhangzhenzyc)
 * 本修改版同样以 GPL-3.0 发布; 附加版权声明见 NOTICE.md —— 两份版权声明相互独立、同时生效。
 */

package com.qust.helper.utils

import android.content.Context
import com.qust.helper.BuildConfig
import com.qust.helper.data.Keys
import com.qust.helper.data.Setting
import com.umeng.analytics.MobclickAgent
import com.umeng.commonsdk.UMConfigure

/**
 * 友盟的工具类
 */
object UmengUtils {

	private var currentPage: String? = null

	/**
	 * 【第三十五轮】SDK 是否已经初始化过。
	 *
	 * `init` 现在被延后到冷启动第一帧之后才跑(见 `App.onCreate` 的注释: 桌面小组件点一下会拉起
	 * 进程, `UMConfigure.init` 是那条路径上最慢的一步)。所以这一小段时间里 `route` 可能先被
	 * 调用 —— 此时绝不能去碰 `MobclickAgent`, 否则 SDK 会报"未初始化就调用"。
	 */
	@Volatile
	private var inited = false

	fun init(context: Context){
		if(BuildConfig.UMENG_APP_KEY.isNotEmpty()){
			UMConfigure.setLogEnabled(BuildConfig.DEBUG)
			UMConfigure.preInit(context.applicationContext, BuildConfig.UMENG_APP_KEY, BuildConfig.UMENG_APP_CHANNEL)
			if(!Setting.getBoolean(Keys.IS_FIRST_USE, true)) {
				UMConfigure.init(context.applicationContext, BuildConfig.UMENG_APP_KEY, BuildConfig.UMENG_APP_CHANNEL, UMConfigure.DEVICE_TYPE_PHONE, "")
				MobclickAgent.setPageCollectionMode(MobclickAgent.PageMode.MANUAL)
			}
		}
		inited = true
	}

	/**
	 * 页面路由监听
	 */
	fun route(context: Context, name: String?){
		if(!inited) return
		if(currentPage == name || name == "home") return

		if(currentPage != null){
			MobclickAgent.onPageEnd(currentPage)
		}

		if(name != null){
//			MobclickAgent.onEventObject(context, "Navigation", mapOf("route" to name))
			MobclickAgent.onPageStart(name)
		}
		currentPage = name
	}
}