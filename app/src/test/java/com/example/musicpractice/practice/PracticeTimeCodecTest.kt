package com.example.musicpractice.practice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** 存盘格式的编解码，包括各种脏数据的容错。 */
class PracticeTimeCodecTest {

    @Before
    fun setUp() {
        TestTime.useFixedZone()
    }

    @Test
    fun `记录可以原样存取`() {
        val data = PracticeTimeData(
            sessions = listOf(
                PracticeSession(
                    "2026-09-21",
                    TestTime.at(2026, 9, 21, 14, 5),
                    TestTime.at(2026, 9, 21, 14, 32)
                ),
                PracticeSession(
                    "2026-09-21",
                    TestTime.at(2026, 9, 21, 23, 58),
                    TestTime.at(2026, 9, 22, 0, 0)
                )
            ),
            active = ActiveSession(
                TestTime.at(2026, 9, 22, 9, 0),
                TestTime.at(2026, 9, 22, 9, 5)
            )
        )

        val restored = PracticeTimeCodec.decode(PracticeTimeCodec.encode(data))

        assertEquals(data.sessions, restored.sessions)
        assertEquals(data.active, restored.active)
    }

    @Test
    fun `没有正在进行时也存不出垃圾数据`() {
        val data = PracticeTimeData(sessions = emptyList(), active = null)
        val restored = PracticeTimeCodec.decode(PracticeTimeCodec.encode(data))

        assertEquals(emptyList<PracticeSession>(), restored.sessions)
        assertNull(restored.active)
    }

    @Test
    fun `空文本当作没有记录`() {
        assertEquals(emptyList<PracticeSession>(), PracticeTimeCodec.decode("").sessions)
    }

    @Test
    fun `文件被写坏时返回空数据而不是抛异常`() {
        val restored = PracticeTimeCodec.decode("{ 这不是 json")
        assertEquals(emptyList<PracticeSession>(), restored.sessions)
        assertNull(restored.active)
    }

    @Test
    fun `个别记录缺字段时只跳过这一条`() {
        val text = """
            {
              "version": 1,
              "sessions": [
                {"date": "2026-09-21", "start": ${TestTime.at(2026, 9, 21, 14, 5)}, "end": ${TestTime.at(2026, 9, 21, 14, 32)}},
                {"start": 1, "end": 2},
                {"date": "2026-09-20", "start": 1000}
              ]
            }
        """.trimIndent()

        val restored = PracticeTimeCodec.decode(text)

        assertEquals(1, restored.sessions.size)
        assertEquals("2026-09-21", restored.sessions[0].dateKey)
    }

    @Test
    fun `读回来的记录按开始时间排好序`() {
        val text = """
            {
              "version": 1,
              "sessions": [
                {"date": "2026-09-21", "start": 2000, "end": 3000},
                {"date": "2026-09-21", "start": 1000, "end": 1500}
              ]
            }
        """.trimIndent()

        val restored = PracticeTimeCodec.decode(text)

        assertEquals(listOf(1000L, 2000L), restored.sessions.map { it.startMillis })
    }
}
