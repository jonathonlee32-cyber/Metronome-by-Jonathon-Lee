package com.example.musicpractice.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.musicpractice.tuner.PitchHistory
import com.example.musicpractice.tuner.TunerNotes
import com.example.musicpractice.tuner.TunerSettingsStore
import com.example.musicpractice.ui.theme.MusicPracticeTheme

/** "已经调准了"的绿色背景。音准历史栏目中间那条绿色也用同一个颜色（见 PitchHistoryPanel）。 */
internal val TunedGreen = Color(0xFF2E7D32)

/** NOISE 指示器在"无法可靠识别"时的蓝色。 */
private val NoiseBlue = Color(0xFF1E88E5)

/** 竖屏中央音符的字号。 */
private val PortraitNoteFontSize = 120.sp

/** 竖屏音分的字号。 */
private val PortraitCentsFontSize = 46.sp

/**
 * 横屏读数区里，音符和音分各占多少高度。
 *
 * 二者相加约 0.63，剩下的留给行距和内边距 —— 所以无论横屏多矮，音分都不会被音符顶出屏幕。
 * 字号由这两个比例乘上实际可用高度算出来，不是写死的，也不随系统字体缩放而变。
 */
private const val NOTE_HEIGHT_RATIO = 0.44f
private const val CENTS_HEIGHT_RATIO = 0.19f

/**
 * 中央读数下面那行提示的固定高度。
 *
 * 有提示、没提示这一块都占同样高（NOISE 前后、识别到音之前之后都一样），
 * 所以提示出现或消失时，上面的音符位置不会变。
 */
private val HintSlotHeight = 40.dp

/** 横屏紧凑头的高度：比 Material 标题栏矮，又不影响 48dp 的点击区。 */
private val HeaderHeight = 48.dp

/**
 * 音准历史栏目的高度：跟着可用高度取一小段，再夹在上下限之间。
 *
 * 按比例取是为了矮屏（横屏尤其矮）不会被栏目吃掉太多读数区；夹上下限是为了高屏上
 * 栏目不会长得太大、抢了中央音符的位置。
 */
private const val PORTRAIT_HISTORY_PANEL_RATIO = 0.22f
private val PortraitHistoryPanelMinHeight = 88.dp
private val PortraitHistoryPanelMaxHeight = 148.dp

private const val LANDSCAPE_HISTORY_PANEL_RATIO = 0.26f
private val LandscapeHistoryPanelMinHeight = 64.dp
private val LandscapeHistoryPanelMaxHeight = 88.dp

/** 横屏三栏的最小高度：再矮也不低于它，保证音符和音分仍然放得下。 */
private val LandscapeColumnsMinHeight = 96.dp

/** 横屏里音准历史栏目和上面读数区之间的间距。 */
private val LandscapeHistoryPanelGap = 10.dp

