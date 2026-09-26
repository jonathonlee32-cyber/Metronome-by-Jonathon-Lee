package com.example.musicpractice.score

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * 乐谱库 ↔ JSON 文本。
 *
 * 和练习记录（PracticeTimeCodec）用同一套做法：只用 Android 自带的 org.json，
 * 不引入任何第三方库；乐谱数量很少（几十份），整份读进内存、整份写回去完全够用。
 *
 * 容错原则也一样：任何一条看不懂的项目直接跳过，整份文件坏了就当成"还没有乐谱"，
 * 绝不让一条脏数据把 App 卡在崩溃里。
 *
 * ## 版本
 *
 * v1（App v4.0）：只有 PDF 项目。
 * v2（App v4.1）：多了项目类型、图片列表、上次读到第几页、排序提示标记。
 * v3（App v4.2）：多了创建时间。
 *
 * 读的时候不区分版本，缺什么字段就用默认值补上：v1 存的文件里没有 `kind`，
 * 但它一定有 `pdfUri`，于是自动按"PDF 项目、上次读到第 1 页"读进来 ——
 * 老用户的乐谱库升级 App 之后照样在，不会丢。
 *
 * 从 v4.2 起，平时落盘的是 Room 数据库（见 ScoreProjectDatabase），这份 JSON 编解码
 * 保留下来做两件事：一是把 v4.1 及更早版本存下来的老乐谱库迁移进数据库，
 * 二是在纯 JVM 单元测试里验证"所有字段都能原样存回来"。
 */
object ScoreLibraryCodec {

    /** 当前写出的格式版本号。 */
    private const val VERSION = 3

    private const val KEY_VERSION = "version"
    private const val KEY_PROJECTS = "projects"
    private const val KEY_LAST_OPENED = "lastOpenedId"

    private const val KEY_ID = "id"
    private const val KEY_NAME = "name"
    private const val KEY_KIND = "kind"
    private const val KEY_PDF_URI = "pdfUri"
    private const val KEY_PATH = "localPath"
    private const val KEY_IMAGES = "images"
    private const val KEY_CREATED_AT = "createdAt"
    private const val KEY_OPENED_AT = "lastOpenedAt"
    private const val KEY_LAST_PAGE = "lastPage"
    private const val KEY_ORDER_HINT = "orderHintShown"

    private const val KEY_IMAGE_URI = "uri"
    private const val KEY_IMAGE_PATH = "path"
    private const val KEY_IMAGE_PAGE = "page"

    private const val KIND_PDF = "pdf"
    private const val KIND_IMAGES = "images"

    fun encode(library: ScoreLibrary): String {
        val root = JSONObject()
        root.put(KEY_VERSION, VERSION)

        val projects = JSONArray()
        for (project in library.projects) {
            val item = JSONObject()
                .put(KEY_ID, project.id)
                .put(KEY_NAME, project.name)
                .put(KEY_KIND, if (project.isImageProject) KIND_IMAGES else KIND_PDF)
                .put(KEY_OPENED_AT, project.lastOpenedAtMillis)
                .put(KEY_LAST_PAGE, project.lastPageIndex)
                .put(KEY_ORDER_HINT, project.orderHintShown)

            // 创建时间是 v4.2 才有的字段。老记录里是 0（没有这个信息）时干脆不写，
            // 读回来还是 0，由 ScoreProject.createdTime 用最近打开时间兜底。
            if (project.createdTimeMillis > 0L) {
                item.put(KEY_CREATED_AT, project.createdTimeMillis)
            }

            // 本地副本路径可能为空（导入时没拷成功），为空时不写这个字段。
            project.pdfUri?.let { item.put(KEY_PDF_URI, it) }
            project.localPath?.let { item.put(KEY_PATH, it) }

            if (project.images.isNotEmpty()) {
                val images = JSONArray()
                for (image in project.images) {
                    val entry = JSONObject().put(KEY_IMAGE_URI, image.uri)
                    image.localPath?.let { entry.put(KEY_IMAGE_PATH, it) }
                    // 没排序的图片不写页码字段（写成 JSON 的 null 读回来还要多一层判断）。
                    image.pageNumber?.let { entry.put(KEY_IMAGE_PAGE, it) }
                    images.put(entry)
                }
                item.put(KEY_IMAGES, images)
            }

            projects.put(item)
        }
        root.put(KEY_PROJECTS, projects)

        // 没有最近项目就不写这个字段（JSONObject 里存 null 会变成 JSON 的 null，读回来更绕）。
        library.lastOpenedId?.let { root.put(KEY_LAST_OPENED, it) }

        return root.toString()
    }

