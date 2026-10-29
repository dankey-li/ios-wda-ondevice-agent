package com.ondeviceagent.android

import com.ondeviceagent.android.manager.*
import com.ondeviceagent.android.llm.*
import com.ondeviceagent.android.automation.ProgressTracker
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ConfigurableRetryTest {
    private fun task() = TaskTemplate(id = "t", title = "测试", task = "测试任务", maxRetries = 3, retryIntervalMinutes = 2, retryWindowMinutes = 10)

    @Test fun `三次重试按配置计数且不包含首次执行`() {
        var current = task()
        for (i in 1..3) {
            current = FailureRetryPolicy.queue(current, "r$i", "parse_error", "上下文", i * 120000L)
            assertEquals(i, current.retryAttempt)
            assertEquals((i + 1) * 120000L, current.pendingRetryAtEpochMs)
            current = FailureRetryPolicy.prepare(current, true)
        }
        assertNull(FailureRetryPolicy.queue(current, "r4", "parse_error", "", 600000).pendingRetryAtEpochMs)
    }
    @Test fun `零次关闭自动重试`() { assertNull(FailureRetryPolicy.queue(task().copy(maxRetries = 0), "r", "parse_error", "", 0).pendingRetryAtEpochMs) }
    @Test fun `重试不重新延长截止时间`() {
        val first = FailureRetryPolicy.queue(task(), "r", "parse_error", "", 0)
        val next = FailureRetryPolicy.queue(FailureRetryPolicy.prepare(first, true), "r2", "parse_error", "", 550000)
        assertEquals(600000L, next.retryDeadlineAtEpochMs)
        assertNull(next.pendingRetryAtEpochMs)
    }
    @Test fun `占用延后不能越过有效期`() {
        val queued = FailureRetryPolicy.queue(task(), "r", "parse_error", "", 0)
        val expired = FailureRetryPolicy.defer(queued, 550000)
        assertNull(expired.pendingRetryAtEpochMs)
        assertEquals(1, expired.retryAttempt)
        assertEquals(TaskRunState.failed, expired.lastRun.state)
    }
    @Test fun `人工阻塞不自动重试`() { assertNull(FailureRetryPolicy.queue(task(), "r", "blocked", "", 0).pendingRetryAtEpochMs) }
    @Test fun `旧任务保留一次五分钟默认值`() {
        val old = Json.decodeFromString<TaskTemplate>("""{"title":"旧任务","task":"测试"}""")
        assertEquals(1, old.maxRetries); assertEquals(5, old.retryIntervalMinutes); assertEquals(30, old.retryWindowMinutes)
    }
    @Test fun `编辑配置完整往返且保护检查点保留`() {
        val old = task().copy(submissionPending = "提交")
        val edited = TaskTemplateDraft.from(old).copy(maxRetries = 4, retryIntervalMinutes = 3, retryWindowMinutes = 60, completionText = "成功", maxRunMinutes = 20).toTemplate(old)
        assertEquals(4, edited.maxRetries); assertEquals(3, edited.retryIntervalMinutes)
        assertEquals("成功", edited.completionText); assertEquals("提交", edited.submissionPending)
        val json = Json { encodeDefaults = true }
        assertEquals(edited, json.decodeFromString<TaskTemplate>(json.encodeToString(edited)))
    }
    @Test fun `人工协助和缺失终态不冒充成功`() {
        assertFalse(CompletionPolicy.evaluate("blocked", "", "验证码", "", "").success)
        assertFalse(CompletionPolicy.evaluate("", "", "已完成", "", "").success)
    }
    @Test fun `成功须有证据且满足指定文字`() {
        assertFalse(CompletionPolicy.evaluate("success", "", "已完成", "", "").success)
        assertFalse(CompletionPolicy.evaluate("success", "已打卡", "", "打卡成功", "登录页面").success)
        assertTrue(CompletionPolicy.evaluate("success", "看到打卡成功", "", "打卡成功", "页面显示打卡成功").success)
    }
    @Test fun `重复动作和页面不变会被识别`() {
        val tracker = ProgressTracker()
        repeat(3) { assertFalse(tracker.stalled("页面A", "tap", "[500,800]")) }
        assertTrue(tracker.stalled("页面A", "tap", "[500,800]"))
        assertFalse(tracker.stalled("页面B", "tap", "[500,800]"))
    }
    @Test fun `计划稳定ID与标点差异不会追加重复步骤`() {
        val initial = ActionParser.mergePlan(emptyList(), listOf(PlanItem(text = "登录，进入工作台")))
        assertTrue(initial.single().id.isNotBlank())
        val next = ActionParser.mergePlan(initial, listOf(PlanItem(text = "登录进入工作台", done = true)))
        assertEquals(1, next.size); assertEquals(initial.single().id, next.single().id)
        assertEquals(initial.single().text, next.single().text); assertTrue(next.single().done)
    }
    @Test fun `已过期或已取消重试票据不能领取`() {
        val queued = FailureRetryPolicy.queue(task(), "r", "parse_error", "", 0)
        val claim = RunClaim(queued.executionRevision, retryAt = queued.pendingRetryAtEpochMs)
        assertTrue(claim.matches(queued, 120000))
        assertFalse(claim.matches(FailureRetryPolicy.clear(queued), 120000))
        assertFalse(claim.matches(queued, 700000))
    }    @Test fun `已取消或未来的正常计划资格不能提前执行`() {
        val schedule = TaskSchedule(ScheduleKind.once, 600000)
        val task = task().copy(schedule = schedule)
        val signature = ScheduleCalculator.occurrenceSignature(schedule, 600000)!!
        assertFalse(RunClaim(task.executionRevision, occurrence = signature).matches(task, 1000))
        assertTrue(RunClaim(task.executionRevision, occurrence = signature).matches(task, 600000))
        assertFalse(RunClaim(task.executionRevision, occurrence = signature).matches(task.copy(lastTriggeredSignature = signature), 600000))
    }
    @Test fun `提交保护不误拦截导航入口`() {
        val labels = task().guardedActionTexts
        assertFalse(SubmissionPolicy.protected("打卡签到", labels))
        assertFalse(SubmissionPolicy.protected("提交记录", labels))
        assertTrue(SubmissionPolicy.protected("上班打卡", labels))
        assertTrue(SubmissionPolicy.protected("提交", labels))
    }
    @Test fun `模型使用简称时仍按实际按钮文案应用提交保护`() {
        val actual = SubmissionPolicy.resolveLabel("提", listOf("提交", "返回"))
        assertEquals("提交", actual)
        assertTrue(SubmissionPolicy.protected(actual, "提交"))
        assertEquals("提交记录", SubmissionPolicy.resolveLabel("提交记录", listOf("提交", "提交记录")))
        assertEquals("", SubmissionPolicy.resolveLabel("提", listOf("提交", "提交记录")))
    }

}
