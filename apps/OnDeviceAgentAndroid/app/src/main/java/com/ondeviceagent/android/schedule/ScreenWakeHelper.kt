package com.ondeviceagent.android.schedule

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.view.WindowManager
import com.ondeviceagent.android.ui.MainActivity

object ScreenWakeHelper {
    @SuppressLint("WakelockTimeout")
    @Suppress("DEPRECATION")
    fun wakeScreen(context: Context, holdMs: Long = 20_000L) {
        val app = context.applicationContext
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        // SCREEN_* + ACQUIRE_CAUSES_WAKEUP is required to actually turn the display on.
        val wakeLock = pm.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
            "ondeviceagent:schedule_wake",
        )
        wakeLock.setReferenceCounted(false)
        runCatching { wakeLock.acquire(holdMs.coerceIn(3_000L, 60_000L)) }
    }

    const val EXTRA_WAKE_FROM_SCHEDULE = "wake_from_schedule"

    fun applyTurnScreenOn(activity: MainActivity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            activity.setTurnScreenOn(true)
            activity.setShowWhenLocked(true)
        } else {
            @Suppress("DEPRECATION")
            activity.window.addFlags(
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD,
            )
        }
        // Keep screen on briefly while agent starts.
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    fun launchMainForWake(context: Context, taskId: String? = null) {
        val app = context.applicationContext
        runCatching {
            app.startActivity(
                Intent(app, MainActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                    )
                    putExtra(EXTRA_WAKE_FROM_SCHEDULE, true)
                    if (!taskId.isNullOrBlank()) {
                        putExtra(TaskAlarmScheduler.EXTRA_TASK_ID, taskId)
                        putExtra(ScheduleLaunchNotifier.EXTRA_AUTO_START_TASK, true)
                    }
                },
            )
        }
    }
}
