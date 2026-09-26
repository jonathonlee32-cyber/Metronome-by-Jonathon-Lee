package com.example.musicpractice.score

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 图片排序逻辑：初始顺序、下一个序号、点一下之后的变化、阅读顺序、重置。 */
class ScoreImageOrderingTest {

    private fun images(count: Int) = List(count) { index ->
        ScoreImage(uri = "content://images/$index", localPath = "/files/img-$index.img")
    }

    @Test
    fun `一张都还没排序时阅读顺序就是导入顺序`() {
        val imported = images(3)

        assertEquals(imported, ScoreImageOrdering.ordered(imported))
    }

    @Test
    fun `还没排序时下一个序号是 1`() {
        val imported = images(4)

        assertEquals(0, ScoreImageOrdering.assignedCount(imported))
        assertEquals(1, ScoreImageOrdering.nextPageNumber(imported))
        assertFalse(ScoreImageOrdering.isComplete(imported))
    }

    @Test
    fun `点哪张哪张就是下一个序号`() {
        // 用户已经排了 1、2、3，那么下一个该给的是 4（需求五）。
        var current = images(5)
        current = ScoreImageOrdering.assignNext(current, 0)
        current = ScoreImageOrdering.assignNext(current, 1)
        current = ScoreImageOrdering.assignNext(current, 2)

        assertEquals(4, ScoreImageOrdering.nextPageNumber(current))

        // 现在点第 5 张（索引 4）。
        current = ScoreImageOrdering.assignNext(current, 4)

        assertEquals(listOf(1, 2, 3, null, 4), current.map { it.pageNumber })
        assertEquals(4, ScoreImageOrdering.assignedCount(current))
        assertEquals(5, ScoreImageOrdering.nextPageNumber(current))
    }

    @Test
    fun `已经排过的图片再点一次不会改号`() {
        var current = ScoreImageOrdering.assignNext(images(3), 0)
        val before = current.map { it.pageNumber }

        current = ScoreImageOrdering.assignNext(current, 0)

        assertEquals(before, current.map { it.pageNumber })
    }

    @Test
    fun `越界的索引不会改坏数据`() {
        val imported = images(2)

        assertEquals(imported, ScoreImageOrdering.assignNext(imported, -1))
        assertEquals(imported, ScoreImageOrdering.assignNext(imported, 5))
    }

    @Test
    fun `阅读顺序把排好的按页码排前面 没排的跟在后面`() {
        // 导入顺序：A B C D；排成：C=1、A=2，B 和 D 还没排。
        val imported = images(4)
        var current = ScoreImageOrdering.assignNext(imported, 2) // C = 1
        current = ScoreImageOrdering.assignNext(current, 0) // A = 2

        val reading = ScoreImageOrdering.ordered(current)

        assertEquals(
            listOf("content://images/2", "content://images/0", "content://images/1", "content://images/3"),
            reading.map { it.uri }
        )
    }

    @Test
    fun `阅读顺序能同时给出导入位置`() {
        val imported = images(3)
        val current = ScoreImageOrdering.assignNext(imported, 2) // 第 3 张 = 第 1 页

        val reading = ScoreImageOrdering.orderedWithPositions(current)

        // 读取的是第 3 张（导入位置 2）在前，然后是导入位置 0、1；
        // 导入位置要留着，因为本地副本的文件名用的就是它。
        assertEquals(listOf(2, 0, 1), reading.map { it.second })
    }

    @Test
    fun `全部排好后就是完整状态`() {
        var current = images(2)
        current = ScoreImageOrdering.assignNext(current, 1)
        current = ScoreImageOrdering.assignNext(current, 0)

        assertTrue(ScoreImageOrdering.isComplete(current))
        assertEquals(listOf(2, 1), current.map { it.pageNumber })
        assertEquals(
            listOf("content://images/1", "content://images/0"),
            ScoreImageOrdering.ordered(current).map { it.uri }
        )
    }

    @Test
    fun `重置之后回到导入顺序`() {
        var current = ScoreImageOrdering.assignNext(images(3), 2)
        current = ScoreImageOrdering.assignNext(current, 0)

        val reset = ScoreImageOrdering.reset(current)

        assertEquals(listOf(null, null, null), reset.map { it.pageNumber })
        assertFalse(ScoreImageOrdering.isComplete(reset))
    }

    @Test
    fun `没有图片时不算排好`() {
        assertFalse(ScoreImageOrdering.isComplete(emptyList()))
        assertEquals(1, ScoreImageOrdering.nextPageNumber(emptyList()))
    }
}
