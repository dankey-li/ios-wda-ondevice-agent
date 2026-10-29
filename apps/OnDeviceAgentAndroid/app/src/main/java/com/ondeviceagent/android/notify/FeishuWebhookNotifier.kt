package com.ondeviceagent.android.notify

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Feishu custom bot webhook (`/open-apis/bot/v2/hook/...`).
 *
 * Text works with webhook alone. Attaching screenshots needs a Feishu app
 * (App ID / Secret) with bot ability + `im:resource` to upload and obtain `image_key`.
 */
object FeishuWebhookNotifier {
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val jpegMedia = "image/jpeg".toMediaType()
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private data class CachedToken(val appId: String, val token: String, val expireAtMs: Long)

    private val tokenCache = AtomicReference<CachedToken?>(null)

    data class SendResult(val ok: Boolean, val detail: String)

    data class Credentials(
        val webhookUrl: String,
        val appId: String = "",
        val appSecret: String = "",
    ) {
        val canUploadImage: Boolean
            get() = appId.isNotBlank() && appSecret.isNotBlank()
    }

    /** Validates App ID / Secret by requesting tenant_access_token. */
    suspend fun verifyCredentials(appId: String, appSecret: String): SendResult = withContext(Dispatchers.IO) {
        val id = sanitizeCredential(appId)
        val secret = sanitizeCredential(appSecret)
        if (id.isEmpty() || secret.isEmpty()) {
            return@withContext SendResult(false, "请填写 App ID 与 App Secret")
        }
        tokenCache.set(null)
        val result = fetchTenantToken(id, secret)
        if (result.ok) {
            SendResult(true, "凭证有效，可上传截图")
        } else {
            SendResult(false, humanizeAuthError(result.detail))
        }
    }

    suspend fun sendTaskSuccess(
        credentials: Credentials,
        title: String,
        task: String,
        message: String,
        runId: String,
        lastImageJpeg: ByteArray? = null,
    ): SendResult = withContext(Dispatchers.IO) {
        val url = credentials.webhookUrl.trim()
        if (url.isEmpty()) return@withContext SendResult(false, "Webhook 未配置")
        if (!isWebhookUrl(url)) return@withContext SendResult(false, "Webhook 地址不像飞书机器人 hook")

        val summary = buildString {
            append("**标题：** ").append(escapeMd(title.ifBlank { "未命名" })).append('\n')
            if (task.isNotBlank()) {
                append("**任务：** ").append(escapeMd(task.take(180))).append('\n')
            }
            append("**结果：** ").append(escapeMd(message.ifBlank { "success" })).append('\n')
            if (runId.isNotBlank()) append("**Run：** ").append(escapeMd(runId)).append('\n')
            append("**时间：** ").append(nowText())
        }

        var imageKey: String? = null
        var imageNote = ""
        if (lastImageJpeg != null && lastImageJpeg.isNotEmpty()) {
            val appId = sanitizeCredential(credentials.appId)
            val appSecret = sanitizeCredential(credentials.appSecret)
            if (appId.isEmpty() || appSecret.isEmpty()) {
                imageNote = "（有截图但未配置 App ID/Secret，图片未发送）"
            } else {
                val upload = uploadImage(appId, appSecret, lastImageJpeg)
                if (upload.ok) {
                    imageKey = upload.detail
                } else {
                    imageNote = "（截图上传失败：${humanizeAuthError(upload.detail)}）"
                }
            }
        }

        val cardBody = buildInteractiveCard(
            title = "✅ 任务完成",
            markdown = summary + if (imageNote.isNotBlank()) "\n$imageNote" else "",
            imageKey = imageKey,
        )
        val cardResult = postJson(url, cardBody)
        if (cardResult.ok) {
            SendResult(
                true,
                if (imageKey != null) "已发送（含截图）" else "已发送" + textSuffix(imageNote),
            )
        } else {
            // Fallback to plain text if card rejected.
            val text = buildString {
                append("✅ OnDevice Agent 任务完成\n")
                append("标题：").append(title.ifBlank { "未命名" }).append('\n')
                if (task.isNotBlank()) append("任务：").append(task.take(200)).append('\n')
                append("结果：").append(message.ifBlank { "success" }).append('\n')
                if (runId.isNotBlank()) append("Run：").append(runId).append('\n')
                append("时间：").append(nowText())
                if (imageNote.isNotBlank()) append('\n').append(imageNote)
            }
            val textResult = postJson(
                url,
                JSONObject()
                    .put("msg_type", "text")
                    .put("content", JSONObject().put("text", text))
                    .toString(),
            )
            if (textResult.ok && imageKey != null) {
                postJson(
                    url,
                    JSONObject()
                        .put("msg_type", "image")
                        .put("content", JSONObject().put("image_key", imageKey))
                        .toString(),
                )
                SendResult(true, "已发送（文本+截图）")
            } else {
                textResult
            }
        }
    }

