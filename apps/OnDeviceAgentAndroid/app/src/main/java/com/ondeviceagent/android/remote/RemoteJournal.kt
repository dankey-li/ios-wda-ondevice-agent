package com.ondeviceagent.android.remote

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.security.MessageDigest

@Serializable
data class RemoteEntry(
    val key: String, val appId: String, val messageId: String, val chatId: String, val userId: String,
    val text: String, val createdAt: Long, val receivedAt: Long,
    val group: Boolean = false,
    val state: String = "received", val runId: String = "", val rootRunId: String = "", val taskId: String = "", val ephemeral: Boolean = false,
)
@Serializable
data class RemoteReply(val id: String, val appId: String, val chatId: String, val text: String, val at: Long,
    val userId: String = "", val group: Boolean = false,
    val attempts: Int = 0, val nextAt: Long = 0)

/** 接收去重和回复发件箱先同步落盘；任何发送失败都不能重放手机动作。 */
class RemoteJournal(context: Context) {
    private val prefs = context.getSharedPreferences("feishu_remote_journal", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val entryType = ListSerializer(RemoteEntry.serializer())
    private val replyType = ListSerializer(RemoteReply.serializer())
    @Volatile var entries: List<RemoteEntry> = json.decodeFromString(entryType, prefs.getString("entries", "[]")!!)
        private set
    @Volatile var replies: List<RemoteReply> = json.decodeFromString(replyType, prefs.getString("replies", "[]")!!)
        private set
    @Synchronized fun accept(appId: String, message: RemoteCommands.Message, now: Long): Boolean {
        val key = "$appId:${message.id}"
        if (entries.any { it.key == key }) return false
        val kept = entries.filter { now - it.receivedAt < 86_400_000 || it.state != "done" }
        check(kept.size < 2000) { "指令记录已满，拒绝新指令" }
        val entry = RemoteEntry(key, appId, message.id, message.chat, message.sender, message.text, message.createdAt, now, group = message.group)
        save(kept + entry, replies)
        return true
    }
    @Synchronized fun update(entry: RemoteEntry) = save(entries.map { if (it.key == entry.key) entry else it }, replies)
    @Synchronized fun reply(entry: RemoteEntry, phase: String, text: String, now: Long) {
        val id = MessageDigest.getInstance("SHA-256").digest("${entry.key}:$phase".toByteArray()).take(16).joinToString("") { "%02x".format(it) }
        if (replies.none { it.id == id }) save(entries, replies + RemoteReply(id, entry.appId, entry.chatId, text.take(3000), now, userId = entry.userId, group = entry.group))
    }
    @Synchronized fun sent(id: String) = save(entries, replies.filterNot { it.id == id })
    @Synchronized fun failed(id: String, now: Long) = save(entries, replies.map { if (it.id == id) it.copy(attempts = it.attempts + 1,
        nextAt = now + minOf(60_000L, 2000L * (1L shl minOf(it.attempts, 5)))) else it })
    @Synchronized fun recoverInterrupted() {
        save(entries.map { if (it.state == "dispatching") it.copy(state = "interrupted") else it }, replies)
    }
    private fun save(nextEntries: List<RemoteEntry>, nextReplies: List<RemoteReply>) {
        check(prefs.edit().putString("entries", json.encodeToString(entryType, nextEntries))
            .putString("replies", json.encodeToString(replyType, nextReplies)).commit()) { "远程指令记录保存失败" }
        entries = nextEntries; replies = nextReplies
    }
}
