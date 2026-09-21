package com.example.musicpractice.practice

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 练习记录的本地文件存储。
 *
 * 放在 App 私有目录（filesDir）里：不需要任何权限，卸载才会清掉，
 * App 关掉、手机重启之后数据都还在。
 *
 * 写入用"先写临时文件再改名"的方式。改名在同一个文件系统上是原子操作，
 * 所以哪怕写到一半进程被强杀，原来的记录文件也不会变成半截损坏的内容 —— 这是
 * 需求里"用户强制关闭 App 不能丢数据"最要紧的一道保险。
 *
 * 主构造函数直接收 [File]，是为了让单元测试能塞一个临时文件进来，
 * 不必依赖 Android 环境。
 */
class PracticeTimeStore(private val file: File) {

    constructor(context: Context) : this(File(context.applicationContext.filesDir, FILE_NAME))

    /** 读不出或文件不存在都返回空数据，不抛异常。 */
    fun read(): PracticeTimeData {
        if (!file.exists()) return PracticeTimeData(emptyList(), null)
        return try {
            PracticeTimeCodec.decode(file.readText())
        } catch (error: Exception) {
            Log.w(TAG, "读取练习记录失败，按空记录处理", error)
            PracticeTimeData(emptyList(), null)
        }
    }

    fun write(data: PracticeTimeData) {
        val text = PracticeTimeCodec.encode(data)
        try {
            val temp = File(file.parentFile, "$FILE_NAME.tmp")
            temp.writeText(text)
            if (!temp.renameTo(file)) {
                // 极少数情况下改名失败（例如临时文件被别的进程占着）：退化成直接覆盖写。
                file.writeText(text)
                temp.delete()
            }
        } catch (error: Exception) {
            Log.w(TAG, "保存练习记录失败", error)
        }
    }

    companion object {
        const val FILE_NAME = "practice_records.json"
        private const val TAG = "PracticeTimeStore"
    }
}
