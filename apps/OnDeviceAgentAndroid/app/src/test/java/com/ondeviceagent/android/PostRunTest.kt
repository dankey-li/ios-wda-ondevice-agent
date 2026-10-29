package com.ondeviceagent.android

import com.ondeviceagent.android.automation.RecentCardMatcher
import com.ondeviceagent.android.manager.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class PostRunTest {
    @Test fun `旧任务默认保持页面且不执行收尾`() {
        val task = Json.decodeFromString<TaskTemplate>("""{"id":"old","title":"旧任务","task":"测试"}""")
        assertEquals(PostRunAction.keep, task.postRunAction)
        assertFalse(PostRunPolicy.shouldRun(task, true, "finished", task.executionRevision))
    }
    @Test fun `收尾设置经过编辑和序列化后保留`() {
        val task = TaskTemplateDraft(title = "测试", postRunAction = PostRunAction.dismissAndReturn,
            postRunWhen = PostRunWhen.successOrFailure, cleanupPackage = "test.app", dismissDirection = DismissDirection.left).toTemplate()
        val restored = Json.decodeFromString<TaskTemplate>(Json { encodeDefaults = true }.encodeToString(TaskTemplate.serializer(), task))
        assertEquals(task, restored)
        assertEquals("test.app", TaskTemplateDraft.from(restored).cleanupPackage)
        assertEquals(DismissDirection.left, TaskTemplateDraft.from(restored).dismissDirection)
    }
    @Test fun `仅成功和失败后执行遵守用户条件`() {
        val task = TaskTemplate(title = "任务", task = "测试", postRunAction = PostRunAction.returnToAgent)
        assertTrue(PostRunPolicy.shouldRun(task, true, "finished", 0))
        assertFalse(PostRunPolicy.shouldRun(task, false, "parse_error", 0))
        assertTrue(PostRunPolicy.shouldRun(task.copy(postRunWhen = PostRunWhen.successOrFailure), false, "parse_error", 0))
    }
    @Test fun `停止取消中断修改删除均不触发收尾`() {
        val task = TaskTemplate(title = "任务", task = "测试", postRunAction = PostRunAction.dismissAndReturn,
            postRunWhen = PostRunWhen.successOrFailure)
        for (reason in listOf("stopped", "cancelled", "interrupted", "task_changed")) assertFalse(PostRunPolicy.shouldRun(task, false, reason, 0))
        assertFalse(PostRunPolicy.shouldRun(task, true, "finished", 1))
        assertFalse(PostRunPolicy.shouldRun(task, true, "finished", null))
    }
    private fun nodes() = listOf(
        RecentCardMatcher.Node(id = "launcher:id/overview"),
        RecentCardMatcher.Node(parent = 0, id = "launcher:id/task_view"),
        RecentCardMatcher.Node(parent = 1, id = "launcher:id/app_name", text = "目标应用"),
        RecentCardMatcher.Node(parent = 0, id = "launcher:id/task_view"),
        RecentCardMatcher.Node(parent = 3, id = "launcher:id/app_name", text = "其他应用"),
        RecentCardMatcher.Node(parent = 0, text = "清理全部"),
    )
    @Test fun `只定位目标卡片而不选择其他卡片或清理全部`() {
        assertEquals(1, RecentCardMatcher.find(nodes(), "目标应用"))
        assertNull(RecentCardMatcher.find(nodes(), "目标"))
        assertNull(RecentCardMatcher.find(nodes(), "清理全部"))
        assertNull(RecentCardMatcher.find(nodes(), ""))
    }
    @Test fun `同名的两张卡片拒绝选择`() {
        assertNull(RecentCardMatcher.find(nodes() + listOf(
            RecentCardMatcher.Node(parent = 0, id = "launcher:id/task_view"),
            RecentCardMatcher.Node(parent = 6, id = "launcher:id/app_name", text = "目标应用")), "目标应用"))
    }
    @Test fun `卡片内部业务文字不当作应用标题`() {
        assertNull(RecentCardMatcher.find(nodes().mapIndexed { i, n -> if (i == 2) n.copy(id = "launcher:id/snapshot_content") else n }, "目标应用"))
    }
    @Test fun `隐藏标题和未知卡片结构不会划动`() {
        assertNull(RecentCardMatcher.find(nodes().mapIndexed { i, n -> if (i == 2) n.copy(visible = false) else n }, "目标应用"))
        assertNull(RecentCardMatcher.find(nodes().map { it.copy(id = "") }, "目标应用"))
    }
}
