package com.ondeviceagent.android.automation

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import com.ondeviceagent.android.OnDeviceAgentApp
import com.ondeviceagent.android.screenshot.ScreenshotSession
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * Cross-app UI executor via Accessibility gestures / node actions.
 * Also provides default screenshots via [takeScreenshot] (API 30+).
 */
class AgentAccessibilityService : AccessibilityService() {
    @Volatile
    private var executor: ActionExecutor? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        executor = ActionExecutor(this)
        OnDeviceAgentApp.get().runtime.accessibilityEnabledHint(true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        executor = null
        runCatching { OnDeviceAgentApp.get().runtime.accessibilityEnabledHint(false) }
        super.onDestroy()
    }

    suspend fun perform(action: ParsedAction): AgentActionResult {
        val exec = executor
            ?: return AgentActionResult(
                ok = false,
                error = "AccessibilityService not connected. Enable it in system settings.",
                errorKind = "a11y_disabled",
                name = action.name,
            )
        return exec.perform(action)
    }

    companion object {
        @Volatile
        var instance: AgentAccessibilityService? = null
            private set

        private val screenshotExecutor = Executors.newSingleThreadExecutor()

        fun isConnected(): Boolean = instance != null

        suspend fun performOrError(action: ParsedAction): AgentActionResult {
            val service = instance
                ?: return AgentActionResult(
                    ok = false,
                    error = "AccessibilityService not connected. Enable it in system settings.",
                    errorKind = "a11y_disabled",
                    name = action.name,
                )
            return service.perform(action)
        }

        @RequiresApi(Build.VERSION_CODES.R)
        suspend fun takeScreenshotJpegBase64(halfRes: Boolean, quality: Int): String? {
            val service = instance ?: return null
            return suspendCancellableCoroutine { cont ->
                service.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    screenshotExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            try {
                                val buffer = screenshot.hardwareBuffer
                                val wrapped = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                                buffer.close()
                                if (wrapped == null) {
                                    if (cont.isActive) cont.resume(null)
                                    return
                                }
                                val software = wrapped.copy(Bitmap.Config.ARGB_8888, false)
                                wrapped.recycle()
                                if (software == null) {
                                    if (cont.isActive) cont.resume(null)
                                    return
                                }
                                val encoded = ScreenshotSession.encodeJpegBase64(software, halfRes, quality)
                                if (cont.isActive) cont.resume(encoded)
                            } catch (_: Throwable) {
                                if (cont.isActive) cont.resume(null)
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            if (cont.isActive) cont.resume(null)
                        }
                    },
                )
            }
        }
    }
}
