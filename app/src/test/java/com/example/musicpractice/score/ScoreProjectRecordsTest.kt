package com.example.musicpractice.score

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "乐谱项目 ↔ 数据库表记录"的换算（纯逻辑，不连数据库）。
 *
 * 真机上数据库的读写由 Room 负责，这里验证最容易出错的两件事：
 * 字段一个不少地存下来、读回来时顺序和兜底值都对。
 */
class ScoreProjectRecordsTest {

    private fun pdfProject() = ScoreProject(
        id = "score-1",
        name = "《Sound Euphonium》",
        kind = ScoreProjectKind.PDF,
        pdfUri = "content://com.android.providers.downloads/document/42",
        localPath = "/files/scores/score-1.pdf",
        createdTimeMillis = 1_774_000_000_000L,
        lastOpenedAtMillis = 1_774_000_500_000L,
        lastPageIndex = 7,
        orderHintShown = true
    )

    private fun imageProject() = ScoreProject(
        id = "score-2",
        name = "《练习曲》",
        kind = ScoreProjectKind.IMAGES,
        images = listOf(
            ScoreImage("content://images/a", "/files/scores/score-2-img-000.img", pageNumber = 2),
            ScoreImage("content://images/b", null, pageNumber = null),
            ScoreImage("content://images/c", "/files/scores/score-2-img-002.img", pageNumber = 1)
        ),
        createdTimeMillis = 1_774_001_000_000L,
        lastOpenedAtMillis = 1_774_001_500_000L,
        lastPageIndex = 2
    )

    /** 把一份乐谱库当成"存进数据库又读回来"走一遍。 */
    private fun roundTrip(library: ScoreLibrary): ScoreLibrary {
        val (projects, images, lastOpenedId) = ScoreProjectRecords.toRecords(library)
        return ScoreProjectRecords.toLibrary(
            rows = projects.map { record ->
                ScoreProjectWithImages(record, images.filter { it.projectId == record.id })
            },
            lastOpenedId = lastOpenedId
        )
    }

    @Test
    fun `项目连创建时间和最近打开时间一起存进表记录`() {
        val record = ScoreProjectRecords.toRecord(pdfProject())

        assertEquals("score-1", record.id)
        assertEquals("《Sound Euphonium》", record.name)
        assertEquals("pdf", record.kind)
        assertEquals("content://com.android.providers.downloads/document/42", record.pdfUri)
        assertEquals("/files/scores/score-1.pdf", record.localPath)
        assertEquals(1_774_000_000_000L, record.createdTimeMillis)
        assertEquals(1_774_000_500_000L, record.lastOpenedTimeMillis)
        assertEquals(7, record.lastPageIndex)
        assertTrue(record.orderHintShown)
    }

    @Test
    fun `没有创建时间的老项目用最近打开时间兜底`() {
        val legacy = pdfProject().copy(createdTimeMillis = 0L)

        assertEquals(1_774_000_500_000L, ScoreProjectRecords.toRecord(legacy).createdTimeMillis)
        assertEquals(1_774_000_500_000L, legacy.createdTime)
    }

    @Test
    fun `图片按导入顺序编号 排序页码原样存下来`() {
        val records = ScoreProjectRecords.toImageRecords("score-2", imageProject().images)

        assertEquals(listOf(0, 1, 2), records.map { it.position })
        assertEquals(listOf(2, null, 1), records.map { it.pageNumber })
        assertEquals(listOf("score-2", "score-2", "score-2"), records.map { it.projectId })
    }

    @Test
    fun `读回来时项目按最近打开倒序 图片按导入顺序`() {
        val library = ScoreLibrary(
            projects = listOf(imageProject(), pdfProject()),
            lastOpenedId = "score-2"
        )

        val restored = roundTrip(library)

        // 图片项目是最近打开的，排在前面。
        assertEquals(listOf("score-2", "score-1"), restored.projects.map { it.id })
        assertEquals("score-2", restored.lastOpened()?.id)
        // 图片永远按导入顺序存：排序结果在每张图的页码里。
        assertEquals(
            listOf("content://images/a", "content://images/b", "content://images/c"),
            restored.project("score-2")?.images?.map { it.uri }
        )
        assertEquals(listOf(2, null, 1), restored.project("score-2")?.images?.map { it.pageNumber })
    }

    @Test
    fun `每一列读回来都和存进去的一模一样`() {
        val library = ScoreLibrary(projects = listOf(pdfProject(), imageProject()))

        // 顺序按"最近打开时间倒序"重排过（这是需求三的排序规则），内容必须分毫不差。
        assertEquals(
            library.projects.sortedByDescending { it.lastOpenedAtMillis },
            roundTrip(library).projects
        )
    }

    @Test
    fun `页码是 0 或负数的图片按还没排序处理`() {
        val images = listOf(
            ScoreImage("content://images/a", null, pageNumber = 0),
            ScoreImage("content://images/b", null, pageNumber = -3),
            ScoreImage("content://images/c", null, pageNumber = 2)
        )
        val project = imageProject().copy(id = "score-x", images = images)

        assertEquals(
            listOf(null, null, 2),
            ScoreProjectRecords.toImageRecords("score-x", images).map { it.pageNumber }
        )
        assertEquals(
            listOf(null, null, 2),
            roundTrip(ScoreLibrary(projects = listOf(project)))
                .project("score-x")
                ?.images
                ?.map { it.pageNumber }
        )
    }

    @Test
    fun `内容缺失的记录被跳过`() {
        val noContent = ScoreProjectRecord(
            id = "no-content",
            name = "没有内容",
            kind = "pdf",
            pdfUri = null,
            localPath = null,
            createdTimeMillis = 1L,
            lastOpenedTimeMillis = 2L,
            lastPageIndex = 0,
            orderHintShown = false
        )
        val emptyImages = noContent.copy(id = "empty-images", kind = "images")

        val library = ScoreProjectRecords.toLibrary(
            rows = listOf(
                ScoreProjectWithImages(noContent, emptyList()),
                ScoreProjectWithImages(emptyImages, emptyList())
            ),
            lastOpenedId = "no-content"
        )

        assertTrue(library.projects.isEmpty())
        assertNull(library.lastOpened())
    }

    @Test
    fun `没有名字的记录会得到一个兜底名字`() {
        val record = ScoreProjectRecords.toRecord(pdfProject()).copy(name = "")

        val restored = ScoreProjectRecords.toLibrary(
            rows = listOf(ScoreProjectWithImages(record, emptyList())),
            lastOpenedId = null
        )

        assertEquals("未命名乐谱", restored.projects.single().name)
    }

    @Test
    fun `最近打开的 id 指向不存在的项目时不认它`() {
        val library = ScoreProjectRecords.toLibrary(
            rows = listOf(
                ScoreProjectWithImages(ScoreProjectRecords.toRecord(pdfProject()), emptyList())
            ),
            lastOpenedId = "已经被删掉的项目"
        )

        assertNull(library.lastOpenedId)
        assertEquals(1, library.projects.size)
    }
}
