package com.example.musicpractice.practice

/**
 * 把一段练习按"本地日期"切开。
 *
 * 需求要求跨午夜的练习分成两条，例如 23:58 开始、次日 00:10 停止：
 *
 * - 前一天：23:58 → 24:00，2 分钟
 * - 后一天：00:00 → 00:10，10 分钟
 *
 * 时刻本身不做任何"假造"：前一条的结束时刻就是次日零点这个真实瞬间，
 * 只是在界面上把它显示成 24:00（见 [PracticeFormat.sessionEndLabel]）。
 */
object PracticeSplitting {

    /**
     * @return 按日期切开后的记录列表；[endMillis] 不晚于 [startMillis] 时返回空列表
     *         （例如用户在练习途中把系统时间往回调了，这种数据不可信，直接丢弃）。
     */
    fun splitIntoSessions(startMillis: Long, endMillis: Long): List<PracticeSession> {
        if (endMillis <= startMillis) return emptyList()

        val segments = ArrayList<PracticeSession>()
        var cursor = startMillis
        while (cursor < endMillis) {
            val dayEnd = PracticeCalendar.startOfNextDay(cursor)
            // endMillis 正好等于零点时，这一段就以 24:00 结束，不会再产生一个长度为 0 的新段。
            val segmentEnd = if (endMillis < dayEnd) endMillis else dayEnd
            segments += PracticeSession(
                dateKey = PracticeCalendar.dateKey(cursor),
                startMillis = cursor,
                endMillis = segmentEnd
            )
            cursor = segmentEnd
        }
        return segments
    }
}
