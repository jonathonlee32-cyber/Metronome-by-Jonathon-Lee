package com.example.musicpractice.tuner

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import kotlin.math.max

/**
 * 麦克风采集 + 实时基频检测。
 *
 * 数据流：AudioRecord（单声道 16bit PCM）→ 采集线程 → [TunerAnalyzer] → [listener] 回调。
 *
 * 线程模型：
 * - [start] / [stop] 由主线程调用（ViewModel 在页面可见性变化时调用）；
 * - 真正的采集与 DSP 在名为 "TunerAudioCapture" 的后台线程上跑，绝不占用主线程；
 * - 回调 [listener] 在这个后台线程上被调用，上层负责切回主线程更新界面。
 *
 * 缓冲区：一帧 [FRAME_SIZE] 个采样点（44.1kHz 下约 93 毫秒），每读入 [hopSize]（半帧）
 * 就分析一次，也就是约 46 毫秒出一个结果（≈22 次/秒）。这个长度对 A3（220Hz）来说
 * 有 20 个周期，足够 YIN 稳定测出基频；同时 46 毫秒的刷新率在界面上看起来是连续的。
 *
 * 所有缓冲区（short 原始数据、float 采样窗口、YIN 的工作数组）都在构造或 start 时一次性
 * 分配好，采集循环里不再产生新数组，避免实时线程上的内存抖动。
 */
