package com.example.musicpractice.ui

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.musicpractice.metronome.MetronomeEngine
import com.example.musicpractice.ui.theme.MusicPracticeTheme

/** 还没有有效数值时，结果卡里显示的占位符。 */
private const val NO_VALUE_TEXT = "--"

/** TAP 按钮直径。它是这一页最该被看到的元素，所以给得足够大。 */
private val TapButtonSize = 200.dp

/**
 * "BPM 测速"页面。
 *
 * 和 [MetronomeScreen]、[PracticeRecordsScreen] 一样是无状态的：只把传进来的 [state] 画出来，
 * 用户操作通过回调交回 ViewModel。页面结构（顶部可回退标题栏 + 内容）和"练习记录"页保持一致。
 *
 * 从上到下的顺序是有意的：先给结果（实时 / 平均 BPM 两张卡），再给"用哪一个"的选择，
 * 然后才是最大的 TAP 按钮，最后是"重置 / 应用到节拍器"两个操作。
 *
 * @param onTap 点一下 TAP。注意是"按下"就算一次（见 [TapButton]），不是抬手才算。
 * @param onApplyToMetronome 把当前选中的 BPM 写进现有节拍器，然后退回节拍器页面。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TapTempoScreen(
    state: TapTempoUiState,
    onTap: () -> Unit,
    onReset: () -> Unit,
    onSelectSource: (TapBpmSource) -> Unit,
    onApplyToMetronome: (Int) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(text = "BPM 测速") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回节拍器"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        // 竖屏和横屏是两套各自独立的布局：竖屏沿用原来的单列布局（可滚动），
        // 横屏走专门的三栏布局（整屏放下，不需要任何滚动）。
        if (isLandscapeScreen()) {
            LandscapeTapTempoContent(
                state = state,
                onTap = onTap,
                onReset = onReset,
                onSelectSource = onSelectSource,
                onApplyToMetronome = onApplyToMetronome,
                contentPadding = innerPadding
            )
        } else {
            PortraitTapTempoContent(
                state = state,
                onTap = onTap,
                onReset = onReset,
                onSelectSource = onSelectSource,
                onApplyToMetronome = onApplyToMetronome,
                contentPadding = innerPadding
            )
        }
    }
}

/** 当前是不是横屏。竖屏和横屏用的是两套完全不同的布局，判断只做这一处。 */
@Composable
private fun isLandscapeScreen(): Boolean =
    LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

/**
 * 竖屏布局：和横屏改造之前完全一致。
 *
 * 从上到下的顺序是有意的：先给结果（实时 / 平均 BPM 两张卡），再给"用哪一个"的选择，
 * 然后才是最大的 TAP 按钮，最后是"重置 / 应用到节拍器"两个操作。
 */
@Composable
private fun PortraitTapTempoContent(
    state: TapTempoUiState,
    onTap: () -> Unit,
    onReset: () -> Unit,
    onSelectSource: (TapBpmSource) -> Unit,
    onApplyToMetronome: (Int) -> Unit,
    contentPadding: PaddingValues
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            // 小屏幕上内容可能放不下，允许上下滚动，免得 TAP 按钮被挤扁。
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 两个结果并排显示，并且各自标出"当前选中的是它"，一眼能看出用哪个。
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            BpmResultCard(
                label = "实时 BPM",
                subtitle = "最近一次间隔",
                value = state.liveBpm,
                highlighted = state.source == TapBpmSource.LIVE,
                modifier = Modifier.weight(1f)
            )
            BpmResultCard(
                label = "平均 BPM",
                subtitle = "这一轮的平均",
                value = state.averageBpm,
                highlighted = state.source == TapBpmSource.AVERAGE,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = statusLine(state),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(20.dp))

        // 用哪一个 BPM：Material 3 的分段选择控件，两个选项平铺一行，点一下就切换。
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = state.source == TapBpmSource.LIVE,
                onClick = { onSelectSource(TapBpmSource.LIVE) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
            ) {
                Text(text = "使用实时 BPM", maxLines = 1)
            }
            SegmentedButton(
                selected = state.source == TapBpmSource.AVERAGE,
                onClick = { onSelectSource(TapBpmSource.AVERAGE) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
            ) {
                Text(text = "使用平均 BPM", maxLines = 1)
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        TapButton(onTap = onTap)

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onReset,
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
            ) {
                Text(text = "重置")
            }
            Button(
                onClick = { state.targetBpm?.let(onApplyToMetronome) },
                // 没有有效 BPM 时按钮是灰的，点不动。
                enabled = state.canApply,
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
                contentPadding = PaddingValues(horizontal = 8.dp)
            ) {
                Text(text = "应用到节拍器", maxLines = 1)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = applyHint(state),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))
    }
}

