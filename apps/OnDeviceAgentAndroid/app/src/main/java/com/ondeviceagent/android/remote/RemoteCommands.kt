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
    fun fromEvent(raw: String, settings: RemoteSettings, appId: String, botId: String, now: Long, diagnostic: (String) -> Unit = {}): Message? {
        fun reject(reason: String): Message? { diagnostic(reason); return null }
        val obj = JSONObject(raw)
        val header = obj.optJSONObject("header") ?: return reject("忽略：事件缺少 header")
        if (header.optString("event_type") != "im.message.receive_v1") return reject("忽略：不是接收消息事件")
        if (header.optString("app_id") != appId) return reject("忽略：事件 App ID 与本机不一致")
        val event = obj.optJSONObject("event") ?: return reject("忽略：事件缺少 event")
        val sender = event.optJSONObject("sender") ?: return reject("忽略：事件缺少 sender")
        if (sender.optString("sender_type") != "user") return reject("忽略：发送者不是用户")
        val user = sender.optJSONObject("sender_id")?.optString("open_id").orEmpty()
        // 仅在手机显示格式合法的会话标识，绝不显示消息正文或凭证。
        val msg = event.optJSONObject("message") ?: return reject("忽略：事件缺少 message")
        fun safeId(value: String, prefix: String) = value.takeIf { it.matches(Regex(prefix + "[A-Za-z0-9_-]{1,128}")) } ?: "缺失或格式异常"
        diagnostic("收到消息事件；用户：${safeId(user, "ou_")}；会话：${safeId(msg.optString("chat_id"), "oc_")}")
        if (!settings.enabled) return reject("忽略：远程控制未启用")
        if (user !in settings.users()) return reject("忽略：发送者不在用户白名单，请核对上方收到的 ou_ ID")
        if (msg.optString("message_type") != "text") return reject("忽略：只支持文本消息")
        val id = msg.optString("message_id")
        val chat = msg.optString("chat_id")
        val time = msg.optString("create_time").toLongOrNull() ?: return reject("忽略：消息时间格式异常")
        if (id.isBlank() || chat.isBlank()) return reject("忽略：消息或会话 ID 缺失")
        if (time > now + 60_000 || now - time > settings.commandTtlMinutes * 60_000L) return reject("忽略：消息过期或手机时间不准确")
        var text = JSONObject(msg.optString("content", "{}")).optString("text")
        val mentions = msg.optJSONArray("mentions")
        if (msg.optString("chat_type") == "group") {
            if (chat !in settings.chats()) return reject("忽略：群不在群白名单，请核对上方收到的 oc_ ID")
            if (botId.isBlank()) return reject("忽略：未确认机器人身份")
            val botMentions = (0 until (mentions?.length() ?: 0)).mapNotNull { mentions?.optJSONObject(it) }
                .filter { it.optJSONObject("id")?.optString("open_id") == botId }
            if (botMentions.isEmpty()) return reject("忽略：群消息未 @当前机器人")
            botMentions.forEach { mention ->
                val key = mention.optString("key")
                if (key.isNotBlank()) text = text.replace(key, "")
            }
        } else if (msg.optString("chat_type") != "p2p") return reject("忽略：不支持此会话类型")
        if (text.length > 6000) return reject("忽略：消息超过长度限制")
        if (parse(text) == null) return reject("忽略：不是以半角 / 开头的命令")
        return Message(id, user, chat, text.trim(), time, msg.optString("chat_type") == "group")
    }
}
