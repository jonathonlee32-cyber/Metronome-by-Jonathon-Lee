package com.example.musicpractice.recording

import android.content.Context
import com.example.musicpractice.pitch.PitchAnalysis

/**
 * 录音库的管理者：管"有哪些录音、叫什么名字、听到哪儿了"。
 *
 * 它不关心界面，也不关心音频怎么播，只对外提供这几件事：
 * [all] 全部录音（按录音时间倒序）、[find] 按 id 取一条、[nextName] 生成下一个自动编号、
 * [add] 新建一条、[rename] 重命名、[delete] 删除、[savePosition] 记住播放位置。
 *
 * 数据存在哪儿由 [store] 决定：App 里是 Room 数据库（[RoomRecordingStore]），
 * 单元测试里是一个内存实现。
 *
 * 每次改动都是"先改内存里那份快照，再把改动立刻存下去"，而且是同步的：
 * 方法返回的时候数据已经在数据库里了，哪怕进程随即被杀也不会丢。
 * 界面读的永远是内存快照，落盘只为了让数据在 App 关掉之后还在。
 */
class RecordingRepository(private val store: RecordingStore) {

    /** 给 App 用的构造方式：数据存在 App 私有目录里的 Room 数据库里。 */
    constructor(context: Context) : this(RoomRecordingStore(context))

    private var recordings: List<Recording> = store.read().sortedByRecent()

    /**
     * 当前打开着的那段录音的音准分析（内存缓存）。
     *
     * 只缓存**一段**：一段几十分钟的录音有上万个时间点，把所有分析结果都留在内存里
     * 没有意义也不安全。播放页打开哪段录音就加载哪一段，换一段就把上一段丢掉。
     */
    private var cachedAnalysis: PitchAnalysis? = null

    /** 全部录音，最新的排在最前面（需求六）。 */
    fun all(): List<Recording> = recordings

    /** 按 id 找一条录音；找不到返回 null。 */
    fun find(id: String?): Recording? =
        if (id == null) null else recordings.firstOrNull { it.id == id }

    /**
     * 生成下一个可用的自动编号（`yyyymmdd-xxx`）。
     *
     * [taken] 由调用方补充"磁盘上已经存在的文件名"（见 [RecordingFiles.existingNames]）：
     * 数据库记录 + 磁盘文件一起算，任何情况下都不会起一个会覆盖已有音频的名字。
     */
    fun nextName(createdMillis: Long, taken: Collection<String> = emptyList()): String =
        RecordingNaming.nextName(createdMillis, recordings.map { it.name } + taken)

    /** 加入一条新录音（录音结束、文件已经写好之后调）。 */
    fun add(recording: Recording) {
        recordings = (recordings.filterNot { it.id == recording.id } + recording).sortedByRecent()
        store.upsert(recording)
    }

    /**
     * 重命名（需求六：长按录音 → 重命名）。
     *
     * 只改数据库里的显示名，**不动磁盘上的文件名** —— 音频文件是用自动编号命名的，
     * 重命名不该搬动文件，也就不可能出现"改到一半文件找不到了"。名字为空或没变化时什么都不做。
     *
     * @return 重命名后的录音；id 不在库里或新名字无效时返回 null。
     */
    fun rename(id: String, newName: String): Recording? {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return null
        val current = find(id) ?: return null
        if (current.name == trimmed) return current

        val renamed = current.copy(name = trimmed)
        recordings = recordings
            .map { if (it.id == id) renamed else it }
            .sortedByRecent()
        store.rename(id, trimmed)
        return renamed
    }

    /**
     * 删除一条录音，返回被删掉的那条；id 不在库里时返回 null。
     *
     * 返回被删的记录是为了让调用方知道要清理哪个音频文件（文件是 App 自己录的，
     * 删掉记录之后留着它就是一段谁也看不到的垃圾）。
     */
    fun delete(id: String): Recording? {
        val removed = find(id) ?: return null
        recordings = recordings.filterNot { it.id == id }
        store.delete(id)
        // 这段录音的分析结果由数据库的外键级联删掉，内存里那份也一起丢掉。
        if (cachedAnalysis?.recordingId == id) cachedAnalysis = null
        return removed
    }

    // ---------------- 音准分析（v5.1） ----------------

    /** 这段录音有没有分析结果（需求六：有就直接显示结果，不再显示"分析音准"）。 */
    fun hasAnalysis(recordingId: String): Boolean =
        cachedAnalysis?.recordingId == recordingId || store.hasAnalysis(recordingId)

    /**
     * 读一段录音的分析结果（带每个时间点）。
     *
     * 已经缓存过就直接返回，避免每次进播放页都从数据库读上万个点。
     */
    fun analysis(recordingId: String): PitchAnalysis? {
        cachedAnalysis?.let { if (it.recordingId == recordingId) return it }
        val loaded = store.readAnalysis(recordingId) ?: return null
        cachedAnalysis = loaded
        return loaded
    }

    /**
     * 保存（覆盖）一段录音的分析结果。
     *
     * 分析结束时、以及用户改了 A4 基准之后（结果要重算）都会调它；两处走同一条路，
     * 所以"数据库里的结果"和"屏幕上显示的结果"永远是同一份。
     */
    fun saveAnalysis(analysis: PitchAnalysis) {
        store.saveAnalysis(analysis)
        cachedAnalysis = analysis
    }

    /** 丢掉内存缓存（离开播放页时用，下次进来重新从数据库读）。 */
    fun releaseAnalysisCache() {
        cachedAnalysis = null
    }

    /**
     * 记住"这段录音听到哪儿了"（需求七：退出播放页保存当前位置）。
     *
     * 播放中每秒会调一次，所以只更新这一行的一个字段，不整行重写。
     */
    fun savePosition(id: String, positionMillis: Long) {
        val current = find(id) ?: return
        val clamped = positionMillis.coerceAtLeast(0L)
        if (current.lastPositionMillis == clamped) return
        recordings = recordings.map {
            if (it.id == id) it.copy(lastPositionMillis = clamped) else it
        }
        store.savePosition(id, clamped)
    }

    /** 按录音时间倒序：最新录的排在最前面。 */
    private fun List<Recording>.sortedByRecent(): List<Recording> =
        sortedWith(compareByDescending<Recording> { it.createdTimeMillis }.thenByDescending { it.name })

    companion object {
        /**
         * 生成录音 id。
         *
         * 和乐谱项目一样直接用"录音结束那一刻的毫秒数"：天然唯一、不依赖随机数，
         * 看日志时还能一眼看出这段录音是什么时候录的。
         */
        fun newRecordingId(atMillis: Long): String = "rec-$atMillis"
    }
}