    suspend fun sendTest(credentials: Credentials, sampleImageJpeg: ByteArray? = null): SendResult {
        return sendTaskSuccess(
            credentials = credentials,
            title = "通知测试",
            task = "Webhook / 截图联调",
            message = "测试通知已连通",
            runId = "test",
            lastImageJpeg = sampleImageJpeg,
        )
    }

    suspend fun sendText(webhookUrl: String, text: String): SendResult = withContext(Dispatchers.IO) {
        val url = webhookUrl.trim()
        if (url.isEmpty()) return@withContext SendResult(false, "Webhook 未配置")
        if (!isWebhookUrl(url)) return@withContext SendResult(false, "Webhook 地址不像飞书机器人 hook")
        postJson(
            url,
            JSONObject()
                .put("msg_type", "text")
                .put("content", JSONObject().put("text", text))
                .toString(),
        )
    }

    private fun textSuffix(imageNote: String): String =
        if (imageNote.isBlank()) "" else " $imageNote"

    private fun isWebhookUrl(url: String): Boolean =
        url.startsWith("https://open.feishu.cn/open-apis/bot/") ||
            url.startsWith("https://open.larksuite.com/open-apis/bot/")

    private fun buildInteractiveCard(title: String, markdown: String, imageKey: String?): String {
        val elements = JSONArray().put(
            JSONObject()
                .put("tag", "div")
                .put(
                    "text",
                    JSONObject()
                        .put("tag", "lark_md")
                        .put("content", markdown),
                ),
        )
        if (!imageKey.isNullOrBlank()) {
            elements.put(
                JSONObject()
                    .put("tag", "img")
                    .put("img_key", imageKey)
                    .put(
                        "alt",
                        JSONObject().put("tag", "plain_text").put("content", "最后一屏截图"),
                    ),
            )
        }
        val card = JSONObject()
            .put("config", JSONObject().put("wide_screen_mode", true))
            .put(
                "header",
                JSONObject()
                    .put("template", "green")
                    .put(
                        "title",
                        JSONObject().put("tag", "plain_text").put("content", title),
                    ),
            )
            .put("elements", elements)
        return JSONObject()
            .put("msg_type", "interactive")
            .put("card", card)
            .toString()
    }

