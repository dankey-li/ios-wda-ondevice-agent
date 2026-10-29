package com.ondeviceagent.android

import com.ondeviceagent.android.automation.*
import com.ondeviceagent.android.manager.*
import com.ondeviceagent.android.model.AgentConfigRequest
import com.ondeviceagent.android.runtime.*
import com.ondeviceagent.android.trace.TraceRecorder
import kotlinx.coroutines.launch
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlinx.serialization.json.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = OnDeviceAgentApp::class)
class RuntimeLifecycleTest {
    private lateinit var app: OnDeviceAgentApp
    private lateinit var server: MockWebServer
    private lateinit var runtime: AgentRuntime
    private val actions = java.util.Collections.synchronizedList(mutableListOf<String>())
    private var screen = "测试页面"
    private var postRunCalls = 0
    private var postRunHook: suspend (TaskTemplate) -> String = { "模拟返回任务页" }

    @Before fun setup() = runBlocking {
        app = RuntimeEnvironment.getApplication() as OnDeviceAgentApp
        server = MockWebServer(); server.start()
        runtime = AgentRuntime(app, TraceRecorder(app), object : ExecutionEnvironment {
            override suspend fun postRun(task: TaskTemplate): String {
                postRunCalls++
                val dir = java.io.File(app.filesDir, "traces")
                assertTrue(dir.listFiles().orEmpty().any { java.io.File(it, "manifest.json").readText().contains("测试结束") })
                assertNotEquals(TaskRunState.running, app.managerStore.templates.value.single().lastRun.state)
                return postRunHook(task)
            }
            override fun ready() = true
            override suspend fun screenshot(halfResolution: Boolean): String? = null
            override fun screenInfo(step: Int) = """{"package":"test.app","width_px":1080,"height_px":2400,"screenshot":false,"ui":[{"text":"$screen","clickable":true,"element":[500,500],"bounds":[0,0,1000,1000]}]}"""
            override suspend fun perform(request: AgentActionRequest): AgentActionResult {
                if (request.name == "tap" && screen == "提交") {
                    assertEquals("提交", app.managerStore.templates.value.single().submissionPending)
                }
                actions += request.name.orEmpty()
                return AgentActionResult(true, name = request.name.orEmpty())
            }
        })
        runtime.applyConfig(AgentConfigRequest(baseUrl = server.url("/v1").toString(), model = "mock", apiKey = "local-only", apiMode = "chat_completions", maxSteps = 20, stepDelaySeconds = 0.1))
        app.managerStore.upsert(TaskTemplate(id = "runtime-test", title = "测试", task = "业务任务", maxRetries = 0))
    }
    @After fun cleanup() { runBlocking { runtime.stop() }; server.shutdown() }

    private fun response(name: String, params: JsonObject = buildJsonObject {}) = MockResponse().setBody(buildJsonObject {
        put("choices", buildJsonArray { add(buildJsonObject { put("message", buildJsonObject {
            put("content", buildJsonObject { put("action", buildJsonObject { put("name", name); put("params", params) }) }.toString())
        }) }) })
    }.toString())

    private suspend fun start(): Unit {
        val owner = app.managerStore.templates.value.single()
        assertTrue(runtime.start(AgentConfigRequest(task = owner.task), false, owner.id, claim = RunClaim(owner.executionRevision)).ok)
    }
    private suspend fun completed() = withTimeout(10000) { runtime.uiState.first { !it.running && it.lastRunResult != null }.lastRunResult!! }

    @Test fun `任务中心手动运行结束后保留结果但不弹窗`() = runBlocking {
        val owner = app.managerStore.templates.value.single()
        app.managerStore.upsert(owner.copy(completionText = "测试页面"))
        server.enqueue(response("Finish", buildJsonObject {
            put("status", "success"); put("message", "测试结束"); put("evidence", "当前页面显示测试页面")
        }))
        val currentOwner = app.managerStore.templates.value.single()
        assertTrue(runtime.start(AgentConfigRequest(task = owner.task), taskId = owner.id,
            claim = RunClaim(currentOwner.executionRevision)).ok)
        val result = completed()
        assertEquals("测试结束", result.message)
        assertTrue(result.success)
        assertTrue(result.dismissed)
        assertEquals(TaskRunState.success, app.managerStore.templates.value.single().lastRun.state)
    }

    @Test fun `先保存成功结果再收尾且清理失败不重试业务`() = runBlocking {
        val owner = app.managerStore.templates.value.single()
        app.managerStore.upsert(owner.copy(postRunAction = com.ondeviceagent.android.manager.PostRunAction.dismissAndReturn, cleanupPackage = "test.app"))
        postRunHook = { throw IllegalStateException("清理模拟失败") }
        server.enqueue(response("Finish", buildJsonObject { put("status", "success"); put("message", "测试结束"); put("evidence", "测试页面") }))
        start(); val result = completed()
        assertTrue(result.success)
        assertEquals(1, postRunCalls)
        assertNull(app.managerStore.templates.value.single().pendingRetryAtEpochMs)
        val detail = TraceRecorder(app).loadDetail(result.runId)!!
        assertTrue(detail.postRunMessage.contains("清理模拟失败"))
        assertTrue(detail.manifest.final_status.success)
    }

    @Test fun `默认保持页面不会触发收尾`() = runBlocking {
        server.enqueue(response("Finish", buildJsonObject { put("status", "success"); put("message", "测试结束"); put("evidence", "测试页面") }))
        start(); assertTrue(completed().success)
        assertEquals(0, postRunCalls)
    }

