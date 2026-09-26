package com.example.musicpractice.score

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 重命名项目（需求一：长按项目 → 重命名，PDF 项目和图片项目都支持）。
 *
 * 这里验证的是乐谱库那一层最要紧的两件事：
 * 只有名字变了（文件、图片顺序、阅读进度、最近打开时间都不许动），
 * 以及空名字不会被写进去。
 */
class ScoreLibraryRenameTest {

    private fun imageProject(id: String, name: String, openedAt: Long) = ScoreProject(
        id = id,
        name = name,
        kind = ScoreProjectKind.IMAGES,
        images = listOf(
            ScoreImage(
                uri = "content://images/$id-0",
                localPath = "/data/scores/$id-img-000.img",
                pageNumber = 2
            ),
            ScoreImage(
                uri = "content://images/$id-1",
                localPath = "/data/scores/$id-img-001.img",
                pageNumber = 1
            )
        ),
        createdTimeMillis = openedAt - 1_000L,
        lastOpenedAtMillis = openedAt,
        lastPageIndex = 1
    )

    private fun pdfProject(id: String, name: String, openedAt: Long) = ScoreProject(
        id = id,
        name = name,
        kind = ScoreProjectKind.PDF,
        pdfUri = "content://scores/$id.pdf",
        localPath = "/data/scores/$id.pdf",
        createdTimeMillis = openedAt - 1_000L,
        lastOpenedAtMillis = openedAt,
        lastPageIndex = 7
    )

    @Test
    fun `PDF 项目可以改名字`() {
        val library = ScoreLibrary(projects = listOf(pdfProject("a", "《旧名字》", openedAt = 100L)))

        val renamed = library.rename("a", "《新名字》")

        assertEquals("《新名字》", renamed.project("a")?.name)
    }

    @Test
    fun `图片项目可以改名字`() {
        val library = ScoreLibrary(projects = listOf(imageProject("b", "《旧名字》", openedAt = 100L)))

        val renamed = library.rename("b", "《新名字》")

        assertEquals("《新名字》", renamed.project("b")?.name)
    }

    @Test
    fun `改名不动文件图片顺序和阅读进度`() {
        val original = imageProject("b", "《旧名字》", openedAt = 100L)

        val renamed = ScoreLibrary(projects = listOf(original)).rename("b", "《新名字》")
            .project("b")!!

        assertEquals(original.images, renamed.images)
        assertEquals(original.createdTimeMillis, renamed.createdTimeMillis)
        assertEquals(original.lastOpenedAtMillis, renamed.lastOpenedAtMillis)
        assertEquals(original.lastPageIndex, renamed.lastPageIndex)
        assertEquals(original.readingImages.map { it.pageNumber }, renamed.readingImages.map { it.pageNumber })
    }

    @Test
    fun `名字首尾的空白会被去掉`() {
        val library = ScoreLibrary(projects = listOf(pdfProject("a", "《旧名字》", openedAt = 100L)))

        assertEquals("《新名字》", library.rename("a", "  《新名字》  ").project("a")?.name)
    }

    @Test
    fun `空名字不会改掉原来的名字`() {
        val library = ScoreLibrary(projects = listOf(pdfProject("a", "《旧名字》", openedAt = 100L)))

        assertEquals("《旧名字》", library.rename("a", "   ").project("a")?.name)
        assertEquals("《旧名字》", library.rename("a", "").project("a")?.name)
    }

    @Test
    fun `名字没变时原样返回同一份库`() {
        val library = ScoreLibrary(projects = listOf(pdfProject("a", "《旧名字》", openedAt = 100L)))

        // 返回同一份对象（===）意味着"什么都没发生"，上层据此不做多余的数据库写入。
        assertEquals(true, library.rename("a", "《旧名字》") === library)
    }

    @Test
    fun `id 不在库里时原样返回`() {
        val library = ScoreLibrary(projects = listOf(pdfProject("a", "《旧名字》", openedAt = 100L)))

        assertEquals(true, library.rename("score-404", "《新名字》") === library)
    }
}
