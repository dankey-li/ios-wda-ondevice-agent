package com.ondeviceagent.android.schedule

import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import com.ondeviceagent.android.OnDeviceAgentApp
import com.ondeviceagent.android.runtime.AgentForegroundService
import com.ondeviceagent.android.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Lightweight activity whose only jobs are:
 * 1) turn the display on
 * 2) dismiss swipe/keyguard so we land in-app without manual swipe
 * 3) kick the scheduled agent run
 *
 * Must stay tiny — launched from AlarmManager's temporary allowlist window.
 */
class ScheduleWakeActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var readyTaskId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "onCreate taskId=${intent.getStringExtra(TaskAlarmScheduler.EXTRA_TASK_ID)}")
        turnScreenOnAndShowOverKeyguard()
        acquireWakeLock()

        val taskId = intent.getStringExtra(TaskAlarmScheduler.EXTRA_TASK_ID).orEmpty()
        AgentForegroundService.start(applicationContext)
        dismissKeyguardThen {
            readyTaskId = taskId
            // 先移除亮屏页面，执行开始后不再启动管理页。
            finish()
        }
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
        readyTaskId?.takeIf { it.isNotBlank() }?.let { taskId ->
            OnDeviceAgentApp.get().scheduleScope.launch {
                OnDeviceAgentApp.get().scheduledRunCoordinator.handleAlarm(taskId)
            }
        }
        scope.cancel()
    }

    private fun turnScreenOnAndShowOverKeyguard() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD,
        )
        ScreenWakeHelper.wakeScreen(applicationContext, holdMs = 30_000L)
    }

    private fun dismissKeyguardThen(onDone: () -> Unit) {
        val km = getSystemService(KeyguardManager::class.java)
        if (km == null || !km.isKeyguardLocked) {
            onDone()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            km.requestDismissKeyguard(
                this,
                object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() {
                        Log.i(TAG, "keyguard dismissed")
                        onDone()
                    }

                    override fun onDismissCancelled() {
                        Log.w(TAG, "keyguard dismiss cancelled — continue anyway")
                        onDone()
                    }

                    override fun onDismissError() {
                        Log.w(TAG, "keyguard dismiss error — continue anyway")
                        onDone()
                    }
                },
            )
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
            onDone()
        }
    }

    private fun isScreenInteractive(): Boolean {
        val pm = getSystemService(PowerManager::class.java) ?: return true
        return pm.isInteractive
    }

    @Suppress("DEPRECATION")
    private fun acquireWakeLock() {
        releaseWakeLock()
        val pm = getSystemService(PowerManager::class.java) ?: return
        val wl = pm.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
            "ondeviceagent:wake_activity",
        )
        wl.setReferenceCounted(false)
        runCatching { wl.acquire(45_000L) }
        wakeLock = wl
    }

    private fun releaseWakeLock() {
        runCatching {
            wakeLock?.takeIf { it.isHeld }?.release()
        }
        wakeLock = null
    }

    companion object {
        private const val TAG = "ScheduleWakeActivity"

        fun intent(context: android.content.Context, taskId: String): Intent {
            return Intent(context, ScheduleWakeActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_NO_USER_ACTION,
                )
                putExtra(TaskAlarmScheduler.EXTRA_TASK_ID, taskId)
                putExtra(ScreenWakeHelper.EXTRA_WAKE_FROM_SCHEDULE, true)
            }
        }
    }
}
