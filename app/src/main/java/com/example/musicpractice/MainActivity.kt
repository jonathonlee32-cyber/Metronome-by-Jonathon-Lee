package com.example.musicpractice

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import com.example.musicpractice.ui.ApplySystemBarAppearance
import com.example.musicpractice.ui.MetronomeScreen
import com.example.musicpractice.ui.MetronomeViewModel
import com.example.musicpractice.ui.ScoreSortScreen
import com.example.musicpractice.ui.ScoreViewerScreen
import com.example.musicpractice.ui.PracticeRecordsScreen
import com.example.musicpractice.ui.RecentProjectsScreen
import com.example.musicpractice.ui.ScoreReaderActions
import com.example.musicpractice.ui.ScoreReaderScreen
import com.example.musicpractice.ui.ScoreReaderViewModel
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
 * 这个 App 的页面不多，用一个枚举比引入 Navigation 库轻得多。它是可序列化的，
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
    RECORDS,
    /** 乐谱阅读器主页：最近项目 / 从图片导入项目 / 从PDF导入项目。 */
    SCORE_READER,
    /** 最近项目：数据库里存着的全部乐谱项目（按最近打开时间倒序）。 */
    SCORE_RECENT,
    /** 乐谱阅读器的阅读页：PDF 项目和图片项目都用它。 */
    SCORE_VIEWER,
    /** 图片项目的排序页（图片总览 + 图片查看界面）。 */
    SCORE_SORT
}

/**
 * 这一页是不是**强制竖屏**。
 *
 * 只有乐谱阅读器（主页 + 最近项目 + 阅读页 + 排序页）强制竖屏（需求二）：乐谱是竖幅的，
 * 横屏会把整页压得很小，而且阅读页还要"点一下全屏"，方向交给自己控制更稳。
 *
 * 其余页面（节拍器、BPM 测速、调音器、练习记录）仍然跟着设备方向走 ——
 * 从乐谱阅读器返回节拍器时，这个判断自然变回 false，方向就还给系统了，
 * 原有的横竖屏逻辑一行没改。
 */
private val Screen.forcesPortrait: Boolean
    get() = this == Screen.SCORE_READER ||
        this == Screen.SCORE_RECENT ||
        this == Screen.SCORE_VIEWER ||
        this == Screen.SCORE_SORT

