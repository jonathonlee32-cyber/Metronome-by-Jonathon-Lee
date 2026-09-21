package com.example.musicpractice.practice

/**
 * 一段练习记录：一次"开始 → 停止"的节拍器使用。
 *
 * 一条记录只属于一天。跨午夜的练习会被拆成两条（前一天的结束时刻记成 24:00，
 * 后一天从 00:00 开始），拆分规则见 [PracticeSplitting.splitIntoSessions]。
 */
data class PracticeSession(
    /** 这条记录归属的本地日期，格式为 [PracticeCalendar.DATE_KEY_PATTERN]。 */
    val dateKey: String,
    /** 开始时刻（Unix 毫秒，挂钟时间）。 */
    val startMillis: Long,
    /** 结束时刻（Unix 毫秒，挂钟时间）。 */
    val endMillis: Long
) {
    /** 单次练习时长。结束早于开始时（例如用户改了系统时间）按 0 处理。 */
    val durationMillis: Long
        get() = (endMillis - startMillis).coerceAtLeast(0L)
}

/**
 * 正在进行、还没有结束的练习。
 *
 * 它会被立刻写进本地文件，所以 App 被后台冻结、被系统回收甚至手机重启之后，
 * 仍然知道"上一次练习是从什么时候开始的"，不会平白丢掉这段时间。
 *
 * [lastSeenMillis] 是心跳：播放过程中每隔几秒更新一次，代表"到这一刻为止都还在练习"。
 */
data class ActiveSession(
    val startMillis: Long,
    val lastSeenMillis: Long
)
