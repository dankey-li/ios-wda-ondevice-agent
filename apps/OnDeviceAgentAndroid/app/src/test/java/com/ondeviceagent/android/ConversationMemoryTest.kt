package com.ondeviceagent.android

import com.ondeviceagent.android.automation.AgentActionResult
import com.ondeviceagent.android.llm.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ConversationMemoryTest {
    @Test fun `历史有界且最近动作和结果成对保留`() {
        val memory = ConversationMemory()
        repeat(10) { memory.record("tap", buildJsonObject { put("text", "目标$it") }, AgentActionResult(true)) }
        val history = memory.snapshot()
        assertEquals(8, history.size)
        assertEquals(listOf("assistant", "user", "assistant", "user", "assistant", "user", "assistant", "user"), history.map { it.role })
        assertFalse(history.joinToString().contains("目标0")); assertTrue(history.joinToString().contains("目标9"))
    }
    @Test fun `输入值不复制到会话历史`() {
        val memory = ConversationMemory()
        memory.record("type", buildJsonObject { put("text", "secret-value") }, AgentActionResult(true))
        assertFalse(memory.snapshot().joinToString().contains("secret-value"))
        assertTrue(memory.snapshot().first().content.contains("输入内容省略"))
    }
    @Test fun `失败原因作为下一步反馈保留`() {
        val memory = ConversationMemory()
        memory.record("tap", buildJsonObject {}, AgentActionResult(false, "找不到唯一目标"))
        assertTrue(memory.snapshot().last().content.contains("找不到唯一目标"))
    }
}
