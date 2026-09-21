package com.example.musicpractice.ui

import com.example.musicpractice.practice.PracticeCalendar
import com.example.musicpractice.practice.PracticeFormat
import com.example.musicpractice.practice.PracticeSession
import com.example.musicpractice.practice.PracticeSplitting

/** 表格里的一行：一段时间范围 + 对应时长。 */
data class PracticeRowUi(
    /** 例如 "14:05–14:32"；正在进行的那一行是 "14:05–进行中"。 */
    val timeRangeLabel: String,
    /** 例如 "27分钟"。 */
    val durationLabel: String,
    val isRunning: Boolean
)

/** 按日期分组后的一张卡片。 */
data class PracticeDayUi(
    /** 分组用的日期字符串，同时当作列表的 key，保证刷新时滚动位置不跳。 */
    val dateKey: String,
    /** 例如 "9月21日"。 */
    val dateLabel: String,
    /** 例如 "星期一"。 */
    val weekdayLabel: String,
    val isToday: Boolean,
    /** 当日累计，例如 "1小时02分钟"。 */
    val totalLabel: String,
    val rows: List<PracticeRowUi>
)

/**
 * "每日时间记录"页面需要的全部数据。
 * 已按日期从新到旧排好，界面拿到就能直接画。
 */
data class PracticeRecordsUiState(
    val todayLabel: String = "0分钟",
    val weekLabel: String = "0分钟",
    val totalLabel: String = "0分钟",
    val days: List<PracticeDayUi> = emptyList()
) {
    val hasRecords: Boolean get() = days.isNotEmpty()
}

/**
 * 把原始数据整理成界面状态：分组、排序、算统计。
 *
 * [activeStartMillis] 不为空表示节拍器正在响。正在进行的这一段也会被算进列表和统计里
 * （界面上标成"进行中"），所以页面上的数字是活的，而不是等停下来了才跳一下。
 * 它同样会被按午夜切开，跨零点时列表里会出现今天和明天两张卡片各一行。
 *
 * 是个纯函数：不读系统时间，[nowMillis] 由调用方传进来，方便写单元测试。
 */
fun buildPracticeRecordsUiState(
    sessions: List<PracticeSession>,
    activeStartMillis: Long?,
    nowMillis: Long
): PracticeRecordsUiState {
    val liveSessions = if (activeStartMillis == null) {
        emptyList()
    } else {
        PracticeSplitting.splitIntoSessions(activeStartMillis, nowMillis)
    }
    val runningSession = liveSessions.lastOrNull()
    val allSessions = sessions + liveSessions

    val todayKey = PracticeCalendar.dateKey(nowMillis)
    val weekStart = PracticeCalendar.startOfWeek(nowMillis)
    val weekStartKey = PracticeCalendar.dateKey(weekStart)
    // 用"下周一"这个开区间上界，比算 7 天里最后一刻简单，也不会有边界歧义。
    val weekEndKey = PracticeCalendar.dateKey(PracticeCalendar.shiftDays(weekStart, 7))

    var todayTotal = 0L
    var weekTotal = 0L
    var overallTotal = 0L
    for (session in allSessions) {
        val duration = session.durationMillis
        overallTotal += duration
        if (session.dateKey == todayKey) todayTotal += duration
        if (session.dateKey >= weekStartKey && session.dateKey < weekEndKey) weekTotal += duration
    }

    val days = allSessions
        .groupBy { it.dateKey }
        // 日期字符串是 ISO 顺序，直接按字符串倒序排就是"最近的日期在前"。
        .entries
        .sortedByDescending { it.key }
        .map { (dateKey, daySessions) ->
            val ordered = daySessions.sortedBy { it.startMillis }
            PracticeDayUi(
                dateKey = dateKey,
                dateLabel = PracticeFormat.dateLabel(dateKey),
                weekdayLabel = PracticeFormat.weekdayLabel(dateKey),
                isToday = dateKey == todayKey,
                totalLabel = PracticeFormat.summaryDuration(ordered.sumOf { it.durationMillis }),
                rows = ordered.map { session -> session.toRowUi(isRunning = session === runningSession) }
            )
        }

    return PracticeRecordsUiState(
        todayLabel = PracticeFormat.summaryDuration(todayTotal),
        weekLabel = PracticeFormat.summaryDuration(weekTotal),
        totalLabel = PracticeFormat.summaryDuration(overallTotal),
        days = days
    )
}

private fun PracticeSession.toRowUi(isRunning: Boolean): PracticeRowUi {
    val endLabel = when {
        isRunning -> "进行中"
        else -> PracticeFormat.sessionEndLabel(startMillis, endMillis)
    }
    return PracticeRowUi(
        timeRangeLabel = "${PracticeFormat.clock(startMillis)}–$endLabel",
        durationLabel = PracticeFormat.duration(durationMillis),
        isRunning = isRunning
    )
}
