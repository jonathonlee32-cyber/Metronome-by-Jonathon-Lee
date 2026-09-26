package com.example.musicpractice.score

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File

/**
 * 乐谱内容的本地文件管理（PDF 和图片都归这里管）。
 *
 * 用户从系统文件管理器 / 系统图片选择器选中的东西，会在导入时各拷贝一份到 App 私有目录
 * （`filesDir/scores/`）。这么做有三个原因：
 *
 * 1) 系统返回的 content:// 有时并不是"可随机读取"的普通文件（某些网盘客户端就是这样），
 *    而 PdfRenderer 需要能随机定位的文件描述符，直接读它会失败；本地副本一定是普通文件。
 * 2) 系统图片选择器（相册）给的授权只在本次有效，进程被回收后就没了；拷到私有目录之后，
 *    不管授权还在不在、原图有没有被删，乐谱都还能看。
 * 3) 完全离线：不用联网、不上传任何图片或 PDF。
 *
 * 导入时选中的 Uri 仍然老老实实存进项目里（需求要求保存 Uri 列表）；本地副本万一被清掉，
 * 还能拿它重新拷一份。所有函数都可能做磁盘 IO，请在后台线程调用。
 */
object ScoreFiles {

    private const val TAG = "ScoreFiles"
    private const val DIR_NAME = "scores"

    /** App 私有目录里放乐谱副本的文件夹。 */
    fun libraryDir(context: Context): File = File(context.filesDir, DIR_NAME)

    /** PDF 项目的本地副本路径（不保证文件已经存在）。 */
    fun pdfFile(context: Context, projectId: String): File =
        File(libraryDir(context), "$projectId.pdf")

    /**
     * 图片项目里第 [ordinal] 张图的本地副本路径（不保证文件已经存在）。
     *
     * 文件名里带的是**导入顺序**的位置，和排序结果无关：排序改的是数据里的页码，
     * 不会去改名、搬动文件，所以排来排去也不会产生重复文件。
     * 后缀用 .img 是因为原图可能是 jpg / png / webp / heic，
     * 解码时按内容判断格式，不依赖扩展名。
     */
    fun imageFile(context: Context, projectId: String, ordinal: Int): File =
        File(libraryDir(context), "$projectId-img-${ordinal.toString().padStart(3, '0')}.img")

    /**
     * 把用户选中的 PDF 拷贝进 App 私有目录，返回拷贝好的文件；
     * 拷贝失败（空间不足、Uri 失效、文件被删）返回 null。
     */
    fun importPdf(context: Context, projectId: String, uri: Uri): File? {
        val target = pdfFile(context, projectId)
        return if (copyFromUri(context, uri, target)) target else null
    }

    /**
     * 把用户选中的第 [ordinal] 张图片拷贝进 App 私有目录，返回拷贝好的文件；
     * 拷贝失败返回 null（这一页以后会显示"打不开"，但不会影响整个项目）。
     */
    fun importImage(context: Context, projectId: String, ordinal: Int, uri: Uri): File? {
        val target = imageFile(context, projectId, ordinal)
        return if (copyFromUri(context, uri, target)) target else null
    }

    /**
     * 找出这个 PDF 项目此刻可以用来渲染的文件；实在拿不到时返回 null（界面会提示文件不可用）。
     *
     * 顺序：项目记录里的本地副本路径 → 私有目录里同名文件 → 缓存目录里之前从 Uri 拷的副本
     * → 现从 Uri 拷一份到缓存目录。
     */
    fun resolvePdf(context: Context, project: ScoreProject): File? {
        project.localPath?.let { path ->
            val file = File(path)
            if (file.isUsableFile()) return file
        }

        val libraryCopy = pdfFile(context, project.id)
        if (libraryCopy.isUsableFile()) return libraryCopy

        val uri = project.pdfUri ?: return null
        val cacheCopy = File(context.cacheDir, "score_${project.id}.pdf")
        if (cacheCopy.isUsableFile()) return cacheCopy

        return if (copyFromUri(context, Uri.parse(uri), cacheCopy)) cacheCopy else null
    }

