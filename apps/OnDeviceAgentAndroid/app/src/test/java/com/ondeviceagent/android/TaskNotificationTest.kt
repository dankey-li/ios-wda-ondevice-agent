package com.ondeviceagent.android

import com.ondeviceagent.android.manager.*
import com.ondeviceagent.android.notify.*
import kotlinx.serialization.json.Json
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = OnDeviceAgentApp::class)
class TaskNotificationTest {
    private fun task() = TaskTemplate(title = "测试任务", task = "查看页面")
    @Test fun `旧任务默认开启失败通知且编辑能保留关闭值`() {
        val old = Json.decodeFromString<TaskTemplate>("""{"title":"旧任务","task":"查看"}""")
        assertTrue(old.notifyOnFailure)
        val edited = TaskTemplateDraft.from(old.copy(notifyOnFailure = false)).toTemplate(old)
        assertFalse(edited.notifyOnFailure)
    }
    @Test fun `人工核对使用橙色且不伪装为成功`() {
        val note = TaskNotificationPolicy.create(task(), false, "blocked", "需要验证码", "run")!!
        assertEquals("orange", note.color); assertTrue(note.heading.contains("核对"))
        assertFalse(note.success); assertTrue(note.message.contains("没有待执行"))
    }
    @Test fun `停止取消与关闭通知均不发失败推送`() {
        for (reason in listOf("stopped", "cancelled")) assertNull(TaskNotificationPolicy.create(task(), false, reason, "", ""))
        assertNull(TaskNotificationPolicy.create(task().copy(notifyOnFailure = false), false, "parse_error", "", ""))
        assertNull(TaskNotificationPolicy.create(task(), true, "finish", "", ""))
    }
    @Test fun `成功通知开关及绿色卡片保持原行为`() {
        val note = TaskNotificationPolicy.create(task().copy(notifyOnSuccess = true), true, "finish", "完成", "run")!!
        val json = JSONObject(FeishuWebhookNotifier.buildInteractiveCard(note.heading, note.message, null, note.color))
        assertEquals("green", json.getJSONObject("card").getJSONObject("header").getString("template"))
    }
    @Test fun `失败卡片标题颜色和截图位置正确`() {
        val note = TaskNotificationPolicy.create(task(), false, "parse_error", "解析失败", "run")!!
        val card = JSONObject(FeishuWebhookNotifier.buildInteractiveCard(note.heading, note.message, "mock-image", note.color)).getJSONObject("card")
        assertEquals("red", card.getJSONObject("header").getString("template"))
        assertTrue(card.getJSONObject("header").getJSONObject("title").getString("content").contains("失败"))
        assertEquals("mock-image", card.getJSONArray("elements").getJSONObject(1).getString("img_key"))
    }
    @Test fun `启动失败落盘后通知且不会改变原重试策略`() {
        val app = RuntimeEnvironment.getApplication() as OnDeviceAgentApp
        val store = app.managerStore
        val notices = mutableListOf<Pair<TaskTemplate, String>>()
        store.onFailureNotice = { t, reason -> notices += t to reason }
        store.upsert(task())
        val owner = store.templates.value.single()
        store.recordStartFailure(owner.id, RunClaim(owner.executionRevision), "blocked", "无障碍未开启")
        assertEquals(1, notices.size)
        assertEquals(TaskRunState.blocked, notices.single().first.lastRun.state)
        assertNull(notices.single().first.pendingRetryAtEpochMs)
        store.recordStartFailure(owner.id, RunClaim(owner.executionRevision - 1), "start_failed", "旧事件")
        assertEquals(1, notices.size)
    }
}
