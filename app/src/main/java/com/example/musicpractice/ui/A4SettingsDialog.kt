package com.example.musicpractice.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.musicpractice.tuner.TunerSettingsStore
import com.example.musicpractice.ui.theme.MusicPracticeTheme

/**
 * A4 基准频率的设置面板。
 *
 * v5.0 之前它只属于调音器；v5.1 起录音的「音准分析」也要改基准音高，而且需求要求
 * "界面与调音器模块中的基准音高修改界面保持一致"，所以把它挪到这个共用文件里，
 * 两个页面用**同一个组件**：
 *
 * - 只提供 `[-] 当前 Hz [+]` 这一种改法：不允许输入数字，也不会一次跳很多；
 * - 范围锁在 [TunerSettingsStore.MIN_A4_HZ]～[TunerSettingsStore.MAX_A4_HZ]（432～448Hz），
 *   到边界的那个按钮变灰、点不动；
 * - 每次点 +/- 调整 [TunerSettingsStore.STEP_HZ]（1Hz）。
 *
 * 两个入口改的是**同一个设置**（`filesDir/tuner_settings.json`），所以在这儿改完，
 * 打开调音器看到的也是新值（需求五）。
 *
 * @param title 面板标题。调音器用"调音器设置"，音准分析页用"基准音高"。
 */
@Composable
internal fun A4SettingsDialog(
    a4Hz: Int,
    onIncrease: () -> Unit,
    onDecrease: () -> Unit,
    onDismiss: () -> Unit,
    title: String = "调音器设置"
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
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

/** 设置面板的预览：A4 = 440Hz，两个按钮都可点。 */
@Preview(showBackground = true)
@Composable
private fun A4SettingsDialogPreview() {
    MusicPracticeTheme {
        A4SettingsDialog(a4Hz = 440, onIncrease = {}, onDecrease = {}, onDismiss = {})
    }
}
