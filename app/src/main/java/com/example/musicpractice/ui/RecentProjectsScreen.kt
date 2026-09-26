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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.musicpractice.score.ScoreImage
import com.example.musicpractice.score.ScoreProject
import com.example.musicpractice.score.ScoreProjectKind
import com.example.musicpractice.score.ScoreTimeFormat
import com.example.musicpractice.ui.theme.MusicPracticeTheme

/**
 * 最近项目：把数据库里的乐谱项目全列出来（需求二、三、五）。
 *
 * - 每一行显示项目名称、类型（PDF / 图片）和最近打开时间（"刚刚打开""昨天打开"这种说法）；
 * - 顺序由数据层排好：最近打开的在上（[ScoreProject] 的列表进来时就已经按最近打开时间倒序）；
 * - 点一下打开这个项目：PDF 项目进 PDF 阅读页，图片项目进图片阅读页，同时刷新最近打开时间；
 * - **长按**弹删除确认窗口，确认后只删 App 里的项目记录，用户原来的文件不碰。
 *
 * @param projects 全部项目，已经按最近打开时间倒序（数据层排的，界面不再排一遍）。
 * @param onBack 左上角返回：回到乐谱阅读器主页（三个入口那一页）。
 * @param onOpenProject 点一下某一行。
 * @param onDeleteProject 删除确认窗口里点了"删除"。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentProjectsScreen(
    projects: List<ScoreProject>,
    actions: ScoreReaderActions,
    isImporting: Boolean,
    onBack: () -> Unit,
    onOpenProject: (ScoreProject) -> Unit,
    onDeleteProject: (ScoreProject) -> Unit,
    modifier: Modifier = Modifier
) {
    var menuExpanded by remember { mutableStateOf(false) }
    // 正等着确认删除的项目；null 表示没有弹窗。
    var pendingDelete by remember { mutableStateOf<ScoreProject?>(null) }

    // "相对时间"要有一个"现在"作参照。它只跟着列表内容变，不需要每秒钟重算 ——
    // 用户在这个页面停留几十秒的话，"刚刚打开"和"1 分钟前打开"的差别不值得为它做定时刷新。
    val nowMillis = remember(projects) { System.currentTimeMillis() }

    val startPdfImport = rememberPdfImportFlow(onImport = actions.onImportPdf)
    val startImageImport = rememberImageImportFlow(onImport = actions.onImportImages)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(text = "最近项目") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回乐谱阅读器主页"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            imageVector = Icons.Filled.Menu,
                            contentDescription = "更多功能"
                        )
                    }
                    ScoreReaderOverflowMenu(
                        expanded = menuExpanded,
                        onDismiss = { menuExpanded = false },
                        actions = actions,
                        onStartPdfImport = startPdfImport,
                        onStartImageImport = startImageImport,
                        // 这一页的上一级就是乐谱阅读器主页，所以菜单里给出回主页的入口。
                        showHomeItem = true,
                        // "排序图片"针对的是"正在看的那个图片项目"，这一页没有当前项目，所以不给。
                        showSortImages = false
                    )
                }
            )
        }
    ) { innerPadding ->
        if (projects.isEmpty()) {
            ReaderMessage(
                title = "还没有乐谱项目",
                detail = "回到乐谱阅读器主页，从图片或 PDF 导入第一份乐谱吧。"
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
                        text = "共 ${projects.size} 个项目 · 点一下打开，长按删除",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                    )
                }

                items(items = projects, key = { it.id }) { project ->
                    RecentProjectCard(
                        project = project,
                        timeText = ScoreTimeFormat.describeLastOpened(project.lastOpenedAtMillis, nowMillis),
                        onClick = { onOpenProject(project) },
                        onLongClick = { pendingDelete = project }
                    )
                }
            }
        }
    }

    // 删除确认窗口。要删的项目被单独存下来，所以弹窗期间列表怎么变都不影响它。
    pendingDelete?.let { project ->
        DeleteProjectDialog(
            project = project,
            onDismiss = { pendingDelete = null },
            onConfirm = {
                pendingDelete = null
                onDeleteProject(project)
            }
        )
    }

    if (isImporting) {
        ImportingIndicator(text = "正在导入…")
    }
}

/**
 * 列表里的一行：项目名 + 类型标签 + 最近打开时间。
 *
 * 长按用 [combinedClickable] 接：单击打开、长按弹删除确认窗口。
 * 整行都是可点区域 —— 点哪儿打开哪儿，长按也是。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentProjectCard(
    project: ScoreProject,
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
                    text = project.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProjectKindLabel(isImageProject = project.isImageProject)
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
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 类型标签：PDF 项目显示"PDF"，图片项目显示"图片"。 */
@Composable
private fun ProjectKindLabel(isImageProject: Boolean) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
    ) {
        Text(
            text = if (isImageProject) "图片" else "PDF",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

/**
 * 删除确认窗口（需求五）。
 *
 * 文案里明确写出"只删 App 里的记录、不动你原来的文件"：删除是不可撤销的动作，
 * 该让用户知道到底会删掉什么。
 */
@Composable
private fun DeleteProjectDialog(
    project: ScoreProject,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "删除项目") },
        text = {
            Column {
                Text(text = "是否删除项目：\n${project.name}？")
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "只会删掉 App 里的这条项目记录（以及导入时拷贝的本地副本），" +
                        "你原来的 PDF / 图片文件不会被删除。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = "删除", color = MaterialTheme.colorScheme.error)
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
private fun RecentProjectsScreenPreview() {
    val now = 1_774_000_000_000L
    MusicPracticeTheme {
        RecentProjectsScreen(
            projects = listOf(
                ScoreProject(
                    id = "score-1",
                    name = "《Sound Euphonium》",
                    kind = ScoreProjectKind.PDF,
                    pdfUri = "content://preview/score.pdf",
                    lastOpenedAtMillis = now - 5_000L
                ),
                ScoreProject(
                    id = "score-2",
                    name = "《练习曲》",
                    kind = ScoreProjectKind.IMAGES,
                    images = listOf(ScoreImage(uri = "content://preview/image", localPath = null)),
                    lastOpenedAtMillis = now - 26L * 60 * 60 * 1000
                )
            ),
            actions = ScoreReaderActions(
                onBack = {},
                onOpenHome = {},
                onRecentProjects = {},
                onImportImages = { _, _ -> },
                onImportPdf = { _, _ -> },
                onSortImages = {}
            ),
            isImporting = false,
            onBack = {},
            onOpenProject = {},
            onDeleteProject = {}
        )
    }
}