    @Test fun `仅成功后返回的设置在失败时不收尾`() = runBlocking {
        val owner = app.managerStore.templates.value.single()
        app.managerStore.upsert(owner.copy(postRunAction = com.ondeviceagent.android.manager.PostRunAction.returnToAgent))
        server.enqueue(response("Finish", buildJsonObject { put("status", "failed"); put("message", "未完成") }))
        start(); assertFalse(completed().success)
        assertEquals(0, postRunCalls)
    }

    @Test fun `选择成功或失败后返回在业务失败时也收尾`() = runBlocking {
        val owner = app.managerStore.templates.value.single()
        app.managerStore.upsert(owner.copy(postRunAction = com.ondeviceagent.android.manager.PostRunAction.returnToAgent,
            postRunWhen = com.ondeviceagent.android.manager.PostRunWhen.successOrFailure))
        server.enqueue(response("Finish", buildJsonObject { put("status", "failed"); put("message", "测试结束") }))
        start(); assertFalse(completed().success)
        assertEquals(1, postRunCalls)
    }

    @Test fun `收尾完成前不允许下次启动覆盖现场`() = runBlocking {
        val owner = app.managerStore.templates.value.single()
        app.managerStore.upsert(owner.copy(postRunAction = com.ondeviceagent.android.manager.PostRunAction.returnToAgent))
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        postRunHook = { entered.complete(Unit); release.await(); "模拟返回" }
        server.enqueue(response("Finish", buildJsonObject { put("status", "success"); put("message", "测试结束"); put("evidence", "测试页面") }))
        start()
        withTimeout(5000) { entered.await() }
        val contender = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch { runtime.start(AgentConfigRequest(task = "")) }
        delay(100)
        assertFalse(contender.isCompleted)
        release.complete(Unit)
        withTimeout(5000) { contender.join() }
        assertEquals(1, postRunCalls)
    }

    @Test fun `Home用于纠错后继续执行不会自动判成功`() = runBlocking {
        server.enqueue(response("Launch", buildJsonObject { put("app", "测试") }))
        server.enqueue(response("Home"))
        server.enqueue(response("Launch", buildJsonObject { put("app", "测试") }))
        server.enqueue(response("Finish", buildJsonObject { put("status", "failed"); put("message", "尚未完成") }))
        start(); val result = completed()
        assertFalse(result.success); assertEquals("model_failed", result.stopReason)
        assertEquals(listOf("launch", "home", "launch"), actions.toList())
    }
    @Test fun `人工协助终态不会记作成功或安排重试`() = runBlocking {
        server.enqueue(response("Finish", buildJsonObject { put("status", "blocked"); put("message", "需要验证码") }))
        start(); assertFalse(completed().success)
        assertEquals(TaskRunState.blocked, app.managerStore.templates.value.single().lastRun.state)
        assertNull(app.managerStore.templates.value.single().pendingRetryAtEpochMs)
    }
    @Test fun `只有当前页面满足成功判据才完成`() = runBlocking {
        val owner = app.managerStore.templates.value.single()
        app.managerStore.upsert(owner.copy(completionText = "打卡成功")); screen = "打卡成功"
        server.enqueue(response("Finish", buildJsonObject { put("status", "success"); put("evidence", "当前页面显示打卡成功") }))
        start(); assertTrue(completed().success)
        assertEquals(TaskRunState.success, app.managerStore.templates.value.single().lastRun.state)
    }
    @Test fun `请求中途取消后旧动作不会执行`() = runBlocking {
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                release.await(5, TimeUnit.SECONDS)
                return response("Tap", buildJsonObject { put("text", "测试页面") })
            }
        }
        try {
            start(); assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) })
            app.managerStore.cancelRetry("runtime-test")
            release.countDown()
            assertEquals("cancelled", completed().stopReason)
            assertTrue(actions.isEmpty())
            assertNull(app.managerStore.templates.value.single().pendingRetryAtEpochMs)
        } finally { release.countDown() }
    }
    @Test fun `停止立即取消等待中的模型请求`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        start(); assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) })
        withTimeout(1500) { assertFalse(runtime.stop().running) }
        assertTrue(actions.isEmpty())
        assertNull(app.managerStore.templates.value.single().activeRunId)
        assertEquals(TaskRunState.stopped, app.managerStore.templates.value.single().lastRun.state)
    }
    @Test fun `提交检查点先持久化并阻止再次提交`() = runBlocking {
        screen = "提交"
        server.enqueue(response("Tap", buildJsonObject { put("text", "提交") }))
        server.enqueue(response("Tap", buildJsonObject { put("text", "提交") }))
        start(); val result = completed()
        assertEquals("blocked", result.stopReason)
        assertEquals(listOf("tap"), actions.toList())
        assertEquals("提交", app.managerStore.templates.value.single().submissionPending)
    }    @Test fun `模型认证错误会阻塞而不循环重试`() = runBlocking {
        app.managerStore.upsert(app.managerStore.templates.value.single().copy(maxRetries = 3))
        server.enqueue(MockResponse().setResponseCode(401).setBody("invalid key"))
        start(); assertEquals("blocked", completed().stopReason)
        assertNull(app.managerStore.templates.value.single().pendingRetryAtEpochMs)
        assertTrue(actions.isEmpty())
    }

}
