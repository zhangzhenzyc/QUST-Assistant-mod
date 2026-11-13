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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.qust.helper.App
import com.qust.helper.R
import com.qust.helper.data.lesson.LessonTable
import com.qust.helper.data.lesson.LessonTableQueryResult
import com.qust.helper.data.lesson.LessonTableSaveBuilder
import com.qust.helper.model.LessonTableRepository
import com.qust.helper.ui.widget.LessonRender
import com.qust.helper.utils.DateUtils
import com.qust.helper.utils.LessonTableDiag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GetLessonTableViewModel(application: Application): BaseEasViewModel(application) {

	var termText by mutableStateOf("")
	var termTimeText by mutableStateOf("")

	var needSave by mutableStateOf(false)

	private val startDay = mutableStateOf(LessonTableRepository.startDay)
	private val totalWeek = mutableIntStateOf(LessonTableRepository.totalWeek)
	private val lessonTable = mutableStateOf(LessonTable())

	var lessonRender = LessonRender(startDay = startDay, totalWeek = totalWeek, lessonTable = lessonTable)

	fun queryLesson(){
		viewModelScope.launch {
			showDialog("查询中")
			val pair = getYearAndTerm()
			val result: LessonTableQueryResult

			withContext(Dispatchers.IO){
				// 【班级课表功能已删除】只保留个人课表查询
				result = LessonTableRepository.queryLessonTable(easAccount = easAccount, pair.first, pair.second)
				// 【第二十七轮】课表查询日志: 补一条界面层结果(长按课表查询页的「查询」按钮可查看/复制)
				LessonTableDiag.add("[界面] 查询结束: " + (result.error ?: "成功, 已更新预览课表(待保存)"))
				val error = result.error
				if(error == null) {
					termText = result.termText
					// 【本轮】按要求恢复 yyyy-MM-dd 形式(这个弹窗不显示月日)
					termTimeText = getApplication<App>().getString(
						R.string.text_query_term_start_time,
						DateUtils.YMD.format(LessonTableRepository.startDay),
						DateUtils.YMD.format(result.lessonTable.startDay)
					)
					needSave = true
					// 【第三十一轮】查询成功即把本次结果**自动保存为"默认课表"快照**(供「恢复默认课表」使用);
					// 注意: 这里**不写入实际课表** —— 实际课表仍然要用户点右下角"保存"(✓) 才更新。
					val slotCount = result.lessonTable.activeSlotCount()
					if(slotCount > 0) {
						LessonTableRepository.saveOriginalLessonTable(result.lessonTable)
					}
					toastOK("获取课表成功！")

					lessonTable.value = result.lessonTable
					startDay.value = result.lessonTable.startDay
					totalWeek.intValue = result.lessonTable.totalWeek
					// 【查询后不显示】把查询结果立刻交给渲染器重算并重绘。
					// 以前这里只换了数据源, 而渲染器只在"课表版本号变化"时才重算,
					// 查询结果还没保存时版本号不变 -> 界面仍是旧课表, 必须点一下右下角的
					// 确认(保存)按钮才会显示出来。
					lessonRender.updateLessonTable()
				}else{
					toastError(error)
				}
			}
			clearDialog()
		}
	}

	/**
	 * 保存查询到的课表
	 *
	 * @param keepUserLesson 是否保留用户修改过的课程。
	 *        勾选 = 把当前课表里"用户创建/修改过(type=1)的课程"合并进学校返回的课表;
	 *        不勾选 = 完全使用学校返回的原始课表, 丢弃用户改过的课程以及"按日期手动修改"的课。
	 * @param updateStartTime 是否用查询结果里的开学时间覆盖当前设置
	 * @param saveAdjustments 是否保存调休/放假安排。
	 *        勾选(默认) = 把调休/放假标记一并保存; 不勾选 = 只保存每周课表, 丢弃全部调休安排。
	 */

	fun saveLessonTable(keepUserLesson: Boolean = true, updateStartTime: Boolean = true, saveAdjustments: Boolean = false){
		val preview = lessonRender.lessonTable
		if(!updateStartTime){
			// 不更新开学时间: 沿用当前课表的开学时间与总周数
			preview.startDay = LessonTableRepository.startDay
			preview.totalWeek = LessonTableRepository.totalWeek
		}

		// “最新原始课表”= 本次查询得到的、没有任何更改的课表(与当下课表无关)。
		// 它只由「查询课表」写入; 这里只读取, 绝不在保存流程里写回, 保证它始终是最新查询结果。
		val original = LessonTableRepository.getOriginalLessonTable()

		if(!keepUserLesson){
			// —— 未勾选“保存用户修改的课程与调休安排”: 恢复到最新原始课表 ——
			// 1) 课程内容与每周模板直接取快照(修改过的课程、删除的课程都回到查询结果);
			// 2) 调休/放假/教务调课与按日期修改全部丢弃;
			// 3) keepAdjustments=false 避免被内存里旧课表的调休再次覆盖。
			val restored = (original ?: preview).deepCopy()
			restored.overrides.clear()
			restored.dayOverrides.clear()
			if(!LessonTableRepository.saveLessonTable(restored, keepAdjustments = false)){
				toastError("保存失败")
				return
			}
			LessonTableRepository.notifyLessonTableChanged()
			val savedTable0 = LessonTableRepository.lessonTable
			lessonTable.value = savedTable0
			startDay.value = savedTable0.startDay
			totalWeek.intValue = savedTable0.totalWeek
			lessonRender.updateLessonTable()
			toastOK("保存成功")
			return
		}

		// —— 勾选“保存用户修改的课程与调休安排” ——
		// 【修复·第二次保存起删掉的课程补不回来】
		// “删除的课程要按最新原始课表恢复”必须以**最新查询到的原始课表**为基准。
		// 以前这里是拿 preview(预览课表)当基准: 而第一次保存成功后 preview 会被替换成
		// “已经保存下来的课表”(它本身就已经少了被删除的课程), 于是从第二次保存开始,
		// 再拿它去“补回删除的课程”自然什么都补不回来 —— 只有重新查询之后的第一次保存有效。
		// 现在统一交给 [LessonTableSaveBuilder]: 每次都从 original(查询课表时写入、不掺任何修改的快照)
		// 出发, 删除(删单节课 / 删整天课表 / 删整门课)一律视为“没改过”, 因此每次都会被原始课表补齐。
		val toSave = LessonTableSaveBuilder.buildKeepUserTable(
			original = original ?: preview,
			current = LessonTableRepository.lessonTable,
			startDay = preview.startDay,
			totalWeek = preview.totalWeek,
			saveAdjustments = saveAdjustments
		)

		// 上面已自行拼好 调休/按日期修改(含"删除视为未修改"的补齐), 这里必须 keepAdjustments=false:
		// 否则 saveLessonTable 会拿内存里旧课表的 overrides/dayOverrides 把刚拼好的结果覆盖回去,
		// 刚补回的课程又会被"删空的副本"顶掉。
		if(!LessonTableRepository.saveLessonTable(toSave, keepAdjustments = false)){
			toastError("保存失败")
			return
		}
		LessonTableRepository.notifyLessonTableChanged()

		// 让预览显示"真正存下来的课表", 避免出现"选了不保留却还显示改过的课"
		val savedTable = LessonTableRepository.lessonTable
		lessonTable.value = savedTable
		startDay.value = savedTable.startDay
		totalWeek.intValue = savedTable.totalWeek
		lessonRender.updateLessonTable()

		toastOK("保存成功")
	}

	/**
	 * 一键恢复默认课表(即最初从教务系统查询导入的课表)
	 *
	 * 【第三十五轮】扩展为四个组合:
	 * @param keepAdjustments true = 调休后(保留调休/放假安排); false = 调休前(清除调休安排)
	 * @param keepEasChanges  true = 调课后(保留教务通知调课); false = 调课前(清除教务通知调课)
	 */
	fun restoreDefaultLessonTable(keepAdjustments: Boolean, keepEasChanges: Boolean){
		if(!LessonTableRepository.hasOriginalLessonTable()){
			toastWarning("还没有可恢复的默认课表，请先在上方查询课表")
			return
		}
		if(!LessonTableRepository.restoreOriginalLessonTable(keepAdjustments, keepEasChanges)){
			toastError("恢复失败")
			return
		}
		// 预览同步显示恢复后的课表
		val saved = LessonTableRepository.lessonTable
		lessonTable.value = saved
		startDay.value = saved.startDay
		totalWeek.intValue = saved.totalWeek
		lessonRender.updateLessonTable()
		// 【第四十轮】两个功能: 最新原始课表(全部清除) / 最新调休课表(保留调休与调课)
		val modeText = if(keepAdjustments && keepEasChanges) "最新调休课表" else "最新原始课表"
		toastOK("已恢复为$modeText")
	}

}