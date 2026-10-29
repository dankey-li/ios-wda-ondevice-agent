package com.ondeviceagent.android.runtime

import android.content.Context
import android.content.SharedPreferences
import com.ondeviceagent.android.OnDeviceAgentApp
import com.ondeviceagent.android.automation.ScreenGeometry
import com.ondeviceagent.android.automation.AccessibilitySnapshot
import com.ondeviceagent.android.automation.AgentAccessibilityService
import com.ondeviceagent.android.automation.AgentActionRequest
import com.ondeviceagent.android.automation.AgentActionResult
import com.ondeviceagent.android.automation.normalizeActionName
import com.ondeviceagent.android.automation.parseActionRequest
import com.ondeviceagent.android.automation.stringParam
import com.ondeviceagent.android.automation.point1000
import com.ondeviceagent.android.automation.doubleParam
import com.ondeviceagent.android.llm.ActionOutputRecovery
import com.ondeviceagent.android.llm.ActionParser
import com.ondeviceagent.android.llm.AgentPrompts
import com.ondeviceagent.android.llm.ConversationMemory
import com.ondeviceagent.android.llm.ModelHttpException
import com.ondeviceagent.android.llm.ModelClient
import com.ondeviceagent.android.llm.PlanItem
import com.ondeviceagent.android.llm.plus
import com.ondeviceagent.android.manager.PostRunPolicy
import com.ondeviceagent.android.manager.SubmissionPolicy
import com.ondeviceagent.android.manager.RunClaim
import com.ondeviceagent.android.manager.CompletionPolicy
import com.ondeviceagent.android.automation.ProgressTracker
import com.ondeviceagent.android.manager.TaskRunState
import com.ondeviceagent.android.manager.TaskTemplate
import com.ondeviceagent.android.model.AgentChatItem
import com.ondeviceagent.android.model.AgentConfigRequest
import com.ondeviceagent.android.model.AgentConfigSnapshot
import com.ondeviceagent.android.model.AgentStartPayload
import com.ondeviceagent.android.model.AgentStatus
import com.ondeviceagent.android.model.AgentTraceSummary
import com.ondeviceagent.android.model.PersistedAgentConfig
import com.ondeviceagent.android.model.TokenUsage
import com.ondeviceagent.android.model.TraceStatus
import com.ondeviceagent.android.notify.FeishuWebhookNotifier
import com.ondeviceagent.android.notify.TaskNotification
import com.ondeviceagent.android.notify.TaskNotificationPolicy
import com.ondeviceagent.android.screenshot.ScreenshotSession
import com.ondeviceagent.android.trace.TraceDetail
import com.ondeviceagent.android.trace.TraceRecorder

import android.util.Base64
import android.graphics.BitmapFactory
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/**
 * In-process agent runtime: screenshot → LLM → parse → execute.
 */
