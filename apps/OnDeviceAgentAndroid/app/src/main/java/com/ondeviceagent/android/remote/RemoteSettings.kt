package com.ondeviceagent.android.remote

import android.content.Context
import com.ondeviceagent.android.manager.DismissDirection
import com.ondeviceagent.android.manager.PostRunAction
import com.ondeviceagent.android.manager.PostRunWhen
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class RemoteSettings(
    val enabled: Boolean = false,
    val allowedUsers: String = "",
    val allowedChats: String = "",
    val commandTtlMinutes: Int = 5,
    val maxRunMinutes: Int = 15,
    val completionText: String = "",
    val guardedActionTexts: String = "上班打卡,下班打卡,提交,发送",
    val postRunAction: PostRunAction = PostRunAction.keep,
    val postRunWhen: PostRunWhen = PostRunWhen.successOnly,
    val cleanupPackage: String = "",
    val dismissDirection: DismissDirection = DismissDirection.up,
) {
    fun users() = ids(allowedUsers)
    fun chats() = ids(allowedChats)
    fun validationError(): String? = when {
        enabled && (users().isEmpty() || users().any { !it.matches(Regex("ou_[A-Za-z0-9_-]+")) }) -> "请填写允许控制的飞书 open_id（ou_ 开头），不可留空"
        chats().any { !it.matches(Regex("oc_[A-Za-z0-9_-]+")) } -> "群会话 ID 须为 oc_ 开头；留空仅允许单聊"
        commandTtlMinutes !in 1..30 -> "指令有效期须为 1～30 分钟"
        maxRunMinutes !in 1..180 -> "一次性任务时限须为 1～180 分钟"
        postRunAction == PostRunAction.dismissAndReturn && cleanupPackage.isBlank() -> "划卡模式须选择目标应用"
        else -> null
    }
    companion object {
        fun ids(value: String) = value.split(Regex("[,，\\s]+")).filter { it.isNotBlank() }.toSet()
    }
}

class RemoteSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("feishu_remote_settings", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun load(): RemoteSettings = runCatching { json.decodeFromString<RemoteSettings>(prefs.getString("settings", "{}")!!) }
        .getOrElse { RemoteSettings() }
    fun save(settings: RemoteSettings) {
        require(settings.validationError() == null) { settings.validationError().orEmpty() }
        check(prefs.edit().putString("settings", json.encodeToString(RemoteSettings.serializer(), settings)).commit()) { "远程设置保存失败" }
    }
}
