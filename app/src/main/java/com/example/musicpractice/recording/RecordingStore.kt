package com.example.musicpractice.recording

import android.content.Context
import androidx.room.withTransaction
import com.example.musicpractice.pitch.PitchAnalysis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * 录音记录"存在哪儿"的抽象。
 *
 * [RecordingRepository]（管自动编号、重命名、删除、记住播放位置）只跟这个接口打交道，
 * 不关心底下是数据库还是别的什么。这样一来：
 *
 * - App 里用的是 Room 数据库（[RoomRecordingStore]）—— 需求五要求"用本地数据库保存"；
 * - 单元测试里塞一个内存实现就能验证全部逻辑，不需要模拟器、也不需要 Android 环境。
 *
 * 方法都是同步的（调用返回 = 已经落盘），内部自己做数据库 IO。
 */
interface RecordingStore {

    /** 读出全部录音，按录音时间倒序；读不出（第一次运行、数据被改坏）时返回空列表，不抛异常。 */
    fun read(): List<Recording>

    /** 写入 / 覆盖一条录音。 */
    fun upsert(recording: Recording)

    /** 删掉一条录音记录。 */
    fun delete(id: String)

    /** 只改名字（重命名）。 */
    fun rename(id: String, name: String)

    /** 只改最近播放位置（比整行重写便宜，播放中每秒都会调）。 */
    fun savePosition(id: String, positionMillis: Long)

    /** 这段录音有没有音准分析结果。 */
    fun hasAnalysis(recordingId: String): Boolean

    /**
     * 读一段录音的音准分析（连每个时间点一起读出来）；没分析过时返回 null。
     *
     * 一段几十分钟的录音会有几万个时间点，所以只有真正要显示时才调它。
     */
    fun readAnalysis(recordingId: String): PitchAnalysis?

    /** 写入（覆盖）一段录音的分析结果：分析行 + 全部时间点，一次写清。 */
    fun saveAnalysis(analysis: PitchAnalysis)

    /** 删掉一段录音的分析结果。 */
    fun deleteAnalysis(recordingId: String)
}

/**
 * 用 Room 实现 [RecordingStore]。
 *
 * 和乐谱库那边一样：Room 不允许在主线程上做阻塞式数据库操作，
 * 所以真正的 SQL 全在 [Dispatchers.IO] 上执行，调用方（[RecordingRepository]）保持同步写法，
 * 对界面来说"调用返回 = 已经存进数据库"。
 *
 * 一次读写是毫秒级的事（录音记录只有几十条），主线程等一下完全看不出来；
 * 播放中频繁更新的"播放位置"还在上层做了节流（每秒最多写一次），
 * 所以这里不会变成每帧都碰一次磁盘。
 */
class RoomRecordingStore(private val context: Context) : RecordingStore {

    private val database = RecordingDatabase.get(context)
    private val dao = database.recordingDao()
    private val analysisDao = database.pitchAnalysisDao()

    override fun read(): List<Recording> = runBlocking(Dispatchers.IO) {
        RecordingRecords.toRecordings(dao.loadAll())
    }

    override fun upsert(recording: Recording) = runBlocking(Dispatchers.IO) {
        dao.upsert(RecordingRecords.toRecord(recording))
    }

    override fun delete(id: String) = runBlocking(Dispatchers.IO) {
        dao.delete(id)
    }

    override fun rename(id: String, name: String) = runBlocking(Dispatchers.IO) {
        dao.updateName(id, name)
    }

    override fun savePosition(id: String, positionMillis: Long) = runBlocking(Dispatchers.IO) {
        dao.updatePosition(id, positionMillis.coerceAtLeast(0L))
    }

    override fun hasAnalysis(recordingId: String): Boolean = runBlocking(Dispatchers.IO) {
        analysisDao.hasAnalysis(recordingId)
    }

    override fun readAnalysis(recordingId: String): PitchAnalysis? = runBlocking(Dispatchers.IO) {
        val record = analysisDao.loadAnalysis(recordingId) ?: return@runBlocking null
        PitchAnalysisRecords.toAnalysis(record, analysisDao.loadPoints(record.id))
    }

    override fun saveAnalysis(analysis: PitchAnalysis) = runBlocking(Dispatchers.IO) {
        database.withTransaction {
            // 重做分析时先清掉旧的时间点，再整份写新的：要么全是新的，要么还是旧的，
            // 不会出现"新分析 + 旧点"这种半截状态。
            analysisDao.upsertAnalysis(PitchAnalysisRecords.toRecord(analysis))
            analysisDao.deletePoints(analysis.id)
            val points = PitchAnalysisRecords.toPointRecords(analysis.id, analysis.points)
            // 分批写：一次塞几万个参数会撞上 SQLite 的语句长度限制。
            points.chunked(INSERT_BATCH_SIZE).forEach { analysisDao.upsertPoints(it) }
        }
    }

    override fun deleteAnalysis(recordingId: String) = runBlocking(Dispatchers.IO) {
        // 时间点由外键 ON DELETE CASCADE 一起删掉。
        analysisDao.deleteAnalysis(recordingId)
    }

    private companion object {
        /** 每批写入多少个时间点。 */
        const val INSERT_BATCH_SIZE = 500
    }
}