/**
 * "调音器"页面。
 *
 * 和 [MetronomeScreen]、[TapTempoScreen] 一样是无状态的：它只把 [state] 画出来，
 * 用户的动作通过回调交给 TunerViewModel，音频采集与 DSP 全部在 ViewModel 和
 * TunerAudioEngine 里，界面从不碰 AudioRecord。
 *
 * 页面做了四件事：
 * 1. 进页面就申请麦克风权限，并把"没授权 / 被拒绝 / 永久拒绝 / 麦克风不可用"分别
 *    显示成可操作的提示（最后一个还提供进系统设置的入口）；
 * 2. 页面可见且已授权时开始实时采集，离开页面或切后台立刻停止（见下面的生命周期观察）；
 * 3. 中央显示当前音名与音分，±10 音分内变绿；
 * 4. 下方是音准历史轨迹栏目（[PitchHistoryPanel]），按时间从右向左滚动最近 8 秒的偏差；
 * 5. 右上角齿轮进入 A4 基准设置。
 *
 * 竖屏和横屏是两套各自独立的布局（[PortraitTunerContent] / [LandscapeTunerContent]），
 * 不是一个布局被拉宽。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TunerScreen(
    state: TunerUiState,
    history: PitchHistory,
    onBack: () -> Unit,
    onScreenResumed: (Any) -> Unit,
    onScreenPaused: (Any) -> Unit,
    onPermissionResult: (granted: Boolean, canAskAgain: Boolean) -> Unit,
    onIncreaseA4: () -> Unit,
    onDecreaseA4: () -> Unit,
    onHistoryFrame: (deltaMillis: Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    var showSettings by rememberSaveable { mutableStateOf(false) }

    // 这一份界面实例的令牌：可见 / 不可见的通知都带着它，ViewModel 只认当前那一个，
    // 所以转屏时旧实例的销毁回调不会把新实例刚打开的麦克风关掉。
    val screenToken = remember { Any() }

    // 权限弹窗的结果：granted=是否同意；canAskAgain=还能不能再弹
    // （false 说明用户选了"不再询问"或多次拒绝，只能引导去系统设置）。
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        // "还能不能再问"只在 Android 6.0 及以上才有意义（更低版本装完即授权）。
        val canAskAgain = if (granted) {
            true
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            activity?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) == true
        } else {
            true
        }
        onPermissionResult(granted, canAskAgain)
    }

    // 进入页面就申请一次。已经授权过的用户不会看到弹窗（系统直接返回 true）；
    // 永久拒绝过的用户也不会看到弹窗（系统直接返回 false），随后显示"去系统设置"的提示。
    LaunchedEffect(Unit) {
        if (!context.hasMicPermission()) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // 采集的开关跟着"页面可见性"走，而不是只在进入页面时开一次：
    // ON_RESUME → 开始；ON_PAUSE（切后台、锁屏、被其他页面覆盖）→ 立刻停止；
    // 离开调音器页时这里被销毁，同样停止。所以麦克风不会在后台一直占着。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> onScreenResumed(screenToken)
                Lifecycle.Event.ON_PAUSE -> onScreenPaused(screenToken)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        // 从节拍器页点进来时 Activity 早就已经是 RESUMED，上面那个回调不会再触发，
        // 所以这里补一次"页面已经可见"的通知。
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            onScreenResumed(screenToken)
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            onScreenPaused(screenToken)
        }
    }

    val landscape = isLandscapeScreen()
    val openSettings = { showSettings = true }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            // 横屏不放 Material 标题栏：它高 64dp 还要再叠一条状态栏，横屏屏幕本来就矮，
            // 这几十 dp 一扣，读数区就被压到屏幕偏下、还容易把音分挤掉。
            // 横屏改用内容顶部那条只有 48dp 的紧凑头（见 [LandscapeHeader]）。
            if (!landscape) {
                TopAppBar(
                    title = { Text(text = "调音器") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回节拍器"
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = openSettings) {
                            Icon(
                                imageVector = Icons.Filled.Settings,
                                contentDescription = "调音器设置"
                            )
                        }
                    }
                )
            }
        }
    ) { innerPadding ->
        // 没有权限时横竖屏共用同一套提示；有权限才按方向走各自的布局。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when {
                state.micPermission != MicPermissionState.GRANTED -> MicPermissionContent(
                    state = state.micPermission,
                    onRequestPermission = {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    onOpenSettings = { context.openAppSettings() },
                    // "麦克风不可用"时的重试：再走一次"检查权限并开始采集"。
                    onRetry = { onScreenResumed(screenToken) }
                )

                landscape -> Column(modifier = Modifier.fillMaxSize()) {
                    LandscapeHeader(onBack = onBack, onOpenSettings = openSettings)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        LandscapeTunerContent(
                            state = state,
                            history = history,
                            onHistoryFrame = onHistoryFrame
                        )
                    }
                }

                else -> PortraitTunerContent(
                    state = state,
                    history = history,
                    onHistoryFrame = onHistoryFrame
                )
            }
        }
    }

    if (showSettings) {
        A4SettingsDialog(
            a4Hz = state.a4Hz,
            onIncrease = onIncreaseA4,
            onDecrease = onDecreaseA4,
            onDismiss = { showSettings = false }
        )
    }
}

/** 当前是不是横屏。竖屏和横屏用的是两套完全不同的布局，判断只做这一处。 */
@Composable
private fun isLandscapeScreen(): Boolean =
    LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

/**
 * 横屏顶部的紧凑头：返回 + 标题 + 设置。
 *
 * 它只有 [HeaderHeight] 高，比 Material 的标题栏（64dp，还要再加一条状态栏）省下约 40dp。
 * 横屏屏幕矮，省下的这点高度直接给了读数区 —— 音符因此能落在屏幕视觉中心，音分也有位置。
 * 两个图标按钮仍然是 Material 的标准点击区（≥48dp），和竖屏的标题栏一样好点。
 */
