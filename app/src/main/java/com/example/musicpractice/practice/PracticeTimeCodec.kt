package com.example.musicpractice.practice

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * 练习记录的完整内存快照：已完成的记录 + 正在进行的那一段。
 */
data class PracticeTimeData(
    val sessions: List<PracticeSession>,
    val active: ActiveSession?
)

/**
 * 内存快照 ↔ JSON 文本。
 *
 * 用 Android 自带的 org.json，不需要引入任何第三方库（项目到现在都是零第三方依赖）。
 * 记录量级很小（每天几条，几年也就几千条），整份读进内存、整份写回去完全够用，
 * 不值得为它上 Room。
 *
 * 容错原则：任何一条看不懂的记录都跳过，整份文件坏了就当成空数据，
 * 绝不让一条脏数据把 App 卡在崩溃里。
 */
object PracticeTimeCodec {

    /** 文件格式版本号。以后要改字段时靠它决定怎么升级老文件。 */
    private const val VERSION = 1

    private const val KEY_VERSION = "version"
    private const val KEY_SESSIONS = "sessions"
    private const val KEY_ACTIVE = "active"
    private const val KEY_DATE = "date"
    private const val KEY_START = "start"
    private const val KEY_END = "end"
    private const val KEY_LAST_SEEN = "lastSeen"

    fun encode(data: PracticeTimeData): String {
        val root = JSONObject()
        root.put(KEY_VERSION, VERSION)

        val sessions = JSONArray()
        for (session in data.sessions) {
            sessions.put(
                JSONObject()
                    .put(KEY_DATE, session.dateKey)
                    .put(KEY_START, session.startMillis)
                    .put(KEY_END, session.endMillis)
            )
        }
        root.put(KEY_SESSIONS, sessions)

        data.active?.let { active ->
            root.put(
                KEY_ACTIVE,
                JSONObject()
                    .put(KEY_START, active.startMillis)
                    .put(KEY_LAST_SEEN, active.lastSeenMillis)
            )
        }

        return root.toString()
    }

    fun decode(text: String): PracticeTimeData {
        if (text.isBlank()) return PracticeTimeData(emptyList(), null)
        return try {
            val root = JSONObject(text)
            PracticeTimeData(
                sessions = decodeSessions(root),
                active = decodeActive(root)
            )
        } catch (_: JSONException) {
            // 内容被截断或被改坏：按"还没有记录"处理，用户重新开始练习即可。
            PracticeTimeData(emptyList(), null)
        }
    }

    private fun decodeSessions(root: JSONObject): List<PracticeSession> {
        val array = root.optJSONArray(KEY_SESSIONS) ?: return emptyList()
        val sessions = ArrayList<PracticeSession>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val dateKey = item.optString(KEY_DATE)
            val start = item.optLong(KEY_START, MISSING)
            val end = item.optLong(KEY_END, MISSING)
            if (dateKey.isEmpty() || start == MISSING || end == MISSING) continue
            sessions += PracticeSession(dateKey = dateKey, startMillis = start, endMillis = end)
        }
        sessions.sortBy { it.startMillis }
        return sessions
    }

    private fun decodeActive(root: JSONObject): ActiveSession? {
        val item = root.optJSONObject(KEY_ACTIVE) ?: return null
        val start = item.optLong(KEY_START, MISSING)
        if (start == MISSING) return null
        val lastSeen = item.optLong(KEY_LAST_SEEN, start)
        return ActiveSession(startMillis = start, lastSeenMillis = maxOf(lastSeen, start))
    }

    /** optLong 的默认值，用来区分"字段是 0"和"字段根本不存在"。 */
    private const val MISSING = Long.MIN_VALUE
}
