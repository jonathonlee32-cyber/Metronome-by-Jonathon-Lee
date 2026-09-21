package com.example.musicpractice.tempo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Tap BPM 的计算规则：实时值、平均值、异常间隔和"停顿后重新开始"。 */
class TapTempoTrackerTest {

    /** 从 0 毫秒开始，按给定的间隔连续点击，返回点完之后的状态。 */
    private fun tapWithIntervals(vararg intervalsMillis: Long): TapTempoTracker {
        var tracker = TapTempoTracker()
        var now = 0L
        tracker = tracker.tap(now)
        for (interval in intervalsMillis) {
            now += interval
            tracker = tracker.tap(now)
        }
        return tracker
    }

    @Test
    fun `第一次点击只记时间不给 BPM`() {
        val tracker = TapTempoTracker().tap(1_000L)

        assertNull(tracker.liveBpm)
        assertNull(tracker.averageBpm)
        assertEquals(1, tracker.tapCount)
        assertEquals(0, tracker.validIntervalCount)
    }

    @Test
    fun `每 0_5 秒点一次是 120 BPM`() {
        val tracker = tapWithIntervals(500L)

        assertEquals(120, tracker.liveBpm)
        assertEquals(120, tracker.averageBpm)
    }

    @Test
    fun `每 1 秒点一次是 60 BPM`() {
        val tracker = tapWithIntervals(1_000L)

        assertEquals(60, tracker.liveBpm)
        assertEquals(60, tracker.averageBpm)
    }

    @Test
    fun `每 0_75 秒点一次是 80 BPM`() {
        val tracker = tapWithIntervals(750L)

        assertEquals(80, tracker.liveBpm)
        assertEquals(80, tracker.averageBpm)
    }

    @Test
    fun `实时 BPM 只看最近一次间隔`() {
        // 先按 120 BPM 点两下，再按 60 BPM 点一下。
        val tracker = tapWithIntervals(500L, 500L, 1_000L)

        // 最近一次间隔是 1000 毫秒 → 60。
        assertEquals(60, tracker.liveBpm)
        // 三个间隔的平均是 (500 + 500 + 1000) / 3 = 666.67 毫秒 → 90。
        assertEquals(90, tracker.averageBpm)
    }

    @Test
    fun `平均 BPM 用所有有效间隔的平均值`() {
        val tracker = tapWithIntervals(500L, 480L, 520L)

        // 平均间隔 500 毫秒 → 120。
        assertEquals(120, tracker.averageBpm)
        // 实时值只看最近一次间隔 520 毫秒 → 60000 / 520 ≈ 115。
        assertEquals(115, tracker.liveBpm)
        assertEquals(3, tracker.validIntervalCount)
    }

    @Test
    fun `过快的间隔被忽略且不破坏平均`() {
        // 正常的 500 毫秒里混进一次手抖（30 毫秒），这一次不算数。
        val tracker = tapWithIntervals(500L, 30L, 470L)

        assertEquals(1, tracker.ignoredTapCount)
        assertEquals(2, tracker.validIntervalCount)
        // 有效间隔是 500 和 500：30 毫秒那一下被忽略后，"上一次点击"仍停在更早的那次，
        // 所以下一次的间隔是 1000 - 500 = 500，而不是 1000 - 530 = 470。
        assertEquals(120, tracker.liveBpm)
        assertEquals(120, tracker.averageBpm)
    }

    @Test
    fun `比 300 BPM 更快的间隔不算有效`() {
        val tracker = tapWithIntervals(150L)

        assertNull(tracker.liveBpm)
        assertEquals(0, tracker.validIntervalCount)
    }

    @Test
    fun `边界值 200 毫秒和 2000 毫秒都有效`() {
        assertEquals(300, tapWithIntervals(200L).liveBpm)
        assertEquals(30, tapWithIntervals(2_000L).liveBpm)
    }

    @Test
    fun `停得太久之后的点击算新一轮`() {
        // 先按 120 BPM 打三下，停 5 秒后再打一下。
        val tracker = tapWithIntervals(500L, 500L, 5_000L)

        // 5 秒远超上限，前面攒的间隔全部作废，这一下只是新一轮的第一次点击。
        assertNull(tracker.liveBpm)
        assertNull(tracker.averageBpm)
        assertEquals(1, tracker.tapCount)
        assertEquals(0, tracker.validIntervalCount)
    }

    @Test
    fun `停顿后重新开始能重新算出 BPM`() {
        // 前三下按 120 BPM（0 / 500 / 1000 毫秒），然后停到 6000 毫秒才再点一下 ——
        // 这一下把计时清零，重新开始。
        val paused = tapWithIntervals(500L, 500L, 5_000L)
        val restarted = paused.tap(6_750L).tap(7_500L)

        assertEquals(80, restarted.liveBpm)
        assertEquals(80, restarted.averageBpm)
        assertEquals(3, restarted.tapCount)
    }

    @Test
    fun `重置回到初始状态`() {
        val tracker = tapWithIntervals(500L, 500L, 500L)
        assertEquals(120, tracker.averageBpm)

        // ViewModel 的"重置"就是换一个全新的 Tracker，这里直接验证全新实例的初始值。
        val reset = TapTempoTracker()
        assertNull(reset.liveBpm)
        assertNull(reset.averageBpm)
        assertEquals(0, reset.tapCount)
        assertEquals(0, reset.ignoredTapCount)
    }
}
