package com.example.musicpractice.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.musicpractice.ui.theme.MusicPracticeTheme

/**
 * "长按一条记录之后弹出的操作菜单"以及配套的确认窗口（需求一、六）。
 *
 * 乐谱项目（最近项目）和录音（最近录音）都需要同一套东西：
 * 长按 → 菜单里选"删除 / 重命名" → 删除要再确认一次、重命名要填个新名字。
 *
 * 三套窗口写在这里共用，两边的交互、文案口吻和判断规则（名字不能为空）就完全一致，
 * 不会出现"乐谱能改、录音改不了"这种走偏。
 */

/**
 * 长按后的操作菜单：把"删除"和"重命名"两个选项列出来。
 *
 * 用 [AlertDialog] 而不是直接删：长按是很容易误触的手势，
 * 弹一层菜单之后，删掉一条记录至少需要"长按 → 点删除 → 再确认"三步。
 *
 * @param title 显示被操作的那条记录的名字，让用户确认自己长按的是哪一个。
 * @param onDelete 点了"删除"（真正的确认窗口由调用方接着弹）。
 * @param onRename 点了"重命名"（输入窗口由调用方接着弹）。
 */
@Composable
internal fun EntryActionMenuDialog(
    title: String,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                EntryActionRow(
                    icon = Icons.Filled.Edit,
                    label = "重命名",
                    onClick = onRename
                )
                EntryActionRow(
                    icon = Icons.Filled.Delete,
                    label = "删除",
                    onClick = onDelete,
                    destructive = true
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "取消")
            }
        }
    )
}

/** 菜单里的一行：图标 + 文字，整行可点。删除用错误色，一眼能区分开。 */
@Composable
private fun EntryActionRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    destructive: Boolean = false
) {
    val color = if (destructive) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = color
        )
        Spacer(modifier = Modifier.width(14.dp))
        Text(text = label, style = MaterialTheme.typography.bodyLarge, color = color)
    }
}

/**
 * 重命名窗口：输入框里预填当前名字，确认后把新名字交回去。
 *
 * 名字为空时"确定"不可点 —— 项目和录音都不应该有"没有名字"的记录。
 * 输入内容用 [rememberSaveable] 保存：弹窗期间转屏、被系统回收再回来，用户刚打的字还在。
 */
@Composable
internal fun RenameEntryDialog(
    title: String,
    currentName: String,
    placeholder: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var input by rememberSaveable(currentName) { mutableStateOf(currentName) }
    val trimmed = input.trim()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = {
            Column {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(text = placeholder) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = { if (trimmed.isNotEmpty()) onConfirm(trimmed) }
                    )
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "当前名字：$currentName",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(trimmed) }, enabled = trimmed.isNotEmpty()) {
                Text(text = "确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "取消")
            }
        }
    )
}

/**
 * 删除确认窗口：删除是不可撤销的，所以在动手之前把"会发生什么"写清楚。
 *
 * @param message 说明这次删除到底删掉什么（乐谱项目只删 App 里的记录、录音连音频文件一起删）。
 */
@Composable
internal fun DeleteEntryDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = {
            Column {
                Text(text = message)
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "删除后无法撤销。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = "删除", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Medium)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "取消")
            }
        }
    )
}

@Preview(showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun EntryActionMenuDialogPreview() {
    MusicPracticeTheme {
        EntryActionMenuDialog(
            title = "《Sound Euphonium》",
            onDismiss = {},
            onDelete = {},
            onRename = {}
        )
    }
}
