package com.example.musicpractice.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.musicpractice.score.PdfPageRenderer
import com.example.musicpractice.score.ScoreBitmapDecoder
import com.example.musicpractice.score.ScoreFiles
import com.example.musicpractice.score.ScoreImage
import com.example.musicpractice.score.ScoreImageOrdering
import com.example.musicpractice.score.ScoreProject
import com.example.musicpractice.score.ScoreProjectKind
import com.example.musicpractice.ui.theme.MusicPracticeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 阅读页：一次一页地显示一份乐谱 —— PDF 项目和图片项目都用这一个页面。
 *
 * 交互（需求五 / 六）：
 * - 左右滑动翻页：向左滑是下一页、向右滑是上一页 —— 用 Compose 的 [HorizontalPager]，
 *   它本身就是这个方向约定，翻页动画也由它负责；
 * - 点击阅读区域进 / 出全屏：全屏时顶栏、页码条和系统状态栏一起收起来，整屏都是乐谱；
 * - 左上角返回：直接回节拍器主页面，不经过"乐谱阅读器主页"；
 * - 翻到第几页会立刻记下来（[onPageChanged]）：退出再进来接着上次那一页看（需求一）。
 *
 * 两种项目的差别只在一页从哪来：PDF 走 [PdfPageRenderer] 渲染，图片走本地图片文件解码。
 * 整个页面用 [key] 包在项目 id 上：换一份乐谱时，页码、全屏状态、渲染好的位图全部重新开始。
 */
