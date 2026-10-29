package com.ondeviceagent.android.schedule

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.ondeviceagent.android.R
import com.ondeviceagent.android.manager.FailureRetryPolicy
import com.ondeviceagent.android.manager.ScheduleCalculator
import com.ondeviceagent.android.manager.ScheduleKind
import com.ondeviceagent.android.manager.TaskTemplate

class TaskAlarmScheduler(context: Context) {
    private val appContext = context.applicationContext
    private val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun rescheduleAll(tasks: List<TaskTemplate>) {
        tasks.forEach { cancel(it.id) }
        tasks.forEach { schedule(it) }
    }

    fun schedule(task: TaskTemplate) {
        cancel(task.id)
        val regularAt = if (task.isScheduleActive) ScheduleCalculator.nextTriggerEpochMs(task.schedule) else null
        val triggerAt = FailureRetryPolicy.nextAlarm(task, regularAt) ?: return
        val firePi = firePendingIntent(task.id)
        val showPi = wakeActivityPendingIntent(task.id)

        val scheduled = runCatching {
            alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, showPi), firePi)
            true
        }.onFailure {
            Log.e(TAG, "setAlarmClock failed task=${task.id}: ${it.message}")
        }.getOrDefault(false)

        if (!scheduled) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, firePi)
                } else {
                    @Suppress("DEPRECATION")
                    alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, firePi)
                }
            }.onFailure {
                Log.e(TAG, "setExact fallback failed task=${task.id}: ${it.message}")
            }
        }
        Log.i(TAG, "scheduled task=${task.id} at=$triggerAt exact=${canScheduleExactAlarms()}")
    }

    fun cancel(taskId: String) {
        runCatching { alarmManager.cancel(firePendingIntent(taskId)) }
    }

    fun canScheduleExactAlarms(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }
    }

    fun canUseFullScreenIntent(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val nm = appContext.getSystemService(NotificationManager::class.java) ?: return false
            nm.canUseFullScreenIntent()
        } else {
            true
        }
    }

    fun exactAlarmSettingsIntent(): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = Uri.parse("package:${appContext.packageName}")
            }
        } else {
            null
        }
    }

    fun fullScreenIntentSettings(): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                data = Uri.parse("package:${appContext.packageName}")
            }
        } else {
            null
        }
    }

    private fun firePendingIntent(taskId: String): PendingIntent {
        val intent = Intent(appContext, ScheduleAlarmReceiver::class.java).apply {
            action = ScheduleAlarmReceiver.ACTION_FIRE
            putExtra(EXTRA_TASK_ID, taskId)
        }
        return PendingIntent.getBroadcast(
            appContext,
            requestCode(taskId, 1),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun wakeActivityPendingIntent(taskId: String): PendingIntent {
        return PendingIntent.getActivity(
            appContext,
            requestCode(taskId, 2),
            ScheduleWakeActivity.intent(appContext, taskId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun requestCode(taskId: String, salt: Int): Int = taskId.hashCode() * 31 + salt

    companion object {
        const val EXTRA_TASK_ID = "task_id"
        private const val TAG = "TaskAlarmScheduler"
    }
}

object ScheduleLaunchNotifier {
    private const val CHANNEL_ID = "ondevice_agent_schedule_launch"
    private const val NOTIFICATION_ID = 8102

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "计划启动",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "到点亮屏并启动任务"
            setBypassDnd(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    fun postFullScreenWake(context: Context, taskId: String, title: String) {
        ensureChannel(context)
        val app = context.applicationContext
        val fullScreen = PendingIntent.getActivity(
            app,
            taskId.hashCode(),
            ScheduleWakeActivity.intent(app, taskId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("计划任务到点")
            .setContentText(title.ifBlank { "正在亮屏启动…" })
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(fullScreen)
            .setFullScreenIntent(fullScreen, true)
            .setTimeoutAfter(60_000)
            .build()
        runCatching {
            NotificationManagerCompat.from(app).notify(NOTIFICATION_ID, notification)
        }.onFailure {
            Log.e("ScheduleLaunchNotifier", "notify failed: ${it.message}")
        }
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    const val EXTRA_AUTO_START_TASK = "auto_start_task"
}

val TaskTemplate.isScheduleActive: Boolean
    get() = schedule.kind != ScheduleKind.none && !isSchedulePaused