@Composable
private fun LandscapeHeader(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(HeaderHeight),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回节拍器",
                modifier = Modifier.size(24.dp)
            )
        }
        Text(
            text = "调音器",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.weight(1f))
        IconButton(onClick = onOpenSettings) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = "调音器设置",
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

/**
 * 竖屏布局：右上角 NOISE，正中间是音符与音分，下面一条音准历史轨迹，
 * 最底部一行小字说明 A4 基准和识别音域。
 *
 * 中央用 weight(1f) 占满标题栏以下、底部说明以上的全部空间，所以音符落在视觉正中，
 * 屏幕无论多高多矮都不会偏。栏目高度按可用高度取一段并夹在上下限之间，矮屏也不会被它挤掉读数。
 */
@Composable
private fun PortraitTunerContent(
    state: TunerUiState,
    history: PitchHistory,
    onHistoryFrame: (deltaMillis: Long) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val historyPanelHeight = (maxHeight * PORTRAIT_HISTORY_PANEL_RATIO)
            .coerceIn(PortraitHistoryPanelMinHeight, PortraitHistoryPanelMaxHeight)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
                .padding(top = 4.dp, bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                NoiseIndicator(isNoise = state.isNoise)
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    TunerReadout(
                        state = state,
                        modifier = Modifier.fillMaxWidth(0.88f),
                        noteFontSize = PortraitNoteFontSize,
                        centsFontSize = PortraitCentsFontSize
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    // 固定高度的提示位：有提示、没提示都占同样高，
                    // 所以 NOISE 出现或消失时上面的音符一动不动。
                    HintSlot(
                        text = tunerHint(state),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // 音准历史轨迹：横向贯穿页面可用宽度。NOISE 期间整体停住（见 PitchHistoryPanel）。
            PitchHistoryPanel(
                history = history,
                active = state.isHistoryRolling,
                onFrame = onHistoryFrame,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(historyPanelHeight)
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = referenceLine(state),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * 横屏布局：上面从左到右三栏 —— 左"识别音域 + 提示"、中"音符与音分"、右"NOISE + A4 基准"；
 * 下面横向贯穿整个页面的"音准历史轨迹"栏目。
 *
 * 横屏不是把竖屏拉宽：屏幕矮、左右宽，所以把说明信息分到两侧，中间那块读数区独自占满高度、
 * 落在视觉正中。
 *
 * 两个容易出问题的点，这里都专门处理了：
 * 1. **内边距只算一次**。页面外层是 Scaffold，它的 innerPadding 已经把状态栏、导航栏、
 *    挖孔都算进去了；这里如果再叠一层 safeDrawing，顶部会多出一截空白，整块内容就被顶到
 *    屏幕偏下的位置。所以横屏这里只留很少的呼吸空间。
 * 2. **字号是按可用高度算出来的，不是写死的**。音符（约 44% 高度）+ 音分（约 19% 高度）
 *    合起来永远小于读数区高度，不会出现"音符太大把音分挤出屏幕"的情况；
 *    换算用 Dp.toSp()，会把系统字体缩放一起算进去，用户在系统里调大字号也不会顶破布局。
 * 3. **栏目高度也参与计算**。三栏的高度 = 可用高度 - 栏目高度 - 间距，字号再由它算出来，
 *    所以加了栏目之后音符仍然落在剩余区域的正中，不会被栏目压出屏幕。
 *
 * 整页没有滚动容器，所有内容都在一屏内。
 */
@Composable
private fun LandscapeTunerContent(
    state: TunerUiState,
    history: PitchHistory,
    onHistoryFrame: (deltaMillis: Long) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            // 系统栏已经由外层 Scaffold 的 innerPadding 处理过了，这里只加一点呼吸空间。
            .padding(horizontal = 20.dp, vertical = 8.dp)
    ) {
        val density = LocalDensity.current
        // 音准历史栏目：横屏矮，所以高度按比例取并夹得更扁一些。
        val historyPanelHeight = (maxHeight * LANDSCAPE_HISTORY_PANEL_RATIO)
            .coerceIn(LandscapeHistoryPanelMinHeight, LandscapeHistoryPanelMaxHeight)
        // 三栏能拿到的高度 = 可用高度 - 栏目高度 - 栏目与三栏之间的间距。
        val columnsHeight = (maxHeight - historyPanelHeight - LandscapeHistoryPanelGap)
            .coerceAtLeast(LandscapeColumnsMinHeight)
        // 读数区能拿到的高度 = 三栏高度减去中栏上下各 8dp 的留白。
        val readoutHeight = (columnsHeight - 16.dp).coerceAtLeast(96.dp)
        // 按比例给音符和音分分配高度，两者之和只占读数区的一小半，怎么都放得下。
        val noteFontSize = with(density) { (readoutHeight * NOTE_HEIGHT_RATIO).toSp() }
        val centsFontSize = with(density) { (readoutHeight * CENTS_HEIGHT_RATIO).toSp() }

        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(columnsHeight),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceEvenly
                ) {
                    InfoBlock(label = "识别音域", value = TunerNotes.RANGE_LABEL)
                    // 和竖屏一样：提示位高度固定，出现／消失都不会顶动中间的音符。
                    HintSlot(
                        text = tunerHint(state),
                        style = MaterialTheme.typography.labelMedium
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(1.7f)
                        .fillMaxHeight()
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    TunerReadout(
                        state = state,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        noteFontSize = noteFontSize,
                        centsFontSize = centsFontSize
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceEvenly
                ) {
                    NoiseIndicator(isNoise = state.isNoise)
                    InfoBlock(label = "A4 基准", value = "${state.a4Hz} Hz")
                }
            }

            Spacer(modifier = Modifier.height(LandscapeHistoryPanelGap))

            // 音准历史轨迹：横向贯穿整个页面宽度。NOISE 期间整体停住（见 PitchHistoryPanel）。
            PitchHistoryPanel(
                history = history,
                active = state.isHistoryRolling,
                onFrame = onHistoryFrame,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(historyPanelHeight)
            )
        }
    }
}

/**
 * 中央读数区：大号音符 + 稍小的音分。
 *
 * 调准时整块背景变绿（[TunedGreen]），这是"已经调准了"最直观的信号。
 * 噪声期间（[TunerUiState.isReliable] 为 false）音符和音分照旧显示，只是颜色变淡，
 * 表示"这是上一次的结果，现在没有在测"。
 */
@Composable
private fun TunerReadout(
    state: TunerUiState,
    modifier: Modifier = Modifier,
    noteFontSize: TextUnit,
    centsFontSize: TextUnit
) {
    val inTune = state.isInTune
    val containerColor = if (inTune) TunedGreen else MaterialTheme.colorScheme.surfaceContainer
    val contentColor = if (inTune) Color.White else MaterialTheme.colorScheme.onSurface

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(32.dp))
            .background(containerColor)
            .padding(horizontal = 16.dp, vertical = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = state.noteText,
                fontSize = noteFontSize,
                lineHeight = noteFontSize,
                fontWeight = FontWeight.SemiBold,
                color = contentColor.copy(alpha = contentAlpha(state)),
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = state.centsText,
                fontSize = centsFontSize,
                // 和音符一样把行高收紧到字号本身，读数区高度才好算，也更好看。
                lineHeight = centsFontSize,
                // 等宽字体：音分变化时数字宽度一致，文字不会左右抖。
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                color = contentColor.copy(alpha = contentAlpha(state)),
                maxLines = 1
            )
        }
    }
}

