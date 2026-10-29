package com.ondeviceagent.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ondeviceagent.android.BuildConfig
import com.ondeviceagent.android.llm.AgentPrompts
import com.ondeviceagent.android.model.AgentConfigRequest
import com.ondeviceagent.android.runtime.AgentRuntime
import kotlinx.coroutines.launch

@Composable
fun ConfigScreen(
    runtime: AgentRuntime,
    onRequestScreenshotPermission: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
) {
    val state by runtime.uiState.collectAsState()
    val scope = rememberCoroutineScope()

    var baseUrl by remember(state.baseUrl) { mutableStateOf(state.baseUrl) }
    var model by remember(state.model) { mutableStateOf(state.model) }
    var apiKey by remember(state.apiKey) { mutableStateOf(state.apiKey) }
    var apiMode by remember(state.apiMode) { mutableStateOf(state.apiMode.ifBlank { "responses" }) }
    var task by remember(state.task) { mutableStateOf(state.task) }
    var maxSteps by remember(state.maxSteps) { mutableStateOf(state.maxSteps.toString()) }
    var maxTokens by remember(state.maxCompletionTokens) { mutableStateOf(state.maxCompletionTokens.toString()) }
    var timeout by remember(state.timeoutSeconds) { mutableStateOf(state.timeoutSeconds.toString()) }
    var stepDelay by remember(state.stepDelaySeconds) { mutableStateOf(state.stepDelaySeconds.toString()) }
    var reasoning by remember(state.reasoningEffort) { mutableStateOf(state.reasoningEffort) }
    var halfRes by remember(state.halfResScreenshot) { mutableStateOf(state.halfResScreenshot) }
    var rememberKey by remember(state.rememberApiKey) { mutableStateOf(state.rememberApiKey) }
    var useCustomPrompt by remember(state.useCustomSystemPrompt) { mutableStateOf(state.useCustomSystemPrompt) }
    var systemPrompt by remember(state.systemPrompt) { mutableStateOf(state.systemPrompt) }
    var debugRaw by remember(state.debugLogRawAssistant) { mutableStateOf(state.debugLogRawAssistant) }
    var insecureTls by remember(state.insecureSkipTlsVerify) { mutableStateOf(state.insecureSkipTlsVerify) }
    var feishuWebhook by remember(state.feishuWebhookUrl) { mutableStateOf(state.feishuWebhookUrl) }
    var feishuAppId by remember(state.feishuAppId) { mutableStateOf(state.feishuAppId) }
    // Never echo saved secret back into the field; blank means keep existing.
    var feishuAppSecret by remember { mutableStateOf("") }
    var statusText by remember { mutableStateOf(state.lastMessage) }

    fun currentRequest(includeTask: Boolean = true) = AgentConfigRequest(
        task = if (includeTask) task else null,
        baseUrl = baseUrl,
        model = model,
        apiKey = apiKey,
        apiMode = apiMode,
        maxSteps = maxSteps.toIntOrNull(),
        maxCompletionTokens = maxTokens.toIntOrNull(),
        timeoutSeconds = timeout.toDoubleOrNull(),
        stepDelaySeconds = stepDelay.toDoubleOrNull(),
        reasoningEffort = reasoning,
        halfResScreenshot = halfRes,
        rememberApiKey = rememberKey,
        useCustomSystemPrompt = useCustomPrompt,
        systemPrompt = systemPrompt,
        debugLogRawAssistant = debugRaw,
        insecureSkipTlsVerify = insecureTls,
        feishuWebhookUrl = feishuWebhook.trim(),
        feishuAppId = feishuAppId.trim(),
        // Empty secret field = keep previously saved secret.
        feishuAppSecret = feishuAppSecret.trim().ifEmpty { null },
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("配置", style = MaterialTheme.typography.headlineSmall)
        Text(
            "版本 ${BuildConfig.VERSION_NAME}（构建 ${BuildConfig.VERSION_CODE}）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "状态：${if (state.running) "运行中 step=${state.currentStep}" else "空闲"} · " +
                "无障碍=${if (state.accessibilityConnected) "开" else "关"} · " +
                "截图=${if (state.screenshotReady) "就绪" else "未就绪"}",
        )
        Text(statusText.ifBlank { state.lastMessage })

        Text("模型", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(baseUrl, { baseUrl = it }, Modifier.fillMaxWidth(), label = { Text("Base URL") }, singleLine = true)
        OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), label = { Text("Model") }, singleLine = true)
        OutlinedTextField(apiKey, { apiKey = it }, Modifier.fillMaxWidth(), label = { Text("API Key") }, singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = apiMode == "responses", onClick = { apiMode = "responses" }, label = { Text("responses") })
            FilterChip(selected = apiMode == "chat_completions", onClick = { apiMode = "chat_completions" }, label = { Text("chat_completions") })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("记住 API Key", Modifier.weight(1f))
            Switch(checked = rememberKey, onCheckedChange = { rememberKey = it })
        }

        Text("运行参数", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(task, { task = it }, Modifier.fillMaxWidth().height(110.dp), label = { Text("当前 Task（也可从任务中心启动）") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(maxSteps, { maxSteps = it }, Modifier.weight(1f), label = { Text("max_steps") }, singleLine = true)
            OutlinedTextField(maxTokens, { maxTokens = it }, Modifier.weight(1f), label = { Text("max_tokens") }, singleLine = true)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(timeout, { timeout = it }, Modifier.weight(1f), label = { Text("timeout_s") }, singleLine = true)
            OutlinedTextField(stepDelay, { stepDelay = it }, Modifier.weight(1f), label = { Text("step_delay_s") }, singleLine = true)
        }
        OutlinedTextField(reasoning, { reasoning = it }, Modifier.fillMaxWidth(), label = { Text("reasoning_effort") }, singleLine = true)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("半分辨率截图", Modifier.weight(1f))
            Switch(checked = halfRes, onCheckedChange = { halfRes = it })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("跳过 TLS 校验", Modifier.weight(1f))
            Switch(checked = insecureTls, onCheckedChange = { insecureTls = it })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("调试原始模型输出", Modifier.weight(1f))
            Switch(checked = debugRaw, onCheckedChange = { debugRaw = it })
        }

        Text("通知设置", style = MaterialTheme.typography.titleMedium)
        Text(
            "Webhook 负责把完成消息发到群。若要附带最后一屏截图，还需填写飞书开放平台应用的 App ID / Secret（开启机器人能力，并开通 im:resource 上传图片权限）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = feishuWebhook,
            onValueChange = { feishuWebhook = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("飞书 Webhook URL") },
            singleLine = true,
            placeholder = { Text("https://open.feishu.cn/open-apis/bot/v2/hook/...") },
        )
        OutlinedTextField(
            value = feishuAppId,
            onValueChange = { feishuAppId = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("飞书 App ID（发截图用）") },
            singleLine = true,
            placeholder = { Text("cli_xxx") },
        )
        OutlinedTextField(
            value = feishuAppSecret,
            onValueChange = { feishuAppSecret = it },
            modifier = Modifier.fillMaxWidth(),
            label = {
                Text(
                    if (state.feishuAppSecret.isNotBlank() && feishuAppSecret.isBlank()) {
                        "飞书 App Secret（已保存，留空不改；重填可覆盖）"
                    } else {
                        "飞书 App Secret（凭证与基础信息 → App Secret）"
                    },
                )
            },
            singleLine = true,
            placeholder = {
                Text(if (state.feishuAppSecret.isNotBlank()) "••••••••（已保存）" else "粘贴 App Secret")
            },
        )
        Text(
            "注意：不要填 Encrypt Key / Verification Token；必须是「凭证与基础信息」页的 App Secret。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            enabled = feishuAppId.trim().isNotEmpty(),
            onClick = {
                scope.launch {
                    runtime.applyConfig(currentRequest())
                    val result = runtime.verifyFeishuCredentials()
                    statusText = if (result.ok) "凭证验证：${result.detail}" else "凭证验证失败：${result.detail}"
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("验证飞书凭证（不发消息）") }
        OutlinedButton(
            enabled = feishuWebhook.trim().isNotEmpty(),
            onClick = {
                scope.launch {
                    runtime.applyConfig(currentRequest())
                    val result = runtime.sendTestFeishuNotify()
                    statusText = if (result.ok) "飞书测试：${result.detail}" else "飞书测试失败：${result.detail}"
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("发送测试通知（有历史截图则尝试附带）") }

        Text("系统提示词", style = MaterialTheme.typography.titleMedium)
        Text(
            "默认使用内置初始 prompt（与 iOS Runner 同契约：think/plan/action JSON）。" +
                "只有明确要改行为策略时，再打开自定义。",
            style = MaterialTheme.typography.bodySmall,
        )
        var showDefaultPrompt by remember { mutableStateOf(false) }
        val defaultPrompt = remember { AgentPrompts.defaultSystemPrompt() }
        OutlinedButton(
            onClick = { showDefaultPrompt = !showDefaultPrompt },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (showDefaultPrompt) "收起初始 Prompt" else "查看初始 Prompt（内置默认）")
        }
        if (showDefaultPrompt) {
            OutlinedTextField(
                value = defaultPrompt,
                onValueChange = {},
                readOnly = true,
                modifier = Modifier.fillMaxWidth().height(220.dp),
                label = { Text("default_system_prompt") },
                textStyle = MaterialTheme.typography.bodySmall,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("使用自定义系统提示词", Modifier.weight(1f))
            Switch(checked = useCustomPrompt, onCheckedChange = { useCustomPrompt = it })
        }
        if (useCustomPrompt) {
            OutlinedTextField(
                systemPrompt,
                { systemPrompt = it },
                Modifier.fillMaxWidth().height(180.dp),
                label = { Text("system_prompt") },
                textStyle = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(
                onClick = { systemPrompt = defaultPrompt },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("用初始 Prompt 填充编辑器") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                scope.launch {
                    runtime.applyConfig(currentRequest())
                    statusText = "已保存"
                }
            }) { Text("保存") }
            Button(
                enabled = !state.running,
                onClick = {
                    scope.launch {
                        val payload = runtime.start(currentRequest())
                        statusText = if (payload.ok) payload.status?.lastMessage ?: "started" else payload.error
                    }
                },
            ) { Text("Start") }
            OutlinedButton(
                enabled = state.running,
                onClick = {
                    scope.launch {
                        statusText = runtime.stop().lastMessage
                    }
                },
            ) { Text("Stop") }
        }

        Text(
            "截图默认走无障碍（开启无障碍即可，无需每次弹窗）。下方仅为旧机/备用方案。",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = onRequestScreenshotPermission, modifier = Modifier.fillMaxWidth()) {
            Text("备用：授权 MediaProjection 截图")
        }
        OutlinedButton(onClick = onOpenAccessibilitySettings, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.accessibilityConnected) "无障碍已开（点此管理）" else "打开无障碍设置")
        }

        Text("Tokens：req=${state.tokenUsage.requests} in=${state.tokenUsage.inputTokens} out=${state.tokenUsage.outputTokens}")
        Text("最近日志", style = MaterialTheme.typography.titleMedium)
        state.logPreview.asReversed().take(15).forEach {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
    }
}
