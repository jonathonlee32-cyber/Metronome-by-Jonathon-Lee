package com.example.musicpractice.ui

import android.app.Application
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.musicpractice.metronome.MetronomeEngine
import com.example.musicpractice.practice.PracticeTimeManager
import com.example.musicpractice.tempo.TapTempoTracker
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 音量滑块最右端时的音量。 */
private const val DEFAULT_VOLUME = 1.0f

/**
 * 界面需要的全部状态。界面只读它，不自己保存状态。
 */
data class MetronomeUiState(
    val bpm: Int = MetronomeEngine.DEFAULT_BPM,
    val isPlaying: Boolean = false,
    val volume: Float = DEFAULT_VOLUME,
    /** 秒表已经走过的毫秒数。 */
    val elapsedMillis: Long = 0L
)

/**
 * 界面状态的管理者（MVVM 里的 ViewModel）。
 *
 * 它的职责只有两件事：
 * 1. 保存状态（bpm、音量、是否正在播放、已计时多久），并在状态变化时通知 Compose 重画；
 * 2. 把界面的操作翻译成对音频引擎的调用。
 *
 * 真正发声和计时的是 [MetronomeEngine]，界面和音频互不认识对方。
 *
 * 状态用 Compose 的 mutableStateOf 保存：它被 @Composable 读取时，值一变就会自动重组。
 * 注意 ViewModel 的生命周期比 Activity 长：屏幕旋转时 Activity 会重建，但 ViewModel 不会，
 * 所以正在播放的节拍不会因为旋转屏幕而中断（这也是不把引擎直接写在 @Composable 里的原因）。
 *
 * 除了节拍本身，它还负责"每日时间记录"：开始播放时开一段练习、停止时结算并存盘。
 * 存储细节交给 [PracticeTimeManager]，这里只做衔接，节拍器的操作逻辑一行没改。
 *
 * 另外它也保管"BPM 测速"页面那几个数字（实时 / 平均 BPM、点了几次）。
 * 测速结果只有一个去处 —— [setBpm]，也就是节拍器自己的那套 BPM 状态，
 * 所以不存在第二套 BPM，两边永远不会打架。
 */
class MetronomeViewModel(application: Application) : AndroidViewModel(application) {

    private val engine = MetronomeEngine()

    /** 练习记录的读写与计时。构造时会自动结算上一次没来得及收尾的练习。 */
    private val practiceTime = PracticeTimeManager(application)

    /** 当前界面状态。private set 表示只有 ViewModel 自己能改，界面只能读。 */
    var uiState by mutableStateOf(MetronomeUiState())
        private set

    /** "每日时间记录"页面要显示的全部内容。 */
    var recordsState by mutableStateOf(PracticeRecordsUiState())
        private set

    /** "BPM 测速"页面要显示的全部内容。 */
    var tapTempoState by mutableStateOf(TapTempoUiState())
        private set

    /**
     * Tap 测速的计算器。它是纯逻辑、不可变，所以这里只保存"当前那一个"。
     * 放在 ViewModel 里而不是 Composable 里：屏幕旋转时它跟着 ViewModel 活下来，
     * 用户刚打的节奏不会因为转屏而白打。
     */
    private var tapTracker = TapTempoTracker()

    /** 用户选的是实时 BPM 还是平均 BPM。默认平均，和需求一致。 */
    private var tapBpmSource = TapBpmSource.AVERAGE

    init {
        // 启动时先算一次：如果上次是被强杀的，管理器已经把那段练习结算好了，
        // 这里顺手把记录页的数据准备好。
        refreshRecords()
    }

    // ---------------- 秒表相关 ----------------
    //
    // 秒表不采用"每秒给变量加 1"的累加方式：那样一旦某次刷新晚了或漏了，误差会一直累积下去。
    // 这里只记住两个数：
    //   accumulatedMillis      —— 之前几段运行时间累计起来的毫秒数
    //   segmentStartedAtMillis —— 当前这一段是什么时候开始的
    // 需要显示时用"现在 - 开始时刻"现算，所以即使界面很久没刷新，算出来的时间依然准确。
    //
    // 用的时钟是 SystemClock.elapsedRealtime()：它是系统开机以来的毫秒数（含休眠时间），
    // 单调递增，不会被用户修改系统时间、时区影响。
    private var accumulatedMillis = 0L
    private var segmentStartedAtMillis = 0L
    private var ticker: Job? = null

    fun increaseBpm() {
        changeBpm(BPM_STEP)
    }

    fun decreaseBpm() {
        changeBpm(-BPM_STEP)
    }

    /**
     * 直接把速度设成某个值。这是"BPM 测速"页面"应用到节拍器"唯一的入口。
     *
     * 用的是和 +/- 按钮同一套状态（uiState.bpm + engine），所以不存在第二套 BPM：
     * 应用之后，节拍器界面上的数字、引擎实际的节奏、之后再按 +/- 的起点，全都是这个值。
     * 正在播放时，引擎会在下一拍就按新速度走。
     */
    fun setBpm(newBpm: Int) {
        val clamped = newBpm.coerceIn(MetronomeEngine.MIN_BPM, MetronomeEngine.MAX_BPM)
        uiState = uiState.copy(bpm = clamped)
        engine.setBpm(clamped)
    }

    // ---------------- BPM 测速页面 ----------------

    /**
     * 用户在测速页面按下了一次 TAP。
     *
     * @param atMillis 这次点击的时刻，默认取单调时钟（不会被用户改系统时间影响）。
     *   参数留了默认值，既方便界面直接调用，也方便以后写测试。
     */
    fun tapTempo(atMillis: Long = SystemClock.elapsedRealtime()) {
        tapTracker = tapTracker.tap(atMillis)
        refreshTapTempoState()
    }

