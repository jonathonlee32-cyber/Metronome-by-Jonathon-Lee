package com.example.musicpractice.tuner

import kotlin.math.abs

/**
 * 音准历史：最近一段时间里"检测到的音分偏差"。
 *
 * 这个类只管数据，不碰界面、不碰音频线程，也不含任何 Android 依赖：
 * 调音器页每收到一帧检测结果就调用一次 [onReading]，每画一帧再调用一次 [advance]，
 * 剩下的（把采样点连成一条连续的白色曲线、标音名）由界面负责。
 *
 * ## 换音时的防尖峰处理
 *
 * 音分是相对于"最近的那个音"算的，所以同一个物理音高落在两个音的交界处时，
 * 旧音上是 +50、新音上是 -50 —— 显示位置一个贴顶、一个贴底。换音那一瞬间检测结果本来就
 * 不稳，如果把这些帧原样画出来，轨迹就会突然冒出一个巨大的 V 型尖峰（不是演奏者的真实变化）。
 *
 * 这里用两步处理掉它，且**只作用于换音瞬间**，同一音符内的真实音高滑动（-5 → +5 → +25 音分）
 * 依旧是逐帧原样记录：
 *
 * 1. **稳定确认**：音名变化后先当"候选音"，连续 [NOTE_SWITCH_CONFIRM_FRAMES] 帧都是同一个
 *    新音名才正式换音。候选期间的帧既不记点也不建标签，所以一帧、两帧的误识别
 *    （C4 → D4 → C4）不会留下任何痕迹。
 * 2. **过渡修剪**：确认换音时，把旧音符末尾那几个贴着 ±50 边界（|cents| ≥ [TRANSITION_EDGE_CENTS]）
 *    的点删掉，最多回看 [TRANSITION_TRIM_WINDOW_MS]。这样曲线是从"旧音符最后一个稳定位置"
 *    直接接到"新音符第一个稳定位置"，只有一段自然的斜坡，不会先冲到顶再弹回底。
 *
 * **时间轴是"有效检测时间"**：只有检测到有效音高时 [advance] 才会让 [nowMs] 前进，
 * 所以进入 NOISE 时整条轨迹（白色曲线和音名标签）原地停住，恢复检测后再从右边缘继续 ——
 * 这也正是需求里要求的滚动方式（按时间走，而不是按"收到几帧"走）。
 *
 * 内存上限：轨迹点和音符标记都放在固定大小的环形缓冲里（[maxPoints] / [maxMarkers]），
 * 再加上按 [windowMs] 时间窗口的裁剪，长时间开着也不会持续增长。
 * 环形缓冲用的是原始类型数组，所以采样过程中不会产生新对象。
 */
