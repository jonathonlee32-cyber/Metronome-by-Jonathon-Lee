package com.example.musicpractice.tuner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** 十二平均律：音名、理论频率、音分偏差、识别音域与显示格式。 */
class TunerNotesTest {

    /** 从某个音出发走 [cents] 音分之后的实际频率。 */
    private fun frequencyAtCents(midi: Int, cents: Double, a4Hz: Double = 440.0): Double =
        TunerNotes.frequencyHz(midi, a4Hz) * 2.0.pow(cents / 1200.0)

    @Test
    fun `A4 基准 440 时各音的理论频率`() {
        // 需求里点名的几个音：A3、A4、B4、C5、A5、B6。
        assertEquals(220.0, TunerNotes.frequencyHz(57, 440.0), 1e-6)
        assertEquals(440.0, TunerNotes.frequencyHz(69, 440.0), 1e-6)
        assertEquals(493.883, TunerNotes.frequencyHz(71, 440.0), 1e-3)
        assertEquals(523.251, TunerNotes.frequencyHz(72, 440.0), 1e-3)
        assertEquals(880.0, TunerNotes.frequencyHz(81, 440.0), 1e-6)
        assertEquals(1975.533, TunerNotes.frequencyHz(95, 440.0), 1e-3)
    }

    @Test
    fun `改 A4 基准后所有音的理论频率跟着变`() {
        // A4 = 442 时，A4 自己就是 442；其他音按同样的比例缩放。
        assertEquals(442.0, TunerNotes.frequencyHz(69, 442.0), 1e-9)
        assertEquals(221.0, TunerNotes.frequencyHz(57, 442.0), 1e-9)
        assertEquals(884.0, TunerNotes.frequencyHz(81, 442.0), 1e-9)
        // 12 个半音正好翻一倍。
        assertEquals(2.0, TunerNotes.frequencyHz(81, 440.0) / TunerNotes.frequencyHz(69, 440.0), 1e-9)
    }

    @Test
    fun `音名按科学音高记法`() {
        assertEquals("A3", TunerNotes.nameOf(57))
        assertEquals("A#3", TunerNotes.nameOf(58))
        assertEquals("B3", TunerNotes.nameOf(59))
        assertEquals("C4", TunerNotes.nameOf(60))
        assertEquals("A4", TunerNotes.nameOf(69))
        assertEquals("B6", TunerNotes.nameOf(95))
    }

    @Test
    fun `识别音域是 A3 到 B6`() {
        assertEquals("A3", TunerNotes.nameOf(TunerNotes.MIN_MIDI))
        assertEquals("B6", TunerNotes.nameOf(TunerNotes.MAX_MIDI))
        assertEquals(57, TunerNotes.MIN_MIDI)
        assertEquals(95, TunerNotes.MAX_MIDI)
    }

    @Test
    fun `准音匹配到对应的音且偏差为 0`() {
        val match = TunerNotes.match(440.0, 440.0)
        assertNotNull(match)
        assertEquals("A4", match!!.name)
        assertEquals(0, match.centsRounded)
        assertTrue(TunerNotes.isInTune(match.centsRounded))
    }

    @Test
    fun `正负偏差的音分数值正确`() {
        val plus5 = TunerNotes.match(frequencyAtCents(69, 5.0), 440.0)!!
        assertEquals("A4", plus5.name)
        assertEquals(5, plus5.centsRounded)

        val minus22 = TunerNotes.match(frequencyAtCents(69, -22.0), 440.0)!!
        assertEquals("A4", minus22.name)
        assertEquals(-22, minus22.centsRounded)

        val plus49 = TunerNotes.match(frequencyAtCents(69, 49.0), 440.0)!!
        assertEquals(49, plus49.centsRounded)
    }

    @Test
    fun `正负 50 音分是归属边界`() {
        // 正好 +50：落在 A4 和 A#4 的正中间，四舍五入归到上方的 A#4，偏差 -50。
        val atPlus50 = TunerNotes.match(frequencyAtCents(69, 50.0), 440.0)
        assertNotNull(atPlus50)
        assertEquals("A#4", atPlus50!!.name)
        assertEquals(-50, atPlus50.centsRounded)

        // 正好 -50：落在 G#4 和 A4 中间，归到上方的 A4，偏差 -50。
        val atMinus50 = TunerNotes.match(frequencyAtCents(69, -50.0), 440.0)
        assertNotNull(atMinus50)
        assertEquals("A4", atMinus50!!.name)
        assertEquals(-50, atMinus50.centsRounded)
    }

