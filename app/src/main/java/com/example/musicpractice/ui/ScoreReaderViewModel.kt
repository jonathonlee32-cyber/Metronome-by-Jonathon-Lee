package com.example.musicpractice.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.musicpractice.score.ScoreFiles
import com.example.musicpractice.score.ScoreImage
import com.example.musicpractice.score.ScoreImageOrdering
import com.example.musicpractice.score.ScoreLibraryManager
import com.example.musicpractice.score.ScoreProject
import com.example.musicpractice.score.ScoreProjectKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 乐谱阅读器的 ViewModel。
 *
 * 它管三件事：
 * 1) 乐谱库（有哪些项目、最近打开的是哪一个、上次读到第几页）—— 数据存在 Room 数据库里，
 *    读写归 [ScoreLibraryManager] 管，这里只做衔接，界面要的数据都从下面这几个方法里取；
 * 2) "从 PDF 导入项目"：申请长期读取权限 → 把 PDF 拷进 App 私有目录 → 写进乐谱库；
 * 3) "从图片导入项目"：把选中的每张图按选择顺序拷进 App 私有目录 → 写进乐谱库；
 * 4) "最近项目"要用的全部项目列表、以及长按删除项目。
 *
 * 导入要读文件、拷贝若干个 MB，所以整段都跑在 IO 线程上，界面只会看到一个
 * "正在导入"的提示，不会卡住。
 */
class ScoreReaderViewModel(application: Application) : AndroidViewModel(application) {

    /** 乐谱库的读写。构造时就把上次存的数据读进内存，之后界面读的都是内存里的快照。 */
    private val library = ScoreLibraryManager(application)

    /** 正在导入。界面据此显示"正在导入…"提示，同时避免用户重复点导入。 */
    var isImporting by mutableStateOf(false)
        private set

    /**
     * 乐谱库的版本号：每写一次库就 +1。
     *
     * 界面在读项目之前会先读它（见 [projectForViewer] / [projectForSorting]），
     * 于是"乐谱库变了"就变成 Compose 眼里的一次状态变化 —— 导入、翻页、排序之后
     * 页面会自动刷新，界面不需要自己再存一份副本，也就不会出现"点了序号但序号没变"这种事。
     */
    var libraryRevision by mutableStateOf(0)
        private set

    /**
     * 全部项目，最近打开的排在前面（需求二、三：最近项目列表用它）。
     *
     * 先读一次 [libraryRevision]：这样删掉项目、新导入项目之后，最近项目列表会自动刷新。
     */
    fun allProjects(): List<ScoreProject> = libraryRevision.let { library.all() }

    /** 最近一次打开的项目。返回 null 表示还没导入过任何乐谱。 */
    fun lastOpenedProject(): ScoreProject? = library.lastOpened()

    /** 最近一次打开的图片项目；当前是 PDF 项目时为 null（"排序图片"菜单项据此显示或隐藏）。 */
    fun lastOpenedImageProject(): ScoreProject? = library.lastOpenedImageProject()

    fun project(id: String?): ScoreProject? = library.project(id)

    /**
     * 取当前阅读页要显示的项目（带了"库变化"的依赖，见 [libraryRevision]）。
     *
     * 这样翻页改了上次阅读位置、或者排序改了图片顺序之后，阅读页会自动拿到最新的数据。
     */
    fun projectForViewer(id: String?): ScoreProject? =
        libraryRevision.let { library.project(id) }

    /**
     * 取当前可以排序的图片项目；不是图片项目（或还没有项目）时为 null
     * —— 界面据此决定「排序图片」这个菜单项显示还是隐藏。
     */
    fun projectForSorting(): ScoreProject? =
        libraryRevision.let { library.lastOpenedImageProject() }

    /** 记录"这个项目刚被打开"，刷新它的最近打开时间。 */
    fun markOpened(id: String) {
        library.markOpened(id, System.currentTimeMillis())
        libraryRevision++
    }

    /**
     * 记住"这个项目读到第几页了"（从 0 开始）。
     *
     * 阅读页每次翻页停稳都会调一次，所以退出再进来能接着上次那一页看（需求一）。
     */
    fun setLastPage(id: String, pageIndex: Int) {
        library.setLastPage(id, pageIndex)
        libraryRevision++
    }

    /**
     * 把图片项目里第 [position] 张图（按导入顺序的位置）排成下一页。
     *
     * 这里刻意接收"第几张"而不是一整份新列表：排序规则作用在**库里当前**的那份数据上，
     * 所以连点两张图也不会因为界面手里的列表还没刷新而排重号。
     */
    fun assignImagePage(projectId: String, position: Int) {
        val current = library.project(projectId) ?: return
        library.setImages(projectId, ScoreImageOrdering.assignNext(current.images, position))
        libraryRevision++
    }

    /** 清掉这个图片项目的所有页码，回到导入顺序。 */
    fun resetImageOrder(projectId: String) {
        val current = library.project(projectId) ?: return
        library.setImages(projectId, ScoreImageOrdering.reset(current.images))
        libraryRevision++
    }

    /** 记住"可点击菜单栏排序图片"这句提示已经显示过了（只提示一次）。 */
    fun markOrderHintShown(id: String) {
        library.markOrderHintShown(id)
        libraryRevision++
    }

