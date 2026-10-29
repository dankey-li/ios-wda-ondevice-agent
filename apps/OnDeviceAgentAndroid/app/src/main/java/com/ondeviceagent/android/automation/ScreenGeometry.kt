package com.ondeviceagent.android.automation

import android.content.Context
import android.util.DisplayMetrics
import android.view.WindowManager

/** 与整屏截图使用同一物理屏幕尺寸，包含状态栏和导航栏。 */
object ScreenGeometry {
    fun size(context: Context): Pair<Int, Int> {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
            .defaultDisplay.getRealMetrics(metrics)
        return metrics.widthPixels.coerceAtLeast(1) to metrics.heightPixels.coerceAtLeast(1)
    }

    fun toPixels(x: Double, y: Double, width: Int, height: Int): Pair<Float, Float> =
        ((x / 1000.0) * width).toFloat().coerceIn(0f, (width - 1).toFloat()) to
            ((y / 1000.0) * height).toFloat().coerceIn(0f, (height - 1).toFloat())
}
