package com.example.musicpractice.recording

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.musicpractice.pitch.PitchAnalysis
import com.example.musicpractice.pitch.PitchData

/**
 * 录音的本地数据库（Room）。
 *
 * 需求五要求录音记录"用本地数据库保存"，而且要求离线、不上传云端。Room 是 Android 官方为
 * 这件事准备的封装，底层就是系统自带的 SQLite（`/data/data/包名/databases/recordings.db`）。
 *
 * ## 为什么单独一个数据库文件，而不是塞进乐谱项目的那个库里？
 *
 * 乐谱项目的库（[com.example.musicpractice.score.ScoreProjectDatabase]）已经是 version 1、
 * 里面装着用户导入的乐谱和阅读进度。往同一个库里加表就必须升版本、写迁移 —— 迁移写错一次，
 * 用户已有的乐谱库就可能读不出来。录音是**全新的、独立的**功能，
 * 单开一个 `recordings.db` 之后，两个模块的存储彻底互不影响：
 * 录音这边出任何问题都不会碰到乐谱数据，反过来也一样。
 *
 * 数据库实例是单例，和乐谱库同理：Room 实例本身就代表一个连接池，重复创建既浪费内存，
 * 又可能在并发写的时候互相等待。
 */
@Database(
    entities = [
        RecordingRecord::class,
        PitchAnalysisRecord::class,
        PitchPointRecord::class
    ],
    version = 2,
    // v5.1 起这个库有了版本迁移（v1 → v2 加音准分析的两张表），所以把 schema 导出来存进仓库：
    // 以后再加字段时，"当初的表长什么样"有据可查，迁移写错也更容易发现。
    exportSchema = true
)
abstract class RecordingDatabase : RoomDatabase() {

    /** 录音记录的读写入口。 */
    abstract fun recordingDao(): RecordingDao

    /** 音准分析结果（分析本身 + 每个时间点）的读写入口。 */
    abstract fun pitchAnalysisDao(): PitchAnalysisDao

    companion object {
        /** 数据库文件名，落在 App 私有目录里。 */
        const val DATABASE_NAME = "recordings.db"

        @Volatile
        private var instance: RecordingDatabase? = null

        /** 取全 App 共用的那一份数据库（第一次调用时创建）。 */
        fun get(context: Context): RecordingDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    RecordingDatabase::class.java,
                    DATABASE_NAME
                )
                    // v5.0 装过的用户库里已经有录音记录：用迁移加表，绝不丢数据。
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }

        /**
         * v5.0（version 1）→ v5.1（version 2）：给录音库加上音准分析的两张表。
         *
         * 只 **新建表**，不动 `recordings` 里已有的任何一行 —— 用户已经录好的音频和播放位置
         * 一个字都不会变。两张新表都带外键并级联删除：删掉一段录音时，它的分析结果
         * （可能上万行）由数据库自己一起清掉，不会留下孤儿数据。
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `pitch_analyses` (
                        `id` TEXT NOT NULL,
                        `recordingId` TEXT NOT NULL,
                        `referenceA4Hz` INTEGER NOT NULL,
                        `analysisTimeMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`recordingId`) REFERENCES `recordings`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_pitch_analyses_recordingId` " +
                        "ON `pitch_analyses` (`recordingId`)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `pitch_points` (
                        `analysisId` TEXT NOT NULL,
                        `timestampMillis` INTEGER NOT NULL,
                        `note` TEXT,
                        `cents` INTEGER NOT NULL,
                        `isValid` INTEGER NOT NULL,
                        `frequencyHz` REAL NOT NULL,
                        PRIMARY KEY(`analysisId`, `timestampMillis`),
                        FOREIGN KEY(`analysisId`) REFERENCES `pitch_analyses`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
            }
        }
    }
}

/**
 * 一段录音在数据库里的一行。
 *
 * 字段和需求五一一对应：文件路径、Uri、文件名称、创建时间、最近播放位置，
 * 另外多存一个时长（列表里要显示"03:12"，不存的话每次都得打开音频文件去问，太慢）。
 */
@Entity(tableName = "recordings")
data class RecordingRecord(
    /** 唯一 ID，例如 `rec-1774000000000`。 */
    @PrimaryKey val id: String,
    /** 显示用的名字（可被重命名），默认是 `yyyymmdd-xxx`。 */
    val name: String,
    /** App 私有目录里那个音频文件的绝对路径。 */
    val filePath: String,
    /** 同一个文件的 `file://` Uri。 */
    val fileUri: String?,
    /** 录音时间（挂钟毫秒）。 */
    val createdTimeMillis: Long,
    /** 录音时长（毫秒）。 */
    val durationMillis: Long,
    /** 上次听到哪儿了（毫秒）；进播放页从这里接着放。 */
    val lastPositionMillis: Long
)

