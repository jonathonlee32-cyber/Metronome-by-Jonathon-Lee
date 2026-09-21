package com.example.musicpractice

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.musicpractice.ui.MetronomeScreen
import com.example.musicpractice.ui.MetronomeViewModel
import com.example.musicpractice.ui.PracticeRecordsScreen
import com.example.musicpractice.ui.SplashScreen
import com.example.musicpractice.ui.theme.MusicPracticeTheme
import kotlinx.coroutines.delay

/** 启动页停留时间：1300 毫秒，之后进入功能页面。 */
private const val SPLASH_DURATION_MILLIS = 1300L

/**
 * 应用唯一的 Activity。
 *
 * 它只做三件事：创建 ViewModel、开启 Compose 界面、把界面和 ViewModel 连起来。
 * 节拍逻辑在 ViewModel 和 MetronomeEngine 里，启动页在 SplashScreen 里，功能界面在 MetronomeScreen 里。
 *
 * 两个页面（节拍器、练习记录）之间的切换用一个布尔值表示。项目只有一个功能页，
 * 为它引入 Navigation 库反而更重；这个值用 rememberSaveable 保存，
 * 所以旋转屏幕、被系统回收后重建，用户还停在原来那一页。
 */
class MainActivity : ComponentActivity() {

    /**
     * by viewModels() 由 Activity 负责创建并保存 ViewModel。
     * Activity 因旋转屏幕等原因重建时，拿到的是同一个 ViewModel 实例，
     * 所以正在播放的节拍不会被打断。
     */
    private val viewModel: MetronomeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MusicPracticeTheme {
                // 是否还在显示启动页。用 rememberSaveable 保存：旋转屏幕重建 Activity 时
                // 它会被恢复，启动页不会被强行重新显示一遍。
                var showSplash by rememberSaveable { mutableStateOf(true) }

                if (showSplash) {
                    // LaunchedEffect 里的代码在进入这一屏时启动一次，1300ms 后把启动页关掉。
                    // 界面销毁时协程会自动取消，不会留下"定时器还在跑"的问题。
                    LaunchedEffect(Unit) {
                        delay(SPLASH_DURATION_MILLIS)
                        showSplash = false
                    }
                        SplashScreen()
                    } else {
                        // 当前是不是停在"练习记录"页。
                        var showRecords by rememberSaveable { mutableStateOf(false) }

                        // 在记录页按系统返回键：回到节拍器，而不是直接退出 App。
                        BackHandler(enabled = showRecords) {
                            showRecords = false
                        }

                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = MaterialTheme.colorScheme.background
                        ) {
                            if (showRecords) {
                                PracticeRecordsScreen(
                                    state = viewModel.recordsState,
                                    isSessionRunning = viewModel.uiState.isPlaying,
                                    onBack = { showRecords = false },
                                    // 记录页自己会在进入时、以及播放中每秒调用一次，
                                    // 所以统计数字和"进行中"那一行是活的。
                                    onRefresh = viewModel::refreshRecords
                                )
                            } else {
                                // state 只读，改动只能通过回调回到 ViewModel —— 数据单向流动。
                                MetronomeScreen(
                                    state = viewModel.uiState,
                                    onDecreaseBpm = viewModel::decreaseBpm,
                                    onIncreaseBpm = viewModel::increaseBpm,
                                    onVolumeChange = viewModel::setVolume,
                                    onResetTimer = viewModel::resetTimer,
                                    onTogglePlay = viewModel::togglePlay,
                                    onOpenRecords = { showRecords = true }
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
