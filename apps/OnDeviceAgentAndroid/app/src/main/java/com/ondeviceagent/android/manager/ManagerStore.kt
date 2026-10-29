package com.ondeviceagent.android.manager

import android.content.Context
import com.ondeviceagent.android.schedule.TaskAlarmScheduler
import com.ondeviceagent.android.schedule.isScheduleActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ManagerStore(
    context: Context,
    private val alarmScheduler: TaskAlarmScheduler? = null,
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    var onFailureNotice: ((TaskTemplate, String) -> Unit)? = null

    var storageWarning: String = ""
        private set
    private val _templates = MutableStateFlow(loadTemplates())
    val templates: StateFlow<List<TaskTemplate>> = _templates.asStateFlow()

    private val _selectedId = MutableStateFlow(prefs.getString(KEY_SELECTED, null))
    val selectedId: StateFlow<String?> = _selectedId.asStateFlow()

    fun selected(): TaskTemplate? {
        val id = _selectedId.value
        return _templates.value.firstOrNull { it.id == id } ?: _templates.value.firstOrNull()
    }

    @Synchronized
    fun select(id: String?) {
        _selectedId.value = id
        prefs.edit().putString(KEY_SELECTED, id).apply()
    }

    @Synchronized
    fun upsert(template: TaskTemplate) {
        val edited = FailureRetryPolicy.clear(template).copy(
            executionRevision = (_templates.value.firstOrNull { it.id == template.id }?.executionRevision ?: 0) + 1,
        )
        _templates.update { list ->
            val without = list.filterNot { it.id == template.id }
            (listOf(edited) + without).sortedByDescending { it.updatedAtEpochMs }
        }
        persist()
        if (_selectedId.value == null) select(template.id)
        syncAlarms()
    }

    @Synchronized
    fun delete(id: String) {
        alarmScheduler?.cancel(id)
        _templates.update { it.filterNot { t -> t.id == id } }
        if (_selectedId.value == id) {
            select(_templates.value.firstOrNull()?.id)
        }
        persist()
        syncAlarms()
    }

    @Synchronized
    fun markRun(id: String, state: TaskRunState, message: String) {
        val now = System.currentTimeMillis()
        _templates.update { list ->
            list.map { t ->
                if (t.id != id) t else t.copy(
                    updatedAtEpochMs = now,
                    lastRunAtEpochMs = now,
                    lastRun = TaskLastRun(state = state, message = message, atEpochMs = now),
                )
            }
        }
        persist()
    }

    @Synchronized
    fun markTriggered(id: String, signature: String) {
        _templates.update { list ->
            list.map { t ->
                if (t.id != id) t else t.copy(lastTriggeredSignature = signature)
            }
        }
        persist()
    }

    @Synchronized
    fun clearSchedule(id: String) {
        _templates.update { list ->
            list.map { t ->
                if (t.id != id) t else t.copy(schedule = TaskSchedule(kind = ScheduleKind.none))
            }
        }
        persist()
        alarmScheduler?.cancel(id)
    }

    @Synchronized
    fun toggleSchedulePause(id: String) {
        _templates.update { list ->
            list.map { t ->
                if (t.id != id) t else FailureRetryPolicy.clear(t.copy(isSchedulePaused = !t.isSchedulePaused, executionRevision = t.executionRevision + 1))
            }
        }
        persist()
        syncAlarms()
    }

    @Synchronized
    fun toggleNotifyOnSuccess(id: String) {
        _templates.update { list ->
            list.map { t ->
                if (t.id != id) t else t.copy(
                    notifyOnSuccess = !t.notifyOnSuccess,
                    updatedAtEpochMs = System.currentTimeMillis(),
                )
            }
        }
        persist()
    }

    @Synchronized
    fun prepareRun(id: String, isRetry: Boolean) {
        _templates.update { list -> list.map { if (it.id == id) FailureRetryPolicy.prepare(it, isRetry) else it } }
        persist()
        syncAlarms()
    }

    @Synchronized
    fun cancelRetry(id: String) {
        _templates.update { list -> list.map { if (it.id == id) FailureRetryPolicy.clear(it).copy(executionRevision = it.executionRevision + 1) else it } }
        persist()
        syncAlarms()
    }

    @Synchronized
    fun queueFailureRetry(id: String, taskText: String, runId: String, reason: String, context: String) {
        _templates.update { list -> list.map { task ->
            if (task.id != id || task.task != taskText) task
            else FailureRetryPolicy.queue(task, runId, reason, context, System.currentTimeMillis())
        } }
        persist()
        syncAlarms()
    }

    @Synchronized
    fun deferRetry(id: String) {
        val before = _templates.value.firstOrNull { it.id == id }
        _templates.update { list -> list.map { if (it.id == id) FailureRetryPolicy.defer(it, System.currentTimeMillis()) else it } }
        persist()
        syncAlarms()
        val after = _templates.value.firstOrNull { it.id == id }
        if (before?.pendingRetryAtEpochMs != null && after != null && after.pendingRetryAtEpochMs == null) {
            runCatching { onFailureNotice?.invoke(after, "retry_expired") }
        }
    }

    @Synchronized
    fun claimRun(id: String, claim: RunClaim, runId: String, now: Long = System.currentTimeMillis()): TaskTemplate? {
        if (_templates.value.any { it.activeRunId != null }) return null
        val task = _templates.value.firstOrNull { it.id == id } ?: return null
        if (!claim.matches(task, now)) return null
        val prepared = FailureRetryPolicy.prepare(task, claim.retryAt != null).copy(
            activeRunId = runId, activeStartedAtEpochMs = now,
            lastTriggeredSignature = claim.occurrence ?: task.lastTriggeredSignature,
            schedule = if (claim.occurrence != null && task.schedule.kind == ScheduleKind.once) TaskSchedule() else task.schedule,
            lastRun = TaskLastRun(TaskRunState.running, "执行中", now), lastRunAtEpochMs = now,
        )
        _templates.update { list -> list.map { if (it.id == id) prepared else it } }
        persist()
        syncAlarms()
        return task
    }

    @Synchronized
    fun finishRun(id: String, runId: String, revision: Long, success: Boolean, reason: String, message: String, context: String): TaskTemplate? {
        val task = _templates.value.firstOrNull { it.id == id && it.activeRunId == runId } ?: return null
        val state = when { success -> TaskRunState.success; reason == "blocked" -> TaskRunState.blocked; reason in setOf("stopped", "cancelled") -> TaskRunState.stopped; else -> TaskRunState.failed }
        var finished = task.copy(activeRunId = null, activeStartedAtEpochMs = null,
            lastRun = TaskLastRun(state, message, System.currentTimeMillis()))
        finished = if (success) FailureRetryPolicy.clear(finished).copy(submissionPending = "")
            else if (task.executionRevision == revision) FailureRetryPolicy.queue(finished, runId, reason, context, System.currentTimeMillis()) else finished
        _templates.update { list -> list.map { if (it.id == id) finished else it } }
        persist()
        syncAlarms()
        return finished
    }

    @Synchronized
    fun recordStartFailure(id: String, claim: RunClaim, reason: String, message: String) {
        val task = _templates.value.firstOrNull { it.id == id } ?: return
        val now = System.currentTimeMillis()
        if (reason == "runtime_busy" && task.activeRunId != null && claim.occurrence != null && claim.matches(task.copy(activeRunId = null), now)) {
            // 同一任务已有执行时合并本次计划，避免完成后重复补跑。
            _templates.update { list -> list.map { if (it.id == id) it.copy(
                lastTriggeredSignature = claim.occurrence,
                schedule = if (it.schedule.kind == ScheduleKind.once) TaskSchedule() else it.schedule,
            ) else it } }
            persist(); syncAlarms(); return
        }
        if (!claim.matches(task, now)) return
        val consumed = FailureRetryPolicy.prepare(task, claim.retryAt != null).copy(
            lastTriggeredSignature = claim.occurrence ?: task.lastTriggeredSignature,
            schedule = if (claim.occurrence != null && task.schedule.kind == ScheduleKind.once) TaskSchedule() else task.schedule,
            lastRun = TaskLastRun(if (reason == "blocked") TaskRunState.blocked else TaskRunState.failed, message, now),
        )
        val queued = FailureRetryPolicy.queue(consumed, task.retrySourceRunId.orEmpty(), reason, "上次尚未启动：$message", now)
        _templates.update { list -> list.map { if (it.id == id) queued else it } }
        persist()
        syncAlarms()
        runCatching { onFailureNotice?.invoke(queued, reason) }
    }

    @Synchronized
    fun markSubmission(id: String, runId: String, label: String): Boolean {
        val task = _templates.value.firstOrNull { it.id == id && it.activeRunId == runId } ?: return false
        if (task.submissionPending.isNotBlank()) return false
        _templates.update { list -> list.map { if (it.id == id) it.copy(submissionPending = label) else it } }
        persist()
        return true
    }

    @Synchronized
    fun clearSubmission(id: String) {
        if (_templates.value.firstOrNull { it.id == id }?.activeRunId != null) return
        cancelRetry(id)
        _templates.update { list -> list.map { if (it.id == id) it.copy(submissionPending = "") else it } }
        persist()
    }

    @Synchronized
    fun expirePastOnce(id: String, revision: Long, now: Long = System.currentTimeMillis()) {
        val task = _templates.value.firstOrNull { it.id == id && it.executionRevision == revision && it.activeRunId == null } ?: return
        val due = task.schedule.onceAtEpochMs ?: return
        if (task.schedule.kind != ScheduleKind.once || now - due <= 15 * 60_000L) return
        _templates.update { list -> list.map { if (it.id == id) it.copy(schedule = TaskSchedule(),
            lastRun = TaskLastRun(TaskRunState.failed, "计划超过补执行窗口，已取消", now)) else it } }
        persist(); syncAlarms()
    }

    @Synchronized
    fun recoverInterrupted() {
        val now = System.currentTimeMillis()
        _templates.update { list -> list.map {
            if (it.activeRunId != null || it.lastRun.state == TaskRunState.running)
                FailureRetryPolicy.clear(it).copy(activeRunId = null, activeStartedAtEpochMs = null,
                    lastRun = TaskLastRun(TaskRunState.interrupted, "上次运行因进程中断，请先核对结果再重新执行", now))
            else if (FailureRetryPolicy.expired(it, now)) it.copy(pendingRetryAtEpochMs = null,
                lastRun = TaskLastRun(TaskRunState.failed, "重试有效期已到，取消补跑", now))
            else it
        } }
        persist()
        syncAlarms()
    }

    @Synchronized
    fun syncAlarms() {
        alarmScheduler?.rescheduleAll(_templates.value)
    }

    fun nextTriggerLabel(task: TaskTemplate): String {
        task.pendingRetryAtEpochMs?.let { at ->
            val fmt = java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.CHINA)
            return "第 ${task.retryAttempt}/${task.maxRetries} 次重试：${fmt.format(java.util.Date(at))}"
        }
        if (task.retryDeadlineAtEpochMs != null && task.activeRunId == null) {
            if (task.retryAttempt >= task.maxRetries) return "本轮重试次数已用尽"
            if (System.currentTimeMillis() + FailureRetryPolicy.intervalMs(task) > task.retryDeadlineAtEpochMs)
                return "本轮重试有效期已到，不再补跑"
        }
        if (!task.isScheduleActive) {
            return if (task.schedule.isEnabled && task.isSchedulePaused) "计划已暂停" else "无计划"
        }
        val next = ScheduleCalculator.nextTriggerEpochMs(task.schedule) ?: return "无下次触发"
        val fmt = java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.CHINA)
        return "下次 ${fmt.format(java.util.Date(next))}"
    }

    private fun loadTemplates(): List<TaskTemplate> {
        val raw = prefs.getString(KEY_TEMPLATES, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<TaskTemplate>>(raw).map { task ->
                if (task.pendingRetryAtEpochMs != null && task.retryDeadlineAtEpochMs == null)
                    task.copy(retryDeadlineAtEpochMs = task.pendingRetryAtEpochMs - FailureRetryPolicy.intervalMs(task) + task.retryWindowMinutes.coerceIn(1, 10080) * 60_000L)
                else task
            }
        } catch (_: Exception) {
            storageWarning = "任务数据读取失败，已保留损坏副本并尝试恢复上次备份"
            val backedUp = prefs.edit().putString("templates_corrupt_backup", raw).commit()
            if (!backedUp) storageWarning = "任务数据损坏且副本保存失败，请先检查手机存储空间，避免覆盖保存"
            runCatching { json.decodeFromString<List<TaskTemplate>>(prefs.getString("templates_last_good", "[]") ?: "[]") }.getOrDefault(emptyList())
        }
    }

    private fun persist() {
        val encoded = json.encodeToString(_templates.value)
        // 先持久化领取资格和检查点，再执行动作或注册闹钟。
        check(prefs.edit().putString(KEY_TEMPLATES, encoded).putString("templates_last_good", encoded).commit()) {
            "任务状态保存失败，已停止后续操作"
        }
    }

    companion object {
        private const val PREFS = "ondevice_agent_manager"
        private const val KEY_TEMPLATES = "templates"
        private const val KEY_SELECTED = "selected_template_id"
    }
}
