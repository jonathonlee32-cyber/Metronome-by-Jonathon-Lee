package com.example.musicpractice.metronome

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 节拍器音频引擎：负责"发声"和"什么时候发声"，不关心界面。
 *
 * ## 为什么不用 Handler / Timer / 协程 delay 来打拍子？
 *
 * 这些 API 都只是在"某个时刻之后"唤醒一个线程，具体什么时候真的被 CPU 执行，
 * 取决于系统调度、GC、界面是否卡顿，误差通常在几毫秒到几十毫秒，而且会累积。
 * 用它们打拍子，听感就是忽快忽慢。
 *
 * ## 这里用的方案：让声卡时钟驱动节拍（AudioTrack 流式播放）
 *
 * 声卡以固定采样率（这里 44100 Hz，即每秒 44100 个采样点）消费数据，这是由硬件
 * 晶振决定的，非常准。我们开一条工作线程，提前把"接下来要播的声音"写进 AudioTrack
 * 的缓冲区：写满了就阻塞等待，声卡播掉一点我们再写一点。
 *
 * 拍子落在第几个采样点上，是我们自己精确计算的：
 *   BPM = 120 时，每拍 = 60 / 120 = 0.5 秒 = 22050 个采样点。
 * 所以如果线程偶尔晚了几毫秒才被调度，那也没关系——它只是"晚一点把后续数据填进去"，
 * 已经写进缓冲区的采样点仍会按声卡的节奏原样播出，节拍不会漂移。
 *
 * 代价：声音从开始到真正被听到，会有几十毫秒的延迟（缓冲区 + 系统混音 + 硬件），
 * 这是 Android 音频播放的固有延迟。它影响的是"按下按钮到听到第一声"的延迟，
 * 不影响"拍与拍之间的间隔"，所以对节拍器来说是可以接受的。
 *
 * ## 线程模型
 *
 * 只有一条工作线程（[worker]），它随播放开始而创建、随暂停而结束，AudioTrack 也在
 * 线程内部创建和释放。加上 [generation] 这个"轮次编号"，即使上一次的线程还没完全退出，
 * 新的一次开始也不会出现两个节拍循环同时发声。
 */
class MetronomeEngine {

    companion object {
        /** BPM 的取值下限。 */
        const val MIN_BPM = 40

        /** BPM 的取值上限。 */
        const val MAX_BPM = 240

        /** 打开应用时的默认速度。 */
        const val DEFAULT_BPM = 120

        /** 采样率，单位 Hz。44100 是 Android 上最通用的取值。 */
        private const val SAMPLE_RATE = 44_100

        /** 每次往 AudioTrack 写多少个采样点。512 个约等于 11.6 毫秒。 */
        private const val CHUNK_FRAMES = 512

        /** "哒"的音高，单位 Hz。1000 Hz 左右听起来清脆，像节拍器。 */
        private const val CLICK_FREQUENCY_HZ = 1_000.0

        /** "哒"的总长度，单位毫秒。 */
        private const val CLICK_DURATION_MS = 50.0

        /** 音量衰减的时间常数，单位毫秒：越小，"哒"结束得越快。 */
        private const val CLICK_DECAY_MS = 20.0

        /** "哒"的音量，0.0~1.0。 */
        private const val CLICK_VOLUME = 0.9

        /** 一个足够大的数：保证开始播放时第一个采样点就响第一拍。 */
        private const val FIRST_BEAT_FRAMES = 1_000_000

        /** 停止时最多等待音频线程多久（毫秒）。 */
        private const val STOP_TIMEOUT_MS = 300L
    }

    /** 音频工作线程。为 null 表示当前没有在播放。 */
    private var worker: Thread? = null

    /** 保护 [worker]、[running] 和 [generation]，避免多线程同时改它们。 */
    private val lock = Any()

    /** 是否正在播放。界面线程写，音频线程读，所以用 @Volatile 保证可见性。 */
    @Volatile
    private var running = false

    /** 播放"轮次"。每次开始/暂停都 +1，用来让上一轮的循环尽快退出。 */
    @Volatile
    private var generation = 0

    /** 当前速度。音频线程每一块都会读它，所以改了之后下一拍就按新值走。 */
    @Volatile
    private var bpm = DEFAULT_BPM

    /** 当前音量，1.0 是最大，0.0 是静音。同样由音频线程每一块重新读取。 */
    @Volatile
    private var volume = 1.0f

    /**
     * 设置速度。可以在播放中调用：音频线程会在下一拍使用新的间隔。
     */
    fun setBpm(newBpm: Int) {
        bpm = newBpm.coerceIn(MIN_BPM, MAX_BPM)
    }

