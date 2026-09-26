package com.example.musicpractice.recording

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 录音文件名的自动编号（需求五）。
 *
 * 规则：`yyyymmdd-xxx`，例如 2026 年 9 月 26 日录的第一段是 `20260926-001`，
 * 第二段是 `20260926-002`，第二天重新从 `001` 开始。
 *
 * 这里的规则全是纯函数（不碰磁盘、不碰数据库、不碰 Android），所以可以直接用电脑上的
 * 单元测试验证"跨天""跳号""连录 1000 段"这些边界情况（见 RecordingNamingTest）。
 */
object RecordingNaming {

    /** 日期前缀的格式。 */
    private const val DATE_PATTERN = "yyyyMMdd"

    /** 序号的位数：不够三位前面补 0（`001`）。 */
    private const val SEQUENCE_DIGITS = 3

    /** 录音文件的扩展名：MPEG-4 容器里装 AAC 音频。 */
    const val FILE_EXTENSION = "m4a"

    /**
     * 取某一时刻的日期前缀（`20260926`）。
     *
     * [timeZone] 留成参数是为了让测试能固定时区：本机时区变了，前缀也跟着变，
     * 用户看到的"今天的编号"永远和手机日历一致。
     */
    fun datePrefix(
        atMillis: Long,
        timeZone: TimeZone = TimeZone.getDefault()
    ): String {
        val format = SimpleDateFormat(DATE_PATTERN, Locale.US)
        format.timeZone = timeZone
        return format.format(Date(atMillis))
    }

    /**
     * 生成这一时刻的下一个可用名字。
     *
     * [taken] 是**所有已经被占用的名字**（数据库里存着的名字 + 磁盘上已经存在的文件名），
     * 从 `001` 开始往后找第一个没被占用的序号。这样两个好处：
     * - 绝不会重名，也就绝不会覆盖掉一段已经录好的录音；
     * - 用户删掉中间某一段之后，空出来的号会被重新用上，编号不会越跳越大。
     *
     * @param createdMillis 这一刻的时间（挂钟毫秒）。
     * @param taken 已经占用的名字集合（不带扩展名，例如 `20260926-001`）。
     */
    fun nextName(
        createdMillis: Long,
        taken: Collection<String>,
        timeZone: TimeZone = TimeZone.getDefault()
    ): String {
        val prefix = datePrefix(createdMillis, timeZone)
        var sequence = 1
        while (true) {
            val candidate = name(prefix, sequence)
            if (!taken.contains(candidate)) return candidate
            sequence += 1
        }
    }

    /** 把日期前缀和序号拼成一个名字：`20260926` + `001` = `20260926-001`。 */
    fun name(prefix: String, sequence: Int): String {
        val padded = sequence.toString().padStart(SEQUENCE_DIGITS, '0')
        return "$prefix-$padded"
    }

    /** 这个名字对应的磁盘文件名：`20260926-001` → `20260926-001.m4a`。 */
    fun fileName(name: String): String = "$name.$FILE_EXTENSION"

    /**
     * 从一个磁盘文件名反推它的名字（去掉扩展名）；不是录音文件（后缀不对）时返回 null。
     *
     * 用来把目录里已经存在的文件算进 [nextName] 的 [taken] 里：
     * 万一数据库里的记录被清掉了、文件还在，也不会给新录音起一个会覆盖它的名字。
     */
    fun nameFromFileName(fileName: String): String? {
        val suffix = ".$FILE_EXTENSION"
        return if (fileName.endsWith(suffix)) fileName.dropLast(suffix.length) else null
    }
}
