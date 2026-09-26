package com.example.musicpractice.recording

import com.example.musicpractice.pitch.PitchAnalysis
import com.example.musicpractice.pitch.PitchData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * 录音库的纯逻辑（需求五、六、七）：自动编号、按时间倒序、重命名、删除、记住播放位置。
 *
 * 数据库那层（Room）由真机上的 SQLite 负责，这里用一个内存实现替掉它，
 * 于是"列表排序对不对""改名之后老的字段还在不在""删除会不会把别的一条也带走"
 * 这些规则可以在电脑上直接跑。
 */
class RecordingRepositoryTest {

    private val zone: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")

    private fun at(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long =
        Calendar.getInstance(zone).apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis

    private fun recording(
        id: String,
        name: String,
        createdAt: Long,
        durationMillis: Long = 60_000L,
        lastPositionMillis: Long = 0L
    ) = Recording(
        id = id,
        name = name,
        filePath = "/data/user/0/com.example.musicpractice/files/recordings/$name.m4a",
        fileUri = "file:///data/user/0/com.example.musicpractice/files/recordings/$name.m4a",
        createdTimeMillis = createdAt,
        durationMillis = durationMillis,
        lastPositionMillis = lastPositionMillis
    )

    @Test
    fun `列表按录音时间倒序`() {
        val repository = RecordingRepository(FakeRecordingStore())

        repository.add(recording("rec-1", "20260926-001", at(2026, 9, 26, 8, 0)))
        repository.add(recording("rec-2", "20260926-002", at(2026, 9, 26, 9, 30)))
        repository.add(recording("rec-3", "20260925-001", at(2026, 9, 25, 21, 0)))

        // 最新录的排在最上面（需求六）。
        assertEquals(listOf("rec-2", "rec-1", "rec-3"), repository.all().map { it.id })
    }

    @Test
    fun `编号会把数据库里已有的名字算进去`() {
        val repository = RecordingRepository(FakeRecordingStore())
        repository.add(recording("rec-1", "20260926-001", at(2026, 9, 26, 8, 0)))

        assertEquals("20260926-002", repository.nextName(at(2026, 9, 26, 10, 0)))
    }

    @Test
    fun `编号会把磁盘上已经存在的文件名算进去`() {
        val repository = RecordingRepository(FakeRecordingStore())

        // 数据库里一条记录都没有，但目录里躺着一个 001 的文件（例如记录被清过）：
        // 新录音也要躲开它，绝不能把已经录好的音频覆盖掉。
        val name = repository.nextName(at(2026, 9, 26, 10, 0), taken = listOf("20260926-001"))

        assertEquals("20260926-002", name)
    }

    @Test
    fun `重命名只改名字其它字段保持原样`() {
        val store = FakeRecordingStore()
        val repository = RecordingRepository(store)
        repository.add(
            recording(
                id = "rec-1",
                name = "20260926-001",
                createdAt = at(2026, 9, 26, 8, 0),
                durationMillis = 125_000L,
                lastPositionMillis = 40_000L
            )
        )

        val renamed = repository.rename("rec-1", "  晨练  ")

        assertEquals("晨练", renamed?.name)
        val stored = repository.find("rec-1")
        assertEquals("晨练", stored?.name)
        // 文件、时长、播放位置一个字都不能变 —— 改的只是显示名。
        assertEquals("/data/user/0/com.example.musicpractice/files/recordings/20260926-001.m4a", stored?.filePath)
        assertEquals(125_000L, stored?.durationMillis)
        assertEquals(40_000L, stored?.lastPositionMillis)
        // 落盘的那一份也要是新名字。
        assertEquals("晨练", store.read().first().name)
    }

    @Test
    fun `名字为空时不重命名`() {
        val repository = RecordingRepository(FakeRecordingStore())
        repository.add(recording("rec-1", "20260926-001", at(2026, 9, 26, 8, 0)))

        assertNull(repository.rename("rec-1", "   "))
        assertEquals("20260926-001", repository.find("rec-1")?.name)
    }

    @Test
    fun `不存在的 id 重命名返回空`() {
        val repository = RecordingRepository(FakeRecordingStore())

        assertNull(repository.rename("rec-404", "新名字"))
    }

    @Test
    fun `删除只删这一条并把它交回来`() {
        val store = FakeRecordingStore()
        val repository = RecordingRepository(store)
        repository.add(recording("rec-1", "20260926-001", at(2026, 9, 26, 8, 0)))
        repository.add(recording("rec-2", "20260926-002", at(2026, 9, 26, 9, 0)))

        val removed = repository.delete("rec-1")

        assertEquals("20260926-001", removed?.name)
        assertEquals(listOf("rec-2"), repository.all().map { it.id })
        assertEquals(listOf("rec-2"), store.read().map { it.id })
        assertNull(repository.find("rec-1"))
    }

    @Test
    fun `删除不存在的 id 什么都不做`() {
        val repository = RecordingRepository(FakeRecordingStore())
        repository.add(recording("rec-1", "20260926-001", at(2026, 9, 26, 8, 0)))

        assertNull(repository.delete("rec-404"))
        assertEquals(1, repository.all().size)
    }

    @Test
    fun `记住播放位置并落盘`() {
        val store = FakeRecordingStore()
        val repository = RecordingRepository(store)
        repository.add(
            recording(
                id = "rec-1",
                name = "20260926-001",
                createdAt = at(2026, 9, 26, 8, 0),
                durationMillis = 300_000L
            )
        )

        repository.savePosition("rec-1", 85_000L)

        assertEquals(85_000L, repository.find("rec-1")?.lastPositionMillis)
        assertEquals(85_000L, store.read().first().lastPositionMillis)
    }

    @Test
    fun `负数播放位置按零存`() {
        val repository = RecordingRepository(FakeRecordingStore())
        repository.add(recording("rec-1", "20260926-001", at(2026, 9, 26, 8, 0)))

        repository.savePosition("rec-1", -500L)

        assertEquals(0L, repository.find("rec-1")?.lastPositionMillis)
    }

    @Test
    fun `启动时会把数据库里已有的记录读进来`() {
        val store = FakeRecordingStore(
            initial = listOf(
                recording("rec-1", "20260925-001", at(2026, 9, 25, 8, 0)),
                recording("rec-2", "20260926-001", at(2026, 9, 26, 8, 0))
            )
        )

        val repository = RecordingRepository(store)

        assertEquals(listOf("rec-2", "rec-1"), repository.all().map { it.id })
        assertNotNull(repository.find("rec-1"))
        assertTrue(repository.all().all { it.filePath.endsWith(".m4a") })
    }

    // ---------------- 音准分析（v5.1） ----------------

    private fun pitchAnalysis(recordingId: String, referenceA4Hz: Int = 440) = PitchAnalysis(
        id = PitchAnalysis.idOf(recordingId),
        recordingId = recordingId,
        referenceA4Hz = referenceA4Hz,
        analysisTimeMillis = at(2026, 9, 26, 19, 0),
        points = listOf(
            PitchData(0L, "A4", 0, true, 440.0),
            PitchData(46L, null, 0, false, 0.0)
        )
    )

    @Test
    fun `没分析过的录音 hasAnalysis 是 false`() {
        val repository = RecordingRepository(FakeRecordingStore())
        repository.add(recording("rec-1", "20260926-001", at(2026, 9, 26, 8, 0)))

        assertFalse(repository.hasAnalysis("rec-1"))
        assertNull(repository.analysis("rec-1"))
    }

    @Test
    fun `分析结果存下来之后能读回来`() {
        val store = FakeRecordingStore()
        val repository = RecordingRepository(store)
        repository.add(recording("rec-1", "20260926-001", at(2026, 9, 26, 8, 0)))

        repository.saveAnalysis(pitchAnalysis("rec-1"))

        assertTrue(repository.hasAnalysis("rec-1"))
        assertEquals("pitch-rec-1", repository.analysis("rec-1")?.id)
        assertEquals(440, repository.analysis("rec-1")?.referenceA4Hz)
        assertEquals(2, repository.analysis("rec-1")?.points?.size)
        // 也真的落到了"数据库"里（新开一个仓库实例也能读到）。
        assertTrue(RecordingRepository(store).hasAnalysis("rec-1"))
    }

    @Test
    fun `重做分析会覆盖上一份结果`() {
        val store = FakeRecordingStore()
        val repository = RecordingRepository(store)
        repository.add(recording("rec-1", "20260926-001", at(2026, 9, 26, 8, 0)))
        repository.saveAnalysis(pitchAnalysis("rec-1", referenceA4Hz = 440))

        repository.saveAnalysis(
            pitchAnalysis("rec-1", referenceA4Hz = 442).copy(
                points = listOf(PitchData(0L, "A4", -8, true, 440.0))
            )
        )

        assertEquals(442, store.readAnalysis("rec-1")?.referenceA4Hz)
        assertEquals(1, store.readAnalysis("rec-1")?.points?.size)
    }

    @Test
    fun `换了基准音高后保存的是重算过的结果`() {
        val repository = RecordingRepository(FakeRecordingStore())
        repository.add(recording("rec-1", "20260926-001", at(2026, 9, 26, 8, 0)))
        repository.saveAnalysis(pitchAnalysis("rec-1"))

        val recomputed = repository.analysis("rec-1")!!.withReference(442)
        repository.saveAnalysis(recomputed)

        assertEquals(442, repository.analysis("rec-1")?.referenceA4Hz)
    }

    @Test
    fun `删掉录音时它的分析结果也一起没了`() {
        val store = FakeRecordingStore()
        val repository = RecordingRepository(store)
        repository.add(recording("rec-1", "20260926-001", at(2026, 9, 26, 8, 0)))
        repository.add(recording("rec-2", "20260926-002", at(2026, 9, 26, 9, 0)))
        repository.saveAnalysis(pitchAnalysis("rec-1"))
        repository.saveAnalysis(pitchAnalysis("rec-2"))

        repository.delete("rec-1")

        assertNull(store.readAnalysis("rec-1"))
        assertFalse(repository.hasAnalysis("rec-1"))
        // 别的录音的分析不受影响。
        assertTrue(repository.hasAnalysis("rec-2"))
    }

    @Test
    fun `丢掉内存缓存之后还能从数据库读回来`() {
        val store = FakeRecordingStore()
        val repository = RecordingRepository(store)
        repository.add(recording("rec-1", "20260926-001", at(2026, 9, 26, 8, 0)))
        repository.saveAnalysis(pitchAnalysis("rec-1"))

        repository.releaseAnalysisCache()

        assertEquals("pitch-rec-1", repository.analysis("rec-1")?.id)
    }
}

/**
 * [RecordingStore] 的内存实现，给单元测试用（真机上换成 Room）。
 *
 * 它只做一件事：把内存里的列表当成"数据库"，模拟出"写进去 → 再读出来"的效果。
 */
private class FakeRecordingStore(
    initial: List<Recording> = emptyList()
) : RecordingStore {

    private val records = initial.associateBy { it.id }.toMutableMap()

    /** 内存里的"音准分析表"。 */
    private val analyses = mutableMapOf<String, PitchAnalysis>()

    override fun read(): List<Recording> =
        records.values.sortedByDescending { it.createdTimeMillis }

    override fun upsert(recording: Recording) {
        records[recording.id] = recording
    }

    override fun delete(id: String) {
        records.remove(id)
        // 真机上是外键级联删除，这里手动模拟同样的行为。
        analyses.remove(id)
    }

    override fun rename(id: String, name: String) {
        val current = records[id] ?: return
        records[id] = current.copy(name = name)
    }

    override fun savePosition(id: String, positionMillis: Long) {
        val current = records[id] ?: return
        records[id] = current.copy(lastPositionMillis = positionMillis)
    }

    override fun hasAnalysis(recordingId: String): Boolean = analyses.containsKey(recordingId)

    override fun readAnalysis(recordingId: String): PitchAnalysis? = analyses[recordingId]

    override fun saveAnalysis(analysis: PitchAnalysis) {
        analyses[analysis.recordingId] = analysis
    }

    override fun deleteAnalysis(recordingId: String) {
        analyses.remove(recordingId)
    }
}