    /**
     * 找出图片项目里第 [ordinal] 张图此刻可以用来显示的文件；拿不到时返回 null。
     *
     * 顺序和 PDF 一样：记录里的本地副本 → 私有目录里同名文件 → 缓存副本 → 现从 Uri 拷一份。
     */
    fun resolveImage(
        context: Context,
        projectId: String,
        ordinal: Int,
        image: ScoreImage
    ): File? {
        image.localPath?.let { path ->
            val file = File(path)
            if (file.isUsableFile()) return file
        }

        val libraryCopy = imageFile(context, projectId, ordinal)
        if (libraryCopy.isUsableFile()) return libraryCopy

        val cacheCopy = File(context.cacheDir, "score_${projectId}_img_$ordinal.img")
        if (cacheCopy.isUsableFile()) return cacheCopy

        return if (copyFromUri(context, Uri.parse(image.uri), cacheCopy)) cacheCopy else null
    }

    /**
     * 删掉一个项目在 App 私有目录里留下的全部本地副本（需求五：删除项目时删掉项目关联数据）。
     *
     * 只删两处**App 自己造出来的**文件：
     * - `filesDir/scores/` 里名字以这个项目 id 开头的副本（PDF 副本 + 每一张图的副本）；
     * - `cacheDir` 里从 Uri 现拷的那几份临时副本。
     *
     * 用户原来那份 PDF / 图片文件不在这里，也永远不会被这个函数碰到 ——
     * 需求里特别强调"暂时不要删除用户原始文件，只删除 App 内项目记录"。
     */
    fun deleteProjectFiles(context: Context, projectId: String) {
        deleteFilesWithPrefix(libraryDir(context), "$projectId.")
        deleteFilesWithPrefix(libraryDir(context), "$projectId-")
        deleteFilesWithPrefix(context.cacheDir, "score_$projectId")
    }

    /** 删掉 [dir] 里所有名字以 [prefix] 开头的文件（就是一个项目自己的那些副本）。 */
    private fun deleteFilesWithPrefix(dir: File, prefix: String) {
        val targets = dir.listFiles { file -> file.isFile && file.name.startsWith(prefix) }
        if (targets.isNullOrEmpty()) return
        for (target in targets) {
            // 单个文件删不掉（被别的进程占着）不该影响别的文件，也不该让删除操作报错。
            if (!target.delete()) {
                Log.w(TAG, "删不掉本地副本：${target.name}")
            }
        }
    }

    /**
     * 把 [uri] 指向的内容拷到 [target]。
     *
     * 先写临时文件、成功后改名：中途失败（空间不足、进程被杀）时不会留下一个半截的文件，
     * 那比"没有副本"更糟糕 —— 半截文件看起来存在，显示时才会报错。
     */
    private fun copyFromUri(context: Context, uri: Uri, target: File): Boolean {
        return try {
            val dir = target.parentFile
            if (dir != null && !dir.isDirectory && !dir.mkdirs()) {
                Log.w(TAG, "建不了乐谱目录：$dir")
                return false
            }
            val input = context.contentResolver.openInputStream(uri)
            if (input == null) {
                Log.w(TAG, "打开的输入流为空：$uri")
                return false
            }
            input.use { source ->
                val temp = File(target.parentFile, "${target.name}.tmp")
                temp.outputStream().use { sink -> source.copyTo(sink) }
                if (temp.renameTo(target)) {
                    true
                } else {
                    temp.delete()
                    false
                }
            }
        } catch (error: Exception) {
            // 有的 Uri 取不到读取权限（用户取消了授权、文件被移走），这里只记录一下，
            // 由调用方决定怎么提示，不往上抛异常。
            Log.w(TAG, "拷贝失败：$uri", error)
            false
        }
    }

    private fun File.isUsableFile(): Boolean = isFile && length() > 0L
}
