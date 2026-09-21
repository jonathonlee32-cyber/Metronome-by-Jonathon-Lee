package com.example.musicpractice.tuner

import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 一个音，以及实际音高与它的偏差。
 *
 * @param midi MIDI 音高号（A4 = 69）。
 * @param name 音名，例如 "A3"、"A#4"、"B6"。
 * @param targetFrequencyHz 这个音在当前 A4 基准下的理论频率。
 * @param cents 实际音高相对它的偏差，单位音分（1 个半音 = 100 音分）。
 */
data class NoteMatch(
    val midi: Int,
    val name: String,
    val targetFrequencyHz: Double,
    val cents: Double
) {
    /** 界面上显示的整数音分：四舍五入到 1 音分。 */
    val centsRounded: Int get() = cents.roundToInt()
}

/**
 * 十二平均律（12-TET）的音高计算。
 *
 * 所有频率都由 A4 基准现算，没有任何写死的音符频率：
 *
 *     frequency = a4Hz × 2^((midi - 69) / 12)
 *
 * 用户把 A4 从 440 改成 442，所有音的理论频率都跟着变，cents 偏差也随之变化。
 *
 * 识别音域固定为 A3（MIDI 57）～ B6（MIDI 95）。
 */
object TunerNotes {

    /** A4 的 MIDI 音高号。 */
    const val A4_MIDI = 69

    /** 识别音域下限：A3。 */
    const val MIN_MIDI = 57

    /** 识别音域上限：B6。 */
    const val MAX_MIDI = 95

    /** 识别音域的文字描述，界面上用来提示用户。 */
    const val RANGE_LABEL = "A3 – B6"

    /** 一个八度 = 1200 音分，也就是音分公式里的系数。 */
    const val CENTS_PER_OCTAVE = 1200.0

    /** 一个半音 = 100 音分。 */
    const val CENTS_PER_SEMITONE = 100.0

    /**
     * "属于这个音"的范围：±50 音分。刚好是相邻两个半音的中点，
     * 所以任何一个频率都能唯一落进某个音里。
     */
    const val NOTE_RANGE_CENTS = 50.0

    /** "已经调准了"的范围：±10 音分。只有它会让中央区域变绿。 */
    const val IN_TUNE_CENTS = 10

    private val NOTE_NAMES = arrayOf(
        "C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"
    )

    /** 某个 MIDI 音在给定 A4 基准下的理论频率。 */
    fun frequencyHz(midi: Int, a4Hz: Double): Double =
        a4Hz * 2.0.pow((midi - A4_MIDI) / 12.0)

    /** MIDI 音高号 → 音名，例如 57 → "A3"、95 → "B6"。 */
    fun nameOf(midi: Int): String {
        val pitchClass = ((midi % 12) + 12) % 12
        val octave = midi / 12 - 1
        return NOTE_NAMES[pitchClass] + octave
    }

    /** 两个频率之间的音分差：1200 × log2(f / target)。 */
    fun centsBetween(frequencyHz: Double, targetHz: Double): Double =
        CENTS_PER_OCTAVE * log2(frequencyHz / targetHz)

    /**
     * 把一个频率对到十二平均律上最近的那个音。
     *
     * @return 命中的音；返回 null 表示这个频率不属于识别音域 A3～B6
     *   （例如明显低于 A3、明显高于 B6 的声音），调用方应当按"没有有效音符"处理。
     */
    fun match(frequencyHz: Double, a4Hz: Double): NoteMatch? {
        if (!frequencyHz.isFinite() || frequencyHz <= 0.0 || a4Hz <= 0.0) return null

        // 先把频率换算成"带小数的 MIDI 号"，四舍五入就得到最近的音。
        val exactMidi = A4_MIDI + 12.0 * log2(frequencyHz / a4Hz)
        val midi = exactMidi.roundToInt()

        // 音域判断：超出 A3～B6 一律不算有效音符，不往范围内强行塞。
        if (midi < MIN_MIDI || midi > MAX_MIDI) return null

        val target = frequencyHz(midi, a4Hz)
        val cents = centsBetween(frequencyHz, target)

        // 最近音的定义决定了 |cents| ≤ 50；这里再显式挡一道（浮点边界、极端输入）。
        if (abs(cents) > NOTE_RANGE_CENTS + 1e-9) return null

        return NoteMatch(
            midi = midi,
            name = nameOf(midi),
            targetFrequencyHz = target,
            cents = cents
        )
    }

    /** 是否已经调准：偏差在 ±10 音分以内。 */
    fun isInTune(cents: Int): Boolean = abs(cents) <= IN_TUNE_CENTS

    /**
     * 音分的显示文本：正数带 "+"，负数带 "-"，0 不带符号。
     * 例如 +5、0、-22。
     */
    fun formatCents(cents: Int): String = if (cents > 0) "+$cents" else cents.toString()
}
