package com.ondeviceagent.android.remote

/** 只允许展示已知错误类别和数字码，不透出请求、凭证、URL 或第三方原始报文。 */
object RemoteDiagnostic {
    fun safeError(error: Exception): String {
        val message = error.message.orEmpty()
        val known = Regex("(飞书 HTTP|飞书凭证错误：|获取机器人信息失败：|飞书消息发送失败：|飞书长连接引导失败：) ?(-?[0-9]{1,12})")
        known.matchEntire(message)?.let { return it.value }
        return when (message) {
            "机器人未启用" -> "未获取到机器人身份，请检查机器人能力与应用发布"
            "飞书网络请求失败" -> "网络请求失败"
            else -> "请求或协议处理失败，请核对网络和应用配置"
        }
    }
}
