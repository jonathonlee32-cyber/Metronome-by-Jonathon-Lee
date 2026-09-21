package com.example.musicpractice.ui

import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.musicpractice.metronome.MetronomeEngine
import com.example.musicpractice.ui.theme.MusicPracticeTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/** 按住多久算长按。 */
private const val LONG_PRESS_DELAY_MILLIS = 700L

/** 长按期间每隔多久改变 1 BPM。 */
private const val LONG_PRESS_REPEAT_MILLIS = 50L

/** 加减按钮的直径。竖屏用这个默认值，横屏会按可用高度等比缩小。 */
private val StepButtonSize = 72.dp

/** 横屏竖直音量滑块的轨道宽度和滑块半径。 */
private val VolumeTrackWidth = 8.dp
private val VolumeThumbRadius = 12.dp

/**
 * 横屏布局的缩放基准高度。
 *
 * 可用高度比它矮时，横屏里的控件（BPM 数字、加减按钮）会等比缩小，这样从 21:9 到 3:2
 * 的各种横屏比例下，左边那四块内容都能完整放下，不会出现控件重叠、被裁切或超出屏幕。
 */
private val LandscapeReferenceHeight = 340.dp

/** 缩放下限：宁可排得紧凑一点，也不把按钮缩到不好按。 */
private const val MIN_LANDSCAPE_SCALE = 0.78f

/**
 * 节拍器界面。
 *
 * 这是一个"无状态"的 Composable：它自己不保存任何业务数据，只是把传进来的 [state] 画出来，
 * 用户点击时调用传进来的回调。这样界面和逻辑分开，逻辑改起来不影响界面，
 * 也方便用 @Preview 直接预览。
 *
 * 竖屏和横屏是两套各自独立的布局，不是一个布局被横向拉伸：
 * - 竖屏（[PortraitMetronomeContent]）：完全沿用原来的单列居中布局，一行没改；
 * - 横屏（[LandscapeMetronomeContent]）：从左到右分成节拍器核心 / 音量 / 功能入口三栏。
 * 旋转屏幕时系统会重建 Activity，这里读到的方向永远是最新的。
 */
@Composable
fun MetronomeScreen(
    state: MetronomeUiState,
    onDecreaseBpm: () -> Unit,
    onIncreaseBpm: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onResetTimer: () -> Unit,
    onTogglePlay: () -> Unit,
    onOpenTapTempo: () -> Unit,
    onOpenTuner: () -> Unit,
    onOpenRecords: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (isLandscapeScreen()) {
        LandscapeMetronomeContent(
            state = state,
            onDecreaseBpm = onDecreaseBpm,
            onIncreaseBpm = onIncreaseBpm,
            onVolumeChange = onVolumeChange,
            onResetTimer = onResetTimer,
            onTogglePlay = onTogglePlay,
            onOpenTapTempo = onOpenTapTempo,
            onOpenTuner = onOpenTuner,
            onOpenRecords = onOpenRecords,
            modifier = modifier
        )
    } else {
        PortraitMetronomeContent(
            state = state,
            onDecreaseBpm = onDecreaseBpm,
            onIncreaseBpm = onIncreaseBpm,
            onVolumeChange = onVolumeChange,
            onResetTimer = onResetTimer,
            onTogglePlay = onTogglePlay,
            onOpenTapTempo = onOpenTapTempo,
            onOpenTuner = onOpenTuner,
            onOpenRecords = onOpenRecords,
            modifier = modifier
        )
    }
}

/** 当前是不是横屏。竖屏和横屏用的是两套完全不同的布局，判断只做这一处。 */
@Composable
private fun isLandscapeScreen(): Boolean =
    LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

/**
 * 竖屏布局：和横屏改造之前完全一致。
 *
 * 顶部一排是三个入口：左"BPM 测速"、中"调音器"、右"练习记录"。
 * 它们用 Box 叠在原有内容之上，所以中间那套 BPM / 音量 / 计时 / 播放按钮的位置一点没动。
 * 三个按钮等分整行宽度、同一个图标尺寸和文字样式，看起来就是一组入口。
 */
