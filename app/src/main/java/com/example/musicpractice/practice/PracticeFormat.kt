package com.example.musicpractice.practice

import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 把毫秒数、日期字符串翻译成界面上要显示的中文文本。
 *
 * 和界面无关的纯函数，所以可以直接写单元测试；放在 practice 包里而不是 ui 包里，
 * 是因为"时长怎么写"属于这个功能的业务规则，换个界面也还是这套写法。
 */
object PracticeFormat {

    private val WEEKDAY_NAMES =
        arrayOf("星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六")

    /** 时刻 → "14:05"。固定 24 小时制，和需求里的例子一致。 */
    fun clock(millis: Long): String =
        SimpleDateFormat("HH:mm", Locale.US).format(Date(millis))

    /**
     * 一条记录的结束时刻标签。
     * 如果它正好落在次日零点（跨午夜被切开的那一段），写成 "24:00" 而不是 "00:00"，
     * 这样"23:58–24:00"读起来才自然。
     */
    fun sessionEndLabel(startMillis: Long, endMillis: Long): String =
        if (endMillis == PracticeCalendar.startOfNextDay(startMillis)) {
            "24:00"
        } else {
            clock(endMillis)
        }

    /** 日期字符串 → "9月21日"。解析失败时原样返回，宁可显示得难看也不要崩。 */
    fun dateLabel(dateKey: String): String {
        val calendar = parseDateKey(dateKey) ?: return dateKey
        return "${calendar.get(Calendar.MONTH) + 1}月${calendar.get(Calendar.DAY_OF_MONTH)}日"
    }

    /** 日期字符串 → "星期一"。 */
    fun weekdayLabel(dateKey: String): String {
        val calendar = parseDateKey(dateKey) ?: return ""
        return WEEKDAY_NAMES[calendar.get(Calendar.DAY_OF_WEEK) - 1]
    }

    /**
     * 单次练习时长 → "27分钟" / "1小时02分钟"。
     * 不满一分钟时显示秒（"38秒"），否则几十秒的练习会显示成"0分钟"，看着像没记上。
     */
    fun duration(millis: Long): String {
        val seconds = roundToSeconds(millis)
        val hours = seconds / 3600
        val minutes = seconds % 3600 / 60
        val restSeconds = seconds % 60
        return when {
            hours > 0 && minutes > 0 -> hoursAndMinutes(hours, minutes)
            hours > 0 -> "${hours}小时"
            minutes > 0 -> "${minutes}分钟"
            else -> "${restSeconds}秒"
        }
    }

    /**
     * 统计数字（今日 / 本周 / 累计）→ "1小时02分钟"。
     * 和 [duration] 的区别是这里只到分钟：统计口径用分钟更整齐，
     * 但只要有练习就显示"不足1分钟"，不然会出现"今日 0分钟"却列着一条 30 秒记录这种自相矛盾。
     */
    fun summaryDuration(millis: Long): String {
        val seconds = roundToSeconds(millis)
        val hours = seconds / 3600
        val minutes = seconds % 3600 / 60
        return when {
            hours > 0 && minutes > 0 -> hoursAndMinutes(hours, minutes)
            hours > 0 -> "${hours}小时"
            minutes > 0 -> "${minutes}分钟"
            seconds > 0 -> "不足1分钟"
            else -> "0分钟"
        }
    }

    /**
     * "1小时02分钟"：有小时的时候分钟补足两位，和需求里的写法一致，
     * 长度也就固定了，卡片里的数字不会忽长忽短。
     * 固定用 Locale.US 拼数字，避免某些语言环境下出现非 0-9 的数字字符。
     */
    private fun hoursAndMinutes(hours: Long, minutes: Long): String =
        String.format(Locale.US, "%d小时%02d分钟", hours, minutes)

    /** 四舍五入到秒，避免 59.6 秒被写成 "59秒"。 */
    private fun roundToSeconds(millis: Long): Long =
        (millis.coerceAtLeast(0L) + 500L) / 1000L

    private fun parseDateKey(dateKey: String): Calendar? {
        val format = SimpleDateFormat(PracticeCalendar.DATE_KEY_PATTERN, Locale.US).apply {
            // 严格模式：不接受 "2026-13-45" 这种越界日期。
            isLenient = false
        }
        val date: Date = try {
            format.parse(dateKey)
        } catch (_: ParseException) {
            null
        } ?: return null
        return Calendar.getInstance().apply { time = date }
    }
}
