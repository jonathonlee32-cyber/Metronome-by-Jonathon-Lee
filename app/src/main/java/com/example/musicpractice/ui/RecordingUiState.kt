package com.example.musicpractice.ui

import com.example.musicpractice.recording.RecordingFormat
import com.example.musicpractice.pitch.PitchAnalysis
import com.example.musicpractice.pitch.PitchData
import com.example.musicpractice.tuner.TunerSettingsStore
import kotlin.math.roundToInt

/**
 * 录音页（需求四）要显示的全部状态。界面只读它，不自己保存状态。
 *
 * @param isRecording 是否正在录音：按钮的样式和文字都跟着它变。
 * @param elapsedMillis 这一段已经录了多久（毫秒），每 200 毫秒刷新一次。
 * @param lastSavedName 刚保存下来的那段录音叫什么（例如 20260926-001）；这次进页面还没录过时为 null。
 * @param message 一次性提示（例如"麦克风被别的应用占用"）；null 表示没有要提示的。
 */
data class RecordingUiState(
    val isRecording: Boolean = false,
    val elapsedMillis: Long = 0L,
    val lastSavedName: String? = null,
    val message: String? = null
) {

    /** 录音中的秒表文案，例如 00:12。 */
    val elapsedLabel: String get() = RecordingFormat.clock(elapsedMillis)

    /** 大按钮下方那行主文案。 */
    val statusLabel: String get() = if (isRecording) "正在录音 $elapsedLabel" else "开始录音"

    /** 大按钮下方的说明：告诉用户"再点一下会发生什么"。 */
    val hintLabel: String get() = if (isRecording) {
        "再次点击结束录音，文件会自动保存"
    } else {
        "点击开始录音，文件只保存在本机"
    }
}

/**
 * 录音播放页（需求七）要显示的全部状态。
 *
 * @param recordingId 当前正在播的是哪一段录音；没有打开任何录音时为 null。
 * @param isPlaying 是否正在出声：中间那个大按钮在"播放 / 暂停"之间切换。
 * @param isLoading 正在打开文件（读盘、准备解码器），界面显示一个转圈。
 * @param failed 这段录音打不开了（文件被删 / 不是合法音频）。
 * @param positionMillis 当前播放位置（毫秒）。
 * @param durationMillis 总时长（毫秒），以文件真实时长为准。
 */
data class RecordingPlaybackUiState(
    val recordingId: String? = null,
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val failed: Boolean = false,
    val positionMillis: Long = 0L,
    val durationMillis: Long = 0L
) {

    /** 进度文案：01:25 / 05:40（需求七）。 */
    val progressLabel: String get() = RecordingFormat.progress(positionMillis, durationMillis)

    /** 进度条用的 0f~1f 比例。总时长还不知道时一律当成 0。 */
    val progressFraction: Float
        get() = if (durationMillis <= 0L) {
            0f
        } else {
            (positionMillis.toFloat() / durationMillis.toFloat()).coerceIn(0f, 1f)
        }
}

/**
 * 播放页上的音准分析状态（v5.1，需求一~八）。
 *
 * 界面按它决定显示哪一块：
 * - [isAnalyzing]：显示"正在分析：45%"的进度条（[showProgressDialog] 为 false 时缩成一行小提示）；
 * - [hasAnalysis]：已经把「分析音准」按钮换成分析结果；
 * - [analysis]：全部时间点，播放时按播放位置查当前音符。
 *
 * @param recordingId 当前播放页打开的是哪段录音。
 * @param isAnalyzing 后台是否正在分析这段录音。
 * @param progress 分析进度 0f～1f。
 * @param showProgressDialog 进度界面是否显示（用户点了"后台继续"之后变成 false，分析照旧跑）。
 * @param isLoadingResult 正在从数据库读已有结果（读一段长录音的结果要一瞬间）。
 * @param hasAnalysis 这段录音有没有分析结果（有就直接显示结果，不再显示按钮）。
 * @param analysis 已有的分析结果；还没读过或没分析过时为 null。
 * @param referenceA4Hz 当前 A4 基准频率（和调音器共享同一个设置）。
 * @param errorMessage 一次性错误提示（例如"分析失败"）。
 */
data class PitchAnalysisUiState(
    val recordingId: String? = null,
    val isAnalyzing: Boolean = false,
    val progress: Float = 0f,
    val showProgressDialog: Boolean = false,
    val isLoadingResult: Boolean = false,
    val hasAnalysis: Boolean = false,
    val analysis: PitchAnalysis? = null,
    val referenceA4Hz: Int = TunerSettingsStore.DEFAULT_A4_HZ,
    val errorMessage: String? = null
) {

    /** 进度百分比（整数），界面上写"正在分析：45%"。 */
    val progressPercent: Int get() = (progress * 100f).roundToInt().coerceIn(0, 100)

    /** 进度文案。 */
    val progressLabel: String get() = "正在分析：$progressPercent%"

    /** 基准音高的显示文本，例如 `A4 = 440Hz`（需求四）。 */
    val referenceLabel: String get() = "A4 = ${referenceA4Hz}Hz"

    /** 播放到 [positionMillis] 时该显示的那一片数据（需求七、八）。 */
    fun pointAt(positionMillis: Long): PitchData? = analysis?.pointAt(positionMillis)

    /** 当前显示的音名（没有数据时是 `--`）。 */
    fun noteTextAt(positionMillis: Long): String =
        pointAt(positionMillis)?.noteText ?: PitchData.NO_VALUE_TEXT

    /** 当前显示的音分（没有数据时是 `--`）。 */
    fun centsTextAt(positionMillis: Long): String =
        pointAt(positionMillis)?.centsText ?: PitchData.NO_VALUE_TEXT

    /** 当前显示的状态：有效音符 / NOISE（没有数据时是 `--`）。 */
    fun stateTextAt(positionMillis: Long): String =
        pointAt(positionMillis)?.stateText ?: PitchData.NO_VALUE_TEXT
}
