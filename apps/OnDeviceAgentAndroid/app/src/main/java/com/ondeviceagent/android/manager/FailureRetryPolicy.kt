package com.ondeviceagent.android.manager

/** 次数不含首次执行；有效期从第一次失败算起，占用延后也不能越过截止时间。 */
object FailureRetryPolicy {
    const val DELAY_MS = 5 * 60_000L
    private val retryableReasons = setOf("parse_error", "model_error", "action_failed", "max_steps", "runtime_error", "start_failed", "runtime_busy", "run_timeout", "stalled", "interrupted", "model_failed")

    fun intervalMs(task: TaskTemplate) = task.retryIntervalMinutes.coerceIn(1, 1440) * 60_000L
    fun expired(task: TaskTemplate, now: Long) = task.retryDeadlineAtEpochMs?.let { now > it } == true

    fun queue(task: TaskTemplate, runId: String, reason: String, context: String, now: Long): TaskTemplate {
        if (!task.retryOnFailure || task.isSchedulePaused || task.retryAttempt >= task.maxRetries.coerceIn(0, 10) || reason !in retryableReasons) return task
        val deadline = task.retryDeadlineAtEpochMs ?: (now + task.retryWindowMinutes.coerceIn(1, 10080) * 60_000L)
        val at = now + intervalMs(task)
        if (at > deadline) return task.copy(pendingRetryAtEpochMs = null, retryDeadlineAtEpochMs = deadline)
        return task.copy(
            pendingRetryAtEpochMs = at, retryDeadlineAtEpochMs = deadline,
            retryAttempt = task.retryAttempt + 1,
            retrySourceRunId = task.retrySourceRunId ?: runId,
            retryContext = context.take(6000),
        )
    }

    fun clear(task: TaskTemplate): TaskTemplate = task.copy(
        pendingRetryAtEpochMs = null, retryDeadlineAtEpochMs = null,
        retryAttempt = 0, retrySourceRunId = null, retryContext = "",
    )

    fun prepare(task: TaskTemplate, isRetry: Boolean): TaskTemplate =
        if (isRetry) task.copy(pendingRetryAtEpochMs = null) else clear(task)

    fun defer(task: TaskTemplate, now: Long): TaskTemplate {
        if (task.pendingRetryAtEpochMs == null) return task
        val at = now + intervalMs(task)
        return if (task.retryDeadlineAtEpochMs?.let { at > it } == true) task.copy(
            pendingRetryAtEpochMs = null,
            lastRun = TaskLastRun(TaskRunState.failed, "重试有效期已到，取消补跑", now),
        ) else task.copy(pendingRetryAtEpochMs = at)
    }

    fun nextAlarm(task: TaskTemplate, regularAt: Long?): Long? {
        val retryAt = task.pendingRetryAtEpochMs.takeIf { task.retryOnFailure && !task.isSchedulePaused }
        return listOfNotNull(regularAt, retryAt).minOrNull()
    }
}