    private fun postJson(url: String, bodyJson: String): SendResult {
        return runCatching {
            val request = Request.Builder()
                .url(url)
                .post(bodyJson.toRequestBody(jsonMedia))
                .header("Content-Type", "application/json")
                .build()
            client.newCall(request).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@use SendResult(false, "HTTP ${resp.code}: ${raw.take(200)}")
                }
                val obj = runCatching { JSONObject(raw) }.getOrNull()
                val code = obj?.optInt("StatusCode", obj.optInt("code", 0)) ?: 0
                if (code != 0 && raw.isNotBlank() && !raw.contains("success", ignoreCase = true)) {
                    SendResult(false, raw.take(200).ifBlank { "飞书返回失败" })
                } else {
                    SendResult(true, "已发送")
                }
            }
        }.getOrElse { SendResult(false, it.message ?: it::class.java.simpleName) }
    }

    private fun uploadImage(appId: String, appSecret: String, jpegBytes: ByteArray): SendResult {
        val token = fetchTenantToken(appId, appSecret)
        if (!token.ok) return token
        val prepared = prepareJpegForUpload(jpegBytes)
        val tmp = File.createTempFile("feishu_shot_", ".jpg").apply {
            writeBytes(prepared)
            deleteOnExit()
        }
        return try {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("image_type", "message")
                .addFormDataPart("image", "last.jpg", tmp.asRequestBody(jpegMedia))
                .build()
            val request = Request.Builder()
                .url("https://open.feishu.cn/open-apis/im/v1/images")
                .header("Authorization", "Bearer ${token.detail}")
                .post(body)
                .build()
            client.newCall(request).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                val obj = runCatching { JSONObject(raw) }.getOrNull()
                val code = obj?.optInt("code", -1) ?: -1
                val key = obj?.optJSONObject("data")?.optString("image_key").orEmpty()
                if (code == 0 && key.isNotBlank()) {
                    SendResult(true, key)
                } else {
                    SendResult(false, raw.take(240).ifBlank { "HTTP ${resp.code}" })
                }
            }
        } catch (t: Throwable) {
            SendResult(false, t.message ?: t::class.java.simpleName)
        } finally {
            tmp.delete()
        }
    }

    private fun fetchTenantToken(appId: String, appSecret: String): SendResult {
        val cached = tokenCache.get()
        val now = System.currentTimeMillis()
        if (cached != null &&
            cached.appId == appId &&
            cached.expireAtMs > now + 60_000L
        ) {
            return SendResult(true, cached.token)
        }
        return runCatching {
            val body = JSONObject()
                .put("app_id", appId)
                .put("app_secret", appSecret)
                .toString()
            val request = Request.Builder()
                .url("https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal")
                .post(body.toRequestBody(jsonMedia))
                .header("Content-Type", "application/json; charset=utf-8")
                .build()
            client.newCall(request).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                val obj = JSONObject(raw)
                val code = obj.optInt("code", -1)
                val token = obj.optString("tenant_access_token")
                val expire = obj.optInt("expire", 7200)
                if (code == 0 && token.isNotBlank()) {
                    tokenCache.set(CachedToken(appId, token, now + expire * 1000L))
                    SendResult(true, token)
                } else {
                    SendResult(false, raw.take(240).ifBlank { "获取 tenant_access_token 失败" })
                }
            }
        }.getOrElse { SendResult(false, it.message ?: "token error") }
    }

    private fun sanitizeCredential(raw: String): String =
        raw.trim()
            .removePrefix("\uFEFF")
            .trim('"')
            .trim('\'')
            .replace("\n", "")
            .replace("\r", "")
            .replace(" ", "")

    private fun humanizeAuthError(raw: String): String {
        val lower = raw.lowercase()
        return when {
            lower.contains("10014") || lower.contains("app secret invalid") ->
                "App Secret 无效。请到飞书开放平台「凭证与基础信息」复制 App Secret（不是 Encrypt Key / Verification Token），重新粘贴后先点「验证飞书凭证」"
            lower.contains("10013") || lower.contains("app id") ->
                "App ID 无效，请确认是 cli_ 开头的应用 ID"
            lower.contains("99991672") || lower.contains("app disabled") ->
                "应用未启用或已停用，请在飞书开放平台启用应用"
            else -> raw
        }
    }

    /** Downscale / recompress so upload stays fast and under Feishu limits. */
    fun prepareJpegForUpload(raw: ByteArray, maxWidth: Int = 1080, quality: Int = 72): ByteArray {
        return runCatching {
            val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return raw
            val scaled = if (bmp.width > maxWidth) {
                val h = (bmp.height.toFloat() * maxWidth / bmp.width).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(bmp, maxWidth, h, true)
            } else {
                bmp
            }
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (scaled !== bmp) scaled.recycle()
            bmp.recycle()
            out.toByteArray()
        }.getOrDefault(raw)
    }

    private fun escapeMd(text: String): String =
        text.replace("*", "\\*").replace("\n", " ")

    private fun nowText(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())
}
