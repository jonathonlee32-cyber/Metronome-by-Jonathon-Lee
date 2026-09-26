package com.example.musicpractice.pitch

import com.example.musicpractice.tuner.TunerAnalyzer
import com.example.musicpractice.tuner.TunerAudioEngine
import kotlin.math.min

/**
 * 把一段 PCM 音频"逐时间片"地过一遍音高检测（需求二、十）。
 *
 * **这里没有第二套音高算法**：每个时间片都交给调音器用的那份 [TunerAnalyzer]
 * （YIN 基频检测 + 可信度判断 + 跨帧中值平滑）处理，本类只做两件事：
 *
 * 1. **切时间片**：和实时采集完全一样的窗口参数 —— [TunerAudioEngine.FRAME_SIZE] 个采样点一帧、
 *    每 [TunerAudioEngine.HOP_SIZE] 个采样点（半帧）出一个结果。参数取自同一个常量，
 *    所以"录音分析"和"调音器实时检测"的分辨率、平滑行为是一致的。
 * 2. **打时间戳**：每个结果对应"到这一刻为止"的那一帧（帧尾时间），
 *    这样播放到 00:35 时，取到的就是 00:35 这一刻正在响的那一片。
 *
 * 逐帧推进、只用固定大小的窗口和一跳缓冲，不把整段音频读进内存，所以多长的录音都能分析。
 * 这个类是纯逻辑（不碰 Android、不碰文件），可以直接用单元测试喂正弦波验证。
 *
 * @param sampleRate 音频采样率（录音是 44100Hz）。
 * @param referenceA4Hz A4 基准频率（432～448）。
 */
class PitchTrackAnalyzer(
    private val sampleRate: Int,
    referenceA4Hz: Int
) {

    private companion object {
        /** 16bit PCM 归一化到 [-1, 1]，和实时采集用的是同一个换算。 */
        const val SHORT_TO_FLOAT = 1f / 32768f
    }

    private val frameSize = TunerAudioEngine.FRAME_SIZE
    private val hopSize = TunerAudioEngine.HOP_SIZE

    /** 复用调音器的分析器：YIN 检测、可信度判断、跨帧平滑全都在这一个对象里。 */
    private val analyzer = TunerAnalyzer(sampleRate, referenceA4Hz)

    /** 滑动窗口：始终保存"最近 frameSize 个采样点"。 */
    private val window = FloatArray(frameSize)

    /** 攒够一跳就分析一次。 */
    private val hopBuffer = FloatArray(hopSize)
    private var hopFilled = 0

    /** 已经喂进来的采样点总数（用来算时间戳）。 */
    private var samplesFed = 0L

    /** 已经产出的时间点个数（方便上层估算进度）。 */
    var pointCount: Int = 0
        private set

    /** 录音当前的 A4 基准；分析过程中不会变（结果里会记下来）。 */
    val referenceA4Hz: Int get() = analyzer.a4Hz

    /** 喂进一段采样值（[-1, 1]），返回这次新产生的全部时间点。 */
    fun accept(samples: FloatArray, length: Int = samples.size): List<PitchData> {
        if (length <= 0) return emptyList()
        val produced = ArrayList<PitchData>(length / hopSize + 1)
        var index = 0
        while (index < length) {
            val take = min(hopSize - hopFilled, length - index)
            System.arraycopy(samples, index, hopBuffer, hopFilled, take)
            hopFilled += take
            index += take
            if (hopFilled == hopSize) {
                hopFilled = 0
                analyzeHop(produced)
            }
        }
        return produced
    }

    /** 喂进一段 16bit PCM（解码器给出来的原始字节）。 */
    fun acceptPcm16(samples: ShortArray, length: Int = samples.size): List<PitchData> {
        if (length <= 0) return emptyList()
        val floats = FloatArray(length)
        for (i in 0 until length) floats[i] = samples[i] * SHORT_TO_FLOAT
        return accept(floats, length)
    }

    /**
     * 收尾：把不足一跳的尾巴丢掉。
     *
     * 和实时采集一致 —— 凑不满一帧不出结果。调用方在音频读完后调一次即可
     * （当前实现不产生额外结果，保留它是为了把这个约定写在代码里）。
     */
    fun finish(): List<PitchData> = emptyList()

    /** 滑动窗口前进一跳，攒满一帧就交给调音器的分析器。 */
    private fun analyzeHop(produced: MutableList<PitchData>) {
        // 窗口左移一跳，把刚攒的一跳填到尾部。
        System.arraycopy(window, hopSize, window, 0, frameSize - hopSize)
        System.arraycopy(hopBuffer, 0, window, frameSize - hopSize, hopSize)
        samplesFed += hopSize

        // 还没攒满一帧（前几跳）时先不出结果。
        if (samplesFed < frameSize) return

        val timestampMillis = samplesFed * 1000L / sampleRate
        val point = pitchDataOf(analyzer.process(window, frameSize), timestampMillis)
        produced.add(point)
        pointCount++
    }
}
