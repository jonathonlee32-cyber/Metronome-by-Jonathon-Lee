package com.example.musicpractice.pitch

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

/**
 * 对一整段录音文件做离线音准分析（需求二、十）。
 *
 * 流程：**MediaExtractor 拆出音频轨 → MediaCodec 解码成 PCM → [PitchTrackAnalyzer] 逐时间片检测**。
 *
 * 两个关键点：
 * 1. **复用调音器的音高算法**：解码出来的每一帧都喂给 [PitchTrackAnalyzer]，
 *    而它内部用的就是调音器那个 [com.example.musicpractice.tuner.TunerAnalyzer]（YIN）。
 *    这里一行音高算法都没有重写，只负责"把音频文件变成 PCM"和"报进度"。
 * 2. **不占 UI 线程、流式处理**：解码边解边算，内存里只放一小段 PCM，
 *    所以几十分钟的录音也能分析（调用方把它放在 IO 协程上跑）。
 *
 * 这个类只碰音频解码，不碰数据库：结果写不写、写到哪儿由
 * [com.example.musicpractice.recording.RecordingRepository] 决定。
 */
class RecordingPitchAnalyzer {

    private companion object {
        const val TAG = "PitchAnalyzer"

        /** 解码器给数据的等待时间（微秒）。 */
        const val DEQUEUE_TIMEOUT_US = 10_000L

        /** 进度回调的最小间隔（毫秒）：分析很快时不必每帧都通知界面。 */
        const val PROGRESS_INTERVAL_MILLIS = 200L

        /** 16bit PCM 归一化到 [-1, 1]，和录音、调音器用的是同一个换算。 */
        const val SHORT_TO_FLOAT = 1f / 32768f

        /** 拿不到解码器参数时的兜底采样率（和录音、调音器一致）。 */
        const val DEFAULT_SAMPLE_RATE = 44_100
    }

    /**
     * 分析一段录音。
     *
     * @param file 录音文件（App 私有目录里的 m4a）。
     * @param recordingId 这段录音在数据库里的 ID（结果会挂到它下面）。
     * @param referenceA4Hz 分析用的 A4 基准频率。
     * @param onProgress 进度回调（0f～1f），不会比每 200 毫秒更频繁。
     * @return 分析结果；文件打不开、没有音频轨、解码失败时返回 null。
     */
    suspend fun analyze(
        file: File,
        recordingId: String,
        referenceA4Hz: Int,
        onProgress: (Float) -> Unit
    ): PitchAnalysis? = withContext(Dispatchers.IO) {
        if (!file.isFile || file.length() <= 0L) {
            Log.w(TAG, "录音文件不可用：${file.absolutePath}")
            return@withContext null
        }

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)