class AgentRuntime(
    context: Context,
    private val traceRecorder: TraceRecorder,
    private val executionEnvironment: ExecutionEnvironment? = null,
    private val taskNotificationSink: (suspend (TaskNotification) -> FeishuWebhookNotifier.SendResult)? = null,
) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val modelClient = ModelClient()

    @Volatile private var config: PersistedAgentConfig = loadConfig()
    private var running: Boolean = false
    private var lastMessage: String = "Android agent ready"
    private var notes: String = ""
    private var plan: List<PlanItem> = emptyList()
    private var tokenUsage: TokenUsage = TokenUsage()
    private val logLines = ArrayDeque<String>()
    private val chatItems = mutableListOf<AgentChatItem>()
    private var loopJob: Job? = null
    private var currentRunId: String = ""
    private var currentTaskId: String? = null
    private var currentTaskRevision: Long = 0
    private var currentStep: Int = 0
    private var lastRunResult: LastRunResult? = null

    private val _uiState = MutableStateFlow(UiSnapshot())
    val uiState: StateFlow<UiSnapshot> = _uiState.asStateFlow()

    init {
        RunCompletionNotifier.ensureChannel(appContext)
        appendLog("runtime", "AgentRuntime initialized (platform=android)")
        publishUi()
    }

    suspend fun status(includeDefaultSystemPrompt: Boolean = false): AgentStatus = mutex.withLock {
        buildStatus(includeDefaultSystemPrompt)
    }

    suspend fun applyConfig(request: AgentConfigRequest): AgentStatus = mutex.withLock {
        config = mergeConfig(config, request)
        persistConfig(config)
        lastMessage = "Config saved"
        appendLog("config", "Config updated (task_len=${config.task.length})")
        publishUiLocked()
        buildStatus(false)
    }

    suspend fun start(
        request: AgentConfigRequest?,
        showCompletionReminder: Boolean = true,
        taskId: String? = null,
        isRetry: Boolean = false,
        claim: RunClaim? = null,
    ): AgentStartPayload = mutex.withLock {
        if (running) return AgentStartPayload(ok = false, error = "already running", status = buildStatus(false))
        if (request != null) {
            config = mergeConfig(config, request)
            persistConfig(config)
        }
        val store = OnDeviceAgentApp.get().managerStore
        val candidate = taskId?.let { id -> store.templates.value.firstOrNull { it.id == id && it.task == config.task } }
        if (taskId != null && candidate == null) {
            return AgentStartPayload(ok = false, error = "任务已删除或内容已变化，请重新选择任务", status = buildStatus(false))
        }
        val retrying = isRetry || claim?.retryAt != null
        if (retrying && (claim?.retryAt ?: candidate?.pendingRetryAtEpochMs) == null) {
            return AgentStartPayload(ok = false, error = "本次重试已取消", status = buildStatus(false))
        }
        if (config.task.isBlank()) {
            return AgentStartPayload(ok = false, error = "task is required", errorKind = "blocked", status = buildStatus(false))
        }
        if (config.baseUrl.isBlank() || config.model.isBlank()) {
            return AgentStartPayload(
                ok = false,
                error = "base_url and model are required",
                errorKind = "blocked",
                status = buildStatus(false),
            )
        }
        if (config.apiKey.isBlank()) {
            return AgentStartPayload(
                ok = false,
                error = "api_key is required",
                errorKind = "blocked",
                status = buildStatus(false),
            )
        }
        if (config.maxSteps < 5) {
            return AgentStartPayload(
                ok = false,
                errorKind = "blocked",
                error = "max_steps 过小（当前 ${config.maxSteps}）。打开设置再回桌面通常需要 4+ 步，请在配置页改为 ≥20 后保存。",
                status = buildStatus(false),
            )
        }
        if (!(executionEnvironment?.ready() ?: AgentAccessibilityService.isConnected())) {
            return AgentStartPayload(
                ok = false,
                error = "无障碍服务未开启。请在系统设置中开启 OnDevice Agent（同时用于点击/滑动和默认截图）。",
                errorKind = "blocked",
                status = buildStatus(false),
            )
        }
        if (!(executionEnvironment?.ready() ?: ScreenshotSession.isReady())) {
            return AgentStartPayload(
                ok = false,
                error = "截图不可用。Android 11+ 开启无障碍即可截图；更低版本请在配置页授权 MediaProjection。",
                errorKind = "blocked",
                status = buildStatus(false),
            )
        }

        val newRunId = "android-" + Instant.now().toString().replace(":", "-") + "-" + UUID.randomUUID().toString().take(8)
        val runOwner = candidate?.let {
            store.claimRun(it.id, claim ?: RunClaim(it.executionRevision, retryAt = if (retrying) it.pendingRetryAtEpochMs else null), newRunId)
                ?: return AgentStartPayload(ok = false, error = "任务已修改、暂停或本次执行已取消", status = buildStatus(false))
        }
        currentTaskId = runOwner?.id
        currentTaskRevision = runOwner?.executionRevision ?: 0
        running = true
        currentRunId = newRunId
        lastRunResult = lastRunResult?.copy(dismissed = true)
        currentStep = 0
        notes = ""
        plan = emptyList()
        tokenUsage = TokenUsage()
        chatItems.clear()
        lastMessage = "Started"
        appendLog("start", "Loop started run_id=$currentRunId task=${config.task.take(80)}")
        traceRecorder.begin(currentRunId, config.task, taskId.orEmpty(), if (retrying) runOwner?.retrySourceRunId.orEmpty() else "", if (retrying) runOwner?.retryAttempt ?: 0 else 0)
        publishUiLocked()
        startAgentLoopLocked(showCompletionReminder, runOwner, if (retrying) runOwner?.retryContext.orEmpty() else "")
        AgentStartPayload(ok = true, status = buildStatus(false))
    }

    suspend fun stop(): AgentStatus = mutex.withLock {
        currentTaskId?.let {
            val store = OnDeviceAgentApp.get().managerStore
            store.cancelRetry(it)
            store.markRun(it, TaskRunState.stopped, "stopped by user")
        }
        stopLoopLocked("stopped by user")
        buildStatus(false)
    }

    suspend fun reset(): AgentStatus = mutex.withLock {
        stopLoopLocked("reset")
        notes = ""
        tokenUsage = TokenUsage()
        chatItems.clear()
        logLines.clear()
        lastMessage = "Reset"
        appendLog("reset", "Runtime reset (config kept)")
        publishUiLocked()
        buildStatus(false)
    }

    suspend fun factoryReset(): AgentStatus = mutex.withLock {
        stopLoopLocked("factory_reset")
        config = PersistedAgentConfig()
        persistConfig(config)
        notes = ""
        tokenUsage = TokenUsage()
        chatItems.clear()
        logLines.clear()
        lastMessage = "Factory reset"
        appendLog("reset", "Factory reset complete")
        publishUiLocked()
        buildStatus(false)
    }

    suspend fun logs(): List<String> = mutex.withLock { logLines.toList() }

    suspend fun chat(): List<AgentChatItem> = mutex.withLock { chatItems.toList() }

    fun traces(): List<AgentTraceSummary> = traceRecorder.listSummaries()

    fun loadModelOutputs(runId: String, step: Int) = traceRecorder.loadModelOutputs(runId, step)
    fun clearCompletedHistory() = traceRecorder.clearCompletedHistory()

    fun loadTraceDetail(runId: String): TraceDetail? = traceRecorder.loadDetail(runId)

    fun loadTraceStepBitmap(runId: String, step: Int) = traceRecorder.loadStepBitmap(runId, step)

    fun dismissLastRunResult() {
        scope.launch {
            mutex.withLock {
                lastRunResult = lastRunResult?.copy(dismissed = true)
                publishUiLocked()
            }
        }
    }

    fun currentConfig(): PersistedAgentConfig = config

    suspend fun snapshotConfig(): PersistedAgentConfig = mutex.withLock { config }

    suspend fun performAction(request: AgentActionRequest): AgentActionResult {
        val parsed = parseActionRequest(request)
            ?: return AgentActionResult(ok = false, error = "Missing action name", errorKind = "invalid_params")
        executionEnvironment?.let { return it.perform(request) }

        if (parsed.name == "note") {
            val message = parsed.params.stringParam("message").orEmpty()
            mutex.withLock {
                notes = message
                appendLog("action", "note updated len=${message.length}")
                publishUiLocked()
            }
            return AgentActionResult(ok = true, name = "note")
        }

        if (parsed.name == "wait") {
            var seconds = parsed.params.doubleParam("seconds", "duration", default = 0.0)
            if (seconds <= 0.0) seconds = 1.0
            kotlinx.coroutines.delay((seconds * 1000).toLong().coerceAtLeast(50L))
            mutex.withLock {
                appendLog("action", "ok name=wait")
                lastMessage = "Action wait ok"
                publishUiLocked()
            }
            return AgentActionResult(ok = true, name = "wait")
        }

        if (parsed.name == "launch") {
            val appName = parsed.params.stringParam("app", "app_name")
            val packageName = parsed.params.stringParam(
                "package",
                "package_name",
                "bundle_id",
                "bundleId",
                "bundleID",
            )
            val resolved = com.ondeviceagent.android.automation.AppLauncher.resolvePackage(
                appContext,
                appName,
                packageName,
            )
            val result = if (resolved == null) {
                AgentActionResult(
                    ok = false,
                    error = "launch requires app (e.g. '设置') or package/bundle_id; got app=${appName ?: ""} package=${packageName ?: ""}",
                    errorKind = if (!appName.isNullOrBlank()) "launch_not_in_map" else "invalid_params",
                    name = "launch",
                )
            } else if (com.ondeviceagent.android.automation.AppLauncher.launch(appContext, resolved)) {
                AgentActionResult(ok = true, name = "launch")
            } else {
                AgentActionResult(
                    ok = false,
                    error = "failed to launch package=$resolved",
                    errorKind = "action_failed",
                    name = "launch",
                )
            }
            mutex.withLock {
                appendLog(
                    "action",
                    if (result.ok) "ok name=launch package=$resolved" else "fail name=launch err=${result.error}",
                )
                lastMessage = if (result.ok) "Action launch ok" else result.error
                publishUiLocked()
            }
            return result
        }

        val result = AgentAccessibilityService.performOrError(parsed)
        mutex.withLock {
            appendLog(
                "action",
                if (result.ok) {
                    "ok name=${result.name}"
                } else {
                    "fail name=${parsed.name} kind=${result.errorKind} err=${result.error}"
                },
            )
            lastMessage = if (result.ok) "Action ${result.name} ok" else result.error
            publishUiLocked()
        }
        return result
    }

    fun accessibilityEnabledHint(enabled: Boolean) {
        scope.launch {
            mutex.withLock {
                appendLog(
                    "a11y",
                    if (enabled) "AccessibilityService connected" else "AccessibilityService disconnected",
                )
                publishUiLocked()
            }
        }
    }

    fun refreshUi() {
        publishUi()
    }

    private fun startAgentLoopLocked(showCompletionReminder: Boolean, runOwner: TaskTemplate?, retryContext: String) {
        loopJob?.cancel()
        val cfg = config
        val maxSteps = cfg.maxSteps.coerceIn(1, 500)
        val delayMs = (cfg.stepDelaySeconds * 1000).toLong().coerceAtLeast(100L)
        val runId = currentRunId
        loopJob = scope.launch {
            var success = false
            var stopReason = "max_steps"
            var finalMessage = ""
            val recentActions = mutableListOf<String>()
            var consecutiveActionFailures = 0
            var useFullResolutionNext = false
            val progress = ProgressTracker()
            val memory = ConversationMemory()
            try {
                withTimeout((runOwner?.maxRunMinutes ?: 15).coerceIn(1, 180) * 60_000L) {
                for (step in 0 until maxSteps) {
                    if (!isActive) {
                        stopReason = "cancelled"
                        break
                    }
                    val stillRunning = mutex.withLock { running }
                    if (!stillRunning) {
                        stopReason = "stopped"
                        break
                    }

                    mutex.withLock {
                        currentStep = step
                        lastMessage = "Step $step: capturing screen"
                        publishUiLocked()
                    }

                    val screenshot = if (executionEnvironment != null) executionEnvironment.screenshot(cfg.halfResScreenshot && !useFullResolutionNext)
                    else ScreenshotSession.captureJpegBase64(halfRes = cfg.halfResScreenshot && !useFullResolutionNext)
                    if (screenshot == null) {
                        mutex.withLock {
                            appendLog("warn", "screenshot unavailable; check Accessibility (or MediaProjection fallback)")
                        }
                    } else {
                        traceRecorder.saveImageJpegBase64(step, screenshot)
                    }

                    val screenInfo = executionEnvironment?.screenInfo(step) ?: buildScreenInfo(step, screenshot)
                    val userText = buildUserText(
                        step = step,
                        task = cfg.task,
                        notes = notes,
                        plan = plan,
                        screenInfo = screenInfo,
                        recentActions = recentActions.toList(),
                    ) + "\n任务成功判据：${runOwner?.completionText?.ifBlank { "必须提供当前页面成功证据" } ?: "必须提供成功证据"}" + if (retryContext.isNotBlank()) "\n\n失败后的恢复上下文（先核对当前页面，确认已完成内容后再继续，禁止重复提交）：\n$retryContext" else ""
                    mutex.withLock {
                        chatItems += AgentChatItem(
                            ts = Instant.now().toString(),
                            step = step,
                            kind = "request",
                            text = userText.take(2000),
                        )
                        lastMessage = "Step $step: calling model"
                        publishUiLocked()
                    }

                    val systemPrompt = (if (cfg.useCustomSystemPrompt && cfg.systemPrompt.isNotBlank()) {
                        cfg.systemPrompt
                    } else {
                        AgentPrompts.defaultSystemPrompt()
                    }) + "\n\n" + AgentPrompts.executionContract()

                    val modelResult = try {
                        modelClient.complete(
                            baseUrl = cfg.baseUrl,
                            apiKey = cfg.apiKey,
                            model = cfg.model,
                            apiMode = cfg.apiMode,
                            systemPrompt = systemPrompt,
                            userText = userText,
                            screenshotJpegBase64 = screenshot,
                            timeoutSeconds = cfg.timeoutSeconds,
                            maxCompletionTokens = cfg.maxCompletionTokens,
                            insecureSkipTls = cfg.insecureSkipTlsVerify,
                            reasoningEffort = cfg.reasoningEffort,
                            history = memory.snapshot(),
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (t: Exception) {
                        mutex.withLock {
                            appendLog("error", "model call failed: ${t.message}")
                            lastMessage = "Model error: ${t.message}"
                            publishUiLocked()
                        }
                        stopReason = if (t is ModelHttpException && !t.retryable) "blocked" else "model_error"
                        finalMessage = t.message.orEmpty()
                        break
                    }

                    mutex.withLock {
                        tokenUsage = tokenUsage.plus(modelResult.usage)
                        chatItems += AgentChatItem(
                            ts = Instant.now().toString(),
                            step = step,
                            kind = "response",
                            text = modelResult.text.take(4000),
                            raw = if (cfg.debugLogRawAssistant) modelResult.raw.take(8000) else null,
                        )
                        publishUiLocked()
                    }

                    traceRecorder.saveModelOutput(step, 0, modelResult.text, modelResult.finishReason)
                    val parsed = try {
                        ActionOutputRecovery.parse(modelResult.text) { badOutput, error, attempt ->
                            mutex.withLock { appendLog("warn", "JSON 格式补救 $attempt/2；期间不执行动作") }
                            val fixed = modelClient.complete(
                                baseUrl = cfg.baseUrl, apiKey = cfg.apiKey, model = cfg.model,
                                apiMode = cfg.apiMode,
                                systemPrompt = systemPrompt,
                                userText = userText + "\n\n" + ActionOutputRecovery.repairPrompt(badOutput, error),
                                screenshotJpegBase64 = screenshot,
                                timeoutSeconds = cfg.timeoutSeconds,
                                maxCompletionTokens = cfg.maxCompletionTokens,
                                insecureSkipTls = cfg.insecureSkipTlsVerify,
                                reasoningEffort = cfg.reasoningEffort,
                                history = memory.snapshot(),
                            )
                            traceRecorder.saveModelOutput(step, attempt, fixed.text, fixed.finishReason)
                            mutex.withLock {
                                tokenUsage = tokenUsage.plus(fixed.usage)
                                chatItems += AgentChatItem(
                                    ts = Instant.now().toString(), step = step, kind = "response",
                                    text = fixed.text.take(4000),
                                )
                                publishUiLocked()
                            }
                            fixed.text
                        }.output
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (t: Exception) {
                        mutex.withLock {
                            appendLog("error", "parse recovery failed: ${t.message}")
                        }
                        stopReason = if (t is ModelHttpException) { if (t.retryable) "model_error" else "blocked" } else "parse_error"
                        finalMessage = t.message.orEmpty()
                        traceRecorder.appendTurn(step, modelResult.text, "parse_error", false, finalMessage)
                        break
                    }

                    mutex.withLock {
                        plan = ActionParser.mergePlan(plan, parsed.plan)
                        notes = if (normalizeActionName(parsed.actionName) == "note") {
                            parsed.actionParams.stringParam("message").orEmpty()
                        } else {
                            notes
                        }
                        lastMessage = "Step $step: ${parsed.actionName}"
                        publishUiLocked()
                    }

                    var actionName = normalizeActionName(parsed.actionName)

                    val currentOwner = runOwner?.let { owner -> OnDeviceAgentApp.get().managerStore.templates.value.firstOrNull { it.id == owner.id } }
                    if (runOwner != null && (currentOwner == null || currentOwner.executionRevision != runOwner.executionRevision)) {
                        stopReason = "cancelled"
                        finalMessage = "任务已修改或取消，旧版本停止执行"
                        break
                    }
                    if (actionName == "finish") {
                        val fresh = executionEnvironment?.screenInfo(step) ?: AgentAccessibilityService.instance?.let { AccessibilitySnapshot.captureJson(it) }.orEmpty()
                        val visible = runCatching {
                            JSONObject(fresh).optJSONArray("ui")?.let { nodes ->
                                (0 until nodes.length()).joinToString("\n") { index ->
                                    val node = nodes.optJSONObject(index)
                                    node?.optString("text").orEmpty() + " " + node?.optString("desc").orEmpty()
                                }
                            }.orEmpty()
                        }.getOrDefault("")
                        val decision = CompletionPolicy.evaluate(
                            parsed.actionParams.stringParam("status").orEmpty(),
                            parsed.actionParams.stringParam("evidence").orEmpty(),
                            parsed.actionParams.stringParam("message").orEmpty(),
                            runOwner?.completionText.orEmpty(), visible,
                        )
                        success = decision.success
                        stopReason = decision.reason
                        finalMessage = decision.message
                        traceRecorder.appendTurn(step, parsed.rawJson, "finish", success, if (success) "" else finalMessage)
                        break
                    }

                    // 模型思考期间前台应用或屏幕方向改变时，不执行过期截图上的动作。
                    val observed = JSONObject(screenInfo)
                    val service = AgentAccessibilityService.instance
                    val currentPackage = executionEnvironment?.let { JSONObject(it.screenInfo(step)).optString("package") } ?: service?.rootInActiveWindow?.packageName?.toString().orEmpty()
                    val size = service?.let { ScreenGeometry.size(it) }
                    fun interactiveSignature(info: JSONObject): String {
                        val nodes = info.optJSONArray("ui") ?: return ""
                        return (0 until nodes.length()).mapNotNull { index -> nodes.optJSONObject(index)?.takeIf {
                            it.optBoolean("clickable") || it.optBoolean("editable")
                        }?.let { "${it.optString("id")}:${it.optString("text")}:${it.optString("desc")}:${it.optJSONArray("bounds")}" } }.sorted().joinToString("|")
                    }
                    val latest = executionEnvironment?.let { JSONObject(it.screenInfo(step)) } ?: service?.let { JSONObject(AccessibilitySnapshot.captureJson(it)) }
                    val oldLayout = interactiveSignature(observed)
                    val coordinateStale = parsed.actionParams.containsKey("element") &&
                        oldLayout.isNotBlank() && latest != null && oldLayout != interactiveSignature(latest)
                    val observationChanged = actionName in setOf("tap", "double_tap", "long_press", "swipe", "type") &&
                        (coordinateStale || (observed.optString("package").isNotBlank() && currentPackage != observed.optString("package")) ||
                            (size != null && observed.optInt("width_px") > 0 &&
                                size != (observed.optInt("width_px") to observed.optInt("height_px"))))
                    val targetSignature = parsed.actionParams.toString()
                    val frame = observed.optString("package") + observed.optJSONArray("ui")?.toString().orEmpty()
                        .replace(Regex("\\b\\d{1,2}:\\d{2}(?::\\d{2})?\\b"), "时间")
                    val stalled = progress.stalled(frame, actionName, targetSignature)
                    if (stalled) {
                        stopReason = "stalled"
                        finalMessage = "相同页面和目标连续多次无进展，停止继续点击"
                        break
                    }
                    val noVisualGrounding = actionName in setOf("tap", "double_tap", "long_press", "swipe") &&
                        parsed.actionParams.containsKey("element") && (screenshot == null || observed.optBoolean("screenshot_black")) &&
                        !parsed.actionParams.containsKey("text") && !parsed.actionParams.containsKey("resource_id")
                    val requestedLabel = parsed.actionParams.stringParam("text", "label").orEmpty()
                    val actualLabel = if (requestedLabel.isNotBlank()) {
                        val labels = observed.optJSONArray("ui")?.let { nodes ->
                            (0 until nodes.length()).flatMap { index ->
                                val node = nodes.optJSONObject(index)
                                listOf(node?.optString("text").orEmpty(), node?.optString("desc").orEmpty())
                            }
                        }.orEmpty()
                        SubmissionPolicy.resolveLabel(requestedLabel, labels)
                    } else ""
                    val label = actualLabel.ifBlank { requestedLabel }.ifBlank {
                        val wantedId = parsed.actionParams.stringParam("resource_id")
                        val point = parsed.actionParams.point1000("element")
                        observed.optJSONArray("ui")?.let { nodes ->
                            (0 until nodes.length()).mapNotNull { nodes.optJSONObject(it) }.filter { node ->
                                if (wantedId != null) node.optString("id") == wantedId
                                else node.optJSONArray("bounds")?.let { bounds -> point != null && bounds.length() == 4 &&
                                    point.first in bounds.optDouble(0)..bounds.optDouble(2) && point.second in bounds.optDouble(1)..bounds.optDouble(3) } == true
                            }.minByOrNull { node -> node.optJSONArray("bounds")?.let {
                                (it.optDouble(2) - it.optDouble(0)) * (it.optDouble(3) - it.optDouble(1))
                            } ?: Double.MAX_VALUE }?.let { it.optString("text").ifBlank { it.optString("desc") } }.orEmpty()
                        }.orEmpty()
                    }
                    val guarded = actionName in setOf("tap", "double_tap", "long_press") && runOwner != null &&
                        SubmissionPolicy.protected(label, runOwner.guardedActionTexts)
                    if (guarded && actionName != "tap") {
                        stopReason = "blocked"
                        finalMessage = "受保护的提交按钮仅允许单击，已阻止重复或长按提交"
                        break
                    }
                    val submissionAllowed = if (guarded && !observationChanged && !noVisualGrounding)
                        OnDeviceAgentApp.get().managerStore.markSubmission(runOwner!!.id, runId, label) else true
                    if (!submissionAllowed) {
                        stopReason = "blocked"
                        finalMessage = "上次提交结果尚未核对，已阻止重复操作；请查看结果后解除提交保护"
                        break
                    }
                    val actionStarted = android.os.SystemClock.elapsedRealtime()
                    val actionResult = when {
                        observationChanged -> AgentActionResult(false, "前台应用或方向已变化，请重新观察", "stale_screen", actionName)
                        noVisualGrounding -> AgentActionResult(false, "截图不可用，禁止猜测坐标，请使用可见控件定位", "invalid_params", actionName)
                        else -> performAction(AgentActionRequest(name = actionName, params = parsed.actionParams))
                    }
                    memory.record(actionName, parsed.actionParams, actionResult)
                    traceRecorder.appendTurn(
                        step,
                        parsed.rawJson,
                        actionName,
                        actionResult.ok,
                        actionResult.error,
                        element = actionResult.resolvedElement,
                        durationMs = android.os.SystemClock.elapsedRealtime() - actionStarted,
                    )
                    // 失败或返回纠错后的下一帧保留原分辨率，帮助识别小图标。
                    useFullResolutionNext = !actionResult.ok || actionName == "back"
                    val target = if (actionName in setOf("tap", "double_tap", "long_press", "swipe", "launch")) {
                        parsed.actionParams.toString()
                    } else "{}"
                    recentActions += "$actionName $target => ${if (actionResult.ok) "已派发，需从新截图确认效果" else "失败：${actionResult.error}"}"
                    if (recentActions.size > 8) recentActions.removeAt(0)
                    if (!actionResult.ok) {
                        consecutiveActionFailures++
                        mutex.withLock {
                            appendLog("warn", "action failed, continuing: ${actionResult.error}")
                        }
                    } else {
                        consecutiveActionFailures = 0
                    }
                    if (consecutiveActionFailures >= 3) {
                        stopReason = "action_failed"
                        finalMessage = "动作连续失败 3 次，已停止，避免继续误点"
                        break
                    }
                    delay(delayMs)
                }
                }
            } catch (timeout: TimeoutCancellationException) {
                stopReason = "run_timeout"
                finalMessage = "本次运行超过任务设置的最长执行时间"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                stopReason = "runtime_error"
                finalMessage = failure.message.orEmpty()
            } finally {
                mutex.withLock {
                    if (running && currentRunId == runId) {
                        val msg = when {
                            success -> finalMessage.ifBlank { "finished" }
                            stopReason == "stopped" -> "stopped by user"
                            else -> finalMessage.ifBlank { stopReason }
                        }
                        val finishedRunId = currentRunId
                        val finishedTask = cfg.task
                        traceRecorder.finish(success, msg, stopReason)
                        lastRunResult = LastRunResult(
                            success = success,
                            message = msg,
                            stopReason = stopReason,
                            runId = finishedRunId,
                            task = finishedTask,
                            // 任务中心手动运行和定时运行均不弹窗，结果保留在历史中。
                            dismissed = !showCompletionReminder || runOwner != null,
                        )
                        if (showCompletionReminder) runCatching {
                            RunCompletionNotifier.notifyResult(
                                context = appContext,
                                success = success,
                                task = finishedTask,
                                message = msg,
                                runId = finishedRunId,
                            )
                        }
                        var taskNotification: TaskNotification? = null
                        runCatching {
                            val store = OnDeviceAgentApp.get().managerStore
                            val finished = runOwner?.let { owner -> store.finishRun(
                                owner.id, finishedRunId, owner.executionRevision, success, stopReason, msg,
                                "上次失败原因：$stopReason。\n计划：\n" + plan.joinToString("\n") { "[${it.id}] ${if (it.done) "已完成待核对" else "待执行"} ${it.text}" } + "\n工作记忆：$notes",
                            ) }
                            taskNotification = finished?.let { TaskNotificationPolicy.create(it, success, stopReason, msg, finishedRunId) }

                        }
                        val lastImage = if (taskNotification != null) {
                            runCatching { traceRecorder.lastImageBytes(finishedRunId) }.getOrNull()
                        } else null
                        // 历史、截图和任务状态先落盘；互斥期间完成收尾，防止旧任务抢占新任务。
                        if (runOwner != null) {
                            val store = OnDeviceAgentApp.get().managerStore
                            fun allowed(): Boolean {
                                val stored = store.templates.value.firstOrNull { it.id == runOwner.id } ?: return false
                                return stored.activeRunId == null && stored.lastRun.state != TaskRunState.running &&
                                    PostRunPolicy.shouldRun(runOwner, success, stopReason, stored.executionRevision)
                            }
                            if (allowed()) {
                                val cleanupMessage = try {
                                    kotlinx.coroutines.withTimeoutOrNull(6500) {
                                        executionEnvironment?.postRun(runOwner)
                                            ?: PostRunController(appContext).execute(runOwner, ::allowed)
                                    } ?: "结束后操作超时，业务结果已保存"
                                } catch (error: Exception) {
                                    if (error is CancellationException) throw error
                                    "结束后操作失败：${error.message.orEmpty()}"
                                }
                                runCatching { traceRecorder.recordPostRun(finishedRunId, cleanupMessage) }
                                appendLog("info", "结束后操作：$cleanupMessage")
                            }
                        }
                        stopLoopLocked(msg, finishedTrace = true)
                        taskNotification?.copy(lastImageJpeg = lastImage)?.let { dispatchTaskNotification(it) }

                    }
                }
            }
        }
    }

    /** 启动失败及重试到期只发送结果，不改变领取、计划或重试状态。 */
    fun notifyTaskStartFailure(task: TaskTemplate, reason: String) {
        TaskNotificationPolicy.create(task, false, reason, task.lastRun.message, "")?.let { dispatchTaskNotification(it) }
    }

    private fun dispatchTaskNotification(notification: TaskNotification) {
        val cfg = config
        scope.launch(Dispatchers.IO) {
            val result = try {
                when {
                    taskNotificationSink != null -> taskNotificationSink.invoke(notification)
                    cfg.feishuWebhookUrl.isBlank() -> FeishuWebhookNotifier.SendResult(false, "任务通知已开启，但未配置飞书 Webhook")
                    else -> FeishuWebhookNotifier.sendTaskResult(
                        FeishuWebhookNotifier.Credentials(cfg.feishuWebhookUrl, cfg.feishuAppId, cfg.feishuAppSecret),
                        notification.title, notification.task, notification.message, notification.runId,
                        notification.success, notification.lastImageJpeg, notification.heading, notification.color,
                    )
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { FeishuWebhookNotifier.SendResult(false, "飞书通知发送异常，业务结果已保留") }
            mutex.withLock {
                appendLog(if (result.ok) "info" else "warn", "feishu notify: ${result.detail}")
                publishUiLocked()
            }
        }
    }

    suspend fun sendTestFeishuNotify(): FeishuWebhookNotifier.SendResult {
        val creds = mutex.withLock {
            FeishuWebhookNotifier.Credentials(
                webhookUrl = config.feishuWebhookUrl,
                appId = config.feishuAppId,
                appSecret = config.feishuAppSecret,
            )
        }
        val sample = runCatching {
            traceRecorder.listSummaries().firstOrNull()?.let { traceRecorder.lastImageBytes(it.runId) }
        }.getOrNull()
        return FeishuWebhookNotifier.sendTest(creds, sampleImageJpeg = sample)
    }

    suspend fun verifyFeishuCredentials(): FeishuWebhookNotifier.SendResult {
        val (id, secret) = mutex.withLock { config.feishuAppId to config.feishuAppSecret }
        return FeishuWebhookNotifier.verifyCredentials(id, secret)
    }

    private fun buildUserText(
        step: Int,
        task: String,
        notes: String,
        plan: List<PlanItem>,
        screenInfo: String,
        recentActions: List<String> = emptyList(),
    ): String {
        return buildString {
            if (step == 0) {
                append("Task:\n").append(task).append("\n\n")
            } else {
                append("Task (reminder):\n").append(task).append("\n\n")
                if (plan.isNotEmpty()) {
                    append("Plan checklist:\n")
                    plan.forEachIndexed { i, item ->
                        append(i + 1).append(". [id:").append(item.id).append("] ")
                            .append(if (item.done) "[done] " else "[ ] ")
                            .append(item.text).append('\n')
                    }
                    append('\n')
                }
                if (notes.isNotBlank()) {
                    append("Working notes:\n").append(notes).append("\n\n")
                }
                if (recentActions.isNotEmpty()) {
                    append("Recent actions (oldest→newest): ")
                        .append(recentActions.joinToString(" → "))
                        .append("\n\n")
                }

            }
            append("Screen Info:\n").append(screenInfo)
        }
    }

    private fun buildScreenInfo(step: Int, screenshotJpegBase64: String?): String {
        val black = screenshotJpegBase64 != null && isMostlyBlackJpeg(screenshotJpegBase64)
        val a11y = AgentAccessibilityService.instance?.let { AccessibilitySnapshot.captureJson(it) }
            ?: """{"ui":[],"package":"","note":"a11y_unavailable"}"""
        return try {
            val uiObj = JSONObject(a11y)
            JSONObject()
                .put("platform", "android")
                .put("step", step)
                .put("screenshot", screenshotJpegBase64 != null)
                .put("screenshot_black", black)
                .put("package", uiObj.optString("package"))
                .put("width_px", uiObj.optInt("width_px"))
                .put("height_px", uiObj.optInt("height_px"))
                .put("coordinate_system", "full_display_0_1000_including_system_bars")
                .put("ui", uiObj.optJSONArray("ui"))
                .put(
                    "hint",
                    if (black) {
                        "Screenshot is black (likely FLAG_SECURE password/login). Use ui[].text with Tap params.text; do not Wait repeatedly."
                    } else {
                        uiObj.optString(
                            "hint",
                            "Prefer Tap params.text for labeled buttons when coordinates are uncertain.",
                        )
                    },
                )
                .toString()
        } catch (_: Throwable) {
            """{"platform":"android","step":$step,"screenshot":${screenshotJpegBase64 != null},"screenshot_black":$black,"ui":[]}"""
        }
    }

    private fun isMostlyBlackJpeg(jpegBase64: String): Boolean {
        return runCatching {
            val bytes = Base64.decode(jpegBase64, Base64.DEFAULT)
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return false
            val w = bmp.width.coerceAtLeast(1)
            val h = bmp.height.coerceAtLeast(1)
            var dark = 0
            var total = 0
            // Sample a sparse grid for speed.
            val stepX = (w / 16).coerceAtLeast(1)
            val stepY = (h / 16).coerceAtLeast(1)
            var y = 0
            while (y < h) {
                var x = 0
                while (x < w) {
                    val c = bmp.getPixel(x, y)
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    if (r + g + b < 48) dark++
                    total++
                    x += stepX
                }
                y += stepY
            }
            bmp.recycle()
            total > 0 && dark.toDouble() / total >= 0.92
        }.getOrDefault(false)
    }

    private fun stopLoopLocked(reason: String, finishedTrace: Boolean = false) {
        loopJob?.cancel()
        loopJob = null
        if (running) {
            running = false
            lastMessage = reason
            appendLog("stop", reason)
            if (!finishedTrace && currentRunId.isNotBlank()) {
                currentTaskId?.let { OnDeviceAgentApp.get().managerStore.finishRun(it, currentRunId, currentTaskRevision, false, "stopped", reason, "") }
                traceRecorder.finish(false, reason, "stopped")
            }
            currentRunId = ""
            currentTaskId = null
            publishUiLocked()
        }
    }

    private fun buildStatus(includeDefaultSystemPrompt: Boolean): AgentStatus {
        return AgentStatus(
            running = running,
            lastMessage = lastMessage,
            config = config.toSnapshot(includeDefaultSystemPrompt),
            notes = notes,
            tokenUsage = tokenUsage,
            logLines = logLines.size,
            trace = TraceStatus(runId = currentRunId, active = running && currentRunId.isNotBlank()),
            platform = "android",
        )
    }

    private fun appendLog(tag: String, message: String) {
        val line = "${Instant.now()} [$tag] $message"
        logLines.addLast(line)
        while (logLines.size > MAX_LOG_LINES) {
            logLines.removeFirst()
        }
    }

    private fun publishUi() {
        scope.launch {
            mutex.withLock { publishUiLocked() }
        }
    }

    private fun publishUiLocked() {
        _uiState.update {
            UiSnapshot(
                running = running,
                lastMessage = lastMessage,
                task = config.task,
                baseUrl = config.baseUrl,
                model = config.model,
                apiKey = if (config.rememberApiKey) config.apiKey else "",
                apiMode = config.apiMode,
                maxSteps = config.maxSteps,
                maxCompletionTokens = config.maxCompletionTokens,
                timeoutSeconds = config.timeoutSeconds,
                stepDelaySeconds = config.stepDelaySeconds,
                reasoningEffort = config.reasoningEffort,
                halfResScreenshot = config.halfResScreenshot,
                rememberApiKey = config.rememberApiKey,
                useCustomSystemPrompt = config.useCustomSystemPrompt,
                systemPrompt = config.systemPrompt,
                debugLogRawAssistant = config.debugLogRawAssistant,
                insecureSkipTlsVerify = config.insecureSkipTlsVerify,
                doubaoSeedEnableSessionCache = false,
                restartResponsesByPlan = false,
                feishuWebhookUrl = config.feishuWebhookUrl,
                feishuAppId = config.feishuAppId,
                feishuAppSecret = config.feishuAppSecret,
                notes = notes,
                currentStep = currentStep,
                tokenUsage = tokenUsage,
                chatPreview = chatItems.takeLast(20),
                logPreview = logLines.takeLast(40).toList(),
                serverPort = AgentHttpServer.PORT,
                accessibilityConnected = AgentAccessibilityService.isConnected(),
                screenshotReady = ScreenshotSession.isReady(),
                lastRunResult = lastRunResult,
            )
        }
    }

    private fun loadConfig(): PersistedAgentConfig {
        return PersistedAgentConfig(
            task = prefs.getString(KEY_TASK, "") ?: "",
            baseUrl = prefs.getString(KEY_BASE_URL, "") ?: "",
            model = prefs.getString(KEY_MODEL, "") ?: "",
            apiMode = prefs.getString(KEY_API_MODE, "responses") ?: "responses",
            apiKey = prefs.getString(KEY_API_KEY, "") ?: "",
            agentToken = prefs.getString(KEY_AGENT_TOKEN, "") ?: "",
            useCustomSystemPrompt = prefs.getBoolean(KEY_USE_CUSTOM_PROMPT, false),
            systemPrompt = prefs.getString(KEY_SYSTEM_PROMPT, "") ?: "",
            rememberApiKey = prefs.getBoolean(KEY_REMEMBER_API_KEY, true),
            debugLogRawAssistant = prefs.getBoolean(KEY_DEBUG_RAW, false),
            reasoningEffort = prefs.getString(KEY_REASONING, "") ?: "",
            doubaoSeedEnableSessionCache = prefs.getBoolean(KEY_DOUBAO_CACHE, true),
            halfResScreenshot = prefs.getBoolean(KEY_HALF_RES, true),
            useW3cActionsForSwipe = prefs.getBoolean(KEY_W3C_SWIPE, false),
            restartResponsesByPlan = prefs.getBoolean(KEY_RESTART_BY_PLAN, false),
            maxSteps = prefs.getInt(KEY_MAX_STEPS, 120),
            maxCompletionTokens = prefs.getInt(KEY_MAX_TOKENS, 4096),
            timeoutSeconds = prefs.getFloat(KEY_TIMEOUT, 90f).toDouble(),
            stepDelaySeconds = prefs.getFloat(KEY_STEP_DELAY, 0.5f).toDouble(),
            insecureSkipTlsVerify = prefs.getBoolean(KEY_INSECURE_TLS, false),
            feishuWebhookUrl = prefs.getString(KEY_FEISHU_WEBHOOK, "") ?: "",
            feishuAppId = prefs.getString(KEY_FEISHU_APP_ID, "") ?: "",
            feishuAppSecret = prefs.getString(KEY_FEISHU_APP_SECRET, "") ?: "",
        )
    }

    private fun persistConfig(cfg: PersistedAgentConfig) {
        prefs.edit()
            .putString(KEY_TASK, cfg.task)
            .putString(KEY_BASE_URL, cfg.baseUrl)
            .putString(KEY_MODEL, cfg.model)
            .putString(KEY_API_MODE, cfg.apiMode)
            .putString(KEY_API_KEY, if (cfg.rememberApiKey) cfg.apiKey else "")
            .putString(KEY_AGENT_TOKEN, cfg.agentToken)
            .putBoolean(KEY_USE_CUSTOM_PROMPT, cfg.useCustomSystemPrompt)
            .putString(KEY_SYSTEM_PROMPT, cfg.systemPrompt)
            .putBoolean(KEY_REMEMBER_API_KEY, cfg.rememberApiKey)
            .putBoolean(KEY_DEBUG_RAW, cfg.debugLogRawAssistant)
            .putString(KEY_REASONING, cfg.reasoningEffort)
            .putBoolean(KEY_DOUBAO_CACHE, cfg.doubaoSeedEnableSessionCache)
            .putBoolean(KEY_HALF_RES, cfg.halfResScreenshot)
            .putBoolean(KEY_W3C_SWIPE, cfg.useW3cActionsForSwipe)
            .putBoolean(KEY_RESTART_BY_PLAN, cfg.restartResponsesByPlan)
            .putInt(KEY_MAX_STEPS, cfg.maxSteps)
            .putInt(KEY_MAX_TOKENS, cfg.maxCompletionTokens)
            .putFloat(KEY_TIMEOUT, cfg.timeoutSeconds.toFloat())
            .putFloat(KEY_STEP_DELAY, cfg.stepDelaySeconds.toFloat())
            .putBoolean(KEY_INSECURE_TLS, cfg.insecureSkipTlsVerify)
            .putString(KEY_FEISHU_WEBHOOK, cfg.feishuWebhookUrl)
            .putString(KEY_FEISHU_APP_ID, cfg.feishuAppId)
            .putString(KEY_FEISHU_APP_SECRET, cfg.feishuAppSecret)
            .apply()
    }

    private fun mergeConfig(base: PersistedAgentConfig, req: AgentConfigRequest): PersistedAgentConfig {
        return base.copy(
            task = req.task ?: base.task,
            baseUrl = req.baseUrl ?: base.baseUrl,
            model = req.model ?: base.model,
            apiMode = req.apiMode ?: base.apiMode,
            apiKey = req.apiKey ?: base.apiKey,
            agentToken = req.agentToken ?: base.agentToken,
            useCustomSystemPrompt = req.useCustomSystemPrompt ?: base.useCustomSystemPrompt,
            systemPrompt = req.systemPrompt ?: base.systemPrompt,
            rememberApiKey = req.rememberApiKey ?: base.rememberApiKey,
            debugLogRawAssistant = req.debugLogRawAssistant ?: base.debugLogRawAssistant,
            doubaoSeedEnableSessionCache = req.doubaoSeedEnableSessionCache
                ?: base.doubaoSeedEnableSessionCache,
            halfResScreenshot = req.halfResScreenshot ?: base.halfResScreenshot,
            useW3cActionsForSwipe = req.useW3cActionsForSwipe ?: base.useW3cActionsForSwipe,
            restartResponsesByPlan = req.restartResponsesByPlan ?: base.restartResponsesByPlan,
            maxSteps = req.maxSteps ?: base.maxSteps,
            maxCompletionTokens = req.maxCompletionTokens ?: base.maxCompletionTokens,
            reasoningEffort = req.reasoningEffort ?: base.reasoningEffort,
            timeoutSeconds = req.timeoutSeconds ?: base.timeoutSeconds,
            stepDelaySeconds = req.stepDelaySeconds ?: base.stepDelaySeconds,
            insecureSkipTlsVerify = req.insecureSkipTlsVerify ?: base.insecureSkipTlsVerify,
            feishuWebhookUrl = req.feishuWebhookUrl ?: base.feishuWebhookUrl,
            feishuAppId = req.feishuAppId?.trim()?.takeIf { it.isNotEmpty() } ?: base.feishuAppId,
            // Blank secret in request means "keep existing" (avoid wiping on accidental empty save).
            feishuAppSecret = req.feishuAppSecret?.trim()?.takeIf { it.isNotEmpty() }
                ?: base.feishuAppSecret,
        )
    }

    data class UiSnapshot(
        val running: Boolean = false,
        val lastMessage: String = "",
        val task: String = "",
        val baseUrl: String = "",
        val model: String = "",
        val apiKey: String = "",
        val apiMode: String = "responses",
        val maxSteps: Int = 120,
        val maxCompletionTokens: Int = 4096,
        val timeoutSeconds: Double = 90.0,
        val stepDelaySeconds: Double = 0.5,
        val reasoningEffort: String = "",
        val halfResScreenshot: Boolean = true,
        val rememberApiKey: Boolean = true,
        val useCustomSystemPrompt: Boolean = false,
        val systemPrompt: String = "",
        val debugLogRawAssistant: Boolean = false,
        val insecureSkipTlsVerify: Boolean = false,
        val doubaoSeedEnableSessionCache: Boolean = true,
        val restartResponsesByPlan: Boolean = false,
        val feishuWebhookUrl: String = "",
        val feishuAppId: String = "",
        val feishuAppSecret: String = "",
        val notes: String = "",
        val currentStep: Int = 0,
        val tokenUsage: TokenUsage = TokenUsage(),
        val chatPreview: List<AgentChatItem> = emptyList(),
        val logPreview: List<String> = emptyList(),
        val serverPort: Int = AgentHttpServer.PORT,
        val accessibilityConnected: Boolean = false,
        val screenshotReady: Boolean = false,
        val lastRunResult: LastRunResult? = null,
    )

    data class LastRunResult(
        val success: Boolean,
        val message: String,
        val stopReason: String,
        val runId: String,
        val task: String,
        val dismissed: Boolean = false,
    )

    companion object {
        private const val PREFS_NAME = "ondevice_agent"
        private const val MAX_LOG_LINES = 500
        private const val KEY_TASK = "task"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_MODEL = "model"
        private const val KEY_API_MODE = "api_mode"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_AGENT_TOKEN = "agent_token"
        private const val KEY_USE_CUSTOM_PROMPT = "use_custom_system_prompt"
        private const val KEY_SYSTEM_PROMPT = "system_prompt"
        private const val KEY_REMEMBER_API_KEY = "remember_api_key"
        private const val KEY_DEBUG_RAW = "debug_log_raw_assistant"
        private const val KEY_REASONING = "reasoning_effort"
        private const val KEY_DOUBAO_CACHE = "doubao_seed_enable_session_cache"
        private const val KEY_HALF_RES = "half_res_screenshot"
        private const val KEY_W3C_SWIPE = "use_w3c_actions_for_swipe"
        private const val KEY_RESTART_BY_PLAN = "restart_responses_by_plan"
        private const val KEY_MAX_STEPS = "max_steps"
        private const val KEY_MAX_TOKENS = "max_completion_tokens"
        private const val KEY_TIMEOUT = "timeout_seconds"
        private const val KEY_STEP_DELAY = "step_delay_seconds"
        private const val KEY_INSECURE_TLS = "insecure_skip_tls_verify"
        private const val KEY_FEISHU_WEBHOOK = "feishu_webhook_url"
        private const val KEY_FEISHU_APP_ID = "feishu_app_id"
        private const val KEY_FEISHU_APP_SECRET = "feishu_app_secret"
    }

    private fun PersistedAgentConfig.toSnapshot(includeDefault: Boolean): AgentConfigSnapshot {
        return AgentConfigSnapshot(
            task = task,
            baseUrl = baseUrl,
            model = model,
            apiMode = apiMode,
            apiKeySet = apiKey.isNotBlank(),
            agentTokenSet = agentToken.isNotBlank(),
            rememberApiKey = rememberApiKey,
            useCustomSystemPrompt = useCustomSystemPrompt,
            systemPrompt = systemPrompt,
            defaultSystemPrompt = if (includeDefault) AgentPrompts.defaultSystemPrompt() else null,
            debugLogRawAssistant = debugLogRawAssistant,
            reasoningEffort = reasoningEffort,
            doubaoSeedEnableSessionCache = doubaoSeedEnableSessionCache,
            halfResScreenshot = halfResScreenshot,
            useW3cActionsForSwipe = useW3cActionsForSwipe,
            restartResponsesByPlan = restartResponsesByPlan,
            maxSteps = maxSteps,
            maxCompletionTokens = maxCompletionTokens,
            timeoutSeconds = timeoutSeconds,
            stepDelaySeconds = stepDelaySeconds,
            insecureSkipTlsVerify = insecureSkipTlsVerify,
            feishuWebhookUrl = feishuWebhookUrl,
            feishuAppId = feishuAppId,
            feishuAppSecretSet = feishuAppSecret.isNotBlank(),
        )
    }
}
