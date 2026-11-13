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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.lifecycle.viewModelScope
import com.qust.helper.data.Data
import com.qust.helper.data.Keys
import com.qust.helper.data.Setting
import com.qust.helper.data.room.LessonDatabase
import com.qust.helper.data.room.LessonInfo
import com.qust.helper.data.room.LessonInfoGroup
import com.qust.helper.data.room.Mark
import com.qust.helper.ui.widget.ToastContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

class GetAcademicViewModel(application: Application): BaseEasViewModel(application) {

	companion object {
		/**
		 * 当前学业缓存的解析版本; 解析逻辑每改一次 +1。
		 * 低于该版本的 Room 缓存会在进页面时自动重新查询一次,
		 * 否则用户会一直看到旧解析逻辑留下的结果(0 分模块、缺通识任选等)。
		 */
		const val CURRENT_ACADEMIC_CACHE_VERSION = 6
	}

	private val lessonData = LessonDatabase.getInstance(application)

	private val showModeGroup: Array<List<AcademicGroup>> = arrayOf(emptyList(), emptyList())

	val uiState = AcademicUIState(_dialogText, toastContent)

	val uiEvent = object : AcademicUIEvent {
		override fun queryData() { this@GetAcademicViewModel.queryData() }

		override fun changeGroup() { this@GetAcademicViewModel.changeGroupMode() }
		override fun clickGroup(index: Int) { this@GetAcademicViewModel.clickGroup(index) }
		override fun clickLesson(group: Int, lesson: Int) { this@GetAcademicViewModel.clickLesson(group, lesson) }

		override fun sortBy(index: Int, type: Int) { this@GetAcademicViewModel.sortBy(index, type) }
		override fun sortType(index: Int) { this@GetAcademicViewModel.changeSortType(index) }
	}

	fun loadData(){
		if(showModeGroup[0].isEmpty()){
			// 解析逻辑升级后, 旧 Room 缓存必须丢弃重查, 否则永远显示旧结果
			if(Setting.getInt(Keys.ACADEMIC_CACHE_VERSION, 0) < CURRENT_ACADEMIC_CACHE_VERSION){
				queryData()
				return
			}
			viewModelScope.launch { withContext(Dispatchers.IO) {
				try {
					showModeGroup[0] = lessonData.lessonInfoDao().selectGroups().map { AcademicGroup(it) }
					uiState.lessonGroups = showModeGroup[0]
				}catch(e: Exception){ /* 【安全改造】不再打印堆栈(可能含页面数据) */ }
			} }
		}
	}

	/**
	 * 切换展示模式
	 */
	fun changeGroupMode() {
		uiState.showMode = if(uiState.showMode == 0) 1 else 0
		if(uiState.showMode == 1 && showModeGroup[1].isEmpty() && showModeGroup[0].isNotEmpty()) {
			viewModelScope.launch { withContext(Dispatchers.IO) {
				sortByTerm()
				uiState.lessonGroups = showModeGroup[1]
			} }
		}else{
			uiState.lessonGroups = showModeGroup[uiState.showMode]
		}
	}

	fun clickGroup(index: Int){
		val item = uiState.lessonGroups[index]
		if(!item.hasQuery){
			viewModelScope.launch {
				withContext(Dispatchers.IO) {
					item.lessons = if(uiState.showMode == 0) lessonData.lessonInfoDao().selectByGroup(item.groupInfo.group) else lessonData.lessonInfoDao().selectByIndex(item.groupInfo.group)
					sort(item)
					item.hasQuery = true
					item.isExpand = !item.isExpand
				}
			}
		}else{
			item.isExpand = !item.isExpand
		}
	}

	fun clickLesson(group: Int, lesson: Int) {
		val item = uiState.lessonGroups[group]
		if(item.lessonMarks[lesson] === Mark.EMPTY_MARK){
			viewModelScope.launch { withContext(Dispatchers.IO) {
				val info = item.lessons[lesson]
				val marks = lessonData.markDao().selectByKchId(info.kchId)
				// 【有成绩却显示"未公布"】成绩库查不到时, 直接用学业查询自己返回的成绩,
				// 只有连它也没有成绩时才保留"成绩未公布或未查询过"
				item.lessonMarks[lesson] = if(marks.isNotEmpty()){
					marks.maxBy { it.time }
				}else{
					Mark.fromLessonInfo(info.kchId, info.name, info.credit, info.mark, info.gpa, info.index) ?: Mark.EMPTY_MARK
				}
				item.isLessonExpand[lesson] = !item.isLessonExpand[lesson]
			} }
		}else{
			item.isLessonExpand[lesson] = !item.isLessonExpand[lesson]
		}
	}