class PitchHistory(
    /** 时间窗口：只保留最近这么久（毫秒）的轨迹，默认 [DEFAULT_WINDOW_MS]。 */
    val windowMs: Long = DEFAULT_WINDOW_MS,
    /** 最多同时保留多少个轨迹点。 */
    private val maxPoints: Int = DEFAULT_MAX_POINTS,
    /** 最多同时保留多少个音符变化标记。 */
    private val maxMarkers: Int = DEFAULT_MAX_MARKERS
) {

    // ---- 轨迹点：环形缓冲，两个原始数组 ----
    private val pointTimes = LongArray(maxPoints)
    private val pointCents = IntArray(maxPoints)
    private var pointHead = 0
    private var pointSize = 0

    // ---- 音符变化标记：除了时间和音名，还记下"这个点当时的音分"，标签要画在对应点的正上方 ----
    private val markerTimes = LongArray(maxMarkers)
    private val markerCents = IntArray(maxMarkers)
    private val markerNames = Array(maxMarkers) { "" }
    private var markerHead = 0
    private var markerSize = 0

    // ---- 换音确认状态 ----
    /** 正在观察的新音名（还没确认的"候选音"）。 */
    private var candidateNote: String? = null

    /** 候选音名已经连续出现多少帧（NOISE 不计入、也不会把它清零）。 */
    private var candidateFrames = 0

    /** 上一次看到候选音名的时间。隔得太久就重新数，避免很久以前的偶发帧被当成证据。 */
    private var candidateLastFrameMs = 0L

    /** 轨迹时间轴上的"现在"（毫秒）。只在检测到有效音高时前进，NOISE 时不动。 */
    var nowMs: Long = 0L
        private set

    /**
     * 当前正在持续的音名。
     *
     * NOISE **不会**清掉它：所以"吹 C4 → 停一下（NOISE）→ 再吹 C4"不算换音，不会重复出现 C4 标签。
     */
    var currentNote: String? = null
        private set

    /** 轨迹点个数。[pointTime] / [pointCents] 的下标 0 是最早的一个点。 */
    val pointCount: Int get() = pointSize

    /** 第 [index] 个轨迹点在时间轴上的时刻（毫秒）。 */
    fun pointTime(index: Int): Long = pointTimes[(pointHead + index) % maxPoints]

    /** 第 [index] 个轨迹点的音分偏差。 */
    fun pointCents(index: Int): Int = pointCents[(pointHead + index) % maxPoints]

    /** 音符变化标记的个数。[markerTime] 等的下标 0 是最早的一个标记。 */
    val markerCount: Int get() = markerSize

    /** 第 [index] 个音名标签出现的时间。 */
    fun markerTime(index: Int): Long = markerTimes[(markerHead + index) % maxMarkers]

    /** 第 [index] 个音名标签对应的音分（标签要画在轨迹这个位置的上方）。 */
    fun markerCents(index: Int): Int = markerCents[(markerHead + index) % maxMarkers]

    /** 第 [index] 个音名标签的音名，例如 "C4"。 */
    fun markerName(index: Int): String = markerNames[(markerHead + index) % maxMarkers]

    /**
     * 处理一帧检测结果。
     *
     * 有效音符（[TunerReading.isReliable] 且匹配到音）且音名没变：在时间轴的当前位置加一个轨迹点。
     *
     * 音名和当前确认的音不一样（或者还没确认过任何音）：这一帧先不记，进入"候选 → 确认"流程，
     * 连续 [NOTE_SWITCH_CONFIRM_FRAMES] 帧都是这个新音名、并且不再贴着 ±50 音分边界，
     * 才正式换音：清掉旧音符末尾的边界异常点，再从新音符的稳定位置开始记点、建标签。
     *
     * NOISE：什么都不做 —— 不加点、不动时间轴、也不清空 [currentNote]。
     */
    fun onReading(reading: TunerReading) {
        val match = reading.match
        if (!reading.isReliable || match == null) return

        if (match.name == currentNote) {
            // 音名没变：照常逐帧记录，真实的音高滑动一点都不打折。
            candidateNote = null
            candidateFrames = 0
            candidateLastFrameMs = 0L
            addPoint(match.centsRounded)
            return
        }

        // 音名变了（或者还没确认过任何音）：先当候选，等它稳定。
        // 候选帧之间隔得太久（比如中间停了一下没吹）就重新数：观察必须是"最近连续的"。
        val continuing = match.name == candidateNote &&
            nowMs - candidateLastFrameMs <= NOTE_SWITCH_CANDIDATE_GAP_MS
        if (continuing) {
            candidateFrames++
        } else {
            candidateNote = match.name
            candidateFrames = 1
        }
        candidateLastFrameMs = nowMs

        // "稳定"还要求不在 ±50 边界上：那里正是换音瞬间读数来回跳的地方。
        val stable = abs(match.centsRounded) < TRANSITION_EDGE_CENTS
        val enoughFrames = candidateFrames >= NOTE_SWITCH_CONFIRM_FRAMES && stable
        // 万一演奏者确实一直吹得很偏（比如持续 +48 音分）：等够时间也照样确认，
        // 不能因为"贴边"就永远不记录这个音。
        val waitedLongEnough = candidateFrames >= NOTE_SWITCH_CONFIRM_TIMEOUT_FRAMES
        if (!enoughFrames && !waitedLongEnough) return

        // 确认换音。
        trimTransitionTail()
        currentNote = match.name
        candidateNote = null
        candidateFrames = 0
        candidateLastFrameMs = 0L
        addPoint(match.centsRounded)
        addMarker(match.name, match.centsRounded)
    }

    /**
     * 让轨迹时间轴前进 [deltaMillis]。
     *
     * 由调音器页每画一帧调用一次，**只在检测到有效音高时调用**：这样轨迹按真实时间
     * 向左滚动（和屏幕刷新率无关），NOISE 期间时间轴不动，轨迹也就整体停住。
     *
     * 单步前进有上限 [MAX_FRAME_STEP_MS]：万一遇到卡顿，或者从后台回来时重新开始出帧，
     * 时间轴不会一下跳掉好几秒，把整条轨迹瞬间推出屏幕。
     */
    fun advance(deltaMillis: Long) {
        if (deltaMillis <= 0L) return
        nowMs += if (deltaMillis > MAX_FRAME_STEP_MS) MAX_FRAME_STEP_MS else deltaMillis
        dropExpired()
    }

    private fun addPoint(cents: Int) {
        val tail = (pointHead + pointSize) % maxPoints
        pointTimes[tail] = nowMs
        pointCents[tail] = cents
        if (pointSize == maxPoints) {
            // 缓冲满了：挤掉最早的那个点。
            pointHead = (pointHead + 1) % maxPoints
        } else {
            pointSize++
        }
    }

    private fun addMarker(name: String, cents: Int) {
        val tail = (markerHead + markerSize) % maxMarkers
        markerTimes[tail] = nowMs
        markerCents[tail] = cents
        markerNames[tail] = name
        if (markerSize == maxMarkers) {
            markerHead = (markerHead + 1) % maxMarkers
        } else {
            markerSize++
        }
    }

    /**
     * 确认换音时，把旧音符末尾那些贴着 ±50 音分边界的点删掉。
     *
     * 这些点多半不是真实音高，而是"旧音已经吹到边界、马上要翻成新音"时的过渡读数：
     * 它们会把曲线先顶到栏目最上／最下，再弹回新音符的位置，也就是那个不自然的尖峰。
     * 删掉之后，曲线就是从旧音符最后一个稳定位置直接连到新音符的第一个稳定位置 —— 一段斜坡。
     *
     * 只回看 [TRANSITION_TRIM_WINDOW_MS]，并且只删"末尾连续贴边界"的那几个点：
     * 一旦遇到正常范围内的点（或者超出回看窗口）就立刻停手，不会误删前面正常的轨迹。
     */
    private fun trimTransitionTail() {
        val earliest = nowMs - TRANSITION_TRIM_WINDOW_MS
        while (pointSize > 0) {
            val last = pointSize - 1
            if (pointTime(last) < earliest) break
            if (abs(pointCents(last)) < TRANSITION_EDGE_CENTS) break
            // 环形缓冲只需要把"个数"退一格，末尾那个槽位就空出来给下一次写入了。
            pointSize--
        }
    }

    /** 丢掉已经滚出左边（早于 `nowMs - windowMs`）的数据。 */
    private fun dropExpired() {
        val oldest = nowMs - windowMs
        while (pointSize > 0 && pointTimes[pointHead] < oldest) {
            pointHead = (pointHead + 1) % maxPoints
            pointSize--
        }
        while (markerSize > 0 && markerTimes[markerHead] < oldest) {
            markerHead = (markerHead + 1) % maxMarkers
            markerSize--
        }
    }

    companion object {
        /**
         * 默认时间窗口：8 秒。
         *
         * 需求给的范围是 5～10 秒：再短了看不出"音准在一段时间里怎么漂"，
         * 再长了每个点的间距就太挤、还费绘图时间。8 秒在竖屏和横屏上都能看清走势。
         */
        const val DEFAULT_WINDOW_MS = 8_000L

        /** 采集是约 46 毫秒一个点，8 秒最多约 174 个点；256 留足余量。 */
        const val DEFAULT_MAX_POINTS = 256

        /** 音名标签只在换音时产生，一个时间窗口里 32 个已经绰绰有余。 */
        const val DEFAULT_MAX_MARKERS = 32

        /** 单次 [advance] 最多前进 100 毫秒（正常一帧 8～17 毫秒），只在异常情况下起作用。 */
        const val MAX_FRAME_STEP_MS = 100L

        /**
         * 换音需要连续稳定多少帧才算数。
         *
         * 一次检测约 46 毫秒（见 TunerAudioEngine），3 帧约 140 毫秒：
         * 足够挡掉一帧、两帧的误识别，又不会让正常的快速换音（每秒 5～6 个音）漏记。
         */
        const val NOTE_SWITCH_CONFIRM_FRAMES = 3

        /**
         * 候选音最多观察多少帧就强制确认。
         *
         * 给"确实一直吹得很偏"的音留一条出路：12 帧约 550 毫秒，
         * 到点后即使还贴着边界也照样记录，不会永远不画。
         */
        const val NOTE_SWITCH_CONFIRM_TIMEOUT_FRAMES = 12

        /**
         * 候选帧之间最长允许的空档：150 毫秒（约 3 次检测）。
         *
         * 超过它就认为"观察被打断了"，候选帧数重新从 1 开始数 —— 免得很久以前的一次偶发帧
         * 被当成这次的证据，让真正的换音提早（甚至在异常读数上）确认。
         */
        const val NOTE_SWITCH_CANDIDATE_GAP_MS = 150L

        /**
         * 换音瞬间的"边界区"：偏差绝对值超过它就认为读数已经贴着 ±50 音分了。
         *
         * 换音就发生在这条线上，这里的读数最容易来回翻，所以确认要求它、修剪也针对它。
         */
        const val TRANSITION_EDGE_CENTS = 45

        /** 确认换音时回看修剪旧音符末尾异常点的时间范围：500 毫秒。 */
        const val TRANSITION_TRIM_WINDOW_MS = 500L
    }
}
