package com.example.musicpractice.score

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 乐谱库存盘格式的编解码，包括各种脏数据、以及 v4.0 老文件的兼容。 */
class ScoreLibraryCodecTest {

    @Test
    fun `PDF 项目可以原样存取`() {
        val library = ScoreLibrary(
            projects = listOf(
                ScoreProject(
                    id = "score-1",
                    name = "《Sound Euphonium》",
                    kind = ScoreProjectKind.PDF,
                    pdfUri = "content://com.android.providers.downloads/document/42",
                    localPath = "/data/user/0/com.example.musicpractice/files/scores/score-1.pdf",
                    lastOpenedAtMillis = 1_774_000_000_000L,
                    lastPageIndex = 7
                ),
                ScoreProject(
                    id = "score-2",
                    name = "练习曲",
                    kind = ScoreProjectKind.PDF,
                    pdfUri = "content://media/external/file/7",
                    // 本地副本没拷成功的情况：路径为空，读回来仍然是空的。
                    localPath = null,
                    lastOpenedAtMillis = 1_774_000_100_000L
                )
            ),
            lastOpenedId = "score-2"
        )

        val restored = ScoreLibraryCodec.decode(ScoreLibraryCodec.encode(library))

        assertEquals(library.projects, restored.projects)
        assertEquals("score-2", restored.lastOpenedId)
        assertNull(restored.project("score-2")?.localPath)
        assertEquals(7, restored.project("score-1")?.lastPageIndex)
    }

    @Test
    fun `图片项目连排序结果一起原样存取`() {
        val library = ScoreLibrary(
            projects = listOf(
                ScoreProject(
                    id = "score-3",
                    name = "《练习曲第一页》",
                    kind = ScoreProjectKind.IMAGES,
                    images = listOf(
                        ScoreImage("content://images/a", "/files/scores/score-3-img-000.img", pageNumber = 2),
                        ScoreImage("content://images/b", null, pageNumber = null),
                        ScoreImage("content://images/c", "/files/scores/score-3-img-002.img", pageNumber = 1)
                    ),
                    lastOpenedAtMillis = 1_774_000_200_000L,
                    lastPageIndex = 2,
                    orderHintShown = true
                )
            ),
            lastOpenedId = "score-3"
        )

        val restored = ScoreLibraryCodec.decode(ScoreLibraryCodec.encode(library))
        val project = restored.project("score-3")

        assertEquals(library.projects, restored.projects)
        assertEquals(3, project?.images?.size)
        // 存的是导入顺序；排序结果在每张图的页码里。
        assertEquals(listOf(2, null, 1), project?.images?.map { it.pageNumber })
        assertEquals(true, project?.orderHintShown)
    }

    @Test
    fun `空乐谱库存取之后还是空乐谱库`() {
        val restored = ScoreLibraryCodec.decode(ScoreLibraryCodec.encode(ScoreLibrary()))

        assertTrue(restored.projects.isEmpty())
        assertNull(restored.lastOpenedId)
    }

    @Test
    fun `整份文件被改坏时按没有乐谱处理`() {
        val restored = ScoreLibraryCodec.decode("{ 这不是 JSON")

        assertTrue(restored.projects.isEmpty())
        assertNull(restored.lastOpened())
    }

    @Test
    fun `缺 id 或内容的记录被跳过`() {
        val text = """
            {
              "version": 2,
              "projects": [
                {"id": "ok", "name": "《好数据》", "kind": "pdf", "pdfUri": "content://a"},
                {"name": "没有 id", "kind": "pdf", "pdfUri": "content://b"},
                {"id": "no-uri", "name": "没有内容", "kind": "pdf"},
                {"id": "no-images", "name": "图片是空的", "kind": "images", "images": []}
              ]
            }
        """.trimIndent()

        val restored = ScoreLibraryCodec.decode(text)

        assertEquals(listOf("ok"), restored.projects.map { it.id })
    }

