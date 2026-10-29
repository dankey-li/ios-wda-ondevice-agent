package com.ondeviceagent.android.manager

/** 完整文案匹配，避免把“进入打卡页面”等导航入口误当成提交。 */
object SubmissionPolicy {
    fun resolveLabel(query: String, visibleLabels: List<String>): String {
        val labels = visibleLabels.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        return com.ondeviceagent.android.automation.TargetMatcher.uniqueMatch(labels, query) { listOf(it) }.orEmpty()
    }

    fun protected(label: String, configuredLabels: String): Boolean = configuredLabels
        .split(',', '，').map { it.trim() }.filter { it.isNotBlank() }.any { label.trim() == it }
}
