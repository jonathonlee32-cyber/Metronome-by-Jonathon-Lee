package com.example.musicpractice.tuner

import kotlin.math.PI
import kotlin.math.sin

/**
 * 测试用的合成音频信号。
 *
 * 单元测试跑在电脑上，没有麦克风，所以用"已知频率的正弦波"当作乐器单音、
 * 用伪随机序列当作环境噪声，验证检测算法给出的频率对不对。
 */
internal object TunerTestSignals {

    /** 和引擎一致的采样率。 */
    const val SAMPLE_RATE = 44_100

    /** 和引擎一致的一帧长度。 */
    const val FRAME_SIZE = 4_096

    /**
     * 纯正弦：只有一个基频，没有泛音。
     *
     * @param frequencyHz 频率。
     * @param amplitude 峰值振幅（0～1）。
     * @param phase 起始相位，用来让波形不总是从 0 开始。
     */
    fun sine(
        frequencyHz: Double,
        amplitude: Float = 0.5f,
        length: Int = FRAME_SIZE,
        phase: Double = 0.0
    ): FloatArray {
        val samples = FloatArray(length)
        for (i in 0 until length) {
            val angle = 2.0 * PI * frequencyHz * i / SAMPLE_RATE + phase
            samples[i] = (amplitude * sin(angle)).toFloat()
        }
        return samples
    }

    /**
     * 带泛音的乐器音：基频 [fundamentalHz] 加上若干个逐渐变弱的谐波。
     *
     * 这正是调音器最容易出错的情况 —— 如果只找最强的频谱峰，很容易把
     * 二次谐波当成基频（把 A3 认成 A4）。这里用来验证算法找的是基频。
     */
    fun harmonicTone(
        fundamentalHz: Double,
        harmonicCount: Int = 5,
        amplitude: Float = 0.5f,
        length: Int = FRAME_SIZE
    ): FloatArray {
        val samples = FloatArray(length)
        for (i in 0 until length) {
            var value = 0.0
            var totalWeight = 0.0
            for (harmonic in 1..harmonicCount) {
                val weight = 1.0 / harmonic
                val angle = 2.0 * PI * fundamentalHz * harmonic * i / SAMPLE_RATE
                value += weight * sin(angle)
                totalWeight += weight
            }
            samples[i] = (amplitude * value / totalWeight).toFloat()
        }
        return samples
    }

    /**
     * 伪随机白噪声（固定种子，结果可复现）。
     * 用线性同余发生器，不依赖 java.util.Random 在不同 JDK 上的实现差异。
     */
    fun noise(amplitude: Float = 0.3f, length: Int = FRAME_SIZE, seed: Long = 20260921L): FloatArray {
        var state = seed
        val samples = FloatArray(length)
        for (i in 0 until length) {
            state = state * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L
            // 取高 24 位映射到 [-1, 1)。
            val normalized = ((state ushr 40) and 0xFFFFFF) / 8_388_608.0 - 1.0
            samples[i] = (amplitude * normalized).toFloat()
        }
        return samples
    }

    /** 完全安静的一帧。 */
    fun silence(length: Int = FRAME_SIZE): FloatArray = FloatArray(length)
}
