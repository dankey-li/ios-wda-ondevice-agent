package com.ondeviceagent.android

import android.app.Application
import com.ondeviceagent.android.remote.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RemoteCommandsTest {
    private val now = 1_000_000L
    private val settings = RemoteSettings(enabled = true, allowedUsers = "ou_me", allowedChats = "oc_group")
    private fun event(group: Boolean = false, mention: String = "ou_bot", user: String = "ou_me", app: String = "cli_test", time: Long = now): String = JSONObject()
        .put("header", JSONObject().put("app_id", app).put("event_type", "im.message.receive_v1"))
        .put("event", JSONObject().put("sender", JSONObject().put("sender_type", "user").put("sender_id", JSONObject().put("open_id", user)))
            .put("message", JSONObject().put("message_id", "om_1").put("chat_id", if (group) "oc_group" else "oc_direct").put("create_time", "$time")
                .put("chat_type", if (group) "group" else "p2p").put("message_type", "text")
                .put("content", JSONObject().put("text", if (group) "@_user_1 /执行 打开设置" else "/执行 打开设置").toString())
                .put("mentions", org.json.JSONArray().put(JSONObject().put("key", "@_user_1").put("id", JSONObject().put("open_id", mention)))))).toString()
    @Test fun `普通聊天不执行且多行任务正文完整保留`() {
        assertNull(RemoteCommands.parse("请执行打开设置"))
        assertEquals("打开设置\n查看电量", RemoteCommands.parse("/执行\n打开设置\n查看电量")!!.argument)
        assertEquals("/执行打开设置", RemoteCommands.parse("/执行打开设置")!!.name)
    }
    @Test fun `白名单或应用不匹配均拒绝接收`() {
        assertNotNull(RemoteCommands.fromEvent(event(), settings, "cli_test", "ou_bot", now))
        assertNull(RemoteCommands.fromEvent(event(user = "ou_other"), settings, "cli_test", "ou_bot", now))
        assertNull(RemoteCommands.fromEvent(event(app = "cli_other"), settings, "cli_test", "ou_bot", now))
        assertNull(RemoteCommands.fromEvent(event(), settings.copy(allowedUsers = ""), "cli_test", "ou_bot", now))
        assertNull(RemoteCommands.fromEvent(event(), settings.copy(enabled = false), "cli_test", "ou_bot", now))
    }
    @Test fun `群内必须在白名单且明确提及当前机器人`() {
        assertEquals("/执行 打开设置", RemoteCommands.fromEvent(event(true), settings, "cli_test", "ou_bot", now)!!.text)
        assertNull(RemoteCommands.fromEvent(event(true, "ou_otherbot"), settings, "cli_test", "ou_bot", now))
        assertNull(RemoteCommands.fromEvent(event(true), settings.copy(allowedChats = ""), "cli_test", "ou_bot", now))
    }
    @Test fun `离线过期和异常未来时间不执行`() {
        assertNull(RemoteCommands.fromEvent(event(time = now - 300001), settings, "cli_test", "ou_bot", now))
        assertNull(RemoteCommands.fromEvent(event(time = now + 60001), settings, "cli_test", "ou_bot", now))
    }
    @Test fun `去重记录跨实例持久化且区分应用`() {
        val context = RuntimeEnvironment.getApplication()
        val first = RemoteJournal(context)
        val message = RemoteCommands.Message("om_1", "ou_me", "oc_direct", "/执行 测试", now)
        assertTrue(first.accept("cli_test", message, now))
        assertFalse(RemoteJournal(context).accept("cli_test", message, now))
        assertTrue(RemoteJournal(context).accept("cli_other", message, now))
    }
    @Test fun `处理状态不确定时恢复为中断而不重放`() {
        val context = RuntimeEnvironment.getApplication()
        val journal = RemoteJournal(context)
        journal.accept("cli_test", RemoteCommands.Message("om_2", "ou_me", "oc_direct", "/执行 测试", now), now)
        journal.update(journal.entries.single().copy(state = "dispatching"))
        val restored = RemoteJournal(context); restored.recoverInterrupted()
        assertEquals("interrupted", restored.entries.single().state)
    }
    @Test fun `回复重试不新增指令且回复标识保持稳定`() {
        val journal = RemoteJournal(RuntimeEnvironment.getApplication())
        journal.accept("cli_test", RemoteCommands.Message("om_3", "ou_me", "oc_direct", "/状态", now), now)
        val entry = journal.entries.single()
        journal.reply(entry, "result", "空闲", now); val id = journal.replies.single().id
        journal.failed(id, now); journal.reply(entry, "result", "空闲", now)
        assertEquals(1, journal.entries.size); assertEquals(1, journal.replies.size)
        assertEquals(id, journal.replies.single().id); assertEquals(1, journal.replies.single().attempts)
        assertTrue(journal.replies.single().nextAt > now)
    }
    @Test fun `指令记录损坏时不能静默清空后重新执行`() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("feishu_remote_journal", 0).edit().putString("entries", "broken-json").commit()
        assertThrows(Exception::class.java) { RemoteJournal(context) }
        assertEquals("broken-json", context.getSharedPreferences("feishu_remote_journal", 0).getString("entries", ""))
    }
    @Test fun `启用远程控制需要明确授权且校验时间范围`() {
        assertNotNull(RemoteSettings(enabled = true).validationError())
        assertNotNull(settings.copy(maxRunMinutes = 0).validationError())
        assertNotNull(settings.copy(commandTtlMinutes = 31).validationError())
        assertNull(settings.validationError())
    }
}
