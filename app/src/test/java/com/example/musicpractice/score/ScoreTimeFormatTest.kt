package com.example.musicpractice.score

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

/**
 * 最近项目列表里"什么时候打开的"这句话。
 *
 * 所有用例都显式造出一个"现在"和"当时"，所以不受测试运行的时刻影响。
 */
class ScoreTimeFormatTest {

    /** 本机时区下的某个时刻。 */
    private fun at(
        year: Int,
        month: Int,
        day: Int,
        hour: Int = 0,
        minute: Int = 0
    ): Long = Calendar.getInstance().apply {
        clear()
        set(year, month - 1, day, hour, minute, 0)
    }.timeInMillis

    @Test
    fun `从没打开过时给出兜底说法`() {
        assertEquals("尚未打开", ScoreTimeFormat.describeLastOpened(0L, at(2026, 9, 26, 10, 30)))
    }

    @Test
    fun `一分钟以内是刚刚打开`() {
        val now = at(2026, 9, 26, 10, 30)

        assertEquals("刚刚打开", ScoreTimeFormat.describeLastOpened(now - 5_000L, now))
        assertEquals("刚刚打开", ScoreTimeFormat.describeLastOpened(now - 59_000L, now))
    }

    @Test
    fun `今天一小时以内说几分钟前`() {
        val now = at(2026, 9, 26, 10, 30)

        assertEquals("30 分钟前打开", ScoreTimeFormat.describeLastOpened(now - 30 * 60_000L, now))
    }

    @Test
    fun `今天超过一小时给出具体时刻`() {
        val now = at(2026, 9, 26, 10, 30)

        assertEquals("今天 09:00 打开", ScoreTimeFormat.describeLastOpened(at(2026, 9, 26, 9, 0), now))
    }

    @Test
    fun `隔了一个自然日就是昨天 哪怕只过了几个小时`() {
        // 昨天 23:50 打开，现在刚过零点 00:10：只过了 20 分钟，但按日历算已经是昨天。
        val now = at(2026, 9, 26, 0, 10)

        assertEquals("昨天打开", ScoreTimeFormat.describeLastOpened(at(2026, 9, 25, 23, 50), now))
    }

    @Test
    fun `两到六天前说几天前`() {
        val now = at(2026, 9, 26, 10, 30)

        assertEquals("3 天前打开", ScoreTimeFormat.describeLastOpened(at(2026, 9, 23, 8, 0), now))
        assertEquals("6 天前打开", ScoreTimeFormat.describeLastOpened(at(2026, 9, 20, 23, 0), now))
    }

    @Test
    fun `更早给出具体日期`() {
        val now = at(2026, 9, 26, 10, 30)

        assertEquals("2026.09.10 打开", ScoreTimeFormat.describeLastOpened(at(2026, 9, 10, 15, 0), now))
    }

    @Test
    fun `时间跑到未来时按刚刚打开算`() {
        // 用户把系统时间往回调过：不能显示成"-2 天前打开"这种读不通的话。
        val now = at(2026, 9, 26, 10, 30)

        assertEquals("刚刚打开", ScoreTimeFormat.describeLastOpened(now + 3 * 24 * 3_600_000L, now))
    }
}
