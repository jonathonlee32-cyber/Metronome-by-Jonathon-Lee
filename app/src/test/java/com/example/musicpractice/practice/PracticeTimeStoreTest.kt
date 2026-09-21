package com.example.musicpractice.practice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 落盘与读回。用临时目录代替 App 私有目录，读写逻辑完全一样。 */
class PracticeTimeStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var file: File
    private lateinit var store: PracticeTimeStore

    @Before
    fun setUp() {
        TestTime.useFixedZone()
        file = File(temporaryFolder.root, PracticeTimeStore.FILE_NAME)
        store = PracticeTimeStore(file)
    }

    @Test
    fun `文件不存在时返回空数据`() {
        val data = store.read()
        assertTrue(data.sessions.isEmpty())
        assertNull(data.active)
    }

    @Test
    fun `写进去的记录重启后还在`() {
        val session = PracticeSession(
            "2026-09-21",
            TestTime.at(2026, 9, 21, 14, 5),
            TestTime.at(2026, 9, 21, 14, 32)
        )
        store.write(PracticeTimeData(listOf(session), null))

        // 新开一个 store 读同一个文件，模拟"App 重启后重新读盘"。
        val restored = PracticeTimeStore(file).read()

        assertEquals(1, restored.sessions.size)
        assertEquals(session, restored.sessions[0])
    }

    @Test
    fun `正在进行的那一段也会被保存下来`() {
        val active = ActiveSession(
            TestTime.at(2026, 9, 21, 14, 5),
            TestTime.at(2026, 9, 21, 14, 10)
        )
        store.write(PracticeTimeData(emptyList(), active))

        assertEquals(active, PracticeTimeStore(file).read().active)
    }

    @Test
    fun `重复写入不会残留临时文件`() {
        val first = PracticeSession("2026-09-21", 1000L, 2000L)
        val second = PracticeSession("2026-09-22", 3000L, 4000L)

        store.write(PracticeTimeData(listOf(first), null))
        store.write(PracticeTimeData(listOf(first, second), null))

        val restored = store.read()
        assertEquals(listOf(first, second), restored.sessions)

        val leftovers = temporaryFolder.root.listFiles()
            ?.filter { it.name.endsWith(".tmp") }
            .orEmpty()
        assertTrue("不该留下临时文件：$leftovers", leftovers.isEmpty())
    }

    @Test
    fun `文件被写坏时返回空数据`() {
        file.writeText("{ 半截内容")
        assertTrue(store.read().sessions.isEmpty())
    }
}
