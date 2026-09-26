package com.example.musicpractice.score

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * PDF 页面的渲染器，直接用系统自带的 [PdfRenderer]。
 *
 * 为什么不用第三方 PDF 库：系统 API 从 Android 5.0（本项目的最低版本）就有，
 * 不需要引入任何 SDK、体积为零、完全离线，也不存在"PDF 被传到哪儿去"的问题。
 *
 * PdfRenderer 有两条使用限制，这个类都处理掉了：
 * 1) 不是线程安全的，而且同一时刻只允许打开一页 —— 所有渲染和关闭都在这把 [lock] 里串行执行；
 * 2) 渲染是阻塞的原生调用，一页几十毫秒 —— 所以整段代码都跑在 IO 线程上，不会卡界面。
 *
 * 用完必须 [close]：它持有的文件句柄不释放，用户就没法覆盖或删除那个 PDF 文件。
 */
class PdfPageRenderer private constructor(
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer
) : Closeable {

    /**
     * 渲染和关闭共用的一把锁。
     *
     * 用普通的 [ReentrantLock] 而不是协程的 Mutex，是为了让 [close] 也能拿到锁：
     * 关闭发生在主线程（界面离开这一页），而渲染在 IO 线程上，两者必须互斥 ——
     * 否则用户翻到一半按返回，就会出现"一边渲染一边把渲染器关掉"的竞态。
     * 代价是关闭时主线程最多等一页渲染的时间（几十毫秒），这个量级完全感觉不到。
     */
    private val lock = ReentrantLock()

    /** 是否已经关闭。关闭之后进来的渲染请求直接放弃（它们来自已经被取消的协程）。 */
    @Volatile
    private var closed = false

    /** 这份 PDF 一共多少页。 */
    val pageCount: Int get() = renderer.pageCount

    /**
     * 把第 [index] 页（从 0 开始）渲染成宽度约 [targetWidthPx] 的位图；页码越界时返回 null。
     *
     * 宽度按页面原始宽高比换算高度，所以位图和纸面完全同形，不会被拉变形。
     * 调用方负责在用完位图后回收它（[Bitmap.recycle]）。
     */
    suspend fun renderPage(index: Int, targetWidthPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        lock.withLock {
            // 已经关掉（用户翻走或退出了这一页）就不再渲染，也不报错。
            if (closed) return@withLock null
            // 渲染失败（页数据损坏、文件被截断）只影响这一页，不该让整个阅读页崩掉。
            runCatching {
                if (index < 0 || index >= renderer.pageCount) return@runCatching null
                renderer.openPage(index).use { page ->
                    if (page.width <= 0 || page.height <= 0) return@use null
                    val width = targetWidthPx.coerceIn(MIN_RENDER_WIDTH_PX, MAX_RENDER_WIDTH_PX)
                    val scale = width.toFloat() / page.width
                    val height = (page.height * scale).toInt().coerceAtLeast(1)

                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    // 乐谱是白纸黑字：先把底色铺白，页面上没有内容的地方就不会是黑的。
                    bitmap.eraseColor(Color.WHITE)
                    // RENDER_MODE_FOR_DISPLAY：按屏幕观看优化，文字和符头都比默认模式清楚。
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                }
            }.onFailure { error ->
                Log.w(TAG, "渲染第 ${index + 1} 页失败", error)
            }.getOrNull()
        }
    }

    override fun close() {
        lock.withLock {
            if (closed) return
            closed = true
            // 关闭时任何一步失败都不该影响界面退出，所以每个调用单独兜住异常。
            runCatching { renderer.close() }
            runCatching { descriptor.close() }
        }
    }

    companion object {
        private const val TAG = "PdfPageRenderer"

        /** 太窄的屏幕也至少渲染到这个宽度，免得放大看时糊成一片。 */
        private const val MIN_RENDER_WIDTH_PX = 480

        /**
         * 渲染宽度上限。
         *
         * 一页 A4 乐谱按 1600px 宽渲染约 1600×2100×4B ≈ 13MB；翻页时相邻页也会被渲染，
         * 再往上加就会在小内存手机上顶到上限。1600px 已经超过绝大多数手机屏幕的物理宽度，
         * 所以"高清显示乐谱"这个要求是满足的。
         */
        private const val MAX_RENDER_WIDTH_PX = 1600

        /** 打开一个本地 PDF 文件。文件不存在或不是合法 PDF 时抛异常，由调用方处理。 */
        fun open(file: File): PdfPageRenderer {
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            return try {
                PdfPageRenderer(descriptor, PdfRenderer(descriptor))
            } catch (error: Exception) {
                // 打开失败（例如文件不是 PDF）时把已经拿到的文件句柄还回去，否则会一直占着它。
                runCatching { descriptor.close() }
                throw error
            }
        }
    }
}
