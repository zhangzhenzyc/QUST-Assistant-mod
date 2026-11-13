/*
 * 青科助手（修改版） —— 基于开源项目 QUST-Assistant 修改而成
 *
 * 原项目版权所有 (C) 2891954521  (https://github.com/2891954521/QUST-Assistant)
 * 原始开源许可: GNU General Public License v3.0 (GPL-3.0), 许可原文见 LICENSE 文件, 保持完整未改动。
 *
 * 修改版版权所有 (C) 2026 zhangzhenzyc  (https://github.com/zhangzhenzyc)
 * 本修改版同样以 GPL-3.0 发布; 附加版权声明见 NOTICE.md —— 两份版权声明相互独立、同时生效。
 */

package com.qust.helper.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.qust.helper.data.Keys
import com.qust.helper.data.Setting
import com.qust.helper.model.account.EASAccount
import com.qust.helper.ui.theme.Theme

class SettingViewModel: ViewModel() {

	var entranceTime by mutableStateOf(EASAccount.getInstance().entranceTime.toString()); private set
	fun setEntranceTimeValue(value: Int) {
		EASAccount.getInstance().entranceTime = value
		entranceTime = value.toString()
	}

	var eaHost by mutableIntStateOf(Setting.getInt(Keys.EA_HOST, 0)); private set
	fun setEaHostValue(value: Int){
		EASAccount.getInstance().changeHost(value)
		eaHost = value
	}

	// 【第三十二轮·按要求】"主题跟随系统"功能已删除(只保留本应用的暗色模式开关)。
	var themeDark by Theme.themeDark; private set
	fun setThemeDarkValue(value: Boolean){
		Setting.edit { it.putBoolean(Keys.KEY_THEME_DARK, value) }
		themeDark = value
	}

}