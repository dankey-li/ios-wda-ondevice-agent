package com.ondeviceagent.android.automation

/** 只按系统最近任务卡片结构和完整应用标签定位，不根据截图内容或模糊名称划卡。 */
object RecentCardMatcher {
    data class Node(
        val parent: Int = -1, val id: String = "", val text: String = "", val description: String = "",
        val visible: Boolean = true,
    )

    fun find(nodes: List<Node>, appLabel: String): Int? {
        if (appLabel.isBlank()) return null
        val cardIds = setOf("task_view", "taskview", "task")
        val titleIds = setOf("title", "task_title", "app_name", "application_name", "icon", "task_icon", "app_icon", "header_title")
        val candidates = mutableSetOf<Int>()
        nodes.forEachIndexed { index, node ->
            // 仅标题或图标的完整可见标签；不接受“关闭全部”或包含匹配。
            val resource = node.id.substringAfterLast('/').lowercase()
            if (resource !in titleIds && resource !in cardIds) return@forEachIndexed
            if (!node.visible || (node.text.trim() != appLabel && node.description.trim() != appLabel)) return@forEachIndexed
            var current = index
            repeat(24) {
                val item = nodes.getOrNull(current) ?: return@repeat
                if (item.visible && item.id.substringAfterLast('/').lowercase() in cardIds) {
                    candidates += current
                    return@forEachIndexed
                }
                current = item.parent
            }
        }
        return candidates.singleOrNull()
    }
}