@Composable
fun ScoreViewerScreen(
    project: ScoreProject,
    actions: ScoreReaderActions,
    isImporting: Boolean,
    onPageChanged: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    key(project.id) {
        ScoreViewerContent(
            project = project,
            actions = actions,
            isImporting = isImporting,
            onPageChanged = onPageChanged,
            modifier = modifier
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScoreViewerContent(
    project: ScoreProject,
    actions: ScoreReaderActions,
    isImporting: Boolean,
    onPageChanged: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // 菜单展开状态用 remember 而不是 rememberSaveable：离开这一页再回来时菜单应该是收起的。
    var menuExpanded by remember { mutableStateOf(false) }
    /** 是否处于全屏阅读模式。 */
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    /** PDF 项目：打开好的渲染器；还在打开时是 null。图片项目不用它。 */
    var renderer by remember { mutableStateOf<PdfPageRenderer?>(null) }
    /** 这份乐谱打不开了（PDF 文件被删 / 不是合法 PDF）。 */
    var openFailed by remember { mutableStateOf(false) }

    // 阅读顺序：图片项目按排好的页码来（没排序的按导入顺序排在后面），PDF 项目就是页顺序。
    // 同时记下每张图在导入顺序里的位置 —— 本地副本的文件名用的是那个位置，和排序结果无关。
    val imagePages = remember(project.images) {
        ScoreImageOrdering.orderedWithPositions(project.images)
    }

    // 打开 PDF：优先用 App 私有目录里的本地副本，没有就临时从 Uri 复制一份。
    // 这是磁盘 IO（大文件要几百毫秒），所以整段放到 IO 线程，界面先显示一个转圈。
    LaunchedEffect(project.id) {
        if (project.isImageProject) return@LaunchedEffect
        openFailed = false
        renderer = null
        val opened = withContext(Dispatchers.IO) {
            runCatching {
                val file = ScoreFiles.resolvePdf(context, project)
                    ?: error("找不到这份 PDF 的本地文件")
                PdfPageRenderer.open(file)
            }.getOrNull()
        }
        if (opened == null) openFailed = true else renderer = opened
    }

    // 释放渲染器（含文件句柄）：换项目、离开这一页、退出 App 都会走到这里。
    // 注意先取到本地变量再用：onDispose 里读 renderer 会读到"之后"的新值，那就关错对象了。
    val currentRenderer = renderer
    DisposableEffect(currentRenderer) {
        onDispose { currentRenderer?.close() }
    }

    // 一共多少页：图片项目立刻就知道，PDF 要等渲染器打开。
    val pageCount = if (project.isImageProject) imagePages.size else currentRenderer?.pageCount ?: 0

    // 续读：把上次读到的那一页当作起始页（需求一）。PDF 的页数这时还不知道，
    // 所以先用记下的页码起步，等页数出来之后再校正一次。
    val pagerState = rememberPagerState(
        initialPage = project.lastPageIndex.coerceAtLeast(0),
        pageCount = { pageCount }
    )
    LaunchedEffect(pageCount) {
        // 页数一确定就把页码校正到"上次读到的那一页"：
        // 图片项目一开始就知道页数，这一步通常什么都不用做（起始页本来就是对的）；
        // PDF 要等渲染器打开才知道页数，这里把页码补齐，并且顺带处理"内容换过、页数变少"
        // 的情况 —— 不会停在一片空白的页上。
        // 只在页数变化时跑，所以用户自己翻页时不会被这个协程拽回去。
        if (pageCount > 0) {
            val target = project.clampedPageIndex(pageCount)
            if (pagerState.currentPage != target) {
                pagerState.scrollToPage(target)
            }
        }
    }
    // 每次翻页停稳就记一次进度：哪怕用户翻到第 8 页直接杀进程，下次进来还是第 8 页。
    LaunchedEffect(pagerState.currentPage, pageCount) {
        if (pageCount > 0) {
            onPageChanged(pagerState.currentPage)
        }
    }

    // 全屏阅读时把系统状态栏和导航栏也收起来，退出全屏或离开本页时一定还原。
    ApplyImmersiveMode(enabled = fullscreen)

    // 全屏时按系统返回键先退出全屏（此时屏幕上看不到返回按钮，用户第一反应就是按返回键）；
    // 不在全屏时这个处理器是关的，由 MainActivity 统一处理成"回节拍器"。
    BackHandler(enabled = fullscreen) { fullscreen = false }

    // 两个导入流程：阅读页的菜单里也能再导入一份乐谱。
    val startPdfImport = rememberPdfImportFlow(onImport = actions.onImportPdf)
    val startImageImport = rememberImageImportFlow(onImport = actions.onImportImages)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            if (!fullscreen) {
                TopAppBar(
                    title = {
                        Text(
                            text = project.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = actions.onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回节拍器"
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
                            showHomeItem = true,
                            // 「排序图片」只对图片项目有效：PDF 项目下这个入口直接隐藏（需求八）。
                            showSortImages = project.isImageProject
                        )
                    }
                )
            }
        },
        bottomBar = {
            if (!fullscreen) {
                ReaderPageBar(
                    currentPage = pagerState.currentPage + 1,
                    pageCount = pageCount
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when {
                openFailed -> ReaderMessage(
                    title = "这份乐谱打不开了",
                    detail = "文件可能已被删除或移动，请回到乐谱阅读器重新导入一份 PDF。"
                )

                project.isImageProject -> if (imagePages.isEmpty()) {
                    ReaderMessage(
                        title = "这个项目里没有图片",
                        detail = "回到乐谱阅读器重新导入一份乐谱吧。"
                    )
                } else {
                    ScorePager(
                        pagerState = pagerState,
                        onToggleFullscreen = { fullscreen = !fullscreen },
                        modifier = Modifier.fillMaxSize()
                    ) { index ->
                        val (image, importPosition) = imagePages[index]
                        ImagePageView(
                            projectId = project.id,
                            importPosition = importPosition,
                            pageNumber = index + 1,
                            image = image,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                currentRenderer != null -> ScorePager(
                    pagerState = pagerState,
                    onToggleFullscreen = { fullscreen = !fullscreen },
                    modifier = Modifier.fillMaxSize()
                ) { index ->
                    PdfPageView(
                        renderer = currentRenderer,
                        pageIndex = index,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                else -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }

    if (isImporting) {
        ImportingIndicator(text = "正在导入…")
    }
}

/**
 * 左右滑动翻页的区域，PDF 和图片共用。
 *
 * 点一下进 / 出全屏：手势挂在 pager 外面那层 Box 上。Compose 的指针事件会先给里面的
 * pager，再给外面的 Box —— 滑动时 pager 会消费掉事件，所以只有"真正点一下"才会触发切全屏，
 * 翻页手势不受影响。
 */
@Composable
private fun ScorePager(
    pagerState: PagerState,
    onToggleFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
    pageContent: @Composable (index: Int) -> Unit
) {
    // 手势协程只建立一次，用这个始终指向最新的回调。
    val currentOnToggle by rememberUpdatedState(onToggleFullscreen)

    Box(
        modifier = modifier
            // 乐谱四周留一圈浅色底，纸张的白色边缘才看得清。
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .pointerInput(Unit) {
                detectTapGestures { currentOnToggle() }
            }
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            pageSpacing = 12.dp
        ) { pageIndex ->
            pageContent(pageIndex)
        }
    }
}

/**
 * PDF 的一页：按屏幕宽度渲染成位图显示。
 *
 * 渲染宽度就取这一页实际占的宽度（上限由 [PdfPageRenderer] 再兜一次），
 * 于是每个屏幕像素都能对上位图上的一个像素，乐谱上的符头、谱号、力度记号都清楚。
 */
@Composable
private fun PdfPageView(
    renderer: PdfPageRenderer,
    pageIndex: Int,
    modifier: Modifier = Modifier
) {
    ScoreBitmapPage(
        modifier = modifier,
        contentDescription = "第 ${pageIndex + 1} 页",
        failureText = "这一页打不开了",
        renderKey = pageIndex,
        load = { targetWidthPx -> renderer.renderPage(pageIndex, targetWidthPx) }
    )
}

/**
 * 图片项目的一页：把本地副本解码成位图显示。
 *
 * 解码时按这一页实际占的宽度降采样，所以一张 4000 像素宽的照片不会整张读进内存
 * （见 [ScoreBitmapDecoder]），翻页也不会卡。
 */
@Composable
private fun ImagePageView(
    projectId: String,
    importPosition: Int,
    pageNumber: Int,
    image: ScoreImage,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    ScoreBitmapPage(
        modifier = modifier,
        contentDescription = "第 $pageNumber 页",
        failureText = "这一页打不开了",
        renderKey = image.uri,
        load = { targetWidthPx ->
            withContext(Dispatchers.IO) {
                val file = ScoreFiles.resolveImage(context, projectId, importPosition, image)
                    ?: return@withContext null
                ScoreBitmapDecoder.decode(file, targetWidthPx)
            }
        }
    )
}

/**
 * 一页乐谱的通用显示：异步准备一张位图，准备好了显示，没准备好显示转圈，
 * 失败就说明这一页打不开。
 *
 * @param renderKey 决定"什么时候要重新准备"的键（第几页 / 这张图的 Uri）。
 * @param load 真正去准备位图的函数，可以做磁盘 IO。
 */
@Composable
internal fun ScoreBitmapPage(
    contentDescription: String,
    failureText: String,
    renderKey: Any?,
    load: suspend (targetWidthPx: Int) -> Bitmap?,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        val targetWidthPx = with(density) { maxWidth.toPx().toInt() }

        // 换了一页、或这一页的宽度变了（分屏、字体缩放）就重新准备：
        // 状态先回到"准备中"，再由下面的协程把结果填进去。
        var page by remember(renderKey, targetWidthPx) {
            mutableStateOf<PageRender>(PageRender.Loading)
        }
        LaunchedEffect(renderKey, targetWidthPx) {
            val bitmap = load(targetWidthPx)
            page = if (bitmap != null) PageRender.Ready(bitmap) else PageRender.Failed
        }

        // 这一页翻走（或换尺寸重新准备）之后回收旧位图。
        // 放在 DisposableEffect 里而不是协程里：它的回收发生在新的画面已经组合好之后，
        // 绝不会出现"画面还在用、位图已经被回收"的情况；又因为不等 GC，快速连翻几十页
        // 也不会把内存堆满。
        // 注意先取到本地变量再用：onDispose 里读 page 会读到"之后"的新值，那就回收错位图了。
        val currentPage = page
        DisposableEffect(currentPage) {
            onDispose { (currentPage as? PageRender.Ready)?.bitmap?.recycle() }
        }

        when (currentPage) {
            is PageRender.Loading -> CircularProgressIndicator()

            is PageRender.Failed -> Text(
                text = failureText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            is PageRender.Ready -> Image(
                bitmap = currentPage.bitmap.asImageBitmap(),
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                // Fit：整页完整显示在屏幕内，自动居中，绝不裁掉任何一处乐谱。
                contentScale = ContentScale.Fit
            )
        }
    }
}

/** 一页乐谱的三种状态：还在准备 / 准备好了 / 这一页打不开。 */
private sealed interface PageRender {
    data object Loading : PageRender
    data class Ready(val bitmap: Bitmap) : PageRender
    data object Failed : PageRender
}

/** 阅读页底部的页码条：第 3 / 20 页。全屏时整条隐藏（它属于"控制区域"）。 */
@Composable
private fun ReaderPageBar(currentPage: Int, pageCount: Int) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 底部导航栏可能压在页码上，所以这里留出系统栏的高度。
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (pageCount > 0) {
                    "第 $currentPage / $pageCount 页"
                } else {
                    "正在载入…"
                },
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

/** 打不开乐谱时的提示。 */
@Composable
internal fun ReaderMessage(title: String, detail: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
        Text(
            text = detail,
            modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun PdfViewerPreview() {
    MusicPracticeTheme {
        ScoreViewerScreen(
            project = ScoreProject(
                id = "score-preview",
                name = "《Sound Euphonium》",
                kind = ScoreProjectKind.PDF,
                pdfUri = "content://preview/score.pdf",
                lastOpenedAtMillis = 0L
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
            onPageChanged = {}
        )
    }
}
