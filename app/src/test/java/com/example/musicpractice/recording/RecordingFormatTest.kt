package com.example.musicpractice.recording

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * 录音模块里的时间文案（需求七：`01:25 / 05:40`；需求六：列表里的录音时间）。
 */
class RecordingFormatTest {

    /** 测试里固定用东八区，断言写出来就是"2026-09-26 15:03"这种一眼能看懂的时刻。 */
    private val zone: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")

    private fun at(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long =
        Calendar.getInstance(zone).apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis

    @Test
    fun `时长按分秒显示`() {
        assertEquals("00:00", RecordingFormat.clock(0L))
        assertEquals("00:38", RecordingFormat.clock(38_000L))
        assertEquals("01:25", RecordingFormat.clock(85_000L))
        assertEquals("05:40", RecordingFormat.clock(340_000L))
    }

    @Test
    fun `超过一小时才带上小时位`() {
        assertEquals("59:59", RecordingFormat.clock(3_599_000L))
        assertEquals("1:00:00", RecordingFormat.clock(3_600_000L))
        assertEquals("1:02:05", RecordingFormat.clock(3_725_000L))
    }

    @Test
    fun `负数当成零`() {
        assertEquals("00:00", RecordingFormat.clock(-1_000L))
    }

    @Test
    fun `播放进度是当前时间斜杠总时间`() {
        assertEquals("01:25 / 05:40", RecordingFormat.progress(85_000L, 340_000L))
        assertEquals("00:00 / 00:00", RecordingFormat.progress(0L, 0L))
    }

    @Test
    fun `今天录的写今天几点`() {
        val created = at(2026, 9, 26, 15, 3)
        val now = at(2026, 9, 26, 18, 30)

        assertEquals("今天 15:03", RecordingFormat.describeCreated(created, now, zone))
    }

    @Test
    fun `昨天录的写昨天几点`() {
        val created = at(2026, 9, 25, 21, 20)
        val now = at(2026, 9, 26, 8, 0)

        assertEquals("昨天 21:20", RecordingFormat.describeCreated(created, now, zone))
    }

    @Test
    fun `今年更早的写月日和时刻`() {
        val created = at(2026, 9, 20, 8, 12)
        val now = at(2026, 9, 26, 8, 0)

        assertEquals("9月20日 08:12", RecordingFormat.describeCreated(created, now, zone))
    }

    @Test
    fun `去年的写完整年月日`() {
        val created = at(2025, 12, 31, 23, 59)
        val now = at(2026, 9, 26, 8, 0)

        assertEquals("2025年12月31日 23:59", RecordingFormat.describeCreated(created, now, zone))
    }
}
