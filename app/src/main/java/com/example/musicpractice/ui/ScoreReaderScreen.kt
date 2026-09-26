package com.example.musicpractice.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.window.Dialog
import com.example.musicpractice.score.ScoreProject
import com.example.musicpractice.ui.theme.MusicPracticeTheme

/** 系统文件管理器里只显示 PDF。 */
private const val PDF_MIME_TYPE = "application/pdf"

/**
 * 乐谱阅读器模块里各个页面（主页、阅读页、排序页）共用的操作。
 *
 * 打包成一个对象传参，是因为几个页面的右上角菜单用的是同一套入口：
 * 同一个实现、同一份文字，行为不会在页面之间走偏。
 */
class ScoreReaderActions(
    /** 左上角返回：直接回节拍器主页面（阅读页的规则，见需求六）。 */
    val onBack: () -> Unit,
    /** 回乐谱阅读器主页（阅读页的菜单用）。 */
    val onOpenHome: () -> Unit,
    /** 最近项目：进入项目列表（数据库里存着的全部乐谱项目）。 */
    val onRecentProjects: () -> Unit,
    /** 从图片导入项目：走完整的导入流程（命名 → 选图片 → 建项目）。 */
    val onImportImages: (name: String, uris: List<Uri>) -> Unit,
    /** 从PDF导入项目：走完整的导入流程（命名 → 选文件 → 建项目）。 */
    val onImportPdf: (name: String, uri: Uri) -> Unit,
    /** 排序图片：进入当前图片项目的排序页。 */
    val onSortImages: () -> Unit
)

/**
 * 乐谱阅读器主页：三个纵向排列的入口 + 右上角菜单。
 *
 * 页面上没有滚动容器：三个入口竖着排，任何手机屏幕都放得下。
 * 三个入口都是真的：「最近项目」进项目列表（需求二），另外两个走各自的导入流程。
 *
 * @param sortableProject 当前可排序的图片项目；不是图片项目时为 null，菜单里的
 *   「排序图片」就会隐藏（需求八：PDF 项目下这个入口不可用）。
 * @param isImporting 正在导入时显示一个简单的进度提示，避免用户以为没反应又点一次。
 * @param recentProjectCount 已经导入过多少个乐谱项目（数据库里的条数），
 *   用来把「最近项目」那句说明写得具体一点。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScoreReaderScreen(
    actions: ScoreReaderActions,
    sortableProject: ScoreProject?,
    isImporting: Boolean,
    recentProjectCount: Int,
    modifier: Modifier = Modifier
) {
    // 菜单展开状态用 remember 而不是 rememberSaveable：离开这一页再回来时菜单应该是收起的。
    var menuExpanded by remember { mutableStateOf(false) }
    // 两个导入流程（命名弹窗 + 系统选择器）都归这两个函数管，返回的是"发起导入"的函数。
    val startPdfImport = rememberPdfImportFlow(onImport = actions.onImportPdf)
    val startImageImport = rememberImageImportFlow(onImport = actions.onImportImages)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(text = "乐谱阅读器") },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回节拍器"
                        )
                    }
                },
                actions = {
                    // 右上角菜单：用于再次进入这些功能入口。
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
                        showHomeItem = false,
                        showSortImages = sortableProject != null
                    )
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ScoreReaderEntry(
                title = "最近项目",
                subtitle = if (recentProjectCount > 0) {
                    "共 $recentProjectCount 个项目，最近打开的排在最前面"
                } else {
                    "还没有项目，导入后的乐谱都会出现在这里"
                },
                icon = Icons.AutoMirrored.Filled.List,
                onClick = actions.onRecentProjects
            )
            ScoreReaderEntry(
                title = "从图片导入项目",
                subtitle = "选择一张或多张图片，整理成乐谱项目",
                icon = Icons.Filled.Add,
                onClick = startImageImport
            )
            ScoreReaderEntry(
                title = "从PDF导入项目",
                subtitle = "选择本机 PDF 文件，创建乐谱项目",
                icon = Icons.Filled.Create,
                onClick = startPdfImport
            )
        }
    }

    if (isImporting) {
        ImportingIndicator(text = "正在导入…")
    }
}

/**
 * 主页上的一个入口：圆角卡片 + 图标 + 标题和一句说明。
 *
 * 用 Card 而不是按钮，是因为这里要写清楚"这个入口做什么"，一行字放不下；
 * 卡片整块可点，点哪儿都能进，比小按钮好按。
 */
@Composable
private fun ScoreReaderEntry(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
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
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
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

/**
 * 右上角菜单。
 *
 * 内容（需求七、八）：最近项目 / 从图片导入项目 / 从PDF导入项目 / 排序图片。
 * 阅读页额外多一项「乐谱阅读器主页」[showHomeItem]，否则"点入口就直接回到上次那份乐谱"
 * 的规则会让主页再也进不去；「排序图片」[showSortImages] 只在当前项目是图片项目时出现。
 */
@Composable
internal fun ScoreReaderOverflowMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    actions: ScoreReaderActions,
    onStartPdfImport: () -> Unit,
    onStartImageImport: () -> Unit,
    showHomeItem: Boolean,
    showSortImages: Boolean
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss
    ) {
        if (showHomeItem) {
            DropdownMenuItem(
                text = { Text(text = "乐谱阅读器主页") },
                onClick = {
                    onDismiss()
                    actions.onOpenHome()
                }
            )
            HorizontalDivider()
        }
        DropdownMenuItem(
            text = { Text(text = "最近项目") },
            onClick = {
                onDismiss()
                actions.onRecentProjects()
            }
        )
        DropdownMenuItem(
            text = { Text(text = "从图片导入项目") },
            onClick = {
                onDismiss()
                onStartImageImport()
            }
        )
        DropdownMenuItem(
            text = { Text(text = "从PDF导入项目") },
            onClick = {
                onDismiss()
                onStartPdfImport()
            }
        )
        if (showSortImages) {
            DropdownMenuItem(
                text = { Text(text = "排序图片") },
                onClick = {
                    onDismiss()
                    actions.onSortImages()
                }
            )
        }
    }
}