    /**
     * 重命名一个项目（需求一：最近项目里长按 → 重命名）。
     *
     * 只改数据库里的项目名称，不动用户原来的 PDF / 图片文件，也不动导入时拷的本地副本
     * 和阅读进度。
     *
     * @return 成功返回新的项目名；名字为空或项目不存在时返回 null。
     */
    fun renameProject(id: String, newName: String): String? {
        val renamed = library.rename(id, newName) ?: return null
        libraryRevision++
        return renamed.name
    }

    /**
     * 删除一个乐谱项目（需求五：最近项目里长按删除）。
     *
     * 两件事：先把项目从数据库里删掉（它的图片记录由外键级联一起删），
     * 再删掉 App 私有目录里为它拷的那几份本地副本。
     *
     * **用户原来的 PDF / 图片文件不在这里被删**：那些文件从头到尾都在用户自己的存储里
     * （我们只是读过一次），所以"删除项目"不可能误删它们。
     *
     * 删文件和删记录一样都不该卡住界面，所以放到 IO 线程上做；界面手上的那份列表
     * 靠 [libraryRevision] 立刻刷新，不用等文件删完。
     */
    fun deleteProject(id: String) {
        val removed = library.delete(id) ?: return
        libraryRevision++

        val context = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            ScoreFiles.deleteProjectFiles(context, removed.id)
        }
    }

    /**
     * 导入一份 PDF，建成一个新的乐谱项目。
     *
     * @param name 用户在命名窗口里输入的项目名。
     * @param uri 系统文件管理器返回的 PDF 地址（已经由系统授予临时读取权限）。
     * @param onFinished 导入结束后的回调，参数是新建好的项目；失败（读不出文件）时为 null。
     *   回调在主线程执行，界面用它决定"进入阅读页"还是"提示导入失败"。
     */
    fun importPdf(name: String, uri: Uri, onFinished: (ScoreProject?) -> Unit) {
        if (isImporting) return
        isImporting = true

        val context = getApplication<Application>()
        viewModelScope.launch {
            val project = withContext(Dispatchers.IO) {
                runCatching {
                    val now = System.currentTimeMillis()
                    val id = ScoreLibraryManager.newProjectId(now)

                    // 向系统申请"长期读取"这份文件的权限：以后即使重启 App，
                    // 只要 Uri 还有效就还能重新拷一份副本。有的来源（云盘）不支持，
                    // 申请失败也不影响：我们拷进私有目录的副本才是平时真正读的那份。
                    runCatching {
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    }

                    val project = ScoreProject(
                        id = id,
                        name = name.trim().ifBlank { DEFAULT_PROJECT_NAME },
                        kind = ScoreProjectKind.PDF,
                        pdfUri = uri.toString(),
                        localPath = ScoreFiles.importPdf(context, id, uri)?.absolutePath,
                        createdTimeMillis = now,
                        lastOpenedAtMillis = now
                    )

                    // 确认这份 PDF 真的打得开：本地副本或 Uri 至少有一个能拿到文件，
                    // 否则宁可报告导入失败，也不要在库里留下一个点开就报错的项目。
                    check(ScoreFiles.resolvePdf(context, project) != null) { "读不到 PDF 内容：$uri" }

                    library.add(project)
                    libraryRevision++
                    project
                }
            }

            isImporting = false
            onFinished(project.getOrNull())
        }
    }

    /**
     * 导入一张或多张图片，建成一个新的图片项目。
     *
     * @param name 用户在命名窗口里输入的项目名。
     * @param uris 系统图片选择器返回的图片地址，**保持用户选择的先后顺序** ——
     *   图片项目的初始顺序就是它（需求三："默认按照系统返回顺序排列"）。
     * @param onFinished 导入结束后的回调，参数是新建好的项目；一张都读不出来时为 null。
     */
    fun importImages(name: String, uris: List<Uri>, onFinished: (ScoreProject?) -> Unit) {
        if (isImporting) return
        if (uris.isEmpty()) {
            onFinished(null)
            return
        }
        isImporting = true

        val context = getApplication<Application>()
        viewModelScope.launch {
            val project = withContext(Dispatchers.IO) {
                runCatching {
                    val now = System.currentTimeMillis()
                    val id = ScoreLibraryManager.newProjectId(now)

                    // 一张一张按顺序拷进私有目录：文件名里的序号就是导入顺序，
                    // 之后的"排序"只改数据里的页码，不搬文件。
                    val images = uris.mapIndexed { index, uri ->
                        ScoreImage(
                            uri = uri.toString(),
                            localPath = ScoreFiles.importImage(context, id, index, uri)?.absolutePath
                        )
                    }

                    // 一张都拷不进来就不建项目：空项目在界面上没有任何意义。
                    check(images.any { it.localPath != null }) { "读不到任何图片内容" }

                    val project = ScoreProject(
                        id = id,
                        name = name.trim().ifBlank { DEFAULT_PROJECT_NAME },
                        kind = ScoreProjectKind.IMAGES,
                        images = images,
                        createdTimeMillis = now,
                        lastOpenedAtMillis = now
                    )

                    library.add(project)
                    libraryRevision++
                    project
                }
            }

            isImporting = false
            onFinished(project.getOrNull())
        }
    }

    private companion object {
        /** 命名窗口里没填出有效名字时的兜底（正常流程下不会用到）。 */
        const val DEFAULT_PROJECT_NAME = "未命名乐谱"
    }
}
