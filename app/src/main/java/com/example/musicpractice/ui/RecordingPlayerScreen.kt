package com.example.musicpractice.ui

import android.content.res.Configuration
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.musicpractice.R
import com.example.musicpractice.pitch.PitchAnalysis
import com.example.musicpractice.pitch.PitchData
import com.example.musicpractice.recording.Recording
import com.example.musicpractice.recording.RecordingFormat
import com.example.musicpractice.ui.theme.MusicPracticeTheme

/** 竖屏时中央播放按钮的直径。 */
private val PortraitPlayButtonSize = 140.dp

/** 横屏（屏幕矮）时中央播放按钮的直径。 */
private val LandscapePlayButtonSize = 110.dp

/**
 * 录音播放页（v5.1 需求一~八、十）。
 *
 * - 中央一个大按钮：点一下播放、再点一下暂停；**没有**上一曲 / 下一曲；
 * - 进度条可拖动，显示 `当前时间 / 总时间`（`01:25 / 05:40`）；
 * - 离开页面保存播放位置，下次接着放；
 * - **音准分析**（v5.1 新增）：
 *   - 还没分析过 → 显示「分析音准」按钮，点一下开始分析（弹出进度界面，可"后台继续"）；
 *   - 正在分析 → 显示进度条和百分比；
 *   - 分析完成后再进来 → 不再显示按钮，直接显示结果：当前音符、音分、有效音符 / NOISE、
 *     基准音高（可点开修改，和调音器共用同一个设置）；
 *   - 播放或拖动进度条时，上面的数值跟着播放位置变。
 *
 * @param onPrepare 进入页面：打开文件、跳到上次听到的位置、读出已有的分析结果。
 * @param onClose 离开页面：保存播放位置并释放播放器。
 * @param onPauseMetronome 开始播放录音之前调用 —— 节拍器正在响就先把它暂停（需求九）。
 * @param onStartAnalysis 点了「分析音准」。
 * @param onShowAnalysisProgress 点了一下"正在分析"那一行（把进度界面调回来）。
 * @param onDismissAnalysisProgress 进度界面点了"后台继续"（分析照旧在后台跑）。
 * @param onDismissAnalysisError 已经提示过分析失败。
 * @param onIncreaseReference / onDecreaseReference 基准音高 +/- 1Hz（432～448，和调音器一致）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingPlayerScreen(
    recording: Recording,
    state: RecordingPlaybackUiState,
    pitch: PitchAnalysisUiState,
    onBack: () -> Unit,
    onPrepare: (String) -> Unit,
    onClose: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onSeekFinished: () -> Unit,
    onPauseMetronome: () -> Unit,
    onStartAnalysis: () -> Unit,
    onShowAnalysisProgress: () -> Unit,
    onDismissAnalysisProgress: () -> Unit,
    onDismissAnalysisError: () -> Unit,
    onIncreaseReference: () -> Unit,
    onDecreaseReference: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 进页面：打开这段录音的准备（读文件、跳到上次的位置）并读出已有的分析结果。
    LaunchedEffect(recording.id) { onPrepare(recording.id) }

    // 离开页面：保存播放位置、释放播放器。放在这里而不是返回按钮里，是为了
    // "系统返回键""左上角返回""页面被换掉"这几条路都能走到同一段收尾逻辑。
    DisposableEffect(recording.id) {
        onDispose { onClose() }
    }

    // 分析失败只提示一次（Toast），提示完就把那个错误状态清掉。
    val context = LocalContext.current
    LaunchedEffect(pitch.errorMessage) {
        val message = pitch.errorMessage ?: return@LaunchedEffect
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        onDismissAnalysisError()
    }

    // 中央大按钮的行为：开始播放之前先把节拍器停下（需求九：暂停节拍器 → 播放录音）。
    val togglePlay: () -> Unit = {
        if (!state.isPlaying) onPauseMetronome()
        onTogglePlay()
    }

    // 基准音高修改面板（和调音器用的是同一个组件、同一份设置）。
    var showReferenceDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = recording.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回最近录音"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        val contentModifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .padding(horizontal = 24.dp, vertical = 16.dp)

        when {
            state.failed -> ReaderMessage(
                title = "这段录音打不开了",
                detail = "文件可能已被删除。回到最近录音看看其它录音吧。"
            )

            isLandscapeScreen() -> Row(
                modifier = contentModifier,
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(32.dp)
            ) {
                // 左边：播放按钮 + 进度条 + 时间。
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    PlayButton(
                        isPlaying = state.isPlaying,
                        enabled = !state.isLoading,
                        size = LandscapePlayButtonSize,
                        onClick = togglePlay
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    PlayerProgress(
                        state = state,
                        onSeek = onSeek,
                        onSeekFinished = onSeekFinished,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                // 右边：音准分析（按钮 / 进度 / 结果）。
                PitchPanel(
                    pitch = pitch,
                    positionMillis = state.positionMillis,
                    onStartAnalysis = onStartAnalysis,
                    onShowProgress = onShowAnalysisProgress,
                    onOpenReference = { showReferenceDialog = true },
                    modifier = Modifier.weight(1f)
                )
            }

            else -> Column(
                modifier = contentModifier,
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                PlayButton(
                    isPlaying = state.isPlaying,
                    enabled = !state.isLoading,
                    size = PortraitPlayButtonSize,
                    onClick = togglePlay
                )
                Spacer(modifier = Modifier.height(36.dp))
                PlayerProgress(
                    state = state,
                    onSeek = onSeek,
                    onSeekFinished = onSeekFinished,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(36.dp))
                PitchPanel(
                    pitch = pitch,
                    positionMillis = state.positionMillis,
                    onStartAnalysis = onStartAnalysis,
                    onShowProgress = onShowAnalysisProgress,
                    onOpenReference = { showReferenceDialog = true },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }

    if (showReferenceDialog) {
        A4SettingsDialog(
            a4Hz = pitch.referenceA4Hz,
            onIncrease = onIncreaseReference,
            onDecrease = onDecreaseReference,
            onDismiss = { showReferenceDialog = false },
            title = "基准音高"
        )
    }

    if (pitch.isAnalyzing && pitch.showProgressDialog) {
        AnalysisProgressDialog(
            pitch = pitch,
            onBackground = onDismissAnalysisProgress
        )
    }
}

/**
 * 分析进度界面（需求一）。
 *
 * 一条进度条 + "正在分析：45%"。点"后台继续"就把这一层收起来，
 * **分析不会停** —— 它跑在 ViewModel 的协程里，用户可以直接返回上一页，
 * 分析完成后再进来就是结果了。
 */
