package com.ondeviceagent.android.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.ondeviceagent.android.OnDeviceAgentApp
import com.ondeviceagent.android.runtime.AgentForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Alarm callback: within AlarmManager's temporary allowlist, immediately start
 * [ScheduleWakeActivity] (turn screen on + dismiss keyguard) and FGS.
 */
class ScheduleAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_FIRE) return
        val taskId = intent.getStringExtra(TaskAlarmScheduler.EXTRA_TASK_ID) ?: return
        Log.i(TAG, "alarm fired taskId=$taskId")
        val agentApp = OnDeviceAgentApp.get()
        if (agentApp.runtime.uiState.value.running) {
            // 所有到期事件先排队处理，不创建亮屏页干扰正在运行的应用。
            val pending = goAsync()
            scope.launch {
                try { agentApp.scheduledRunCoordinator.handleAlarm(taskId) }
                finally { pending.finish() }
            }
            return
        }

        val app = context.applicationContext
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        val wakeLock = pm.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
            "ondeviceagent:alarm_fire",
        )
        wakeLock.setReferenceCounted(false)
        runCatching { wakeLock.acquire(60_000L) }

        val title = runCatching {
            OnDeviceAgentApp.get().managerStore.templates.value
                .firstOrNull { it.id == taskId }?.title
        }.getOrNull().orEmpty()

        ScreenWakeHelper.wakeScreen(app, holdMs = 30_000L)
        ScheduleLaunchNotifier.postFullScreenWake(app, taskId, title)
        AgentForegroundService.start(app)

        // Critical: start wake activity inside the alarm allowlist window (~10s).
        val started = runCatching {
            app.startActivity(ScheduleWakeActivity.intent(app, taskId))
            true
        }.onFailure {
            Log.e(TAG, "startActivity(ScheduleWakeActivity) failed: ${it.message}")
        }.getOrDefault(false)

        if (!started) {
            // Fallback: still try to run agent even if UI couldn't open.
            val pending = goAsync()
            scope.launch {
                try {
                    OnDeviceAgentApp.get().scheduledRunCoordinator.handleAlarm(taskId)
                } finally {
                    runCatching { if (wakeLock.isHeld) wakeLock.release() }
                    pending.finish()
                }
            }
        } else {
            // Activity will call handleAlarm; release wake lock shortly after.
            scope.launch {
                kotlinx.coroutines.delay(8_000)
                runCatching { if (wakeLock.isHeld) wakeLock.release() }
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "com.ondeviceagent.android.SCHEDULE_FIRE"
        private const val TAG = "ScheduleAlarmReceiver"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        val pending = goAsync()
        scope.launch {
            try {
                val app = OnDeviceAgentApp.get()
                app.alarmScheduler.rescheduleAll(app.managerStore.templates.value)
                app.scheduledRunCoordinator.catchUpOverdue()
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