// 这个类里所有碰麦克风的调用都要求 RECORD_AUDIO 权限。权限统一由上层（TunerViewModel）
// 检查：没有权限时根本不会调用 start()。即便如此，这里仍然对每一步都做了失败处理
// （构造失败、startRecording 抛异常、状态不是 RECORDING 都会安全返回 false 并释放资源），
// 所以权限被中途撤销也不会崩。
@SuppressLint("MissingPermission")
class TunerAudioEngine(
    /** 每分析出一帧就回调一次（在采集线程上）。 */
    private val listener: (TunerReading) -> Unit,
    private val sampleRate: Int = SAMPLE_RATE,
    private val frameSize: Int = FRAME_SIZE
) {

    private val analyzer = TunerAnalyzer(sampleRate)

    /** 保护 [record] / [worker] / [running] 的锁：start / stop 都从主线程来，操作是串行的。 */
    private val lock = Any()

    private var record: AudioRecord? = null
    private var worker: Thread? = null

    @Volatile
    private var running = false

    /** 是否正在采集。 */
    val isRunning: Boolean get() = running

    /** A4 基准变化时同步给分析器（不用重启采集）。 */
    fun setReferenceA4Hz(hz: Int) {
        analyzer.setReferenceA4Hz(hz)
    }

    /**
     * 开始采集。
     *
     * @return true 表示已经开始采集；false 表示麦克风打不开（没有权限、被别的应用占用、
     *   或者设备不支持这套参数）。返回 false 时不会有任何线程或资源被留下。
     *
     * 调用前必须已经拿到 RECORD_AUDIO 权限：没有权限时 AudioRecord 构造出来是未初始化状态，
     * 这里会直接放弃，而不会去碰麦克风。
     */
    fun start(): Boolean = synchronized(lock) {
        if (running) return true

        val newRecord = createRecord() ?: return false
        try {
            newRecord.startRecording()
        } catch (error: Throwable) {
            Log.w(TAG, "无法开始录音", error)
            newRecord.release()
            return false
        }
        if (newRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            // 权限被撤销、麦克风被占用等情况会走到这里。
            try {
                newRecord.release()
            } catch (error: Throwable) {
                Log.w(TAG, "释放 AudioRecord 失败", error)
            }
            return false
        }

        record = newRecord
        analyzer.reset()
        running = true
        worker = Thread({ captureLoop(newRecord) }, THREAD_NAME).also { it.start() }
        true
    }

    /**
     * 停止采集并释放 AudioRecord、结束采集线程。
     *
     * 重活在采集线程里做（它在循环退出后自己 stop + release），这里只负责"叫醒"阻塞在
     * read() 上的线程，所以主线程最多等 [RELEASE_JOIN_MILLIS] 毫秒，不会卡住界面。
     */
    fun stop() {
        val thread: Thread?
        val activeRecord: AudioRecord?
        synchronized(lock) {
            running = false
            thread = worker
            activeRecord = record
            worker = null
            record = null
        }
        // stop() 可以让阻塞中的 read() 立刻返回，采集线程随即跳出循环并释放资源。
        try {
            activeRecord?.stop()
        } catch (error: Throwable) {
            Log.w(TAG, "停止录音失败", error)
        }
        if (thread != null) {
            try {
                thread.join(RELEASE_JOIN_MILLIS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    /** 采集线程主循环。退出前一定会 stop + release，保证麦克风被放开。 */
    private fun captureLoop(audioRecord: AudioRecord) {
        val hopSize = frameSize / HOP_DIVISOR
        val chunk = ShortArray(hopSize)
        val window = FloatArray(frameSize)
        var filled = 0

        try {
            while (running) {
                val read = audioRecord.read(chunk, 0, chunk.size)
                if (read <= 0) {
                    // 负数表示出错（例如设备被拔掉），结束循环；0 只是暂时没有数据。
                    if (read < 0) break else continue
                }
                val count = if (read > frameSize) frameSize else read

                // 滑动窗口：整体左移 count 个样本，把新样本接到末尾。
                System.arraycopy(window, count, window, 0, frameSize - count)
                var i = 0
                while (i < count) {
                    window[frameSize - count + i] = chunk[i] * SHORT_TO_FLOAT
                    i++
                }

                filled += count
                if (filled < frameSize) continue
                filled = frameSize

                val reading = analyzer.process(window, frameSize)
                try {
                    listener(reading)
                } catch (error: Throwable) {
                    // 回调里的问题不应该拖垮采集线程。
                    Log.w(TAG, "处理检测结果失败", error)
                }
            }
        } catch (error: Throwable) {
            Log.w(TAG, "音频采集线程异常退出", error)
        } finally {
            try {
                audioRecord.stop()
            } catch (error: Throwable) {
                Log.w(TAG, "采集线程停止录音失败", error)
            }
            try {
                audioRecord.release()
            } catch (error: Throwable) {
                Log.w(TAG, "采集线程释放 AudioRecord 失败", error)
            }
        }
    }

    private fun createRecord(): AudioRecord? {
        val minBufferBytes = AudioRecord.getMinBufferSize(sampleRate, CHANNEL_CONFIG, ENCODING)
        if (minBufferBytes <= 0) {
            Log.w(TAG, "设备不支持 $sampleRate Hz 单声道录音（minBuffer=$minBufferBytes）")
            return null
        }
        // 给两帧的余量，采集线程不会因为上层偶尔慢半拍而丢数据。
        val bufferBytes = max(minBufferBytes, frameSize * BYTES_PER_SAMPLE * 2)

        for (source in audioSources()) {
            val candidate = try {
                AudioRecord(source, sampleRate, CHANNEL_CONFIG, ENCODING, bufferBytes)
            } catch (error: Throwable) {
                Log.w(TAG, "用音频源 $source 创建 AudioRecord 失败", error)
                null
            }
            if (candidate != null && candidate.state == AudioRecord.STATE_INITIALIZED) {
                return candidate
            }
            // 这个音频源在这台设备上用不了：释放掉再试下一个，而不是直接放弃。
            try {
                candidate?.release()
            } catch (error: Throwable) {
                Log.w(TAG, "释放不可用的 AudioRecord 失败", error)
            }
        }
        return null
    }

    /**
     * 优先用 UNPROCESSED（未加工的原始信号）：系统不会做自动增益、降噪和回声消除，
     * 波形的周期性最完整，基频检测最准。Android 7.0 才支持这个音频源，
     * 老设备或个别手机不支持时回落到普通 MIC。
     */
    private fun audioSources(): IntArray =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            intArrayOf(MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.MIC)
        } else {
            intArrayOf(MediaRecorder.AudioSource.MIC)
        }

    companion object {
        /** 采样率：44.1kHz，几乎所有手机都原生支持。 */
        const val SAMPLE_RATE = 44_100

        /** 每帧采样点数：约 93 毫秒。 */
        const val FRAME_SIZE = 4096

        /** 每次分析前进半帧，即约 46 毫秒一个结果。 */
        private const val HOP_DIVISOR = 2

        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val BYTES_PER_SAMPLE = 2

        /** 16bit PCM 归一化到 [-1, 1]。 */
        private const val SHORT_TO_FLOAT = 1f / 32768f

        private const val THREAD_NAME = "TunerAudioCapture"
        private const val RELEASE_JOIN_MILLIS = 500L
        private const val TAG = "TunerAudioEngine"
    }
}
