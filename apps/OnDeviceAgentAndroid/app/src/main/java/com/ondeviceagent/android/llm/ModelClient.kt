package com.ondeviceagent.android.llm

import com.ondeviceagent.android.model.TokenUsage
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

data class ModelCallResult(
    val text: String,
    val usage: TokenUsageDelta = TokenUsageDelta(),
    val raw: String = "",
    val finishReason: String = "",
)

data class TokenUsageDelta(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cachedTokens: Int = 0,
    val totalTokens: Int = 0,
)

class ModelHttpException(val code: Int, message: String) : IOException(message) {
    val retryable: Boolean get() = code == 408 || code == 429 || code >= 500
}

class ModelClient {
    private val sharedClient = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun complete(
        baseUrl: String,
        apiKey: String,
        model: String,
        apiMode: String,
        systemPrompt: String,
        userText: String,
        screenshotJpegBase64: String?,
        timeoutSeconds: Double,
        maxCompletionTokens: Int,
        insecureSkipTls: Boolean,
        reasoningEffort: String,
        history: List<ModelMessage> = emptyList(),
    ): ModelCallResult = withContext(Dispatchers.IO) {
        val client = buildClient(timeoutSeconds, insecureSkipTls)
        val root = baseUrl.trimEnd('/')
        val mode = apiMode.ifBlank { "responses" }
        if (mode == "chat_completions") {
            callChatCompletions(
                client, root, apiKey, model, systemPrompt, userText,
                screenshotJpegBase64, maxCompletionTokens, reasoningEffort, history,
            )
        } else {
            callResponses(
                client, root, apiKey, model, systemPrompt, userText,
                screenshotJpegBase64, maxCompletionTokens, reasoningEffort, history,
            )
        }
    }

