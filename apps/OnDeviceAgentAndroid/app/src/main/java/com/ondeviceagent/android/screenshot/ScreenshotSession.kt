package com.ondeviceagent.android.screenshot

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.DisplayMetrics
import android.view.WindowManager
import com.ondeviceagent.android.automation.AgentAccessibilityService
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream

/**
 * Screenshots for the agent loop.
 *
 * Primary (Android 11+): AccessibilityService.takeScreenshot — no MediaProjection dialog.
 * Fallback: user-granted MediaProjection session.
 */
object ScreenshotSession {
    private val mutex = Mutex()
    @Volatile private var projection: MediaProjection? = null
    @Volatile private var reader: ImageReader? = null
    @Volatile private var display: VirtualDisplay? = null
    @Volatile private var width: Int = 0
    @Volatile private var height: Int = 0
    @Volatile private var density: Int = 0
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    /** Ready when a11y screenshot is available, or MediaProjection is bound. */
    fun isReady(): Boolean {
        if (projection != null) return true
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            AgentAccessibilityService.isConnected()
    }

    fun sourceLabel(): String {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && AgentAccessibilityService.isConnected() ->
                "无障碍截图"
            projection != null -> "MediaProjection"
            else -> "未就绪"
        }
    }

    fun createPermissionIntent(context: Context): Intent {
        val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        return mgr.createScreenCaptureIntent()
    }

    suspend fun bind(context: Context, resultCode: Int, data: Intent): Boolean = mutex.withLock {
        releaseLocked()
        val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mgr.getMediaProjection(resultCode, data) ?: return false
        projection = proj
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        width = metrics.widthPixels
        height = metrics.heightPixels
        density = metrics.densityDpi
        val ht = HandlerThread("screenshot").also { it.start(); thread = it }
        handler = Handler(ht.looper)
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        display = proj.createVirtualDisplay(
            "ondevice-agent",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface,
            null,
            handler,
        )
        true
    }

    suspend fun captureJpegBase64(halfRes: Boolean = true, quality: Int = 70): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val viaA11y = AgentAccessibilityService.takeScreenshotJpegBase64(halfRes, quality)
            if (viaA11y != null) return viaA11y
        }
        return mutex.withLock { captureMediaProjectionLocked(halfRes, quality) }
    }

    private fun captureMediaProjectionLocked(halfRes: Boolean, quality: Int): String? {
        val imageReader = reader ?: return null
        var image = imageReader.acquireLatestImage()
        if (image == null) {
            Thread.sleep(120)
            image = imageReader.acquireLatestImage()
        }
        image ?: return null
        return image.use { img ->
            val plane = img.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width
            var bitmap = Bitmap.createBitmap(
                width + rowPadding / pixelStride,
                height,
                Bitmap.Config.ARGB_8888,
            )
            bitmap.copyPixelsFromBuffer(buffer)
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height)
            encodeJpegBase64(bitmap, halfRes, quality)
        }
    }

    fun encodeJpegBase64(source: Bitmap, halfRes: Boolean, quality: Int): String {
        var bitmap = source
        var recycleSource = false
        if (halfRes && bitmap.width > 1 && bitmap.height > 1) {
            val scaled = Bitmap.createScaledBitmap(bitmap, bitmap.width / 2, bitmap.height / 2, true)
            if (scaled !== bitmap) {
                recycleSource = true
                bitmap = scaled
            }
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(40, 95), out)
        if (recycleSource) bitmap.recycle()
        source.recycle()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    suspend fun release() = mutex.withLock { releaseLocked() }

    private fun releaseLocked() {
        display?.release()
        display = null
        reader?.close()
        reader = null
        projection?.stop()
        projection = null
        thread?.quitSafely()
        thread = null
        handler = null
    }
}
