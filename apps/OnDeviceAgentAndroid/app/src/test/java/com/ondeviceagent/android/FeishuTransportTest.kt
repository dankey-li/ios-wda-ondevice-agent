package com.ondeviceagent.android

import android.app.Application
import com.ondeviceagent.android.remote.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.mockwebserver.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FeishuTransportTest {
    @Test fun `回复使用应用令牌及稳定去重标识并复用令牌`() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"code":0,"tenant_access_token":"mock-token","expire":7200}"""))
            server.enqueue(MockResponse().setBody("""{"code":0}"""))
            server.enqueue(MockResponse().setBody("""{"code":0}"""))
            val api = FeishuApi(base = server.url("/").toString().removeSuffix("/"))
            val reply = RemoteReply("stable-id", "cli_test", "oc_test", "已启动", 0)
            api.send("cli_test", "mock-secret", reply); api.send("cli_test", "mock-secret", reply)
            val token = server.takeRequest(3, TimeUnit.SECONDS)!!
            assertTrue(token.path!!.endsWith("tenant_access_token/internal"))
            val first = server.takeRequest(3, TimeUnit.SECONDS)!!
            val second = server.takeRequest(3, TimeUnit.SECONDS)!!
            assertEquals("Bearer mock-token", first.getHeader("Authorization"))
            assertEquals("stable-id", JSONObject(first.body.readUtf8()).getString("uuid"))
            assertEquals("stable-id", JSONObject(second.body.readUtf8()).getString("uuid"))
            assertEquals(3, server.requestCount)
        } finally { server.shutdown() }
    }
    private fun eventFrame() = FeishuWire.encode(listOf(FeishuWire.number(1, 1), FeishuWire.number(2, 2),
        FeishuWire.number(3, 1), FeishuWire.number(4, 1), FeishuWire.header("type", "event"), FeishuWire.header("sum", "1"), FeishuWire.header("seq", "0"),
        FeishuWire.header("message_id", "frame1"), FeishuWire.blob(8, "{}".toByteArray())))
    @Test fun `长连接在指令落盘后才回成功确认`() = runBlocking {
        val server = MockWebServer(); server.start()
        val ack = CompletableDeferred<Int>()
        val journal = RemoteJournal(RuntimeEnvironment.getApplication())
        server.enqueue(MockResponse().setBody(JSONObject().put("code", 0).put("data", JSONObject().put("URL", server.url("/ws?service_id=1").toString().replace("http://", "ws://"))).toString()))
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send(eventFrame().toByteString()) }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val frame = FeishuWire.frame(bytes.toByteArray())
                if (frame.number(4) == 1L) {
                    assertEquals(1, journal.entries.size)
                    ack.complete(JSONObject(frame.bytes(8).toString(Charsets.UTF_8)).getInt("code"))
                }
            }
        }))
        val api = FeishuApi(base = server.url("/").toString().removeSuffix("/"))
        val job = launch(Dispatchers.Default) { FeishuSocket(api, allowedSocket = { it.startsWith("ws://localhost:") }).run("cli_test", "mock-secret", {}) {
            journal.accept("cli_test", RemoteCommands.Message("om_frame", "ou_me", "oc_test", "/状态", System.currentTimeMillis()), System.currentTimeMillis())
        } }
        try { assertEquals(200, withTimeout(5000) { ack.await() }) }
        finally { job.cancelAndJoin(); server.shutdown() }
    }
    @Test fun `持久接收失败返回失败确认而非提前成功`() = runBlocking {
        val server = MockWebServer(); server.start()
        val ack = CompletableDeferred<Int>()
        server.enqueue(MockResponse().setBody(JSONObject().put("code", 0).put("data", JSONObject().put("URL", server.url("/ws?service_id=1").toString().replace("http://", "ws://"))).toString()))
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send(eventFrame().toByteString()) }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val frame = FeishuWire.frame(bytes.toByteArray())
                if (frame.number(4) == 1L) ack.complete(JSONObject(frame.bytes(8).toString(Charsets.UTF_8)).getInt("code"))
            }
        }))
        val job = launch(Dispatchers.Default) { FeishuSocket(FeishuApi(base = server.url("/").toString().removeSuffix("/")), allowedSocket = { true })
            .run("cli_test", "mock-secret", {}) { throw IllegalStateException("模拟存储失败") } }
        try { assertEquals(500, withTimeout(5000) { ack.await() }) }
        finally { job.cancelAndJoin(); server.shutdown() }
    }
}
