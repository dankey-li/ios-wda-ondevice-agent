package com.ondeviceagent.android.manager

/** 启动资格在亮屏完成后领取；旧版本、重复广播和已取消的重试不能再启动。 */
data class RunClaim(
    val revision: Long,
    val occurrence: String? = null,
    val retryAt: Long? = null,
) {
    fun matches(task: TaskTemplate, now: Long): Boolean {
        if (task.executionRevision != revision || task.activeRunId != null) return false
        if (retryAt != null) return task.retryOnFailure && !task.isSchedulePaused &&
            task.pendingRetryAtEpochMs == retryAt && task.retryAttempt in 1..task.maxRetries.coerceIn(0, 10) && retryAt <= now + 1000 && !FailureRetryPolicy.expired(task, now)
        if (occurrence != null) {
            if (!task.schedule.isEnabled || task.isSchedulePaused || task.lastTriggeredSignature == occurrence) return false
            val due = if (task.schedule.kind == ScheduleKind.once) task.schedule.onceAtEpochMs
                else ScheduleCalculator.nextTriggerEpochMs(task.schedule, now - 15 * 60_000L)
            return due != null && due <= now + 1000 && now - due <= 15 * 60_000L &&
                ScheduleCalculator.occurrenceSignature(task.schedule, due) == occurrence
        }
        return true
    }
}
