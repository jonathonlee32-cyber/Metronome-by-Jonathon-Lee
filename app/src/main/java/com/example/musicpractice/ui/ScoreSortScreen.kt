package com.example.musicpractice.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.musicpractice.score.ScoreFiles
import com.example.musicpractice.score.ScoreImage
import com.example.musicpractice.score.ScoreImageOrdering
import com.example.musicpractice.score.ScoreBitmapDecoder
import com.example.musicpractice.score.ScoreProject
import com.example.musicpractice.score.ScoreProjectKind
import com.example.musicpractice.ui.theme.MusicPracticeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 缩略图一行放几张。三列在 360dp 宽的手机上每张约 110dp，看得清也点得准。 */
private const val THUMBNAIL_COLUMNS = 3

/** 序号圆点的直径。 */
private val BADGE_SIZE = 34.dp

/**
 * 图片排序页（需求四 ~ 七）。
 *
 * 页面分两层，用同一个 Composable 表示：
 * - **图片总览**（默认）：三列缩略图，每张图右上角一个序号圆点；
 * - **图片查看界面**：点某张缩略图进来，单页显示、左右滑动换上一张 / 下一张，
 *   返回键回到总览（需求六）。
 *
 * 序号圆点的规则（需求五）：
 * - 这张图**还没排序**：显示"下一个该给的页码"，灰色。例如已经排了 1、2、3，
 *   所有没排的图都显示 4 —— 点哪张，哪张就是第 4 页；
 * - 这张图**已经排好**：显示它最终的页码，颜色变成主题色（点一下不会改号）。
 *
 * 在查看界面里点序号，除了记住这一页，还会自动翻到下一张，方便一张接一张地排下去（需求六）。
 *
 * 缩略图和查看界面都按 [project] 里的**导入顺序**排列（不是阅读顺序）：
 * 这样"图片A → 图片B → 图片C"是一条稳定的线，不会因为刚排好一张就整页跳位置。
 * 排好之后的阅读顺序由 [ScoreImageOrdering.ordered] 换算，阅读页用的是那个顺序。
 *
 * @param onAssignPage 用户点了某张图（按导入顺序的位置）的序号：把它排成下一页并落盘。
 * @param onResetOrder 用户点了"重置排序"：清掉所有页码、回到导入顺序并落盘。
 */
@Composable
fun ScoreSortScreen(
    project: ScoreProject,
    isImporting: Boolean,
    onBack: () -> Unit,
    onAssignPage: (position: Int) -> Unit,
    onResetOrder: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 正在查看第几张（进入图片查看界面）；null 表示停在总览。
    var viewingIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    // 缩略图网格的滚动位置放在这一层：进图片查看再回来，还是停在上次看的那一片。
    val gridState = rememberLazyGridState()

    val images = project.images
    val index = viewingIndex

    if (index == null || index !in images.indices) {
        SortOverview(
            project = project,
            images = images,
            gridState = gridState,
            isImporting = isImporting,
            onBack = onBack,
            onOpenImage = { viewingIndex = it },
            onAssign = onAssignPage,
            onResetOrder = onResetOrder,
            modifier = modifier
        )
    } else {
        SortImageViewer(
            project = project,
            images = images,
            initialIndex = index,
            onBack = { viewingIndex = null },
            onAssign = onAssignPage,
            modifier = modifier
        )
    }
}

/** 图片总览：项目名 + 排序进度 + 三列缩略图。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortOverview(
    project: ScoreProject,
    images: List<ScoreImage>,
    gridState: LazyGridState,
    isImporting: Boolean,
    onBack: () -> Unit,
    onOpenImage: (Int) -> Unit,
    onAssign: (Int) -> Unit,
    onResetOrder: () -> Unit,
    modifier: Modifier = Modifier
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val assigned = ScoreImageOrdering.assignedCount(images)
    val nextNumber = ScoreImageOrdering.nextPageNumber(images)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                // 顶部显示当前项目名称（需求四）。
                title = {
                    Text(
                        text = project.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回阅读页"
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
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        // 排错了想重来：清掉所有页码，回到导入顺序。不提供这个入口的话，
                        // 一次误点就再也改不回来了。
                        DropdownMenuItem(
                            text = { Text(text = "重置排序") },
                            onClick = {
                                menuExpanded = false
                                onResetOrder()
                            }
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            SortProgress(
                assigned = assigned,
                total = images.size,
                nextNumber = nextNumber
            )

            LazyVerticalGrid(
                columns = GridCells.Fixed(THUMBNAIL_COLUMNS),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    top = 4.dp,
                    bottom = 24.dp
                ),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(
                    items = images,
                    key = { position, image -> "$position-${image.uri}" }
                ) { position, image ->
                    SortThumbnail(
                        projectId = project.id,
                        importPosition = position,
                        image = image,
                        nextNumber = nextNumber,
                        onOpen = { onOpenImage(position) },
                        onAssign = { onAssign(position) }
                    )
                }
            }
        }
    }

    if (isImporting) {
        ImportingIndicator(text = "正在导入…")
    }
}

/** 排序进度 + 一句操作提示。 */
@Composable
private fun SortProgress(assigned: Int, total: Int, nextNumber: Int) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = if (assigned == 0 || total == 0) {
                "还没开始排序"
            } else if (assigned == total) {
                "已排序 $assigned / $total 张 · 全部排好了"
            } else {
                "已排序 $assigned / $total 张 · 下一张是第 $nextNumber 页"
            },
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "点缩略图右上角的序号就能按顺序编号，点图片本身可以放大查看",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 一张缩略图 + 右上角的序号圆点。 */
@Composable
private fun SortThumbnail(
    projectId: String,
    importPosition: Int,
    image: ScoreImage,
    nextNumber: Int,
    onOpen: () -> Unit,
    onAssign: () -> Unit
) {
    val context = LocalContext.current

    Box(
        modifier = Modifier
            // 固定成竖幅比例：不管原图是横是竖，网格都整整齐齐。
            .aspectRatio(3f / 4f)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        ScoreBitmapPage(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onOpen),
            contentDescription = "第 $importPosition 张图片",
            renderKey = image.uri,
            failureText = "打不开",
            load = { targetWidthPx ->
                loadLocalImage(context, projectId, importPosition, image, targetWidthPx)
            }
        )

        // 序号圆点压在缩略图右上角：它在上层，点它只排序，不会连带打开图片。
        SequenceBadge(
            number = image.pageNumber ?: nextNumber,
            assigned = image.pageNumber != null,
            onClick = onAssign,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
        )
    }
}