    /** 重置：所有点击记录、实时 BPM、平均 BPM 全部清空，回到刚进页面的样子。 */
    fun resetTapTempo() {
        tapTracker = TapTempoTracker()
        refreshTapTempoState()
    }

    /** 切换"用实时 BPM"还是"用平均 BPM"。 */
    fun selectTapBpmSource(source: TapBpmSource) {
        tapBpmSource = source
        refreshTapTempoState()
    }

    private fun refreshTapTempoState() {
        tapTempoState = buildTapTempoUiState(tapTracker, tapBpmSource)
    }

    fun setVolume(newVolume: Float) {
        uiState = uiState.copy(volume = newVolume)
        engine.setVolume(newVolume)
    }

    /** 开始 / 暂停。切换时同步更新界面状态、音频引擎和秒表。 */
    fun togglePlay() {
        if (uiState.isPlaying) {
            // 暂停：先结算当前这一段，再改 isPlaying。
            // 顺序很重要——elapsedNow() 需要 isPlaying 仍为 true 才能算出这一段的时间，
            // 先改状态会把刚走过的时间丢掉，看起来就像"暂停后自动清零"。
            accumulatedMillis = elapsedNow()
            stopTicker()
            engine.stop()
            // 节拍停下 → 这一段练习结束，落盘存档。用挂钟时间（当前时刻）而不是秒表读数，
            // 因为记录要写进"某天某时"，必须能和日历对上。
            practiceTime.endSession(System.currentTimeMillis())
            refreshRecords()
            // 暂停不清零：把累计到的时间留在界面上。
            uiState = uiState.copy(isPlaying = false, elapsedMillis = accumulatedMillis)
        } else {
            engine.start()
            // 从"现在"开始新的一段，接着之前累计的时间继续走。
            segmentStartedAtMillis = SystemClock.elapsedRealtime()
            // 节拍响起 → 开一段新的练习记录并立刻落盘，
            // 这样即使下一秒进程被杀，也知道这次练习是从什么时候开始的。
            practiceTime.beginSession(System.currentTimeMillis())
            refreshRecords()
            // 先让 isPlaying 变成 true，再启动刷新协程，这样它算出来的时间才是"正在计时"。
            uiState = uiState.copy(isPlaying = true)
            startTicker()
        }
    }

    /**
     * 请记录页重新算一遍数据。进入记录页时、每秒刷新时由界面调用。
     * 顺便把"到这一刻还在练习"写进文件（心跳），强杀时能少损失几秒。
     */
    fun refreshRecords() {
        val now = System.currentTimeMillis()
        practiceTime.touchSession(now)
        recordsState = buildPracticeRecordsUiState(
            sessions = practiceTime.sessions(),
            activeStartMillis = practiceTime.activeSession()?.startMillis,
            nowMillis = now
        )
    }

    /**
     * App 退到后台时立刻记一次心跳。
     *
     * 后台里定时器可能被系统冻结，光靠 5 秒一次的心跳就不够准了；
     * 在"还能确定这一刻还在练习"的时候落一次盘，最坏情况下也只是少记几秒。
     */
    fun recordHeartbeat() {
        practiceTime.touchSession(System.currentTimeMillis(), force = true)
    }

    /** 清零：时间回到 00:00:00。正在计时的话就从此刻重新开始累计。 */
    fun resetTimer() {
        accumulatedMillis = 0L
        segmentStartedAtMillis = SystemClock.elapsedRealtime()
        uiState = uiState.copy(elapsedMillis = 0L)
    }

    /** 当前应该显示的计时毫秒数。 */
    private fun elapsedNow(): Long =
        if (uiState.isPlaying) {
            accumulatedMillis + (SystemClock.elapsedRealtime() - segmentStartedAtMillis)
        } else {
            accumulatedMillis
        }

    /**
     * 定时刷新界面上的时间显示。
     *
     * 这个协程属于 viewModelScope，只要 ViewModel 还活着就会继续跑：
     * 应用退到后台时主线程仍在运行，所以计时会继续走（回到前台时显示的时间依然正确）；
     * 就算刷新被系统暂停过，因为时间是用 elapsedNow() 现算的，也不会少算。
     */
    private fun startTicker() {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (true) {
                uiState = uiState.copy(elapsedMillis = elapsedNow())
                // 每 200 毫秒调用一次，但存储层会按 5 秒节流，真正的写盘次数很少。
                practiceTime.touchSession(System.currentTimeMillis())
                delay(DISPLAY_REFRESH_MILLIS)
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    private fun changeBpm(delta: Int) {
        // 和测速页面走同一条路：加减也好、从测速页面直接赋值也好，都只改这一处状态。
        setBpm(uiState.bpm + delta)
    }

    override fun onCleared() {
        // ViewModel 即将销毁（例如用户退出界面），确保停掉音频、释放 AudioTrack。
        stopTicker()
        engine.stop()
        // 用户按返回键退出时 onCleared 会被调用，但这时音频已经停了，练习实际到此为止，
        // 所以把还没结束的那一段结算掉（按"现在"收尾，而不是等下次打开时按心跳收尾）。
        if (practiceTime.hasActiveSession()) {
            practiceTime.endSession(System.currentTimeMillis())
        }
    }

    private companion object {
        /** 每次点 +/- 调整多少 BPM。想一次调 5 就改成 5。 */
        const val BPM_STEP = 1

        /** 时间显示的刷新间隔（毫秒）。界面只显示到秒，200 毫秒刷一次足够顺滑。 */
        const val DISPLAY_REFRESH_MILLIS = 200L
    }
}
