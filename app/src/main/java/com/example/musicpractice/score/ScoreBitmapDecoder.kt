package com.example.musicpractice.score

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File

/**
 * 把本地的乐谱图片解码成位图。
 *
 * 两件事必须自己做，不能直接 `BitmapFactory.decodeFile`：
 *
 * 1) **按需要的大小解码**。手机拍的一张乐谱照片可能有 4000×3000 像素（约 48MB 位图），
 *    直接整张读进内存，翻两三页就会 OutOfMemory。所以先用 `inJustDecodeBounds` 读出尺寸，
 *    再按 2 的幂次降采样（`inSampleSize`）到目标宽度附近 —— 缩略图要 300 像素就给 300 像素，
 *    整页显示要屏幕宽就给屏幕宽。
 * 2) **失败不崩**。图片文件可能被截断、可能是系统认不出的格式，这时返回 null，
 *    由界面显示"这一页打不开"，而不是让整个阅读页挂掉。
 */
object ScoreBitmapDecoder {

    private const val TAG = "ScoreBitmapDecoder"

    /**
     * 解码 [file]，最宽不超过 [targetWidthPx]（会向下取到 2 的幂次倍采样，不会把图画花）。
     * 文件不存在、格式不认识、内存不够时返回 null。
     */
    fun decode(file: File, targetWidthPx: Int): Bitmap? {
        if (!file.isFile || file.length() <= 0L) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, targetWidthPx)
                // ARGB_8888：乐谱以线条和文字为主，需要完整的灰度层次，画质优先。
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeFile(file.absolutePath, options)
        } catch (error: OutOfMemoryError) {
            // 极端情况（超大图 + 内存紧张）下宁可让这一页显示不出来，也不要让 App 崩掉。
            Log.w(TAG, "解码图片时内存不足：${file.name}")
            null
        } catch (error: Exception) {
            Log.w(TAG, "解码图片失败：${file.name}", error)
            null
        }
    }

    /**
     * 算出 2 的幂次降采样倍数。
     *
     * 一直减半到"再减就比目标宽度还小"为止：解码结果**始终不小于目标宽度**（不会发虚），
     * 又是 2 的幂次（BitmapFactory 唯一支持的降采样方式，不会有奇怪的插值）。
     *
     * 举例：4000 像素宽的照片，目标是手机屏幕的 1080 像素 → 取样 2，解码成 2000 像素；
     * 目标是缩略图的 330 像素 → 取样 16，解码成 250 像素。
     */
    private fun sampleSizeFor(width: Int, targetWidthPx: Int): Int {
        val target = targetWidthPx.coerceAtLeast(1)
        var sample = 1
        var current = width
        while (current / 2 >= target) {
            current /= 2
            sample *= 2
        }
        return sample
    }
}