            // 找第一条音频轨。
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index)
                    .getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: run {
                Log.w(TAG, "文件里没有音频轨：${file.name}")
                return@withContext null
            }

            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return@withContext null
            // 总时长（微秒 → 毫秒），用来算分析进度。
            val durationMillis = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION) / 1000L
            } else {
                0L
            }
            extractor.selectTrack(trackIndex)

            val decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(format, null, null, 0)
            decoder.start()
            codec = decoder

            decodeAndAnalyze(
                extractor = extractor,
                decoder = decoder,
                recordingId = recordingId,
                durationMillis = durationMillis,
                referenceA4Hz = referenceA4Hz,
                onProgress = onProgress
            )
        } catch (error: Exception) {
            Log.w(TAG, "分析 ${file.name} 失败", error)
            null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /**
     * 真正"边解码边分析"的主循环。
     *
     * 结构就是官方文档里那个"喂输入缓冲 → 取输出缓冲"的循环，
     * 唯一特别的地方是：拿到 PCM 之后立刻交给 [PitchTrackAnalyzer]，不攒着。
     */
    private suspend fun decodeAndAnalyze(
        extractor: MediaExtractor,
        decoder: MediaCodec,
        recordingId: String,
        durationMillis: Long,
        referenceA4Hz: Int,
        onProgress: (Float) -> Unit
    ): PitchAnalysis? {
        val info = MediaCodec.BufferInfo()
        val points = ArrayList<PitchData>()

        var tracker = PitchTrackAnalyzer(sampleRate = DEFAULT_SAMPLE_RATE, referenceA4Hz = referenceA4Hz)
        var channels = 1
        var inputDone = false
        var outputDone = false
        var lastProgressAt = 0L
        var samples = FloatArray(0)

        while (!outputDone) {
            // 分析跑在协程里：用户离开页面 / 进程退出时立刻停下，不留下后台线程。
            coroutineContext.ensureActive()

            if (!inputDone) {
                val inputIndex = decoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                if (inputIndex >= 0) {
                    val inputBuffer = decoder.getInputBuffer(inputIndex)
                    val sampleSize = if (inputBuffer == null) {
                        -1
                    } else {
                        extractor.readSampleData(inputBuffer, 0)
                    }
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(
                            inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            when (val outputIndex = decoder.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // 解码器这时候才给出真实参数：采样率变了就重建分析器，声道数记下来备用。
                    val outputFormat = decoder.outputFormat
                    val sampleRate = outputFormat.intOrNull(MediaFormat.KEY_SAMPLE_RATE)
                        ?: DEFAULT_SAMPLE_RATE
                    channels = outputFormat.intOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: 1
                    tracker = PitchTrackAnalyzer(
                        sampleRate = if (sampleRate > 0) sampleRate else DEFAULT_SAMPLE_RATE,
                        referenceA4Hz = referenceA4Hz
                    )
                }

                else -> if (outputIndex >= 0) {
                    val buffer = decoder.getOutputBuffer(outputIndex)
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (buffer != null && info.size > 0 && !isConfig) {
                        // 先按这一帧的实际大小准备缓冲（帧大小基本稳定，所以只会分配一次），
                        // 再把 PCM 归一化写进去，最后整帧交给分析器。
                        val count = sampleCountOf(info, channels)
                        if (samples.size < count) samples = FloatArray(count)
                        if (count > 0) {
                            fillSamples(buffer, info, channels, samples)
                            val produced = tracker.accept(samples, count)
                            points.addAll(produced)
                        }

                        val elapsed = points.lastOrNull()?.timestampMillis ?: 0L
                        val now = System.currentTimeMillis()
                        if (now - lastProgressAt >= PROGRESS_INTERVAL_MILLIS) {
                            lastProgressAt = now
                            onProgress(progressOf(elapsed, durationMillis))
                        }
                    }
                    decoder.releaseOutputBuffer(outputIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        }

        points.addAll(tracker.finish())
        onProgress(1f)

        return PitchAnalysis(
            id = PitchAnalysis.idOf(recordingId),
            recordingId = recordingId,
            referenceA4Hz = tracker.referenceA4Hz,
            analysisTimeMillis = System.currentTimeMillis(),
            points = points
        )
    }

    /**
     * 这一帧解码出来有多少个采样点（单声道 = 短整型个数；多声道先折算成帧数）。
     *
     * 先算大小再准备缓冲，是为了让 [fillSamples] 能老老实实往一个已经够大的数组里写，
     * 不在解码循环里制造垃圾。
     */
    private fun sampleCountOf(
        info: MediaCodec.BufferInfo,
        channels: Int
    ): Int {
        val shortCount = info.size / 2
        if (shortCount <= 0) return 0
        return if (channels <= 1) shortCount else shortCount / channels
    }

    /**
     * 把解码器输出缓冲里的 16bit PCM 归一化成 [-1, 1] 写进 [target]。
     *
     * [target] 由调用方按 [sampleCountOf] 准备好，所以这里不会再分配内存。
     */
    private fun fillSamples(
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
        channels: Int,
        target: FloatArray
    ) {
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        val shorts = buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
        val count = shorts.remaining()
        if (count <= 0) return

        // 单声道：直接一个短整型一个采样点。
        if (channels <= 1) {
            for (i in 0 until count) target[i] = shorts.get(i) * SHORT_TO_FLOAT
            return
        }

        // 多声道（正常不会出现，录音就是单声道）：把每帧的声道平均成一个采样点。
        val frames = count / channels
        for (frame in 0 until frames) {
            var sum = 0
            for (channel in 0 until channels) sum += shorts.get(frame * channels + channel).toInt()
            target[frame] = (sum.toFloat() / channels) * SHORT_TO_FLOAT
        }
    }

    /** 进度：已经分析到的时间 / 总时长；总时长未知时按 0 处理（界面显示"正在分析"）。 */
    private fun progressOf(elapsedMillis: Long, durationMillis: Long): Float =
        if (durationMillis <= 0L) 0f else (elapsedMillis.toFloat() / durationMillis).coerceIn(0f, 1f)

    /** 读一个整型参数；没有这个键时返回 null。 */
    private fun MediaFormat.intOrNull(key: String): Int? =
        if (containsKey(key)) getInteger(key) else null
}
