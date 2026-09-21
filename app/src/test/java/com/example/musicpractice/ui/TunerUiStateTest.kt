package com.example.musicpractice.ui

import com.example.musicpractice.tuner.NoteMatch
import com.example.musicpractice.tuner.TunerNotes
import com.example.musicpractice.tuner.TunerReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** 调音器界面状态：初始状态、NOISE 保留上一次结果、绿色反馈的条件。 */
class TunerUiStateTest {

    private fun match(midi: Int, cents: Double, name: String = TunerNotes.nameOf(midi)): NoteMatch =
        NoteMatch(
            midi = midi,
            name = name,
            targetFrequencyHz = TunerNotes.frequencyHz(midi, 440.0),
            cents = cents
        )

    private fun reading(cents: Double, frequencyHz: Double = 441.27): TunerReading =
        TunerReading(
            isReliable = true,
            match = match(69, cents, "A4"),
            frequencyHz = frequencyHz
        )

    @Test
    fun `初次进入显示占位符并且 NOISE 是蓝的`() {
        val state = TunerUiState()

        assertEquals("--", state.noteText)
        assertEquals("--", state.centsText)
        assertFalse(state.hasNote)
        assertTrue("还没有任何识别结果时 NOISE 应该是蓝的", state.isNoise)
        assertFalse(state.isInTune)
        assertEquals(440, state.a4Hz)
        assertEquals(MicPermissionState.NEEDS_PERMISSION, state.micPermission)
    }

    @Test
    fun `有效识别更新音名和音分且 NOISE 变灰`() {
        val state = TunerUiState().withReading(reading(5.0))

        assertEquals("A4", state.noteText)
        assertEquals("+5", state.centsText)
        assertFalse(state.isNoise)
        assertTrue(state.isInTune)
    }

    @Test
    fun `丢失信号时保留上一次的音名和音分`() {
        val tuned = TunerUiState().withReading(reading(5.0))
        val noise = tuned.withReading(TunerReading.NOISE)

        assertEquals("A4", noise.noteText)
        assertEquals("+5", noise.centsText)
        assertTrue(noise.isNoise)
        // 上一次的结果还在，只是不再显示绿色：绿色代表"现在吹准了"。
        assertFalse(noise.isInTune)
        assertEquals(441.27, noise.frequencyHz!!, 0.01)
    }

    @Test
    fun `重新识别后回到灰色并更新数值`() {
        val noise = TunerUiState()
            .withReading(reading(5.0))
            .withReading(TunerReading.NOISE)
        val recovered = noise.withReading(reading(-3.0))

        assertEquals("A4", recovered.noteText)
        assertEquals("-3", recovered.centsText)
        assertFalse(recovered.isNoise)
    }

    @Test
    fun `正负 10 音分内显示绿色`() {
        assertTrue(TunerUiState().withReading(reading(0.0)).isInTune)
        assertTrue(TunerUiState().withReading(reading(5.0)).isInTune)
        assertTrue(TunerUiState().withReading(reading(-10.0)).isInTune)
        assertTrue(TunerUiState().withReading(reading(10.0)).isInTune)
    }

    @Test
    fun `超过正负 10 音分不显示绿色但仍然显示音符`() {
        val plus22 = TunerUiState().withReading(reading(22.0))
        assertFalse(plus22.isInTune)
        assertEquals("+22", plus22.centsText)

        val minus49 = TunerUiState().withReading(reading(-49.0))
        assertFalse(minus49.isInTune)
        assertEquals("-49", minus49.centsText)

        val plus11 = TunerUiState().withReading(reading(11.0))
        assertFalse(plus11.isInTune)
    }

    @Test
    fun `音分文本带正负号`() {
        assertEquals("0", TunerUiState().withReading(reading(0.0)).centsText)
        assertEquals("+5", TunerUiState().withReading(reading(5.0)).centsText)
        assertEquals("-22", TunerUiState().withReading(reading(-22.0)).centsText)
        assertEquals("+50", TunerUiState().withReading(reading(50.0)).centsText)
        assertEquals("-50", TunerUiState().withReading(reading(-50.0)).centsText)
    }

    @Test
    fun `改 A4 基准会用上一次的频率重算音分`() {
        // 实际 442Hz：440 基准下偏高 8 音分。
        val state = TunerUiState().withReading(
            TunerReading(
                isReliable = true,
                match = match(69, 8.0, "A4"),
                frequencyHz = 442.0
            )
        )
        assertEquals("+8", state.centsText)

        // 把基准改成 442：同一个频率就准了。
        val changed = state.withReferenceA4Hz(442)
        assertEquals(442, changed.a4Hz)
        assertEquals("A4", changed.noteText)
        assertEquals("0", changed.centsText)
        assertTrue(changed.isInTune)
    }

    @Test
    fun `还没有测到频率时改基准只改数字`() {
        val state = TunerUiState().withReferenceA4Hz(432)

        assertEquals(432, state.a4Hz)
        assertEquals("--", state.noteText)
        assertEquals("--", state.centsText)
    }

    @Test
    fun `四舍五入到整数音分决定绿色`() {
        // 10.4 音分显示成 "+10"，显示与反馈一致，所以也显示绿色。
        val state = TunerUiState().withReading(reading(10.4))
        assertEquals("+10", state.centsText)
        assertTrue(state.isInTune)

        val overRounded = TunerUiState().withReading(reading(10.6))
        assertEquals("+11", overRounded.centsText)
        assertFalse(overRounded.isInTune)
    }

    @Test
    fun `识别到相邻音时更新音名`() {
        val state = TunerUiState().withReading(
            TunerReading(
                isReliable = true,
                match = match(70, -49.0, "A#4"),
                frequencyHz = TunerNotes.frequencyHz(70, 440.0) * 2.0.pow(-49.0 / 1200.0)
            )
        )
        assertEquals("A#4", state.noteText)
        assertEquals("-49", state.centsText)
        assertFalse(state.isInTune)
    }
}
