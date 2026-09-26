package com.example.musicpractice.score

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 乐谱库的本地文件存储。
 *
 * 和练习记录（PracticeTimeStore）放在一起，都是 App 私有目录：不需要任何权限，
 * 卸载才会清掉，App 关掉、手机重启之后数据都还在。
 *
 * 写入同样是"先写临时文件再改名"：改名在同一个文件系统上是原子操作，
 * 所以哪怕写到一半进程被强杀，原来的库文件也不会变成半截损坏的内容。
 *
 * 主构造函数直接收 [File]，是为了让单元测试能塞一个临时文件进来，不必依赖 Android 环境。
 *
 * 从 v4.2 起，App 平时用的是 Room 数据库（[ScoreProjectDatabaseStorage]）：
 * 这个类于是只出现在两个地方 —— 读取 v4.1 及更早版本的老乐谱库用于迁移，以及不依赖
 * Android 环境就能跑的单元测试。它和数据库实现的是同一个 [ScoreProjectStorage] 接口，
 * 所以上层的管理逻辑（[ScoreLibraryManager]）对两者一视同仁。
 *
 * 按块写的那几个方法（[saveProject] / [saveImages] / [saveLastOpened] / [deleteProject]）
 * 在文件存储上只能"读出来 → 改一块 → 整份写回去"，这是文件存储的代价；
 * 数据库实现里它们各自只动自己那几行。
 */
class ScoreLibraryStore(private val file: File) : ScoreProjectStorage {

    constructor(context: Context) : this(File(context.applicationContext.filesDir, FILE_NAME))

    /** 读不出或文件不存在都返回空库，不抛异常。 */
    override fun read(): ScoreLibrary {
        if (!file.exists()) return ScoreLibrary()
        return try {
            ScoreLibraryCodec.decode(file.readText())
        } catch (error: Exception) {
            Log.w(TAG, "读取乐谱库失败，按空库处理", error)
            ScoreLibrary()
        }
    }

    override fun replaceAll(library: ScoreLibrary) {
        write(library)
    }

    override fun saveProject(project: ScoreProject) {
        mutate { library ->
            // 同一个 id 的项目先去掉再放进来：等价于"覆盖旧的那条"。
            library.copy(projects = library.projects.filterNot { it.id == project.id } + project)
        }
    }

    override fun saveImages(projectId: String, images: List<ScoreImage>) {
        mutate { library ->
            library.copy(
                projects = library.projects.map {
                    if (it.id == projectId) it.copy(images = images) else it
                }
            )
        }
    }

    override fun saveLastOpened(projectId: String?) {
        mutate { library -> library.copy(lastOpenedId = projectId) }
    }

    override fun deleteProject(projectId: String) {
        mutate { library -> library.remove(projectId) }
    }

    /** 读出当前内容、改一处、整份写回去。 */
    private fun mutate(transform: (ScoreLibrary) -> ScoreLibrary) {
        write(transform(read()))
    }

    private fun write(library: ScoreLibrary) {
        val text = ScoreLibraryCodec.encode(library)
        try {
            val temp = File(file.parentFile, "$FILE_NAME.tmp")
            temp.writeText(text)
            if (!temp.renameTo(file)) {
                // 极少数情况下改名失败（例如临时文件被别的进程占着）：退化成直接覆盖写。
                file.writeText(text)
                temp.delete()
            }
        } catch (error: Exception) {
            Log.w(TAG, "保存乐谱库失败", error)
        }
    }

    companion object {
        const val FILE_NAME = "score_library.json"

        /**
         * 老乐谱库迁移进数据库之后改名成的名字。
         *
         * 用"改名"而不是"删除"：迁移逻辑不能再读到它，但数据还在原地躺着，
         * 万一迁移出了问题也还有得救 —— 绝不主动删掉用户的数据。
         */
        const val MIGRATED_FILE_NAME = "score_library.json.migrated"

        private const val TAG = "ScoreLibraryStore"
    }
}
