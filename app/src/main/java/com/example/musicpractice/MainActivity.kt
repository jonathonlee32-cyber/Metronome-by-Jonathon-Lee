package com.example.musicpractice

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import com.example.musicpractice.ui.ApplySystemBarAppearance
import com.example.musicpractice.ui.MetronomeScreen
import com.example.musicpractice.ui.MetronomeViewModel
import com.example.musicpractice.ui.PracticeRecordsScreen
import com.example.musicpractice.ui.SplashScreen
import com.example.musicpractice.ui.TapTempoScreen
import com.example.musicpractice.ui.TunerScreen
import com.example.musicpractice.ui.TunerViewModel
import com.example.musicpractice.ui.theme.MusicPracticeTheme
import kotlinx.coroutines.delay

/** 启动页停留时间：1300 毫秒，之后进入功能页面。 */
private const val SPLASH_DURATION_MILLIS = 1300L

/**
 * 当前显示哪一页。
 *
 * 这个 App 只有四个页面，用一个枚举比引入 Navigation 库轻得多。它是可序列化的，
 * 所以能直接交给 rememberSaveable 保存 —— 转屏、被系统回收后重建，用户还停在原来那一页。
 */
private enum class Screen {
    /** 节拍器主页。 */
    METRONOME,
    /** BPM 测速（Tap BPM）。 */
    TAP_TEMPO,
    /** 调音器：实时采集麦克风并检测基频。 */
    TUNER,
    /** 练习记录。 */
    RECORDS
}

/**
 * 应用唯一的 Activity。
 *
 * 它只做三件事：创建 ViewModel、开启 Compose 界面、把界面和 ViewModel 连起来。
 * 节拍逻辑在 ViewModel 和 MetronomeEngine 里，启动页在 SplashScreen 里，功能界面在 MetronomeScreen 里。
 *
 * 四个页面（节拍器、BPM 测速、调音器、练习记录）之间的切换用一个 [Screen] 表示。
 * 项目页面很少，为它引入 Navigation 库反而更重；这个值用 rememberSaveable 保存，
 * 所以旋转屏幕、被系统回收后重建，用户还停在原来那一页。
 */
class MainActivity : ComponentActivity() {

    /**
     * by viewModels() 由 Activity 负责创建并保存 ViewModel。
     * Activity 因旋转屏幕等原因重建时，拿到的是同一个 ViewModel 实例，
     * 所以正在播放的节拍不会被打断。
     */
    private val viewModel: MetronomeViewModel by viewModels()

    /**
     * 调音器自己的 ViewModel。
     *
     * 它管着麦克风采集与基频检测，所以单独一个实例：节拍器的状态和调音器的状态互不影响，
     * 离开调音器页时只需要停掉这一份资源。
     */
    private val tunerViewModel: TunerViewModel by viewModels()

