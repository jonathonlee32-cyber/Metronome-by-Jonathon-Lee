package com.example.musicpractice.recording

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File

/**
 * 录音文件的本地存放（需求五：保存到本地存储）。
 *
 * 所有录音都放在 App 私有目录的 `filesDir/recordings/` 里：
 * - 不需要申请任何存储权限（Android 10 起分区存储、更早的版本用私有目录也一样免权限）；
 * - 别的 App 看不到、也不会被相册或文件管理器扫到；
 * - 完全离线，不上传云端；
 * - 用户卸载 App 时随 App 一起清掉。
 *
 * 文件名就是自动编号出来的名字（`20260926-001.m4a`），名字和文件一一对应：
 * **重命名只改数据库里的显示名，不动磁盘上的文件名**，所以改名永远不会弄丢音频。
 */
object RecordingFiles {

    private const val TAG = "RecordingFiles"
    private const val DIR_NAME = "recordings"

    /** 放录音的文件夹（不保证已经存在）。 */
    fun dir(context: Context): File = File(context.filesDir, DIR_NAME)

    /** 一段录音对应的文件（不保证已经存在）。 */
    fun file(context: Context, name: String): File =
        File(dir(context), RecordingNaming.fileName(name))

    /**
     * 确保录音目录存在，返回它；建不出来（极少见）时返回 null。
     *
     * 开始录音之前会调一次：MediaRecorder 不会替我们建目录。
     */
    fun ensureDir(context: Context): File? {
        val dir = dir(context)
        if (dir.isDirectory) return dir
        if (dir.mkdirs()) return dir
        Log.w(TAG, "建不了录音目录：$dir")
        return dir.takeIf { it.isDirectory }
    }

    /** 一段录音文件的 `file://` Uri（存进数据库，需求五要求同时保存路径和 Uri）。 */
    fun uriOf(file: File): String = Uri.fromFile(file).toString()

    /**
     * 目录里已经存在的录音名字（去掉扩展名）。
     *
     * 自动编号时把它算进去，就算数据库里的记录被清过、文件还躺在目录里，
     * 新录音也不会起一个会覆盖它的名字。
     */
    fun existingNames(context: Context): Set<String> {
        val files = dir(context).listFiles { file -> file.isFile } ?: return emptySet()
        return files.mapNotNull { RecordingNaming.nameFromFileName(it.name) }.toSet()
    }

    /**
     * 删掉一段录音的文件（需求六：长按删除）。
     *
     * 这里删的是**App 自己录出来的**文件，它没有第二份（不像是用户导入的 PDF / 图片，
     * 那些原始文件从不归 App 管）。所以删除录音时，文件和记录一起清掉，不留垃圾。
     */
    fun delete(context: Context, recording: Recording) {
        val target = File(recording.filePath)
        if (!target.isFile) return
        if (!target.delete()) {
            Log.w(TAG, "删不掉录音文件：${target.name}")
        }
    }
}
