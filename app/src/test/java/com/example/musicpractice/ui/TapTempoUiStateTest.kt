package com.example.musicpractice.ui

import com.example.musicpractice.metronome.MetronomeEngine
import com.example.musicpractice.tempo.TapTempoTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 测速页面状态：默认选哪个 BPM、什么时候能应用、超出节拍器范围怎么处理。 */
class TapTempoUiStateTest {

    @Test
    fun `默认选择平均 BPM`() {
        val state = buildTapTempoUiState(tracker = TapTempoTracker(), source = TapBpmSource.AVERAGE)

        assertEquals(TapBpmSource.AVERAGE, state.source)
    }

    @Test
    fun `没有有效 BPM 时不能应用`() {
        val state = TapTempoUiState(liveBpm = null, averageBpm = null)

        assertNull(state.targetBpm)
        assertFalse(state.canApply)
    }

    @Test
    fun `选实时就用实时值`() {
        val state = TapTempoUiState(
            liveBpm = 126,
            averageBpm = 118,
            source = TapBpmSource.LIVE
        )

        assertEquals(126, state.targetBpm)
        assertTrue(state.canApply)
    }

    @Test
    fun `选平均就用平均值`() {
        val state = TapTempoUiState(
            liveBpm = 126,
            averageBpm = 118,
            source = TapBpmSource.AVERAGE
        )

        assertEquals(118, state.targetBpm)
    }

    @Test
    fun `节拍器范围内的 BPM 原样应用`() {
        assertEquals(126, appliedMetronomeBpm(126))
        assertEquals(MetronomeEngine.MIN_BPM, appliedMetronomeBpm(MetronomeEngine.MIN_BPM))
        assertEquals(MetronomeEngine.MAX_BPM, appliedMetronomeBpm(MetronomeEngine.MAX_BPM))
    }

    @Test
    fun `超出节拍器范围的 BPM 夹到范围内`() {
        // 测速页面能算出 30~300，而节拍器只支持 40~240，所以两头的极值要被夹住。
        assertEquals(MetronomeEngine.MIN_BPM, appliedMetronomeBpm(30))
        assertEquals(MetronomeEngine.MAX_BPM, appliedMetronomeBpm(300))
        assertEquals(MetronomeEngine.MAX_BPM, appliedMetronomeBpm(241))
    }
}
