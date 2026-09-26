package com.example.musicpractice.pitch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 音准分析的数据结构（需求三、四、七、八）。
 *
 * 这里验证的是"播放到某一刻该显示哪一片数据"以及"改了基准音高之后数值怎么变"，
 * 都是纯逻辑，不需要音频也不需要数据库。
 */
class PitchDataTest {

    private fun valid(
        timestamp: Long,
        note: String,
        cents: Int,
        frequencyHz: Double
    ) = PitchData(
        timestampMillis = timestamp,
        note = note,
        cents = cents,
        isValid = true,
        frequencyHz = frequencyHz
    )

    private fun noise(timestamp: Long) = PitchData(
        timestampMillis = timestamp,
        note = null,
        cents = 0,
        isValid = false,
        frequencyHz = 0.0
    )

    private fun analysis(points: List<PitchData>) = PitchAnalysis(
        id = "pitch-rec-1",
        recordingId = "rec-1",
        referenceA4Hz = 440,
        analysisTimeMillis = 1_774_000_000_000L,
        points = points
    )

    @Test
    fun `播放位置落在两个时间点之间时取前一个`() {
        val result = analysis(listOf(valid(0, "A4", 0, 440.0), valid(46, "A4", 3, 441.0), valid(92, "C5", -4, 523.0)))

        assertEquals(0L, result.pointAt(0L)?.timestampMillis)
        assertEquals(0L, result.pointAt(45L)?.timestampMillis)
        assertEquals(46L, result.pointAt(46L)?.timestampMillis)
        assertEquals(46L, result.pointAt(91L)?.timestampMillis)
        assertEquals(92L, result.pointAt(1_000L)?.timestampMillis)
    }

    @Test
    fun `播放位置早于第一个点或没有数据时返回空`() {
        val result = analysis(listOf(valid(1_000, "A4", 0, 440.0)))

        assertNull(result.pointAt(0L))
        assertNull(result.pointAt(999L))
        assertNull(analysis(emptyList()).pointAt(5_000L))
    }

    @Test
    fun `无效时间点显示 NOISE 和占位符`() {
        val point = noise(500L)

        assertEquals("NOISE", point.stateText)
        assertEquals("--", point.noteText)
        assertEquals("--", point.centsText)
        assertEquals(false, point.isValid)
    }

    @Test
    fun `有效时间点显示音名和带符号的音分`() {
        assertEquals("有效音符", valid(0, "A4", 12, 443.0).stateText)
        assertEquals("A4", valid(0, "A4", 12, 443.0).noteText)
        assertEquals("+12", valid(0, "A4", 12, 443.0).centsText)
        assertEquals("-8", valid(0, "A4", -8, 438.0).centsText)
    }

    @Test
    fun `改基准音高时用原始频率重算音分`() {
        // 441Hz 在 A4=440 时偏高约 +3.93 音分（界面上是 +4）。
        val point = valid(0, "A4", 4, 441.0)

        val at442 = point.withReference(442)

        // 441Hz 相对 442Hz 低约 3.92 音分 → -4。
        assertEquals("A4", at442.note)
        assertEquals(-4, at442.cents)
        assertEquals(441.0, at442.frequencyHz, 1e-9)
    }

    @Test
    fun `改基准音高不会碰无效时间点`() {
        val point = noise(0L)

        assertSame(point, point.withReference(448))
    }

    @Test
    fun `整段结果的时长和有效音占比`() {
        val result = analysis(
            listOf(
                valid(0, "A4", 0, 440.0),
                noise(46),
                valid(92, "A4", 0, 440.0),
                noise(138)
            )
        )

        assertEquals(138L, result.durationMillis)
        assertEquals(0.5f, result.validRatio, 1e-6f)
        assertEquals(0f, analysis(emptyList()).validRatio, 1e-6f)
    }

    @Test
    fun `整段结果换基准时所有点一起重算`() {
        val result = analysis(listOf(valid(0, "A4", 4, 441.0), noise(46)))

        val updated = result.withReference(442)

        assertEquals(442, updated.referenceA4Hz)
        assertEquals(-4, updated.points[0].cents)
        assertEquals(false, updated.points[1].isValid)
        // 原始频率永远是分析时测到的那个值。
        assertEquals(441.0, updated.points[0].frequencyHz, 1e-9)
    }

    @Test
    fun `分析 ID 由录音 ID 推出来`() {
        assertEquals("pitch-rec-123", PitchAnalysis.idOf("rec-123"))
    }
}