	fun sortBy(index: Int, type: Int){
		val group = showModeGroup[uiState.showMode][index]
		group.sortBy = type
		sort(group)
	}

	fun changeSortType(index: Int){
		val group = showModeGroup[uiState.showMode][index]
		group.sortType = if(group.sortType == 1) -1 else 1
		sort(group)
	}

	fun queryData() {
		showDialog("查询中")
		request({
			val pair = easAccount.getAcademic()
			try {
				lessonData.lessonInfoDao().clear()
				lessonData.lessonInfoDao().clearGroups()
				lessonData.lessonInfoDao().insertAllGroups(pair.first)
				lessonData.lessonInfoDao().insertAll(pair.second.toList())
			} catch(_: IOException) { }
			uiState.showMode = 0
			showModeGroup[0] = pair.first.map { AcademicGroup(it) }
			showModeGroup[1] = emptyList()
			uiState.lessonGroups = showModeGroup[0]
			// 查询到数据后才写入当前缓存版本(空结果可能是解析/网络异常, 下次进页面继续自动重查)
			if(pair.first.isNotEmpty()){
				Setting.edit { it.putInt(Keys.ACADEMIC_CACHE_VERSION, CURRENT_ACADEMIC_CACHE_VERSION) }
			}
		}, {
			clearDialog()
			if(uiState.lessonGroups.isEmpty()){
				toastWarning("未查询到学业数据，请稍后重试")
			}else{
				toastOK("查询完成")
			}
		})
	}

	private fun sort(group: AcademicGroup){
		group.lessons = when(group.sortBy){
			0 ->  group.lessons.sortedWith { a, b -> a.mark.compareTo(b.mark) * group.sortType }
			1 -> group.lessons.sortedWith { a, b ->
				val v = a.credit.compareTo(b.credit) * group.sortType
				if(v == 0) a.mark.compareTo(b.mark) * group.sortType
				else v
			}
			2 -> group.lessons.sortedWith { a, b ->
				val v = a.status.compareTo(b.status) * group.sortType
				if(v == 0) a.mark.compareTo(b.mark) * group.sortType
				else v
			}
			else -> group.lessons
		}
	}

	/**
	 * 按修读学期分组
	 */
	private suspend fun sortByTerm() {
		val builders: Array<LessonInfoGroup.Builder> = Array(Data.TermName.size){
			LessonInfoGroup.Builder(group = it, type = Data.TermName[it])
		}

		val entranceTime: Int = easAccount.entranceTime
		if(entranceTime == -1) {
			toastWarning("未设置入学年份")
		}

		for(index in lessonData.lessonInfoDao().selectIndex()){
			if(index.group >= 0 && index.group < builders.size){
				val group = builders[index.group]
				group.totalCounts = index.totalCounts
				group.passedCounts = index.passedCounts
				group.requireCredits = index.requireCredits
				group.obtainedCredits = index.obtainedCredits
			}
		}

		showModeGroup[1] = List(builders.size){ AcademicGroup(builders[it].build()) }
	}
}

class AcademicGroup(val groupInfo: LessonInfoGroup){
	var sortBy = 0
	var hasQuery = false

	var isExpand by mutableStateOf(false)
	var sortType by mutableIntStateOf(-1)

	val _lessons: MutableState<List<LessonInfo>> = mutableStateOf(emptyList())
	var lessons: List<LessonInfo>
		get() = _lessons.value
		set(value) {
			isLessonExpand = value.map { false }.toMutableStateList()
			lessonMarks = Array(value.size) { Mark.EMPTY_MARK }
			_lessons.value = value
		}

	var isLessonExpand = lessons.map { false }.toMutableStateList()
	var lessonMarks: Array<Mark> = emptyArray()
}

class AcademicUIState(
	var dialogText: MutableState<String>,
	var toastContent: MutableState<ToastContent>,
) {
	/**
	 * 展示模式: 0 - 按课程类型，1 - 按修读年份
	 */
	var showMode by mutableIntStateOf(0)
	var lessonGroups by mutableStateOf(listOf<AcademicGroup>())
}

interface AcademicUIEvent{
	fun queryData() { }
	fun changeGroup() { }

	fun clickGroup(index: Int) { }
	fun clickLesson(group: Int, lesson: Int) { }

	fun sortBy(index: Int, type: Int) { }
	fun sortType(index: Int) { }
}