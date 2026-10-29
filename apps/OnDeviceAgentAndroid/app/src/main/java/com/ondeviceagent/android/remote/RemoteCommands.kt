package com.ondeviceagent.android.remote

import org.json.JSONObject

/** 命令严格解析，只有明确的斜杠命令会进入执行路径。 */
object RemoteCommands {
    val help = "/帮助：命令说明\n/任务：已有任务与 ID\n/启动 <任务ID>：运行已有任务\n/执行 <任务内容>：执行一次，不保存模板、不定时、不自动重试\n/状态：当前状态\n/停止：停止当前执行或取消最近执行的待重试\n/结果：最近结果"
    data class Command(val name: String, val argument: String = "")
    data class Message(val id: String, val sender: String, val chat: String, val text: String, val createdAt: Long, val group: Boolean = false)
    fun parse(text: String): Command? {
        val value = text.trim()
        if (!value.startsWith('/')) return null
        val parts = value.split(Regex("\\s+"), limit = 2)
        return Command(parts[0], parts.getOrElse(1) { "" }.trim())
    }
    fun fromEvent(raw: String, settings: RemoteSettings, appId: String, botId: String, now: Long): Message? {
        val obj = JSONObject(raw)
        val header = obj.optJSONObject("header") ?: return null
        if (header.optString("event_type") != "im.message.receive_v1" || header.optString("app_id") != appId) return null
        val event = obj.optJSONObject("event") ?: return null
        val sender = event.optJSONObject("sender") ?: return null
        if (sender.optString("sender_type") != "user") return null
        val user = sender.optJSONObject("sender_id")?.optString("open_id").orEmpty()
        if (!settings.enabled || user !in settings.users()) return null
        val msg = event.optJSONObject("message") ?: return null
        if (msg.optString("message_type") != "text") return null
        val id = msg.optString("message_id")
        val chat = msg.optString("chat_id")
        val time = msg.optString("create_time").toLongOrNull() ?: return null
        if (id.isBlank() || chat.isBlank() || time > now + 60_000 || now - time > settings.commandTtlMinutes * 60_000L) return null
        var text = JSONObject(msg.optString("content", "{}")).optString("text")
        val mentions = msg.optJSONArray("mentions")
        if (msg.optString("chat_type") == "group") {
            if (chat !in settings.chats() || botId.isBlank()) return null
            val botMentions = (0 until (mentions?.length() ?: 0)).mapNotNull { mentions?.optJSONObject(it) }
                .filter { it.optJSONObject("id")?.optString("open_id") == botId }
            if (botMentions.isEmpty()) return null
            botMentions.forEach { mention ->
                val key = mention.optString("key")
                if (key.isNotBlank()) text = text.replace(key, "")
            }
        } else if (msg.optString("chat_type") != "p2p") return null
        if (parse(text) == null || text.length > 6000) return null
        return Message(id, user, chat, text.trim(), time, msg.optString("chat_type") == "group")
    }
}