@Composable
private fun PortraitMetronomeContent(
    state: MetronomeUiState,
    onDecreaseBpm: () -> Unit,
    onIncreaseBpm: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onResetTimer: () -> Unit,
    onTogglePlay: () -> Unit,
    onOpenTapTempo: () -> Unit,
    onOpenTuner: () -> Unit,
    onOpenRecords: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        // 整个界面不再统一加左右内边距：因为音量滑块要求从屏幕左端一直延伸到右端。
        // 需要留白的地方（数字、按钮、计时那一行）各自加 padding。
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(text = "BPM", style = MaterialTheme.typography.titleMedium)

            // 当前 BPM。state.bpm 变化时，Compose 会自动重新执行这里，把数字刷新出来。
            Text(text = state.bpm.toString(), style = MaterialTheme.typography.displayLarge)

            Spacer(modifier = Modifier.height(24.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                StepButton(
                    symbol = "-",
                    enabled = state.bpm > MetronomeEngine.MIN_BPM,
                    onStep = onDecreaseBpm
                )
                StepButton(
                    symbol = "+",
                    enabled = state.bpm < MetronomeEngine.MAX_BPM,
                    onStep = onIncreaseBpm
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 音量滑块：0.0 在最左端（静音），1.0 在最右端（最大音量）。
            // Slider 是 Material 3 自带的组件，拖动时 onValueChange 会连续回调。
            Slider(
                value = state.volume,
                onValueChange = onVolumeChange,
                valueRange = 0f..1f,
                // 左右各留 32dp：这样不用把手指拖到屏幕最边缘也能滑到 0（静音）和 1（最大音量）。
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 计时那一行：左边显示时间，右边是清零按钮。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formatElapsed(state.elapsedMillis),
                    style = MaterialTheme.typography.headlineSmall,
                    // 等宽字体：数字宽度一致，秒数跳动时文字不会左右抖动。
                    fontFamily = FontFamily.Monospace
                )
                TextButton(onClick = onResetTimer) {
                    Text(text = "清零")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = onTogglePlay,
                modifier = Modifier.size(width = 160.dp, height = 56.dp)
            ) {
                Text(
                    text = if (state.isPlaying) "暂停" else "开始",
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }

        // 顶部三个入口：左"BPM 测速"、中"调音器"、右"练习记录"。
        // 整行横跨屏幕，每个按钮 weight(1f) 等分宽度，间距一致 —— 一眼看去是一组并列的入口。
        // 加 statusBars 内边距：targetSdk 35 起 Android 15 会强制全屏布局，
        // 不加的话这排按钮会被状态栏压住。
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TopEntryButton(
                text = "BPM 测速",
                // 图标库里没有秒表，用"播放"三角表示"开始测速"，和 App 里其他图标的粗细一致。
                icon = Icons.Filled.PlayArrow,
                onClick = onOpenTapTempo,
                modifier = Modifier.weight(1f)
            )
            TopEntryButton(
                text = "调音器",
                icon = Icons.Filled.Build,
                onClick = onOpenTuner,
                modifier = Modifier.weight(1f)
            )
           TopEntryButton(
               text = "练习记录",
               icon = Icons.Filled.DateRange,
               onClick = onOpenRecords,
               modifier = Modifier.weight(1f)
           )
       }
    }
}

/**
 * 横屏布局：从左到右三栏 —— 左"节拍器核心"、中"音量"、右"功能入口"。
 *
 * 三栏宽度按 weight 分配（3 : 2 : 3），所以从 16:9 到 21:9 的横屏都能按比例摊开，
 * 三栏之间还有固定的 16dp 间距，永远不会互相重叠。
 *
 * 整页没有任何滚动容器（没有 ScrollView，也没有 verticalScroll / horizontalScroll）：
 * 高度方向用 Arrangement.SpaceEvenly / Center 把内容排满整屏，再配合 [scale] 在矮屏上
 * 等比缩小控件，所以内容始终完整显示在屏幕内，不需要上下或左右滑动。
 *
 * 外边距：先扣掉系统栏（状态栏、导航栏、刘海或挖孔），再加 20dp 横 / 12dp 纵的内边距，
 * 所以左右两侧的控件都不会贴到屏幕边缘。
 */
@Composable
private fun LandscapeMetronomeContent(
    state: MetronomeUiState,
    onDecreaseBpm: () -> Unit,
    onIncreaseBpm: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onResetTimer: () -> Unit,
    onTogglePlay: () -> Unit,
    onOpenTapTempo: () -> Unit,
    onOpenTuner: () -> Unit,
    onOpenRecords: () -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            // safeDrawing 一次把状态栏、导航栏、显示挖孔都算进来：横屏时导航栏可能在屏幕
            // 右侧或底部，处理完这些内边距，最右边的功能入口按钮也不会贴边或被系统栏压住。
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        // 只缩不放：基准高度以上的屏幕用原始尺寸，更矮的屏幕按比例缩小。
        val scale = (maxHeight / LandscapeReferenceHeight).coerceIn(MIN_LANDSCAPE_SCALE, 1f)

        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ---------------- 左：节拍器核心 ----------------
            Column(
                modifier = Modifier
                    .weight(3f)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceEvenly
            ) {
                // 1) BPM 面板
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "BPM", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = state.bpm.toString(),
                        style = MaterialTheme.typography.displayMedium.copy(
                            fontSize = 45.sp * scale,
                            lineHeight = 52.sp * scale
                        )
                    )
                }

                // 2) BPM 加减按钮：和竖屏一致 —— 左边"-"减 BPM、右边"+"加 BPM
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    StepButton(
                        symbol = "-",
                        enabled = state.bpm > MetronomeEngine.MIN_BPM,
                        onStep = onDecreaseBpm,
                        size = 64.dp * scale
                    )
                    StepButton(
                        symbol = "+",
                        enabled = state.bpm < MetronomeEngine.MAX_BPM,
                        onStep = onIncreaseBpm,
                        size = 64.dp * scale
                    )
                }

                // 3) 计时模块：左边时间、右边清零，和竖屏一致
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatElapsed(state.elapsedMillis),
                        style = MaterialTheme.typography.headlineSmall,
                        // 等宽字体：数字宽度一致，秒数跳动时文字不会左右抖动。
                        fontFamily = FontFamily.Monospace
                    )
                    TextButton(onClick = onResetTimer) {
                        Text(text = "清零")
                    }
                }

                // 4) 开始 / 暂停：和竖屏共用同一个回调和同一套判断
                Button(
                    onClick = onTogglePlay,
                    modifier = Modifier.size(width = 160.dp, height = 52.dp)
                ) {
                    Text(
                        text = if (state.isPlaying) "暂停" else "开始",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }

            // ---------------- 中：音量（竖直，顶大底小） ----------------
            Column(
                modifier = Modifier
                    .weight(2f)
                    .fillMaxHeight()
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(text = "音量", style = MaterialTheme.typography.titleMedium)

                Spacer(modifier = Modifier.height(12.dp))

                VerticalVolumeSlider(
                    value = state.volume,
                    onValueChange = onVolumeChange,
                    // 占满中间栏剩下的高度（滑块尽量长，好拖），宽度收窄到 64dp：
                    // 左右都留白，离旁边两栏也有足够距离，不会误碰。
                    modifier = Modifier
                        .weight(1f)
                        .width(64.dp)
                )
            }

            // ---------------- 右：三个功能入口 ----------------
            Column(
                modifier = Modifier
                    .weight(3f)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceEvenly
            ) {
                // 顺序固定：BPM 测速 → 调音器 → 练习记录。
                // 按钮本身是横向矩形，三个从上到下排成一列；宽度只占本栏，本栏右侧还有
                // 外层 20dp 内边距和系统栏内边距，所以不会贴到屏幕最右边。
                TopEntryButton(
                    text = "BPM 测速",
                    icon = Icons.Filled.PlayArrow,
                    onClick = onOpenTapTempo,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                )
                TopEntryButton(
                    text = "调音器",
                    icon = Icons.Filled.Build,
                    onClick = onOpenTuner,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                )
                TopEntryButton(
                    text = "练习记录",
                    icon = Icons.Filled.DateRange,
                    onClick = onOpenRecords,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                )
            }
        }
    }
}

