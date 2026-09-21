package com.example.musicpractice.tuner

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** A4 基准设置的落盘与读回。 */
class TunerSettingsStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var file: File
    private lateinit var store: TunerSettingsStore

    @Before
    fun setUp() {
        file = File(temporaryFolder.root, TunerSettingsStore.FILE_NAME)
        store = TunerSettingsStore(file)
    }

    @Test
    fun `第一次打开是默认的 440Hz`() {
        assertEquals(440, store.readA4Hz())
    }

    @Test
    fun `写进去的数值重启后还在`() {
        store.writeA4Hz(443)
        assertEquals(443, TunerSettingsStore(file).readA4Hz())
    }

    @Test
    fun `范围内的每个值都能存取`() {
        for (hz in TunerSettingsStore.MIN_A4_HZ..TunerSettingsStore.MAX_A4_HZ) {
            store.writeA4Hz(hz)
            assertEquals(hz, store.readA4Hz())
        }
    }

    @Test
    fun `超出范围的数值会被夹到 432 到 448`() {
        store.writeA4Hz(400)
        assertEquals(432, store.readA4Hz())

        store.writeA4Hz(500)
        assertEquals(448, store.readA4Hz())
    }

    @Test
    fun `文件被改坏时回落到默认值`() {
        file.writeText("这不是 JSON")
        assertEquals(440, store.readA4Hz())

        file.writeText("{\"a4Hz\": 9999}")
        assertEquals(448, store.readA4Hz())
    }
}
