package com.example.musicpractice.pitch

import com.example.musicpractice.tuner.TunerAudioEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * 逐时间片的音高检测（需求二、十）。
 *
 * 这里直接喂合成波形，验证三件事：
 * 1. 调音器那套算法（YIN + 可信度 + 跨帧平滑）在离线分析里被原样用上了 —— 准音判准、噪声判噪；
 * 2. 时间戳按半帧递增（46 毫秒一个点），播放时才能"按时间取到正确的一片"；
 * 3. 长音频是流式处理的：一段几十秒的音频分很多次喂进去，结果和一次喂完一样。
 */
class PitchTrackAnalyzerTest {

    private val sampleRate = TunerAudioEngine.SAMPLE_RATE
    private val frameSize = TunerAudioEngine.FRAME_SIZE
    private val hopSize = TunerAudioEngine.HOP_SIZE

    /** 生成一段正弦波（模拟乐器单音）。 */
    private fun sine(frequencyHz: Double, sampleCount: Int, amplitude: Double = 0.5): FloatArray =
        FloatArray(sampleCount) { index ->
            (amplitude * sin(2.0 * PI * frequencyHz * index / sampleRate)).toFloat()
        }

    private fun silence(sampleCount: Int) = FloatArray(sampleCount)

    private fun noise(sampleCount: Int, seed: Int = 7): FloatArray {
        val random = Random(seed)
        return FloatArray(sampleCount) { (random.nextDouble(-1.0, 1.0) * 0.5).toFloat() }
    }

    @Test
    fun `准音识别成 A4 而且音分接近零`() {
        val analyzer = PitchTrackAnalyzer(sampleRate, referenceA4Hz = 440)

        val points = analyzer.accept(sine(440.0, sampleCount = hopSize * 10))

        assertTrue("应该分析出多个时间点", points.size >= 5)
        val last = points.last()
        assertTrue("440Hz 应该是有效音", last.isValid)
        assertEquals("A4", last.note)
        assertTrue("音分应该接近 0，实际 ${last.cents}", abs(last.cents) <= 2)
        assertEquals(440.0, last.frequencyHz, 5.0)
    }

    @Test
    fun `偏高八音分会被算出来`() {
        val analyzer = PitchTrackAnalyzer(sampleRate, referenceA4Hz = 440)
        // 442Hz 相对 A4=440 高约 +7.85 音分。
        val target = 440.0 * Math.pow(2.0, 8.0 / 1200.0)

        val points = analyzer.accept(sine(target, sampleCount = hopSize * 10))

        val last = points.last()
        assertTrue(last.isValid)
        assertEquals("A4", last.note)
        assertTrue("音分应该在 +8 附近，实际 ${last.cents}", abs(last.cents - 8) <= 2)
    }

    @Test
    fun `换一个基准音高，同一个音的音分跟着变`() {
        val samples = sine(441.0, sampleCount = hopSize * 10)

        val at440 = PitchTrackAnalyzer(sampleRate, referenceA4Hz = 440)
            .accept(samples).last()
        val at442 = PitchTrackAnalyzer(sampleRate, referenceA4Hz = 442)
            .accept(samples).last()

        assertTrue(abs(at440.cents - 4) <= 2)
        assertTrue(abs(at442.cents + 4) <= 2)
    }

    @Test
    fun `静音全部判成 NOISE`() {
        val analyzer = PitchTrackAnalyzer(sampleRate, referenceA4Hz = 440)

        val points = analyzer.accept(silence(hopSize * 10))

        assertTrue(points.isNotEmpty())
        assertTrue(points.none { it.isValid })
        assertTrue(points.all { it.stateText == PitchData.NOISE_LABEL })
    }

    @Test
    fun `白噪声基本判成 NOISE`() {
        val analyzer = PitchTrackAnalyzer(sampleRate, referenceA4Hz = 440)

        val points = analyzer.accept(noise(hopSize * 20))

        val validCount = points.count { it.isValid }
        assertTrue("噪声里不应该有大量有效音（实际 $validCount / ${points.size}）", validCount <= points.size / 2)
    }

    @Test
    fun `时间戳按半帧递增`() {
        val analyzer = PitchTrackAnalyzer(sampleRate, referenceA4Hz = 440)

        val points = analyzer.accept(sine(440.0, sampleCount = hopSize * 6))

        assertTrue(points.size >= 4)
        // 第一个点出现在"攒满一帧"时（两跳），之后每跳一个点。
        assertEquals((frameSize * 1000L) / sampleRate, points.first().timestampMillis)
        // 一跳约 46 毫秒（毫秒取整时会 46 / 47 交替，所以不写死某一个值）。
        val step = points[1].timestampMillis - points[0].timestampMillis
        assertTrue("相邻两点间隔应该在 46 毫秒附近，实际 $step", step in 44L..48L)
        // 时间戳严格递增，播放时二分查找才有意义。
        assertTrue(points.zipWithNext().all { (a, b) -> b.timestampMillis > a.timestampMillis })
    }

    @Test
    fun `一次喂完和分多次喂结果一样`() {
        val samples = sine(440.0, sampleCount = hopSize * 12)

        val whole = PitchTrackAnalyzer(sampleRate, 440).accept(samples)
        val piecewise = PitchTrackAnalyzer(sampleRate, 440).let { analyzer ->
            val collected = ArrayList<PitchData>()
            var index = 0
            // 故意按"不是半帧整数倍"的块大小切，模拟解码器给多少算多少。
            val chunk = 1_000
            while (index < samples.size) {
                val take = minOf(chunk, samples.size - index)
                collected.addAll(analyzer.accept(samples.copyOfRange(index, index + take), take))
                index += take
            }
            collected
        }

        assertEquals(whole.size, piecewise.size)
        assertEquals(whole.map { it.timestampMillis }, piecewise.map { it.timestampMillis })
        assertEquals(whole.map { it.note }, piecewise.map { it.note })
        assertEquals(whole.map { it.cents }, piecewise.map { it.cents })
    }

    @Test
    fun `不够一帧时没有结果`() {
        val analyzer = PitchTrackAnalyzer(sampleRate, referenceA4Hz = 440)

        assertTrue(analyzer.accept(sine(440.0, sampleCount = frameSize - 1)).isEmpty())
        assertTrue(analyzer.accept(FloatArray(0)).isEmpty())
        assertTrue(analyzer.finish().isEmpty())
    }

    @Test
    fun `一段几十秒的音频能一路分析下去`() {
        val analyzer = PitchTrackAnalyzer(sampleRate, referenceA4Hz = 440)
        val seconds = 30
        val totalSamples = sampleRate * seconds
        val tone = sine(440.0, sampleCount = totalSamples)

        var points = 0
        var index = 0
        // 按 8192 个采样点一块喂（约 186 毫秒，和解码器给数据的粒度接近）。
        while (index < totalSamples) {
            val take = minOf(8_192, totalSamples - index)
            points += analyzer.accept(tone.copyOfRange(index, index + take), take).size
            index += take
        }

        // 30 秒 × 约 21.5 个点/秒 ≈ 645 个点。
        assertTrue("点太少：$points", points > 600)
        assertTrue("点太多：$points", points < 700)
        assertFalse(points == 0)
    }
}
