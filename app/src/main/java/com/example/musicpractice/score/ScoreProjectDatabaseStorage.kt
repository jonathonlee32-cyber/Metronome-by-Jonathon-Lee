package com.example.musicpractice.score

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * 用 Room 数据库实现 [ScoreProjectStorage]（需求一：用 Android 本地数据库保存项目）。
 *
 * 每个写操作只动自己那一块：
 * - [saveProject]：只写项目那一行（翻页记进度就是这一种，翻一页只更新一行）；
 * - [saveImages]：只重写这一个项目的图片（导入、排序之后才需要）；
 * - [saveLastOpened]：只写 app_state 里那一条；
 * - [deleteProject]：删项目那一行，它的图片记录由外键的级联删除一起带走；
 * - [replaceAll]：整份同步（只在把老 JSON 乐谱库迁移进数据库时用一次）。
 *
 * 事务是这里最关键的一点：写到一半进程被杀，数据库会整体回滚，
 * 绝不会留下"项目在、图片没了"这种半截状态。
 *
 * **读写一律在 IO 线程上做**（[Dispatchers.IO]）：Room 有一条硬性规定 ——
 * 阻塞式的数据库操作不许在主线程上跑（否则会抛
 * "Cannot access database on the main thread"），就是怕它把界面卡住。
 *
 * 但界面又需要"改一次就立刻能看到"（翻页记进度、导入项目、删除项目之后列表马上变），
 * 所以这里的做法是：调用方（[ScoreLibraryManager]）保持同步的写法，
 * 而**数据库操作本身**放到 IO 线程上执行并等它做完再返回。对界面来说，
 * "调用返回 = 已经存进数据库"，和 v4.1 之前直接写文件的语义完全一样，
 * 只是真正碰 SQLite 的那几毫秒不在主线程上。项目和图片都是几十条的量级，
 * 一次读写是毫秒级的事，主线程等一下完全看不出来。
 */
class ScoreProjectDatabaseStorage(private val context: Context) : ScoreProjectStorage {

    private val database = ScoreProjectDatabase.get(context)
    private val dao = database.scoreProjectDao()

    init {
        // 第一次用 v4.2 打开 App 时，把 v4.1 及更早版本存在 JSON 文件里的乐谱库搬进数据库。
        runBlocking(Dispatchers.IO) { migrateLegacyJsonIfNeeded() }
    }

    override fun read(): ScoreLibrary = runBlocking(Dispatchers.IO) {
        val rows = dao.loadProjects()
        val lastOpenedId = dao.readState(KEY_LAST_OPENED)
        ScoreProjectRecords.toLibrary(rows, lastOpenedId)
    }

    override fun replaceAll(library: ScoreLibrary) = runBlocking(Dispatchers.IO) {
        val (projects, images, lastOpenedId) = ScoreProjectRecords.toRecords(library)
        database.withTransaction {
            if (projects.isEmpty()) {
                // 没有项目了：整张表清空（图片记录会被级联删掉）。
                dao.deleteAllProjects()
                dao.deleteAllImages()
            } else {
                dao.upsertProjects(projects)
                // 库里已经没有的项目：连它的图片一起删掉（需求五：删除项目关联数据）。
                dao.deleteProjectsExcept(projects.map { it.id })
                // 图片按导入顺序整份重写：这样排序结果（页码）和图片增删都是一次事务里的同一份数据。
                for (project in library.projects) {
                    dao.deleteImagesOf(project.id)
                    val records = ScoreProjectRecords.toImageRecords(project.id, project.images)
                    if (records.isNotEmpty()) dao.upsertImages(records)
                }
            }

            if (lastOpenedId != null) {
                dao.upsertState(ScoreAppStateRecord(KEY_LAST_OPENED, lastOpenedId))
            } else {
                dao.deleteState(KEY_LAST_OPENED)
            }
        }
    }

    override fun saveProject(project: ScoreProject) = runBlocking(Dispatchers.IO) {
        dao.upsertProjects(listOf(ScoreProjectRecords.toRecord(project)))
    }

    override fun saveImages(projectId: String, images: List<ScoreImage>) = runBlocking(Dispatchers.IO) {
        val records = ScoreProjectRecords.toImageRecords(projectId, images)
        // 先删后写放在一个事务里：排序结果要么整份是新的，要么整份还是旧的，
        // 不会出现"删了一半"的空项目。
        database.withTransaction {
            dao.deleteImagesOf(projectId)
            if (records.isNotEmpty()) dao.upsertImages(records)
        }
    }

    override fun saveLastOpened(projectId: String?) = runBlocking(Dispatchers.IO) {
        if (projectId != null) {
            dao.upsertState(ScoreAppStateRecord(KEY_LAST_OPENED, projectId))
        } else {
            dao.deleteState(KEY_LAST_OPENED)
        }
    }

    override fun deleteProject(projectId: String) = runBlocking(Dispatchers.IO) {
        // 图片记录由外键 ON DELETE CASCADE 一起删掉（需求五：删除项目关联数据）。
        dao.deleteProject(projectId)
    }

    /**
     * 把老版本存在 `filesDir/score_library.json` 里的乐谱库搬进数据库。
     *
     * 只在"还没搬过"的时候做一次（标志存在数据库自己的 app_state 表里，所以即使乐谱库被删空，
     * 也不会把老文件再搬一遍）。搬完把老文件改名留档（`score_library.json.migrated`）：
     * 数据不删、但也不会再有第二个人去读它，一眼就能看出"这份文件已经不管用了"。
     *
     * 任何一步失败都只记日志：迁移失败最多是"老乐谱没搬过来"，绝不能连累 App 启动。
     */
    private fun migrateLegacyJsonIfNeeded() {
        runCatching {
            if (dao.readState(KEY_LEGACY_JSON_MIGRATED) == VALUE_TRUE) return

            val legacyFile = legacyJsonFile()
            if (legacyFile.isFile) {
                val legacy = ScoreLibraryStore(legacyFile).read()
                if (legacy.projects.isNotEmpty()) {
                    replaceAll(legacy)
                }
                val archived = File(legacyFile.parentFile, ScoreLibraryStore.MIGRATED_FILE_NAME)
                if (!legacyFile.renameTo(archived)) {
                    Log.w(TAG, "老乐谱库改名失败，下次启动会再检查一遍")
                }
            }

            dao.upsertState(ScoreAppStateRecord(KEY_LEGACY_JSON_MIGRATED, VALUE_TRUE))
        }.onFailure { error ->
            Log.w(TAG, "把老乐谱库迁移进数据库失败，这次先按空库启动", error)
        }
    }

    /** v4.1 及更早版本存乐谱库的那个 JSON 文件。 */
    private fun legacyJsonFile(): File =
        File(context.applicationContext.filesDir, ScoreLibraryStore.FILE_NAME)

    private companion object {
        const val TAG = "ScoreProjectDatabase"

        /** app_state 里"最近打开的是哪个项目"的键。 */
        const val KEY_LAST_OPENED = "lastOpenedProjectId"

        /** app_state 里"老 JSON 乐谱库是否已经迁移过"的键。 */
        const val KEY_LEGACY_JSON_MIGRATED = "legacyJsonMigrated"

        const val VALUE_TRUE = "true"
    }
}
