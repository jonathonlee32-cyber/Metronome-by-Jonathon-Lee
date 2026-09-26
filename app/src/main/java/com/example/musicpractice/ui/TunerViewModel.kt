package com.example.musicpractice.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import com.example.musicpractice.tuner.PitchHistory
import com.example.musicpractice.tuner.TunerAudioEngine
import com.example.musicpractice.tuner.TunerReading
import com.example.musicpractice.tuner.TunerSettingsStore

/**
 * 调音器的状态管理者。
 *
 * 职责：
 * 1. 保存界面状态（A4 基准、权限状态、当前音名与音分、NOISE 指示器）；
 * 2. 管理 [TunerAudioEngine] 的生命周期 —— 页面可见且拿到权限才开始采集，
 *    页面离开或切到后台立刻停止，绝不让麦克风在后台空转；
 * 3. 把音频线程送来的检测结果切回主线程更新界面。
 *
 * 放在 ViewModel 里而不是 Composable 里：转屏时 Activity 会重建，而 ViewModel 不会，
 * A4 基准和最后一次识别结果都能延续，麦克风也只是短暂重启而不会泄漏。
 *
 * A4 基准存到 App 私有目录（[TunerSettingsStore]），下次打开还是用户调过的值。
 */
class TunerViewModel(application: Application) : AndroidViewModel(application) {

    private val settings = TunerSettingsStore(application)

    /** 音频回调 → 主线程的搬运工。 */
    private val mainHandler = Handler(Looper.getMainLooper())

    private var engine: TunerAudioEngine? = null

    /**
     * 当前"拥有"调音器页面的那一份界面实例。
     *
     * 每次 TunerScreen 被放进组合（进入页面、转屏重建）都会拿到一个新的令牌，
     * 只有当前令牌的暂停通知才会真的停止采集。这样转屏时即使旧实例的销毁回调
     * 来得比新实例的恢复回调晚，也不会把新实例刚打开的麦克风关掉。
     */
    private var activeToken: Any? = null

    /** 调音器页面当前是否可见（ON_RESUME 与 ON_PAUSE 之间）。 */
    private var screenVisible = false

    var uiState by mutableStateOf(
        TunerUiState(
            a4Hz = settings.readA4Hz(),
            micPermission = MicPermissionState.NEEDS_PERMISSION
        )
    )
        private set

    /**
     * 音准历史：最近 8 秒检测到的音分偏差轨迹（白色曲线和音名标签都从它画出来）。
     *
     * 它复用调音器已有的检测结果 —— 只是把 [TunerReading] 里现成的音名和音分记下来，
     * 没有任何新的音高检测逻辑。界面只读它，写入只有下面两处：
     * 1. [applyPendingReading]：每收到一帧检测结果就记一次；
     * 2. [onHistoryFrame]：调音器页每画一帧，推进一次轨迹时间轴。
     */
    val pitchHistory = PitchHistory()

    /**
     * 音频线程送来的最新一帧。只保留最新的一帧：界面来不及画就直接用新的覆盖，
     * 这样既不会排队堆积，也不会有"几十帧前的旧结果"被画出来。
     */
    @Volatile
    private var pendingReading: TunerReading? = null

    /** 把最新一帧合进界面状态。整个对象只创建一次，反复投递，不产生额外垃圾。 */
    private val applyPendingReading = Runnable {
        val reading = pendingReading ?: return@Runnable
        pendingReading = null
        if (!screenVisible) return@Runnable
        // 历史轨迹和界面状态走的是同一帧数据：有效音高记点、NOISE 什么都不做。
        pitchHistory.onReading(reading)
        val next = uiState.withReading(reading)
        // 值和上一帧完全一样就不写状态，省掉一次无意义的重组。
        if (next != uiState) uiState = next
    }

    /**
     * 调音器页每画一帧调用一次，把轨迹时间轴推进 [deltaMillis] 毫秒。
     *
     * **只会在检测到有效音高（不是 NOISE）时被调用**：所以时间轴只在"真的有声音"时前进，
     * 轨迹按真实时间从右向左滚动，和刷新率无关；NOISE 期间时间轴不动，
     * 白色曲线和音名标签全部原地停住，恢复检测后再从右边缘继续。
     *
     * 这个方法只改一个 Long（加一次、再裁掉滚出窗口的点），没有状态写入、没有重组，
     * 也完全不在音频线程上，所以不会影响检测延迟。
     */
    fun onHistoryFrame(deltaMillis: Long) {
        pitchHistory.advance(deltaMillis)
    }

    // ---------------- 页面生命周期 ----------------

    /**
     * 页面可见（ON_RESUME）：有权限就开始采集，没权限就把提示切到"需要授权"。
     *
     * @param token 页面实例自己的令牌（TunerScreen 用 remember 保存的那个），
     *   [onScreenPaused] 要带同一个令牌回来。
     */
    fun onScreenResumed(token: Any) {
        activeToken = token
        screenVisible = true
        // v5.1：录音的「音准分析」页也能改 A4 基准，而且改的是同一个设置文件。
        // 所以每次调音器页面变可见都重新读一次设置 —— 用户在分析页改成 442，
        // 回来打开调音器看到的就是 442（需求五：两个模块共享同一个基准音高）。
        syncReferenceFromSettings()
        if (hasMicPermission()) {
            startListening()
        } else {
            stopListening()
            uiState = uiState.copy(micPermission = permissionWithoutAccess())
        }
    }

