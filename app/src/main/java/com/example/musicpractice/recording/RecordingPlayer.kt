package com.example.musicpractice.recording

import android.media.MediaPlayer
import android.util.Log
import java.io.File

/**
 * 录音播放器：用 Android 原生的 [MediaPlayer] 播放一段录音（需求七）。
 *
 * 只有"放 / 停 / 跳转 / 问当前位置"这几件事，没有上一曲下一曲 —— 需求里明确不要。
 *
 * 这个类不保存任何界面状态，也不碰数据库：播放位置要落在哪儿由 ViewModel 决定，
 * 它只负责"现在播到第几毫秒了"这种毫秒级的问题。
 *
 * prepare 会读文件、准备解码器，必须在后台线程调用（ViewModel 里放在 IO 协程上）。
 */
class RecordingPlayer {

    private companion object {
        const val TAG = "RecordingPlayer"

        /**
         * 上次听到的位置离结尾不到这么近时，就从头开始播。
         *
         * 否则"上次听到了最后 0.3 秒"这种情况再进来就只剩一句听不清的尾巴，
         * 用户还得自己拖回去 —— 从头放更符合直觉。
         */
        const val RESTART_THRESHOLD_MILLIS = 1_000L
    }

    /** 正在用的播放器；为 null 表示还没准备或者已经释放。 */
    private var player: MediaPlayer? = null

    /** 已经准备好可以播放的文件路径。 */
    private var preparedPath: String? = null

    /** 当前准备好的文件路径（没准备时为 null）。 */
    val currentPath: String? get() = preparedPath

    /** 是否已经准备好可以播放。 */
    val isPrepared: Boolean get() = player != null

    /**
     * 打开一个录音文件，并从 [startPositionMillis] 处起步。
     *
     * @param onCompleted 播到结尾时的回调（在播放器自己的线程上触发，ViewModel 里会转回主线程）。
     * @return 打开成功返回 true；文件没了 / 不是合法音频时返回 false。
     */
    fun prepare(file: File, startPositionMillis: Long, onCompleted: () -> Unit): Boolean {
        release()
        if (!file.isFile || file.length() <= 0L) {
            Log.w(TAG, "录音文件不可用：${file.absolutePath}")
            return false
        }

        val created = MediaPlayer()
        return try {
            created.setDataSource(file.absolutePath)
            created.setOnCompletionListener { onCompleted() }
            created.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "播放出错：what=$what extra=$extra")
                true
            }
            created.prepare()

            // 上次听到哪儿就从哪儿继续（需求七）；快到结尾了就从头来。
            val duration = created.duration.toLong()
            val resume = startPositionMillis.coerceIn(0L, duration)
            if (duration - resume >= RESTART_THRESHOLD_MILLIS) {
                created.seekTo(resume.toInt())
            }

            player = created
            preparedPath = file.absolutePath
            true
        } catch (error: Exception) {
            Log.w(TAG, "打不开录音文件：${file.absolutePath}", error)
            runCatching { created.release() }
            false
        }
    }

    /** 开始（或继续）播放。准备好之前调用是安全的，返回 false。 */
    fun start(): Boolean {
        val current = player ?: return false
        return try {
            current.start()
            true
        } catch (error: Exception) {
            Log.w(TAG, "播放失败", error)
            false
        }
    }

    /** 暂停（不释放资源，位置保留在当前处）。 */
    fun pause() {
        val current = player ?: return
        runCatching { if (current.isPlaying) current.pause() }
    }

    /** 跳到 [positionMillis]。拖动进度条时会被连续调用。 */
    fun seekTo(positionMillis: Long) {
        val current = player ?: return
        val duration = runCatching { current.duration.toLong() }.getOrDefault(0L)
        runCatching { current.seekTo(positionMillis.coerceIn(0L, duration).toInt()) }
    }

    /** 当前播放位置（毫秒）。 */
    fun positionMillis(): Long {
        val current = player ?: return 0L
        return runCatching { current.currentPosition.toLong() }.getOrDefault(0L)
    }

    /** 文件真实时长（毫秒）。拿不到时返回 0。 */
    fun durationMillis(): Long {
        val current = player ?: return 0L
        return runCatching { current.duration.toLong() }.getOrDefault(0L)
    }

    /** 是否正在出声。 */
    fun isPlaying(): Boolean {
        val current = player ?: return false
        return runCatching { current.isPlaying }.getOrDefault(false)
    }

    /** 释放播放器（含解码器和文件句柄）。重复调用是安全的。 */
    fun release() {
        val current = player ?: return
        player = null
        preparedPath = null
        runCatching { current.pause() }
        runCatching { current.release() }
    }
}
