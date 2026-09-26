package com.example.musicpractice.recording

/**
 * 一段录音（需求四~七）。
 *
 * 它是这个模块里最核心的数据结构：一条记录 = 一次录音的文件在哪、叫什么名字、什么时候录的、
 * 有多长、上次听到哪儿了。App 里其它地方（录音页、最近录音页、播放页）全都只认这个模型，
 * 数据库的表结构藏在 [RecordingDatabase] 后面。
 *
 * 录音文件本身只存在本机 App 私有目录里（`filesDir/recordings/`）：
 * 不需要任何存储权限、不上传云端、卸载 App 才会清掉。
 */
data class Recording(
    /** 唯一标识，例如 `rec-1774000000000`（录音结束那一刻的毫秒数）。 */
    val id: String,
    /**
     * 显示用的名字，默认按需求规定自动编号成 `yyyymmdd-xxx`（例如 `20260926-001`）。
     * 重命名改的就是它 —— 磁盘上的文件名不变，这样"改名"永远不可能弄丢音频。
     */
    val name: String,
    /** 录音文件在 App 私有目录里的绝对路径。 */
    val filePath: String,
    /** 同一个文件的 `file://` Uri（需求五要求同时保存"文件路径/Uri"）。 */
    val fileUri: String? = null,
    /** 录音结束（创建这条记录）的时间，最近录音列表按它倒序排。 */
    val createdTimeMillis: Long,
    /** 录音时长（毫秒）。 */
    val durationMillis: Long = 0L,
    /** 上次听到哪儿了（毫秒）；下次进播放页从这里接着放（需求七）。 */
    val lastPositionMillis: Long = 0L
) {

    /** 时长文案，例如 `05:40`。 */
    val durationLabel: String get() = RecordingFormat.clock(durationMillis)

    /** 播放页进度文案，例如 `01:25 / 05:40`。 */
    val progressLabel: String
        get() = RecordingFormat.progress(lastPositionMillis, durationMillis)

    /**
     * 把"上次听的位置"夹进合法范围。
     *
     * 时长字段是录音结束时按秒表记下来的，和文件真实时长可能有几十毫秒的出入；
     * 播放器打开文件后会拿真实时长再校正一次（见 [RecordingPlayer]）。
     */
    fun clampedPosition(durationMillis: Long): Long {
        if (durationMillis <= 0L) return 0L
        return lastPositionMillis.coerceIn(0L, durationMillis)
    }
}
