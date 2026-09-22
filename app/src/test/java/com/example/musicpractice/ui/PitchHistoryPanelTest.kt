package com.example.musicpractice.ui

import com.example.musicpractice.tuner.TunerNotes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 音准历史栏目里"音分到垂直位置"的换算：+50 音分在顶部、0 音分在正中央、
 * -50 音分在底部，超出的往边界夹住（轨迹不会跑出栏目）。
 */
class PitchHistoryPanelTest {

    private val maxCents = TunerNotes.NOTE_RANGE_CENTS.toInt()

    @Test
    fun `零音分在正中央 正负五十在上下两端`() {
        assertEquals(0.5f, centsToVerticalFraction(0), 1e-6f)
        assertEquals(0f, centsToVerticalFraction(maxCents), 1e-6f)
        assertEquals(1f, centsToVerticalFraction(-maxCents), 1e-6f)
    }

    @Test
    fun `中间的音分落在中心线和边界线之间`() {
        // +25 音分正好在中心线到顶部线的一半处。
        assertEquals(0.25f, centsToVerticalFraction(25), 1e-6f)
        // -15 音分在中心线以下的区域里。
        val below = centsToVerticalFraction(-15)
        assertTrue("-15 音分应该在中心线下方、底部上方", below > 0.5f && below < 1f)
    }

    @Test
    fun `超出正负五十音分时夹到边界`() {
        assertEquals(0f, centsToVerticalFraction(120), 1e-6f)
        assertEquals(1f, centsToVerticalFraction(-120), 1e-6f)
    }

    @Test
    fun `正负十音分是中间绿色区域的上下边界`() {
        assertEquals(0.4f, centsToVerticalFraction(TunerNotes.IN_TUNE_CENTS), 1e-6f)
        assertEquals(0.6f, centsToVerticalFraction(-TunerNotes.IN_TUNE_CENTS), 1e-6f)
    }
}
