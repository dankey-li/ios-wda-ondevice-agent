package com.ondeviceagent.android

import com.ondeviceagent.android.automation.*
import com.ondeviceagent.android.manager.*
import com.ondeviceagent.android.model.AgentConfigRequest
import com.ondeviceagent.android.remote.*
import com.ondeviceagent.android.runtime.*
import com.ondeviceagent.android.trace.TraceRecorder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = OnDeviceAgentApp::class)
class RemoteProcessorTest {
    private lateinit var app: OnDeviceAgentApp
    private lateinit var runtime: AgentRuntime
    private lateinit var server: MockWebServer
    private lateinit var journal: RemoteJournal
    private lateinit var processor: RemoteProcessor
    private val settings = RemoteSettings(enabled = true, allowedUsers = "ou_me", postRunAction = PostRunAction.returnToAgent)
    private val cleaned = mutableListOf<TaskTemplate>()
    private val appId = "cli_test"
    @Before fun setup() = runBlocking {
        app = RuntimeEnvironment.getApplication() as OnDeviceAgentApp
        server = MockWebServer(); server.start()
        journal = RemoteJournal(app)
        runtime = AgentRuntime(app, TraceRecorder(app), object : ExecutionEnvironment {
            override fun ready() = true
            override suspend fun screenshot(halfResolution: Boolean): String? = null
            override fun screenInfo(step: Int) = """{"package":"test.app","width_px":1080,"height_px":2400,"ui":[{"text":"测试成功"}]}"""
            override suspend fun perform(request: AgentActionRequest) = AgentActionResult(true)
            override suspend fun postRun(task: TaskTemplate): String { cleaned += task; return "模拟已返回任务页" }
        })
        runtime.applyConfig(AgentConfigRequest(baseUrl = server.url("/v1").toString(), model = "mock", apiKey = "mock-local", apiMode = "chat_completions", maxSteps = 20, stepDelaySeconds = 0.1))
        processor = RemoteProcessor(app, runtime, app.managerStore, journal, prepareDevice = { null })
    }
    @After fun cleanup() { runBlocking { runtime.stop() }; server.shutdown() }
    private fun accept(text: String, id: String = "om_one", age: Long = 0) {
        journal.accept(appId, RemoteCommands.Message(id, "ou_me", "oc_test", text, System.currentTimeMillis() - age), System.currentTimeMillis())
    }
    private fun success() = MockResponse().setBody("""{"choices":[{"message":{"content":"{\"action\":{\"name\":\"Finish\",\"params\":{\"status\":\"success\",\"message\":\"完成\",\"evidence\":\"测试成功\"}}}"}}]}""")
    private suspend fun completed() = withTimeout(10000) { runtime.uiState.first { !it.running && it.lastRunResult != null }.lastRunResult!! }
    @Test fun `执行一次不产生模板计划重试并应用独立结束后设置`() = runBlocking {
        accept("/执行 打开设置并检查")
        server.enqueue(success())
        processor.tick(appId, settings)
        val transient = app.managerStore.templates.value.single()
        assertTrue(transient.isEphemeral); assertFalse(transient.isTemplate)
        assertEquals(ScheduleKind.none, transient.schedule.kind)
        assertFalse(transient.retryOnFailure); assertEquals(0, transient.maxRetries)
        assertEquals(PostRunAction.returnToAgent, transient.postRunAction)
        val result = completed(); assertTrue(result.success)
        processor.tick(appId, settings)
        assertTrue(app.managerStore.templates.value.isEmpty())
        assertEquals(1, cleaned.size)
        assertTrue(runtime.loadTraceDetail(result.runId)!!.postRunMessage.contains("任务页"))
        assertEquals("done", journal.entries.single().state)
        assertEquals(3, journal.replies.size)
        assertTrue(journal.replies.any { it.text.contains("执行成功") })
        assertFalse(journal.accept(appId, RemoteCommands.Message("om_one", "ou_me", "oc_test", "/执行 打开设置并检查", System.currentTimeMillis()), System.currentTimeMillis()))
        processor.tick(appId, settings)
        assertEquals(1, server.requestCount)
    }
    @Test fun `启动已有任务使用任务自身的结束后设置`() = runBlocking {
        val existing = TaskTemplate(id = "existing-0001", title = "本地任务", task = "本地内容", retryOnFailure = false)
        app.managerStore.upsert(existing)
        accept("/启动 ${RemoteProcessor.taskCode(existing)}")
        server.enqueue(success())
        processor.tick(appId, settings)
        assertTrue(completed().success)
        processor.tick(appId, settings)
        assertTrue(cleaned.isEmpty())
        assertFalse(app.managerStore.templates.value.single().isEphemeral)
        assertEquals(TaskRunState.success, app.managerStore.templates.value.single().lastRun.state)
    }
    @Test fun `忙碌时拒绝第二个任务且不会入执行队列`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        accept("/执行 第一条", "om_first"); processor.tick(appId, settings)
        assertTrue(runtime.status().running)
        accept("/执行 第二条", "om_second"); processor.tick(appId, settings)
        assertEquals(1, app.managerStore.templates.value.size)
        assertEquals("done", journal.entries.first { it.messageId == "om_second" }.state)
        assertTrue(journal.replies.any { it.text.contains("未排队") })
    }
    @Test fun `执行中仍能通过停止命令取消模型等待`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        accept("/执行 测试", "om_run"); processor.tick(appId, settings)
        accept("/停止", "om_stop"); withTimeout(5000) { processor.tick(appId, settings) }
        assertFalse(runtime.status().running)
        processor.tick(appId, settings)
        assertTrue(app.managerStore.templates.value.isEmpty())
        assertTrue(journal.replies.any { it.text.contains("已停止") })
        assertTrue(cleaned.isEmpty())
    }
    @Test fun `过期撤销授权和参数错误不会启动手机动作`() = runBlocking {
        accept("/执行 过期任务", "om_old", 400000)
        accept("/执行", "om_empty")
        accept("/启动 missing", "om_missing")
        accept("/未知", "om_unknown")
        processor.tick(appId, settings)
        assertFalse(runtime.status().running)
        assertTrue(app.managerStore.templates.value.isEmpty())
        assertEquals(0, server.requestCount)
        assertTrue(journal.entries.all { it.state == "done" })
        accept("/执行 撤销后任务", "om_revoked")
        processor.tick(appId, settings.copy(allowedUsers = "ou_other"))
        assertEquals(0, server.requestCount)
    }
    @Test fun `手机被锁定时不创建一次性任务`() = runBlocking {
        val locked = RemoteProcessor(app, runtime, app.managerStore, journal, prepareDevice = { "手机已锁定" })
        accept("/执行 测试")
        locked.tick(appId, settings)
        assertTrue(app.managerStore.templates.value.isEmpty())
        assertFalse(runtime.status().running)
        assertTrue(journal.replies.any { it.text.contains("锁定") })
    }
    @Test fun `已有任务按配置等待重试并可由停止命令取消`() = runBlocking {
        val owner = TaskTemplate(id = "retry-owner", title = "重试任务", task = "重试内容", maxRetries = 1, retryIntervalMinutes = 1)
        app.managerStore.upsert(owner)
        accept("/启动 ${owner.id}")
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"{\"action\":{\"name\":\"Finish\",\"params\":{\"status\":\"failed\",\"message\":\"本次失败\"}}}"}}]}"""))
        processor.tick(appId, settings); assertFalse(completed().success)
        processor.tick(appId, settings)
        assertEquals("retry_wait", journal.entries.single().state)
        assertNotNull(app.managerStore.templates.value.single().pendingRetryAtEpochMs)
        accept("/停止", "om_cancel_retry"); processor.tick(appId, settings); processor.tick(appId, settings)
        assertNull(app.managerStore.templates.value.single().pendingRetryAtEpochMs)
        assertTrue(journal.entries.all { it.state == "done" })
        assertEquals(1, server.requestCount)
    }

    @Test fun `轮询间快速完成的重试通过父运行历史回传结果`() = runBlocking {
        val owner = TaskTemplate(id = "fast-retry", title = "快速重试", task = "内容", maxRetries = 1, retryIntervalMinutes = 1)
        app.managerStore.upsert(owner)
        accept("/启动 ${owner.id}")
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"{\"action\":{\"name\":\"Finish\",\"params\":{\"status\":\"failed\",\"message\":\"未完成\"}}}"}}]}"""))
        processor.tick(appId, settings); completed(); processor.tick(appId, settings)
        val entry = journal.entries.single()
        assertEquals("retry_wait", entry.state)
        val recorder = TraceRecorder(app)
        recorder.begin("fast-child", owner.task, owner.id, entry.rootRunId, 1)
        recorder.finish(true, "快速重试成功", "finished")
        app.managerStore.cancelRetry(owner.id)
        processor.tick(appId, settings); processor.tick(appId, settings)
        assertEquals("done", journal.entries.single().state)
        assertTrue(journal.replies.any { it.text.contains("快速重试成功") })
        assertEquals(1, server.requestCount)
    }

    @Test fun `飞书回复网络挂起不阻塞远程停止`() = runBlocking {
        val apiServer = MockWebServer(); apiServer.start()
        val sending = CompletableDeferred<Unit>()
        apiServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.contains("tenant_access_token") -> MockResponse().setBody("""{"code":0,"tenant_access_token":"mock-token","expire":7200}""")
                request.path!!.contains("bot/v3/info") -> MockResponse().setBody("""{"code":0,"bot":{"open_id":"ou_bot"}}""")
                request.path!!.contains("callback/ws/endpoint") -> MockResponse().setBody("""{"code":403}""")
                else -> { sending.complete(Unit); MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) }
            }
        }
        val api = FeishuApi(base = apiServer.url("/").toString().removeSuffix("/"))
        runtime.applyConfig(AgentConfigRequest(feishuAppId = appId, feishuAppSecret = "mock-secret"))
        RemoteSettingsStore(app).save(settings)
        org.robolectric.Shadows.shadowOf(app.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager).setIsInteractive(true)
        org.robolectric.Shadows.shadowOf(app.getSystemService(android.content.Context.KEYGUARD_SERVICE) as android.app.KeyguardManager).setKeyguardLocked(false)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        accept("/执行 测试", "om_hanging_run")
        val control = RemoteControl(app, runtime, app.managerStore, api, journalFactory = { journal })
        try {
            control.refresh()
            withTimeout(6000) { sending.await() }
            withTimeout(3000) { runtime.uiState.first { it.running } }
            assertTrue(runtime.status().running)
            accept("/停止", "om_hanging_stop")
            withTimeout(3000) { runtime.uiState.first { !it.running }; while (journal.replies.none { it.text.contains("已停止") }) delay(10) }
            assertTrue(journal.replies.any { it.text.contains("已停止") })
        } finally { control.stop(); api.client.dispatcher.cancelAll(); delay(100); apiServer.shutdown() }
    }

    @Test fun `群授权撤销后不执行此前已接收的指令`() = runBlocking {
        val now = System.currentTimeMillis()
        journal.accept(appId, RemoteCommands.Message("om_group", "ou_me", "oc_group", "/执行 测试", now, group = true), now)
        processor.tick(appId, settings)
        assertEquals(0, server.requestCount)
        assertTrue(app.managerStore.templates.value.isEmpty())
    }
}
