/*
 * 青科助手（修改版） —— 基于开源项目 QUST-Assistant 修改而成
 *
 * 原项目版权所有 (C) 2891954521  (https://github.com/2891954521/QUST-Assistant)
 * 原始开源许可: GNU General Public License v3.0 (GPL-3.0), 许可原文见 LICENSE 文件, 保持完整未改动。
 *
 * 修改版版权所有 (C) 2026 zhangzhenzyc  (https://github.com/zhangzhenzyc)
 * 本修改版同样以 GPL-3.0 发布; 附加版权声明见 NOTICE.md —— 两份版权声明相互独立、同时生效。
 */

package com.qust.helper.data.room

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2: Migration = object : Migration(1, 2) {
	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL("""CREATE TABLE IF NOT EXISTS `lesson_info`(
			`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
			`kchId` TEXT NOT NULL,
			`name` TEXT NOT NULL,
			`type` TEXT NOT NULL,
			`credit` REAL NOT NULL,
			`mark` TEXT NOT NULL,
			`gpa` REAL NOT NULL,
			`status` INTEGER NOT NULL,
			`category` TEXT NOT NULL,
			`content` TEXT NOT NULL,
			`index` INTEGER NOT NULL,
			`group` INTEGER NOT NULL
		)""")

		db.execSQL("""CREATE TABLE IF NOT EXISTS `lesson_info_group`(
			`group` INTEGER NOT NULL,
			`type` TEXT NOT NULL,
			`requireCredits` REAL NOT NULL,
			`obtainedCredits` REAL NOT NULL,
			`creditNotEarned` REAL NOT NULL,
			`passedCounts` INTEGER NOT NULL,
			`totalCounts` INTEGER NOT NULL,
			PRIMARY KEY(`group`)
		)""")

	}
}

@Database(entities = [Mark::class, LessonInfo::class, LessonInfoGroup::class], version = 2, exportSchema = false)
abstract class LessonDatabase : RoomDatabase() {

	abstract fun markDao(): MarkDao

	abstract fun lessonInfoDao(): LessonInfoDao

	companion object {
		@Volatile
		private var INSTANCE: LessonDatabase? = null

		fun getInstance(context: Context): LessonDatabase {
			return INSTANCE ?: synchronized(this) {
				val instance = Room.databaseBuilder(
					context.applicationContext,
					LessonDatabase::class.java,
					"lessons"
				)
				.addMigrations(MIGRATION_1_2)
				.build()
				INSTANCE = instance
				instance
			}
		}
	}
}