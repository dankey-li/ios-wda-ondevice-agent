package com.ondeviceagent.android.remote

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import com.ondeviceagent.android.manager.*
import com.ondeviceagent.android.model.AgentConfigRequest
import com.ondeviceagent.android.runtime.AgentRuntime
import com.ondeviceagent.android.schedule.ScreenWakeHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 指令消费与结果观察分离，不等待长任务结束，保证执行过程中仍能接收停止命令。 */
class RemoteProcessor(
    private val context: Context,
    private val runtime: AgentRuntime,
    private val store: ManagerStore,
    private val journal: RemoteJournal,
    private val prepareDevice: suspend () -> String? = {
        ScreenWakeHelper.wakeScreen(context, 30_000)
        delay(500)
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        when { keyguard.isKeyguardLocked -> "手机已锁定，请先解锁后重新发送指令"
            !power.isInteractive -> "手机未唤醒，请在手机上检查电源和后台权限"
            else -> null }
    },
) {
    private val mutex = Mutex()
    suspend fun tick(appId: String, settings: RemoteSettings, now: Long = System.currentTimeMillis()) = mutex.withLock {
        // 重启后不重放状态不确定的动作；仍可回传原运行的历史结果。
        journal.entries.filter { it.appId == appId && it.state == "interrupted" }.forEach { entry ->
            finish(entry, "上次指令处理时进程中断，未自动重放；请核对手机历史后重新下发。", now)
            cleanupEphemeral(entry)
        }
        journal.entries.filter { it.appId == appId && it.state == "running" }.forEach { entry ->
            val status = runtime.status()
            if (!status.running || status.trace.runId != entry.runId) {
                val detail = runtime.loadTraceDetail(entry.runId)
                if (detail != null && detail.manifest.finished_at.isNotBlank()) {
                    val final = detail.manifest.final_status
                    val owner = store.templates.value.firstOrNull { it.id == entry.taskId }
                    val rootRunId = entry.rootRunId.ifBlank { entry.runId }
                    if (!final.success && owner?.pendingRetryAtEpochMs != null && owner.retrySourceRunId == rootRunId) {
                        journal.reply(entry, "attempt-${entry.runId}", "本次执行未成功：${final.message}\n已按任务配置安排第 ${owner.retryAttempt} 次重试；可用 /停止 取消。", now)
                        journal.update(entry.copy(state = "retry_wait", rootRunId = rootRunId))
                        return@forEach
                    }
                    finish(entry, "${if (final.success) "执行成功" else "执行结束（未成功）"}\n运行 ID：${entry.runId}\n${final.message}" +
                        detail.postRunMessage.takeIf { it.isNotBlank() }?.let { "\n结束后操作：$it" }.orEmpty(), now)
                } else finish(entry, "运行状态未确认，未自动重放；请核对手机历史。运行 ID：${entry.runId}", now)
                cleanupEphemeral(entry)
            }
        }
        journal.entries.filter { it.appId == appId && it.state == "retry_wait" }.forEach { entry ->
            val task = store.templates.value.firstOrNull { it.id == entry.taskId }
            if (task?.activeRunId != null && task.retrySourceRunId == entry.rootRunId) {
                journal.update(entry.copy(state = "running", runId = task.activeRunId))
                journal.reply(entry, "retry-${task.activeRunId}", "已开始任务配置的第 ${task.retryAttempt} 次重试，运行 ID：${task.activeRunId}", now)
            } else {
                // 快速重试可能在两次轮询间结束，从带父运行 ID 的历史继续追踪，避免漏掉结果。
                val previousAttempt = runtime.loadTraceDetail(entry.runId)?.manifest?.attempt ?: 0
                val child = if (task?.pendingRetryAtEpochMs == null || task.retryAttempt > previousAttempt + 1) {
                    runtime.traces().firstOrNull { it.parentRunId == entry.rootRunId && it.attempt > previousAttempt }
                } else null
                if (child != null) journal.update(entry.copy(state = "running", runId = child.runId))
                else if (task == null || task.pendingRetryAtEpochMs == null || task.retrySourceRunId != entry.rootRunId || FailureRetryPolicy.expired(task, now)) {
                    finish(entry, "本轮自动重试已结束、取消或过期；最近一次运行 ID：${entry.runId}", now)
                }
            }
        }
        for (entry in journal.entries.filter { it.appId == appId && it.state == "received" }) {
            currentCoroutineContext().ensureActive()
            if (!settings.enabled || entry.userId !in settings.users() || (entry.group && entry.chatId !in settings.chats()) || now - entry.createdAt > settings.commandTtlMinutes * 60_000L) {
                finish(entry, "指令已过期或授权已撤销，未执行。", now); continue
            }
            val cmd = RemoteCommands.parse(entry.text)
            if (cmd == null) { finish(entry, "不是有效命令。", now); continue }
            val response = when (cmd.name) {
                "/帮助" -> if (cmd.argument.isBlank()) RemoteCommands.help else "用法：/帮助"
                "/任务" -> if (cmd.argument.isNotBlank()) "用法：/任务" else {
                    val tasks = store.templates.value.filterNot { it.isEphemeral }
                    if (tasks.isEmpty()) "手机上没有已有任务；可用 /执行 下发一次性任务。"
                    else tasks.take(30).joinToString("\n") { "${taskCode(it)}  ${it.title.take(60)}" } + if (tasks.size > 30) "\n仅列出前 30 项，请在手机查看其余任务。" else ""
                }
                "/状态" -> if (cmd.argument.isNotBlank()) "用法：/状态" else runtime.status().let { "手机在线；${if (it.running) "正在执行，运行 ID：${it.trace.runId}" else "当前空闲"}" }
                "/结果" -> if (cmd.argument.isNotBlank()) "用法：/结果" else runtime.traces().firstOrNull { it.finishedAt.isNotBlank() }?.let {
                    "${if (it.finalStatus.success) "成功" else "未成功"} · ${it.runId}\n${it.finalStatus.message}"
                } ?: "尚无已结束的执行记录。"
                "/停止" -> if (cmd.argument.isNotBlank()) "用法：/停止" else {
                    val status = runtime.status()
                    if (status.running) { runtime.stop(); "已停止当前执行，并取消本轮待重试。" }
                    else {
                        val last = runtime.uiState.value.lastRunResult?.runId
                        val remoteWait = journal.entries.lastOrNull { it.appId == appId && it.state == "retry_wait" }
                        val retry = store.templates.value.firstOrNull { it.pendingRetryAtEpochMs != null &&
                            ((it.id == remoteWait?.taskId && it.retrySourceRunId == remoteWait.rootRunId) || (last != null && it.retrySourceRunId == last)) }
                        if (retry != null) { store.cancelRetry(retry.id); "已取消最近执行的待重试。" } else "当前无运行或最近一轮待重试。"
                    }
                }
                "/启动", "/执行" -> {
                    if (cmd.argument.isBlank()) { finish(entry, if (cmd.name == "/启动") "用法：/启动 <任务ID>，用 /任务 查看 ID。" else "用法：/执行 <任务内容>（支持换行）", now); continue }
                    val task = if (cmd.name == "/启动") {
                        val matches = store.templates.value.filter { !it.isEphemeral && (it.id == cmd.argument || taskCode(it) == cmd.argument) }
                        if (matches.size != 1) { finish(entry, "任务 ID 不存在或不唯一，请发送 /任务 查看。", now); continue }
                        matches.single()
                    } else null
                    if (runtime.status().running) { finish(entry, "手机正在执行其他任务，本条未启动、未排队。", now); continue }
                    val deviceError = prepareDevice()
                    if (deviceError != null) { finish(entry, deviceError, now); continue }
                    currentCoroutineContext().ensureActive()
                    val taskId = task?.id ?: "remote-" + java.security.MessageDigest.getInstance("SHA-256").digest(entry.key.toByteArray()).take(16).joinToString("") { "%02x".format(it) }
                    val dispatch = entry.copy(state = "dispatching", taskId = taskId, ephemeral = task == null)
                    journal.update(dispatch)
                    journal.reply(entry, "accepted", "已收到指令，正在检查并启动。", now)
                    val owner = if (task != null) task else TaskTemplate(id = taskId, title = "飞书一次性任务", task = cmd.argument,
                        isTemplate = false, isEphemeral = true, schedule = TaskSchedule(), retryOnFailure = false, maxRetries = 0,
                        maxRunMinutes = settings.maxRunMinutes, completionText = settings.completionText.take(80),
                        guardedActionTexts = settings.guardedActionTexts.take(200), postRunAction = settings.postRunAction,
                        postRunWhen = settings.postRunWhen, cleanupPackage = settings.cleanupPackage, dismissDirection = settings.dismissDirection).also { store.upsert(it) }
                    val saved = store.templates.value.firstOrNull { it.id == owner.id }
                    if (saved == null) { finish(dispatch, "任务已删除，未启动。", now); continue }
                    currentCoroutineContext().ensureActive()
                    val result = runtime.start(AgentConfigRequest(task = owner.task), false, owner.id, claim = RunClaim(saved.executionRevision))
                    if (result.ok) {
                        val active = dispatch.copy(state = "running", runId = result.status?.trace?.runId.orEmpty(), rootRunId = result.status?.trace?.runId.orEmpty())
                        journal.update(active)
                        journal.reply(active, "started", "已启动${if (active.ephemeral) "一次性任务" else "已有任务"}，运行 ID：${active.runId}", now)
                    } else { finish(dispatch, "未启动：${result.error}", now); cleanupEphemeral(dispatch) }
                    continue
                }
                else -> "未知命令。发送 /帮助 查看可用命令。"
            }
            finish(entry, response, now)
        }
    }
    private fun cleanupEphemeral(entry: RemoteEntry) {
        if (entry.ephemeral && store.templates.value.any { it.id == entry.taskId && it.activeRunId == null }) store.delete(entry.taskId)
    }
    private fun finish(entry: RemoteEntry, text: String, now: Long) {
        journal.reply(entry, "result", text, now)
        journal.update(entry.copy(state = "done"))
    }
    companion object { fun taskCode(task: TaskTemplate) = "T-" + task.id.take(8) }
}
