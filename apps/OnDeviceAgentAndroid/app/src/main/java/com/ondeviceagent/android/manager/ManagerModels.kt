package com.ondeviceagent.android.manager

import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@Serializable
enum class ScheduleKind {
    none, once, daily, weekly;

    val title: String
        get() = when (this) {
            none -> "不启用"
            once -> "单次"
            daily -> "每天"
            weekly -> "每周"
        }
}

@Serializable
data class TaskSchedule(
    val kind: ScheduleKind = ScheduleKind.none,
    val onceAtEpochMs: Long? = null,
    val hour: Int = 8,
    val minute: Int = 0,
    val weekdays: List<Int> = listOf(2),
) {
    val isEnabled: Boolean get() = kind != ScheduleKind.none

    val displayText: String
        get() {
            val time = "%02d:%02d".format(hour, minute)
            return when (kind) {
                ScheduleKind.none -> "未设置计划"
                ScheduleKind.once -> {
                    val at = onceAtEpochMs
                    if (at == null) "单次执行 $time"
                    else "单次 ${ONCE_FMT.format(Date(at))}"
                }
                ScheduleKind.daily -> "每天 $time"
                ScheduleKind.weekly -> {
                    val days = weekdayText(weekdays)
                    if (days.isBlank()) "每周 $time" else "每周$days $time"
                }
            }
        }

    companion object {
        private val ONCE_FMT = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA)

        /** Calendar weekday: 1=周日 … 7=周六（与 iOS 一致） */
        val weekdayOptions: List<Pair<Int, String>> = listOf(
            2 to "周一",
            3 to "周二",
            4 to "周三",
            5 to "周四",
            6 to "周五",
            7 to "周六",
            1 to "周日",
        )

        fun weekdayText(weekdays: List<Int>): String {
            val selected = weekdays.toSet()
            return weekdayOptions
                .mapNotNull { (value, label) -> if (value in selected) label else null }
                .joinToString("、")
        }
    }
}

@Serializable
enum class TaskRunState {
    idle, running, stopped, success, failed, blocked, interrupted
}

@Serializable
data class TaskLastRun(
    val state: TaskRunState = TaskRunState.idle,
    val message: String = "",
    val atEpochMs: Long? = null,
)

@Serializable
enum class PostRunAction(val title: String) {
    keep("保持当前页面"), returnToAgent("返回 One Agent 任务页"),
    dismissAndReturn("划掉目标 App 后返回")
}

@Serializable
enum class PostRunWhen(val title: String) {
    successOnly("仅成功后"), successOrFailure("成功或失败后")
}

@Serializable
enum class DismissDirection(val title: String) {
    up("向上划"), left("向左划"), right("向右划")
}

@Serializable
data class TaskTemplate(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val task: String,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val updatedAtEpochMs: Long = System.currentTimeMillis(),
    val lastRunAtEpochMs: Long? = null,
    val isTemplate: Boolean = true,
    val isEphemeral: Boolean = false,
    val schedule: TaskSchedule = TaskSchedule(),
    val isSchedulePaused: Boolean = false,
    /** When true, send Feishu webhook after a successful run (requires config webhook). */
    val notifyOnSuccess: Boolean = false,
    val executionRevision: Long = 0,
    val retryOnFailure: Boolean = true,
    val maxRetries: Int = 1,
    val retryIntervalMinutes: Int = 5,
    val retryWindowMinutes: Int = 30,
    val maxRunMinutes: Int = 15,
    val completionText: String = "",
    val guardedActionTexts: String = "上班打卡,下班打卡,提交,发送",
    val postRunAction: PostRunAction = PostRunAction.keep,
    val postRunWhen: PostRunWhen = PostRunWhen.successOnly,
    val cleanupPackage: String = "",
    val dismissDirection: DismissDirection = DismissDirection.up,

    val pendingRetryAtEpochMs: Long? = null,
    val retryDeadlineAtEpochMs: Long? = null,
    val submissionPending: String = "",
    val activeRunId: String? = null,
    val activeStartedAtEpochMs: Long? = null,
    val retryAttempt: Int = 0,
    val retrySourceRunId: String? = null,
    val retryContext: String = "",
    val lastRun: TaskLastRun = TaskLastRun(),
    val lastTriggeredSignature: String? = null,
)