@Composable
private fun AnalysisProgressDialog(
    pitch: PitchAnalysisUiState,
    onBackground: () -> Unit
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onBackground,
        title = { Text(text = "正在分析音准") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                LinearProgressIndicator(
                    progress = { pitch.progress },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = pitch.progressLabel,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "分析在后台进行，可以随时返回上一页",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onBackground) {
                Text(text = "后台继续")
            }
        }
    )
}

/**
 * 音准分析那一块：按状态显示「分析音准」按钮 / 分析进度 / 分析结果（需求四、七）。
 *
 * @param positionMillis 当前播放位置，用来取"此刻正在响的那一片"的音符和音分。
 */
@Composable
private fun PitchPanel(
    pitch: PitchAnalysisUiState,
    positionMillis: Long,
    onStartAnalysis: () -> Unit,
    onShowProgress: () -> Unit,
    onOpenReference: () -> Unit,
    modifier: Modifier = Modifier
) {
    when {
        // 正在分析、但用户把进度界面收起来了：缩成一行小卡片，点一下能把进度调回来。
        pitch.isAnalyzing -> AnalyzingCard(pitch = pitch, onClick = onShowProgress, modifier = modifier)

        pitch.isLoadingResult -> LoadingResult(modifier = modifier)

        pitch.hasAnalysis -> PitchResultCard(
            pitch = pitch,
            positionMillis = positionMillis,
            onOpenReference = onOpenReference,
            modifier = modifier
        )

        else -> AnalyzePitchButton(onClick = onStartAnalysis, modifier = modifier)
    }
}

