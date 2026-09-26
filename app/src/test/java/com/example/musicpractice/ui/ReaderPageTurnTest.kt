package com.example.musicpractice.ui

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 乐谱阅读器的按键翻页（需求二）。
 *
 * 蓝牙翻页器、蓝牙键盘、`adb shell input keyevent` 发出来的都是普通按键事件，
 * 所以只要这张"按键 → 翻页动作"的表是对的，这三种外部输入就都支持：
 *
 * - 下一页：上键 / 右键 / PageDown；
 * - 上一页：下键 / 左键 / PageUp；
 * - 其它键（返回、音量、字母……）一律不接管，交回给系统。
 */
class ReaderPageTurnTest {

    @Test
    fun `上键右键和 PageDown 都是下一页`() {
        assertEquals(ReaderPageAction.NEXT, readerPageAction(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(ReaderPageAction.NEXT, readerPageAction(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(ReaderPageAction.NEXT, readerPageAction(KeyEvent.KEYCODE_PAGE_DOWN))
    }

    @Test
    fun `下键左键和 PageUp 都是上一页`() {
        assertEquals(ReaderPageAction.PREVIOUS, readerPageAction(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(ReaderPageAction.PREVIOUS, readerPageAction(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(ReaderPageAction.PREVIOUS, readerPageAction(KeyEvent.KEYCODE_PAGE_UP))
    }

    @Test
    fun `别的按键不接管`() {
        assertEquals(ReaderPageAction.NONE, readerPageAction(KeyEvent.KEYCODE_BACK))
        assertEquals(ReaderPageAction.NONE, readerPageAction(KeyEvent.KEYCODE_VOLUME_UP))
        assertEquals(ReaderPageAction.NONE, readerPageAction(KeyEvent.KEYCODE_ENTER))
        assertEquals(ReaderPageAction.NONE, readerPageAction(KeyEvent.KEYCODE_B))
    }

    @Test
    fun `下一页就是在当前页上加一`() {
        assertEquals(1, nextPageIndex(currentPage = 0, pageCount = 20))
        assertEquals(8, nextPageIndex(currentPage = 7, pageCount = 20))
    }

    @Test
    fun `上一页就是在当前页上减一`() {
        assertEquals(0, previousPageIndex(currentPage = 1, pageCount = 20))
        assertEquals(7, previousPageIndex(currentPage = 8, pageCount = 20))
    }

    @Test
    fun `最后一页再往后翻会停在最后一页`() {
        assertEquals(19, nextPageIndex(currentPage = 19, pageCount = 20))
        // 连按两下也一样：不会翻到一个不存在的第 21 页。
        assertEquals(19, nextPageIndex(currentPage = 19, pageCount = 20))
    }

    @Test
    fun `第一页再往前翻会停在第一页`() {
        assertEquals(0, previousPageIndex(currentPage = 0, pageCount = 20))
    }

    @Test
    fun `页数还没读出来时按键不动页码`() {
        // PDF 项目打开渲染器之前页数是 0，这时按翻页键应该什么都不发生，
        // 而不是把阅读器推到一个不存在的页码上。
        assertEquals(0, nextPageIndex(currentPage = 3, pageCount = 0))
        assertEquals(0, previousPageIndex(currentPage = 3, pageCount = 0))
    }

    @Test
    fun `只有一页时两个方向都不动`() {
        assertEquals(0, nextPageIndex(currentPage = 0, pageCount = 1))
        assertEquals(0, previousPageIndex(currentPage = 0, pageCount = 1))
    }
}
