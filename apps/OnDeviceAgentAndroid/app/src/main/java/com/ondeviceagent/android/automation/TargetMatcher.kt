package com.ondeviceagent.android.automation

/** 先匹配完整文案；有歧义时返回空值，交给模型结合当前截图重新选择。 */
object TargetMatcher {
    fun <T> uniqueMatch(candidates: List<T>, query: String, labels: (T) -> List<String>): T? {
        val needle = query.trim()
        if (needle.isEmpty()) return null
        val exact = candidates.filter { item -> labels(item).any { it.trim().equals(needle, true) } }
        if (exact.isNotEmpty()) return exact.singleOrNull()
        return candidates.filter { item -> labels(item).any { it.contains(needle, true) } }.singleOrNull()
    }
}