/** 有实时识别结果时正常显示，处于 NOISE（显示的是上一次结果）时淡一点。 */
private fun contentAlpha(state: TunerUiState): Float = when {
    state.isReliable -> 1f
    state.hasNote -> 0.55f
    else -> 0.4f
}

/**
 * 中央读数下面那行提示的固定位置。
 *
 * 这块区域**永远**占 [HintSlotHeight]，没有提示时就是一块空白。
 * 这样"请演奏一个音"和"等待稳定的声音，先保留上一次结果"交替出现时，
 * 上面的音符和音分不会跟着上下移动。
 */
@Composable
private fun HintSlot(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(HintSlotHeight),
        contentAlignment = Alignment.Center
    ) {
        if (text.isNotEmpty()) {
            Text(
                text = text,
                style = style,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2
            )
        }
    }
}

/**
 * NOISE 指示器：正常是灰色，无法可靠识别时变蓝。
 *
 * 它只表示"这一帧的声音可不可信"，不改变中央的音符和音分 —— 用户停止演奏时，
 * 界面不会跳成"NO NOTE"或者 0。
 */
@Composable
private fun NoiseIndicator(
    isNoise: Boolean,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(50)
    val color = if (isNoise) NoiseBlue else MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = modifier
            .clip(shape)
            .border(width = 1.dp, color = color.copy(alpha = 0.45f), shape = shape)
            .padding(horizontal = 12.dp, vertical = 5.dp)
    ) {
        Text(
            text = "NOISE",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
            color = color
        )
    }
}

