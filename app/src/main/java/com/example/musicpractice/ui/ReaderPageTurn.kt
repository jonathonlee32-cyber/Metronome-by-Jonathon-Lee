package com.example.musicpractice.ui

import android.view.KeyEvent

/**
 * 乐谱阅读器的"外部输入翻页"（需求二）。
 *
 * 蓝牙翻页器、蓝牙键盘、USB 键盘，以及 `adb shell input keyevent` 发出来的按键，最后都会变成
 * 一个普通的按键事件。所以只要把"哪个按键 = 翻哪一页"这条规则写在一处，
 * 这几种外部设备就都自动支持了 —— 不需要为某一种设备单独写代码。
 *
 * 规则（按需求）：
 * - **下一页**：键盘上键、键盘右键、PageDown；
 * - **上一页**：键盘下键、键盘左键、PageUp。
 *
 * 这些函数都是纯逻辑（不碰界面、不碰音频），所以可以直接用电脑上的单元测试验证
 * （见 ReaderPageTurnTest）：每个按键映射到哪个动作、第一页再往前翻、
 * 最后一页再往后翻，全都测得到。
 */

/** 一次外部输入要做的翻页动作。 */
internal enum class ReaderPageAction {
    /** 下一页。 */
    NEXT,

    /** 上一页。 */
    PREVIOUS,

    /** 不是翻页键：交回给系统（例如音量键、返回键、字母键）。 */
    NONE
}

/** 把一个按键码翻译成翻页动作（需求二里的按键表）。 */
internal fun readerPageAction(keyCode: Int): ReaderPageAction = when (keyCode) {
    KeyEvent.KEYCODE_DPAD_UP,
    KeyEvent.KEYCODE_DPAD_RIGHT,
    KeyEvent.KEYCODE_PAGE_DOWN -> ReaderPageAction.NEXT

    KeyEvent.KEYCODE_DPAD_DOWN,
    KeyEvent.KEYCODE_DPAD_LEFT,
    KeyEvent.KEYCODE_PAGE_UP -> ReaderPageAction.PREVIOUS

    else -> ReaderPageAction.NONE
}

/**
 * 下一页该翻到第几页（从 0 开始算）。
 *
 * 已经在最后一页时停住不动；页数还没读出来（PDF 正在打开，pageCount 是 0）时也返回 0，
 * 这样按键不会把阅读器推进一个不存在的页码。
 */
internal fun nextPageIndex(currentPage: Int, pageCount: Int): Int {
    if (pageCount <= 0) return 0
    return (currentPage + 1).coerceIn(0, pageCount - 1)
}

/** 上一页该翻到第几页；已经在第一页时停住不动。 */
internal fun previousPageIndex(currentPage: Int, pageCount: Int): Int {
    if (pageCount <= 0) return 0
    return (currentPage - 1).coerceIn(0, pageCount - 1)
}
