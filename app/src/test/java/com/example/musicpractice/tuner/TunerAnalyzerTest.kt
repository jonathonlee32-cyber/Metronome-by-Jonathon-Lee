package com.example.musicpractice.tuner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** 可信度判断：什么算有效识别、什么进 NOISE、A4 基准与音域如何起作用。 */
class TunerAnalyzerTest {

    private fun analyzer(a4Hz: Int = 440) =
        TunerAnalyzer(TunerTestSignals.SAMPLE_RATE, a4Hz)

    /** 某个音走 [cents] 音分之后的频率。 */
    private fun frequencyAtCents(midi: Int, cents: Double, a4Hz: Double = 440.0): Double =
        TunerNotes.frequencyHz(midi, a4Hz) * 2.0.pow(cents / 1200.0)

    @Test
    fun `稳定的正弦被识别成对应的音`() {
        val cases = listOf(
            220.0 to "A3",
            440.0 to "A4",
            493.883 to "B4",
            523.251 to "C5",
            880.0 to "A5",
            1975.533 to "B6"
        )

        for ((frequency, name) in cases) {
            val reading = analyzer().process(TunerTestSignals.sine(frequency))
            assertTrue("$frequency Hz 应该是有效识别", reading.isReliable)
            assertEquals(name, reading.match!!.name)
        }
    }

    @Test
    fun `静音进入 NOISE`() {
        assertFalse(analyzer().process(TunerTestSignals.silence()).isReliable)
    }

    @Test
    fun `音量过低进入 NOISE`() {
        // 振幅 0.005 的正弦：虽然很干净，但 RMS 低于阈值，算"没有明显声音"。
        val faint = TunerTestSignals.sine(440.0, amplitude = 0.005f)
        assertFalse(analyzer().process(faint).isReliable)
    }

    @Test
    fun `白噪声进入 NOISE`() {
        assertFalse(analyzer().process(TunerTestSignals.noise(amplitude = 0.4f)).isReliable)
    }

    @Test
    fun `低于 A3 的声音进入 NOISE`() {
        // 200Hz 很响也很"干净"，但它已经更接近音域外的 G3，不能硬凑成 A3。
        assertFalse(analyzer().process(TunerTestSignals.sine(200.0)).isReliable)
    }

    @Test
    fun `高于 B6 的声音进入 NOISE`() {
        assertFalse(analyzer().process(TunerTestSignals.sine(2100.0)).isReliable)
    }

    @Test
    fun `音分偏差按实际频率算`() {
        val reading = analyzer().process(TunerTestSignals.sine(frequencyAtCents(69, -22.0)))
        assertTrue(reading.isReliable)
        assertEquals("A4", reading.match!!.name)
        assertEquals(-22, reading.match!!.centsRounded)
    }

    @Test
    fun `A4 基准 442 时 442Hz 是准音`() {
        val reading = analyzer(a4Hz = 442).process(TunerTestSignals.sine(442.0))
        assertTrue(reading.isReliable)
        assertEquals("A4", reading.match!!.name)
        assertEquals(0, reading.match!!.centsRounded)
    }

    @Test
    fun `A4 基准 432 时 440Hz 偏高约 32 音分`() {
        val reading = analyzer(a4Hz = 432).process(TunerTestSignals.sine(440.0))
        assertTrue(reading.isReliable)
        assertEquals("A4", reading.match!!.name)
        // 1200 × log2(440 / 432) ≈ 31.8 音分。
        assertEquals(32, reading.match!!.centsRounded)
    }

    @Test
    fun `改基准后同一帧的偏差立刻跟着变`() {
        val local = analyzer(a4Hz = 440)
        val frame = TunerTestSignals.sine(442.0)
        assertEquals(8, local.process(frame).match!!.centsRounded)

        local.setReferenceA4Hz(442)
        assertEquals(0, local.process(frame).match!!.centsRounded)
    }

    @Test
    fun `超出范围的基准会被夹住`() {
        val local = analyzer()
        local.setReferenceA4Hz(400)
        assertEquals(TunerSettingsStore.MIN_A4_HZ, local.a4Hz)
        local.setReferenceA4Hz(500)
        assertEquals(TunerSettingsStore.MAX_A4_HZ, local.a4Hz)
    }

    @Test
    fun `单帧跳变会被中值平滑压掉`() {
        val local = analyzer()
        val a4 = TunerTestSignals.sine(440.0)
        // 先连续几帧稳定的 A4。
        repeat(3) { local.process(a4) }

        // 突然来一帧 A5（模拟起音瞬间的八度误判）。
        val jump = local.process(TunerTestSignals.sine(880.0))
        assertEquals("一帧跳变后仍然应该是 A4", "A4", jump.match!!.name)

        // 但真的换音（连续吹 A5）时，很快就应该跟上。
        local.process(TunerTestSignals.sine(880.0))
        val settled = local.process(TunerTestSignals.sine(880.0))
        assertEquals("连续几帧 A5 之后应该跟到 A5", "A5", settled.match!!.name)
    }

    @Test
    fun `reset 之后没有残留状态`() {
        val local = analyzer()
        repeat(3) { local.process(TunerTestSignals.sine(440.0)) }
        local.reset()
        val reading = local.process(TunerTestSignals.sine(880.0))
        assertEquals("A5", reading.match!!.name)
    }
}
