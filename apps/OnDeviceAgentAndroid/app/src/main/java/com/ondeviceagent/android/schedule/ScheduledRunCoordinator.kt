package com.ondeviceagent.android.schedule

import com.ondeviceagent.android.manager.*
import com.ondeviceagent.android.model.AgentConfigRequest
import com.ondeviceagent.android.runtime.AgentRuntime
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 亮屏流程仅转交事件，最终任务版本和取消状态在 runtime 领取时复核。 */
class ScheduledRunCoordinator(
    private val appContext: android.content.Context,
    private val managerStore: ManagerStore,
    private val runtime: AgentRuntime,
    private val alarmScheduler: TaskAlarmScheduler,
) {
    private val mutex = Mutex()

    suspend fun handleAlarm(taskId: String) = mutex.withLock { handleLocked(taskId) }

    suspend fun catchUpOverdue(graceMs: Long = 15 * 60_000L) = mutex.withLock {
        val now = System.currentTimeMillis()
        for (task in managerStore.templates.value) {
            managerStore.expirePastOnce(task.id, task.executionRevision, now)
            if (task.pendingRetryAtEpochMs?.let { it <= now } == true || dueClaim(task, now, graceMs) != null) {
                handleLocked(task.id)
                break
            }
        }
    }

    private fun dueClaim(task: TaskTemplate, now: Long, graceMs: Long = 15 * 60_000L): RunClaim? {
        if (!task.isScheduleActive) return null
        val due = if (task.schedule.kind == ScheduleKind.once) task.schedule.onceAtEpochMs
            else ScheduleCalculator.nextTriggerEpochMs(task.schedule, now - graceMs)
        if (due == null || due > now + 1000 || now - due > graceMs) return null
        val signature = ScheduleCalculator.occurrenceSignature(task.schedule, due) ?: return null
        if (signature == task.lastTriggeredSignature) return null
        return RunClaim(task.executionRevision, occurrence = signature)
    }

    private suspend fun handleLocked(taskId: String) {
        val task = managerStore.templates.value.firstOrNull { it.id == taskId }
            ?: return alarmScheduler.cancel(taskId)
        val now = System.currentTimeMillis()
        val retryAt = task.pendingRetryAtEpochMs?.takeIf { it <= now + 1000 }
        if (retryAt != null && FailureRetryPolicy.expired(task, now)) {
            managerStore.deferRetry(taskId)
            return
        }
        val claim = if (retryAt != null) RunClaim(task.executionRevision, retryAt = retryAt)
            else dueClaim(task, now)
        if (claim == null) {
            managerStore.expirePastOnce(task.id, task.executionRevision, now)
            managerStore.syncAlarms()
            return
        }
        if (runtime.status().running) {
            if (retryAt != null) managerStore.deferRetry(taskId)
            else managerStore.recordStartFailure(taskId, claim, "runtime_busy", "执行器被其他任务占用")
            return
        }
        val payload = runtime.start(
            AgentConfigRequest(task = task.task), showCompletionReminder = false,
            taskId = task.id, isRetry = retryAt != null, claim = claim,
        )
        if (!payload.ok) managerStore.recordStartFailure(taskId, claim, if (payload.errorKind == "blocked") "blocked" else "start_failed", payload.error)
        ScheduleLaunchNotifier.cancel(appContext)
    }

    fun resyncAlarms() = alarmScheduler.rescheduleAll(managerStore.templates.value)
}
