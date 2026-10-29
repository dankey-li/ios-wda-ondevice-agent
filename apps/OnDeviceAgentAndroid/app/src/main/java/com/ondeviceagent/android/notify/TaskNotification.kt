package com.ondeviceagent.android.notify

import com.ondeviceagent.android.manager.TaskTemplate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 通知只描述结果，不参与业务状态、重试和完成判定。 */
data class TaskNotification(
    val success: Boolean, val title: String, val task: String, val message: String, val runId: String,
    val heading: String, val color: String, val lastImageJpeg: ByteArray? = null,
)

object TaskNotificationPolicy {
    fun create(task: TaskTemplate, success: Boolean, reason: String, message: String, runId: String): TaskNotification? {
        if (success && !task.notifyOnSuccess) return null
        if (!success && (!task.notifyOnFailure || reason in setOf("stopped", "cancelled"))) return null
        val waiting = !success && task.pendingRetryAtEpochMs != null
        val heading = when {
            success -> "✅ 任务完成"
            waiting -> "⏳ 本次失败，等待重试"
            reason == "blocked" -> "⚠️ 任务需要人工核对"
            else -> "❌ 任务失败"
        }
        val text = buildString {
            append(message.ifBlank { if (success) "执行成功" else "执行未成功" })
            if (!success) {
                append("\n原因：").append(reason)
                if (waiting) {
                    val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(task.pendingRetryAtEpochMs!!))
                    append("\n已安排第 ${task.retryAttempt}/${task.maxRetries} 次重试：$time。本次失败不代表最终失败。")
                } else append("\n本轮没有待执行的自动重试，请查看手机历史核对。")
            }
        }
        return TaskNotification(success, task.title, task.task, text, runId, heading,
            if (success) "green" else if (waiting || reason == "blocked") "orange" else "red")
    }
}
