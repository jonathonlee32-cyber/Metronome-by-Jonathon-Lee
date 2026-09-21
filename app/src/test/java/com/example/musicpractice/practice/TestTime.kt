package com.example.musicpractice.practice

import java.util.Calendar
import java.util.TimeZone

/**
 * 测试里统一用东八区，并且用"本地时间"而不是固定毫秒数来构造时刻，
 * 这样断言写出来是 "2026-09-21 23:58" 这种一眼能看懂的东西。
 */
internal object TestTime {

    const val ZONE = "Asia/Shanghai"

    fun useFixedZone() {
        TimeZone.setDefault(TimeZone.getTimeZone(ZONE))
    }

    fun at(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long {
        return Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis
    }
}
