package com.example.musicpractice.ui

import com.example.musicpractice.metronome.MetronomeEngine
import com.example.musicpractice.tempo.TapTempoTracker

/** 用户选择把哪一个 BPM 应用到节拍器。 */
enum class TapBpmSource {
    /** 使用实时 BPM（最近一次点击间隔）。 */
    LIVE,

    /** 使用平均 BPM（这一轮所有有效间隔的平均值）。 */
    AVERAGE
}

/**
 * "BPM 测速"页面需要的全部数据。和 [MetronomeUiState] 一样，界面只读它，改动只能通过 ViewModel。
 */
data class TapTempoUiState(
    /** 实时 BPM：最近一次点击间隔算出来的值；还没形成间隔时为 null。 */
    val liveBpm: Int? = null,
    /** 平均 BPM：这一轮所有有效间隔的平均值算出来的值；没有有效间隔时为 null。 */
    val averageBpm: Int? = null,
    /** 这一轮有效的点击次数。 */
    val tapCount: Int = 0,
    /** 被忽略的过快点击次数。 */
    val ignoredTapCount: Int = 0,
    /** 用户选择的 BPM 来源。默认平均 BPM：它更稳，适合直接拿去当节拍。 */
    val source: TapBpmSource = TapBpmSource.AVERAGE
) {

    /** 按用户的选择，这一次该用哪个 BPM。还没有任何有效 BPM 时为 null。 */
    val targetBpm: Int?
        get() = when (source) {
            TapBpmSource.LIVE -> liveBpm
            TapBpmSource.AVERAGE -> averageBpm
        }

    /** 还没有有效 BPM 时，"应用到节拍器"应该是灰的，免得把无效数据传给节拍器。 */
    val canApply: Boolean get() = targetBpm != null
}

/** 把测速逻辑（[TapTempoTracker]）的结果整理成界面状态。是个纯函数，方便写单元测试。 */
fun buildTapTempoUiState(tracker: TapTempoTracker, source: TapBpmSource): TapTempoUiState =
    TapTempoUiState(
        liveBpm = tracker.liveBpm,
        averageBpm = tracker.averageBpm,
        tapCount = tracker.tapCount,
        ignoredTapCount = tracker.ignoredTapCount,
        source = source
    )

/**
 * 真正会写进节拍器的 BPM。
 *
 * 测速页面允许 30~300 BPM（[TapTempoTracker.MIN_BPM] / [TapTempoTracker.MAX_BPM]），
 * 而现有的节拍器引擎只能 40~240 BPM（[MetronomeEngine.MIN_BPM] / [MetronomeEngine.MAX_BPM]）。
 * 两边范围不一致时以现有节拍器为准，超出的值夹到它的范围内 —— 这样"测速页面选中的 BPM"
 * 和"节拍器能显示的 BPM"永远是一致的，不会出现按钮显示 300 而节拍器显示 240 的错位。
 */
fun appliedMetronomeBpm(bpm: Int): Int =
    bpm.coerceIn(MetronomeEngine.MIN_BPM, MetronomeEngine.MAX_BPM)
