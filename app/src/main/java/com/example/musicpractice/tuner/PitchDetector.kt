package com.example.musicpractice.tuner

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 一帧音频的基频检测结果。
 *
 * @param frequencyHz 检测到的基频（Hz）。
 * @param clarity 置信度 0..1：1 表示"周期性极强"（纯净的单音），越小说明这段声音越不像
 *   规则周期信号（噪声、多个音混在一起、或者根本没人演奏）。
 * @param rms 这一帧的音量（对 [-1,1] 采样归一化后的均方根）。
 */
data class PitchDetection(
    val frequencyHz: Float,
    val clarity: Float,
    val rms: Float
)

/**
 * 基频检测器：YIN 算法（de Cheveigné & Kawahara, 2002）。
 *
 * 为什么用 YIN，而不是"找出 FFT 里最大的那个峰"：
 * 乐器（尤其中低音区）的能量有很大一部分在泛音上，最强的谱峰经常是二次、三次谐波，
 * 直接把最大峰值当基频就会把 A4 认成 A5、E5。YIN 不算频谱，而是算"这段波形往后
 * 移 tau 个采样点以后，和原波形还像不像自己"（差值函数），并做累积均值归一化，
 * 然后从 **最短的** 那个"像自己"的周期开始找 —— 也就是基频周期，而不是泛音周期。
 *
 * 计算步骤（全部在固定大小的数组上原地完成，不做任何分配）：
 * 1. 算 RMS，静音直接返回 null，后面的重计算全部省掉；
 * 2. 差值函数 d(tau) = Σ (x[j] - x[j+tau])²；
 * 3. 累积均值归一化 d'(tau) = d(tau) / ((1/tau)·Σ_{k≤tau} d(k))，这一步让阈值与音量无关；
 * 4. 从 tau 最小的一端开始，取第一个低于 [threshold] 的谷底（基频周期）；
 * 5. 抛物线插值把 tau 精确到小数，再换算成频率。
 *
 * 这是一个"单帧"检测器：一帧给一个结果，不做任何跨帧判断。可信度判定和噪声处理在
 * [TunerAnalyzer] 里做，这样两层可以分别测试。
 *
 * @param sampleRate 采样率（Hz）。
 * @param minFrequencyHz 搜索范围下限。取得比识别音域（A3 = 220Hz）更宽，
 *   这样明显偏低的声音也能被测到，然后由音域判断把它挡掉，而不是让它变成一个假音。
 * @param maxFrequencyHz 搜索范围上限，略高于识别音域上限（B6 ≈ 1976Hz）。
 * @param threshold YIN 的绝对阈值，越小越严格。
 */