@Serializable
data class TaskTemplateDraft(
    val title: String = "",
    val task: String = "",
    val isTemplate: Boolean = true,
    val scheduleKind: ScheduleKind = ScheduleKind.none,
    val hour: Int = 8,
    val minute: Int = 0,
    val weekdays: List<Int> = listOf(2),
    val notifyOnSuccess: Boolean = false,
    val retryOnFailure: Boolean = true,
    val maxRetries: Int = 1,
    val retryIntervalMinutes: Int = 5,
    val retryWindowMinutes: Int = 30,
    val maxRunMinutes: Int = 15,
    val completionText: String = "",
    val guardedActionTexts: String = "上班打卡,下班打卡,提交,发送",
    val postRunAction: PostRunAction = PostRunAction.keep,
    val postRunWhen: PostRunWhen = PostRunWhen.successOnly,
    val cleanupPackage: String = "",
    val dismissDirection: DismissDirection = DismissDirection.up,

) {
    fun toTemplate(existing: TaskTemplate? = null): TaskTemplate {
        val now = System.currentTimeMillis()
        val h = hour.coerceIn(0, 23)
        val m = minute.coerceIn(0, 59)
        val onceAt = if (scheduleKind == ScheduleKind.once) {
            ScheduleCalculator.resolveOnceAtEpochMs(h, m, now)
        } else {
            null
        }
        return TaskTemplate(
            id = existing?.id ?: UUID.randomUUID().toString(),
            title = title.ifBlank { task.take(24).ifBlank { "未命名任务" } },
            task = task,
            createdAtEpochMs = existing?.createdAtEpochMs ?: now,
            updatedAtEpochMs = now,
            lastRunAtEpochMs = existing?.lastRunAtEpochMs,
            isTemplate = isTemplate,
            schedule = TaskSchedule(
                kind = scheduleKind,
                onceAtEpochMs = onceAt,
                hour = h,
                minute = m,
                weekdays = weekdays.ifEmpty { listOf(2) },
            ),
            isSchedulePaused = if (scheduleKind == ScheduleKind.none) false else existing?.isSchedulePaused ?: false,
            notifyOnSuccess = notifyOnSuccess,
            retryOnFailure = retryOnFailure,
            maxRetries = maxRetries.coerceIn(0, 10),
            retryIntervalMinutes = retryIntervalMinutes.coerceIn(1, 1440),
            retryWindowMinutes = retryWindowMinutes.coerceIn(1, 10080),
            maxRunMinutes = maxRunMinutes.coerceIn(1, 180),
            completionText = completionText.trim().take(80),
            guardedActionTexts = guardedActionTexts.trim().take(200),
            postRunAction = postRunAction, postRunWhen = postRunWhen,
            cleanupPackage = cleanupPackage.trim(), dismissDirection = dismissDirection,
            submissionPending = existing?.submissionPending.orEmpty(),
            activeRunId = existing?.activeRunId,
            activeStartedAtEpochMs = existing?.activeStartedAtEpochMs,
            lastRun = existing?.lastRun ?: TaskLastRun(),
            lastTriggeredSignature = existing?.lastTriggeredSignature,
        )
    }

    companion object {
        fun from(task: TaskTemplate) = TaskTemplateDraft(
            title = task.title,
            task = task.task,
            isTemplate = task.isTemplate,
            scheduleKind = task.schedule.kind,
            hour = task.schedule.hour,
            minute = task.schedule.minute,
            weekdays = task.schedule.weekdays,
            notifyOnSuccess = task.notifyOnSuccess,
            retryOnFailure = task.retryOnFailure,
            maxRetries = task.maxRetries,
            retryIntervalMinutes = task.retryIntervalMinutes,
            retryWindowMinutes = task.retryWindowMinutes,
            maxRunMinutes = task.maxRunMinutes,
            completionText = task.completionText,
            guardedActionTexts = task.guardedActionTexts,
            postRunAction = task.postRunAction, postRunWhen = task.postRunWhen,
            cleanupPackage = task.cleanupPackage, dismissDirection = task.dismissDirection,
        )
    }
}
