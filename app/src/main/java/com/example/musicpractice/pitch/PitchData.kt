package com.example.musicpractice.pitch

import com.example.musicpractice.tuner.TunerNotes
import com.example.musicpractice.tuner.TunerReading

/**
 * 录音音准分析里的一个时间点（需求二、三）。
 *
 * 一次分析会把整段录音切成很多时间片，每一片出一个 [PitchData]：
 * - [timestampMillis]：这一片对应录音里的第几毫秒（从 0 开始）；
 * - [note]：识别到的音名，例如 `A4`、`C5`；没有有效音高时为 null；
 * - [cents]：音准偏差（音分），例如 +12 / -8；没有有效音高时为 0；
 * - [isValid]：true = 有效音（检测到稳定音高）；false = NOISE（空白、呼吸声、杂音）；
 * - [frequencyHz]：这一片测到的基频。**它是"原始值"**，音名和音分都是它 + 基准音高
 *   算出来的，所以之后用户改了 A4 基准，可以只用它重算，不必再解一遍音频。
 */
data class PitchData(
    val timestampMillis: Long,
    val note: String?,
    val cents: Int,
    val isValid: Boolean,
    val frequencyHz: Double = 0.0
) {

    /** 界面上显示的音分文本，例如 `+12`、`-8`；无效时是 `--`。 */
    val centsText: String
        get() = if (isValid) TunerNotes.formatCents(cents) else NO_VALUE_TEXT

    /** 界面上显示的音名，没有有效音高时是 `--`。 */
    val noteText: String get() = note ?: NO_VALUE_TEXT

    /** 界面上显示的状态：有效音符 / NOISE。 */
    val stateText: String get() = if (isValid) VALID_LABEL else NOISE_LABEL

    /** 换一个 A4 基准重算音名和音分（需求四：基准音高可改，改完结果跟着变）。 */
    fun withReference(referenceA4Hz: Int): PitchData {
        if (!isValid || frequencyHz <= 0.0) return this
        val match = TunerNotes.match(frequencyHz, referenceA4Hz.toDouble()) ?: return copy(
            note = null,
            cents = 0,
            isValid = false
        )
        return copy(note = match.name, cents = match.centsRounded)
    }

    companion object {
        /** 没有数值时界面上显示的占位符。 */
        const val NO_VALUE_TEXT = "--"

        /** 有效音的状态文案。 */
        const val VALID_LABEL = "有效音符"

        /** 没有有效音高时的状态文案（和调音器里的叫法一致）。 */
        const val NOISE_LABEL = "NOISE"
    }
}

/**
 * 一段录音的完整音准分析结果（需求三、六、九）。
 *
 * 它和录音是**一对一**的：同一段录音重做一次分析就覆盖上一份。
 * 表结构见 [com.example.musicpractice.recording.PitchAnalysisRecord] /
 * [com.example.musicpractice.recording.PitchPointRecord]：
 *
 * - 分析结果本身（基准音高、分析时间、属于哪段录音）存一张表；
 * - 每个时间点存另一张表（可能成千上万行，绝不塞进录音表）。
 *
 * @param id 分析记录的唯一 ID（`pitch-<录音 id>`）。
 * @param recordingId 属于哪段录音。
 * @param referenceA4Hz 这次分析用的 A4 基准频率（432～448）。
 * @param analysisTimeMillis 分析完成的时间（挂钟毫秒）。
 * @param points 全部时间点，按 [PitchData.timestampMillis] 升序。
 */
data class PitchAnalysis(
    val id: String,
    val recordingId: String,
    val referenceA4Hz: Int,
    val analysisTimeMillis: Long,
    val points: List<PitchData>
) {

    /** 分析的时间跨度：最后一个时间点的时刻（列表为空时是 0）。 */
    val durationMillis: Long get() = points.lastOrNull()?.timestampMillis ?: 0L

    /** 有效音的比例（0f～1f），用来说明"这段录音里有多少时间是能听清音高的"。 */
    val validRatio: Float
        get() = if (points.isEmpty()) 0f else points.count { it.isValid }.toFloat() / points.size

    /**
     * 找出播放到 [positionMillis] 时应该显示的那个时间点（需求七、八）。
     *
     * 用二分查找：一个几十分钟的录音会有几万个时间点，播放中每 250 毫秒都要查一次，
     * 顺序扫描会越查越慢，二分是 O(log n)。
     *
     * 取的是**最后一个时间戳不超过播放位置的点**（也就是"此刻正在响的那一片"）；
     * 播放位置早于第一个点、或者压根没有数据时返回 null（界面显示 `--`）。
     */
    fun pointAt(positionMillis: Long): PitchData? {
        if (points.isEmpty()) return null
        var low = 0
        var high = points.size - 1
        var found = -1
        while (low <= high) {
            val middle = (low + high) / 2
            if (points[middle].timestampMillis <= positionMillis) {
                found = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return if (found >= 0) points[found] else null
    }

    /** 换一个 A4 基准重算全部时间点（音名、音分都跟着变，原始频率不动）。 */
    fun withReference(referenceA4Hz: Int): PitchAnalysis =
        copy(
            referenceA4Hz = referenceA4Hz,
            points = points.map { it.withReference(referenceA4Hz) }
        )

    companion object {
        /** 一段录音对应的分析 ID。 */
        fun idOf(recordingId: String): String = "pitch-$recordingId"
    }
}

/**
 * 把 [TunerAnalyzer] 送出的一帧结果转成 [PitchData]（纯逻辑，单元测试直接验证）。
 *
 * 这里**没有任何新的音高算法**：音名和音分全部来自调音器的 [TunerAnalyzer]，
 * 这一层只负责"换一种表示"：把"可信 / 不可信 + NoteMatch"翻译成
 * "有效音符（音名 + 音分）/ NOISE"。
 */
internal fun pitchDataOf(
    reading: TunerReading,
    timestampMillis: Long
): PitchData {
    val match = reading.match
    return if (reading.isReliable && match != null) {
        PitchData(
            timestampMillis = timestampMillis,
            note = match.name,
            cents = match.centsRounded,
            isValid = true,
            frequencyHz = reading.frequencyHz
        )
    } else {
        PitchData(
            timestampMillis = timestampMillis,
            note = null,
            cents = 0,
            isValid = false,
            frequencyHz = 0.0
        )
    }
}
