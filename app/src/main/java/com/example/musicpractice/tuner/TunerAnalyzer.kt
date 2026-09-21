package com.example.musicpractice.tuner

/**
 * 一帧音频经过"可信度判断 + 音高匹配"之后的结果。
 *
 * @param isReliable 这一帧是否可信。false 表示应当进入 NOISE 状态：
 *   界面上保留上一次有效识别结果，只把 NOISE 指示器变蓝。
 * @param match 可信时识别到的音（音名 + 音分偏差）；不可信时为 null。
 * @param frequencyHz 这一帧测到的基频；不可信时为 0。
 */
data class TunerReading(
    val isReliable: Boolean,
    val match: NoteMatch? = null,
    val frequencyHz: Double = 0.0
) {
    companion object {
        /** 不可信的一帧：没有音符，也没有频率。 */
        val NOISE = TunerReading(isReliable = false)
    }
}

/**
 * 把音频帧变成"可信 / 不可信 + 音名 + 音分"的判断器。
 *
 * 这一层负责需求里说的"可靠性判断"，它比"有没有声音"严格得多。一帧要算作有效识别，
 * 必须同时满足下面所有条件：
 *
 * 1. **音量够大**：RMS ≥ [minRms]。太轻的声音（远处环境噪声、麦克风底噪）直接算噪声。
 * 2. **周期性够强**：YIN 的置信度 ≥ [minClarity]。白噪声、说话声、多个音混在一起时，
 *    波形怎么平移都不像自己，置信度上不去，于是判为噪声。
 * 3. **音域内**：最近的音必须落在 A3～B6 里，且偏差在 ±50 音分内。
 *    明显偏低（例如 100Hz）或偏高的声音会被这里挡掉 —— 不会硬凑成一个范围内的音。
 * 4. **跨帧稳定**：把最近 3 帧的基频取中值再做匹配，单帧的跳变（起音瞬间、爆音、
 *    偶尔的八度误判）不会让界面上的音名乱跳。
 *
 * 相机被挡住或者人停下来时，条件 1、2 会立刻失败 → 返回不可信的读数 → 界面进入 NOISE，
 * 但中央显示的音符和音分保持不变。
 *
 * @param sampleRate 采样率，必须和实际采集一致。
 * @param a4Hz 当前的 A4 基准频率（Hz）。
 */
class TunerAnalyzer(
    sampleRate: Int,
    a4Hz: Int = TunerSettingsStore.DEFAULT_A4_HZ,
    private val minRms: Float = MIN_RMS,
    private val minClarity: Float = MIN_CLARITY,
    private val detector: PitchDetector = PitchDetector(sampleRate)
) {

    /** 当前 A4 基准。改了它，所有音的理论频率与音分偏差都会跟着变。 */
    var a4Hz: Int = clampA4(a4Hz)
        private set

    // 最近几帧的基频（Hz），用来做中值平滑。固定大小，循环使用，不产生垃圾。
    private val history = DoubleArray(HISTORY_SIZE)
    private var historyCount = 0
    private var historyIndex = 0
    private val scratch = DoubleArray(HISTORY_SIZE)

    /** 修改 A4 基准。超出 432～448 的值会被夹到范围内。 */
    fun setReferenceA4Hz(hz: Int) {
        a4Hz = clampA4(hz)
    }

    /** 清空跨帧平滑状态（开始采集、停止采集时调用）。 */
    fun reset() {
        historyCount = 0
        historyIndex = 0
    }

    /**
     * 分析一帧音频，给出这一帧的识别结果。
     *
     * @param samples 采样值，范围 [-1, 1]。
     * @param length 有效样本数。
     */
    fun process(samples: FloatArray, length: Int = samples.size): TunerReading {
        val detection = detector.analyze(samples, length) ?: return TunerReading.NOISE

        // 条件 1、2：音量与周期性。
        if (detection.rms < minRms || detection.clarity < minClarity) return TunerReading.NOISE

        val frequency = detection.frequencyHz.toDouble()

        // 条件 3：先看这一帧本身是否落在识别音域里。不在范围内就不进平滑历史，
        // 免得把"明显偏低/偏高"的样本混进来，让接下来几帧都被带偏。
        if (TunerNotes.match(frequency, a4Hz.toDouble()) == null) return TunerReading.NOISE

        // 条件 4：中值平滑。最近 3 帧里排中间的那个值，能压掉单帧跳变，
        // 又不会像平均值那样把正在变化（用户正在拧弦）的音高拖住。
        pushHistory(frequency)
        val smoothed = medianOfHistory()

        val match = TunerNotes.match(smoothed, a4Hz.toDouble())
            ?: TunerNotes.match(frequency, a4Hz.toDouble())
            ?: return TunerReading.NOISE

        return TunerReading(
            isReliable = true,
            match = match,
            frequencyHz = smoothed
        )
    }

    private fun pushHistory(frequencyHz: Double) {
        history[historyIndex] = frequencyHz
        historyIndex = (historyIndex + 1) % HISTORY_SIZE
        if (historyCount < HISTORY_SIZE) historyCount++
    }

    private fun medianOfHistory(): Double {
        if (historyCount == 0) return 0.0
        if (historyCount == 1) return history[0]

        // 最多 3 个元素，插入排序足够（而且是原地排序，不分配新数组）。
        System.arraycopy(history, 0, scratch, 0, historyCount)
        for (i in 1 until historyCount) {
            val value = scratch[i]
            var j = i - 1
            while (j >= 0 && scratch[j] > value) {
                scratch[j + 1] = scratch[j]
                j--
            }
            scratch[j + 1] = value
        }
        val middle = historyCount / 2
        return if (historyCount % 2 == 1) {
            scratch[middle]
        } else {
            (scratch[middle - 1] + scratch[middle]) / 2.0
        }
    }

    private fun clampA4(hz: Int): Int =
        hz.coerceIn(TunerSettingsStore.MIN_A4_HZ, TunerSettingsStore.MAX_A4_HZ)

    companion object {
        /**
         * 音量下限：RMS 0.006，约 -44dBFS。
         * 手机麦克风在一臂距离上收到乐器单音时，RMS 通常在 0.02～0.3，
         * 安静房间的底噪在 0.001 以下，所以这个阈值既能拾到轻奏，也不会被底噪触发。
         */
        const val MIN_RMS = 0.006f

        /**
         * 置信度下限：0.80。
         * YIN 论文的绝对阈值 0.15 对应置信度 0.85，这里再放宽一点，
         * 让带揉弦、带泛音的正常乐器音也能通过，同时仍然挡得住噪声。
         */
        const val MIN_CLARITY = 0.80f

        /** 参与中值平滑的帧数。 */
        private const val HISTORY_SIZE = 3
    }
}
