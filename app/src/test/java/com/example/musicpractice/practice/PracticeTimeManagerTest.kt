package com.example.musicpractice.practice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 计时流程测试：开始、心跳、结束、以及"上次被强杀"的恢复。
 * 用临时文件当存储，跑起来和真机上的逻辑完全一样。
 */
class PracticeTimeManagerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var file: File

    @Before
    fun setUp() {
        TestTime.useFixedZone()
        file = File(temporaryFolder.root, PracticeTimeStore.FILE_NAME)
    }

    /** 每次新建一个管理器，等价于"App 重新启动后再读一次盘"。 */
    private fun newManager(): PracticeTimeManager = PracticeTimeManager(PracticeTimeStore(file))

    private fun storedActive(): ActiveSession? = PracticeTimeStore(file).read().active

    @Test
    fun `开始然后停止会记下一条记录`() {
        val manager = newManager()
        val start = TestTime.at(2026, 9, 21, 14, 5)
        val end = TestTime.at(2026, 9, 21, 14, 32)

        manager.beginSession(start)
        val added = manager.endSession(end)

        assertEquals(1, added.size)
        assertEquals(27 * 60 * 1000L, added[0].durationMillis)
        assertFalse(manager.hasActiveSession())
        // 结束后文件里不应再残留"正在进行"的状态。
        assertNull(storedActive())
        assertEquals(added, newManager().sessions())
    }

    @Test
    fun `没有开始就结束不会产生记录`() {
        assertTrue(newManager().endSession(TestTime.at(2026, 9, 21, 14, 0)).isEmpty())
    }

    @Test
    fun `重复开始不会开出第二段计时`() {
        val manager = newManager()
        val start = TestTime.at(2026, 9, 21, 14, 5)

        manager.beginSession(start)
        manager.beginSession(TestTime.at(2026, 9, 21, 14, 6))

        // 起点仍然是第一次那个，说明第二次开始被忽略了。
        assertEquals(start, storedActive()?.startMillis)
    }

    @Test
    fun `不到一秒的误触不记录`() {
        val manager = newManager()
        val start = TestTime.at(2026, 9, 21, 14, 5)

        manager.beginSession(start)
        val added = manager.endSession(start + 300L)

        assertTrue(added.isEmpty())
        assertTrue(manager.sessions().isEmpty())
    }

    @Test
    fun `开始练习后立刻落盘`() {
        val start = TestTime.at(2026, 9, 21, 14, 5)
        newManager().beginSession(start)

        // 不开新的管理器，直接从文件里读：这一步模拟"刚按下开始就被强杀"。
        assertEquals(start, PracticeTimeStore(file).read().active?.startMillis)
    }

    @Test
    fun `心跳按节流更新并且可以强制更新`() {
        val manager = newManager()
        val start = TestTime.at(2026, 9, 21, 14, 5)
        manager.beginSession(start)

        // 间隔太短：不写盘。
        manager.touchSession(start + 1_000L)
        assertEquals(start, storedActive()?.lastSeenMillis)

        // 超过节流间隔：写盘。
        manager.touchSession(start + 6_000L)
        assertEquals(start + 6_000L, storedActive()?.lastSeenMillis)

        // 强制：立刻写盘。
        manager.touchSession(start + 6_100L, force = true)
        assertEquals(start + 6_100L, storedActive()?.lastSeenMillis)
    }

    @Test
    fun `上次被强杀后重新打开按最后心跳结算`() {
        val start = TestTime.at(2026, 9, 21, 14, 5)
        val lastHeartbeat = start + 27 * 60 * 1000L

        val killed = newManager()
        killed.beginSession(start)
        killed.touchSession(lastHeartbeat, force = true)
        // 这里没有再调用 endSession —— 模拟进程被系统直接回收。

        val restarted = newManager()

        assertFalse(restarted.hasActiveSession())
        val recovered = restarted.sessions().single()
        assertEquals(start, recovered.startMillis)
        // 结束时刻取最后心跳，而不是"重新打开 App 的时刻"：
        // 否则 App 关掉的那几个小时会被算成练习时间。
        assertEquals(lastHeartbeat, recovered.endMillis)
        assertEquals(27 * 60 * 1000L, recovered.durationMillis)
        // 恢复过一次之后，文件里也不该再留着进行中的状态。
        assertNull(storedActive())
    }

    @Test
    fun `强杀时跨午夜的练习按两天分别结算`() {
        val start = TestTime.at(2026, 9, 21, 23, 58)
        val lastHeartbeat = TestTime.at(2026, 9, 22, 0, 10)

        val killed = newManager()
        killed.beginSession(start)
        killed.touchSession(lastHeartbeat, force = true)

        val recovered = newManager().sessions()

        assertEquals(listOf("2026-09-21", "2026-09-22"), recovered.map { it.dateKey })
        assertEquals(2 * 60 * 1000L, recovered[0].durationMillis)
        assertEquals(10 * 60 * 1000L, recovered[1].durationMillis)
    }

    @Test
    fun `结束之后继续开始会追加新的一条`() {
        val manager = newManager()

        manager.beginSession(TestTime.at(2026, 9, 21, 14, 5))
        manager.endSession(TestTime.at(2026, 9, 21, 14, 32))
        manager.beginSession(TestTime.at(2026, 9, 21, 15, 10))
        manager.endSession(TestTime.at(2026, 9, 21, 15, 45))

        val sessions = manager.sessions()
        assertEquals(2, sessions.size)
        assertEquals(listOf("14:05–14:32", "15:10–15:45"), sessions.map {
            "${PracticeFormat.clock(it.startMillis)}–${PracticeFormat.sessionEndLabel(it.startMillis, it.endMillis)}"
        })
    }
}
