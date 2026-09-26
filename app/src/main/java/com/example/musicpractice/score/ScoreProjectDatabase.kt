package com.example.musicpractice.score

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert

/**
 * 乐谱项目的本地数据库（Room）。
 *
 * 需求一要求"用 Android 本地数据库保存，不依赖云端，应用关闭后数据仍然存在"，
 * Room 就是 Android 官方为这件事准备的那层封装：底下还是系统自带的 SQLite
 * （`/data/data/包名/databases/score_projects.db`），只是把建表、读写、事务的样板代码
 * 交给编译期的注解处理器去生成。它不需要任何权限、不联网，卸载 App 才会清掉。
 *
 * 表结构见 [ScoreProjectRecord] / [ScoreImageRecord] / [ScoreAppStateRecord]，
 * 换算逻辑见 [ScoreProjectRecords]，实际的存取见 [ScoreProjectDatabaseStorage]。
 *
 * 数据库实例是**单例**：Room 的实例本身就代表一个连接池，重复创建既浪费内存，
 * 又可能在并发写的时候互相等待，所以整个 App 只开一份。
 */
@Database(
    entities = [
        ScoreProjectRecord::class,
        ScoreImageRecord::class,
        ScoreAppStateRecord::class
    ],
    version = 1,
    // 不导出 schema JSON：这个项目不做数据库版本迁移的自动化测试，
    // 表结构改了直接升 version 并写好 Migration 就行。
    exportSchema = false
)
abstract class ScoreProjectDatabase : RoomDatabase() {

    /** 乐谱项目的读写入口。 */
    abstract fun scoreProjectDao(): ScoreProjectDao

    companion object {
        /** 数据库文件名，落在 App 私有目录里。 */
        const val DATABASE_NAME = "score_projects.db"

        @Volatile
        private var instance: ScoreProjectDatabase? = null

        /** 取全 App 共用的那一份数据库（第一次调用时创建）。 */
        fun get(context: Context): ScoreProjectDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ScoreProjectDatabase::class.java,
                    DATABASE_NAME
                ).build().also { instance = it }
            }
    }
}

/**
 * 乐谱项目的增删改查。
 *
 * 这里的方法都是**阻塞**的（不是 suspend）：[ScoreProjectDatabaseStorage] 在 IO 线程或
 * 极短的一次调用里用 runBlocking 把它们包起来，写完立刻回到内存快照。
 * 这样做是为了跟 v4.0 以来的架构保持一致 —— 界面永远读内存里的那份乐谱库快照，
 * 数据库只负责"存得住、关掉 App 还在"。
 */
@Dao
interface ScoreProjectDao {

    /**
     * 全部项目 + 每个项目的图片，按最近打开时间倒序（需求三：最近打开的在最上面）。
     *
     * [Transaction] 保证"项目和它的图片"是同一时刻读出来的，不会读到某个项目刚删掉一半的状态。
     */
    @Transaction
    @Query("SELECT * FROM projects ORDER BY lastOpenedTimeMillis DESC")
    fun loadProjects(): List<ScoreProjectWithImages>

    /** 写入或覆盖这些项目。 */
    @Upsert
    fun upsertProjects(projects: List<ScoreProjectRecord>)

    /** 写入或覆盖这些图片记录。 */
    @Upsert
    fun upsertImages(images: List<ScoreImageRecord>)

    /**
     * 删掉不在 [ids] 里的项目（已经不在乐谱库里的项目就这么消失；图片记录会被级联删掉）。
     *
     * [ids] 必须非空：空列表在 SQL 里会变成 `NOT IN ()`，那是语法错误。
     * 调用方（整份覆盖那条路径）已经先判断过"一个项目都没有"的情况。
     */
    @Query("DELETE FROM projects WHERE id NOT IN (:ids)")
    fun deleteProjectsExcept(ids: List<String>)

    /** 删掉全部项目。 */
    @Query("DELETE FROM projects")
    fun deleteAllProjects()

    /** 删掉一个项目；它的图片记录由外键 ON DELETE CASCADE 一起删掉。 */
    @Query("DELETE FROM projects WHERE id = :id")
    fun deleteProject(id: String)

    /** 删掉一个项目的全部图片记录（重新写这个项目的图片之前先清空）。 */
    @Query("DELETE FROM project_images WHERE projectId = :projectId")
    fun deleteImagesOf(projectId: String)

    /** 删掉全部图片记录。 */
    @Query("DELETE FROM project_images")
    fun deleteAllImages()

    /** 读一条全局状态；没有这条记录时返回 null。 */
    @Query("SELECT value FROM app_state WHERE key = :key")
    fun readState(key: String): String?

    /** 写一条全局状态。 */
    @Upsert
    fun upsertState(state: ScoreAppStateRecord)

    /** 删掉一条全局状态。 */
    @Query("DELETE FROM app_state WHERE key = :key")
    fun deleteState(key: String)
}