/**
 * 横屏专用的竖直音量滑块。
 *
 * 方向：**顶部 = 1.0（最大音量），底部 = 0.0（静音）**，和需求一致。
 * 数值仍然是原来那套 0f..1f，直接交给 [onValueChange]，音量计算方式没有任何改动。
 *
 * 这里用 Canvas 自己画轨道和滑块，而不是把 Material 的横向 Slider 旋转 90°：旋转之后
 * 手势坐标系跟着转，点击位置和拖动方向都容易出问题；自己画则几何关系一目了然。
 * 手势规则和 Material 的 Slider 一样：按到哪里就跳到哪里，按住不放可以继续上下拖。
 * 只有这块区域响应手势，横屏页面本身没有滚动容器，所以不会误触滚动。
 */
@Composable
private fun VerticalVolumeSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val activeColor = MaterialTheme.colorScheme.primary
    val thumbColor = MaterialTheme.colorScheme.primary

    // 手势协程只建立一次（拖动中重建会丢掉手指），用这个始终指向最新的回调。
    val currentOnValueChange by rememberUpdatedState(onValueChange)

    Canvas(
        modifier = modifier
            // 给读屏软件一个名字和进度，不然它只看到一块没有语义的画布。
            .semantics {
                contentDescription = "音量"
                progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..1f)
            }
            .pointerInput(Unit) {
                val thumbRadiusPx = VolumeThumbRadius.toPx()
                awaitEachGesture {
                    // 按下就跳到按下的位置（和 Material Slider 一致），然后跟着手指上下走。
                    val down = awaitFirstDown(requireUnconsumed = false)
                    currentOnValueChange(
                        volumeFromY(down.position.y, size.height.toFloat(), thumbRadiusPx)
                    )
                    drag(down.id) { change ->
                        currentOnValueChange(
                            volumeFromY(change.position.y, size.height.toFloat(), thumbRadiusPx)
                        )
                        change.consume()
                    }
                }
            }
    ) {
        val thumbRadiusPx = VolumeThumbRadius.toPx()
        val trackWidthPx = VolumeTrackWidth.toPx()
        val centerX = size.width / 2f
        // 滑块圆心能到的最低 / 最高位置：上下各留一个半径，滑块不会跑出控件之外。
        val top = thumbRadiusPx
        val bottom = size.height - thumbRadiusPx
        // value = 1（最大）时圆心在最上面，value = 0（静音）时在最下面。
        val thumbY = top + (bottom - top) * (1f - value)

        // 整条轨道
        drawLine(
            color = trackColor,
            start = Offset(centerX, top),
            end = Offset(centerX, bottom),
            strokeWidth = trackWidthPx,
            cap = StrokeCap.Round
        )
        // 已使用的那一段：从滑块往下的部分（底部是静音，所以"用掉"的是靠上的那一段）
        drawLine(
            color = activeColor,
            start = Offset(centerX, thumbY),
            end = Offset(centerX, bottom),
            strokeWidth = trackWidthPx,
            cap = StrokeCap.Round
        )
        // 滑块
        drawCircle(
            color = thumbColor,
            radius = thumbRadiusPx,
            center = Offset(centerX, thumbY)
        )
    }
}