/**
 * 横屏布局：从左到右三栏 —— 左"两张结果卡"、中"来源选择与操作"、右"TAP 按钮"。
 *
 * - 左边：实时 BPM 和平均 BPM 上下排列，各占半栏高度；
 * - 中间：状态提示、用哪一个 BPM 的分段选择、"重置 / 应用到节拍器"，都放得下；
 * - 右边：TAP 按钮占本栏最大可用正方形，方便连续点。
 *
 * 和竖屏不同，这里没有任何滚动容器：高度按 weight 摊开，卡片用 compact 样式（字号和内边距
 * 都小一号），再加上 TAP 按本栏能放下的最大尺寸自适应，所以横屏下所有元素都在屏幕内。
 * 测速算法和 TAP 的点击行为没有改动，只是在横屏下换了排布方式和尺寸。
 */
@Composable
private fun LandscapeTapTempoContent(
    state: TapTempoUiState,
    onTap: () -> Unit,
    onReset: () -> Unit,
    onSelectSource: (TapBpmSource) -> Unit,
    onApplyToMetronome: (Int) -> Unit,
    contentPadding: PaddingValues
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ---------------- 左：实时 / 平均 BPM ----------------
        Column(
            modifier = Modifier
                .weight(1.1f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            BpmResultCard(
                label = "实时 BPM",
                subtitle = "最近一次间隔",
                value = state.liveBpm,
                highlighted = state.source == TapBpmSource.LIVE,
                compact = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
            BpmResultCard(
                label = "平均 BPM",
                subtitle = "这一轮的平均",
                value = state.averageBpm,
                highlighted = state.source == TapBpmSource.AVERAGE,
                compact = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        }

        // ---------------- 中：来源选择 + 重置 / 应用 ----------------
        Column(
            modifier = Modifier
                .weight(1.3f)
                .fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            Text(
                text = statusLine(state),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            // 用哪一个 BPM：和竖屏同一个分段选择控件，只是横屏这一栏比竖屏窄，
            // 所以文字用小一号并限成一行，保证两个选项都完整显示。
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = state.source == TapBpmSource.LIVE,
                    onClick = { onSelectSource(TapBpmSource.LIVE) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                ) {
                    Text(
                        text = "使用实时 BPM",
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1
                    )
                }
                SegmentedButton(
                    selected = state.source == TapBpmSource.AVERAGE,
                    onClick = { onSelectSource(TapBpmSource.AVERAGE) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                ) {
                    Text(
                        text = "使用平均 BPM",
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onReset,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    Text(text = "重置", maxLines = 1)
                }
                Button(
                    onClick = { state.targetBpm?.let(onApplyToMetronome) },
                    // 没有有效 BPM 时按钮是灰的，点不动。
                    enabled = state.canApply,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Text(
                        text = "应用到节拍器",
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1
                    )
                }
            }

            Text(
                text = applyHint(state),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }

        // ---------------- 右：TAP ----------------
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center
        ) {
            // TAP 是这一页最该被看到、最该好按的元素：取本栏能放下的最大正方形，
            // 所以屏幕越宽越大，屏幕矮时自动变小，永远不会超出屏幕或被裁掉。
            val diameter = minOf(maxWidth, maxHeight) * 0.94f
            TapButton(onTap = onTap, size = diameter)
        }
    }
}

/**
 * 一张结果卡：标题 + 大号数字 + 一行说明。
 *
 * [highlighted] 为 true 时用主色调底，表示"现在选中的是它"，和上面的分段选择控件对应。
 * [compact] 为 true 时用更小的字号和内边距（横屏用）：横屏的卡只有竖屏一半左右的高度，
 * 用紧凑样式才能把标题、数字、说明三行完整放进去。
 */
@Composable
private fun BpmResultCard(
    label: String,
    subtitle: String,
    value: Int?,
    highlighted: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val containerColor = if (highlighted) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }
    val contentColor = if (highlighted) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    // 横屏的卡是"固定高度"（按 weight 平分左栏），字号和内边距都收一号，内容才放得下。
    val labelStyle = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium
    val valueStyle = if (compact) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.displaySmall
    val horizontalPadding = if (compact) 8.dp else 12.dp
    val verticalPadding = if (compact) 10.dp else 16.dp
    val valueSpacing = if (compact) 2.dp else 6.dp

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = horizontalPadding, vertical = verticalPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            // 卡片比自己内容高时（横屏按 weight 分高度就是这样），内容垂直居中。
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = label,
                style = labelStyle,
                color = contentColor.copy(alpha = 0.75f)
            )
            Spacer(modifier = Modifier.height(valueSpacing))
            Text(
                text = value?.toString() ?: NO_VALUE_TEXT,
                style = valueStyle,
                // 等宽字体：数字刷新时宽度一致，卡片不会跟着抖。
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
    }
}

/**
 * 大号 TAP 按钮。
 *
 * 这里不用 Material 的 Button：它的 onClick 只在"抬手"时触发一次，而需求要求
 * **按下**就算一次点击（这样手感更接近真实节拍器，也不会因为手指滑动导致点空）。
 * 所以用 pointerInput 自己在按下的瞬间回调，抬手只用来恢复颜色。
 *
 * [size] 是按钮直径：竖屏固定用 [TapButtonSize]，横屏按右侧那一栏能放下的最大尺寸传进来。
 */
@Composable
private fun TapButton(
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = TapButtonSize
) {
    // 按下时颜色深一点，作为最直接的视觉反馈（自己实现的简化版水波纹）。
    var pressed by remember { mutableStateOf(false) }

    val containerColor = if (pressed) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
    } else {
        MaterialTheme.colorScheme.primary
    }
    val contentColor = MaterialTheme.colorScheme.onPrimary

    Box(
        modifier = modifier
            .size(size)
            .shadow(elevation = 8.dp, shape = CircleShape)
            .clip(CircleShape)
            .background(containerColor)
            // 给读屏软件一个名字，不然这个大圆圈对它来说是个无名元素。
            .semantics {
                role = Role.Button
                contentDescription = "TAP，按下即计一次点击"
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        // 按下就算一次，不等抬手。
                        onTap()
                        // 挂起在这里直到抬手（或被取消），然后恢复颜色。
                        tryAwaitRelease()
                        pressed = false
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "TAP",
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
                color = contentColor
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "按下即计一次",
                style = MaterialTheme.typography.labelMedium,
                color = contentColor.copy(alpha = 0.8f)
            )
        }
    }
}

