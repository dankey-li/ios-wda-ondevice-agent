package com.ondeviceagent.android.manager

import java.util.Calendar

object ScheduleCalculator {
    fun nextTriggerEpochMs(schedule: TaskSchedule, afterEpochMs: Long = System.currentTimeMillis()): Long? {
        return when (schedule.kind) {
            ScheduleKind.none -> null
            ScheduleKind.once -> {
                val at = schedule.onceAtEpochMs ?: return null
                if (at > afterEpochMs) at else null
            }
            ScheduleKind.daily -> nextDaily(schedule.hour, schedule.minute, afterEpochMs)
            ScheduleKind.weekly -> nextWeekly(schedule.hour, schedule.minute, schedule.weekdays, afterEpochMs)
        }
    }

    fun occurrenceSignature(schedule: TaskSchedule, atEpochMs: Long): String? {
        val cal = Calendar.getInstance().apply { timeInMillis = atEpochMs }
        return when (schedule.kind) {
            ScheduleKind.none -> null
            ScheduleKind.once -> {
                val once = schedule.onceAtEpochMs ?: return null
                if (atEpochMs < once) return null
                "once-${once / 60_000}"
            }
            ScheduleKind.daily -> {
                "daily-%04d-%02d-%02d-%02d-%02d".format(
                    cal.get(Calendar.YEAR),
                    cal.get(Calendar.MONTH) + 1,
                    cal.get(Calendar.DAY_OF_MONTH),
                    schedule.hour,
                    schedule.minute,
                )
            }
            ScheduleKind.weekly -> {
                "weekly-%04d-%02d-%02d-%02d-%02d-%d".format(
                    cal.get(Calendar.YEAR),
                    cal.get(Calendar.MONTH) + 1,
                    cal.get(Calendar.DAY_OF_MONTH),
                    schedule.hour,
                    schedule.minute,
                    cal.get(Calendar.DAY_OF_WEEK),
                )
            }
        }
    }

    /** Next occurrence of hour:minute strictly after [afterEpochMs]. */
    fun nextDaily(hour: Int, minute: Int, afterEpochMs: Long): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = afterEpochMs }
        cal.set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
        cal.set(Calendar.MINUTE, minute.coerceIn(0, 59))
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (cal.timeInMillis <= afterEpochMs) {
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        return cal.timeInMillis
    }

    private fun nextWeekly(hour: Int, minute: Int, weekdays: List<Int>, afterEpochMs: Long): Long? {
        val wanted = weekdays.toSet()
        if (wanted.isEmpty()) return null
        for (offset in 0..7) {
            val cal = Calendar.getInstance().apply { timeInMillis = afterEpochMs }
            cal.add(Calendar.DAY_OF_MONTH, offset)
            val weekday = cal.get(Calendar.DAY_OF_WEEK) // 1=Sun … 7=Sat
            if (weekday !in wanted) continue
            cal.set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
            cal.set(Calendar.MINUTE, minute.coerceIn(0, 59))
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            if (cal.timeInMillis > afterEpochMs) return cal.timeInMillis
        }
        return null
    }

    fun resolveOnceAtEpochMs(hour: Int, minute: Int, now: Long = System.currentTimeMillis()): Long {
        return nextDaily(hour, minute, now)
    }
}