/** 把手指的 y 坐标换算成音量：顶部 = 1.0（最大），底部 = 0.0（静音）。 */
private fun volumeFromY(y: Float, height: Float, thumbRadiusPx: Float): Float {
    val top = thumbRadiusPx
    val bottom = height - thumbRadiusPx
    val usable = bottom - top
    // 控件矮到放不下两个半径时（理论上不会发生）固定返回静音，避免除零。
    if (usable <= 0f) return 0f
    return ((bottom - y) / usable).coerceIn(0f, 1f)
}

/**
 * 顶部的入口按钮：图标 + 文字，用 Material 3 的 FilledTonalButton。
 *
 * 用带底色的按钮而不是纯文字，是为了容易发现（需求要求"容易发现"）。
 * 内边距和文字样式统一写在这里，所以三个入口的尺寸、间距看起来完全一致；
 * 文字用 labelMedium 而不是默认的 labelLarge，是为了让"BPM 测速"这种较长的标签
 * 在 360dp 宽的手机上也能和另外两个按钮一样占满各自那一列而不被截断。
 */
@Composable
private fun TopEntryButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 支持"长按连续调整"的圆形按钮。
 *
 * 行为：
 * - 按下后不到 [LONG_PRESS_DELAY_MILLIS] 就抬手 → 算一次单击，触发一次 [onStep]；
 * - 按住超过 [LONG_PRESS_DELAY_MILLIS] → 判定为长按，立刻触发一次，之后每隔
 *   [LONG_PRESS_REPEAT_MILLIS] 触发一次，直到手指抬起（抬手本身不再额外触发）。
 *
 * 这里没有用 Material 的 Button，因为它自带的 onClick 只在"抬手"时触发一次，
 * 而且长按抬手后还会再算一次点击。自己用 pointerInput 处理手势，逻辑更好控制。
 */