    @Test
    fun `超过半个半音就会归到相邻的音`() {
        // A4 高 51 音分，已经更像 A#4（低 49 音分）。
        val above = TunerNotes.match(frequencyAtCents(69, 51.0), 440.0)!!
        assertEquals("A#4", above.name)
        assertEquals(-49, above.centsRounded)

        // A4 低 51 音分，已经更像 G#4（高 49 音分）。
        val below = TunerNotes.match(frequencyAtCents(69, -51.0), 440.0)!!
        assertEquals("G#4", below.name)
        assertEquals(49, below.centsRounded)
    }

    @Test
    fun `低于 A3 的声音不是有效音`() {
        // 明显低于 A3 的声音：最近的音已经落到音域外的 G3 及以下 —— 按需求视为没有有效音符。
        assertNull(TunerNotes.match(200.0, 440.0))
        assertNull(TunerNotes.match(150.0, 440.0))
        assertNull(TunerNotes.match(100.0, 440.0))
        assertNull(TunerNotes.match(60.0, 440.0))
    }

    @Test
    fun `A3 下方半个半音以内仍然算 A3`() {
        // ±50 音分是"属于这个音"的范围：A3 低 40 音分还没有更接近别的音（G#3 在音域外），
        // 所以算作偏低的 A3，而不是"没有音符"。
        val lowA3 = TunerNotes.match(frequencyAtCents(57, -40.0), 440.0)!!
        assertEquals("A3", lowA3.name)
        assertEquals(-40, lowA3.centsRounded)

        // 再低一点（-60 音分）就已经更接近 G#3 了，于是超出识别音域。
        assertNull(TunerNotes.match(frequencyAtCents(57, -60.0), 440.0))
    }

    @Test
    fun `高于 B6 的声音不是有效音`() {
        // 2100Hz 已经超过 B6 超过 50 音分。
        assertNull(TunerNotes.match(2100.0, 440.0))
        assertNull(TunerNotes.match(3000.0, 440.0))
    }

    @Test
    fun `音域边缘内侧仍然能识别`() {
        // 比 A3 高 40 音分：仍然算 A3，只是偏低。
        val lowEdge = TunerNotes.match(frequencyAtCents(57, 40.0), 440.0)!!
        assertEquals("A3", lowEdge.name)
        assertEquals(40, lowEdge.centsRounded)

        // 比 B6 低 40 音分：仍然算 B6。
        val highEdge = TunerNotes.match(frequencyAtCents(95, -40.0), 440.0)!!
        assertEquals("B6", highEdge.name)
        assertEquals(-40, highEdge.centsRounded)
    }

    @Test
    fun `A4 基准变化会改变偏差`() {
        // 实际吹出 442Hz：A4 = 440 时偏高约 +8 音分，A4 = 442 时正好准。
        val at440 = TunerNotes.match(442.0, 440.0)!!
        assertEquals("A4", at440.name)
        assertEquals(8, at440.centsRounded)

        val at442 = TunerNotes.match(442.0, 442.0)!!
        assertEquals("A4", at442.name)
        assertEquals(0, at442.centsRounded)
    }

    @Test
    fun `很接近 A4 但仍然属于 A4`() {
        // 需求点名的 ±10 音分边界：正好 10 算准，11 不算。
        val at10 = TunerNotes.match(frequencyAtCents(69, 10.0), 440.0)!!
        assertEquals(10, at10.centsRounded)
        assertTrue(TunerNotes.isInTune(at10.centsRounded))

        val atMinus10 = TunerNotes.match(frequencyAtCents(69, -10.0), 440.0)!!
        assertEquals(-10, atMinus10.centsRounded)
        assertTrue(TunerNotes.isInTune(atMinus10.centsRounded))

        val at11 = TunerNotes.match(frequencyAtCents(69, 11.0), 440.0)!!
        assertEquals(11, at11.centsRounded)
        assertFalse(TunerNotes.isInTune(at11.centsRounded))
    }

    @Test
    fun `音分显示格式`() {
        assertEquals("+5", TunerNotes.formatCents(5))
        assertEquals("0", TunerNotes.formatCents(0))
        assertEquals("-22", TunerNotes.formatCents(-22))
        assertEquals("+50", TunerNotes.formatCents(50))
        assertEquals("-50", TunerNotes.formatCents(-50))
    }

    @Test
    fun `非法输入不会算出音符`() {
        assertNull(TunerNotes.match(0.0, 440.0))
        assertNull(TunerNotes.match(-100.0, 440.0))
        assertNull(TunerNotes.match(Double.NaN, 440.0))
    }
}
