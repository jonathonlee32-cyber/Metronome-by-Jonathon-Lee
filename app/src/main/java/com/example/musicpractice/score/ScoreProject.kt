package com.example.musicpractice.score

/**
 * 乐谱项目的类型。
 *
 * - [PDF]：一份 PDF 文件，一页就是 PDF 的一页；
 * - [IMAGES]：导入时选的一张或多张图片，一张图一页，顺序可以自己排。
 *
 * 两种项目共用同一个阅读页（都能左右滑动翻页、点一下全屏），
 * 差别只在"一页从哪来"：PDF 走 [PdfPageRenderer]，图片走本地图片文件。
 */
enum class ScoreProjectKind {
    PDF,
    IMAGES
}

/**
 * 图片项目里的一张图（一页）。
 *
 * [uri] 是导入时系统图片选择器给的那个地址，只作为"这张图从哪来"的记录；
 * 真正用来显示的是导入时拷进 App 私有目录的本地副本 [localPath]，
 * 所以之后即使用户把原图删了、或者系统收回了访问授权，乐谱照样能看。
 */
data class ScoreImage(
    /** 导入时选中的原始 Uri。 */
    val uri: String,
    /** App 私有目录里的本地副本路径；拷贝失败时为 null（这一页会显示"打不开"）。 */
    val localPath: String?,
    /**
     * 已经排定的页码，从 1 开始；null 表示这张图还没有排序。
     *
     * 排序就是给图片一个页码：每张图要么还没排（null），要么已经排在第 N 页。
     * 排序页把"下一个该给的页码"显示成灰色序号，用户点一下就把这张图排进去。
     */
    val pageNumber: Int? = null
)

/**
 * 一个乐谱项目。
 *
 * 一个项目 = 用户起的名字 + 内容（PDF 的 Uri / 图片列表）+ 阅读状态（上次看到第几页）+
 * 最近打开时间。PDF 与图片都只存在本机：原始 Uri 只作记录，实际显示的是 App 私有目录里的本地副本，
 * 两者都出不了这台设备。
 */
data class ScoreProject(
    /** 项目唯一标识，同时用来给本地副本命名。 */
    val id: String,
    /** 用户输入的项目名，例如《Sound Euphonium》。 */
    val name: String,
    /** 这是 PDF 项目还是图片项目。 */
    val kind: ScoreProjectKind,
    /** PDF 项目：导入时选中的 PDF 的 content:// 地址。 */
    val pdfUri: String? = null,
    /** PDF 项目：App 私有目录里那份 PDF 的本地副本路径。 */
    val localPath: String? = null,
    /** 图片项目：所有图片，按导入顺序排列（排序结果记在每张图的 [ScoreImage.pageNumber] 上）。 */
    val images: List<ScoreImage> = emptyList(),
    /**
     * 项目创建时间（挂钟毫秒）—— 也就是"这份乐谱是什么时候导入的"。
     *
     * 0 表示记录里没有这个信息（v4.1 及更早存下来的项目）。这时按 [createdTime] 的规则用
     * 最近打开时间兜底：老项目一导入就会被打上"最近打开时间"，用它当创建时间是合理的近似。
     */
    val createdTimeMillis: Long = 0L,
    /** 最近一次打开这个项目的时间（挂钟毫秒）。 */
    val lastOpenedAtMillis: Long = 0L,
    /** 上次阅读到第几页（从 0 开始），下次进这个项目直接翻到这一页。 */
    val lastPageIndex: Int = 0,
    /** 是否已经提示过"可点击菜单栏排序图片"。只提示一次，所以这个标记要落盘。 */
    val orderHintShown: Boolean = false
) {

    val isImageProject: Boolean get() = kind == ScoreProjectKind.IMAGES

    /**
     * 阅读顺序（图片项目专用）：已经排好页码的按 1、2、3 排在最前面，
     * 还没排序的按导入顺序跟在后面。
     *
     * 注意存盘的 [images] 始终是导入顺序 —— 排序页和"点序号自动换下一张"都按它走，
     * 位置不会在用户点来点去的时候跳来跳去；这里换算出来的才是**看谱的顺序**。
     */
    val readingImages: List<ScoreImage> get() = ScoreImageOrdering.ordered(images)

    /** 这个项目一共多少页。PDF 要等渲染器打开才知道页数，所以那边单独处理。 */
    val imagePageCount: Int get() = images.size

    /**
     * 创建时间；记录里没有（0）时用最近打开时间兜底，保证界面上永远显示得出一个时间。
     *
     * 存进数据库的是这个兜底后的值（见 ScoreProjectRecords），所以新导入的项目总是有创建时间，
     * 老项目在第一次被重新保存时也会被补上。
     */
    val createdTime: Long get() = if (createdTimeMillis > 0L) createdTimeMillis else lastOpenedAtMillis

    /** 把上次阅读位置夹到合法范围内（内容换过、页数变少时用）。 */
    fun clampedPageIndex(pageCount: Int): Int =
        if (pageCount <= 0) 0 else lastPageIndex.coerceIn(0, pageCount - 1)
}