/**
 * 项目命名窗口的通用部分：需要名字的导入流程（PDF / 图片）都从它开始。
 *
 * 返回值是"打开命名窗口"的函数。弹窗由本函数直接显示（[AlertDialog] 是独立窗口，
 * 放在页面哪个位置都不影响显示），所以几个页面和两种导入流程可以共用同一份实现。
 *
 * 输入内容用 rememberSaveable 保存：用户在系统选择器里挑东西时，本 App 可能被系统回收，
 * 回来之后还要知道这份乐谱该叫什么名字。
 */
@Composable
private fun rememberProjectNameDialog(
    hint: String,
    onConfirm: (String) -> Unit
): () -> Unit {
    var dialogVisible by rememberSaveable { mutableStateOf(false) }
    var nameInput by rememberSaveable { mutableStateOf("") }

    if (dialogVisible) {
        ProjectNameDialog(
            name = nameInput,
            hint = hint,
            onNameChange = { nameInput = it },
            onDismiss = { dialogVisible = false },
            onConfirm = {
                dialogVisible = false
                onConfirm(nameInput.trim())
            }
        )
    }

    return {
        // 每次重新开始导入都从空白名字开始，避免上一次的名字被顺手确认掉。
        nameInput = ""
        dialogVisible = true
    }
}

/**
 * 记住一整套"给项目命名 → 选 PDF → 交给 ViewModel 导入"的流程。
 *
 * 返回值就是"发起导入"的函数：点「从PDF导入项目」时调用它。
 * 用户确认名字后才打开系统文件管理器；选完文件回来，用刚才那个名字建项目。
 */
@Composable
internal fun rememberPdfImportFlow(onImport: (name: String, uri: Uri) -> Unit): () -> Unit {
    // 用户确认过的项目名。选完文件回来时用它建项目，所以不能只存在弹窗里。
    var confirmedName by rememberSaveable { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        val name = confirmedName
        // uri 为 null：用户在系统文件管理器里按了返回、没选文件 —— 什么都不做，
        // 也不留下半个项目。
        if (uri != null && name != null) {
            onImport(name, uri)
        }
    }

    return rememberProjectNameDialog(
        hint = "下一步会打开系统文件管理器，让你选择这份乐谱的 PDF 文件。"
    ) { name ->
        confirmedName = name
        // 打开 Android 系统文件管理器，只显示 PDF 文件。
        picker.launch(arrayOf(PDF_MIME_TYPE))
    }
}

/**
 * 记住一整套"给项目命名 → 选图片（可多选）→ 交给 ViewModel 导入"的流程。
 *
 * 用的是系统图片选择器（Android 13 起是系统相册选择器，更早的系统上系统会自动回退到
 * 文件选择器），只挑图片、不需要存储权限，也不会把图片交给任何第三方。
 * 选中的顺序就是图片项目的初始顺序（需求三）。
 */
@Composable
internal fun rememberImageImportFlow(onImport: (name: String, uris: List<Uri>) -> Unit): () -> Unit {
    var confirmedName by rememberSaveable { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        val name = confirmedName
        // 空列表 = 用户什么都没选就返回了，什么都不做。
        if (uris.isNotEmpty() && name != null) {
            onImport(name, uris)
        }
    }

    return rememberProjectNameDialog(
        hint = "下一步会打开系统图片选择器，可以只选一张，也可以一次选多张。"
    ) { name ->
        confirmedName = name
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
}

/** 项目命名窗口。名字为空时"确定"不可点，省得建出一个没有名字的项目。 */
@Composable
private fun ProjectNameDialog(
    name: String,
    hint: String,
    onNameChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "新建乐谱项目") },
        text = {
            Column {
                Text(
                    text = "项目名称",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(text = "例如：《Sound Euphonium》") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = { if (name.isNotBlank()) onConfirm() }
                    )
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = name.isNotBlank()) {
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
 * "正在导入…"的提示。
 *
 * 导入要拷文件、写乐谱库，图片多的时候可能要一两秒；期间用一个小窗挡住点击
 * （onDismissRequest 空实现），用户就知道"App 正在干活"，也不会重复触发导入。
 */
@Composable
internal fun ImportingIndicator(text: String) {
    Dialog(onDismissRequest = {}) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun ScoreReaderScreenPreview() {
    MusicPracticeTheme {
        ScoreReaderScreen(
            actions = ScoreReaderActions(
                onBack = {},
                onOpenHome = {},
                onRecentProjects = {},
                onImportImages = { _, _ -> },
                onImportPdf = { _, _ -> },
                onSortImages = {}
            ),
            sortableProject = null,
            isImporting = false,
            recentProjectCount = 2
        )
    }
}
