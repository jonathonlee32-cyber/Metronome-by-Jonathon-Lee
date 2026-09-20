package com.example.musicpractice.ui

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.musicpractice.metronome.MetronomeEngine
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
 */
class MetronomeViewModel : ViewModel() {

    private val engine = MetronomeEngine()

    /** 当前界面状态。private set 表示只有 ViewModel 自己能改，界面只能读。 */
    var uiState by mutableStateOf(MetronomeUiState())
        private set

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
            // 暂停不清零：把累计到的时间留在界面上。
            uiState = uiState.copy(isPlaying = false, elapsedMillis = accumulatedMillis)
        } else {
            engine.start()
            // 从"现在"开始新的一段，接着之前累计的时间继续走。
            segmentStartedAtMillis = SystemClock.elapsedRealtime()
            // 先让 isPlaying 变成 true，再启动刷新协程，这样它算出来的时间才是"正在计时"。
            uiState = uiState.copy(isPlaying = true)
            startTicker()
        }
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
                delay(DISPLAY_REFRESH_MILLIS)
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    private fun changeBpm(delta: Int) {
        val newBpm = (uiState.bpm + delta)
            .coerceIn(MetronomeEngine.MIN_BPM, MetronomeEngine.MAX_BPM)
        uiState = uiState.copy(bpm = newBpm)
        // 通知引擎。正在播放时，音频线程会用新间隔安排下一拍。
        engine.setBpm(newBpm)
    }

    override fun onCleared() {
        // ViewModel 即将销毁（例如用户退出界面），确保停掉音频、释放 AudioTrack。
        stopTicker()
        engine.stop()
    }

    private companion object {
        /** 每次点 +/- 调整多少 BPM。想一次调 5 就改成 5。 */
        const val BPM_STEP = 1

        /** 时间显示的刷新间隔（毫秒）。界面只显示到秒，200 毫秒刷一次足够顺滑。 */
        const val DISPLAY_REFRESH_MILLIS = 200L
    }
}