/**
 * 乐谱库的内存快照：所有项目 + 最近打开的是哪一个。
 *
 * 这里全是纯函数（返回新对象，不修改自己），所以排序、"最近打开的是谁"这些规则
 * 可以直接跑在电脑上的单元测试里，不需要 Android 环境。
 */
data class ScoreLibrary(
    val projects: List<ScoreProject> = emptyList(),
    val lastOpenedId: String? = null
) {

    /** 按最近打开时间倒序：最近打开过的排在最前面。 */
    fun sortedByRecent(): List<ScoreProject> =
        projects.sortedByDescending { it.lastOpenedAtMillis }

    /** 按 id 找项目；id 为 null（还没有打开过任何项目）时返回 null。 */
    fun project(id: String?): ScoreProject? =
        if (id == null) null else projects.firstOrNull { it.id == id }

    /** 最近一次打开的项目。一份乐谱都没有时返回 null —— 这时入口应该显示"三个入口"的主页。 */
    fun lastOpened(): ScoreProject? = project(lastOpenedId)

    /**
     * 最近一次打开的**图片**项目；最近打开的是 PDF（或还没有项目）时返回 null。
     *
     * "排序图片"这个菜单项只对当前图片项目有效，靠的就是这个判断。
     */
    fun lastOpenedImageProject(): ScoreProject? = lastOpened()?.takeIf { it.isImageProject }

    /** 加入一个新项目，并把它记为最近打开。同一个 id 重复加入时覆盖旧的那条。 */
    fun add(project: ScoreProject): ScoreLibrary =
        copy(
            projects = projects.filterNot { it.id == project.id } + project,
            lastOpenedId = project.id
        )

    /**
     * 记录"这个项目刚被打开了一次"：刷新它的最近打开时间，并把它记为最近打开。
     *
     * id 不在库里时原样返回（数据被外部改坏时不至于凭空冒出一个空项目）。
     */
    fun touch(id: String, atMillis: Long): ScoreLibrary {
        if (projects.none { it.id == id }) return this
        return copy(
            projects = projects.map {
                if (it.id == id) it.copy(lastOpenedAtMillis = atMillis) else it
            },
            lastOpenedId = id
        )
    }

    /**
     * 就地改一个项目（记住读到第几页、保存排序结果、标记提示已显示）。
     *
     * 只改这一个项目的字段，不动"最近打开的是谁"—— 记页码不是"打开"，
     * 打开过谁由 [touch] 单独负责。
     */
    fun update(id: String, transform: (ScoreProject) -> ScoreProject): ScoreLibrary {
        if (projects.none { it.id == id }) return this
        return copy(projects = projects.map { if (it.id == id) transform(it) else it })
    }

    /**
     * 删除一个项目（需求五：长按删除）。
     *
     * 只动"库里有哪些项目"这件事，用户原来的 PDF / 图片文件一个字都不碰 ——
     * 删掉的只是 App 里的这条项目记录，重新导入一次就能再建一个。
     *
     * "最近打开的是谁"如果正好指向被删掉的项目，就顺延到剩下项目里最近打开的那一个
     * （一份都不剩时回到"没有最近项目"，入口会显示三个入口的主页）。这样删掉当前项目之后，
     * 再点「乐谱阅读器」还能直接进到下一份最近看过的乐谱，而不是莫名其妙地回到主页。
     */
    fun remove(id: String): ScoreLibrary {
        if (projects.none { it.id == id }) return this
        val remaining = projects.filterNot { it.id == id }
        return copy(
            projects = remaining,
            lastOpenedId = if (lastOpenedId == id) {
                remaining.maxByOrNull { it.lastOpenedAtMillis }?.id
            } else {
                lastOpenedId
            }
        )
    }
}
