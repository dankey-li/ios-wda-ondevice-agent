package com.ondeviceagent.android.runtime

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.ondeviceagent.android.automation.AgentAccessibilityService
import com.ondeviceagent.android.automation.RecentCardMatcher
import com.ondeviceagent.android.automation.ScreenGeometry
import com.ondeviceagent.android.manager.DismissDirection
import com.ondeviceagent.android.manager.PostRunAction
import com.ondeviceagent.android.manager.TaskTemplate
import com.ondeviceagent.android.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.coroutines.resume

/** 本地确定性收尾，不调用模型；调用方保持执行互斥，防止抢占下一任务。 */
class PostRunController(private val context: Context) {
    suspend fun execute(task: TaskTemplate, allowed: () -> Boolean): String {
        if (!allowed()) return "任务已修改或删除，跳过收尾"
        val cleanup = if (task.postRunAction == PostRunAction.dismissAndReturn) {
            try {
                withTimeoutOrNull(4500) { withContext(Dispatchers.Main) { dismissTarget(task, allowed) } }
                    ?: "划卡超时，未确认移除"
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                "划卡未完成：${error.message.orEmpty()}"
            }
        } else ""
        if (!allowed()) return listOf(cleanup, "任务已修改或删除，跳过返回").filter { it.isNotBlank() }.joinToString("；")
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
        return listOf(cleanup, returned).filter { it.isNotBlank() }.joinToString("；")
    }

    private suspend fun dismissTarget(task: TaskTemplate, allowed: () -> Boolean): String {
        val service = AgentAccessibilityService.instance ?: return "未启用无障碍，跳过划卡"
        val target = task.cleanupPackage
        if (target.isBlank() || target == context.packageName || target == "com.android.systemui") return "目标应用无效，跳过划卡"
        val pm = context.packageManager
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        @Suppress("DEPRECATION")
        val homePackages = pm.queryIntentActivities(homeIntent, 0).map { it.activityInfo.packageName }.toSet()
        if (target in homePackages) return "不能清理系统桌面，跳过划卡"
        val foreground = service.rootInActiveWindow?.packageName?.toString()
        if (foreground != target) return "前台已非指定目标 App，跳过划卡"
        @Suppress("DEPRECATION")
        val app = pm.getApplicationInfo(target, 0)
        val label = pm.getApplicationLabel(app).toString()
        // 同名应用无法仅凭最近任务标签区分，拒绝猜测。
        @Suppress("DEPRECATION")
        val launchers = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val sameLabel = launchers.filter { it.loadLabel(pm).toString() == label }.map { it.activityInfo.packageName }.toSet()
        if (sameLabel != setOf(target)) return "应用名称无法唯一对应目标，跳过划卡"
        if (!allowed()) return "任务已修改或删除，跳过划卡"
        if (!service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)) return "系统未打开最近任务，跳过划卡"
        delay(600)
        val root = service.rootInActiveWindow ?: return "最近任务节点不可用，跳过划卡"
        val launcher = root.packageName?.toString().orEmpty()
        if (launcher !in homePackages && launcher != "com.android.systemui") return "无法确认系统最近任务界面，跳过划卡"
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        val records = mutableListOf<RecentCardMatcher.Node>()
        fun visit(node: AccessibilityNodeInfo, parent: Int, depth: Int) {
            if (nodes.size >= 600 || depth > 24) return
            val index = nodes.size
            nodes += node
            records += RecentCardMatcher.Node(parent, node.viewIdResourceName.orEmpty(), node.text?.toString().orEmpty(), node.contentDescription?.toString().orEmpty(), node.isVisibleToUser)
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it, index, depth + 1) }
        }
        visit(root, -1, 0)
        val index = RecentCardMatcher.find(records, label) ?: return "未找到唯一且受支持的目标卡片，跳过划卡"
        val card = nodes[index]
        val bounds = Rect().also { card.getBoundsInScreen(it) }
        val (width, height) = ScreenGeometry.size(service)
        // 拒绝根容器、卡片局部可见和过小的节点，避免在其他卡片上划动。
        if (bounds.left < 0 || bounds.top < 0 || bounds.right > width || bounds.bottom > height ||
            bounds.width() < width * 0.2 || bounds.height() < height * 0.2 || bounds.height() > height * 0.9) {
            return "目标卡片边界无法安全确认，跳过划卡"
        }
        if (!allowed() || AgentAccessibilityService.instance !== service || service.rootInActiveWindow?.packageName?.toString() != launcher) return "界面已变化，跳过划卡"
        if (card.actionList.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS.id }) {
            if (!card.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS.id)) return "系统拒绝移除卡片（可能已锁定）"
            return "已派发目标卡片移除操作，实际结果待核对"
        }
        val x = bounds.exactCenterX()
        val y = bounds.exactCenterY()
        val endX = when (task.dismissDirection) { DismissDirection.left -> 1f; DismissDirection.right -> width - 2f; else -> x }
        val endY = if (task.dismissDirection == DismissDirection.up) 1f else y
        val path = Path().apply { moveTo(x, y); lineTo(endX, endY) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 350)).build()
        val dispatched = suspendCancellableCoroutine<Boolean> { cont ->
            val accepted = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(true) }
                override fun onCancelled(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(false) }
            }, null)
            if (!accepted && cont.isActive) cont.resume(false)
        }
        if (!dispatched) return "目标卡片划动未完成"
        delay(350)
        return "已向${task.dismissDirection.title.removePrefix("向").removeSuffix("划")}划动目标卡片，是否移除待核对（锁定或方向不符可能无效）"
    }
}