    private suspend fun callChatCompletions(
        client: OkHttpClient,
        root: String,
        apiKey: String,
        model: String,
        systemPrompt: String,
        userText: String,
        imageB64: String?,
        maxTokens: Int,
        reasoningEffort: String,
        history: List<ModelMessage>,
    ): ModelCallResult {
        val userContent = buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put("text", userText)
            })
            if (!imageB64.isNullOrBlank()) {
                add(buildJsonObject {
                    put("type", "image_url")
                    put(
                        "image_url",
                        buildJsonObject {
                            put("url", "data:image/jpeg;base64,$imageB64")
                        },
                    )
                })
            }
        }
        val body = buildJsonObject {
            put("model", model)
            put(
                "messages",
                buildJsonArray {
                    add(buildJsonObject {
                        put("role", "system")
                        put("content", systemPrompt)
                    })
                    history.takeLast(8).forEach { item ->
                        add(buildJsonObject { put("role", item.role); put("content", item.content) })
                    }
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", userContent)
                    })
                },
            )
            put("max_tokens", maxTokens.coerceAtLeast(256))
            if (reasoningEffort.isNotBlank()) put("reasoning_effort", reasoningEffort)
        }
        val url = resolveEndpoint(root, "chat/completions")
        val raw = postJson(client, url, apiKey, body.toString())
        val obj = json.parseToJsonElement(raw).jsonObject
        val text = obj["choices"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("message")?.jsonObject?.get("content")
            ?.let { content ->
                when (content) {
                    is JsonPrimitive -> content.contentOrNull.orEmpty()
                    is JsonArray -> content.joinToString("") {
                        it.jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    }
                    else -> content.toString()
                }
            }.orEmpty()
        return ModelCallResult(
            text = text, usage = parseUsage(obj["usage"] as? JsonObject), raw = raw,
            finishReason = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("finish_reason")?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
    }

    private suspend fun callResponses(
        client: OkHttpClient,
        root: String,
        apiKey: String,
        model: String,
        systemPrompt: String,
        userText: String,
        imageB64: String?,
        maxTokens: Int,
        reasoningEffort: String,
        history: List<ModelMessage>,
    ): ModelCallResult {
        val content = buildJsonArray {
            add(buildJsonObject {
                put("type", "input_text")
                put("text", userText)
            })
            if (!imageB64.isNullOrBlank()) {
                add(buildJsonObject {
                    put("type", "input_image")
                    put("image_url", "data:image/jpeg;base64,$imageB64")
                })
            }
        }
        val body = buildJsonObject {
            put("model", model)
            put(
                "input",
                buildJsonArray {
                    add(buildJsonObject {
                        put("role", "system")
                        put(
                            "content",
                            buildJsonArray {
                                add(buildJsonObject {
                                    put("type", "input_text")
                                    put("text", systemPrompt)
                                })
                            },
                        )
                    })
                    history.takeLast(8).forEach { item ->
                        add(buildJsonObject { put("role", item.role); put("content", item.content) })
                    }
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", content)
                    })
                },
            )
            put("max_output_tokens", maxTokens.coerceAtLeast(256))
            if (reasoningEffort.isNotBlank()) {
                put("reasoning", buildJsonObject { put("effort", reasoningEffort) })
            }
        }
        val url = resolveEndpoint(root, "responses")
        val raw = postJson(client, url, apiKey, body.toString())
        val obj = json.parseToJsonElement(raw).jsonObject
        val text = extractResponsesText(obj)
        return ModelCallResult(
            text = text, usage = parseUsage(obj["usage"] as? JsonObject), raw = raw,
            finishReason = obj["status"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
    }

    /**
     * Match iOS OnDeviceAgentModelClient URL rules:
     * - already ends with /chat/completions or /responses → use as-is
     * - ends with /v1 or /api/v3 → append endpoint
     * - otherwise → append /v1/<endpoint>
     */
    private fun resolveEndpoint(baseUrl: String, endpoint: String): String {
        val root = baseUrl.trim().trimEnd('/')
        if (root.endsWith("/$endpoint")) return root
        if (root.endsWith("/v1") || root.endsWith("/api/v3")) {
            return "$root/$endpoint"
        }
        return "$root/v1/$endpoint"
    }

    private fun extractResponsesText(obj: JsonObject): String {
        obj["output_text"]?.jsonPrimitive?.contentOrNull?.let { if (it.isNotBlank()) return it }
        val output = obj["output"]?.jsonArray ?: return ""
        val parts = mutableListOf<String>()
        for (item in output) {
            val content = item.jsonObject["content"]?.jsonArray ?: continue
            for (c in content) {
                val co = c.jsonObject
                val t = co["text"]?.jsonPrimitive?.contentOrNull
                if (!t.isNullOrBlank()) parts += t
            }
        }
        return parts.joinToString("\n")
    }

    private fun parseUsage(usage: JsonObject?): TokenUsageDelta {
        if (usage == null) return TokenUsageDelta()
        fun intOf(vararg keys: String): Int {
            for (k in keys) {
                val v = usage[k]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                if (v != null) return v
            }
            return 0
        }
        val input = intOf("input_tokens", "prompt_tokens")
        val output = intOf("output_tokens", "completion_tokens")
        val cached = intOf("cached_tokens")
        val total = intOf("total_tokens").takeIf { it > 0 } ?: (input + output)
        return TokenUsageDelta(input, output, cached, total)
    }

    private suspend fun postJson(client: OkHttpClient, url: String, apiKey: String, body: String): String =
        suspendCancellableCoroutine { continuation ->
            val request = Request.Builder().url(url).addHeader("Content-Type", "application/json")
                .apply { if (apiKey.isNotBlank()) addHeader("Authorization", "Bearer $apiKey") }
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, error: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    response.use {
                        try {
                            val text = it.body?.string().orEmpty()
                            if (!it.isSuccessful) throw ModelHttpException(it.code, "HTTP ${it.code}: ${text.take(800)}")
                            if (continuation.isActive) continuation.resume(text)
                        } catch (error: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(error)
                        }
                    }
                }
            })
        }

    private fun buildClient(timeoutSeconds: Double, insecure: Boolean): OkHttpClient {
        val timeout = timeoutSeconds.coerceIn(10.0, 300.0).toLong()
        val builder = sharedClient.newBuilder()
            .callTimeout(timeout, TimeUnit.SECONDS)
            .connectTimeout(timeout, TimeUnit.SECONDS)
            .readTimeout(timeout, TimeUnit.SECONDS)
            .writeTimeout(timeout, TimeUnit.SECONDS)
        if (insecure) {
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val ssl = SSLContext.getInstance("TLS")
            ssl.init(null, arrayOf<TrustManager>(trustAll), java.security.SecureRandom())
            builder.sslSocketFactory(ssl.socketFactory, trustAll)
            builder.hostnameVerifier { _, _ -> true }
        }
        return builder.build()
    }
}

fun TokenUsage.plus(delta: TokenUsageDelta): TokenUsage = copy(
    requests = requests + 1,
    inputTokens = inputTokens + delta.inputTokens,
    outputTokens = outputTokens + delta.outputTokens,
    cachedTokens = cachedTokens + delta.cachedTokens,
    totalTokens = totalTokens + delta.totalTokens,
)
