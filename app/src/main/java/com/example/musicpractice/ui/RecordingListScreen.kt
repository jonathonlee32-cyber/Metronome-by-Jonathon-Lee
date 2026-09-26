package com.example.musicpractice.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.musicpractice.recording.Recording
import com.example.musicpractice.recording.RecordingFormat
import com.example.musicpractice.ui.theme.MusicPracticeTheme

/**
 * 最近录音（需求六、十）。
 *
 * - 列出全部录音，**按录音时间倒序**：最新的排在最上面；
 * - 点一下进播放页；
 * - 长按弹出操作菜单：删除 / 重命名（和"最近项目"完全同一套交互）。
 *
 * 竖屏横屏都用这一个列表：内容是一行行的卡片，横屏时屏幕更宽，卡片自然更宽，
 * 不需要另做一套布局；页面本身就是可滚动列表，任何高度都放得下。
 *
 * @param recordings 全部录音，已经按录音时间倒序（数据层排的，界面不再排一遍）。
 * @param onOpenRecording 点一下某一行：进播放页。
 * @param onRenameRecording 重命名窗口确认后调用（参数是原录音和新名字）。
 * @param onDeleteRecording 删除确认窗口里点了"删除"。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingListScreen(
    recordings: List<Recording>,
    onBack: () -> Unit,
    onOpenRecording: (Recording) -> Unit,
    onRenameRecording: (Recording, String) -> Unit,
    onDeleteRecording: (Recording) -> Unit,
    modifier: Modifier = Modifier
) {
    // 长按之后的一串状态：先弹操作菜单，选了删除 / 重命名再弹对应的窗口。
    // 记住的都是"哪一条录音"，所以弹窗期间列表怎么变都不影响它。
    var actionTarget by remember { mutableStateOf<Recording?>(null) }
    var deleteTarget by remember { mutableStateOf<Recording?>(null) }
    var renameTarget by remember { mutableStateOf<Recording?>(null) }

    // "多久以前录的"要有一个"现在"作参照。它只跟着列表内容变，不需要每秒钟重算。
    val nowMillis = remember(recordings) { System.currentTimeMillis() }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(text = "最近录音") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回录音"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        if (recordings.isEmpty()) {
            ReaderMessage(
                title = "还没有录音",
                detail = "回到录音页，点一下大按钮录第一段吧。"
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item(key = "hint") {
                    Text(
                        text = "共 ${recordings.size} 段录音 · 点一下播放，长按更多操作",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                    )
                }

                items(items = recordings, key = { it.id }) { recording ->
                    RecordingCard(
                        recording = recording,
                        timeText = RecordingFormat.describeCreated(recording.createdTimeMillis, nowMillis),
                        onClick = { onOpenRecording(recording) },
                        onLongClick = { actionTarget = recording }
                    )
                }
            }
        }
    }

    // 长按后的操作菜单（需求六）：删除 / 重命名。
    actionTarget?.let { recording ->
        EntryActionMenuDialog(
            title = recording.name,
            onDismiss = { actionTarget = null },
            onRename = {
                actionTarget = null
                renameTarget = recording
            },
            onDelete = {
                actionTarget = null
                deleteTarget = recording
            }
        )
    }

    // 重命名：输入框里预填当前名字。
    renameTarget?.let { recording ->
        RenameEntryDialog(
            title = "重命名录音",
            currentName = recording.name,
            placeholder = "例如：20260926-001",
            onDismiss = { renameTarget = null },
            onConfirm = { newName ->
                renameTarget = null
                onRenameRecording(recording, newName)
            }
        )
    }

    // 删除确认：录音是 App 自己录的、只有这一份，所以明说"音频文件会一起删掉"。
    deleteTarget?.let { recording ->
        DeleteEntryDialog(
            title = "删除录音",
            message = "是否删除录音：\n${recording.name}？\n\n" +
                "这段录音的音频文件也会一起删除（它只保存在本机，删除后无法恢复）。",
            onDismiss = { deleteTarget = null },
            onConfirm = {
                deleteTarget = null
                onDeleteRecording(recording)
            }
        )
    }
}

/**
 * 列表里的一行：名字 + 录音时间 + 时长。
 *
 * 长按用 [combinedClickable] 接：单击进播放页、长按弹操作菜单，整行都是可点区域。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecordingCard(
    recording: Recording,
    timeText: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = recording.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DurationLabel(recording.durationLabel)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = timeText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/** 时长标签：等宽字体，读起来像播放器上的时间。 */
@Composable
private fun DurationLabel(text: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun RecordingListScreenPreview() {
    val now = 1_774_000_000_000L
    MusicPracticeTheme {
        RecordingListScreen(
            recordings = listOf(
                Recording(
                    id = "rec-1",
                    name = "20260926-002",
                    filePath = "/data/user/0/com.example.musicpractice/files/recordings/20260926-002.m4a",
                    createdTimeMillis = now - 5_000L,
                    durationMillis = 340_000L
                ),
                Recording(
                    id = "rec-2",
                    name = "20260926-001",
                    filePath = "/data/user/0/com.example.musicpractice/files/recordings/20260926-001.m4a",
                    createdTimeMillis = now - 26L * 60 * 60 * 1000,
                    durationMillis = 85_000L,
                    lastPositionMillis = 20_000L
                )
            ),
            onBack = {},
            onOpenRecording = {},
            onRenameRecording = { _, _ -> },
            onDeleteRecording = {}
        )
    }
}
