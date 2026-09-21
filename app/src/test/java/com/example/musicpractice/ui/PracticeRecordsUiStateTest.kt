package com.example.musicpractice.ui

import com.example.musicpractice.practice.PracticeSession
import com.example.musicpractice.practice.TestTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 记录页的数据整理：分组、排序、统计口径、正在进行的这一段怎么算。 */
class PracticeRecordsUiStateTest {

    @Before
    fun setUp() {
        TestTime.useFixedZone()
    }

    /** 造一条"某天 from 点到 to 点"的记录。 */
    private fun session(
        year: Int,
        month: Int,
        day: Int,
        from: Pair<Int, Int>,
        to: Pair<Int, Int>
    ): PracticeSession = PracticeSession(
        dateKey = "%04d-%02d-%02d".format(year, month, day),
        startMillis = TestTime.at(year, month, day, from.first, from.second),
        endMillis = TestTime.at(year, month, day, to.first, to.second)
    )

    @Test
    fun `按日期分组且最近的日期排在最前面`() {
        // 2026-09-23 是星期三。
        val now = TestTime.at(2026, 9, 23, 20, 0)
        val sessions = listOf(
            session(2026, 9, 21, 9 to 0, 9 to 30),
            session(2026, 9, 23, 14 to 5, 14 to 25),
            session(2026, 9, 20, 19 to 20, 19 to 30)
        )

        val state = buildPracticeRecordsUiState(sessions, activeStartMillis = null, nowMillis = now)

        assertEquals(
            listOf("2026-09-23", "2026-09-21", "2026-09-20"),
            state.days.map { it.dateKey }
        )
        assertEquals("9月23日", state.days[0].dateLabel)
        assertEquals("星期三", state.days[0].weekdayLabel)
        assertTrue(state.days[0].isToday)
        assertFalse(state.days[1].isToday)
    }

    @Test
    fun `统计按今日本周累计三个口径分别求和`() {
        // 2026-09-23 是星期三，所在的一周从 9 月 21 日（星期一）开始。
        val now = TestTime.at(2026, 9, 23, 20, 0)
        val sessions = listOf(
            session(2026, 9, 23, 14 to 0, 14 to 20), // 今天 20 分钟
            session(2026, 9, 21, 9 to 0, 9 to 30),   // 本周 30 分钟
            session(2026, 9, 20, 19 to 0, 19 to 10), // 上周日 10 分钟
            session(2026, 9, 16, 19 to 0, 19 to 5)   // 上周三 5 分钟
        )

        val state = buildPracticeRecordsUiState(sessions, activeStartMillis = null, nowMillis = now)

        assertEquals("20分钟", state.todayLabel)
        assertEquals("50分钟", state.weekLabel)
        assertEquals("1小时05分钟", state.totalLabel)
    }

    @Test
    fun `一天里的多次练习按开始时间排好并且有当日合计`() {
        val now = TestTime.at(2026, 9, 21, 16, 0)
        // 故意乱序传入，界面上的顺序应该由开始时间决定。
        val sessions = listOf(
            session(2026, 9, 21, 15 to 10, 15 to 45),
            session(2026, 9, 21, 14 to 5, 14 to 32)
        )

        val state = buildPracticeRecordsUiState(sessions, activeStartMillis = null, nowMillis = now)

        val day = state.days.single()
        assertEquals(listOf("14:05–14:32", "15:10–15:45"), day.rows.map { it.timeRangeLabel })
        assertEquals(listOf("27分钟", "35分钟"), day.rows.map { it.durationLabel })
        assertEquals("1小时02分钟", day.totalLabel)
    }

    @Test
    fun `正在进行的练习会被算进列表和统计并标成进行中`() {
        val now = TestTime.at(2026, 9, 21, 16, 0)
        val sessions = listOf(session(2026, 9, 21, 14 to 5, 14 to 32))

        val state = buildPracticeRecordsUiState(
            sessions = sessions,
            activeStartMillis = TestTime.at(2026, 9, 21, 15, 10),
            nowMillis = now
        )

        val day = state.days.single()
        assertEquals(2, day.rows.size)
        assertEquals("15:10–进行中", day.rows[1].timeRangeLabel)
        assertTrue(day.rows[1].isRunning)
        assertEquals("50分钟", day.rows[1].durationLabel)
        // 27 + 50 = 77 分钟，当日累计里包含正在进行的这一段。
        assertEquals("1小时17分钟", day.totalLabel)
        assertEquals("1小时17分钟", state.todayLabel)
    }

    @Test
    fun `正在进行的练习跨过午夜时分别落到两天`() {
        val now = TestTime.at(2026, 9, 22, 0, 10)
        val state = buildPracticeRecordsUiState(
            sessions = emptyList(),
            activeStartMillis = TestTime.at(2026, 9, 21, 23, 58),
            nowMillis = now
        )

        assertEquals(listOf("2026-09-22", "2026-09-21"), state.days.map { it.dateKey })
        assertEquals(listOf("00:00–进行中"), state.days[0].rows.map { it.timeRangeLabel })
        assertEquals(listOf("23:58–24:00"), state.days[1].rows.map { it.timeRangeLabel })
        assertEquals("2分钟", state.days[1].rows[0].durationLabel)
        // 前一天的 2 分钟不算进"今日"，今日只有跨过来的 10 分钟。
        assertEquals("10分钟", state.todayLabel)
        assertEquals("12分钟", state.totalLabel)
    }

    @Test
    fun `没有任何记录时是空状态`() {
        val state = buildPracticeRecordsUiState(
            sessions = emptyList(),
            activeStartMillis = null,
            nowMillis = TestTime.at(2026, 9, 21, 16, 0)
        )

        assertFalse(state.hasRecords)
        assertEquals("0分钟", state.todayLabel)
        assertEquals("0分钟", state.weekLabel)
        assertEquals("0分钟", state.totalLabel)
    }
}
