package com.example.musicpractice.recording

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.io.File

/**
 * 录音：用 Android 原生 [MediaRecorder] 把麦克风的声音录进一个文件（需求四、五、十二）。
 *
 * - 音频来源：麦克风（[MediaRecorder.AudioSource.MIC]）；
 * - 容器 / 编码：MPEG-4 + AAC（`m4a`）。这是 Android 上最通用的一对组合，
 *   系统自带的播放器也认，不需要引入任何第三方编码库；
 * - 输出：App 私有目录里的一个文件（路径由 [RecordingFiles] 给）；
 * - 全程离线：录出来的音频只写进本机私有目录，不上传任何地方。
 *
 * 这个类只负责"开始 / 结束"这一件事，不关心界面，也不碰数据库 ——
 * 编号、落库、播放位置那些都归 [RecordingRepository] 和 [RecordingPlayer] 管。
 *
 * 录音时长以 [SystemClock.elapsedRealtime] 的秒表为准：它单调递增、不受用户改系统时间影响，
 * 而且"按下开始 → 按下结束"之间的时间正是用户看到的那段录音。
 *
 * @param context 用来在 Android 12 及以上创建录音器（那个版本起 `MediaRecorder` 需要 Context）。
 *   ViewModel 传进来的是 Application Context，所以这里不会泄漏 Activity。
 */
class RecordingRecorder(private val context: Context) {

    private companion object {
        const val TAG = "RecordingRecorder"

        /** 采样率 44100Hz：和节拍器的音频引擎一样，是 Android 上最通用的取值。 */
        const val SAMPLE_RATE = 44_100

        /** 单声道就够用（乐器 / 人声练习录音），文件也更小。 */
        const val CHANNELS = 1

        /** 编码码率 128kbps：AAC 在这个码率下人声和乐器都已经很清楚。 */
        const val BIT_RATE = 128_000
    }

    /** 当前正在用的录音器；为 null 表示没有在录。 */
    private var recorder: MediaRecorder? = null

    /** 这一段录音是什么时候开始的（单调时钟）。 */
    private var startedAtMillis = 0L

    /** 是否正在录音。 */
    val isRecording: Boolean get() = recorder != null

    /** 这一段已经录了多久（毫秒）；没在录时返回 0。 */
    fun elapsedMillis(): Long =
        if (recorder == null) 0L else SystemClock.elapsedRealtime() - startedAtMillis

    /**
     * 开始录音，把声音写进 [file]。
     *
     * @return 成功返回 true；麦克风被别的 App 占用、文件写不了等情况下返回 false
     *   （界面据此提示"录音失败"，绝不会留下一个半截文件当作成功）。
     */
    fun start(file: File): Boolean {
        // 保险：上一次没停干净的话先收尾，避免两段录音同时往一个文件里写。
        releaseQuietly()

        val created = createRecorder()
        return try {
            created.setAudioSource(MediaRecorder.AudioSource.MIC)
            created.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            created.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            created.setAudioSamplingRate(SAMPLE_RATE)
            created.setAudioChannels(CHANNELS)
            created.setAudioEncodingBitRate(BIT_RATE)
            created.setOutputFile(file.absolutePath)
            created.prepare()
            created.start()

            recorder = created
            startedAtMillis = SystemClock.elapsedRealtime()
            true
        } catch (error: Exception) {
            // 常见原因：麦克风被别的 App 占着（连不上麦克风）、目录不可写、文件被占用。
            Log.w(TAG, "开始录音失败", error)
            runCatching { created.reset() }
            runCatching { created.release() }
            // 录失败时清掉可能已经建出来的空文件，免得目录里留一个 0 字节的垃圾。
            runCatching { if (file.length() == 0L) file.delete() }
            false
        }
    }

    /**
     * 结束录音，返回这一段录了多久（毫秒）。
     *
     * 没有在录音时返回 0。这一句会阻塞到音频数据写完，所以调用方要在后台线程（或 IO 协程）里调。
     */
    fun stop(): Long {
        val current = recorder ?: return 0L
        recorder = null
        val duration = SystemClock.elapsedRealtime() - startedAtMillis
        startedAtMillis = 0L

        return try {
            // 正常结束：stop() 会把缓冲里的数据写完并落盘。
            // 录得太短（比如刚按下就松开）时它会抛异常，那时文件里本来就没有有效数据。
            current.stop()
            duration
        } catch (error: Exception) {
            Log.w(TAG, "结束录音时出错，这一段内容可能不完整", error)
            0L
        } finally {
            runCatching { current.release() }
        }
    }

    /** 不收尾地放弃当前这一段（离开录音页时的兜底），尽量别留下占着的麦克风。 */
    fun releaseQuietly() {
        val current = recorder ?: return
        recorder = null
        startedAtMillis = 0L
        runCatching { current.stop() }
        runCatching { current.release() }
    }

    /**
     * 建一个 MediaRecorder。
     *
     * minSdk 是 21，而 `MediaRecorder(Context)` 需要 Android 12（API 31）以上，
     * 所以低版本上退回使用旧的构造方法，两者录音效果一样。
     */
    @Suppress("DEPRECATION")
    private fun createRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context.applicationContext)
        } else {
            MediaRecorder()
        }
}
