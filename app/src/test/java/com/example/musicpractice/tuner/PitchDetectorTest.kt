package com.example.musicpractice.tuner

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** YIN 基频检测：正弦、带泛音的乐器音、噪声与静音。 */
class PitchDetectorTest {

    private val detector = PitchDetector(TunerTestSignals.SAMPLE_RATE)

    /** 两个频率之间的音分差，用来描述"差了多少"。 */
    private fun centsOf(detected: Double, expected: Double): Double =
        TunerNotes.centsBetween(detected, expected)

    private fun assertDetects(expectedHz: Double, samples: FloatArray, toleranceCents: Double = 6.0) {
        val detection = detector.analyze(samples)
        assertNotNull("应该检测到 $expectedHz Hz，但没有检测结果", detection)
        val cents = centsOf(detection!!.frequencyHz.toDouble(), expectedHz)
        assertTrue(
            "期望 $expectedHz Hz，检测到 ${detection.frequencyHz} Hz（偏差 $cents 音分）",
            abs(cents) <= toleranceCents
        )
        assertTrue("置信度应该很高，实际 ${detection.clarity}", detection.clarity > 0.9f)
    }

    @Test
    fun `识别音域内的各个音都测得准`() {
        // A3、A4、B4、C5、A5、B6 —— 需求点名要测的六个音。
        val cases = listOf(220.0, 440.0, 493.883, 523.251, 880.0, 1975.533)
        for (frequency in cases) {
            assertDetects(frequency, TunerTestSignals.sine(frequency))
        }
    }

    @Test
    fun `带泛音的乐器音识别出基频而不是高次谐波`() {
        // A3 带 5 个谐波：如果算法去挑最强的频谱峰，很容易报成 440 或 660。
        val samples = TunerTestSignals.harmonicTone(220.0)
        val detection = detector.analyze(samples)!!
        val cents = centsOf(detection.frequencyHz.toDouble(), 220.0)
        assertTrue(
            "带泛音的 A3 应该报 220Hz，实际 ${detection.frequencyHz} Hz（$cents 音分）",
            abs(cents) <= 10.0
        )
    }

    @Test
    fun `音量很小但很干净的声音仍然测得到`() {
        val quiet = TunerTestSignals.sine(440.0, amplitude = 0.01f)
        assertDetects(440.0, quiet, toleranceCents = 10.0)
    }

    @Test
    fun `静音没有检测结果`() {
        assertNull(detector.analyze(TunerTestSignals.silence()))
    }

    @Test
    fun `白噪声的置信度很低`() {
        val detection = detector.analyze(TunerTestSignals.noise(amplitude = 0.3f))
        // 噪声也可能被算出某个"频率"，关键是置信度必须低到不足以当作有效音。
        if (detection != null) {
            assertTrue(
                "白噪声的置信度应该很低，实际 ${detection.clarity}",
                detection.clarity < TunerAnalyzer.MIN_CLARITY
            )
        }
    }

    @Test
    fun `连续多帧不会出错也不会漂移`() {
        val samples = TunerTestSignals.sine(329.628) // E4
        var previous = 0.0
        repeat(50) {
            val detection = detector.analyze(samples)!!
            if (previous != 0.0) {
                assertTrue(
                    "连续帧的结果应该稳定：上一帧 $previous Hz，这一帧 ${detection.frequencyHz} Hz",
                    abs(centsOf(detection.frequencyHz.toDouble(), previous)) < 0.5
                )
            }
            previous = detection.frequencyHz.toDouble()
        }
    }
}
