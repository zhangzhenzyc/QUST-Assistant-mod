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

import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.FileReader
import java.io.FileWriter
import java.io.IOException
import java.io.InputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.RandomAccessFile
import java.util.Locale

object FileUtils {

	fun writeFile(f: String, string: String) {
		writeFile(File(f), string)
	}

	fun writeFile(f: File, string: String?) {
		try {
			val w = FileWriter(f)
			w.write(string)
			w.flush()
			w.close()
		} catch(ignored: IOException) { }
	}

	fun readFile(str: String): String {
		return readFile(File(str))
	}

	fun readFile(f: File): String {
		if(!f.exists()) return ""
		val sb = StringBuilder()
		try {
			val b = FileReader(f)
			val c = CharArray(1024)
			var len = b.read(c)
			while(len != -1) {
				sb.appendRange(c, 0, len)
				len = b.read(c)
			}
		} catch(ignored: IOException) { }
		return sb.toString()
	}

	fun appendFile(file: File, s: String) {
		var raf: RandomAccessFile? = null
		try {
			if(!file.exists()) file.createNewFile()
			raf = RandomAccessFile(file, "rw")
			raf.seek(raf.length())
			raf.write(s.toByteArray())
		} catch(ignored: IOException) {
		} finally {
			try { raf?.close() } catch(ignored: IOException) { }
		}
	}

	fun appendFileNewLine(file: File, s: String) {
		var raf: RandomAccessFile? = null
		try {
			if(!file.exists()) file.createNewFile()
			raf = RandomAccessFile(file, "rw")
			raf.seek(raf.length())
			raf.write(s.toByteArray())
			raf.writeChar('\n'.code)
		} catch(ignored: IOException) {
		} finally {
			try { raf?.close() } catch(ignored: IOException) { }
		}
	}

	fun copyFile(input: InputStream, targetPath: String) {
		copyFile(input, File(targetPath))
	}

	fun copyFile(input: InputStream, targetPath: File) {
		try {
			FileOutputStream(targetPath).use { fos ->
				val buffer = ByteArray(1024)
				var byteCount: Int
				while(input.read(buffer).also { byteCount = it } != -1) {
					fos.write(buffer, 0, byteCount)
				}
				fos.flush()
				input.close()
			}
		} catch(ignore: Exception) { }
	}

	/**
	 * 从文件读取序列化后的数据
	 * @param file  文件
	 * @return      反序列化后的数据
	 */
	@Throws(Exception::class)
	fun loadData(file: File): Any {
		return if(file.exists()) {
			ObjectInputStream(FileInputStream(file)).use {
				it.readObject()
			}
		} else throw FileNotFoundException()
	}

	/**
	 * 将获取到的数据序列化存储
	 * @param file  文件
	 * @param o     数据对象
	 */
	@Throws(IOException::class)
	fun saveData(file: File?, o: Any?) {
		ObjectOutputStream(FileOutputStream(file)).use {
			it.writeObject(o)
			it.flush()
		}
	}

	// 获取后缀名
	fun getFileExt(file: File): String {
		return getFileExt(file.name)
	}

	//
	fun getFileExt(fileName: String): String {
		val pos = fileName.lastIndexOf(".")
		return if(pos == -1) "" else fileName.substring(pos + 1).lowercase(Locale.getDefault())
	}

}
