package com.example.musicpractice.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * 录音文件名的自动编号（需求五：`yyyymmdd-xxx`，自动编号、避免重名）。
 *
 * 这里把最容易出错的情况都过一遍：跨天重新从 001 开始、删掉中间一段之后空出来的号会被补上、
 * 序号超过三位之后不会撞回 001、别的日期占掉的名字不影响今天。
 */
class RecordingNamingTest {

    /** 测试里固定用东八区，断言写出来就是"2026-09-26 08:00"这种一眼能看懂的时刻。 */
    private val zone: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")

    private fun at(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long =
        Calendar.getInstance(zone).apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis

    private val morning = at(2026, 9, 26, 8, 0)
    private val lateNight = at(2026, 9, 26, 23, 59)
    private val nextMorning = at(2026, 9, 27, 7, 30)

    @Test
    fun `日期前缀是八位年月日`() {
        assertEquals("20260926", RecordingNaming.datePrefix(morning, zone))
        assertEquals("20260927", RecordingNaming.datePrefix(nextMorning, zone))
    }

    @Test
    fun `同一天的第一段录音编号是 001`() {
        assertEquals("20260926-001", RecordingNaming.nextName(morning, emptyList(), zone))
    }

    @Test
    fun `接着录就是 002、003`() {
        val taken = listOf("20260926-001")
        assertEquals("20260926-002", RecordingNaming.nextName(morning, taken, zone))

        val more = listOf("20260926-001", "20260926-002")
        assertEquals("20260926-003", RecordingNaming.nextName(morning, more, zone))
    }

    @Test
    fun `删掉中间一段之后空出来的号会补上`() {
        // 001 和 003 还在，002 被删掉了：下一段就补 002，编号不会一直往上涨。
        val taken = listOf("20260926-001", "20260926-003")
        assertEquals("20260926-002", RecordingNaming.nextName(morning, taken, zone))
    }

    @Test
    fun `同一天录到 999 段之后继续排下去`() {
        val taken = (1..999).map { RecordingNaming.name("20260926", it) }
        assertEquals("20260926-1000", RecordingNaming.nextName(morning, taken, zone))
    }

    @Test
    fun `别的日期占掉的名字不影响今天`() {
        val taken = listOf("20260925-001", "20260925-002", "20260927-001")
        assertEquals("20260926-001", RecordingNaming.nextName(morning, taken, zone))
    }

    @Test
    fun `同一天的深夜和白天用的是同一个日期前缀`() {
        assertEquals("20260926", RecordingNaming.datePrefix(lateNight, zone))
        // 深夜录的这一段会占掉 001，白天再来一段就是 002。
        assertEquals("20260926-002", RecordingNaming.nextName(morning, listOf("20260926-001"), zone))
    }

    @Test
    fun `跨天之后重新从 001 开始`() {
        val taken = listOf("20260926-001", "20260926-002")
        assertEquals("20260927-001", RecordingNaming.nextName(nextMorning, taken, zone))
    }

    @Test
    fun `序号补零到三位`() {
        assertEquals("20260926-001", RecordingNaming.name("20260926", 1))
        assertEquals("20260926-010", RecordingNaming.name("20260926", 10))
        assertEquals("20260926-100", RecordingNaming.name("20260926", 100))
    }

    @Test
    fun `名字和磁盘文件名可以互相换算`() {
        assertEquals("20260926-001.m4a", RecordingNaming.fileName("20260926-001"))
        assertEquals("20260926-001", RecordingNaming.nameFromFileName("20260926-001.m4a"))
        assertNull(RecordingNaming.nameFromFileName("score-1.pdf"))
        assertNull(RecordingNaming.nameFromFileName("20260926-001"))
    }
}
