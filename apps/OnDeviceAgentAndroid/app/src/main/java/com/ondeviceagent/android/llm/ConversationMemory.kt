package com.ondeviceagent.android.llm

import com.ondeviceagent.android.automation.AgentActionResult
import kotlinx.serialization.json.*

data class ModelMessage(val role: String, val content: String)

/** 最近四步的动作与结果进入会话，计划和检查点继续单独维护。 */
class ConversationMemory {
    private val messages = ArrayDeque<ModelMessage>()
    fun snapshot(): List<ModelMessage> = messages.toList()
    fun record(name: String, params: JsonObject, result: AgentActionResult) {
        val action = buildJsonObject {
            put("action", buildJsonObject {
                put("name", name)
                put("params", if (name == "type") buildJsonObject { put("text", "<输入内容省略>") } else params)
            })
        }
        messages.addLast(ModelMessage("assistant", action.toString()))
        messages.addLast(ModelMessage("user", "执行结果：${if (result.ok) "已派发，效果仍需依据当前页面核对" else result.error.take(500)}"))
        while (messages.size > 8) messages.removeFirst()
    }
}
