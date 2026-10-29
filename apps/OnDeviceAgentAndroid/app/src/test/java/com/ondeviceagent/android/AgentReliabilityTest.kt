package com.ondeviceagent.android

import com.ondeviceagent.android.automation.ScreenGeometry
import com.ondeviceagent.android.automation.TargetMatcher
import com.ondeviceagent.android.llm.ActionOutputRecovery
import com.ondeviceagent.android.llm.ActionParser
import com.ondeviceagent.android.manager.FailureRetryPolicy
import com.ondeviceagent.android.manager.ScheduleKind
import com.ondeviceagent.android.manager.TaskSchedule
import com.ondeviceagent.android.manager.TaskTemplate
import com.ondeviceagent.android.manager.TaskTemplateDraft
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class AgentReliabilityTest {
    private val storageJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val valid = """{"think":"确认页面","plan":[],"action":{"name":"Tap","params":{"element":[500,900]}}}"""
    private fun task() = TaskTemplate(title = "测试任务", task = "打开测试页面")

    @Test fun `整屏坐标包含底部导航区域`() {
        val point = ScreenGeometry.toPixels(500.0, 900.0, 1080, 2400)
        assertEquals(540f, point.first, 0.01f)
        assertEquals(2160f, point.second, 0.01f)
        assertEquals(2399f, ScreenGeometry.toPixels(1000.0, 1000.0, 1080, 2400).second, 0.01f)
    }

    @Test fun `完整文案优先于局部匹配`() {
        assertEquals("登录", TargetMatcher.uniqueMatch(listOf("取消登录", "登录"), "登录") { listOf(it) })
    }

    @Test fun `重复同名或多个模糊匹配时不猜测目标`() {
        assertNull(TargetMatcher.uniqueMatch(listOf("登录", "登录"), "登录") { listOf(it) })
        assertNull(TargetMatcher.uniqueMatch(listOf("工作台入口", "关闭工作台"), "工作台") { listOf(it) })
    }

    @Test fun `图标描述可以唯一定位`() {
        assertEquals("返回", TargetMatcher.uniqueMatch(listOf("返回", "更多"), "返回") { listOf(it) })
    }

    @Test fun `修正数组漏逗号不改变字符串内数字空格`() {
        val text = """{"think":"输入 123 456，保持原样,}","action":{"name":"Tap","params":{"element":[300 310],}}} """
        val parsed = ActionParser.parse(text)
        assertEquals("输入 123 456，保持原样,}", parsed.think)
        assertTrue(parsed.rawJson.contains("[300, 310]"))
    }

    @Test fun `合法密码及转义不被修复器修改`() {
        val source = """{"action":{"name":"Type","params":{"text":"123 456,} \\\"测试\\\""}}}"""
        val parsed = ActionParser.parse(source)
        assertEquals(Json.parseToJsonElement(source).toString(), Json.parseToJsonElement(parsed.rawJson).toString())
    }

    @Test fun `不猜测修复未转义引号`() {
        val bad = """{"think":"点击"工作台"按钮","action":{"name":"Tap","params":{"element":[500,900]}}}"""
        assertTrue(runCatching { ActionParser.parse(bad) }.isFailure)
    }

    @Test fun `Markdown代码块输出保持兼容`() {
        assertEquals("Tap", ActionParser.parse("```json\n$valid\n```").actionName)
    }

    @Test fun `正常输出不调用纠错模型`() = runBlocking {
        val result = ActionOutputRecovery.parse(valid) { _, _, _ -> error("不应补救") }
        assertEquals(0, result.repairs)
    }

    @Test fun `坏JSON补救后仅执行最终动作且忽略补救计划`() = runBlocking {
        var calls = 0
        val result = ActionOutputRecovery.parse("截断的 JSON") { _, _, attempt ->
            calls++
            assertEquals(1, attempt)
            valid.replace("\"plan\":[]", "\"plan\":[{\"text\":\"错误新增计划\",\"done\":true}]")
        }
        assertEquals(1, calls)
        assertEquals("Tap", result.output.actionName)
        assertTrue(result.output.plan.isEmpty())
    }

    @Test fun `连续坏输出最多纠错两次后失败`() = runBlocking {
        var calls = 0
        val result = runCatching { ActionOutputRecovery.parse("bad") { _, _, _ -> calls++; "bad" } }
        assertTrue(result.isFailure)
        assertEquals(2, calls)
    }

    @Test fun `取消补救立即传播且不继续请求`() = runBlocking {
        var calls = 0
        val failure = runCatching { ActionOutputRecovery.parse("bad") { _, _, _ -> calls++; throw CancellationException() } }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(1, calls)
    }

    @Test fun `运行或启动失败均进入一次重试策略`() {
        for (reason in listOf("parse_error", "model_error", "action_failed", "max_steps", "runtime_error", "start_failed", "runtime_busy")) {
            assertEquals(300000L, FailureRetryPolicy.queue(task(), "r", reason, "", 0).pendingRetryAtEpochMs)
        }
    }

    @Test fun `失败后精确五分钟重试`() {
        val queued = FailureRetryPolicy.queue(task(), "run-1", "parse_error", "保留进度", 1000)
        assertEquals(301000L, queued.pendingRetryAtEpochMs)
        assertEquals(1, queued.retryAttempt)
        assertEquals("run-1", queued.retrySourceRunId)
    }

    @Test fun `重试再次失败不无限循环`() {
        val queued = FailureRetryPolicy.queue(task(), "run-1", "model_error", "", 1000)
        val executing = FailureRetryPolicy.prepare(queued, true)
        val secondFailure = FailureRetryPolicy.queue(executing, "run-2", "parse_error", "", 400000)
        assertNull(secondFailure.pendingRetryAtEpochMs)
        assertEquals(1, secondFailure.retryAttempt)
    }

    @Test fun `手动停止取消和成功不触发重试`() {
        for (reason in listOf("stopped", "cancelled", "finish", "reset")) {
            assertNull(FailureRetryPolicy.queue(task(), "run", reason, "", 0).pendingRetryAtEpochMs)
        }
    }

    @Test fun `关闭重试和暂停任务都不进入重试`() {
        assertNull(FailureRetryPolicy.queue(task().copy(retryOnFailure = false), "r", "parse_error", "", 0).pendingRetryAtEpochMs)
        assertNull(FailureRetryPolicy.queue(task().copy(isSchedulePaused = true), "r", "parse_error", "", 0).pendingRetryAtEpochMs)
    }

    @Test fun `新手动运行取消旧重试并重新计算额度`() {
        val queued = FailureRetryPolicy.queue(task(), "r", "parse_error", "旧进度", 0)
        val fresh = FailureRetryPolicy.prepare(queued, false)
        assertNull(fresh.pendingRetryAtEpochMs)
        assertEquals(0, fresh.retryAttempt)
        assertEquals("", fresh.retryContext)
        assertNotNull(FailureRetryPolicy.queue(fresh, "r2", "parse_error", "", 10).pendingRetryAtEpochMs)
    }

    @Test fun `单次计划清除后仍能按失败重试`() {
        val queued = FailureRetryPolicy.queue(task().copy(schedule = TaskSchedule(kind = ScheduleKind.none)), "r", "parse_error", "", 0)
        assertEquals(300000L, FailureRetryPolicy.nextAlarm(queued, null))
    }

    @Test fun `正常计划与重试选择更早时间互不覆盖`() {
        val queued = FailureRetryPolicy.queue(task(), "r", "parse_error", "", 0)
        assertEquals(200000L, FailureRetryPolicy.nextAlarm(queued, 200000))
        assertEquals(300000L, FailureRetryPolicy.nextAlarm(queued, 500000))
    }

    @Test fun `暂停后即使残留重试也不注册闹钟`() {
        val queued = FailureRetryPolicy.queue(task(), "r", "parse_error", "", 0).copy(isSchedulePaused = true)
        assertNull(FailureRetryPolicy.nextAlarm(queued, null))
    }

    @Test fun `旧任务数据能无损迁移且重试默认为开启`() {
        val old = """{"id":"old-task","title":"原任务","task":"打开页面","schedule":{"kind":"weekly","weekdays":[6,7,1]}}"""
        val decoded = storageJson.decodeFromString<TaskTemplate>(old)
        assertTrue(decoded.retryOnFailure)
        assertNull(decoded.pendingRetryAtEpochMs)
        assertEquals(listOf(6,7,1), decoded.schedule.weekdays)
        val roundtrip = storageJson.decodeFromString<TaskTemplate>(storageJson.encodeToString(decoded))
        assertEquals(decoded, roundtrip)
    }

    @Test fun `待重试状态序列化后保留时间与上下文`() {
        val queued = FailureRetryPolicy.queue(task(), "r", "parse_error", "已完成登录，必须核对提交", 0)
        assertEquals(queued, storageJson.decodeFromString<TaskTemplate>(storageJson.encodeToString(queued)))
    }

    @Test fun `编辑任务取消旧的待重试状态`() {
        val queued = FailureRetryPolicy.queue(task(), "r", "parse_error", "旧进度", 0)
        val edited = TaskTemplateDraft.from(queued).copy(title = "新标题").toTemplate(queued)
        assertNull(edited.pendingRetryAtEpochMs)
        assertEquals(0, edited.retryAttempt)
        assertEquals(queued.schedule.weekdays, edited.schedule.weekdays)
    }

    @Test fun `关闭重试设置可经编辑保存`() {
        val edited = TaskTemplateDraft.from(task()).copy(retryOnFailure = false).toTemplate(task())
        assertFalse(edited.retryOnFailure)
    }
}
