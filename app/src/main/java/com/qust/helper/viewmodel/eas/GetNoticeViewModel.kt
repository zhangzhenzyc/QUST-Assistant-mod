/*
 * 青科助手（修改版） —— 基于开源项目 QUST-Assistant 修改而成
 *
 * 原项目版权所有 (C) 2891954521  (https://github.com/2891954521/QUST-Assistant)
 * 原始开源许可: GNU General Public License v3.0 (GPL-3.0), 许可原文见 LICENSE 文件, 保持完整未改动。
 *
 * 修改版版权所有 (C) 2026 zhangzhenzyc  (https://github.com/zhangzhenzyc)
 * 本修改版同样以 GPL-3.0 发布; 附加版权声明见 NOTICE.md —— 两份版权声明相互独立、同时生效。
 */

package com.qust.helper.viewmodel.eas

import android.app.Application
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.qust.helper.data.eas.Notice
import com.qust.helper.model.account.EASAccount
import com.qust.helper.model.account.NeedLoginException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.ArrayList

@OptIn(ExperimentalSerializationApi::class)
class GetNoticeViewModel(application: Application) : BaseEasViewModel(application){

	private val dataPath: File

	var notices: MutableState<Array<Notice>>

	var hasRefresh by mutableStateOf(false)
	var refreshing by mutableStateOf(false)

	/**
	 * 【本期】查询单飞标记。
	 *
	 * 页面里原先有两处自动触发(页面级"列表为空就查"+列表级"没刷过就查"), 同一帧会并发发出
	 * 两次查询: 两次请求互相覆盖 `notices`, 后返回的那次常常是空结果, 页面就变成空白 ——
	 * 用户看到的现象是"点通知进来没有通知, 下拉刷新才有"。现在同一时刻只允许一个查询。
	 */
	@Volatile
	private var querying = false

	init{
		dataPath = File(application.filesDir, "notice")
		if(dataPath.exists()) {
			try {
				FileInputStream(dataPath).use {
					notices = mutableStateOf(Json.decodeFromStream<Array<Notice>>(it))
				}
			}catch(_: Exception){
				notices = mutableStateOf(emptyArray())
			}
		}else{
			notices = mutableStateOf(emptyArray())
		}
	}

	/**
	 * 查询教务通知
	 *
	 * 【本期·"点通知进来有时看不到通知, 需要刷新"修复】
	 * 1. 会话没建立时先进入教务: 通知接口用的是 `postNoCheck`(不校验登录), 冷启动/长时间没用
	 *    应用时会话可能已经失效, 第一次请求只会拿到教务登录页 HTML, 解析不出任何通知 ——
	 *    于是首屏是空的, 再刷一次(此时会话已由别的流程带起来)才有。
	 * 2. 空结果不再覆盖已有通知: 会话抖动时宁可显示上一次的通知, 也不要变成空白页。
	 * 3. 单飞: 并发查询会互相覆盖列表(见 [querying])。
	 */
	fun queryNotice() {
		if(querying) return
		querying = true
		hasRefresh = true
		refreshing = true
		request({
			// 1) 会话没建立(冷启动必然如此)时先把教务通道带起来, 否则第一次请求拿不到数据
			if(!isLogin) {
				try {
					checkLogin()
				}catch(e: NeedLoginException) {
					throw e
				}catch(_: Exception) {
					// 403/维护/网络抖动: 继续尝试查询, 拿得到就拿
				}
			}

			// 2) 分页抓取全部教务通知, 避免只取前 20 条导致后面的通知"显示不全"
			val list = fetchAllNotices()

			// 3) 空结果保护: 有旧数据时保留旧数据(不落盘覆盖), 让页面至少不是空白
			if(list.isNotEmpty() || notices.value.isEmpty()) {
				notices.value = list.toTypedArray()
				try {
					FileOutputStream(dataPath).use {
						Json.encodeToStream(notices.value, it)
					}
				}catch(_: Exception) {
					// 缓存写失败不影响界面显示
				}
			}
		}, {
			refreshing = false
			querying = false
		})
	}

	private suspend fun EASAccount.fetchAllNotices(): List<Notice> {
		val list = ArrayList<Notice>()
		var page = 1
		while(page <= 10) {
			// 这里的 queryNotice(page, 20) 是 EASAccount 的成员(本类同名的无参方法是"整页查询"入口)
			val batch = this.queryNotice(page, 20)
			if(batch.isEmpty()) break
			list.addAll(batch)
			if(batch.size < 20) break
			page++
		}
		return list
	}
}
