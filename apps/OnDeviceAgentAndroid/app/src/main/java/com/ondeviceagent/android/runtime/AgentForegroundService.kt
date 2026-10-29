package com.ondeviceagent.android.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ondeviceagent.android.OnDeviceAgentApp
import com.ondeviceagent.android.R
import com.ondeviceagent.android.ui.MainActivity

class AgentForegroundService : Service() {
    private var httpServer: AgentHttpServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        val runtime = OnDeviceAgentApp.get().runtime
        val server = AgentHttpServer(runtime)
        try {
            server.start(NanoHTTPD_SOCKET_READ_TIMEOUT, false)
            httpServer = server
        } catch (failure: Exception) {
            // 本地 HTTP 端口异常不应终止定时执行所需的前台服务。
            android.util.Log.e("AgentService", "HTTP 服务未启动，保留前台执行服务", failure)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SCHEDULED_TASK -> {
                val taskId = intent.getStringExtra(com.ondeviceagent.android.schedule.TaskAlarmScheduler.EXTRA_TASK_ID)
                if (!taskId.isNullOrBlank()) {
                    // FGS is allowed to start activities more reliably than a plain broadcast.
                    runCatching {
                        startActivity(com.ondeviceagent.android.schedule.ScheduleWakeActivity.intent(this, taskId))
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        httpServer?.stop()
        httpServer = null
        super.onDestroy()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "OnDevice Agent",
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.fg_notification_title))
            .setContentText(getString(R.string.fg_notification_text))
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.ondeviceagent.android.STOP_AGENT_SERVICE"
        const val ACTION_SCHEDULED_TASK = "com.ondeviceagent.android.SCHEDULED_TASK"
        private const val CHANNEL_ID = "ondevice_agent"
        private const val NOTIFICATION_ID = 8100
        private const val NanoHTTPD_SOCKET_READ_TIMEOUT = 30_000

        fun start(context: Context) {
            val intent = Intent(context, AgentForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun startForScheduledTask(context: Context, taskId: String) {
            val intent = Intent(context, AgentForegroundService::class.java).apply {
                action = ACTION_SCHEDULED_TASK
                putExtra(com.ondeviceagent.android.schedule.TaskAlarmScheduler.EXTRA_TASK_ID, taskId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, AgentForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
            context.stopService(Intent(context, AgentForegroundService::class.java))
        }
    }
}
