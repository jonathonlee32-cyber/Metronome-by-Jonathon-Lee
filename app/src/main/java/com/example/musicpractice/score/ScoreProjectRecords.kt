package com.example.musicpractice.score

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

/**
 * 数据库里的表结构，以及"表记录 ↔ 乐谱项目"之间的换算。
 *
 * 三张表：
 * - `projects`：一个乐谱项目一行（PDF 项目和图片项目共用这一张表，靠 `kind` 区分）；
 * - `project_images`：图片项目里每一张图一行，`position` 是**导入顺序**（第几张选中的），
 *   `pageNumber` 是用户排序后排定的页码（没排序就是 NULL）；
 * - `app_state`：几个零碎的全局状态，例如"最近打开的是哪个项目"。键值对表，加字段不用改表结构。
 *
 * 换算函数（[toRecord] / [toImageRecords] / [toLibrary]）全是纯函数，不碰数据库、不碰 Android，
 * 所以可以在电脑上的单元测试里直接跑（见 ScoreProjectRecordsTest）。
 */

/** 一个乐谱项目。PDF 项目和图片项目统一放在这一张表里（需求六：统一项目模型）。 */
@Entity(tableName = "projects")
data class ScoreProjectRecord(
    /** 项目唯一 ID，例如 `score-1774000000000`。 */
    @PrimaryKey val id: String,
    /** 项目名称，例如《Sound Euphonium》。 */
    val name: String,
    /** 项目类型：`pdf` 或 `images`。[ScoreProjectKind] 的名字。 */
    val kind: String,
    /** PDF 项目：导入时选中的 content:// 地址；图片项目为 null。 */
    val pdfUri: String?,
    /** PDF 项目：App 私有目录里那份 PDF 的本地副本路径。 */
    val localPath: String?,
    /** 创建时间（挂钟毫秒）。 */
    val createdTimeMillis: Long,
    /** 最近一次打开时间（挂钟毫秒）—— 最近项目列表就按它倒序排。 */
    val lastOpenedTimeMillis: Long,
    /** 上次阅读到第几页（从 0 开始）。 */
    val lastPageIndex: Int,
    /** 是否已经提示过"可点击菜单栏排序图片"。 */
    val orderHintShown: Boolean
)

/**
 * 图片项目里的一张图（一页）。
 *
 * 主键是"哪个项目 + 导入顺序里的第几张"：同一张图不可能在同一个项目里出现两次，
 * 而排序改的只是 [pageNumber]，不会搬动记录，也就不会产生重复行。
 *
 * 外键挂到 [ScoreProjectRecord] 上并且 `onDelete = CASCADE`：删项目时它的图片记录
 * 由数据库自己一起删掉，不会留下孤儿行（需求五："删除项目关联数据"）。
 */
