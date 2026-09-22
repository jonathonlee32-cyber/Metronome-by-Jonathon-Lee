package com.example.musicpractice.tuner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 音准历史：只有有效音符才记点、换音要先稳定确认再记、NOISE 完全冻结、数据不会无限增长。
 */
class PitchHistoryTest {

    private fun reading(name: String, cents: Int, midi: Int = 60): TunerReading {
        val frequency = TunerNotes.frequencyHz(midi, 440.0)
        return TunerReading(
            isReliable = true,
            match = NoteMatch(
                midi = midi,
                name = name,
                targetFrequencyHz = frequency,
                cents = cents.toDouble()
            ),
            frequencyHz = frequency
        )
    }

    /** 连着喂 [frames] 帧同一个音，每帧前进 [stepMs]（默认 46 毫秒，和真机一次检测一致）。 */
    private fun PitchHistory.feed(
        name: String,
        cents: Int,
        midi: Int = 60,
        frames: Int = 1,
        stepMs: Long = 46L
    ) {
        repeat(frames) {
            advance(stepMs)
            onReading(reading(name, cents, midi))
        }
    }

    // ---------------- 基本记录 ----------------

    @Test
    fun `有效音符按时间轴记录音分`() {
        val history = PitchHistory()

        // 第一个音也要先稳定确认：前两帧只在观察，第三帧才正式记点。
        history.feed("C4", 3, frames = 3)
        assertEquals(1, history.pointCount)
        assertEquals(3, history.pointCents(0))
        assertEquals(138L, history.pointTime(0))

        // 音名没变：之后每一帧都逐帧记录。
        history.feed("C4", -4, frames = 2)
        assertEquals(3, history.pointCount)
        assertEquals(-4, history.pointCents(2))
        assertEquals(230L, history.pointTime(2))
        assertEquals(230L, history.nowMs)
    }

    @Test
    fun `没有音高时状态为空`() {
        val history = PitchHistory()

        assertEquals(0, history.pointCount)
        assertEquals(0, history.markerCount)
        assertEquals(0L, history.nowMs)
        assertNull(history.currentNote)
    }

    @Test
    fun `同一个音内的真实音高滑动一帧都不丢`() {
        val history = PitchHistory()
        history.feed("C4", 0, frames = 3)
        val before = history.pointCount

        // 真实的滑音：-5 → +5 → +15 → +25 → 甚至滑到 44 / -49 音分。
        // 同一个音之内不许做任何滤波或丢弃。
        val slide = intArrayOf(-5, 5, 15, 25, 40, 44, -20, -49)
        for (cents in slide) history.feed("C4", cents)

        assertEquals(before + slide.size, history.pointCount)
        for ((index, cents) in slide.withIndex()) {
            assertEquals(cents, history.pointCents(before + index))
        }
    }

    // ---------------- NOISE ----------------

    @Test
    fun `NOISE 不加点也不动时间轴`() {
        val history = PitchHistory()
        history.feed("C4", 3, frames = 3)
        val points = history.pointCount
        val now = history.nowMs

        history.onReading(TunerReading.NOISE)
        history.onReading(TunerReading(isReliable = false, match = reading("D4", 0, midi = 62).match))

        assertEquals(points, history.pointCount)
        assertEquals(now, history.nowMs)
        assertEquals("C4", history.currentNote)
        assertEquals(1, history.markerCount)
    }

    @Test
    fun `NOISE 夹在中间不会把同一个音当成换音`() {
        val history = PitchHistory()
        history.feed("C4", 0, frames = 4)
        val points = history.pointCount

        // C4 → NOISE → C4：还是同一个音，不加标签、照常记点。
        history.onReading(TunerReading.NOISE)
        history.feed("C4", 2)

        assertEquals(1, history.markerCount)
        assertEquals(points + 1, history.pointCount)
    }

    // ---------------- 换音确认 ----------------

    @Test
    fun `第一个音也要稳定确认`() {
        val history = PitchHistory()

        history.feed("C4", 0)
        assertEquals("只来一帧不算确认", 0, history.pointCount)
        assertEquals(0, history.markerCount)

        history.feed("C4", 0, frames = PitchHistory.NOTE_SWITCH_CONFIRM_FRAMES - 1)
        assertEquals(1, history.pointCount)
        assertEquals(1, history.markerCount)
    }

