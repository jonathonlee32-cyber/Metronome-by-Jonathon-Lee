package com.example.musicpractice.practice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 跨午夜拆分的规则测试。这是整个功能里最容易出错、也最该被钉死的一段逻辑。
 */
class PracticeSplittingTest {

    @Before
    fun setUp() {
        TestTime.useFixedZone()
    }

    @Test
    fun `同一天内的练习保持为一条`() {
        val start = TestTime.at(2026, 9, 21, 14, 5)
        val end = TestTime.at(2026, 9, 21, 14, 32)

        val segments = PracticeSplitting.splitIntoSessions(start, end)

        assertEquals(1, segments.size)
        assertEquals("2026-09-21", segments[0].dateKey)
        assertEquals(27 * 60 * 1000L, segments[0].durationMillis)
    }

    @Test
    fun `跨午夜拆成前一天24点结束和后一天0点开始`() {
        val start = TestTime.at(2026, 9, 21, 23, 58)
        val end = TestTime.at(2026, 9, 22, 0, 10)

        val segments = PracticeSplitting.splitIntoSessions(start, end)

        assertEquals(2, segments.size)

        val first = segments[0]
        assertEquals("2026-09-21", first.dateKey)
        assertEquals(start, first.startMillis)
        assertEquals(TestTime.at(2026, 9, 22, 0, 0), first.endMillis)
        assertEquals(2 * 60 * 1000L, first.durationMillis)
        // 前一天的结束时刻要显示成 24:00，而不是 00:00。
        assertEquals("24:00", PracticeFormat.sessionEndLabel(first.startMillis, first.endMillis))

        val second = segments[1]
        assertEquals("2026-09-22", second.dateKey)
        assertEquals(TestTime.at(2026, 9, 22, 0, 0), second.startMillis)
        assertEquals(end, second.endMillis)
        assertEquals(10 * 60 * 1000L, second.durationMillis)
    }

    @Test
    fun `正好在零点结束时不产生空记录`() {
        val start = TestTime.at(2026, 9, 21, 23, 58)
        val end = TestTime.at(2026, 9, 22, 0, 0)

        val segments = PracticeSplitting.splitIntoSessions(start, end)

        assertEquals(1, segments.size)
        assertEquals(2 * 60 * 1000L, segments[0].durationMillis)
        assertEquals(
            "24:00",
            PracticeFormat.sessionEndLabel(segments[0].startMillis, segments[0].endMillis)
        )
    }

    @Test
    fun `跨越多天时每一天各占一条`() {
        val start = TestTime.at(2026, 9, 21, 22, 0)
        val end = TestTime.at(2026, 9, 23, 1, 0)

        val segments = PracticeSplitting.splitIntoSessions(start, end)

        assertEquals(3, segments.size)
        assertEquals(
            listOf("2026-09-21", "2026-09-22", "2026-09-23"),
            segments.map { it.dateKey }
        )
        assertEquals(2 * 60 * 60 * 1000L, segments[0].durationMillis)
        // 中间那一整天是完整的 24 小时。
        assertEquals(24 * 60 * 60 * 1000L, segments[1].durationMillis)
        assertEquals(60 * 60 * 1000L, segments[2].durationMillis)
    }

    @Test
    fun `时长为零或为负时不产生记录`() {
        val moment = TestTime.at(2026, 9, 21, 10, 0)

        assertTrue(PracticeSplitting.splitIntoSessions(moment, moment).isEmpty())
        assertTrue(PracticeSplitting.splitIntoSessions(moment, moment - 60_000L).isEmpty())
    }
}
