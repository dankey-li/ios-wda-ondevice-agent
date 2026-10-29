package com.ondeviceagent.android.remote

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/** 长连接只确认已持久接收的事件，业务任务在独立协程处理；断开后由上层重新引导连接。 */
class FeishuSocket(private val api: FeishuApi,
    private val allowedSocket: (String) -> Boolean = { url ->
        val parsed = url.replaceFirst("wss://", "https://").replaceFirst("ws://", "http://").toHttpUrl()
        url.startsWith("wss://") && (parsed.host == "feishu.cn" || parsed.host.endsWith(".feishu.cn"))
    },
) {
    suspend fun run(appId: String, secret: String, onState: (String) -> Unit, onEvent: (String) -> Unit) = coroutineScope {
        val bootstrap = api.post("/callback/ws/endpoint", JSONObject().put("AppID", appId).put("AppSecret", secret))
        check(bootstrap.optInt("code", -1) == 0) { "飞书长连接引导失败：${bootstrap.optInt("code", -1)}" }
        val data = bootstrap.getJSONObject("data")
        val url = data.getString("URL")
        val parsed = url.replaceFirst("wss://", "https://").replaceFirst("ws://", "http://").toHttpUrl()
        require(allowedSocket(url)) { "拒绝非飞书安全长连接地址" }
        val serviceId = parsed.queryParameter("service_id")?.toLongOrNull() ?: error("长连接缺少服务标识")
        val interval = AtomicLong(data.optJSONObject("ClientConfig")?.optLong("PingInterval", 120)?.coerceIn(10, 300) ?: 120)
        val lastPong = AtomicLong(System.nanoTime())
        val closed = CompletableDeferred<Unit>()
        val fragments = FeishuFragments()
        var heartbeat: Job? = null
        val socket = api.client.newWebSocket(Request.Builder().url(url).header("User-Agent", "OnDeviceAgent-Android").build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                onState("长连接已建立；等待消息（请确认飞书已订阅接收消息事件）")
                heartbeat = launch {
                    while (isActive) {
                        if ((System.nanoTime() - lastPong.get()) / 1_000_000_000 > interval.get() * 3) {
                            webSocket.cancel(); closed.complete(Unit); break
                        }
                        if (!webSocket.send(FeishuWire.ping(serviceId).toByteString())) { closed.complete(Unit); break }
                        delay(interval.get() * 1000)
                    }
                }
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                var frame: FeishuWire.Frame? = null
                try {
                    frame = FeishuWire.frame(bytes.toByteArray())
                    val headers = frame.headers()
                    if (frame.number(4) == 0L) {
                        if (headers["type"] == "pong") {
                            lastPong.set(System.nanoTime())
                            if (frame.bytes(8).isNotEmpty()) interval.set(JSONObject(frame.bytes(8).toString(Charsets.UTF_8)).optLong("PingInterval", interval.get()).coerceIn(10, 300))
                        }
                        return
                    }
                    if (frame.number(4) != 1L) return
                    val payload = fragments.collect(frame, System.currentTimeMillis()) ?: return
                    if (headers["type"] == "event") {
                        val encoding = frame.bytes(6).toString(Charsets.UTF_8)
                        val eventBytes = if (encoding.equals("gzip", true)) {
                            java.util.zip.GZIPInputStream(payload.inputStream()).use { input ->
                                val out = java.io.ByteArrayOutputStream()
                                val buffer = ByteArray(8192)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    require(out.size() + count <= FeishuWire.MAX_BYTES)
                                    out.write(buffer, 0, count)
                                }
                                out.toByteArray()
                            }
                        } else payload
                        onEvent(eventBytes.toString(Charsets.UTF_8))
                    }
                    webSocket.send(frame.ack(200).toByteString())
                } catch (_: Exception) {
                    frame?.let { webSocket.send(it.ack(500).toByteString()) }
                    onState("事件接收失败，等待飞书重投；未执行未持久化指令")
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { closed.complete(Unit) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed.complete(Unit) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
        })
        try { closed.await() } finally { heartbeat?.cancel(); socket.cancel() }
    }
}
