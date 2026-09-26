package com.example.musicpractice.score

import android.content.Context

/**
 * 乐谱库的管理者：管"有哪些乐谱项目、最近打开的是哪一个、什么时候落盘"。
 *
 * 它不关心界面，也不关心 PDF / 图片怎么显示，只对外提供这几件事：
 * [all] 全部项目（按最近打开排序）、[lastOpened] 最近打开的项目、
 * [project] 按 id 取一个、[add] 新建项目、[markOpened] 记录"打开了谁"、
 * [setLastPage] 记住读到第几页、[setImages] 保存排序结果、[markOrderHintShown] 记住提示已显示、
 * [delete] 删除一个项目。
 *
 * 数据存在哪儿由 [storage] 决定：App 里是 Room 数据库（[ScoreProjectDatabaseStorage]），
 * 单元测试里是一个临时 JSON 文件（[ScoreLibraryStore]）。
 *
 * 每次改动都是"先改内存里那份快照，再把改了的那一块立刻存下去"，而且是**同步**的：
 * 方法返回的时候数据已经在数据库里了，哪怕进程随即被杀也不会丢。界面读的永远是内存快照，
 * 落盘只是为了让数据在 App 关掉之后还在。
 */
class ScoreLibraryManager(private val storage: ScoreProjectStorage) {

    /** 给 App 用的构造方式：数据存在 App 私有目录里的 Room 数据库里。 */
    constructor(context: Context) : this(ScoreProjectDatabaseStorage(context))

    private var library: ScoreLibrary = storage.read()

    /** 全部项目，最近打开的排在前面。 */
    fun all(): List<ScoreProject> = library.sortedByRecent()

    /** 最近一次打开的项目；一份乐谱都没有（或记录已损坏）时返回 null。 */
    fun lastOpened(): ScoreProject? = library.lastOpened()

    /** 最近一次打开的图片项目；最近打开的是 PDF 时为 null（"排序图片"菜单项据此显示或隐藏）。 */
    fun lastOpenedImageProject(): ScoreProject? = library.lastOpenedImageProject()

    fun project(id: String?): ScoreProject? = library.project(id)

    /** 加入一个新项目，并把它记为最近打开。 */
    fun add(project: ScoreProject) {
        library = library.add(project)
        // 新项目的三个部分分开写：项目本身、它的图片（PDF 项目没有图片）、最近打开的是谁。
        storage.saveProject(project)
        storage.saveImages(project.id, project.images)
        storage.saveLastOpened(project.id)
    }

    /** 记录"这个项目刚被打开"，同时刷新它的最近打开时间。 */
    fun markOpened(id: String, atMillis: Long) {
        val updated = library.touch(id, atMillis)
        if (updated === library) return
        library = updated
        library.project(id)?.let { storage.saveProject(it) }
        storage.saveLastOpened(id)
    }

    /**
     * 记住"这个项目上次读到了第几页"。
     *
     * 每次翻页停稳都会调一次，所以只改这一个字段、立刻落盘：哪怕用户翻到第 8 页就
     * 直接杀进程，下次进来还是第 8 页。
     */
    fun setLastPage(id: String, pageIndex: Int) {
        if (pageIndex < 0) return
        update(id) { it.copy(lastPageIndex = pageIndex) }
    }

    /** 保存图片项目的排序结果（每张图的页码记在 [ScoreImage.pageNumber] 上）。 */
    fun setImages(id: String, images: List<ScoreImage>) {
        val updated = library.update(id) { it.copy(images = images) }
        if (updated === library) return
        library = updated
        storage.saveImages(id, images)
    }

    /** 记住"可点击菜单栏排序图片"这句提示已经显示过了（只提示一次）。 */
    fun markOrderHintShown(id: String) {
        update(id) { it.copy(orderHintShown = true) }
    }

    /**
     * 删除一个项目（需求五：长按删除），返回被删掉的那条记录；id 不在库里时返回 null。
     *
     * 返回被删的项目是为了让调用方知道要清理哪些**本地副本**（App 私有目录里导入时拷的那几份
     * 文件）。用户原来的 PDF / 图片文件不归这里管，也从不会被碰到。
     */
    fun delete(id: String): ScoreProject? {
        val removed = library.project(id) ?: return null
        library = library.remove(id)
        storage.deleteProject(id)
        // 删掉的可能正好是"最近打开的那个"，删完顺手把新的最近项目记下来。
        storage.saveLastOpened(library.lastOpenedId)
        return removed
    }

    /** 改一个项目的字段并立刻落盘（只写这一个项目那一行）。id 不在库里时什么都不做。 */
    private fun update(id: String, transform: (ScoreProject) -> ScoreProject) {
        val updated = library.update(id, transform)
        if (updated === library) return
        library = updated
        library.project(id)?.let { storage.saveProject(it) }
    }

    companion object {
        /**
         * 生成项目 id。
         *
         * 直接用"导入那一刻的毫秒数"：它天然唯一（两次导入不可能落在同一毫秒），
         * 又不依赖随机数，看日志时还能一眼看出这份乐谱是什么时候导进来的。
         */
        fun newProjectId(atMillis: Long): String = "score-$atMillis"
    }
}