    /**
     * 设置音量。可以在播放中调用，下一块（约 11.6 毫秒）就会按新音量输出。
     * 音量调到 0 时仍然在"走节拍"，只是输出静音，所以恢复音量后节奏不会错位。
     */
    fun setVolume(newVolume: Float) {
        volume = newVolume.coerceIn(0.0f, 1.0f)
    }

    /**
     * 开始节拍。已经在播放时什么都不做，所以重复调用是安全的
     * （Compose 重组、用户连点按钮都不会产生第二条节拍线程）。
     */
    fun start() {
        synchronized(lock) {
            if (worker?.isAlive == true) return
            running = true
            generation += 1
            val myGeneration = generation
            worker = Thread({ playLoop(myGeneration) }, "MetronomeAudio").apply { start() }
        }
    }

    /**
     * 暂停节拍，并释放 AudioTrack。重复调用也是安全的。
     */
    fun stop() {
        val oldWorker = synchronized(lock) {
            running = false
            generation += 1
            val thread = worker
            worker = null
            thread
        }
        // 等音频线程自己收尾（正常十几毫秒）。等不到也不影响正确性：
        // 它醒来后会发现轮次已经变了，不会继续发声。
        try {
            oldWorker?.join(STOP_TIMEOUT_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * 工作线程的主循环。整个方法的要点是：把 [CHUNK_FRAMES] 个采样点填满就写一次，
     * 写不下就等着，节奏交给声卡。
     */
    private fun playLoop(myGeneration: Int) {
        val track = createAudioTrack()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            return
        }

        try {
            track.play()

            val click = buildClickSamples()
            val chunk = ShortArray(CHUNK_FRAMES)
            var framesSinceLastBeat = FIRST_BEAT_FRAMES
            var clickPosition = click.size // 等于 click 长度时表示"当前不发声"

            while (running && generation == myGeneration) {
                // 每一块都重新读一次 bpm：用户改了速度，下一拍就按新间隔走。
                // 例如 120 BPM → 22050 个采样点；改成 240 BPM → 11025 个采样点。
                val framesPerBeat = (60.0 * SAMPLE_RATE / bpm).roundToInt().coerceAtLeast(1)

                for (i in 0 until CHUNK_FRAMES) {
                    // 距上一拍已经够久了：触发新的一拍，从头开始输出"哒"。
                    if (framesSinceLastBeat >= framesPerBeat) {
                        framesSinceLastBeat = 0
                        clickPosition = 0
                    }
                    val sample = if (clickPosition < click.size) click[clickPosition++] else 0
                    chunk[i] = (sample * volume).toInt().toShort()
                    framesSinceLastBeat += 1
                }

                // 阻塞式写入：缓冲区满时会在这里等待，等声卡播掉一点再继续。
                // 返回值小于 0 表示出错，那就结束循环。
                if (track.write(chunk, 0, CHUNK_FRAMES) < 0) break
            }
        } finally {
            // pause + flush 会丢掉还没播出去的数据，保证暂停后立刻安静，
            // 然后释放掉这次播放占用的硬件资源。
            track.pause()
            track.flush()
            track.release()
        }
    }

    /**
     * 生成"哒"的波形：一段频率固定、音量指数衰减的正弦波。
     * 衰减很快（20 毫秒左右），所以听起来是一个短促的打击声，而不是一段长音。
     */
    private fun buildClickSamples(): ShortArray {
        val length = (CLICK_DURATION_MS / 1000.0 * SAMPLE_RATE).toInt()
        val samples = ShortArray(length)
        for (i in 0 until length) {
            val seconds = i.toDouble() / SAMPLE_RATE
            val envelope = exp(-seconds / (CLICK_DECAY_MS / 1000.0))
            val tone = sin(2 * PI * CLICK_FREQUENCY_HZ * seconds)
            samples[i] = (tone * envelope * CLICK_VOLUME * Short.MAX_VALUE).toInt().toShort()
        }
        return samples
    }

    /**
     * 创建 AudioTrack：单声道、16 位整数、流式播放。
     *
     * minSdk 是 21，而 AudioTrack.Builder 需要 Android 6.0（API 23）以上，
     * 所以低版本上退回使用旧的构造方法，两者效果一样。
     */
    @Suppress("DEPRECATION")
    private fun createAudioTrack(): AudioTrack {
        val minBufferBytes = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        // 缓冲区稍微留点余量：太小容易"欠载"（声音断续），太大则开始/暂停反应变慢。
        val bufferSizeBytes = maxOf(minBufferBytes, CHUNK_FRAMES * 2 * 4)

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSizeBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } else {
            AudioTrack(
                AudioManager.STREAM_MUSIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSizeBytes,
                AudioTrack.MODE_STREAM
            )
        }
    }
}