@Entity(
    tableName = "project_images",
    primaryKeys = ["projectId", "position"],
    foreignKeys = [
        ForeignKey(
            entity = ScoreProjectRecord::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    // 光有主键索引不够：按 projectId 单独查（关系加载）也要走索引。
    indices = [Index("projectId")]
)
data class ScoreImageRecord(
    /** 属于哪个项目。 */
    val projectId: String,
    /** 导入顺序里的位置（0 开始）—— 本地副本的文件名用的就是它。 */
    val position: Int,
    /** 导入时选中的原始 Uri。 */
    val uri: String,
    /** App 私有目录里的本地副本路径；拷贝失败时为 null。 */
    val localPath: String?,
    /** 排定的页码（从 1 开始）；NULL 表示这一张还没排序。 */
    val pageNumber: Int?
)

/** 全局状态表：放"最近打开的是谁"这类一句话的状态，加字段不用改表结构。 */
@Entity(tableName = "app_state")
data class ScoreAppStateRecord(
    @PrimaryKey val key: String,
    val value: String
)

/**
 * "一个项目 + 它的全部图片"，给 Room 的关系查询用。
 *
 * [images] 的顺序由数据库返回，不一定按 [ScoreImageRecord.position]；换算成
 * [ScoreProject] 时会按导入顺序重新排好（见 [ScoreProjectRecords.toLibrary]）。
 */
data class ScoreProjectWithImages(
    @Embedded val project: ScoreProjectRecord,
    @Relation(parentColumn = "id", entityColumn = "projectId")
    val images: List<ScoreImageRecord>
)

/** 表记录 ↔ 乐谱项目的换算（纯逻辑，可以直接用单元测试验证）。 */
object ScoreProjectRecords {

    private const val KIND_PDF = "pdf"
    private const val KIND_IMAGES = "images"

    /** 项目 → 表记录。 */
    fun toRecord(project: ScoreProject): ScoreProjectRecord = ScoreProjectRecord(
        id = project.id,
        name = project.name,
        kind = if (project.isImageProject) KIND_IMAGES else KIND_PDF,
        pdfUri = project.pdfUri,
        localPath = project.localPath,
        // 老项目没有创建时间：用最近打开时间兜底，这样数据库里这一列总是有意义的。
        createdTimeMillis = project.createdTime,
        lastOpenedTimeMillis = project.lastOpenedAtMillis,
        lastPageIndex = project.lastPageIndex.coerceAtLeast(0),
        orderHintShown = project.orderHintShown
    )

    /** 一个项目的图片列表 → 表记录（按导入顺序编号）。 */
    fun toImageRecords(projectId: String, images: List<ScoreImage>): List<ScoreImageRecord> =
        images.mapIndexed { position, image ->
            ScoreImageRecord(
                projectId = projectId,
                position = position,
                uri = image.uri,
                localPath = image.localPath,
                // 页码必须是 1 起的正数，别的都按"还没排序"处理。
                pageNumber = image.pageNumber?.takeIf { it > 0 }
            )
        }

    /**
     * 一份乐谱库 → 表记录。
     *
     * 返回的是三个列表（项目、图片、最近打开的项目 id），调用方在一个事务里写下去。
     */
    fun toRecords(library: ScoreLibrary): Triple<List<ScoreProjectRecord>, List<ScoreImageRecord>, String?> {
        val projects = library.projects.map { toRecord(it) }
        val images = library.projects.flatMap { toImageRecords(it.id, it.images) }
        return Triple(projects, images, library.lastOpenedId)
    }

    /** 表记录 → 一份乐谱库（项目按最近打开时间倒序排好，图片按导入顺序排好）。 */
    fun toLibrary(
        rows: List<ScoreProjectWithImages>,
        lastOpenedId: String?
    ): ScoreLibrary {
        val projects = rows.mapNotNull { toProject(it) }.sortedByDescending { it.lastOpenedAtMillis }
        // 最近打开的项目必须真的存在，否则当成"没有最近项目"：这样即使这条状态被改坏，
        // 入口也只是回到主页，不会打开一个空项目。
        val lastOpened = lastOpenedId?.takeIf { id -> projects.any { it.id == id } }
        return ScoreLibrary(projects = projects, lastOpenedId = lastOpened)
    }

    /** 一个项目 + 它的图片 → [ScoreProject]；内容缺得没法用的记录返回 null（跳过这一条）。 */
    private fun toProject(row: ScoreProjectWithImages): ScoreProject? {
        val record = row.project
        if (record.id.isBlank()) return null

        val images = row.images
            .sortedBy { it.position }
            .mapNotNull { image ->
                // Uri 是这张图的全部意义所在，缺了这条就跳过。
                if (image.uri.isBlank()) null
                else ScoreImage(
                    uri = image.uri,
                    localPath = image.localPath,
                    pageNumber = image.pageNumber?.takeIf { it > 0 }
                )
            }

        // 认不出类型时按内容猜（和 JSON 那边的兼容规则一致）：有 PDF Uri 的是 PDF 项目，
        // 否则是图片项目。
        val kind = when (record.kind) {
            KIND_IMAGES -> ScoreProjectKind.IMAGES
            KIND_PDF -> ScoreProjectKind.PDF
            else -> if (!record.pdfUri.isNullOrBlank() || images.isEmpty()) {
                ScoreProjectKind.PDF
            } else {
                ScoreProjectKind.IMAGES
            }
        }
        // 内容缺失的记录没法用：PDF 项目必须有 Uri，图片项目必须至少有一张图。
        if (kind == ScoreProjectKind.PDF && record.pdfUri.isNullOrBlank()) return null
        if (kind == ScoreProjectKind.IMAGES && images.isEmpty()) return null

        return ScoreProject(
            id = record.id,
            name = record.name.ifBlank { DEFAULT_NAME },
            kind = kind,
            pdfUri = record.pdfUri?.takeIf { it.isNotBlank() },
            localPath = record.localPath?.takeIf { it.isNotBlank() },
            images = images,
            createdTimeMillis = record.createdTimeMillis.coerceAtLeast(0L),
            lastOpenedAtMillis = record.lastOpenedTimeMillis,
            lastPageIndex = record.lastPageIndex.coerceAtLeast(0),
            orderHintShown = record.orderHintShown
        )
    }

    /** 记录里没有名字时用的兜底名字，保证界面上永远有一个能显示的项目名。 */
    private const val DEFAULT_NAME = "未命名乐谱"
}
