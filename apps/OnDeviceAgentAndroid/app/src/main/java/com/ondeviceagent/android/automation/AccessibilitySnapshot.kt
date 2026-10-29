package com.ondeviceagent.android.automation

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * Compact UI snapshot from the accessibility tree — critical when screenshots are black
 * (FLAG_SECURE / password pages).
 */
object AccessibilitySnapshot {
    fun captureJson(service: AgentAccessibilityService, maxNodes: Int = 90): String {
        val root = service.rootInActiveWindow ?: return """{"ui":[],"package":"","note":"no_root"}"""
        val (w, h) = ScreenGeometry.size(service)
        val nodes = JSONArray()
        val pkg = root.packageName?.toString().orEmpty()
        collect(root, nodes, maxNodes, w, h)
        return JSONObject()
            .put("package", pkg)
            .put("width_px", w)
            .put("height_px", h)
            .put("ui", nodes)
            .put(
                "hint",
                "If screenshot is black (secure/password page), use ui[].text with Tap text=... instead of guessing coordinates.",
            )
            .toString()
    }

    private fun collect(
        node: AccessibilityNodeInfo,
        out: JSONArray,
        max: Int,
        screenW: Int,
        screenH: Int,
    ) {
        if (out.length() >= max) return
        if (!node.isVisibleToUser) return
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val id = node.viewIdResourceName.orEmpty()
        val useful = text.isNotEmpty() || desc.isNotEmpty() || node.isClickable || node.isEditable
        if (useful && node.isEnabled) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.intersect(0, 0, screenW, screenH) && !bounds.isEmpty) {
                val obj = JSONObject()
                if (text.isNotEmpty()) obj.put("text", text.take(48))
                if (desc.isNotEmpty()) obj.put("desc", desc.take(48))
                if (id.isNotEmpty()) obj.put("id", id)
                if (node.isClickable) obj.put("clickable", true)
                if (node.isEditable) obj.put("editable", true)
                if (node.isFocused) obj.put("focused", true)
                obj.put("bounds", JSONArray().put(bounds.left * 1000 / screenW)
                    .put(bounds.top * 1000 / screenH).put(bounds.right * 1000 / screenW)
                    .put(bounds.bottom * 1000 / screenH))
                obj.put("element", JSONArray().put((bounds.centerX() * 1000 / screenW)).put(bounds.centerY() * 1000 / screenH))
                out.put(obj)
            }
        }
        for (i in 0 until node.childCount) {
            if (out.length() >= max) return
            val child = node.getChild(i) ?: continue
            collect(child, out, max, screenW, screenH)
        }
    }

    fun findClickableByText(root: AccessibilityNodeInfo, needle: String): AccessibilityNodeInfo? {
        val candidates = visibleNodes(root).filter { node ->
            node.text?.isNotBlank() == true || node.contentDescription?.isNotBlank() == true
        }
        // 同一按钮的父子节点可能都带文案，按实际点击目标去重。
        val matching = candidates.filter { node ->
            listOf(node.text, node.contentDescription).any { it?.contains(needle, true) == true }
        }.groupBy { targetKey(preferClickable(it)) }.values.map { group ->
            group.first() to group.flatMap { listOf(it.text?.toString().orEmpty(), it.contentDescription?.toString().orEmpty()) }
        }
        return TargetMatcher.uniqueMatch(matching, needle) { it.second }?.first?.let { preferClickable(it) }
    }

    fun findClickableById(root: AccessibilityNodeInfo, resourceId: String): AccessibilityNodeInfo? =
        visibleNodes(root).filter { it.viewIdResourceName == resourceId }
            .map { preferClickable(it) }.distinctBy { targetKey(it) }.singleOrNull()

    private fun targetKey(node: AccessibilityNodeInfo): String {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return "${node.windowId}:${node.viewIdResourceName}:$bounds"
    }

    private fun preferClickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var cur: AccessibilityNodeInfo? = node
        repeat(6) {
            val current = cur ?: return node
            if (current.isClickable && current.isEnabled && current.isVisibleToUser) return current
            cur = current.parent
        }
        return node
    }

    private fun visibleNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val found = mutableListOf<AccessibilityNodeInfo>()
        val windowBounds = Rect().also { root.getBoundsInScreen(it) }
        fun visit(node: AccessibilityNodeInfo) {
            if (!node.isVisibleToUser) return
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (node.isEnabled && !bounds.isEmpty && Rect.intersects(bounds, windowBounds)) found += node
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it) }
        }
        visit(root)
        return found
    }
}