class PitchDetector(
    private val sampleRate: Int,
    minFrequencyHz: Float = MIN_FREQUENCY_HZ,
    maxFrequencyHz: Float = MAX_FREQUENCY_HZ,
    private val threshold: Float = YIN_THRESHOLD
) {

    /** 最小周期（采样点数）：对应搜索范围上限。 */
    private val tauMin: Int = max(2, (sampleRate / maxFrequencyHz).toInt())

    /** 最大周期（采样点数）：对应搜索范围下限。 */
    private val tauMax: Int = max(tauMin + 1, (sampleRate / minFrequencyHz).toInt())

    // 两个工作数组只在这里分配一次，之后每一帧反复覆盖使用，音频线程上没有对象产生。
    private val difference = FloatArray(tauMax + 2)
    private val normalized = FloatArray(tauMax + 2)

    /**
     * 分析一帧音频。
     *
     * @param samples 采样值，范围 [-1, 1]。
     * @param length 有效样本数（[samples] 可能比它长，长出来的部分不参与计算）。
     * @return 检测结果；静音或短得没法分析时返回 null。
     */
    fun analyze(samples: FloatArray, length: Int = samples.size): PitchDetection? {
        // 差值函数比较的是 x[j] 和 x[j+tau]，取一半长度做比较窗口，保证每个 tau
        // 用的是同样多的样本，d(tau) 之间才能直接比较。
        val window = length / 2
        val maxTau = min(tauMax, window - 1)
        if (maxTau < tauMin) return null

        var sumOfSquares = 0f
        for (i in 0 until length) {
            val value = samples[i]
            sumOfSquares += value * value
        }
        val rms = sqrt(sumOfSquares / length)
        // 几乎没有声音：不用往下算了。
        if (rms < SILENCE_RMS) return null

        // 1) 差值函数。
        var tau = 1
        while (tau <= maxTau) {
            var sum = 0f
            var j = 0
            while (j < window) {
                val delta = samples[j] - samples[j + tau]
                sum += delta * delta
                j += DIFFERENCE_STEP
            }
            difference[tau] = sum
            tau++
        }

        // 2) 累积均值归一化：把"音量大小"从差值里除掉，阈值才有统一含义。
        normalized[0] = 1f
        var runningSum = 0f
        tau = 1
        while (tau <= maxTau) {
            runningSum += difference[tau]
            normalized[tau] = if (runningSum <= 1e-12f) 1f else difference[tau] * tau / runningSum
            tau++
        }

        // 3) 找第一个低于阈值的谷底。从短周期往长周期扫，找到的就是基频周期；
        //    如果先扫到长周期，就会把 A4 认成 A3（低八度错误），所以顺序很重要。
        var estimate = -1
        tau = tauMin
        while (tau <= maxTau) {
            if (normalized[tau] < threshold) {
                var dip = tau
                while (dip + 1 <= maxTau && normalized[dip + 1] < normalized[dip]) dip++
                estimate = dip
                break
            }
            tau++
        }
        if (estimate < 0) {
            // 没有谷底低过阈值（典型情况：白噪声、人说话、环境杂音）。
            // 仍然把最小值返回去，让上层按置信度判断"不可信"，而不是在这里就下结论。
            var best = tauMin
            for (t in tauMin..maxTau) {
                if (normalized[t] < normalized[best]) best = t
            }
            estimate = best
        }

        val clarity = (1f - normalized[estimate]).coerceIn(0f, 1f)
        val preciseTau = interpolateTau(estimate, maxTau)
        if (preciseTau <= 0f) return null

        return PitchDetection(
            frequencyHz = sampleRate / preciseTau,
            clarity = clarity,
            rms = rms
        )
    }

    /**
     * 抛物线插值：真正的周期几乎不会正好落在整数采样点上，用谷底和左右两点拟合一条抛物线，
     * 把 tau 精确到小数。不做这一步，高频区的误差会有几十音分（远大于调音器需要的精度）。
     */
    private fun interpolateTau(tau: Int, maxTau: Int): Float {
        if (tau <= 1 || tau >= maxTau) return tau.toFloat()
        val before = normalized[tau - 1]
        val current = normalized[tau]
        val after = normalized[tau + 1]
        val denominator = 2f * (2f * current - after - before)
        if (abs(denominator) < 1e-9f) return tau.toFloat()
        return tau + (after - before) / denominator
    }

    companion object {
        /** 搜索范围下限：70Hz，比识别音域的下限（A3 = 220Hz）低得多，便于识别"明显偏低"。 */
        const val MIN_FREQUENCY_HZ = 70f

        /** 搜索范围上限：2200Hz，略高于识别音域上限（B6，A4=448Hz 时约 2011Hz）。 */
        const val MAX_FREQUENCY_HZ = 2200f

        /** YIN 论文推荐的阈值。 */
        const val YIN_THRESHOLD = 0.15f

        /**
         * 差值函数在窗口内的取样步长。取 2 等于把内层循环的运算量减半
         * （归一化是比值，缩放不影响结果），对音高精度的影响远小于 1 音分。
         */
        private const val DIFFERENCE_STEP = 2

        /** 低到这个音量就当没有声音，直接跳过整帧计算。 */
        private const val SILENCE_RMS = 1e-4f
    }
}
