package com.example.musicpractice.ui

import com.example.musicpractice.tuner.TunerNotes
import com.example.musicpractice.tuner.TunerReading
import com.example.musicpractice.tuner.TunerSettingsStore

/** 还没有识别到任何音时，中央显示区里的占位符。 */
const val TUNER_NO_VALUE_TEXT = "--"

/** 麦克风权限的几种状态，界面按它决定显示哪种提示。 */
enum class MicPermissionState {
    /** 还没授权，也没被明确拒绝过（刚进页面时的状态）。 */
    NEEDS_PERMISSION,

    /** 已授权，正在采集。 */
    GRANTED,

    /** 用户拒绝了这一次，但还能再问（系统会再次弹窗）。 */
    DENIED,

    /** 用户选了"不再询问"或拒绝多次：只能去系统设置里手动开。 */
    DENIED_PERMANENTLY,

    /** 有权限但麦克风打不开（被别的应用占用、设备不支持等）。 */
    UNAVAILABLE
}

/**
 * "调音器"页面需要的全部数据。
 *
 * 和 [MetronomeUiState]、[TapTempoUiState] 一样：界面只读它，改动只能通过 TunerViewModel。
 *
 * @param a4Hz A4 基准频率，432～448Hz。
 * @param micPermission 麦克风权限状态。
 * @param noteName 当前显示的音名（例如 "A4"）；还没识别到过任何音时为 null。
 * @param cents 当前显示的整数音分偏差；还没识别到过任何音时为 null。
 * @param isReliable 当前这一帧是否可信。false 表示 NOISE：音符和音分保留上一次的结果。
 * @param frequencyHz 最近一次可信的基频（Hz），用于换 A4 基准时重算音名/音分。
 */
data class TunerUiState(
    val a4Hz: Int = TunerSettingsStore.DEFAULT_A4_HZ,
    val micPermission: MicPermissionState = MicPermissionState.NEEDS_PERMISSION,
    val noteName: String? = null,
    val cents: Int? = null,
    val isReliable: Boolean = false,
    val frequencyHz: Double? = null
) {

    /** 是否已经有可显示的音（识别过一次之后就一直是 true，噪声期间也保留）。 */
    val hasNote: Boolean get() = noteName != null && cents != null

    /** 中央大号音符文本。没识别过时是 "--"。 */
    val noteText: String get() = noteName ?: TUNER_NO_VALUE_TEXT

    /** 中央音分文本。没识别过时是 "--"。 */
    val centsText: String get() = cents?.let(TunerNotes::formatCents) ?: TUNER_NO_VALUE_TEXT

    /**
     * 是否显示"已经调准了"的绿色背景。
     *
     * 条件：当前这一帧可信 + 偏差在 ±10 音分以内。噪声期间只保留上一次的音名和音分，
     * 但不再显示绿色 —— 那个绿色代表"现在正吹准"，信号都没了就不该继续亮着。
     */
    val isInTune: Boolean
        get() = isReliable && cents != null && TunerNotes.isInTune(cents)

    /** NOISE 指示器是否变蓝。 */
    val isNoise: Boolean get() = !isReliable

    /**
     * 音准历史轨迹是否在滚动（见 [com.example.musicpractice.ui.PitchHistoryPanel]）。
     *
     * 条件：正在采集 + 当前这一帧是有效音符。NOISE 期间为 false —— 轨迹整体停住：
     * 不加新的轨迹数据、已有的白色曲线／音名标签也不移动。
     */
    val isHistoryRolling: Boolean
        get() = isReliable && micPermission == MicPermissionState.GRANTED
}

/**
 * 把一帧检测结果合进界面状态。
 *
 * 可信：更新音名与音分，NOISE 指示器变灰。
 * 不可信（NOISE）：**保留**上一次的音名和音分，只把指示器变蓝，
 * 这样用户因为短暂走音、换气或环境噪声丢了一两帧时，界面不会疯狂跳动。
 */
fun TunerUiState.withReading(reading: TunerReading): TunerUiState {
    val match = reading.match
    return if (reading.isReliable && match != null) {
        copy(
            noteName = match.name,
            cents = match.centsRounded,
            isReliable = true,
            frequencyHz = reading.frequencyHz
        )
    } else {
        copy(isReliable = false)
    }
}

/**
 * A4 基准改变后，用最近一次测到的频率重算音名和音分。
 *
 * 十二平均律下每个音的理论频率都跟着 A4 变，所以调完基准不用等下一帧音频，
 * 界面上的偏差就应当是新的了。
 */
fun TunerUiState.withReferenceA4Hz(a4Hz: Int): TunerUiState {
    val frequency = frequencyHz
    val match = frequency?.let { TunerNotes.match(it, a4Hz.toDouble()) }
    return copy(
        a4Hz = a4Hz,
        noteName = match?.name,
        cents = match?.centsRounded
    )
}