/** 横屏两侧的信息块：一行小标题 + 一行值。 */
@Composable
private fun InfoBlock(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * 没有麦克风权限时占满整页的提示。
 *
 * 三种情况给三种出路：还能再问 → 再弹一次系统权限窗；永久拒绝 → 只能去系统设置；
 * 有权限但麦克风打不开 → 重试一次。
 */
@Composable
private fun MicPermissionContent(
    state: MicPermissionState,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val title = when (state) {
        MicPermissionState.DENIED_PERMANENTLY -> "麦克风权限已被拒绝"
        MicPermissionState.UNAVAILABLE -> "麦克风暂时不可用"
        else -> "需要麦克风权限"
    }
    val description = when (state) {
        MicPermissionState.DENIED ->
            "调音器要用麦克风听音，才能测出你在演奏哪个音。没有权限就没法工作，请允许使用麦克风。"

        MicPermissionState.DENIED_PERMANENTLY ->
            "系统已记住「不再询问」。请到系统设置里打开本应用的「麦克风」权限，再回到这一页。"

        MicPermissionState.UNAVAILABLE ->
            "权限已经有了，但麦克风打不开：可能被其他应用占用，或者设备不支持当前录音参数。" +
                "请关闭占用麦克风的应用后重试。"

        else ->
            "调音器需要麦克风来采集你演奏的声音。声音只在这台手机上实时分析，" +
                "不会上传、不联网、不需要任何账号。"
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Info,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))

        when (state) {
            MicPermissionState.DENIED_PERMANENTLY -> {
                Button(onClick = onOpenSettings) {
                    Text(text = "去系统设置开启")
                }
            }

            MicPermissionState.UNAVAILABLE -> {
                Button(onClick = onRetry) {
                    Text(text = "重试")
                }
            }

            else -> {
                Button(onClick = onRequestPermission) {
                    Text(text = "授予麦克风权限")
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(onClick = onOpenSettings) {
                    Text(text = "打开系统设置")
                }
            }
        }
    }
}

/**
 * A4 基准设置面板。
 *
 * 只提供 [-] 当前 Hz [+] 这一种改法：不允许输入数字，也不会一次跳很多，
 * 范围锁在 432～448Hz（到边界的那个按钮会变灰、点不动）。
 */
@Composable
private fun A4SettingsDialog(
    a4Hz: Int,
    onIncrease: () -> Unit,
    onDecrease: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "调音器设置") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "A4 基准频率",
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    A4StepButton(
                        symbol = "-",
                        enabled = a4Hz > TunerSettingsStore.MIN_A4_HZ,
                        onClick = onDecrease
                    )
                    Text(
                        text = "$a4Hz Hz",
                        style = MaterialTheme.typography.headlineSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        // 固定最小宽度：点 +/- 时数字宽度不变，读数不会左右晃。
                        modifier = Modifier.widthIn(min = 108.dp)
                    )
                    A4StepButton(
                        symbol = "+",
                        enabled = a4Hz < TunerSettingsStore.MAX_A4_HZ,
                        onClick = onIncrease
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "用于十二平均律音高计算",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "完成")
            }
        }
    )
}

/** 设置面板里的圆形 -/+ 按钮。到范围边界时变灰并失效。 */
@Composable
private fun A4StepButton(
    symbol: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor = if (enabled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    Box(
        modifier = modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(containerColor)
    ) {
        TextButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(0.dp)
        ) {
            Text(
                text = symbol,
                style = MaterialTheme.typography.headlineSmall
            )
        }
    }
}

/** 中央读数下面那句提示：告诉用户现在该做什么、或者为什么在 NOISE。 */
private fun tunerHint(state: TunerUiState): String = when {
    !state.hasNote -> "请演奏一个音"
    state.isNoise -> "等待稳定的声音，先保留上一次结果"
    else -> ""
}

/** 底部那行小字：当前的 A4 基准和识别音域。 */
private fun referenceLine(state: TunerUiState): String =
    "A4 基准 ${state.a4Hz} Hz · 识别音域 ${TunerNotes.RANGE_LABEL}"

