package com.example.musicpractice.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.musicpractice.R
import com.example.musicpractice.ui.theme.MusicPracticeTheme

/** 竖屏时录音大按钮的直径。 */
private val PortraitRecordButtonSize = 200.dp

/** 横屏（屏幕矮）时录音大按钮的直径。 */
private val LandscapeRecordButtonSize = 150.dp

/**
 * 录音页（需求四、五、十）。
 *
 * 页面中间只有一个大按钮：
 * - 没在录音时显示"开始录音"，点一下开始（同时申请麦克风权限）；
 * - 录音中按钮变红并显示"正在录音 00:12"，再点一下结束，文件自动保存。
 *
 * 页面下方（横屏时在右边一栏）是「最近录音」入口，进到录音列表（需求六）。
 *
 * 竖屏 / 横屏各一套布局：竖屏是上下排列，横屏是左右两栏 —— 横屏时屏幕很矮，
 * 上下堆叠会把大按钮挤没，左右分栏则能把"按钮"和"最近录音"并排放好。
 *
 * 需求九：录音**不碰节拍器**。节拍器正在响就继续响，这里不会去暂停它。
 *
 * @param recordingsCount 已经录了多少段，用来把「最近录音」入口的说明写具体。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingScreen(
    state: RecordingUiState,
    recordingsCount: Int,
    onBack: () -> Unit,
    onToggleRecording: () -> Unit,
    onOpenRecordings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // 麦克风权限：录音必须要有它。已经有就什么都不问，没有就先申请一次。
    var hasPermission by remember { mutableStateOf(context.hasRecordPermission()) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(text = "录音") },
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
        val contentModifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .padding(horizontal = 20.dp, vertical = 16.dp)

        val buttonSize = if (isLandscapeScreen()) {
            LandscapeRecordButtonSize
        } else {
            PortraitRecordButtonSize
        }

        if (isLandscapeScreen()) {
            Row(
                modifier = contentModifier,
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                RecordColumn(
                    state = state,
                    hasPermission = hasPermission,
                    buttonSize = buttonSize,
                    onToggleRecording = onToggleRecording,
                    onRequestPermission = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                    modifier = Modifier.weight(1f)
                )
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    SavedNotice(state)
                    Spacer(modifier = Modifier.height(20.dp))
                    RecordingsEntry(count = recordingsCount, onClick = onOpenRecordings)
                }
            }
        } else {
            Column(
                modifier = contentModifier,
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                RecordColumn(
                    state = state,
                    hasPermission = hasPermission,
                    buttonSize = buttonSize,
                    onToggleRecording = onToggleRecording,
                    onRequestPermission = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(32.dp))
                RecordingsEntry(
                    count = recordingsCount,
                    onClick = onOpenRecordings,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * 大按钮那一列：状态文字 + 录音按钮 + 说明 + 已保存 / 出错的提示。
 *
 * 竖屏时它占满宽度、居中显示；横屏时占左边一栏。
 */
@Composable
private fun RecordColumn(
    state: RecordingUiState,
    hasPermission: Boolean,
    buttonSize: Dp,
    onToggleRecording: () -> Unit,
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = state.statusLabel,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            // 秒表用等宽字体：数字跳动时文字不会左右抖。
            fontFamily = if (state.isRecording) FontFamily.Monospace else FontFamily.Default
        )

        Spacer(modifier = Modifier.height(20.dp))

        RecordButton(
            isRecording = state.isRecording,
            enabled = hasPermission,
            size = buttonSize,
            onClick = onToggleRecording
        )

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = state.hintLabel,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        if (!hasPermission) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "需要麦克风权限才能录音",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(10.dp))
            Button(onClick = onRequestPermission) {
                Text(text = "授予麦克风权限")
            }
        }

        SavedNotice(state)
    }
}

/** "已保存：20260926-001" / 出错提示。两句话都可能有，所以分开显示。 */
@Composable
private fun SavedNotice(state: RecordingUiState) {
    val saved = state.lastSavedName
    if (saved != null) {
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "已保存：$saved",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center
        )
    }
    state.message?.let { message ->
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * 录音大按钮。
 *
 * 两个状态一眼能分清：
 * - 没录音：主色圆底 + 中间一个实心圆点（经典"录制"符号），文字提示"开始录音"；
 * - 录音中：错误色（红）圆底 + 中间一个圆角方块（经典"停止"符号）。
 *
 * 形状全部用 Compose 画出来，不依赖任何图标库 —— 这样"开始 / 停止"的语义最直白。
 */
@Composable
private fun RecordButton(
    isRecording: Boolean,
    enabled: Boolean,
    size: Dp,
    onClick: () -> Unit
) {
    val containerColor = when {
        !enabled -> MaterialTheme.colorScheme.surfaceVariant
        isRecording -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    val glyphColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
        isRecording -> MaterialTheme.colorScheme.onError
        else -> MaterialTheme.colorScheme.onPrimary
    }
    val contentDescription = if (isRecording) "结束录音" else "开始录音"

    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(containerColor)
            .border(
                width = 2.dp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                shape = CircleShape
            )
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center
    ) {
        if (isRecording) {
            // "停止"：圆角方块。
            Box(
                modifier = Modifier
                    .size(size * 0.32f)
                    .clip(RoundedCornerShape(size * 0.06f))
                    .background(glyphColor)
            )
        } else {
            // "录制"：实心圆点。
            Box(
                modifier = Modifier
                    .size(size * 0.42f)
                    .clip(CircleShape)
                    .background(glyphColor)
            )
        }
    }
}

/** 「最近录音」入口卡片：图标 + 标题 + 一句说明 + 右箭头。 */
@Composable
private fun RecordingsEntry(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = ImageVector.vectorResource(R.drawable.ic_queue_music),
                contentDescription = null,
                modifier = Modifier.size(26.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "最近录音",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (count > 0) {
                        "共 $count 段，最新的排在最上面"
                    } else {
                        "还没有录音，录一段试试"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 当前是不是横屏。录音页和播放页各有一份，和项目里其它页面保持同样的写法。 */
@Composable
private fun isLandscapeScreen(): Boolean =
    LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

/** 有没有麦克风权限。 */
private fun Context.hasRecordPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

@Preview(showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun RecordingScreenPreview() {
    MusicPracticeTheme {
        RecordingScreen(
            state = RecordingUiState(lastSavedName = "20260926-001"),
            recordingsCount = 3,
            onBack = {},
            onToggleRecording = {},
            onOpenRecordings = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun RecordingScreenRecordingPreview() {
    MusicPracticeTheme {
        RecordingScreen(
            state = RecordingUiState(isRecording = true, elapsedMillis = 12_000L),
            recordingsCount = 3,
            onBack = {},
            onToggleRecording = {},
            onOpenRecordings = {}
        )
    }
}
