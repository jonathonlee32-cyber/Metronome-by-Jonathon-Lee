package com.example.musicpractice.ui

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.musicpractice.pitch.PitchAnalysis
import com.example.musicpractice.pitch.RecordingPitchAnalyzer
import com.example.musicpractice.recording.Recording
import com.example.musicpractice.recording.RecordingFiles
import com.example.musicpractice.recording.RecordingNaming
import com.example.musicpractice.recording.RecordingPlayer
import com.example.musicpractice.recording.RecordingRecorder
import com.example.musicpractice.recording.RecordingRepository
import com.example.musicpractice.tuner.TunerSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 录音模块的 ViewModel（需求四~九）。
 *
 * 它管四件事，各管各的、界面不需要知道细节：
 * 1) **录音**：开始 / 结束，文件名自动编号，结束就自动落库（[RecordingRecorder] + [RecordingRepository]）；
 * 2) **最近录音**：全部录音按录音时间倒序（[RecordingRepository]）；
 * 3) **播放**：放 / 暂停 / 拖动进度条 / 记住播放位置（[RecordingPlayer]）；
 * 4) **重命名和删除**：只动 App 自己的记录和录音文件。
 *
 * 和节拍器的关系（需求九）不在这里实现：开始播放录音之前要暂停节拍器，这件事归
 * MainActivity 接线（见 RecordingPlayerScreen 的 onPauseMetronome）。
 * 录音时**不动节拍器**，节拍器该响就继续响 —— 所以录音这条路径上没有任何"暂停节拍器"的调用。
 */
class RecordingViewModel(application: Application) : AndroidViewModel(application) {

    /** 录音库的读写。构造时就把上次存的记录读进内存，之后界面读的都是内存里的快照。 */
    private val repository = RecordingRepository(application)

    /** 真正录声音的那位。 */
    private val recorder = RecordingRecorder(application)

    /** 真正放声音的那位。 */
    private val player = RecordingPlayer()

    /**
     * 音准分析（v5.1，需求一~八）。
     *
     * [RecordingPitchAnalyzer] 内部用的是调音器那套音高算法（见它的类注释），
     * 这里只负责"什么时候分析、进度报到界面上、结果存起来"。
     */
    private val pitchAnalyzer = RecordingPitchAnalyzer()

    /**
     * A4 基准频率的存储。
     *
     * **和调音器是同一个文件**（`filesDir/tuner_settings.json`）：在音准分析页改基准，
     * 打开调音器看到的就是新值（需求五）。反过来也一样 —— 调音器改了基准，
     * 这里每次打开录音时都会重新读一次。
     */
    private val tunerSettings = TunerSettingsStore(application)

    /** 录音页要显示的状态。private set 表示只有 ViewModel 自己能改，界面只能读。 */
    var uiState by mutableStateOf(RecordingUiState())
        private set

    /** 播放页要显示的状态。 */
    var playbackState by mutableStateOf(RecordingPlaybackUiState())
        private set

    /** 播放页上"音准分析"那一块的状态。 */
    var pitchState by mutableStateOf(
        PitchAnalysisUiState(referenceA4Hz = tunerSettings.readA4Hz())
    )
        private set

    /**
     * 录音库的版本号：每改一次库就 +1。
     *
     * 界面在读列表之前会先读它，于是"库变了"就成了 Compose 眼里的一次状态变化 ——
     * 录音结束后回到列表、重命名、删除、播放位置变化之后，页面都会自动刷新。
     */
    var libraryRevision by mutableStateOf(0)
        private set

    /** 正在录的那一段：名字、文件路径、开始时刻（挂钟）。 */
    private var activeName: String? = null
    private var activeFilePath: String? = null
    private var activeStartedAtMillis = 0L

    /** 当前打开着的那段录音（播放用）。 */
    private var activePlaybackId: String? = null

    /** 录音秒表的刷新协程；不录音时为 null。 */
    private var recorderTicker: Job? = null

    /** 播放进度的刷新协程；不在播放时为 null。 */
    private var playbackTicker: Job? = null

    /** 正在跑的分析任务；没有分析时为 null。 */
    private var analysisJob: Job? = null

    /** 改了基准音高之后延迟落盘的任务（连点 +/- 时只写一次）。 */
    private var referencePersistJob: Job? = null

    /** 音频线程 → 主线程的搬运工（分析进度回调在 IO 线程上）。 */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 最新一次分析进度，只保留最新值。 */
    @Volatile
    private var pendingProgress = -1f