    fun decode(text: String): ScoreLibrary {
        if (text.isBlank()) return ScoreLibrary()
        return try {
            val root = JSONObject(text)
            val projects = decodeProjects(root)
            // 最近打开的项目必须真的存在，否则当成"没有最近项目"：
            // 这样即使 id 那条记录被改坏，入口也只是回到主页，不会打开一个空项目。
            val lastOpened = root.optString(KEY_LAST_OPENED, "")
                .takeIf { id -> projects.any { it.id == id } }
            ScoreLibrary(projects = projects, lastOpenedId = lastOpened)
        } catch (_: JSONException) {
            // 内容被截断或被改坏：按"还没有乐谱"处理，用户重新导入即可。
            ScoreLibrary()
        }
    }

    private fun decodeProjects(root: JSONObject): List<ScoreProject> {
        val array = root.optJSONArray(KEY_PROJECTS) ?: return emptyList()
        val projects = ArrayList<ScoreProject>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.optString(KEY_ID, "")
            if (id.isBlank()) continue

            val images = decodeImages(item)
            val pdfUri = item.optString(KEY_PDF_URI, "").takeIf { it.isNotBlank() }
            val kind = decodeKind(item, pdfUri, images)

            // 内容缺失的记录没法用：PDF 项目必须有 Uri，图片项目必须至少有一张图。
            if (kind == ScoreProjectKind.PDF && pdfUri == null) continue
            if (kind == ScoreProjectKind.IMAGES && images.isEmpty()) continue

            projects += ScoreProject(
                id = id,
                name = item.optString(KEY_NAME, "").ifBlank { DEFAULT_NAME },
                kind = kind,
                pdfUri = pdfUri,
                localPath = item.optString(KEY_PATH, "").takeIf { it.isNotBlank() },
                images = images,
                createdTimeMillis = item.optLong(KEY_CREATED_AT, 0L).coerceAtLeast(0L),
                lastOpenedAtMillis = item.optLong(KEY_OPENED_AT, 0L),
                lastPageIndex = item.optInt(KEY_LAST_PAGE, 0).coerceAtLeast(0),
                orderHintShown = item.optBoolean(KEY_ORDER_HINT, false)
            )
        }
        return projects
    }

    /**
     * 判断项目类型：优先看 `kind` 字段；没有（v1 文件）就按内容猜 ——
     * 有 PDF Uri 的是 PDF 项目，否则是图片项目。
     */
    private fun decodeKind(
        item: JSONObject,
        pdfUri: String?,
        images: List<ScoreImage>
    ): ScoreProjectKind = when (item.optString(KEY_KIND, "")) {
        KIND_IMAGES -> ScoreProjectKind.IMAGES
        KIND_PDF -> ScoreProjectKind.PDF
        else -> if (pdfUri != null || images.isEmpty()) ScoreProjectKind.PDF else ScoreProjectKind.IMAGES
    }

    private fun decodeImages(item: JSONObject): List<ScoreImage> {
        val array = item.optJSONArray(KEY_IMAGES) ?: return emptyList()
        val images = ArrayList<ScoreImage>(array.length())
        for (index in 0 until array.length()) {
            val entry = array.optJSONObject(index) ?: continue
            val uri = entry.optString(KEY_IMAGE_URI, "")
            // Uri 是这张图的全部意义所在，缺了这条就跳过。
            if (uri.isBlank()) continue
            images += ScoreImage(
                uri = uri,
                localPath = entry.optString(KEY_IMAGE_PATH, "").takeIf { it.isNotBlank() },
                // 页码必须从 1 开始，0 或负数说明数据不可信，按"还没排序"处理。
                pageNumber = entry.optInt(KEY_IMAGE_PAGE, 0).takeIf { it > 0 }
            )
        }
        return images
    }

    /** 老文件里没有名字时用的兜底名字，保证界面上永远有一个能显示的项目名。 */
    private const val DEFAULT_NAME = "未命名乐谱"
}