    @Test
    fun `换音要连续稳定若干帧才确认`() {
        val history = PitchHistory()
        history.feed("C4", 0, frames = 4)
        val c4Points = history.pointCount
        assertEquals(1, history.markerCount)

        // 只来一帧 D4：还在观察，不记点、不建标签。
        history.feed("D4", -4, midi = 62)
        assertEquals(1, history.markerCount)
        assertEquals(c4Points, history.pointCount)

        // 连续够帧数：这一次才算真的换音。
        history.feed("D4", -4, midi = 62, frames = PitchHistory.NOTE_SWITCH_CONFIRM_FRAMES - 1)
        assertEquals(2, history.markerCount)
        assertEquals("D4", history.markerName(1))
        assertEquals(c4Points + 1, history.pointCount)
        assertEquals(-4, history.pointCents(history.pointCount - 1))
    }

    @Test
    fun `短暂误识别不会产生音名标签`() {
        val history = PitchHistory()
        history.feed("C4", 0, frames = 6)
        val points = history.pointCount
        assertEquals(1, history.markerCount)

        // C4 → D4 → C4：D4 只出现两帧，是一次误识别，什么都不该留下。
        history.feed("D4", -6, midi = 62, frames = PitchHistory.NOTE_SWITCH_CONFIRM_FRAMES - 1)
        history.feed("C4", 1, frames = 2)

        assertEquals(1, history.markerCount)
        assertEquals("C4", history.markerName(0))
        assertEquals(points + 2, history.pointCount)
    }

    @Test
    fun `相邻半音切换也按同样的规则确认`() {
        val history = PitchHistory()
        history.feed("C4", 0, frames = 4)
        history.feed("C#4", -3, midi = 61, frames = PitchHistory.NOTE_SWITCH_CONFIRM_FRAMES)

        assertEquals(2, history.markerCount)
        assertEquals("C#4", history.markerName(1))
        assertEquals(-3, history.markerCents(1))
    }

    @Test
    fun `快速连续换音只要稳定够帧数就会被记录`() {
        val history = PitchHistory()
        // 每个音 4 帧（约 184 毫秒，相当于每秒 5～6 个音的快板）。
        history.feed("C4", 0, frames = 4)
        history.feed("D4", -5, midi = 62, frames = 4)
        history.feed("E4", -8, midi = 64, frames = 4)
        history.feed("D4", -6, midi = 62, frames = 4)

        assertEquals(4, history.markerCount)
        assertEquals("C4", history.markerName(0))
        assertEquals("D4", history.markerName(1))
        assertEquals("E4", history.markerName(2))
        assertEquals("D4", history.markerName(3))
    }

    @Test
    fun `一直贴着边界的音等够时间也会确认`() {
        val history = PitchHistory()
        history.feed("C4", 0, frames = 3)
        val points = history.pointCount

        // 演奏者确实一直吹得很偏（-48 音分）：观察期比平时长，但不能永远不记录。
        history.feed(
            "D4",
            -48,
            midi = 62,
            frames = PitchHistory.NOTE_SWITCH_CONFIRM_TIMEOUT_FRAMES - 1
        )
        assertEquals(1, history.markerCount)
        assertEquals(points, history.pointCount)

        history.feed("D4", -48, midi = 62)
        assertEquals(2, history.markerCount)
        assertEquals(points + 1, history.pointCount)
    }

    @Test
    fun `观察被打断后候选帧数重新开始数`() {
        val history = PitchHistory()
        history.feed("C4", 0, frames = 4)

        // 差一帧就要确认了，这时候停了一下（NOISE），又过了 200 毫秒。
        history.feed("D4", -4, midi = 62, frames = PitchHistory.NOTE_SWITCH_CONFIRM_FRAMES - 1)
        history.onReading(TunerReading.NOISE)
        history.advance(100)
        history.advance(100)

        // 重新吹 D4：久远的证据不算数，要从第 1 帧重新数起。
        history.feed("D4", -4, midi = 62)
        assertEquals("不该用很久以前的帧凑数", 1, history.markerCount)

        history.feed("D4", -4, midi = 62, frames = PitchHistory.NOTE_SWITCH_CONFIRM_FRAMES - 1)
        assertEquals(2, history.markerCount)
        assertEquals("D4", history.markerName(1))
    }

    // ---------------- 换音时的过渡修剪（防尖峰） ----------------

