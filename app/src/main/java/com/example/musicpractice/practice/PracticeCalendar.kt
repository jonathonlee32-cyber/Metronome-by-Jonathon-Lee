package com.example.musicpractice.practice

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 与"哪一天 / 哪一周"有关的纯计算。
 *
 * 全部用 [Calendar] 而不是 java.time：项目的 minSdk 是 21，java.time 要开启脱糖才能用，
 * 这里不引入额外依赖，Calendar 在所有版本上行为一致。
 *
 * 这些函数都不依赖 [System.currentTimeMillis]，传入什么时刻就算什么时刻，方便写单元测试。
 */
object PracticeCalendar {

    /**
     * 记录里保存的日期字符串格式。
     * 用"年-月-日"的 ISO 顺序有个好处：字符串比较的结果和日期先后完全一致，
     * 所以判断"这条记录是不是本周的"直接比字符串就行。
     */
    const val DATE_KEY_PATTERN = "yyyy-MM-dd"

    /** 把时刻格式化成它所属的本地日期，例如 "2026-09-21"。 */
    fun dateKey(millis: Long): String =
        SimpleDateFormat(DATE_KEY_PATTERN, Locale.US).format(Date(millis))

    /** 某个时刻所在那一天的 00:00:00.000。 */
    fun startOfDay(millis: Long): Long = calendarAt(millis).apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** 某个时刻所在那一天的次日 00:00:00.000，也就是这一天的"24:00"。 */
    fun startOfNextDay(millis: Long): Long = shiftDays(startOfDay(millis), 1)

    /**
     * 往前/往后移动整数天。
     * 这里用 Calendar.add 而不是直接加减 86_400_000 毫秒：夏令时切换的那一天并不是 24 小时，
     * 用毫秒加减会把"次日零点"算偏一小时。
     */
    fun shiftDays(millis: Long, days: Int): Long = calendarAt(millis).apply {
        add(Calendar.DAY_OF_MONTH, days)
    }.timeInMillis

    /** 某个时刻所在那一周的星期一 00:00（国内习惯以周一为一周的开始）。 */
    fun startOfWeek(millis: Long): Long {
        val dayStart = startOfDay(millis)
        // Calendar 里星期日是 1、星期一是 2……星期六是 7。换算成"距周一过了几天"。
        val daysSinceMonday = (calendarAt(dayStart).get(Calendar.DAY_OF_WEEK) + 5) % 7
        return shiftDays(dayStart, -daysSinceMonday)
    }

    private fun calendarAt(millis: Long): Calendar =
        Calendar.getInstance().apply { timeInMillis = millis }
}
