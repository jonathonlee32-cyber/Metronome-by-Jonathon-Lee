package com.example.musicpractice.practice

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** 界面上那几行文案的格式化规则。 */
class PracticeFormatTest {

    @Before
    fun setUp() {
        TestTime.useFixedZone()
    }

    @Test
    fun `单次时长按分钟显示`() {
        assertEquals("27分钟", PracticeFormat.duration(27 * 60_000L))
        assertEquals("0秒", PracticeFormat.duration(0L))
    }

    @Test
    fun `单次时长超过一小时同时显示小时和分钟`() {
        assertEquals("1小时", PracticeFormat.duration(60 * 60_000L))
        assertEquals("1小时02分钟", PracticeFormat.duration(62 * 60_000L))
    }

    @Test
    fun `不足一分钟显示秒`() {
        assertEquals("38秒", PracticeFormat.duration(38_000L))
    }

    @Test
    fun `统计时长不足一分钟不会显示成0分钟`() {
        assertEquals("0分钟", PracticeFormat.summaryDuration(0L))
        assertEquals("不足1分钟", PracticeFormat.summaryDuration(45_000L))
        assertEquals("1小时02分钟", PracticeFormat.summaryDuration(62 * 60_000L))
    }

    @Test
    fun `时刻固定用24小时制`() {
        assertEquals("14:05", PracticeFormat.clock(TestTime.at(2026, 9, 21, 14, 5)))
        assertEquals("00:00", PracticeFormat.clock(TestTime.at(2026, 9, 21, 0, 0)))
        assertEquals("23:58", PracticeFormat.clock(TestTime.at(2026, 9, 21, 23, 58)))
    }

    @Test
    fun `日期和星期按中文显示`() {
        assertEquals("9月21日", PracticeFormat.dateLabel("2026-09-21"))
        assertEquals("星期一", PracticeFormat.weekdayLabel("2026-09-21"))
        assertEquals("星期日", PracticeFormat.weekdayLabel("2026-09-20"))
    }

    @Test
    fun `非法日期不会抛异常`() {
        assertEquals("坏数据", PracticeFormat.dateLabel("坏数据"))
        assertEquals("", PracticeFormat.weekdayLabel("2026-13-45"))
    }

    @Test
    fun `正常结束时刻照常显示`() {
        val start = TestTime.at(2026, 9, 21, 14, 5)
        assertEquals(
            "14:32",
            PracticeFormat.sessionEndLabel(start, TestTime.at(2026, 9, 21, 14, 32))
        )
        // 同一天内以 00:00 结束（不是跨天）时照常显示 00:00。
        assertEquals(
            "00:00",
            PracticeFormat.sessionEndLabel(start, TestTime.at(2026, 9, 21, 0, 0))
        )
    }
}