/** 分析中（进度界面已收起）的那一行小卡片。 */
@Composable
private fun AnalyzingCard(
    pitch: PitchAnalysisUiState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Text(
                text = pitch.progressLabel,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { pitch.progress },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** 正在从数据库读已有结果（一段长录音的结果可能有一两万个点）。 */
@Composable
private fun LoadingResult(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = "正在载入分析结果…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 分析结果卡片（需求四、七、八）。
 *
 * 显示当前播放位置对应的：音符、音分、状态（有效音符 / NOISE）、基准音高。
 * 基准音高那一行整块可点 —— 点开就是和调音器一模一样的面板（[-] Hz [+]）。
 */
@Composable
private fun PitchResultCard(
    pitch: PitchAnalysisUiState,
    positionMillis: Long,
    onOpenReference: () -> Unit,
    modifier: Modifier = Modifier
) {
    val point = pitch.pointAt(positionMillis)
    val isValid = point?.isValid == true

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            // 第一行：标题 + 基准音高（可点）。
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "音准分析",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onOpenReference)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = pitch.referenceLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 第二行：音符（大号）+ 音分。
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = pitch.noteTextAt(positionMillis),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.SemiBold,
                    // 等宽字体：音符在音名之间跳动时宽度变化更小，整行不会左右抖。
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = if (isValid) {
                        "${pitch.centsTextAt(positionMillis)} cent"
                    } else {
                        "--"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontFamily = FontFamily.Monospace,
                    color = if (isValid) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 第三行：状态（有效音符 / NOISE）。
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "状态",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = pitch.stateTextAt(positionMillis),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (isValid) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.tertiary
                    }
                )
            }

            // 第四行：这次分析的元信息（什么时候分析的、有效音占比多少）。
            pitch.analysis?.let { analysis ->
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = analysisSummary(analysis),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 结果卡片最下面那行小字：分析时间 + 有效音占比 + 一共多少个时间点。
 *
 * 把"分析时间"显示出来是需求三要求保存的字段之一，也让用户知道这份结果是哪一次跑出来的。
 */
private fun analysisSummary(analysis: PitchAnalysis): String {
    val percent = (analysis.validRatio * 100f).toInt()
    val time = RecordingFormat.describeCreated(
        analysis.analysisTimeMillis,
        System.currentTimeMillis()
    )
    return "分析于 $time · 有效音 $percent% · ${analysis.points.size} 个时间点"
}

/**
 * 播放进度：可拖动的进度条 + `当前时间 / 总时间`（需求八：进度条和时间显示保持不变）。
 *
 * 拖动过程中只更新播放位置，松手（[Slider] 的 onValueChangeFinished）才把位置写进数据库 ——
 * 拖一次手指会产生几十次位置变化，没必要每次都落盘。
 * 进度一变，上面那块的音准数据就跟着变（它按播放位置查）。
 */
@Composable
private fun PlayerProgress(
    state: RecordingPlaybackUiState,
    onSeek: (Long) -> Unit,
    onSeekFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Slider(
            value = state.progressFraction,
            onValueChange = { fraction ->
                onSeek((fraction * state.durationMillis.toFloat()).toLong())
            },
            onValueChangeFinished = onSeekFinished,
            // 还没读出总时长（正在打开文件）时不给拖，免得拖了个没有意义的位置。
            enabled = !state.isLoading && state.durationMillis > 0L,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = state.progressLabel,
            style = MaterialTheme.typography.titleMedium,
            // 等宽字体：时间跳动时整行文字不会左右抖。
            fontFamily = FontFamily.Monospace
        )
    }
}

/**
 * 中央大播放按钮：三角形（播放）/ 两根竖条（暂停）。
 *
 * 圆形底 + 图标，尺寸由调用方给（竖屏大一点、横屏小一点）。
 * 正在打开文件时中间显示一个转圈，用户知道"点了确实有反应"。
 */
@Composable
private fun PlayButton(
    isPlaying: Boolean,
    enabled: Boolean,
    size: Dp,
    onClick: () -> Unit
) {
    val containerColor = if (enabled) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val contentDescription = if (isPlaying) "暂停播放" else "开始播放"

    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(containerColor)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center
    ) {
        if (!enabled) {
            CircularProgressIndicator(modifier = Modifier.size(size * 0.3f), strokeWidth = 3.dp)
        } else if (isPlaying) {
            Icon(
                imageVector = ImageVector.vectorResource(R.drawable.ic_pause),
                contentDescription = null,
                modifier = Modifier.size(size * 0.42f),
                tint = contentColor
            )
        } else {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(size * 0.5f),
                tint = contentColor
            )
        }
    }
}

