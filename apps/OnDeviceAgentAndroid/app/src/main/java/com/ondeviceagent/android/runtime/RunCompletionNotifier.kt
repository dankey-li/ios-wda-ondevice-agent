package com.ondeviceagent.android.runtime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.ondeviceagent.android.R
import com.ondeviceagent.android.ui.MainActivity

object RunCompletionNotifier {
    private const val CHANNEL_ID = "ondevice_agent_results"
    private const val NOTIFICATION_ID = 8101

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "任务结果",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Agent 任务完成或失败时的提醒"
        }
        manager.createNotificationChannel(channel)
    }

    fun notifyResult(
        context: Context,
        success: Boolean,
        task: String,
        message: String,
        runId: String,
    ) {
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context,
            1,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_OPEN_HISTORY, true)
                putExtra(EXTRA_RUN_ID, runId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = if (success) "任务已完成" else "任务未完成"
        val body = buildString {
            val shortTask = task.trim().take(40)
            if (shortTask.isNotEmpty()) append(shortTask).append('\n')
            append(message.ifBlank { if (success) "成功结束" else "已结束" })
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }

    const val EXTRA_OPEN_HISTORY = "open_history"
    const val EXTRA_RUN_ID = "run_id"
}
