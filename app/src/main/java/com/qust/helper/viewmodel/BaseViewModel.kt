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

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import com.qust.helper.ui.widget.DialogAble
import com.qust.helper.ui.widget.DialogAbleImpl
import com.qust.helper.ui.widget.ToastAble
import com.qust.helper.ui.widget.ToastAbleImpl

abstract class BaseViewModel: ViewModel(), ToastAble by ToastAbleImpl(), DialogAble by DialogAbleImpl()

abstract class BaseAndroidViewModel(application: Application): AndroidViewModel(application), ToastAble by ToastAbleImpl(), DialogAble by DialogAbleImpl()