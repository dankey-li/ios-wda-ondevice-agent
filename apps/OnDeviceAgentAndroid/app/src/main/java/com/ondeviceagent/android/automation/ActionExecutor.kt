package com.ondeviceagent.android.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlin.coroutines.resume
import kotlin.math.roundToLong

class ActionExecutor(
    private val service: AgentAccessibilityService,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    suspend fun perform(action: ParsedAction): AgentActionResult {
        return try {
            when (action.name) {
                "wait" -> wait(action.params)
                "note" -> AgentActionResult(ok = true, name = "note")
                "home" -> global(AccessibilityService.GLOBAL_ACTION_HOME, "home")
                "back" -> global(AccessibilityService.GLOBAL_ACTION_BACK, "back")
                "launch" -> launch(action.params)
                "tap" -> tap(action.params, clicks = 1, longPressMs = 0)
                "double_tap" -> tap(action.params, clicks = 2, longPressMs = 0)
                "long_press" -> {
                    val seconds = action.params.doubleParam("seconds", default = 3.0).coerceIn(0.3, 10.0)
                    tap(action.params, clicks = 1, longPressMs = (seconds * 1000).roundToLong())
                }
                "swipe" -> swipe(action.params)
                "type" -> type(action.params)
                else -> AgentActionResult(
                    ok = false,
                    error = "Unknown action: ${action.name}",
                    errorKind = "unknown_action",
                    name = action.name,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Exception) {
            AgentActionResult(
                ok = false,
                error = t.message ?: t::class.java.simpleName,
                errorKind = "exception",
                name = action.name,
            )
        }
    }

    private suspend fun wait(params: JsonObject): AgentActionResult {
        var seconds = params.doubleParam("seconds", "duration", default = 0.0)
        if (seconds <= 0.0) seconds = 1.0
        delay((seconds * 1000).roundToLong().coerceAtLeast(50L))
        return AgentActionResult(ok = true, name = "wait")
    }

    private fun global(actionId: Int, name: String): AgentActionResult {
        val ok = service.performGlobalAction(actionId)
        return if (ok) {
            AgentActionResult(ok = true, name = name)
        } else {
            AgentActionResult(ok = false, error = "global action failed: $name", errorKind = "action_failed", name = name)
        }
    }

    private fun launch(params: JsonObject): AgentActionResult {
        val appName = params.stringParam("app", "app_name")
        val packageName = params.stringParam("package", "package_name", "bundle_id", "bundleId", "bundleID")
        val resolved = AppLauncher.resolvePackage(service, appName, packageName)
            ?: return AgentActionResult(
                ok = false,
                error = "找不到应用「${appName.orEmpty()}」。请改用桌面准确名称，或 Home 后 Tap 图标；也可传 package=包名",
                errorKind = if (!appName.isNullOrBlank()) "launch_not_in_map" else "invalid_params",
                name = "launch",
            )
        val ok = AppLauncher.launch(service, resolved)
        return if (ok) {
            AgentActionResult(ok = true, name = "launch")
        } else {
            AgentActionResult(
                ok = false,
                error = "failed to launch package=$resolved",
                errorKind = "action_failed",
                name = "launch",
            )
        }
    }

    private suspend fun tap(params: JsonObject, clicks: Int, longPressMs: Long): AgentActionResult {
        val actionName = when {
            clicks > 1 -> "double_tap"
            longPressMs > 0 -> "long_press"
            else -> "tap"
        }
        val resourceId = params.stringParam("resource_id")
        if (!resourceId.isNullOrBlank()) {
            return tapByText(resourceId, clicks, actionName, longPressMs, byId = true)
        }
        val byText = params.stringParam("text", "label", "name", "button")
        if (!byText.isNullOrBlank()) {
            return tapByText(byText, clicks, actionName, longPressMs)
        }

        val point = params.point1000("element")
            ?: return AgentActionResult(
                ok = false,
                error = "Invalid tap. Use params.element=[x,y] or params.text=\"验证\"",
                errorKind = "invalid_params",
                name = actionName,
            )
        val (xPx, yPx) = toPixels(point.first, point.second)
        val strokeMs = if (longPressMs > 0) longPressMs else 50L
        var ok = true
        repeat(clicks) { index ->
            if (!dispatchClick(xPx, yPx, strokeMs)) {
                ok = false
                return@repeat
            }
            if (index < clicks - 1) delay(80)
        }
        return if (ok) {
            AgentActionResult(ok = true, name = actionName, resolvedElement = listOf(point.first, point.second))
        } else {
            AgentActionResult(ok = false, error = "gesture dispatch failed", errorKind = "action_failed", name = actionName)
        }
    }

    private suspend fun tapByText(
        label: String,
        clicks: Int,
        actionName: String,
        longPressMs: Long = 0,
        byId: Boolean = false,
    ): AgentActionResult =
        withContext(Dispatchers.Main) {
            val root = service.rootInActiveWindow
                ?: return@withContext AgentActionResult(
                    ok = false,
                    error = "no active window root",
                    errorKind = "action_failed",
                    name = actionName,
                )
            val node = (if (byId) AccessibilitySnapshot.findClickableById(root, label)
                else AccessibilitySnapshot.findClickableByText(root, label))
                ?: return@withContext AgentActionResult(
                    ok = false,
                    error = "找不到唯一、可见且可用的目标「$label」，请结合最新截图使用 resource_id 或控件中心坐标",
                    errorKind = "action_failed",
                    name = actionName,
                )
            var ok = true
            repeat(clicks) { index ->
                val clicked = node.performAction(
                    if (longPressMs > 0) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK,
                )
                if (!clicked) {
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    val (screenWidth, screenHeight) = ScreenGeometry.size(service)
                    if (!bounds.intersect(0, 0, screenWidth, screenHeight) || bounds.isEmpty) {
                        ok = false
                        return@repeat
                    }
                    if (!dispatchClick(bounds.centerX().toFloat(), bounds.centerY().toFloat(), longPressMs.takeIf { it > 0 } ?: 50L)) {
                        ok = false
                        return@repeat
                    }
                }
                if (index < clicks - 1) delay(80)
            }
            if (ok) {
                val bounds = Rect().also { node.getBoundsInScreen(it) }
                val (width, height) = ScreenGeometry.size(service)
                AgentActionResult(ok = true, name = actionName, resolvedElement = listOf(bounds.centerX() * 1000.0 / width, bounds.centerY() * 1000.0 / height))
            } else {
                AgentActionResult(
                    ok = false,
                    error = "click「$label」failed",
                    errorKind = "action_failed",
                    name = actionName,
                )
            }
        }

    private suspend fun swipe(params: JsonObject): AgentActionResult {
        val start = params.point1000("start")
        val end = params.point1000("end")
        if (start == null || end == null) {
            return AgentActionResult(
                ok = false,
                error = "Invalid swipe coordinates. Expected params.start/end=[x,y] in [0,1000]",
                errorKind = "invalid_params",
                name = "swipe",
            )
        }
        var durationMs = params.doubleParam("duration_ms", default = 0.0).toLong()
        if (durationMs <= 0L) {
            val seconds = params.doubleParam("seconds", default = 0.0)
            durationMs = if (seconds > 0) (seconds * 1000).roundToLong() else 300L
        }
        durationMs = durationMs.coerceIn(80L, 5000L)
        val holdMs = params.doubleParam("hold_ms", "press_seconds", default = 0.0).let {
            if (it > 0 && it <= 10) {
                // press_seconds is seconds; hold_ms is ms. Prefer hold_ms if provided via key name.
                if (params.containsKey("hold_ms")) it.toLong() else (it * 1000).roundToLong()
            } else {
                0L
            }
        }.coerceIn(0L, 3000L)

        val (sx, sy) = toPixels(start.first, start.second)
        val (ex, ey) = toPixels(end.first, end.second)
        val path = Path().apply {
            moveTo(sx, sy)
            if (holdMs > 0) {
                // Keep a tiny dwell by starting stroke earlier; Gesture API uses stroke duration.
            }
            lineTo(ex, ey)
        }
        val stroke = GestureDescription.StrokeDescription(path, holdMs, durationMs)
        val ok = dispatchGesture(GestureDescription.Builder().addStroke(stroke).build())
        return if (ok) {
            AgentActionResult(ok = true, name = "swipe")
        } else {
            AgentActionResult(ok = false, error = "swipe gesture failed", errorKind = "action_failed", name = "swipe")
        }
    }

    private suspend fun type(params: JsonObject): AgentActionResult = withContext(Dispatchers.Main) {
        if (params.stringParam("submit", "confirm", "button") != null) return@withContext AgentActionResult(
            false, "输入和提交必须分步执行，请先 Type，再单独 Tap 提交按钮", "invalid_params", "type",
        )
        val text = params.stringParam("text", "content", "message", "value", "password")
            ?: return@withContext AgentActionResult(
                ok = false,
                error = "type requires action.params.text (or content) as non-empty string",
                errorKind = "invalid_params",
                name = "type",
            )

        val root = service.rootInActiveWindow
            ?: return@withContext AgentActionResult(
                ok = false,
                error = "no active window root",
                errorKind = "action_failed",
                name = "type",
            )

        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?.takeIf { it.isEditable && it.isEnabled && it.isVisibleToUser }
            ?: return@withContext AgentActionResult(
                ok = false,
                error = "no focused/editable text field",
                errorKind = "action_failed",
                name = "type",
            )

        focused.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        // Prefer SET_TEXT; many password fields reject it — fall back to clipboard paste.
        val setArgs = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val typed = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setArgs) ||
            pasteViaClipboard(focused, text)
        if (!typed) {
            return@withContext AgentActionResult(
                ok = false,
                error = "无法写入输入框（SET_TEXT/粘贴均失败，密码框常被系统拦截）",
                errorKind = "action_failed",
                name = "type",
            )
        }
        // Hide IME so buttons like「验证」are not covered by the keyboard.
        hideSoftKeyboard()
        delay(250)
        return@withContext AgentActionResult(ok = true, name = "type")
    }

    private fun hideSoftKeyboard() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching {
                service.softKeyboardController.showMode =
                    AccessibilityService.SHOW_MODE_HIDDEN
            }
        }
    }

    private fun pasteViaClipboard(node: AccessibilityNodeInfo, text: String): Boolean {
        return runCatching {
            val cm = service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("ondevice-agent", text))
            // Some fields need clear first.
            runCatching {
                val clear = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clear)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }.getOrDefault(false)
    }

    private fun findEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditable(child)
            if (found != null) return found
        }
        return null
    }

    private suspend fun dispatchClick(x: Float, y: Float, durationMs: Long): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1L))
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build())
    }

    private suspend fun dispatchGesture(gesture: GestureDescription): Boolean =
        suspendCancellableCoroutine { cont ->
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            }
            val posted = service.dispatchGesture(gesture, callback, mainHandler)
            if (!posted) {
                cont.resume(false)
            }
        }

    private fun toPixels(x1000: Double, y1000: Double): Pair<Float, Float> {
        val (width, height) = ScreenGeometry.size(service)
        return ScreenGeometry.toPixels(x1000, y1000, width, height)
    }
}