    /** 把最新进度合进界面状态（主线程执行）。 */
    private val applyProgress = Runnable {
        val value = pendingProgress
        pendingProgress = -1f
        if (value < 0f || !pitchState.isAnalyzing) return@Runnable
        val next = pitchState.copy(progress = value.coerceIn(0f, 1f))
        if (next.progress != pitchState.progress) pitchState = next
    }

    // ---------------- 数据 ----------------

    /** 全部录音，最新的排在最前面（需求六）。 */
    fun recordings(): List<Recording> = libraryRevision.let { repository.all() }

    /** 按 id 取一段录音。 */
    fun recording(id: String?): Recording? = libraryRevision.let { repository.find(id) }

    // ---------------- 录音 ----------------

    /**
     * 开始 / 结束录音（需求四：同一个大按钮，点一下开始、再点一下结束）。
     *
     * 录音开始时**不会碰节拍器**：节拍器正在响就继续响（需求九）。
     */
    fun toggleRecording() {
        if (uiState.isRecording) stopRecording() else startRecording()
    }

    /** 开始录音：先按规则算好文件名，再在后台线程打开麦克风开录。 */
    private fun startRecording() {
        val application = getApplication<Application>()
        val startedAt = System.currentTimeMillis()
        // 数据库里已经用掉的名字先在主线程取好（内存快照只在主线程改），
        // 目录里已有的文件名是磁盘 IO，放到后台线程里一起去算。
        val takenNames = repository.all().map { it.name }

        viewModelScope.launch {
            val prepared = withContext(Dispatchers.IO) {
                if (RecordingFiles.ensureDir(application) == null) {
                    null
                } else {
                    val name = RecordingNaming.nextName(
                        createdMillis = startedAt,
                        taken = takenNames + RecordingFiles.existingNames(application)
                    )
                    val file = RecordingFiles.file(application, name)
                    if (recorder.start(file)) name to file.absolutePath else null
                }
            }

            if (prepared == null) {
                uiState = uiState.copy(message = "录音启动失败：麦克风可能被别的应用占用")
                return@launch
            }

            activeName = prepared.first
            activeFilePath = prepared.second
            activeStartedAtMillis = startedAt
            uiState = uiState.copy(
                isRecording = true,
                elapsedMillis = 0L,
                lastSavedName = null,
                message = null
            )
            startRecorderTicker()
        }
    }

    /**
     * 结束录音：把数据写完，然后自动保存进数据库（需求五）。
     *
     * 录得太短（MediaRecorder 都来不及写出有效数据）时不建记录，也不留一个空文件。
     */
    private fun stopRecording() {
        if (!uiState.isRecording) return
        stopRecorderTicker()

        val name = activeName
        val path = activeFilePath
        val startedAt = activeStartedAtMillis

        viewModelScope.launch {
            // stop() 会等音频数据全部落盘，所以放在 IO 线程上。
            val duration = withContext(Dispatchers.IO) { recorder.stop() }

            activeName = null
            activeFilePath = null
            activeStartedAtMillis = 0L

            val saved = if (name != null && path != null && duration > 0L && File(path).length() > 0L) {
                repository.add(
                    Recording(
                        id = RecordingRepository.newRecordingId(startedAt),
                        name = name,
                        filePath = path,
                        fileUri = RecordingFiles.uriOf(File(path)),
                        createdTimeMillis = startedAt,
                        durationMillis = duration
                    )
                )
                name
            } else {
                // 录得太短 / 文件是空的：把空文件清掉，只提示一句，不在列表里留一条点开没声音的记录。
                if (path != null) withContext(Dispatchers.IO) { File(path).delete() }
                null
            }

            libraryRevision++
            uiState = uiState.copy(
                isRecording = false,
                elapsedMillis = 0L,
                lastSavedName = saved,
                message = if (saved == null) "这次录音太短，没有保存" else null
            )
        }
    }

    // ---------------- 重命名 / 删除 ----------------

    /**
     * 重命名一段录音（需求六）。只改数据库里的显示名，磁盘上的文件名不动。
     *
     * @return 成功返回新的名字；名字为空或 id 不存在时返回 null。
     */
    fun renameRecording(id: String, newName: String): String? {
        val renamed = repository.rename(id, newName) ?: return null
        libraryRevision++
        return renamed.name
    }

