package com.example.musicpractice.practice

import android.content.Context

/**
 * 练习计时的管理者：管"什么时候开始、什么时候结束、数据怎么落盘"。
 *
 * 它不关心界面，也不关心节拍器怎么发声，只对外提供四件事：
 * [beginSession] 开始一段、[touchSession] 报心跳、[endSession] 结束并保存、
 * [recoverInterruptedSession] 处理上次没来得及收尾的练习。
 *
 * ## 为什么要有"心跳"
 *
 * 用户强杀 App、系统在后台回收进程，这些情况下 onCleared 不会执行，我们不知道练习
 * 到底在哪一刻结束。所以播放期间每一小段时间就往文件里写一次"到这一刻还在练习"，
 * 下次打开时用这个时间作为结束时刻。心跳宁可少记几秒，也绝不多记 —— 把 App 关掉
 * 的那几个小时也算成练习显然是错的。
 */
class PracticeTimeManager(private val store: PracticeTimeStore) {

    /** 给 App 用的构造方式：数据存在 App 私有目录里。 */
    constructor(context: Context) : this(PracticeTimeStore(context))

    private val sessions = ArrayList<PracticeSession>()
    private var active: ActiveSession? = null

    init {
        val loaded = store.read()
        sessions += loaded.sessions
        active = loaded.active
        recoverInterruptedSession()
    }

    /** 开始一段新练习。已经在计时时再调用不会有副作用。 */
    fun beginSession(nowMillis: Long) {
        if (active != null) return
        active = ActiveSession(startMillis = nowMillis, lastSeenMillis = nowMillis)
        persist()
    }

    /**
     * 报告"现在还在练习"。
     *
     * 每 200 毫秒写一次盘太浪费，所以默认按 [HEARTBEAT_INTERVAL_MILLIS] 节流；
     * App 退到后台这类需要立刻固定下来的时刻可以 [force] 一次。
     */
    fun touchSession(nowMillis: Long, force: Boolean = false) {
        val current = active ?: return
        if (!force && nowMillis - current.lastSeenMillis < HEARTBEAT_INTERVAL_MILLIS) return
        active = current.copy(lastSeenMillis = nowMillis)
        persist()
    }

    /**
     * 结束当前练习并保存，返回新写入的记录（跨午夜时会有多条）。
     * 没有正在进行的练习时返回空列表。
     */
    fun endSession(nowMillis: Long): List<PracticeSession> {
        val current = active ?: return emptyList()
        active = null
        val added = addSessions(current.startMillis, nowMillis)
        persist()
        return added
    }

    fun hasActiveSession(): Boolean = active != null

    /** 已完成记录的快照（调用方拿到的是副本，改它不会影响本对象）。 */
    fun sessions(): List<PracticeSession> = ArrayList(sessions)

    fun activeSession(): ActiveSession? = active

    /**
     * 处理"上次没正常收尾"的练习。
     *
     * 构造函数里跑一次。如果文件里还留着正在进行的那一段，说明上次是被强杀或系统回收的：
     * 此时音频早就停了（进程都没了，不可能还在响），所以这次练习按"最后心跳那一刻结束"
     * 直接结算成正式记录，既不丢时间，也不会把关闭期间的时间算进去。
     */
    private fun recoverInterruptedSession() {
        val current = active ?: return
        active = null
        addSessions(
            startMillis = current.startMillis,
            endMillis = maxOf(current.lastSeenMillis, current.startMillis)
        )
        persist()
    }

    /**
     * 把 [startMillis]~[endMillis] 这段练习按日期切开后存起来。
     *
     * 太短的不记：用户手抖连点两下"开始/暂停"会留下一条"0秒"的记录，看着像 bug。
     * 一秒以内的"练习"没有实际意义，直接丢掉。
     */
    private fun addSessions(startMillis: Long, endMillis: Long): List<PracticeSession> {
        if (endMillis - startMillis < MIN_SESSION_MILLIS) return emptyList()
        val segments = PracticeSplitting.splitIntoSessions(startMillis, endMillis)
        sessions += segments
        return segments
    }

    private fun persist() {
        store.write(PracticeTimeData(sessions = ArrayList(sessions), active = active))
    }

    private companion object {
        /** 心跳最小间隔（毫秒）。越小心跳越准，代价是写盘次数越多。 */
        const val HEARTBEAT_INTERVAL_MILLIS = 5_000L

        /** 短于这个时长的练习不记录（多半是误触）。 */
        const val MIN_SESSION_MILLIS = 1_000L
    }
}