/**
 * 图片查看界面：单页显示一张图，左右滑动换上一张 / 下一张。
 *
 * 返回键回图片总览（需求六）。点右上角的序号 = 给这张图排下一个号，然后自动翻到下一张，
 * 用户可以一张接一张排下去；已经是最后一张时就停在这里。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortImageViewer(
    project: ScoreProject,
    images: List<ScoreImage>,
    initialIndex: Int,
    onBack: () -> Unit,
    onAssign: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, (images.size - 1).coerceAtLeast(0)),
        pageCount = { images.size }
    )
    val scope = rememberCoroutineScope()
    val nextNumber = ScoreImageOrdering.nextPageNumber(images)
    val currentImage = images.getOrNull(pagerState.currentPage)
    val context = LocalContext.current

    // 查看界面里的返回键：回图片总览，而不是直接回节拍器。
    BackHandler(enabled = true) { onBack() }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "${project.name} · 第 ${pagerState.currentPage + 1} / ${images.size} 张",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回图片总览"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                pageSpacing = 12.dp
            ) { position ->
                Box(modifier = Modifier.fillMaxSize()) {
                    ScoreBitmapPage(
                        modifier = Modifier.fillMaxSize(),
                        contentDescription = "第 ${position + 1} 张图片",
                        renderKey = images[position].uri,
                        failureText = "这张图打不开了",
                        load = { targetWidthPx ->
                            loadLocalImage(
                                context = context,
                                projectId = project.id,
                                importPosition = position,
                                image = images[position],
                                targetWidthPx = targetWidthPx
                            )
                        }
                    )

                    SequenceBadge(
                        number = images[position].pageNumber ?: nextNumber,
                        assigned = images[position].pageNumber != null,
                        onClick = {
                            onAssign(position)
                            // 自动进入下一张，继续排序。
                            if (position + 1 < images.size) {
                                scope.launch { pagerState.animateScrollToPage(position + 1) }
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(16.dp)
                    )
                }
            }

            // 底部一句话说明当前这张排到第几页 / 还没排，免得用户看不出点了有没有生效。
            SortViewerStatus(
                pageNumber = currentImage?.pageNumber,
                nextNumber = nextNumber,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp)
            )
        }
    }
}

/** 查看界面底部的一句状态：这张图排到第几页，或点序号会排到第几页。 */
@Composable
private fun SortViewerStatus(pageNumber: Int?, nextNumber: Int, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (pageNumber != null) {
                    "这张已经是第 $pageNumber 页"
                } else {
                    "点右上角序号，排成第 $nextNumber 页"
                },
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

/**
 * 序号圆点。
 *
 * 灰色 = 还没排（显示的点下去就是这一页），主题色 = 已经排好（显示最终页码）。
 */
@Composable
private fun SequenceBadge(
    number: Int,
    assigned: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .size(BADGE_SIZE)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        shape = CircleShape,
        color = if (assigned) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        contentColor = if (assigned) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        shadowElevation = 2.dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = number.toString(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * 按目标宽度读出某张图的本地副本（读不到返回 null，界面显示"打不开"）。
 *
 * 解码放在 IO 线程上：一张手机照片好几 MB，在主线程解码会把翻页卡出可见的顿挫。
 */
private suspend fun loadLocalImage(
    context: Context,
    projectId: String,
    importPosition: Int,
    image: ScoreImage,
    targetWidthPx: Int
): Bitmap? = withContext(Dispatchers.IO) {
    val file = ScoreFiles.resolveImage(context, projectId, importPosition, image)
        ?: return@withContext null
    ScoreBitmapDecoder.decode(file, targetWidthPx)
}

@Preview(showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun ScoreSortScreenPreview() {
    MusicPracticeTheme {
        ScoreSortScreen(
            project = ScoreProject(
                id = "score-preview",
                name = "《练习曲第一页》",
                kind = ScoreProjectKind.IMAGES,
                images = List(5) { index ->
                    ScoreImage(uri = "content://preview/$index", localPath = null)
                },
                lastOpenedAtMillis = 0L
            ),
            isImporting = false,
            onBack = {},
            onAssignPage = {},
            onResetOrder = {}
        )
    }
}
