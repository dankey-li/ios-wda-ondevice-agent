package com.ondeviceagent.android

import com.ondeviceagent.android.llm.ModelClient
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ModelClientTest {
    @Test fun `Chat模式保留结束原因供截断分析`() = runBlocking {
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse().setBody("""{"choices":[{"finish_reason":"length","message":{"content":"{截断"}}],"usage":{"prompt_tokens":5,"completion_tokens":2}}"""))
            server.start()
            val result = complete(server, "chat_completions")
            assertEquals("length", result.finishReason)
            assertEquals("{截断", result.text)
            assertEquals(7, result.usage.totalTokens)
            val request = server.takeRequest()
            assertEquals("/v1/chat/completions", request.path)
            assertTrue(request.body.readUtf8().contains("上次点击目标及失败原因"))
        } finally { server.shutdown() }
    }

    @Test fun `Responses模式保留未完成状态`() = runBlocking {
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse().setBody("""{"status":"incomplete","output":[{"content":[{"type":"output_text","text":"{截断"}]}]}"""))
            server.start()
            val result = complete(server, "responses")
            assertEquals("incomplete", result.finishReason)
            assertEquals("{截断", result.text)
            assertEquals("/v1/responses", server.takeRequest().path)
        } finally { server.shutdown() }
    }

    private suspend fun complete(server: MockWebServer, mode: String) = ModelClient().complete(
        baseUrl = server.url("/v1").toString(), apiKey = "test-only", model = "local-mock",
        apiMode = mode, systemPrompt = "仅输出 JSON", userText = "上次点击目标及失败原因",
        screenshotJpegBase64 = null, timeoutSeconds = 10.0, maxCompletionTokens = 256,
        insecureSkipTls = false, reasoningEffort = "",
    )
}