/**
 * 一次音准分析（需求三、六、九）。
 *
 * 和录音是**一对一**的：`recordingId` 上加了唯一索引，所以一段录音永远只有一份分析结果，
 * 重做分析就是覆盖它。每个时间点存在 [PitchPointRecord] 表里 ——
 * 一段几十分钟的录音会有几万个时间点，绝不能塞进这一行。
 *
 * 外键挂到 [RecordingRecord] 上并级联删除：删掉录音时，它的分析结果由数据库一起清掉。
 */
@Entity(
    tableName = "pitch_analyses",
    foreignKeys = [
        ForeignKey(
            entity = RecordingRecord::class,
            parentColumns = ["id"],
            childColumns = ["recordingId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["recordingId"], unique = true)]
)
data class PitchAnalysisRecord(
    /** 分析 ID，例如 `pitch-rec-1774000000000`。 */
    @PrimaryKey val id: String,
    /** 属于哪一段录音。 */
    val recordingId: String,
    /** 这次分析用的 A4 基准频率（432～448）。 */
    val referenceA4Hz: Int,
    /** 分析完成的时间（挂钟毫秒）。 */
    val analysisTimeMillis: Long
)

/**
 * 音准分析里的一个时间点（需求三、九）。
 *
 * 主键是"哪次分析 + 第几毫秒"：同一毫秒不可能出现两个点，所以用组合主键最合适 ——
 * 既天然去重，又正好是"按时间顺序取一段"要用的索引。外键级联删除，分析被删掉时点也一起走。
 */
@Entity(
    tableName = "pitch_points",
    primaryKeys = ["analysisId", "timestampMillis"],
    foreignKeys = [
        ForeignKey(
            entity = PitchAnalysisRecord::class,
            parentColumns = ["id"],
            childColumns = ["analysisId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class PitchPointRecord(
    /** 属于哪一次分析。 */
    val analysisId: String,
    /** 这个时间点在录音里的位置（毫秒，从 0 开始）。 */
    val timestampMillis: Long,
    /** 识别到的音名（例如 `A4`）；没有有效音高时为 NULL。 */
    val note: String?,
    /** 音准偏差（音分）。 */
    val cents: Int,
    /** 是否有效音符：false 表示 NOISE。 */
    val isValid: Boolean,
    /** 这一片测到的基频（Hz）；无效时为 0。改基准音高时用它重算音名和音分。 */
    val frequencyHz: Double
)

/**
 * 录音记录的增删改查。
 *
 * 和乐谱库的 DAO 一样，这里的方法都是**阻塞**的（不是 suspend）：[RoomRecordingStore]
 * 在 IO 线程上用 runBlocking 包一层，写完立刻回到内存快照。
 * 界面永远读内存里的那份列表，数据库只负责"存得住、关掉 App 还在"。
 */
@Dao
interface RecordingDao {

    /** 全部录音，按录音时间倒序（需求六：最新的显示在最上面）。 */
    @Query("SELECT * FROM recordings ORDER BY createdTimeMillis DESC, name DESC")
    fun loadAll(): List<RecordingRecord>

    /** 写入或覆盖一条录音记录。 */
    @Upsert
    fun upsert(recording: RecordingRecord)

    /** 删掉一条录音记录。 */
    @Query("DELETE FROM recordings WHERE id = :id")
    fun delete(id: String)

    /** 只改名字（重命名，需求六）。 */
    @Query("UPDATE recordings SET name = :name WHERE id = :id")
    fun updateName(id: String, name: String)

    /** 只改最近播放位置（退出播放页时保存，需求七）。 */
    @Query("UPDATE recordings SET lastPositionMillis = :positionMillis WHERE id = :id")
    fun updatePosition(id: String, positionMillis: Long)
}

/**
 * 音准分析结果的增删改查（需求六、九）。
 *
 * 分两层：`pitch_analyses` 一行代表"这段录音有没有分析过、用的什么基准"，
 * `pitch_points` 存每一个时间点。读取时间点时才去查第二张表 —— 打开录音列表
 * 不需要把几万个点读进内存。
 */
@Dao
interface PitchAnalysisDao {

    /** 这段录音有没有分析结果。列表 / 播放页用它决定显示"分析音准"还是显示结果。 */
    @Query("SELECT EXISTS(SELECT 1 FROM pitch_analyses WHERE recordingId = :recordingId)")
    fun hasAnalysis(recordingId: String): Boolean

    /** 读一次分析（不含时间点）；没有时返回 null。 */
    @Query("SELECT * FROM pitch_analyses WHERE recordingId = :recordingId")
    fun loadAnalysis(recordingId: String): PitchAnalysisRecord?

    /** 读一次分析的全部时间点，按时间升序（播放时按位置查找靠的就是这个顺序）。 */
    @Query("SELECT * FROM pitch_points WHERE analysisId = :analysisId ORDER BY timestampMillis ASC")
    fun loadPoints(analysisId: String): List<PitchPointRecord>

    /** 写入 / 覆盖一次分析。 */
    @Upsert
    fun upsertAnalysis(analysis: PitchAnalysisRecord)

    /** 批量写入时间点（分析结束时一次写进来，几千几万个点也只走一次事务）。 */
    @Upsert
    fun upsertPoints(points: List<PitchPointRecord>)

    /** 清掉一次分析的全部时间点（重做分析时先清空）。 */
    @Query("DELETE FROM pitch_points WHERE analysisId = :analysisId")
    fun deletePoints(analysisId: String)

    /** 删掉一段录音的分析（分析行删掉时，时间点由外键级联一起删）。 */
    @Query("DELETE FROM pitch_analyses WHERE recordingId = :recordingId")
    fun deleteAnalysis(recordingId: String)
}

/** 表记录 ↔ 录音模型之间的换算（纯逻辑，可以直接用单元测试验证）。 */
object RecordingRecords {

    /** 模型 → 表记录。 */
    fun toRecord(recording: Recording): RecordingRecord = RecordingRecord(
        id = recording.id,
        name = recording.name,
        filePath = recording.filePath,
        fileUri = recording.fileUri,
        createdTimeMillis = recording.createdTimeMillis,
        durationMillis = recording.durationMillis.coerceAtLeast(0L),
        lastPositionMillis = recording.lastPositionMillis.coerceAtLeast(0L)
    )

    /** 表记录 → 模型。 */
    fun toRecording(record: RecordingRecord): Recording = Recording(
        id = record.id,
        name = record.name,
        filePath = record.filePath,
        fileUri = record.fileUri,
        createdTimeMillis = record.createdTimeMillis,
        durationMillis = record.durationMillis,
        lastPositionMillis = record.lastPositionMillis
    )

    /** 一批表记录 → 一批模型（顺序由数据库的 ORDER BY 决定）。 */
    fun toRecordings(records: List<RecordingRecord>): List<Recording> =
        records.map { toRecording(it) }
}

/** 音准分析的表记录 ↔ 模型之间的换算（纯逻辑，单元测试直接验证）。 */
object PitchAnalysisRecords {

    /** 模型 → 分析表记录。 */
    fun toRecord(analysis: PitchAnalysis): PitchAnalysisRecord = PitchAnalysisRecord(
        id = analysis.id,
        recordingId = analysis.recordingId,
        referenceA4Hz = analysis.referenceA4Hz,
        analysisTimeMillis = analysis.analysisTimeMillis
    )

    /** 一批时间点 → 一批表记录。 */
    fun toPointRecords(analysisId: String, points: List<PitchData>): List<PitchPointRecord> =
        points.map { point ->
            PitchPointRecord(
                analysisId = analysisId,
                timestampMillis = point.timestampMillis,
                note = point.note,
                cents = point.cents,
                isValid = point.isValid,
                frequencyHz = point.frequencyHz
            )
        }

    /** 分析表记录 + 一批时间点 → 模型（时间点的顺序由 SQL 的 ORDER BY 保证）。 */
    fun toAnalysis(
        record: PitchAnalysisRecord,
        points: List<PitchPointRecord>
    ): PitchAnalysis = PitchAnalysis(
        id = record.id,
        recordingId = record.recordingId,
        referenceA4Hz = record.referenceA4Hz,
        analysisTimeMillis = record.analysisTimeMillis,
        points = points.map { point ->
            PitchData(
                timestampMillis = point.timestampMillis,
                note = point.note,
                cents = point.cents,
                isValid = point.isValid,
                frequencyHz = point.frequencyHz
            )
        }
    )
}
