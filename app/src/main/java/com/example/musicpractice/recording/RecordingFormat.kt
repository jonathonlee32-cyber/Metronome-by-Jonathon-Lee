package com.example.musicpractice.recording

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * 录音模块里的时间文案（时长、进度、创建时间）。
 *
 * 全是纯函数：只做数字到字符串的换算，不碰界面也不碰磁盘，所以能在电脑上直接跑单元测试
 * （见 RecordingFormatTest）。
 *
 * 一律用 [Locale.US]：数字一定是 0-9，不会在某些语言环境下变成别的数字字符。
 */
object RecordingFormat {

    const val SECOND_MILLIS = 1_000L
    const val DAY_MILLIS = 86_400_000L

    /**
     * 把毫秒格式化成 `xx:xx`（分:秒）。
     *
     * 超过一小时才带上小时位，写成 `h:mm:ss` —— 需求里的 `01:25 / 05:40` 就是这个格式。
     */
    fun clock(millis: Long): String {
        val totalSeconds = (millis / SECOND_MILLIS).coerceAtLeast(0L)
        val hours = totalSeconds / 3600
        val minutes = totalSeconds % 3600 / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    /** 播放页的进度文案：`01:25 / 05:40`。 */
    fun progress(positionMillis: Long, durationMillis: Long): String =
        "${clock(positionMillis)} / ${clock(durationMillis)}"

    /**
     * 最近录音列表里的创建时间说法。
     *
     * 今天录的写"今天 15:03"，昨天写"昨天 21:20"，今年更早的写"9月20日 08:12"，
     * 跨年的写"2025年12月31日 23:59" —— 和乐谱模块的"最近打开时间"是同一种口吻。
     */
    fun describeCreated(
        createdMillis: Long,
        nowMillis: Long,
        timeZone: TimeZone = TimeZone.getDefault()
    ): String {
        val days = daysBetween(createdMillis, nowMillis, timeZone)
        val time = timeOfDay(createdMillis, timeZone)
        return when {
            days <= 0L -> "今天 $time"
            days == 1L -> "昨天 $time"
            sameYear(createdMillis, nowMillis, timeZone) -> "${monthDay(createdMillis, timeZone)} $time"
            else -> "${yearMonthDay(createdMillis, timeZone)} $time"
        }
    }

    /** 两个时刻相隔几天（按各自的"当天零点"算，所以不受夏令时影响）。 */
    private fun daysBetween(fromMillis: Long, toMillis: Long, timeZone: TimeZone): Long =
        ((startOfDay(toMillis, timeZone) - startOfDay(fromMillis, timeZone)).toDouble() / DAY_MILLIS)
            .roundToInt()
            .toLong()

    /** 这一时刻所在那一天的零点。 */
    private fun startOfDay(atMillis: Long, timeZone: TimeZone): Long {
        val calendar = Calendar.getInstance(timeZone)
        calendar.timeInMillis = atMillis
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    private fun sameYear(a: Long, b: Long, timeZone: TimeZone): Boolean =
        format(a, "yyyy", timeZone) == format(b, "yyyy", timeZone)

    private fun timeOfDay(atMillis: Long, timeZone: TimeZone): String =
        format(atMillis, "HH:mm", timeZone)

    private fun monthDay(atMillis: Long, timeZone: TimeZone): String =
        "${format(atMillis, "M", timeZone)}月${format(atMillis, "d", timeZone)}日"

    private fun yearMonthDay(atMillis: Long, timeZone: TimeZone): String =
        "${format(atMillis, "yyyy", timeZone)}年" +
            "${format(atMillis, "M", timeZone)}月${format(atMillis, "d", timeZone)}日"

    private fun format(atMillis: Long, pattern: String, timeZone: TimeZone): String {
        val format = SimpleDateFormat(pattern, Locale.US)
        format.timeZone = timeZone
        return format.format(Date(atMillis))
    }

}
