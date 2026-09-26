package com.example.musicpractice.recording

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * "录音 ↔ 数据库表记录"的换算（需求五：路径 / Uri、文件名称、创建时间、最近播放位置都要存住）。
 *
 * 真机上数据库的读写由 Room 负责，这里验证字段一个不少地存下来、读回来还是原来那条。
 */
class RecordingRecordsTest {

    private fun recording() = Recording(
        id = "rec-1790380800000",
        name = "20260926-001",
        filePath = "/data/user/0/com.example.musicpractice/files/recordings/20260926-001.m4a",
        fileUri = "file:///data/user/0/com.example.musicpractice/files/recordings/20260926-001.m4a",
        createdTimeMillis = 1_790_380_800_000L,
        durationMillis = 340_000L,
        lastPositionMillis = 85_000L
    )

    @Test
    fun `录音存成表记录时字段一个不少`() {
        val record = RecordingRecords.toRecord(recording())

        assertEquals("rec-1790380800000", record.id)
        assertEquals("20260926-001", record.name)
        assertEquals(
            "/data/user/0/com.example.musicpractice/files/recordings/20260926-001.m4a",
            record.filePath
        )
        assertEquals(
            "file:///data/user/0/com.example.musicpractice/files/recordings/20260926-001.m4a",
            record.fileUri
        )
        assertEquals(1_790_380_800_000L, record.createdTimeMillis)
        assertEquals(340_000L, record.durationMillis)
        assertEquals(85_000L, record.lastPositionMillis)
    }

    @Test
    fun `存进数据库再读回来是同一条录音`() {
        val original = recording()

        val restored = RecordingRecords.toRecording(RecordingRecords.toRecord(original))

        assertEquals(original, restored)
    }

    @Test
    fun `负数时长和播放位置按零存`() {
        val broken = recording().copy(durationMillis = -1L, lastPositionMillis = -5L)

        val record = RecordingRecords.toRecord(broken)

        assertEquals(0L, record.durationMillis)
        assertEquals(0L, record.lastPositionMillis)
    }

    @Test
    fun `一批记录按数据库给的顺序换算`() {
        val records = listOf(
            RecordingRecords.toRecord(recording().copy(id = "rec-2", name = "20260926-002")),
            RecordingRecords.toRecord(recording().copy(id = "rec-1", name = "20260926-001"))
        )

        assertEquals(listOf("rec-2", "rec-1"), RecordingRecords.toRecordings(records).map { it.id })
    }

    @Test
    fun `上次听到结尾附近时位置会被夹进合法范围`() {
        val finished = recording().copy(lastPositionMillis = 400_000L)

        // 时长 340 秒，记录里的位置却写成了 400 秒：读出来要夹回 340 秒，不能越界。
        assertEquals(340_000L, finished.clampedPosition(340_000L))
        // 时长还不知道（文件没打开）时一律从 0 开始。
        assertEquals(0L, finished.clampedPosition(0L))
    }

    @Test
    fun `时长和进度的文案`() {
        val recording = recording()

        assertEquals("05:40", recording.durationLabel)
        assertEquals("01:25 / 05:40", recording.progressLabel)
    }
}
