package com.ondeviceagent.android.manager

/** 结束执行与业务成功分开；成功判据来自当前页面，不能用 Home 动作代替。 */
object CompletionPolicy {
    data class Decision(val success: Boolean, val reason: String, val message: String)

    fun evaluate(status: String, evidence: String, message: String, requiredText: String, screenText: String): Decision = when {
        status == "blocked" -> Decision(false, "blocked", message.ifBlank { "需要人工协助" })
        status == "failed" -> Decision(false, "model_failed", message.ifBlank { "模型报告任务未完成" })
        status != "success" -> Decision(false, "blocked", "Finish 未提供明确状态，请核对任务结果")
        evidence.isBlank() -> Decision(false, "blocked", "未提供成功证据，请核对任务结果")
        requiredText.isNotBlank() && !screenText.contains(requiredText, ignoreCase = true) ->
            Decision(false, "blocked", "当前页面未出现成功判据「$requiredText」，请人工核对")
        else -> Decision(true, "finish", message.ifBlank { evidence })
    }
}
