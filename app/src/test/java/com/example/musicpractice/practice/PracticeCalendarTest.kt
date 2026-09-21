package com.example.musicpractice.practice

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** 日期边界、"本周从周一开始"这些规则。 */
class PracticeCalendarTest {

    @Before
    fun setUp() {
        TestTime.useFixedZone()
    }

    @Test
    fun `一天的起点和次日起点`() {
        val noon = TestTime.at(2026, 9, 21, 12, 34)
        assertEquals(TestTime.at(2026, 9, 21, 0, 0), PracticeCalendar.startOfDay(noon))
        assertEquals(TestTime.at(2026, 9, 22, 0, 0), PracticeCalendar.startOfNextDay(noon))
    }

    @Test
    fun `日期字符串就是当天的本地日期`() {
        assertEquals("2026-09-21", PracticeCalendar.dateKey(TestTime.at(2026, 9, 21, 23, 59)))
        assertEquals("2026-09-22", PracticeCalendar.dateKey(TestTime.at(2026, 9, 22, 0, 1)))
    }

    @Test
    fun `本周从周一开始`() {
        // 2026-09-21 是星期一。
        assertEquals(
            TestTime.at(2026, 9, 21),
            PracticeCalendar.startOfWeek(TestTime.at(2026, 9, 21, 9, 0))
        )
        // 同周的星期三、星期日都归到 9 月 21 日那一周。
        assertEquals(
            TestTime.at(2026, 9, 21),
            PracticeCalendar.startOfWeek(TestTime.at(2026, 9, 23, 9, 0))
        )
        assertEquals(
            TestTime.at(2026, 9, 21),
            PracticeCalendar.startOfWeek(TestTime.at(2026, 9, 27, 23, 0))
        )
        // 9 月 20 日是星期日，属于上一周（从 9 月 14 日起）。
        assertEquals(
            TestTime.at(2026, 9, 14),
            PracticeCalendar.startOfWeek(TestTime.at(2026, 9, 20, 9, 0))
        )
    }
}