/** 当前是否已经拿到麦克风权限。 */
private fun Context.hasMicPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

/** 打开本应用的系统设置页（权限被永久拒绝时唯一的入口）。 */
private fun Context.openAppSettings() {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", packageName, null)
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        startActivity(intent)
    } catch (error: Exception) {
        // 个别设备没有这个设置页：忽略即可，页面上仍然有"重试"等出路。
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun TunerScreenPreview() {
    MusicPracticeTheme {
        TunerScreen(
            state = TunerUiState(
                micPermission = MicPermissionState.GRANTED,
                noteName = "A4",
                cents = 5,
                isReliable = true,
                frequencyHz = 441.27
            ),
            // 示例轨迹（C4 → D4 → E4）：预览里也能看到连续曲线和音名标签。
            history = remember { previewPitchHistory() },
            onBack = {},
            onScreenResumed = { _ -> },
            onScreenPaused = { _ -> },
            onPermissionResult = { _, _ -> },
            onIncreaseA4 = {},
            onDecreaseA4 = {},
            onHistoryFrame = {}
        )
    }
}

/** 刚进页面、还没识别到任何音：中央是 "--"，NOISE 是蓝色。 */
@Preview(showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun TunerScreenEmptyPreview() {
    MusicPracticeTheme {
        TunerScreen(
            state = TunerUiState(micPermission = MicPermissionState.GRANTED),
            history = remember { PitchHistory() },
            onBack = {},
            onScreenResumed = { _ -> },
            onScreenPaused = { _ -> },
            onPermissionResult = { _, _ -> },
            onIncreaseA4 = {},
            onDecreaseA4 = {},
            onHistoryFrame = {}
        )
    }
}

/** 横屏预览：三栏布局，中间是读数区。 */
@Preview(showBackground = true, widthDp = 780, heightDp = 360)
@Composable
private fun TunerScreenLandscapePreview() {
    MusicPracticeTheme {
        TunerScreen(
            state = TunerUiState(
                micPermission = MicPermissionState.GRANTED,
                noteName = "A#4",
                cents = -22,
                isReliable = true,
                frequencyHz = 466.3
            ),
            history = remember { previewPitchHistory() },
            onBack = {},
            onScreenResumed = { _ -> },
            onScreenPaused = { _ -> },
            onPermissionResult = { _, _ -> },
            onIncreaseA4 = {},
            onDecreaseA4 = {},
            onHistoryFrame = {}
        )
    }
}

/** 矮横屏预览（640×300）：最紧的一种情况，用来看音分还在不在、内容有没有贴边。 */
@Preview(showBackground = true, widthDp = 640, heightDp = 300)
@Composable
private fun TunerScreenShortLandscapePreview() {
    MusicPracticeTheme {
        TunerScreen(
            state = TunerUiState(
                micPermission = MicPermissionState.GRANTED,
                noteName = "B6",
                cents = 5,
                isReliable = true,
                frequencyHz = 1979.8
            ),
            history = remember { previewPitchHistory() },
            onBack = {},
            onScreenResumed = { _ -> },
            onScreenPaused = { _ -> },
            onPermissionResult = { _, _ -> },
            onIncreaseA4 = {},
            onDecreaseA4 = {},
            onHistoryFrame = {}
        )
    }
}

/** NOISE 预览：音符和音分保留上一次结果，只有 NOISE 变蓝，位置和上面完全一样。 */
@Preview(showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun TunerScreenNoisePreview() {
    MusicPracticeTheme {
        TunerScreen(
            state = TunerUiState(
                micPermission = MicPermissionState.GRANTED,
                noteName = "A4",
                cents = 5,
                isReliable = false,
                frequencyHz = 441.27
            ),
            // NOISE：轨迹整体停住，示例轨迹也就停在原地。
            history = remember { previewPitchHistory() },
            onBack = {},
            onScreenResumed = { _ -> },
            onScreenPaused = { _ -> },
            onPermissionResult = { _, _ -> },
            onIncreaseA4 = {},
            onDecreaseA4 = {},
            onHistoryFrame = {}
        )
    }
}

/** 设置面板的预览：A4 = 440Hz，两个按钮都可点。 */
@Preview(showBackground = true)
@Composable
private fun A4SettingsDialogPreview() {
    MusicPracticeTheme {
        A4SettingsDialog(a4Hz = 440, onIncrease = {}, onDecrease = {}, onDismiss = {})
    }
}
