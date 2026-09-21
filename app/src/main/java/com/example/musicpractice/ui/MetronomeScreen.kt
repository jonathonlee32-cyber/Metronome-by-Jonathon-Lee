package com.example.musicpractice.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.musicpractice.metronome.MetronomeEngine
import com.example.musicpractice.ui.theme.MusicPracticeTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/** 按住多久算长按。 */
private const val LONG_PRESS_DELAY_MILLIS = 700L

/** 长按期间每隔多久改变 1 BPM。 */
private const val LONG_PRESS_REPEAT_MILLIS = 50L

/**
 * 节拍器界面。
 *
 * 这是一个"无状态"的 Composable：它自己不保存任何业务数据，只是把传进来的 [state] 画出来，
 * 用户点击时调用传进来的回调。这样界面和逻辑分开，逻辑改起来不影响界面，
 * 也方便用 @Preview 直接预览。
 *
 * 顶部一排是三个入口：左"BPM 测速"、中"调音器"、右"练习记录"。
 * 它们用 Box 叠在原有内容之上，所以中间那套 BPM / 音量 / 计时 / 播放按钮的位置一点没动。
 * 三个按钮等分整行宽度、同一个图标尺寸和文字样式，看起来就是一组入口。
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
            .size(72.dp)
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
