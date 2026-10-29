package com.ondeviceagent.android.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Mirrors the iOS /agent JSON contract for Manager/Python client reuse. */
@Serializable
data class AgentStatus(
    val running: Boolean = false,
    @SerialName("last_message") val lastMessage: String = "",
    val config: AgentConfigSnapshot = AgentConfigSnapshot(),
    val notes: String = "",
    @SerialName("token_usage") val tokenUsage: TokenUsage = TokenUsage(),
    @SerialName("log_lines") val logLines: Int = 0,
    val trace: TraceStatus = TraceStatus(),
    val platform: String = "android",
)

@Serializable
data class TokenUsage(
    val requests: Int = 0,
    @SerialName("input_tokens") val inputTokens: Int = 0,
    @SerialName("output_tokens") val outputTokens: Int = 0,
    @SerialName("cached_tokens") val cachedTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
)

@Serializable
data class TraceStatus(
    @SerialName("run_id") val runId: String = "",
    @SerialName("active") val active: Boolean = false,
)

@Serializable
data class AgentConfigSnapshot(
    val task: String = "",
    @SerialName("base_url") val baseUrl: String = "",
    val model: String = "",
    @SerialName("api_mode") val apiMode: String = "responses",
    @SerialName("api_key_set") val apiKeySet: Boolean = false,
    @SerialName("agent_token_set") val agentTokenSet: Boolean = false,
    @SerialName("remember_api_key") val rememberApiKey: Boolean = false,
    @SerialName("use_custom_system_prompt") val useCustomSystemPrompt: Boolean = false,
    @SerialName("system_prompt") val systemPrompt: String = "",
    @SerialName("default_system_prompt") val defaultSystemPrompt: String? = null,
    @SerialName("debug_log_raw_assistant") val debugLogRawAssistant: Boolean = false,
    @SerialName("reasoning_effort") val reasoningEffort: String = "",
    @SerialName("doubao_seed_enable_session_cache") val doubaoSeedEnableSessionCache: Boolean = true,
    @SerialName("half_res_screenshot") val halfResScreenshot: Boolean = true,
    @SerialName("use_w3c_actions_for_swipe") val useW3cActionsForSwipe: Boolean = false,
    @SerialName("restart_responses_by_plan") val restartResponsesByPlan: Boolean = false,
    @SerialName("max_steps") val maxSteps: Int = 120,
    @SerialName("max_completion_tokens") val maxCompletionTokens: Int = 4096,
    @SerialName("timeout_seconds") val timeoutSeconds: Double = 90.0,
    @SerialName("step_delay_seconds") val stepDelaySeconds: Double = 0.5,
    @SerialName("insecure_skip_tls_verify") val insecureSkipTlsVerify: Boolean = false,
    @SerialName("feishu_webhook_url") val feishuWebhookUrl: String = "",
    @SerialName("feishu_app_id") val feishuAppId: String = "",
    @SerialName("feishu_app_secret_set") val feishuAppSecretSet: Boolean = false,
)

@Serializable
data class AgentConfigRequest(
    val task: String? = null,
    @SerialName("base_url") val baseUrl: String? = null,
    val model: String? = null,
    @SerialName("api_mode") val apiMode: String? = null,
    @SerialName("api_key") val apiKey: String? = null,
    @SerialName("agent_token") val agentToken: String? = null,
    @SerialName("use_custom_system_prompt") val useCustomSystemPrompt: Boolean? = null,
    @SerialName("system_prompt") val systemPrompt: String? = null,
    @SerialName("remember_api_key") val rememberApiKey: Boolean? = null,
    @SerialName("debug_log_raw_assistant") val debugLogRawAssistant: Boolean? = null,
    @SerialName("doubao_seed_enable_session_cache") val doubaoSeedEnableSessionCache: Boolean? = null,
    @SerialName("half_res_screenshot") val halfResScreenshot: Boolean? = null,
    @SerialName("use_w3c_actions_for_swipe") val useW3cActionsForSwipe: Boolean? = null,
    @SerialName("restart_responses_by_plan") val restartResponsesByPlan: Boolean? = null,
    @SerialName("max_steps") val maxSteps: Int? = null,
    @SerialName("max_completion_tokens") val maxCompletionTokens: Int? = null,
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    @SerialName("timeout_seconds") val timeoutSeconds: Double? = null,
    @SerialName("step_delay_seconds") val stepDelaySeconds: Double? = null,
    @SerialName("insecure_skip_tls_verify") val insecureSkipTlsVerify: Boolean? = null,
    @SerialName("feishu_webhook_url") val feishuWebhookUrl: String? = null,
    @SerialName("feishu_app_id") val feishuAppId: String? = null,
    @SerialName("feishu_app_secret") val feishuAppSecret: String? = null,
)

@Serializable
data class AgentStartPayload(
    val ok: Boolean = true,
    val error: String = "",
    val status: AgentStatus? = null,
    @SerialName("error_kind") val errorKind: String = "",
)

@Serializable
data class AgentLogsPayload(
    val lines: List<String> = emptyList(),
)

@Serializable
data class AgentChatPayload(
    val items: List<AgentChatItem> = emptyList(),
)

@Serializable
data class AgentChatItem(
    val ts: String? = null,
    val step: Int? = null,
    val kind: String = "",
    val attempt: Int? = null,
    val text: String? = null,
    val content: String? = null,
    val reasoning: String? = null,
    val raw: String? = null,
)

@Serializable
data class AgentTraceListPayload(
    val items: List<AgentTraceSummary> = emptyList(),
)

@Serializable
data class AgentTraceSummary(
    @SerialName("run_id") val runId: String = "",
    val task: String = "",
    @SerialName("started_at") val startedAt: String = "",
    @SerialName("finished_at") val finishedAt: String = "",
    @SerialName("parent_run_id") val parentRunId: String = "",
    val attempt: Int = 0,
    val schema: String = "",
    val counts: AgentTraceCounts = AgentTraceCounts(),
    @SerialName("final_status") val finalStatus: AgentTraceFinalStatus = AgentTraceFinalStatus(),
)

@Serializable
data class AgentTraceCounts(
    val logs: Int = 0,
    val events: Int = 0,
    val turns: Int = 0,
    val images: Int = 0,
)

@Serializable
data class AgentTraceFinalStatus(
    val success: Boolean = false,
    val message: String = "",
    @SerialName("stop_reason") val stopReason: String = "",
)

data class PersistedAgentConfig(
    val task: String = "",
    val baseUrl: String = "",
    val model: String = "",
    val apiMode: String = "responses",
    val apiKey: String = "",
    val agentToken: String = "",
    val useCustomSystemPrompt: Boolean = false,
    val systemPrompt: String = "",
    val rememberApiKey: Boolean = true,
    val debugLogRawAssistant: Boolean = false,
    val reasoningEffort: String = "",
    val doubaoSeedEnableSessionCache: Boolean = true,
    val halfResScreenshot: Boolean = true,
    val useW3cActionsForSwipe: Boolean = false,
    val restartResponsesByPlan: Boolean = false,
    val maxSteps: Int = 120,
    val maxCompletionTokens: Int = 4096,
    val timeoutSeconds: Double = 90.0,
    val stepDelaySeconds: Double = 0.5,
    val insecureSkipTlsVerify: Boolean = false,
    /** Feishu custom bot webhook for task-success notifications. */
    val feishuWebhookUrl: String = "",
    /** Optional: Feishu open-platform app used to upload screenshots (im:resource). */
    val feishuAppId: String = "",
    val feishuAppSecret: String = "",
)
