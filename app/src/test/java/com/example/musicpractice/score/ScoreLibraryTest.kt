package com.example.musicpractice.score

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 乐谱库的纯逻辑：排序、"最近打开的是谁"、加项目、标记打开、改单个项目。 */
class ScoreLibraryTest {

    private fun pdfProject(id: String, name: String, openedAt: Long) = ScoreProject(
        id = id,
        name = name,
        kind = ScoreProjectKind.PDF,
        pdfUri = "content://scores/$id.pdf",
        localPath = "/data/scores/$id.pdf",
        lastOpenedAtMillis = openedAt
    )

    private fun imageProject(id: String, name: String, openedAt: Long) = ScoreProject(
        id = id,
        name = name,
        kind = ScoreProjectKind.IMAGES,
        images = listOf(
            ScoreImage(uri = "content://images/$id-0", localPath = "/data/scores/$id-img-000.img"),
            ScoreImage(uri = "content://images/$id-1", localPath = "/data/scores/$id-img-001.img")
        ),
        lastOpenedAtMillis = openedAt
    )

    @Test
    fun `没有任何项目时最近打开的是空的`() {
        val library = ScoreLibrary()

        assertTrue(library.projects.isEmpty())
        assertNull(library.lastOpened())
        assertNull(library.lastOpenedImageProject())
    }

    @Test
    fun `项目按最近打开时间倒序排列`() {
        val library = ScoreLibrary(
            projects = listOf(
                pdfProject("a", "《A》", openedAt = 100L),
                pdfProject("b", "《B》", openedAt = 300L),
                pdfProject("c", "《C》", openedAt = 200L)
            )
        )

        assertEquals(listOf("b", "c", "a"), library.sortedByRecent().map { it.id })
    }

    @Test
    fun `新导入的项目会成为最近打开的项目`() {
        val library = ScoreLibrary(projects = listOf(pdfProject("a", "《A》", 100L)))

        val updated = library.add(pdfProject("b", "《B》", 200L))

        assertEquals("b", updated.lastOpenedId)
        assertEquals("《B》", updated.lastOpened()?.name)
    }

    @Test
    fun `标记打开会刷新时间并把它变成最近打开`() {
        val library = ScoreLibrary(
            projects = listOf(
                pdfProject("a", "《A》", 100L),
                pdfProject("b", "《B》", 200L)
            ),
            lastOpenedId = "b"
        )

        val updated = library.touch("a", atMillis = 500L)

        assertEquals("a", updated.lastOpenedId)
        val openedAt = updated.projects.associate { it.id to it.lastOpenedAtMillis }
        assertEquals(mapOf("a" to 500L, "b" to 200L), openedAt)
    }

    @Test
    fun `标记一个不存在的项目不会改坏乐谱库`() {
        val library = ScoreLibrary(
            projects = listOf(pdfProject("a", "《A》", 100L)),
            lastOpenedId = "a"
        )

        assertEquals(library, library.touch("missing", atMillis = 500L))
    }

    @Test
    fun `最近打开的 id 对不上任何项目时按没有最近项目处理`() {
        val library = ScoreLibrary(
            projects = listOf(pdfProject("a", "《A》", 100L)),
            lastOpenedId = "b"
        )

        assertNull(library.lastOpened())
    }

    @Test
    fun `最近打开的是 PDF 时没有可排序的图片项目`() {
        val library = ScoreLibrary(
            projects = listOf(pdfProject("a", "《A》", 100L)),
            lastOpenedId = "a"
        )

        assertNull(library.lastOpenedImageProject())
    }

    @Test
    fun `最近打开的是图片项目时它就是可排序的项目`() {
        val library = ScoreLibrary(
            projects = listOf(imageProject("b", "《B》", 200L)),
            lastOpenedId = "b"
        )

        assertEquals("b", library.lastOpenedImageProject()?.id)
    }

    @Test
    fun `改单个项目不会改变最近打开的是谁`() {
        val library = ScoreLibrary(
            projects = listOf(
                pdfProject("a", "《A》", 100L),
                pdfProject("b", "《B》", 200L)
            ),
            lastOpenedId = "b"
        )

        val updated = library.update("a") { it.copy(lastPageIndex = 7) }

        assertEquals("b", updated.lastOpenedId)
        assertEquals(7, updated.project("a")?.lastPageIndex)
        assertEquals(0, updated.project("b")?.lastPageIndex)
    }

    @Test
    fun `改一个不存在的项目不会改坏乐谱库`() {
        val library = ScoreLibrary(projects = listOf(pdfProject("a", "《A》", 100L)))

        assertEquals(library, library.update("missing") { it.copy(lastPageIndex = 3) })
    }

    @Test
    fun `上次阅读位置会被夹进合法范围`() {
        val project = imageProject("a", "《A》", 100L).copy(lastPageIndex = 9)

        assertEquals(4, project.clampedPageIndex(5))
        assertEquals(0, project.clampedPageIndex(0))
        assertEquals(9, project.clampedPageIndex(20))
    }

    @Test
    fun `删除项目只去掉那一条记录`() {
        val library = ScoreLibrary(
            projects = listOf(
                pdfProject("a", "《A》", 100L),
                imageProject("b", "《B》", 200L)
            ),
            lastOpenedId = "b"
        )

        val updated = library.remove("a")

        assertEquals(listOf("b"), updated.projects.map { it.id })
        // 删的不是最近打开的那个，最近打开的还是它。
        assertEquals("b", updated.lastOpenedId)
    }

    @Test
    fun `删掉最近打开的项目时顺延到下一个最近打开的`() {
        val library = ScoreLibrary(
            projects = listOf(
                pdfProject("a", "《A》", 100L),
                pdfProject("b", "《B》", 300L),
                pdfProject("c", "《C》", 200L)
            ),
            lastOpenedId = "b"
        )

        val updated = library.remove("b")

        // b（300）被删了，剩下 a（100）和 c（200）→ 最近打开的是 c。
        assertEquals("c", updated.lastOpenedId)
        assertEquals(listOf("c", "a"), updated.sortedByRecent().map { it.id })
    }

    @Test
    fun `删掉最后一个项目后回到没有最近项目`() {
        val library = ScoreLibrary(projects = listOf(pdfProject("a", "《A》", 100L)), lastOpenedId = "a")

        val updated = library.remove("a")

        assertTrue(updated.projects.isEmpty())
        assertNull(updated.lastOpenedId)
        assertNull(updated.lastOpened())
    }

    @Test
    fun `删除一个不存在的项目不会改坏乐谱库`() {
        val library = ScoreLibrary(projects = listOf(pdfProject("a", "《A》", 100L)), lastOpenedId = "a")

        assertEquals(library, library.remove("missing"))
    }
}