/**
 * 应用唯一的 Activity。
 *
 * 它只做三件事：创建 ViewModel、开启 Compose 界面、把界面和 ViewModel 连起来。
 * 节拍逻辑在 ViewModel 和 MetronomeEngine 里，启动页在 SplashScreen 里，功能界面在 MetronomeScreen 里。
 *
 * 八个页面（节拍器、BPM 测速、调音器、练习记录、乐谱阅读器主页、最近项目、阅读页、图片排序页）
 * 之间的切换用一个 [Screen] 表示。页面很少，为它引入 Navigation 库反而更重；这个值用
 * rememberSaveable 保存，所以旋转屏幕、被系统回收后重建，用户还停在原来那一页。
 *
 * 乐谱阅读器（v4.2 起带"最近项目"）的分支比别的页面多三件事：
 * - 方向：进乐谱阅读器锁竖屏，回到节拍器就还给系统（见 [Screen.forcesPortrait]）；
 * - 项目记忆：打开 PDF 阅读页看的是哪一个项目也存成状态，所以"重新打开 App 还停在刚才那份乐谱上"
 *   和"再点一次入口直接回到那份乐谱"都能成立（需求八）；
 * - 阅读进度：当前项目 + 读到第几页都存在项目记录里（本地 Room 数据库），退出再进来接着看。
 *
 * 乐谱项目、图片顺序、阅读进度、创建时间、最近打开时间全部存在 Room 数据库里：
 * 关掉 App、重启手机都还在，而且完全离线（不联网、不上传任何文件）。
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

    /**
     * 乐谱阅读器自己的 ViewModel：管乐谱库（有哪些项目、最近打开的是哪一个）和 PDF 导入。
     *
     * 同样单独一份：导入 PDF 要拷贝文件、写盘，和节拍器的状态、调音器的麦克风都不相干。
     */
    private val scoreReaderViewModel: ScoreReaderViewModel by viewModels()

    // 这里刻意在启动页期间锁竖屏（需求要求开屏页面永远是竖屏），启动页一结束就恢复
    // SCREEN_ORIENTATION_UNSPECIFIED，所以主页面和 BPM 测速页都能正常横屏。
    // Lint 的 SourceLockedOrientationActivity 是针对"整个 Activity 被锁死方向"的提醒，
    // 本应用并没有锁死，所以这一条在这里显式忽略。
    @SuppressLint("SourceLockedOrientationActivity")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 这一次启动是不是"从头开始"：savedInstanceState 为空说明不是转屏、也不是被系统回收后的
        // 重建，而是用户重新打开 App。只有这种情况才需要"恢复上次阅读的乐谱"（需求八 情况1）。
        val isFreshStart = savedInstanceState == null

        // 启动页始终竖屏：Activity 第一次创建时一定还在启动页，这里先把屏幕锁成竖屏。
        // 因为是在 onCreate 里、画出第一帧之前设置的，所以哪怕设备当前是横屏，
        // 启动页也不会先以横屏闪一下再转过来。
        // 启动页结束后就把方向还给系统（见下面的 LaunchedEffect），横屏用户会立刻转回横屏布局。
        if (isFreshStart) {
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

                // 冷启动时要回到的那份乐谱（需求八 情况1）：上次打开过的项目，没有就是 null。
                // remember 只算一次 —— 它读的是内存里的乐谱库，不涉及磁盘。
                // 转屏 / 系统回收后的重建不算冷启动：那时 rememberSaveable 已经把页面恢复好了，
                // 用户当时在节拍器就还是节拍器，不该被拽进乐谱阅读器。
                val restoredProject = remember {
                    if (isFreshStart) scoreReaderViewModel.lastOpenedProject() else null
                }

                // 当前停在哪一页。默认是节拍器主页；冷启动要恢复乐谱时直接从这里起步。
                var screen by rememberSaveable {
                    mutableStateOf(
                        if (restoredProject != null) Screen.SCORE_VIEWER else Screen.METRONOME
                    )
                }

                // 阅读页 / 排序页正在看的是哪个项目（项目 id）。
                // 它和 screen 一起被 rememberSaveable 保存，所以：
                // - 转屏 / 被系统回收后重建，用户还停在原来那份乐谱上；
                // - 返回节拍器后再点一次「乐谱阅读器」，能回到"刚刚打开的那份"（需求八 情况2）。
                var openScoreProjectId by rememberSaveable { mutableStateOf(restoredProject?.id) }

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

                // 屏幕方向：启动页和乐谱阅读器锁竖屏，其余页面交回系统（跟随设备姿态）。
                //
                // 这样两点都能保证：
                // 1) 启动页永远是竖屏 UI；
                // 2) 从乐谱阅读器返回节拍器后，横屏用户立刻回到原来的横竖屏布局逻辑 ——
                //    因为这里重新设成了 SCREEN_ORIENTATION_UNSPECIFIED。
                // showSplash / screen 都由 rememberSaveable 保存，所以转屏重建后这里仍然知道
                // 自己在哪一屏：在启动页里转屏会锁回竖屏，在乐谱阅读器里转屏也还是竖屏。
                LaunchedEffect(showSplash, screen) {
                    requestedOrientation = if (showSplash || screen.forcesPortrait) {
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
                    // 不在主页时按系统返回键：回到节拍器，而不是直接退出 App。
                    // 乐谱阅读器的阅读页也是这样 —— 它的返回**不经过**三个入口那一页（需求六）。
                    // 例外是图片排序页：它是从阅读页点进来的子页面，返回键回到阅读页更像"上一级"；
                    // 图片查看界面又比排序总览更里一层，由 ScoreSortScreen 自己处理成"回总览"。
                    // 全屏阅读时这个处理器也会让位给阅读页自己的那个（它只负责退出全屏）。
                    BackHandler(enabled = screen != Screen.METRONOME) {
                        screen = when (screen) {
                            Screen.SCORE_SORT -> Screen.SCORE_VIEWER
                            // 最近项目是从"三个入口"的主页点进来的，返回就回主页。
                            Screen.SCORE_RECENT -> Screen.SCORE_READER
                            else -> Screen.METRONOME
                        }
                    }

                    // 点「乐谱阅读器」入口。
                    // 需求八 情况2：已经打开过某份乐谱时，直接进入那份乐谱的阅读页；
                    // 只有一份乐谱都还没导入过的时候，才显示"三个入口"的主页。
                    val openScoreReader: () -> Unit = {
                        val last = scoreReaderViewModel.lastOpenedProject()
                        if (last == null) {
                            screen = Screen.SCORE_READER
                        } else {
                            // 顺手刷新"最近打开时间"，这样它永远是真正最近的那一份。
                            scoreReaderViewModel.markOpened(last.id)
                            openScoreProjectId = last.id
                            screen = Screen.SCORE_VIEWER
                        }
                    }

                    // 导入 PDF 的收尾：命名和选文件在乐谱阅读器的界面里完成（见 rememberPdfImportFlow），
                    // 这里只管"导入好了就打开它，失败了就提示"。
                    val importScorePdf: (String, Uri) -> Unit = { name, uri ->
                        scoreReaderViewModel.importPdf(name, uri) { project ->
                            if (project == null) {
                                Toast.makeText(
                                    this,
                                    "导入失败：读不出这个 PDF 文件",
                                    Toast.LENGTH_LONG
                                ).show()
                            } else {
                                openScoreProjectId = project.id
                                screen = Screen.SCORE_VIEWER
                            }
                        }
                    }

                    // 导入图片的收尾：和 PDF 一样，导入好了就直接进阅读页。
                    // 图片项目第一次导入完成时提示一句"可点击菜单栏排序图片"（需求三），
                    // 提示过就在项目里记下来，以后不再弹。
                    val importScoreImages: (String, List<Uri>) -> Unit = { name, uris ->
                        scoreReaderViewModel.importImages(name, uris) { project ->
                            if (project == null) {
                                Toast.makeText(
                                    this,
                                    "导入失败：读不出这些图片",
                                    Toast.LENGTH_LONG
                                ).show()
                            } else {
                                openScoreProjectId = project.id
                                screen = Screen.SCORE_VIEWER
                                if (!project.orderHintShown) {
                                    Toast.makeText(
                                        this,
                                        "可点击菜单栏排序图片",
                                        Toast.LENGTH_LONG
                                    ).show()
                                    scoreReaderViewModel.markOrderHintShown(project.id)
                                }
                            }
                        }
                    }

                    // 排序图片：只对当前打开着的图片项目有效（需求八）。阅读页里点它进排序页，
                    // 排序页的左上角返回回到这个项目的阅读页。
                    val openScoreSort: () -> Unit = {
                        val target = scoreReaderViewModel.lastOpenedImageProject()
                        if (target != null) {
                            openScoreProjectId = target.id
                            screen = Screen.SCORE_SORT
                        }
                    }

                    // 乐谱阅读器各页面共用的操作：左返回、回主页、占位入口、两种导入、排序。
                    // 定义在一处，所有页面的左上角返回和右上角菜单行为完全一致。
                    val scoreReaderActions = ScoreReaderActions(
                        onBack = { screen = Screen.METRONOME },
                        onOpenHome = { screen = Screen.SCORE_READER },
                        // 「最近项目」：进项目列表（数据库里的全部项目，最近打开的排在最前面）。
                        onRecentProjects = { screen = Screen.SCORE_RECENT },
                        onImportImages = importScoreImages,
                        onImportPdf = importScorePdf,
                        onSortImages = openScoreSort
                    )

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
                                onOpenRecords = { screen = Screen.RECORDS },
                                onOpenScoreReader = openScoreReader
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
                                // 音准历史：数据在 ViewModel 里（复用同一份检测结果），
                                // 界面只读它，并按帧回调推进轨迹时间轴。
                                history = tunerViewModel.pitchHistory,
                                onBack = { screen = Screen.METRONOME },
                                // 页面可见 / 不可见由界面通知 ViewModel：可见就（有权限时）开始采集，
                                // 不可见立刻停止并释放麦克风。
                                onScreenResumed = tunerViewModel::onScreenResumed,
                                onScreenPaused = tunerViewModel::onScreenPaused,
                                onPermissionResult = tunerViewModel::onPermissionResult,
                                onIncreaseA4 = tunerViewModel::increaseA4,
                                onDecreaseA4 = tunerViewModel::decreaseA4,
                                onHistoryFrame = tunerViewModel::onHistoryFrame
                            )

                            Screen.RECORDS -> PracticeRecordsScreen(
                                state = viewModel.recordsState,
                                isSessionRunning = viewModel.uiState.isPlaying,
                                onBack = { screen = Screen.METRONOME },
                                // 记录页自己会在进入时、以及播放中每秒调用一次，
                                // 所以统计数字和"进行中"那一行是活的。
                                onRefresh = viewModel::refreshRecords
                            )

                            Screen.SCORE_READER -> ScoreReaderScreen(
                                actions = scoreReaderActions,
                                // 「排序图片」只在当前项目是图片项目时出现（需求八）；
                                // 主页上没有"正在看的项目"，所以这里取"最近打开的图片项目"。
                                sortableProject = scoreReaderViewModel.projectForSorting(),
                                isImporting = scoreReaderViewModel.isImporting,
                                // 主页上「最近项目」那句说明会写"共 N 个项目"。
                                recentProjectCount = scoreReaderViewModel.allProjects().size
                            )

                            Screen.SCORE_RECENT -> RecentProjectsScreen(
                                // 已经按最近打开时间倒序排好（需求三：最新打开的在最上面）。
                                projects = scoreReaderViewModel.allProjects(),
                                actions = scoreReaderActions,
                                isImporting = scoreReaderViewModel.isImporting,
                                // 这一页是从主页点进来的子页面，返回就回主页。
                                onBack = { screen = Screen.SCORE_READER },
                                onOpenProject = { project ->
                                    // 打开项目：刷新最近打开时间（需求三、四），再按类型进对应的阅读页
                                    // （PDF 和图片共用同一个阅读页，页面内部自己按类型取内容）。
                                    scoreReaderViewModel.markOpened(project.id)
                                    openScoreProjectId = project.id
                                    screen = Screen.SCORE_VIEWER
                                },
                                onDeleteProject = { project ->
                                    scoreReaderViewModel.deleteProject(project.id)
                                    // 删掉的正好是"当前打开着的项目"时，把它从状态里清掉，
                                    // 免得之后不小心又进到那份已经不存在的乐谱里。
                                    if (openScoreProjectId == project.id) {
                                        openScoreProjectId = null
                                    }
                                    Toast.makeText(this, "已删除：${project.name}", Toast.LENGTH_SHORT).show()
                                }
                            )

                            Screen.SCORE_VIEWER -> {
                                val project = scoreReaderViewModel.projectForViewer(openScoreProjectId)
                                if (project == null) {
                                    // 项目不见了（乐谱库被清过或数据损坏）：退回乐谱阅读器主页，
                                    // 而不是停在一个什么都显示不出来的页面上。
                                    LaunchedEffect(Unit) { screen = Screen.SCORE_READER }
                                } else {
                                    ScoreViewerScreen(
                                        project = project,
                                        actions = scoreReaderActions,
                                        isImporting = scoreReaderViewModel.isImporting,
                                        // 翻到第几页就立刻记下来，退出再进来接着这一页看（需求一）。
                                        onPageChanged = { pageIndex ->
                                            scoreReaderViewModel.setLastPage(project.id, pageIndex)
                                        }
                                    )
                                }
                            }

                            Screen.SCORE_SORT -> {
                                // 排序页只对图片项目有意义；项目不是图片项目（或已经不在库里）
                                // 就退回阅读页 / 主页，不做无意义的停留。
                                val project = scoreReaderViewModel.projectForViewer(openScoreProjectId)
                                    ?.takeIf { it.isImageProject }
                                if (project == null) {
                                    LaunchedEffect(Unit) { screen = Screen.SCORE_READER }
                                } else {
                                    ScoreSortScreen(
                                        project = project,
                                        isImporting = scoreReaderViewModel.isImporting,
                                        // 排序页是从阅读页进来的，返回回到阅读页（不直接回节拍器）。
                                        onBack = { screen = Screen.SCORE_VIEWER },
                                        // 排序规则（下一个序号是几、阅读顺序怎么排）都在
                                        // ScoreImageOrdering 里，ViewModel 作用在库里最新的那份数据上。
                                        onAssignPage = { position ->
                                            scoreReaderViewModel.assignImagePage(project.id, position)
                                        },
                                        onResetOrder = {
                                            scoreReaderViewModel.resetImageOrder(project.id)
                                        }
                                    )
                                }
                            }
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
