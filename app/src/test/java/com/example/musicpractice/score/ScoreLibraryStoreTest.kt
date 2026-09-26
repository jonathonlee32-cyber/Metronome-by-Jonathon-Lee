package com.example.musicpractice.score

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 落盘与读回。用临时目录代替 App 私有目录，读写逻辑完全一样。 */
class ScoreLibraryStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var file: File
    private lateinit var store: ScoreLibraryStore

    @Before
    fun setUp() {
        file = File(temporaryFolder.root, ScoreLibraryStore.FILE_NAME)
        store = ScoreLibraryStore(file)
    }

    private fun pdfProject(id: String, name: String, openedAt: Long) = ScoreProject(
        id = id,
        name = name,
        kind = ScoreProjectKind.PDF,
        pdfUri = "content://scores/$id.pdf",
        lastOpenedAtMillis = openedAt
    )

    private fun imageProject(id: String, name: String, openedAt: Long) = ScoreProject(
        id = id,
        name = name,
        kind = ScoreProjectKind.IMAGES,
        images = listOf(
            ScoreImage(uri = "content://images/$id-0", localPath = "/files/$id-img-000.img"),
            ScoreImage(uri = "content://images/$id-1", localPath = "/files/$id-img-001.img")
        ),
        lastOpenedAtMillis = openedAt
    )

    @Test
    fun `文件不存在时返回空乐谱库`() {
        val library = store.read()

        assertTrue(library.projects.isEmpty())
        assertNull(library.lastOpened())
    }

    @Test
    fun `导入的项目重启后还在`() {
        store.replaceAll(ScoreLibrary().add(pdfProject("score-1", "《Sound Euphonium》", 100L)))

        // 新开一个 store 读同一个文件，模拟"App 重启后重新读盘"。
        val restored = ScoreLibraryStore(file).read()

        assertEquals(1, restored.projects.size)
        assertEquals("《Sound Euphonium》", restored.lastOpened()?.name)
    }

    @Test
    fun `管理器能记住最近打开的项目`() {
        val manager = ScoreLibraryManager(store)
        manager.add(pdfProject("score-1", "《A》", 100L))
        manager.add(pdfProject("score-2", "《B》", 200L))

        // 用户又回去看了第一份：最近打开的应该变成它。
        manager.markOpened("score-1", atMillis = 300L)

        val reopened = ScoreLibraryManager(ScoreLibraryStore(file))
        assertEquals("score-1", reopened.lastOpened()?.id)
        assertEquals(listOf("score-1", "score-2"), reopened.all().map { it.id })
    }

    @Test
    fun `读到第几页会被记住`() {
        val manager = ScoreLibraryManager(store)
        manager.add(pdfProject("score-1", "《A》", 100L))

        // 用户翻到第 8 页（索引 7），然后退出去 / 杀进程。
        manager.setLastPage("score-1", 7)

        val reopened = ScoreLibraryManager(ScoreLibraryStore(file))
        assertEquals(7, reopened.project("score-1")?.lastPageIndex)
        // 记页码不是"打开"，最近打开的还是它，但时间没被改写。
        assertEquals(100L, reopened.project("score-1")?.lastOpenedAtMillis)
    }

    @Test
    fun `排序结果会被记住`() {
        val manager = ScoreLibraryManager(store)
        manager.add(imageProject("score-img", "《练习曲》", 100L))

        val images = manager.project("score-img")!!.images
        manager.setImages("score-img", ScoreImageOrdering.assignNext(images, 1))

        val reopened = ScoreLibraryManager(ScoreLibraryStore(file))
        val saved = reopened.project("score-img")!!.images

        assertEquals(listOf(null, 1), saved.map { it.pageNumber })
        // 阅读顺序按页码来：排好第 1 页的那张排到最前面。
        assertEquals(listOf("content://images/score-img-1", "content://images/score-img-0"),
            reopened.project("score-img")!!.readingImages.map { it.uri })
    }

    @Test
    fun `排序提示只提示一次`() {
        val manager = ScoreLibraryManager(store)
        manager.add(imageProject("score-img", "《练习曲》", 100L))

        assertEquals(false, manager.project("score-img")?.orderHintShown)
        manager.markOrderHintShown("score-img")

        val reopened = ScoreLibraryManager(ScoreLibraryStore(file))
        assertEquals(true, reopened.project("score-img")?.orderHintShown)
    }

    @Test
    fun `项目 id 直接来自导入时刻`() {
        assertEquals("score-1774000000000", ScoreLibraryManager.newProjectId(1_774_000_000_000L))
    }

    @Test
    fun `项目连创建时间一起存下来`() {
        val manager = ScoreLibraryManager(store)
        manager.add(
            pdfProject("score-1", "《A》", 100L).copy(createdTimeMillis = 90L)
        )

        val reopened = ScoreLibraryManager(ScoreLibraryStore(file))

        assertEquals(90L, reopened.project("score-1")?.createdTimeMillis)
        assertEquals(90L, reopened.project("score-1")?.createdTime)
    }

    @Test
    fun `删除的项目重启后不会再出现`() {
        val manager = ScoreLibraryManager(store)
        manager.add(imageProject("score-img", "《练习曲》", 200L))
        manager.add(pdfProject("score-1", "《Sound Euphonium》", 100L))

        // 最近打开的是后面加进来的 score-1，把它删掉。
        val removed = manager.delete("score-1")

        assertEquals("score-1", removed?.id)
        assertEquals(listOf("score-img"), manager.all().map { it.id })

        val reopened = ScoreLibraryManager(ScoreLibraryStore(file))
        assertEquals(listOf("score-img"), reopened.all().map { it.id })
        assertNull(reopened.project("score-1"))
        // 最近打开的项目顺延到还剩下的那一个。
        assertEquals("score-img", reopened.lastOpened()?.id)
    }

    @Test
    fun `删除一个不存在的项目不会动到别人`() {
        val manager = ScoreLibraryManager(store)
        manager.add(pdfProject("score-1", "《A》", 100L))

        assertNull(manager.delete("missing"))
        assertEquals(listOf("score-1"), manager.all().map { it.id })
    }
}