    @Test
    fun `最近打开的 id 指向不存在的项目时不认它`() {
        val text = """
            {
              "version": 2,
              "projects": [{"id": "ok", "name": "《A》", "kind": "pdf", "pdfUri": "content://a"}],
              "lastOpenedId": "已经被删掉的项目"
            }
        """.trimIndent()

        val restored = ScoreLibraryCodec.decode(text)

        assertNull(restored.lastOpenedId)
        assertNull(restored.lastOpened())
    }

    @Test
    fun `没有名字的老记录会得到一个兜底名字`() {
        val text = """
            {
              "version": 2,
              "projects": [{"id": "ok", "name": "", "kind": "pdf", "pdfUri": "content://a"}]
            }
        """.trimIndent()

        val restored = ScoreLibraryCodec.decode(text)

        assertEquals("未命名乐谱", restored.projects.single().name)
    }

    @Test
    fun `v4_0 的老文件照样能读成 PDF 项目`() {
        // v4.0（格式 v1）存出来的样子：只有 pdfUri / localPath，没有 kind、页码、提示标记。
        val text = """
            {
              "version": 1,
              "projects": [
                {
                  "id": "score-1774000000000",
                  "name": "《Sound Euphonium》",
                  "pdfUri": "content://com.android.providers.downloads/document/42",
                  "localPath": "/files/scores/score-1774000000000.pdf",
                  "lastOpenedAt": 1774000000000
                }
              ],
              "lastOpenedId": "score-1774000000000"
            }
        """.trimIndent()

        val restored = ScoreLibraryCodec.decode(text)
        val project = restored.project("score-1774000000000")

        assertEquals(ScoreProjectKind.PDF, project?.kind)
        assertEquals("《Sound Euphonium》", project?.name)
        assertEquals(0, project?.lastPageIndex)
        assertEquals(false, project?.orderHintShown)
        assertEquals("score-1774000000000", restored.lastOpened()?.id)
    }

    @Test
    fun `页码是 0 或负数的图片按还没排序处理`() {
        val text = """
            {
              "version": 2,
              "projects": [{
                "id": "score-images",
                "name": "《练习曲》",
                "kind": "images",
                "images": [
                  {"uri": "content://images/a", "page": 0},
                  {"uri": "content://images/b", "page": -3},
                  {"uri": "content://images/c", "page": 2}
                ]
              }]
            }
        """.trimIndent()

        val restored = ScoreLibraryCodec.decode(text)
        val pages = restored.project("score-images")?.images?.map { it.pageNumber }

        assertEquals(listOf(null, null, 2), pages)
    }

    @Test
    fun `创建时间会被存下来 老记录读回来还是 0`() {
        val library = ScoreLibrary(
            projects = listOf(
                ScoreProject(
                    id = "score-1",
                    name = "《Sound Euphonium》",
                    kind = ScoreProjectKind.PDF,
                    pdfUri = "content://scores/score-1.pdf",
                    createdTimeMillis = 1_774_000_000_000L,
                    lastOpenedAtMillis = 1_774_000_500_000L
                )
            )
        )

        val restored = ScoreLibraryCodec.decode(ScoreLibraryCodec.encode(library))

        assertEquals(1_774_000_000_000L, restored.project("score-1")?.createdTimeMillis)
        assertEquals(1_774_000_000_000L, restored.project("score-1")?.createdTime)
    }

    @Test
    fun `没有创建时间的老记录读回来用最近打开时间兜底`() {
        val text = """
            {
              "version": 2,
              "projects": [
                {
                  "id": "score-1",
                  "name": "《Sound Euphonium》",
                  "kind": "pdf",
                  "pdfUri": "content://scores/score-1.pdf",
                  "lastOpenedAt": 1774000000000
                }
              ]
            }
        """.trimIndent()

        val restored = ScoreLibraryCodec.decode(text)

        assertEquals(0L, restored.project("score-1")?.createdTimeMillis)
        assertEquals(1_774_000_000_000L, restored.project("score-1")?.createdTime)
    }
}