    /** 把界面上显示的 A4 基准同步成设置文件里的值（音准分析页可能刚改过）。 */
    private fun syncReferenceFromSettings() {
        val stored = settings.readA4Hz()
        if (stored == uiState.a4Hz) return
        uiState = uiState.withReferenceA4Hz(stored)
        engine?.setReferenceA4Hz(stored)
    }

    /**
     * 页面离开或 App 切到后台（ON_PAUSE / 页面销毁）：停止采集、释放麦克风。
     * 界面停留在最后一次的识别结果上，不会清空。
     *
     * @param token [onScreenResumed] 传进来的那个令牌。不是当前令牌的暂停通知会被忽略
     *   （例如转屏时旧实例的销毁回调）。
     */
    fun onScreenPaused(token: Any) {
        if (activeToken !== token) return
        activeToken = null
        screenVisible = false
        stopListening()
    }

    /**
     * 没有权限时该显示哪种提示。
     *
     * 保留"已被拒绝 / 永久拒绝"这两个状态：用户去系统设置里改完权限再回来，
     * 页面不会把"去系统设置"的入口收走；真的被撤销权限时（原来 GRANTED）才回到"需要授权"。
     */
    private fun permissionWithoutAccess(): MicPermissionState = when (uiState.micPermission) {
        MicPermissionState.DENIED, MicPermissionState.DENIED_PERMANENTLY -> uiState.micPermission
        else -> MicPermissionState.NEEDS_PERMISSION
    }

    /**
     * 系统权限弹窗的结果。
     *
     * @param granted 用户是否同意。
     * @param canAskAgain 还能不能再弹系统权限窗。false 表示用户选了"不再询问"
     *   （或者拒绝多次被系统记住），这时界面要给"去系统设置"的入口。
     */
    fun onPermissionResult(granted: Boolean, canAskAgain: Boolean) {
        if (granted) {
            uiState = uiState.copy(micPermission = MicPermissionState.GRANTED)
            if (screenVisible) startListening()
        } else {
            stopListening()
            uiState = uiState.copy(
                micPermission = if (canAskAgain) {
                    MicPermissionState.DENIED
                } else {
                    MicPermissionState.DENIED_PERMANENTLY
                }
            )
        }
    }

    // ---------------- A4 基准 ----------------

    fun increaseA4() {
        setA4Hz(uiState.a4Hz + TunerSettingsStore.STEP_HZ)
    }

    fun decreaseA4() {
        setA4Hz(uiState.a4Hz - TunerSettingsStore.STEP_HZ)
    }

    /**
     * 设置 A4 基准。超出 432～448 的值会被夹住，所以点到底之后按钮再加也不会越界
     * （界面同时会把按钮置灰）。
     */
    private fun setA4Hz(hz: Int) {
        val value = hz.coerceIn(TunerSettingsStore.MIN_A4_HZ, TunerSettingsStore.MAX_A4_HZ)
        if (value == uiState.a4Hz) return

        // 立刻用新基准重算当前显示的音名和音分，不用等下一帧音频。
        uiState = uiState.withReferenceA4Hz(value)
        engine?.setReferenceA4Hz(value)
        settings.writeA4Hz(value)
    }

    // ---------------- 音频采集 ----------------

    private fun startListening() {
        if (engine?.isRunning == true) {
            uiState = uiState.copy(micPermission = MicPermissionState.GRANTED)
            return
        }
        if (!hasMicPermission()) {
            uiState = uiState.copy(micPermission = MicPermissionState.NEEDS_PERMISSION)
            return
        }

        val newEngine = TunerAudioEngine(listener = ::onReading)
        newEngine.setReferenceA4Hz(uiState.a4Hz)
        val started = newEngine.start()
        engine = if (started) newEngine else null
        uiState = uiState.copy(
            micPermission = if (started) {
                MicPermissionState.GRANTED
            } else {
                // 有权限却打不开麦克风：不假装在采集，明确告诉用户。
                MicPermissionState.UNAVAILABLE
            }
        )
    }

    private fun stopListening() {
        engine?.stop()
        engine = null
        pendingReading = null
        mainHandler.removeCallbacks(applyPendingReading)
    }

    /** 音频线程回调：只把最新一帧交给主线程，DSP 结果不在这里做任何界面工作。 */
    private fun onReading(reading: TunerReading) {
        pendingReading = reading
        mainHandler.removeCallbacks(applyPendingReading)
        mainHandler.post(applyPendingReading)
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            getApplication(),
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

    override fun onCleared() {
        // 页面被销毁（退出 App、Activity 结束）：确保麦克风被放开、采集线程结束。
        stopListening()
    }
}
