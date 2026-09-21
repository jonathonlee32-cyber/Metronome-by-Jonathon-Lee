package com.example.musicpractice.tempo

import kotlin.math.roundToInt

/**
 * "Tap BPM"（点按测速）的纯计算逻辑。
 *
 * 用户按稳定节奏连续点击，这里只记两样东西：上一次点击发生在什么时候，以及这一轮里所有
 * *有效* 的点击间隔。由它算出两个数字：
 *
 * - 实时 BPM：只看最近一次间隔，60000 / 间隔毫秒数，节奏一变立刻跟上；
 * - 平均 BPM：用这一轮所有有效间隔的平均值算，偶尔一次手抖不会让它大幅跳动。
 *
 * 它不依赖 Android、也不自己读系统时间：每次点击的时刻由调用方传进来（[tap]），
 * 所以可以直接写单元测试。本身是不可变的 —— [tap] 返回一个新实例，调用方不会有意外的副作用。
 *
 * ## 异常间隔怎么处理
 *
 * - 间隔比 [MIN_INTERVAL_MILLIS]（300 BPM）还短：多半是手抖连点，这次点击直接忽略，
 *   而且 *不* 更新"上一次点击"的时刻 —— 后面的间隔仍然从上一个有效点击算起，
 *   一次异常点击不会连锁破坏平均 BPM；
 * - 间隔比 [MAX_INTERVAL_MILLIS]（30 BPM）还长：说明用户已经停下来过，这一下算新一轮的开始，
 *   之前累计的间隔全部清掉，避免把中间那段停顿算进平均节奏。
 */
data class TapTempoTracker(
    /** 这一轮里最近一次点击的时刻（调用方给的毫秒数），null 表示这一轮还没点击过。 */
    private val lastTapMillis: Long? = null,
    /** 这一轮里所有有效的点击间隔，单位毫秒。平均 BPM 由它们算出来。 */
    private val intervals: List<Long> = emptyList(),
    /** 这一轮里有效的点击次数。 */
    val tapCount: Int = 0,
    /** 被忽略的异常点击次数（间隔过短）。 */
    val ignoredTapCount: Int = 0
) {

    /** 实时 BPM：只用最近一次间隔算。还没形成有效间隔时为 null。 */
    val liveBpm: Int? get() = intervals.lastOrNull()?.let { bpmOfMillis(it.toDouble()) }

    /** 平均 BPM：用这一轮所有有效间隔的平均值算。一个有效间隔都没有时为 null。 */
    val averageBpm: Int?
        get() = if (intervals.isEmpty()) null else bpmOfMillis(intervals.average())

    /** 这一轮已经累计了几个有效间隔。 */
    val validIntervalCount: Int get() = intervals.size

    /**
     * 记录一次点击，返回更新后的状态。
     *
     * @param atMillis 这次点击发生的时刻（毫秒）。用单调递增的时钟取值（例如
     *   SystemClock.elapsedRealtime()），不要用会被用户改系统时间影响的挂钟时间。
     */
    fun tap(atMillis: Long): TapTempoTracker {
        val previous = lastTapMillis
        // 第一次点击：只记时间，不算 BPM。
        if (previous == null) return copy(lastTapMillis = atMillis, tapCount = tapCount + 1)

        val interval = atMillis - previous
        return when {
            // 停得太久：当作重新开始，之前攒下的间隔都不算数。
            interval > MAX_INTERVAL_MILLIS ->
                TapTempoTracker(lastTapMillis = atMillis, tapCount = 1)

            // 快得不像话（也可能是时钟回退导致的负间隔）：忽略这一次，有效点击的时刻保持不变。
            interval < MIN_INTERVAL_MILLIS -> copy(ignoredTapCount = ignoredTapCount + 1)

            else -> copy(
                lastTapMillis = atMillis,
                intervals = intervals + interval,
                tapCount = tapCount + 1
            )
        }
    }

    companion object {
        /** 能算出来的最慢速度。比这更慢的间隔视为"用户停了"，重新开始一轮。 */
        const val MIN_BPM = 30

        /** 能算出来的最快速度。比这更快的间隔视为异常，忽略掉。 */
        const val MAX_BPM = 300

        /** 与 [MAX_BPM] 对应的最短间隔：200 毫秒。 */
        const val MIN_INTERVAL_MILLIS = 60_000 / MAX_BPM

        /** 与 [MIN_BPM] 对应的最长间隔：2000 毫秒。 */
        const val MAX_INTERVAL_MILLIS = 60_000 / MIN_BPM
    }
}

/** 毫秒 → BPM：一分钟 60000 毫秒除以一次点击的间隔，就是每分钟多少次。 */
internal fun bpmOfMillis(millis: Double): Int = (60_000.0 / millis).roundToInt()
