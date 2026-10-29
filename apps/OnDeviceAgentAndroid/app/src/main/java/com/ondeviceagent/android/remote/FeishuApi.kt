package com.ondeviceagent.android.remote

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 网络错误只报告 HTTP／业务码，不回显凭证、连接 URL 或原始消息。 */
class FeishuApi(
    val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build(),
    private val base: String = "https://open.feishu.cn",
) {
    private data class CachedToken(val key: String, val value: String, val expiresAt: Long)
    @Volatile private var cachedToken: CachedToken? = null
    suspend fun post(path: String, body: JSONObject, bearer: String = ""): JSONObject {
        val request = Request.Builder().url(base + path).post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        if (bearer.isNotBlank()) request.header("Authorization", "Bearer $bearer")
        return execute(request.build())
    }
    private suspend fun execute(request: Request): JSONObject {
        val call = client.newCall(request)
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(IOException("飞书网络请求失败")) }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val value = response.use {
                            check(it.isSuccessful) { "飞书 HTTP ${it.code}" }
                            val body = it.body ?: error("飞书响应为空")
                            val bytes = body.byteStream().use { input ->
                                val out = java.io.ByteArrayOutputStream()
                                val buffer = ByteArray(8192)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    require(out.size() + count <= 2_097_152) { "飞书响应过大" }
                                    out.write(buffer, 0, count)
                                }
                                out.toByteArray()
                            }
                            require(bytes.size <= 2_097_152) { "飞书响应过大" }
                            JSONObject(bytes.toString(Charsets.UTF_8))
                        }
                        if (cont.isActive) cont.resume(value)
                    } catch (e: Exception) { if (cont.isActive) cont.resumeWithException(e) }
                }
            })
        }
    }
    suspend fun accessToken(appId: String, secret: String): String {
        val key = "$appId:$secret"
        cachedToken?.let { if (it.key == key && System.currentTimeMillis() < it.expiresAt) return it.value }
        val obj = post("/open-apis/auth/v3/tenant_access_token/internal", JSONObject().put("app_id", appId).put("app_secret", secret))
        check(obj.optInt("code", -1) == 0) { "飞书凭证错误：${obj.optInt("code", -1)}" }
        val token = obj.getString("tenant_access_token")
        cachedToken = CachedToken(key, token, System.currentTimeMillis() + (obj.optLong("expire", 7200) - 120).coerceAtLeast(1) * 1000)
        return token
    }
    suspend fun botId(appId: String, secret: String): String {
        val access = accessToken(appId, secret)
        val obj = execute(Request.Builder().url(base + "/open-apis/bot/v3/info").header("Authorization", "Bearer $access").build())
        if (obj.optInt("code", -1) != 0) { cachedToken = null; error("获取机器人信息失败：${obj.optInt("code", -1)}") }
        return obj.optJSONObject("bot")?.optString("open_id").orEmpty().also { check(it.isNotBlank()) { "机器人未启用" } }
    }
    suspend fun send(appId: String, secret: String, reply: RemoteReply) {
        val access = accessToken(appId, secret)
        val obj = try { post("/open-apis/im/v1/messages?receive_id_type=chat_id", JSONObject().put("receive_id", reply.chatId)
            .put("msg_type", "text").put("content", JSONObject().put("text", reply.text).toString()).put("uuid", reply.id), access) } catch (e: Exception) { cachedToken = null; throw e }
        if (obj.optInt("code", -1) != 0) { cachedToken = null; error("飞书消息发送失败：${obj.optInt("code", -1)}") }
    }
}