    // 这里刻意在启动页期间锁竖屏（需求要求开屏页面永远是竖屏），启动页一结束就恢复
    // SCREEN_ORIENTATION_UNSPECIFIED，所以主页面和 BPM 测速页都能正常横屏。
    // Lint 的 SourceLockedOrientationActivity 是针对"整个 Activity 被锁死方向"的提醒，
    // 本应用并没有锁死，所以这一条在这里显式忽略。
    @SuppressLint("SourceLockedOrientationActivity")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 启动页始终竖屏：Activity 第一次创建时一定还在启动页，这里先把屏幕锁成竖屏。
        // 因为是在 onCreate 里、画出第一帧之前设置的，所以哪怕设备当前是横屏，
        // 启动页也不会先以横屏闪一下再转过来。
        // 启动页结束后就把方向还给系统（见下面的 LaunchedEffect），横屏用户会立刻转回横屏布局。
        if (savedInstanceState == null) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }

        setContent {
            // 主题明暗在这里算一次：Compose 主题和状态栏图标都要用它，两者必须一致，
            // 否则又会出现"浅色界面配白色状态栏图标"这种看不清的情况。
            val darkTheme = isSystemInDarkTheme()
            MusicPracticeTheme(darkTheme = darkTheme) {
                // 是否还在显示启动页。用 rememberSaveable 保存：旋转屏幕重建 Activity 时
                // 它会被恢复，启动页不会被强行重新显示一遍。
                var showSplash by rememberSaveable { mutableStateOf(true) }

                // 状态栏图标颜色跟着画面走：启动页是深蓝底 → 白色图标；
                // 进功能页后跟随主题（浅色主题 → 深色图标，深色主题 → 白色图标）。
                ApplySystemBarAppearance(
                    darkBackground = showSplash || darkTheme,
                    backgroundColor = if (showSplash) {
                        colorResource(R.color.splash_background)
                    } else {
                        MaterialTheme.colorScheme.background
                    }
                )

                // 启动页期间锁竖屏；启动页一结束就恢复"跟随设备方向"。这样两点都能保证：
                // 1) 启动页永远是竖屏 UI；2) 启动页之后按设备当前方向进入对应的主页面布局
                // （横屏进横屏布局，竖屏进原来的竖屏布局）。
                // showSplash 由 rememberSaveable 保存，所以转屏重建后这里仍然知道自己在哪一屏：
                // 如果是在启动页里转屏，重建后又会锁回竖屏；已经进了功能页则不再锁。
                LaunchedEffect(showSplash) {
                    requestedOrientation = if (showSplash) {
                        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    } else {
                        ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    }
                }

                if (showSplash) {
                    // LaunchedEffect 里的代码在进入这一屏时启动一次，1300ms 后把启动页关掉。
                    // 界面销毁时协程会自动取消，不会留下"定时器还在跑"的问题。
                    LaunchedEffect(Unit) {
                        delay(SPLASH_DURATION_MILLIS)
                        showSplash = false
                    }
                    SplashScreen()
                } else {
                    // 当前停在哪一页。默认是节拍器主页。
                    var screen by rememberSaveable { mutableStateOf(Screen.METRONOME) }

                    // 不在主页时按系统返回键：回到节拍器，而不是直接退出 App。
                    BackHandler(enabled = screen != Screen.METRONOME) {
                        screen = Screen.METRONOME
                    }

                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        when (screen) {
                            Screen.METRONOME -> MetronomeScreen(
                                // state 只读，改动只能通过回调回到 ViewModel —— 数据单向流动。
                                state = viewModel.uiState,
                                onDecreaseBpm = viewModel::decreaseBpm,
                                onIncreaseBpm = viewModel::increaseBpm,
                                onVolumeChange = viewModel::setVolume,
                                onResetTimer = viewModel::resetTimer,
                                onTogglePlay = viewModel::togglePlay,
                                onOpenTapTempo = { screen = Screen.TAP_TEMPO },
                                onOpenTuner = { screen = Screen.TUNER },
                                onOpenRecords = { screen = Screen.RECORDS }
                            )

                            Screen.TAP_TEMPO -> TapTempoScreen(
                                state = viewModel.tapTempoState,
                                // 点击时刻在 ViewModel 里取，界面只管"被按了一下"。
                                onTap = { viewModel.tapTempo() },
                                onReset = viewModel::resetTapTempo,
                                onSelectSource = viewModel::selectTapBpmSource,
                                // 应用之后立刻回到节拍器，用户能马上看到 BPM 变成了多少。
                                onApplyToMetronome = { bpm ->
                                    viewModel.setBpm(bpm)
                                    screen = Screen.METRONOME
                                },
                                onBack = { screen = Screen.METRONOME }
                            )

                            Screen.TUNER -> TunerScreen(
                                state = tunerViewModel.uiState,
                                onBack = { screen = Screen.METRONOME },
                                // 页面可见 / 不可见由界面通知 ViewModel：可见就（有权限时）开始采集，
                                // 不可见立刻停止并释放麦克风。
                                onScreenResumed = tunerViewModel::onScreenResumed,
                                onScreenPaused = tunerViewModel::onScreenPaused,
                                onPermissionResult = tunerViewModel::onPermissionResult,
                                onIncreaseA4 = tunerViewModel::increaseA4,
                                onDecreaseA4 = tunerViewModel::decreaseA4
                            )

                            Screen.RECORDS -> PracticeRecordsScreen(
                                state = viewModel.recordsState,
                                isSessionRunning = viewModel.uiState.isPlaying,
                                onBack = { screen = Screen.METRONOME },
                                // 记录页自己会在进入时、以及播放中每秒调用一次，
                                // 所以统计数字和"进行中"那一行是活的。
                                onRefresh = viewModel::refreshRecords
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // App 退到后台。此时主线程随时可能被系统冻结，趁现在把"还在练习"这件事写进文件，
        // 万一进程被回收，也只会少记最后这几秒，而不是整段丢掉。
        viewModel.recordHeartbeat()
    }
}
