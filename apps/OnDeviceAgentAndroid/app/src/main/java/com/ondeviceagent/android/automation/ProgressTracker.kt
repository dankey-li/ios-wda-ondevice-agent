package com.ondeviceagent.android.automation

/** 识别系统接收了手势、但同一目标和页面连续不变的情况。 */
class ProgressTracker(private val limit: Int = 3) {
    private var previous = ""
    private var repeats = 0
    fun stalled(frame: String, action: String, target: String): Boolean {
        if (action !in setOf("tap", "double_tap", "long_press", "swipe", "back")) return false
        val key = "$frame|$action|$target"
        repeats = if (key == previous) repeats + 1 else 1
        previous = key
        return repeats > limit
    }
}