@Composable
private fun StepButton(
    symbol: String,
    enabled: Boolean,
    onStep: () -> Unit,
    // 按钮直径。竖屏用 [StepButtonSize]，横屏会按可用高度传一个等比缩小后的值。
    size: Dp = StepButtonSize,
    modifier: Modifier = Modifier
) {
    // 按住时换个颜色作为视觉反馈（相当于自己实现一个简化版的水波纹）。
    var pressed by remember { mutableStateOf(false) }

    // 用来启动"长按连续触发"的协程。它跟随界面生命周期，界面销毁时自动取消。
    val scope = rememberCoroutineScope()

    val backgroundColor = when {
        !enabled -> MaterialTheme.colorScheme.surfaceVariant
        pressed -> MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
        else -> MaterialTheme.colorScheme.primary
    }
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(backgroundColor)
            // enabled 变化时重新建立手势监听，所以禁用状态下按下不会有任何反应。
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        pressed = true

                        // 这个协程在手指抬起（或手势被取消）时会被取消。
                        var longPressTriggered = false
                        val repeatJob = scope.launch {
                            delay(LONG_PRESS_DELAY_MILLIS)
                            longPressTriggered = true
                            while (true) {
                                onStep()
                                delay(LONG_PRESS_REPEAT_MILLIS)
                            }
                        }

                        // 挂起在这里，等手指抬起；返回 false 表示手势被取消。
                        val releasedNormally = tryAwaitRelease()
                        repeatJob.cancel()
                        pressed = false

                        // 只有"没进入长按的正常短按"才算一次单击。
                        if (releasedNormally && !longPressTriggered) {
                            onStep()
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = symbol,
            style = MaterialTheme.typography.headlineMedium,
            color = contentColor
        )
    }
}

/**
 * 把毫秒格式化成 xx:xx:xx（时:分:秒）。
 * 指定 Locale.US 是为了保证数字一定是 0-9，不会在某些语言环境下变成别的数字字符。
 */
private fun formatElapsed(millis: Long): String {
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = totalSeconds % 3600 / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
}

@Preview(showBackground = true)
@Composable
private fun MetronomeScreenPreview() {
    MusicPracticeTheme {
        MetronomeScreen(
            state = MetronomeUiState(bpm = 120, isPlaying = false, volume = 0.7f),
            onDecreaseBpm = {},
            onIncreaseBpm = {},
            onVolumeChange = {},
            onResetTimer = {},
            onTogglePlay = {},
            onOpenTapTempo = {},
            onOpenTuner = {},
            onOpenRecords = {}
        )
    }
}

/** 横屏预览：一个典型的手机横屏比例，用来看三栏布局和竖直音量滑块。 */
@Preview(showBackground = true, widthDp = 780, heightDp = 360)
@Composable
private fun MetronomeScreenLandscapePreview() {
    MusicPracticeTheme {
        MetronomeScreen(
            state = MetronomeUiState(bpm = 120, isPlaying = true, volume = 0.7f),
            onDecreaseBpm = {},
            onIncreaseBpm = {},
            onVolumeChange = {},
            onResetTimer = {},
            onTogglePlay = {},
            onOpenTapTempo = {},
            onOpenTuner = {},
            onOpenRecords = {}
        )
    }
}
