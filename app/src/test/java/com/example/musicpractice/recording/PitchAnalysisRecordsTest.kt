package com.example.musicpractice.recording

import com.example.musicpractice.pitch.PitchAnalysis
import com.example.musicpractice.pitch.PitchData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 音准分析结果 ↔ 数据库表记录的换算（需求三、六、九）。
 *
 * 重点验证"存下来再读回来是同一份"：分析用的基准音高、分析时间、每个时间点的
 * 音名 / 音分 / 是否有效 / 原始频率，一个都不能丢。
 */
class PitchAnalysisRecordsTest {

    private fun analysis() = PitchAnalysis(
        id = "pitch-rec-1",
        recordingId = "rec-1",
        referenceA4Hz = 441,
        analysisTimeMillis = 1_790_380_900_000L,
        points = listOf(
            PitchData(0L, "A4", -5, true, 439.7),
            PitchData(46L, null, 0, false, 0.0),
            PitchData(92L, "C5", 12, true, 526.6)
        )
    )

    @Test
    fun `分析存成表记录时字段一个不少`() {
        val record = PitchAnalysisRecords.toRecord(analysis())

        assertEquals("pitch-rec-1", record.id)
        assertEquals("rec-1", record.recordingId)
        assertEquals(441, record.referenceA4Hz)
        assertEquals(1_790_380_900_000L, record.analysisTimeMillis)
    }

    @Test
    fun `每个时间点都存下来`() {
        val records = PitchAnalysisRecords.toPointRecords("pitch-rec-1", analysis().points)

        assertEquals(3, records.size)
        assertEquals("pitch-rec-1", records[0].analysisId)
        assertEquals(0L, records[0].timestampMillis)
        assertEquals("A4", records[0].note)
        assertEquals(-5, records[0].cents)
        assertEquals(true, records[0].isValid)
        assertEquals(439.7, records[0].frequencyHz, 1e-9)

        // NOISE 的时间点：没有音名、频率是 0，但"这一片是无效的"必须存住。
        assertNull(records[1].note)
        assertEquals(false, records[1].isValid)
        assertEquals(0.0, records[1].frequencyHz, 1e-9)
    }

    @Test
    fun `存进数据库再读回来是同一份结果`() {
        val original = analysis()
        val record = PitchAnalysisRecords.toRecord(original)
        val points = PitchAnalysisRecords.toPointRecords(record.id, original.points)

        assertEquals(original, PitchAnalysisRecords.toAnalysis(record, points))
    }

    @Test
    fun `没有时间点的分析也能读回来`() {
        val empty = analysis().copy(points = emptyList())

        val restored = PitchAnalysisRecords.toAnalysis(
            PitchAnalysisRecords.toRecord(empty),
            emptyList()
        )

        assertEquals(empty, restored)
        assertEquals(0L, restored.durationMillis)
    }
}
