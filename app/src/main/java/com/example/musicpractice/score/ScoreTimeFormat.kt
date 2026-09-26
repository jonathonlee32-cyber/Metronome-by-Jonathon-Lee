package com.example.musicpractice.score

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 最近项目列表里那句"什么时候打开的"。
 *
 * 规则（需求二里的例子就是"刚刚打开""昨天打开"这种说法）：
 *
 * | 距现在 | 显示 |
 * | --- | --- |
 * | 不到 1 分钟 | 刚刚打开 |
 * | 今天之内 | 10 分钟前打开 / 今天 09:00 打开 |
 * | 昨天 | 昨天打开 |
 * | 2～6 天前 | 3 天前打开 |
 * | 更早 | 2026.09.20 打开 |
 *
 * "今天 / 昨天"按**日历上的日期**判断，不是按"过去了多少小时"：今天 00:30 打开、
 * 现在是 09:00，虽然只过了 8 个半小时，显示的也是"今天 00:30 打开"。
 *
 * 这里所有计算都显式传入"现在几点"（[nowMillis]），不自己去取系统时间 ——
 * 这样这段逻辑不依赖当前时刻，可以直接用单元测试验证（见 ScoreTimeFormatTest）。
 */
object ScoreTimeFormat {

    private const val MINUTE_MILLIS = 60_000L
    private const val HOUR_MILLIS = 3_600_000L

    /** 从没打开过（时间是 0）时显示的话。 */
    private const val NEVER_OPENED = "尚未打开"

    fun describeLastOpened(lastOpenedAtMillis: Long, nowMillis: Long): String {
        if (lastOpenedAtMillis <= 0L) return NEVER_OPENED

        val elapsed = nowMillis - lastOpenedAtMillis
        // 时间出现了"未来"（用户改过系统时间、时区变化）时按刚打开算，不显示"-3 天前打开"。
        if (elapsed < MINUTE_MILLIS) return "刚刚打开"

        if (isSameDay(lastOpenedAtMillis, nowMillis)) {
            // 今天之内：一小时以内说"多少分钟前"，更早直接给具体时刻，信息量更大。
            return if (elapsed < HOUR_MILLIS) {
                "${elapsed / MINUTE_MILLIS} 分钟前打开"
            } else {
                "今天 ${format(lastOpenedAtMillis, "HH:mm")} 打开"
            }
        }

        if (isYesterday(lastOpenedAtMillis, nowMillis)) return "昨天打开"

        // 昨天以前：按自然日相差几天来说"几天前"，最多说到第 6 天，再早就给具体日期。
        val days = daysBetween(lastOpenedAtMillis, nowMillis)
        if (days in 2..6) return "$days 天前打开"

        return "${format(lastOpenedAtMillis, "yyyy.MM.dd")} 打开"
    }

    /** 两个时刻是不是同一个日历日（按本机时区）。 */
    private fun isSameDay(firstMillis: Long, secondMillis: Long): Boolean =
        calendarOf(firstMillis).let { first ->
            compare(
                first,
                calendarOf(secondMillis),
                Calendar.YEAR,
                Calendar.DAY_OF_YEAR
            )
        }

    /** [earlierMillis] 是不是 [nowMillis] 的前一天。 */
    private fun isYesterday(earlierMillis: Long, nowMillis: Long): Boolean {
        val yesterday = calendarOf(nowMillis).apply { add(Calendar.DAY_OF_YEAR, -1) }
        val first = calendarOf(earlierMillis)
        return compare(first, yesterday, Calendar.YEAR, Calendar.DAY_OF_YEAR)
    }

    /**
     * [earlierMillis] 到 [nowMillis] 之间差几个自然日。
     *
     * 把两个时刻都归到当天的 00:00 再相减，所以"昨天 23:59 → 今天 00:01"算 1 天（而不是 0 天）。
     */
    private fun daysBetween(earlierMillis: Long, nowMillis: Long): Int {
        val start = startOfDay(earlierMillis).timeInMillis
        val end = startOfDay(nowMillis).timeInMillis
        return ((end - start) / (24L * HOUR_MILLIS)).toInt()
    }

    private fun startOfDay(millis: Long): Calendar = calendarOf(millis).apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    private fun calendarOf(millis: Long): Calendar =
        Calendar.getInstance().apply { timeInMillis = millis }

    private fun compare(first: Calendar, second: Calendar, vararg fields: Int): Boolean =
        fields.all { field -> first.get(field) == second.get(field) }

    private fun format(millis: Long, pattern: String): String =
        SimpleDateFormat(pattern, Locale.getDefault()).format(Date(millis))
}