/**
 * 「分析音准」按钮（需求一）。
 *
 * 只有"这段录音还没分析过"时才显示；分析完成之后按钮就没了，位置上是分析结果
 * （需求四：分析完成后删除按钮、改为显示结果）。
 */
@Composable
private fun AnalyzePitchButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
    ) {
        Icon(
            imageVector = ImageVector.vectorResource(R.drawable.ic_graphic_eq),
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = "分析音准")
    }
}

/** 当前是不是横屏。录音页也有一份同样写法的私有函数，和项目里其它页面的风格一致。 */
@Composable
private fun isLandscapeScreen(): Boolean =
    LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

@Preview(showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun RecordingPlayerScreenPreview() {
    MusicPracticeTheme {
        RecordingPlayerScreen(
            recording = Recording(
                id = "rec-1",
                name = "20260926-001",
                filePath = "/data/user/0/com.example.musicpractice/files/recordings/20260926-001.m4a",
                createdTimeMillis = 1_774_000_000_000L,
                durationMillis = 340_000L
            ),
            state = RecordingPlaybackUiState(
                recordingId = "rec-1",
                isPlaying = true,
                positionMillis = 85_000L,
                durationMillis = 340_000L
            ),
            pitch = PitchAnalysisUiState(
                recordingId = "rec-1",
                hasAnalysis = true,
                referenceA4Hz = 440,
                analysis = PitchAnalysis(
                    id = "pitch-rec-1",
                    recordingId = "rec-1",
                    referenceA4Hz = 440,
                    analysisTimeMillis = 1_774_000_100_000L,
                    points = listOf(
                        PitchData(0L, "A4", -5, true, 440.0),
                        PitchData(46L, "A4", 12, true, 443.0),
                        PitchData(92L, null, 0, false)
                    )
                )
            ),
            onBack = {},
            onPrepare = {},
            onClose = {},
            onTogglePlay = {},
            onSeek = {},
            onSeekFinished = {},
            onPauseMetronome = {},
            onStartAnalysis = {},
            onShowAnalysisProgress = {},
            onDismissAnalysisProgress = {},
            onDismissAnalysisError = {},
            onIncreaseReference = {},
            onDecreaseReference = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun RecordingPlayerScreenAnalyzingPreview() {
    MusicPracticeTheme {
        RecordingPlayerScreen(
            recording = Recording(
                id = "rec-2",
                name = "20260926-002",
                filePath = "/data/user/0/com.example.musicpractice/files/recordings/20260926-002.m4a",
                createdTimeMillis = 1_774_000_000_000L,
                durationMillis = 60_000L
            ),
            state = RecordingPlaybackUiState(recordingId = "rec-2", durationMillis = 60_000L),
            pitch = PitchAnalysisUiState(
                recordingId = "rec-2",
                isAnalyzing = true,
                progress = 0.45f,
                showProgressDialog = true
            ),
            onBack = {},
            onPrepare = {},
            onClose = {},
            onTogglePlay = {},
            onSeek = {},
            onSeekFinished = {},
            onPauseMetronome = {},
            onStartAnalysis = {},
            onShowAnalysisProgress = {},
            onDismissAnalysisProgress = {},
            onDismissAnalysisError = {},
            onIncreaseReference = {},
            onDecreaseReference = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 780, heightDp = 360)
@Composable
private fun RecordingPlayerScreenLandscapePreview() {
    MusicPracticeTheme {
        RecordingPlayerScreen(
            recording = Recording(
                id = "rec-3",
                name = "20260926-003",
                filePath = "/data/user/0/com.example.musicpractice/files/recordings/20260926-003.m4a",
                createdTimeMillis = 1_774_000_000_000L,
                durationMillis = 120_000L
            ),
            state = RecordingPlaybackUiState(
                recordingId = "rec-3",
                positionMillis = 30_000L,
                durationMillis = 120_000L
            ),
            pitch = PitchAnalysisUiState(recordingId = "rec-3"),
            onBack = {},
            onPrepare = {},
            onClose = {},
            onTogglePlay = {},
            onSeek = {},
            onSeekFinished = {},
            onPauseMetronome = {},
            onStartAnalysis = {},
            onShowAnalysisProgress = {},
            onDismissAnalysisProgress = {},
            onDismissAnalysisError = {},
            onIncreaseReference = {},
            onDecreaseReference = {}
        )
    }
}
