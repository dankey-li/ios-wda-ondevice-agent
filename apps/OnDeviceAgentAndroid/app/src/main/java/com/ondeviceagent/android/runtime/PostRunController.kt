package com.ondeviceagent.android.runtime

import android.content.Context
import android.content.Intent
import com.ondeviceagent.android.automation.AgentAccessibilityService
import com.ondeviceagent.android.manager.TaskTemplate
import com.ondeviceagent.android.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.UUID

/** 本地确定性收尾，不调用模型；调用方保持执行互斥，防止抢占下一任务。 */
class PostRunController(private val context: Context) {
    suspend fun execute(task: TaskTemplate, allowed: () -> Boolean): String {
        if (!allowed()) return "任务已修改或删除，跳过收尾"
        // 旧版划卡设置也仅返回任务页，不再打开最近任务或关闭目标应用。
        val returned = try {
            withContext(Dispatchers.Main) {
                if (!allowed()) return@withContext "任务已修改或删除，跳过返回"
                context.startActivity(Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra(MainActivity.EXTRA_OPEN_TASKS, true)
                    putExtra(MainActivity.EXTRA_RETURN_REQUEST, UUID.randomUUID().toString())
                })
                var visible = false
                repeat(10) {
                    if (!visible) {
                        delay(100)
                        visible = AgentAccessibilityService.instance?.rootInActiveWindow?.packageName?.toString() == context.packageName
                    }
                }
                if (visible) "已返回 One Agent 任务页" else "已请求返回任务页，未确认前台（可能受系统限制）"
            }
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            "返回任务页失败：${error.message.orEmpty()}"
        }
        return returned
    }

}