    @Test
    fun `确认换音时会清掉旧音符末尾贴着边界的异常点`() {
        val history = PitchHistory()
        // C4 先稳住。
        history.feed("C4", 0, frames = 4)
        val stablePoints = history.pointCount

        // 音高往上滑到贴着边界：这三帧就是"换音瞬间的不稳定读数"，正是尖峰的来源。
        history.feed("C4", 48, frames = 2)
        history.feed("C4", 50, frames = 1)
        assertEquals(stablePoints + 3, history.pointCount)

        // 翻到 D4 并稳定：确认换音。
        history.feed("D4", -30, midi = 62, frames = PitchHistory.NOTE_SWITCH_CONFIRM_FRAMES)

        // 贴着边界的 3 个点被删掉，曲线从 C4 的稳定位置直接接到 D4 的稳定位置。
        assertEquals(stablePoints + 1, history.pointCount)
        assertEquals(0, history.pointCents(stablePoints - 1))
        assertEquals("最后一个点是新音符的稳定位置", -30, history.pointCents(history.pointCount - 1))
    }

    @Test
    fun `修剪不会碰正常的轨迹`() {
        val history = PitchHistory()
        history.feed("C4", 0, frames = 4)
        // 没有贴边界的读数：换音时点数一个都不该少。
        history.feed("C4", 20, frames = 3)
        val before = history.pointCount

        history.feed("D4", -12, midi = 62, frames = PitchHistory.NOTE_SWITCH_CONFIRM_FRAMES)

        assertEquals(before + 1, history.pointCount)
    }

    @Test
    fun `修剪只回看有限的时间范围`() {
        val history = PitchHistory()
        // 先正常确认 C4，这个点是"很久以前"的稳定数据。
        history.feed("C4", 0, frames = 3)
        val oldestTime = history.pointTime(0)
        // 接着一直吹在 +48 音分（贴着边界）约 1.8 秒。
        history.feed("C4", 48, frames = 40)
        val beforeTrim = history.pointCount

        history.feed("D4", -10, midi = 62, frames = PitchHistory.NOTE_SWITCH_CONFIRM_FRAMES)

        // 回看窗口（500 毫秒 ≈ 11 帧）内的贴边界点被删掉，但窗口之外的数据一点没动。
        assertTrue("窗口内的贴边界点应该被删掉", history.pointCount < beforeTrim + 1)
        assertTrue("最多只删一个回看窗口的量", history.pointCount > beforeTrim + 1 - 12)
        assertEquals(oldestTime, history.pointTime(0))
    }

    // ---------------- 时间窗口与容量上限 ----------------

    @Test
    fun `滚出时间窗口的点会被丢掉`() {
        val history = PitchHistory(windowMs = 1_000L)

        // 每 100 毫秒一帧，一共 20 帧 → 时间轴走到 2000 毫秒。
        history.feed("C4", 0, frames = 20, stepMs = 100L)

        assertEquals(2000L, history.nowMs)
        // 只保留 [1000, 2000] 里的 11 个点。
        assertEquals(11, history.pointCount)
        assertEquals(1000L, history.pointTime(0))
        assertEquals(2000L, history.pointTime(history.pointCount - 1))
    }

    @Test
    fun `单次前进有上限 卡顿之后不会一下跳空`() {
        val history = PitchHistory()
        history.advance(10_000)
        assertEquals(PitchHistory.MAX_FRAME_STEP_MS, history.nowMs)

        history.advance(0)
        history.advance(-50)
        assertEquals(PitchHistory.MAX_FRAME_STEP_MS, history.nowMs)
    }

    @Test
    fun `点数和标签数都有上限 缓冲满了挤掉最早的`() {
        val history = PitchHistory(windowMs = 100_000L, maxPoints = 4, maxMarkers = 2)

        history.feed("C4", 0, frames = 3)
        history.feed("D4", -5, midi = 62, frames = 3)
        history.feed("E4", -8, midi = 64, frames = 4)

        assertEquals(4, history.pointCount)
        for (index in 1 until history.pointCount) {
            assertTrue(
                "时间必须递增（环形缓冲回绕后顺序也要对）",
                history.pointTime(index) > history.pointTime(index - 1)
            )
        }

        assertEquals(2, history.markerCount)
        assertEquals("D4", history.markerName(0))
        assertEquals("E4", history.markerName(1))
    }

    @Test
    fun `标签记下当时音分以便画在轨迹起始位置上方`() {
        val history = PitchHistory()
        history.feed("D4", -17, midi = 62, frames = PitchHistory.NOTE_SWITCH_CONFIRM_FRAMES)

        assertEquals(1, history.markerCount)
        assertEquals(-17, history.markerCents(0))
        assertEquals(138L, history.markerTime(0))
    }
}