    /**
     * 删除一段录音（需求六）：记录和音频文件一起删。
     *
     * 正在播的这一段被删掉时，先停掉播放再删，免得播放器还抓着一个已经不在的文件。
     *
     * @return 被删掉的录音；id 不存在时返回 null。
     */
    fun deleteRecording(id: String): Recording? {
        if (activePlaybackId == id) closePlayback()
        val removed = repository.delete(id) ?: return null
        libraryRevision++
        // 删掉的正好是音准分析页上这一段：把分析状态一起清掉（数据库里的分析由外键级联删除）。
        if (pitchState.recordingId == id) {
            pitchState = PitchAnalysisUiState(referenceA4Hz = tunerSettings.readA4Hz())
        }

        val application = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            RecordingFiles.delete(application, removed)
        }
        return removed
    }

    // ---------------- 播放 ----------------

    /**
     * 打开一段录音准备播放（进入播放页时调用）。
     *
     * 上次听到哪儿就从哪儿继续（需求七）：位置存在数据库里，由 [RecordingPlayer] 跳过去。
     */
    fun preparePlayback(recordingId: String) {
        if (activePlaybackId == recordingId && player.isPrepared) return
        val recording = repository.find(recordingId) ?: return

        stopPlaybackTicker()
        activePlaybackId = recordingId
        playbackState = RecordingPlaybackUiState(recordingId = recordingId, isLoading = true)
        // 同一段录音可能已经分析过了：进页面就把结果准备好（有就直接显示，不再显示"分析音准"）。
        loadPitchAnalysis(recordingId)

        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val opened = player.prepare(
                    file = File(recording.filePath),
                    startPositionMillis = recording.lastPositionMillis
                ) { onPlaybackCompleted() }
                if (!opened) {
                    null
                } else {
                    player.durationMillis() to player.positionMillis()
                }
            }

            playbackState = if (loaded == null) {
                RecordingPlaybackUiState(recordingId = recordingId, failed = true)
            } else {
                RecordingPlaybackUiState(
                    recordingId = recordingId,
                    isLoading = false,
                    positionMillis = loaded.second,
                    durationMillis = loaded.first
                )
            }
        }
    }

    /** 播放 / 暂停（需求七：中央一个大按钮，点一下放、再点一下停）。 */
    fun togglePlayback() {
        if (playbackState.isLoading || playbackState.failed) return

        if (playbackState.isPlaying) {
            player.pause()
            val position = player.positionMillis()
            stopPlaybackTicker()
            playbackState = playbackState.copy(isPlaying = false, positionMillis = position)
            savePosition(position)
        } else if (player.start()) {
            playbackState = playbackState.copy(isPlaying = true)
            startPlaybackTicker()
        } else {
            playbackState = playbackState.copy(failed = true)
        }
    }

    /** 拖动进度条：界面拖动过程中连续调用，只更新播放器和进度显示。 */
    fun seekTo(positionMillis: Long) {
        if (playbackState.isLoading || playbackState.failed) return
        val duration = playbackState.durationMillis
        val target = if (duration > 0L) positionMillis.coerceIn(0L, duration) else 0L
        player.seekTo(target)
        playbackState = playbackState.copy(positionMillis = target)
    }

    /** 拖动结束：把这一次拖动的结果落进数据库（拖动过程中不写，免得一秒写几十次）。 */
    fun commitPosition() {
        savePosition(playbackState.positionMillis)
    }

    /**
     * 离开播放页：保存"当前录音 + 当前播放时间"，然后释放播放器（需求七）。
     *
     * 由播放页的 DisposableEffect 在页面离开时调用；重复调用是安全的。
     */
    fun closePlayback() {
        val id = activePlaybackId ?: return
        player.pause()
        val position = player.positionMillis()
        player.release()
        stopPlaybackTicker()
        activePlaybackId = null
        repository.savePosition(id, position)
        libraryRevision++
        playbackState = RecordingPlaybackUiState()

        // 离开播放页：把这段录音的分析结果从内存里放掉（下次进来再读）。
        // 正在分析的话保持状态不动 —— 分析要继续跑完，进度界面也不能被清掉。
        repository.releaseAnalysisCache()
        if (!pitchState.isAnalyzing) {
            pitchState = PitchAnalysisUiState(referenceA4Hz = tunerSettings.readA4Hz())
        }
    }

    /** 播到结尾：停在这里，**不自动恢复节拍器**（需求九），下次进来从头再放。 */
    private fun onPlaybackCompleted() {
        viewModelScope.launch {
            if (activePlaybackId == null) return@launch
            stopPlaybackTicker()
            playbackState = playbackState.copy(isPlaying = false, positionMillis = 0L)
            savePosition(0L)
        }
    }

    /** 把播放位置写进数据库（同时刷新界面读的那份快照）。 */
    private fun savePosition(positionMillis: Long) {
        val id = activePlaybackId ?: return
        repository.savePosition(id, positionMillis)
        libraryRevision++
    }

    // ---------------- 计时 ----------------

    // ---------------- 音准分析（v5.1） ----------------

    /**
     * 准备一段录音的音准分析状态（进播放页时调）。
     *
     * 已有结果就直接读出来显示（需求六：下次进来不再显示"分析音准"，直接显示结果）；
     * 没有结果就只标记"没分析过"，等用户点按钮。
     *
     * 读的是数据库里那一份（可能上万个时间点），所以放在 IO 协程里；界面先显示一个加载态。
     */
    private fun loadPitchAnalysis(recordingId: String) {
        // 每次打开都重新读一次 A4 基准：用户在调音器里改过的话，这里要跟着变（需求五）。
        val reference = tunerSettings.readA4Hz()
        val has = repository.hasAnalysis(recordingId)
        pitchState = PitchAnalysisUiState(
            recordingId = recordingId,
            referenceA4Hz = reference,
            hasAnalysis = has,
            isLoadingResult = has
        )
        if (!has) return

        viewModelScope.launch {
            val stored = withContext(Dispatchers.IO) { repository.analysis(recordingId) }
            if (pitchState.recordingId != recordingId) return@launch
            if (stored == null) {
                pitchState = pitchState.copy(isLoadingResult = false, hasAnalysis = false)
                return@launch
            }

            // 分析时用的基准和现在的基准不一样（用户后来改过）：按当前基准重算一遍再显示，
            // 顺手把重算结果写回去，保证"数据库里的结果"和"屏幕上显示的结果"是同一份。
            val displayed = if (stored.referenceA4Hz == reference) {
                stored
            } else {
                val recomputed = stored.withReference(reference)
                withContext(Dispatchers.IO) { repository.saveAnalysis(recomputed) }
                recomputed
            }
            pitchState = pitchState.copy(
                isLoadingResult = false,
                hasAnalysis = true,
                analysis = displayed
            )
        }
    }

    /**
     * 开始分析这段录音的音准（需求一：点「分析音准」后弹出进度界面）。
     *
     * 分析整个跑在协程里（解码 + 检测都在 IO 线程），界面只收进度、不会被卡住；
     * 用户可以点"后台继续"或直接返回上一页，分析会继续跑完并把结果存进数据库。
     */
    fun startPitchAnalysis(recordingId: String) {
        if (pitchState.isAnalyzing && pitchState.recordingId == recordingId) {
            // 正在分析同一段：再点一次就是把进度界面调回来。
            pitchState = pitchState.copy(showProgressDialog = true)
            return
        }
        val recording = repository.find(recordingId) ?: return
        val reference = tunerSettings.readA4Hz()

        analysisJob?.cancel()
        pitchState = PitchAnalysisUiState(
            recordingId = recordingId,
            isAnalyzing = true,
            progress = 0f,
            showProgressDialog = true,
            referenceA4Hz = reference
        )

        analysisJob = viewModelScope.launch {
            val result = pitchAnalyzer.analyze(
                file = File(recording.filePath),
                recordingId = recordingId,
                referenceA4Hz = reference,
                onProgress = ::onAnalysisProgress
            )

            if (result == null) {
                if (pitchState.recordingId == recordingId) {
                    // 分析失败时回到"还没分析"的样子，用户还能再点一次。
                    pitchState = pitchState.copy(
                        isAnalyzing = false,
                        showProgressDialog = false,
                        errorMessage = "分析失败：这段录音读不出来"
                    )
                }
                return@launch
            }

            // 结果落库：分析行 + 全部时间点一次写清（几万个点也只走一次事务）。
            withContext(Dispatchers.IO) { repository.saveAnalysis(result) }
            libraryRevision++

            // 用户可能已经离开这一段去看别的录音了：那就只把结果存好，不动当前界面。
            if (pitchState.recordingId == recordingId) {
                pitchState = pitchState.copy(
                    isAnalyzing = false,
                    showProgressDialog = false,
                    progress = 1f,
                    hasAnalysis = true,
                    analysis = result,
                    errorMessage = null
                )
            }
        }
    }

    /** 把分析进度界面调出来（用户之前点了"后台继续"）。 */
    fun showAnalysisProgress() {
        if (pitchState.isAnalyzing) pitchState = pitchState.copy(showProgressDialog = true)
    }

    /**
     * 收起进度界面，**分析继续在后台跑**（需求一：可以正常返回页面）。
     */
    fun dismissAnalysisProgress() {
        pitchState = pitchState.copy(showProgressDialog = false)
    }

    /** 关掉"分析失败"的提示。 */
    fun dismissAnalysisError() {
        pitchState = pitchState.copy(errorMessage = null)
    }

    /** 分析结果里的基准音高：加 1Hz（到 448Hz 为止，和调音器一致）。 */
    fun increaseAnalysisReference() = changeAnalysisReference(TunerSettingsStore.STEP_HZ)

    /** 分析结果里的基准音高：减 1Hz（到 432Hz 为止，和调音器一致）。 */
    fun decreaseAnalysisReference() = changeAnalysisReference(-TunerSettingsStore.STEP_HZ)

    /**
     * 改基准音高（需求四、五）。
     *
     * 三件事一起做：
     * 1. **写进和调音器共享的那个设置文件** —— 所以打开调音器看到的就是新值；
     * 2. **立刻用新基准重算屏幕上的音名和音分**（纯计算，不用重新解音频）；
     * 3. 延迟把重算后的结果写回数据库（连点 +/- 时只写最后一次，不反复重写几万个点）。
     */
    private fun changeAnalysisReference(deltaHz: Int) {
        val value = (pitchState.referenceA4Hz + deltaHz)
            .coerceIn(TunerSettingsStore.MIN_A4_HZ, TunerSettingsStore.MAX_A4_HZ)
        if (value == pitchState.referenceA4Hz) return

        tunerSettings.writeA4Hz(value)
        val recomputed = pitchState.analysis?.withReference(value)
        pitchState = pitchState.copy(referenceA4Hz = value, analysis = recomputed)

        if (recomputed != null) {
            referencePersistJob?.cancel()
            referencePersistJob = viewModelScope.launch {
                delay(REFERENCE_PERSIST_DELAY_MILLIS)
                withContext(Dispatchers.IO) { repository.saveAnalysis(recomputed) }
                libraryRevision++
            }
        }
    }

    /** 录音中每 200 毫秒刷新一次秒表。 */
    private fun startRecorderTicker() {
        stopRecorderTicker()
        recorderTicker = viewModelScope.launch {
            while (isActive) {
                uiState = uiState.copy(elapsedMillis = recorder.elapsedMillis())
                delay(RECORDER_TICK_MILLIS)
            }
        }
    }

    private fun stopRecorderTicker() {
        recorderTicker?.cancel()
        recorderTicker = null
    }

    /**
     * 播放中每 250 毫秒刷新进度，并且每秒把播放位置落一次盘。
     *
     * 落盘做成"每秒一次"而不是每帧一次：拖动进度条、连续刷新位置都很频繁，
     * 但"上次听到哪儿"这件事精确到秒就完全够用了。
     */
    private fun startPlaybackTicker() {
        stopPlaybackTicker()
        playbackTicker = viewModelScope.launch {
            var sinceLastSave = 0L
            while (isActive) {
                playbackState = playbackState.copy(positionMillis = player.positionMillis())
                sinceLastSave += PLAYBACK_TICK_MILLIS
                if (sinceLastSave >= POSITION_SAVE_INTERVAL_MILLIS) {
                    sinceLastSave = 0L
                    savePosition(playbackState.positionMillis)
                }
                delay(PLAYBACK_TICK_MILLIS)
            }
        }
    }

    private fun stopPlaybackTicker() {
        playbackTicker?.cancel()
        playbackTicker = null
    }

    /**
     * 分析进度回调（**在 IO 线程上被调用**）。
     *
     * 只把最新进度交给主线程，和调音器的音频回调是同一套做法：这里不做任何界面工作，
     * 界面来不及画就直接被新值覆盖，不会排队堆积。
     */
    private fun onAnalysisProgress(fraction: Float) {
        pendingProgress = fraction
        mainHandler.removeCallbacks(applyProgress)
        mainHandler.post(applyProgress)
    }

    override fun onCleared() {
        super.onCleared()
        stopRecorderTicker()
        stopPlaybackTicker()
        analysisJob?.cancel()
        referencePersistJob?.cancel()
        mainHandler.removeCallbacks(applyProgress)
        // 退出时兜底：别让麦克风一直占着、也别让播放器抓着文件句柄。
        recorder.releaseQuietly()
        player.release()
    }

    private companion object {
        /** 录音秒表的刷新间隔（毫秒）。 */
        const val RECORDER_TICK_MILLIS = 200L

        /** 播放进度的刷新间隔（毫秒）。 */
        const val PLAYBACK_TICK_MILLIS = 250L

        /** 播放中每隔多久把"上次听到哪儿"落一次盘（毫秒）。 */
        const val POSITION_SAVE_INTERVAL_MILLIS = 1_000L

        /** 改了基准音高之后隔多久把重算结果写回数据库（毫秒）：连点 +/- 时只写一次。 */
        const val REFERENCE_PERSIST_DELAY_MILLIS = 400L
    }
}