/** TAP 按钮下面那句状态提示：告诉用户现在处于哪一步。 */
private fun statusLine(state: TapTempoUiState): String = when {
    state.tapCount == 0 -> "按下 TAP 开始测速，至少点两次才会出现 BPM"
    state.liveBpm == null && state.averageBpm == null -> "等待下一次点击…"
    state.ignoredTapCount > 0 -> "已记录 ${state.tapCount} 次点击，忽略 ${state.ignoredTapCount} 次过快点击"
    else -> "已记录 ${state.tapCount} 次点击"
}

/** "应用到节拍器"下面那句说明：明确写出会把哪个数字、多少 BPM 送过去。 */
private fun applyHint(state: TapTempoUiState): String {
    val target = state.targetBpm ?: return "还没有有效 BPM，先按 TAP 打几下节奏"
    val applied = appliedMetronomeBpm(target)
    val sourceLabel = if (state.source == TapBpmSource.LIVE) "实时 BPM" else "平均 BPM"
    return if (applied == target) {
        "将把 $sourceLabel $target 应用到节拍器"
    } else {
        "节拍器范围是 ${MetronomeEngine.MIN_BPM}–${MetronomeEngine.MAX_BPM} BPM，$target 将按 $applied 应用"
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun TapTempoScreenPreview() {
    MusicPracticeTheme {
        TapTempoScreen(
            state = TapTempoUiState(
                liveBpm = 126,
                averageBpm = 118,
                tapCount = 9,
                ignoredTapCount = 0,
                source = TapBpmSource.AVERAGE
            ),
            onTap = {},
            onReset = {},
            onSelectSource = {},
            onApplyToMetronome = {},
            onBack = {}
        )
    }
}

/** 横屏预览：一个典型的手机横屏比例，用来看左边两张卡和右边 TAP 的位置。 */
@Preview(showBackground = true, widthDp = 780, heightDp = 360)
@Composable
private fun TapTempoScreenLandscapePreview() {
    MusicPracticeTheme {
        TapTempoScreen(
            state = TapTempoUiState(
                liveBpm = 126,
                averageBpm = 118,
                tapCount = 9,
                ignoredTapCount = 0,
                source = TapBpmSource.AVERAGE
            ),
            onTap = {},
            onReset = {},
            onSelectSource = {},
            onApplyToMetronome = {},
            onBack = {}
        )
    }
}
