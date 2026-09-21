package com.example.musicpractice.tuner

import android.content.Context
import android.util.Log
import java.io.File
import org.json.JSONObject

/**
 * 调音器设置的本地存储。
 *
 * 和练习记录一样，放在 App 私有目录（filesDir）里，不需要任何权限，卸载才会清掉。
 * 现在只有一个设置项：A4 基准频率。
 *
 * 主构造函数直接收 [File]，方便单元测试塞一个临时文件进来，不必依赖 Android 环境。
 */
class TunerSettingsStore(private val file: File) {

    constructor(context: Context) : this(File(context.applicationContext.filesDir, FILE_NAME))

    /** 读 A4 基准频率。文件不存在、读不出来或数值非法都回落到默认值 440Hz。 */
    fun readA4Hz(): Int {
        if (!file.exists()) return DEFAULT_A4_HZ
        return try {
            decodeA4Hz(file.readText())
        } catch (error: Exception) {
            Log.w(TAG, "读取调音器设置失败，按默认值处理", error)
            DEFAULT_A4_HZ
        }
    }

    /** 写 A4 基准频率。超出 432～448 的值会被夹到范围内。 */
    fun writeA4Hz(hz: Int) {
        try {
            file.writeText(encodeA4Hz(hz.coerceIn(MIN_A4_HZ, MAX_A4_HZ)))
        } catch (error: Exception) {
            Log.w(TAG, "保存调音器设置失败", error)
        }
    }

    companion object {
        const val FILE_NAME = "tuner_settings.json"

        /** 默认 A4 基准：440Hz。 */
        const val DEFAULT_A4_HZ = 440

        /** A4 基准最小 432Hz。 */
        const val MIN_A4_HZ = 432

        /** A4 基准最大 448Hz。 */
        const val MAX_A4_HZ = 448

        /** 每次点 +/- 调整 1Hz。 */
        const val STEP_HZ = 1

        private const val KEY_A4_HZ = "a4Hz"
        private const val TAG = "TunerSettingsStore"
    }
}

/** 把 A4 基准编码成一个很小的 JSON 对象。 */
internal fun encodeA4Hz(hz: Int): String = JSONObject().put("a4Hz", hz).toString()

/**
 * 解析设置文件。任何缺失或非法内容都回落到默认 440Hz，
 * 所以就算文件被改坏，调音器也只是回到默认值，不会崩。
 */
internal fun decodeA4Hz(text: String): Int {
    val json = JSONObject(text)
    val value = json.optInt("a4Hz", TunerSettingsStore.DEFAULT_A4_HZ)
    return value.coerceIn(TunerSettingsStore.MIN_A4_HZ, TunerSettingsStore.MAX_A4_HZ)
}
