package com.ondeviceagent.android.ui

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.ondeviceagent.android.manager.PostRunAction
import com.ondeviceagent.android.manager.PostRunWhen
import com.ondeviceagent.android.manager.DismissDirection
import com.ondeviceagent.android.manager.ManagerStore
import com.ondeviceagent.android.manager.ScheduleKind
import com.ondeviceagent.android.manager.TaskRunState
import com.ondeviceagent.android.manager.TaskSchedule
import com.ondeviceagent.android.manager.TaskTemplate
import com.ondeviceagent.android.manager.TaskTemplateDraft
import com.ondeviceagent.android.model.AgentConfigRequest
import com.ondeviceagent.android.runtime.AgentRuntime
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TasksScreen(
    runtime: AgentRuntime,
    managerStore: ManagerStore,
) {
    val state by runtime.uiState.collectAsState()
    val allTemplates by managerStore.templates.collectAsState()
    val templates = allTemplates.filterNot { it.isEphemeral }
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<TaskTemplate?>(null) }
    var creating by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var showScheduleSettings by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (managerStore.storageWarning.isNotBlank()) Text(managerStore.storageWarning, color = MaterialTheme.colorScheme.error)
        Text("任务中心", style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { creating = true }) { Text("新建任务") }
            OutlinedButton(
                enabled = state.running,
                onClick = {
                    scope.launch {
                        val st = runtime.stop()
                        status = st.lastMessage
                    }
                },
            ) { Text("停止当前") }
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
            modifier = Modifier.weight(1f),
        ) {
            item(key = "task_status") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        if (state.running) {
                            "Agent 运行中 · step=${state.currentStep} · ${state.lastMessage}"
                        } else {
                            val result = state.lastRunResult
                            when {
                                result != null && !result.dismissed && result.success ->
                                    "已完成 · ${result.message}"
                                result != null && !result.dismissed && !result.success ->
                                    "未完成 · ${result.message}"
                                else -> "空闲 · ${state.lastMessage}"
                            }
                        },
                        color = when {
                            state.running -> MaterialTheme.colorScheme.primary
                            state.lastRunResult?.let { !it.dismissed && it.success } == true ->
                                androidx.compose.ui.graphics.Color(0xFF2E7D32)
                            state.lastRunResult?.let { !it.dismissed && !it.success } == true ->
                                androidx.compose.ui.graphics.Color(0xFFC62828)
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                    )
                    if (status.isNotBlank()) Text(status)

                    Text(
                        "计划到点会自动亮屏并启动；灭屏也可，无需一直亮着。OPPO 请允许精确闹钟与忽略电池优化。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                }
            }
            item(key = "schedule_settings") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = { showScheduleSettings = !showScheduleSettings }) {
                            Text(if (showScheduleSettings) "收起定时任务设置" else "展开定时任务权限与设置")
                        }
                        if (showScheduleSettings) {
                            SchedulePermissionHints(managerStore = managerStore)
                        }
                    }
                }
            }
            items(templates, key = { it.id }) { item ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(item.title, style = MaterialTheme.typography.titleMedium)
                        Text(item.task.take(120), style = MaterialTheme.typography.bodySmall)
                        Text(item.schedule.displayText, style = MaterialTheme.typography.bodySmall)
                        Text(
                            managerStore.nextTriggerLabel(item),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            "上次：${item.lastRun.state} ${item.lastRun.message.take(40)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = when (item.lastRun.state) {
                                TaskRunState.success -> androidx.compose.ui.graphics.Color(0xFF2E7D32)
                                TaskRunState.failed, TaskRunState.stopped, TaskRunState.blocked, TaskRunState.interrupted ->
                                    androidx.compose.ui.graphics.Color(0xFFC62828)
                                TaskRunState.running -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        if (item.notifyOnSuccess) {
                            Text(
                                "完成通知：开（成功后发飞书）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        if (item.submissionPending.isNotBlank()) {
                            Text("提交保护：${item.submissionPending} 的结果尚待核对", color = MaterialTheme.colorScheme.error)
                            TextButton(enabled = !state.running, onClick = { confirmClear = item.id }) { Text("已核对结果，解除提交保护") }
                        }
                        Text(if (item.retryOnFailure && item.maxRetries > 0) "失败重试：最多 ${item.maxRetries} 次 · 间隔 ${item.retryIntervalMinutes} 分钟 · 有效期 ${item.retryWindowMinutes} 分钟" else "失败重试：关闭", style = MaterialTheme.typography.bodySmall)
                        if (item.postRunAction != PostRunAction.keep) {
                            Text("结束后：${item.postRunAction.title} · ${item.postRunWhen.title}", style = MaterialTheme.typography.bodySmall)
                        }
                        if (item.pendingRetryAtEpochMs != null) {
                            TextButton(onClick = { managerStore.cancelRetry(item.id) }) { Text("取消本次自动重试") }
                        }
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Button(
                                enabled = !state.running,
                                onClick = {
                                    managerStore.select(item.id)
                                    scope.launch {
                                        val payload = runtime.start(
                                            AgentConfigRequest(task = item.task), taskId = item.id,
                                            claim = com.ondeviceagent.android.manager.RunClaim(item.executionRevision),
                                        )
                                        if (payload.ok) {
                                            status = "已启动：${item.title}"
                                        } else {
                                            if (payload.status?.running != true) managerStore.recordStartFailure(
                                                item.id, com.ondeviceagent.android.manager.RunClaim(item.executionRevision), if (payload.errorKind == "blocked") "blocked" else "start_failed", payload.error,
                                            )
                                            status = payload.error
                                        }
                                    }
                                },
                            ) { Text("运行") }
                            FilterChip(
                                selected = item.notifyOnSuccess,
                                onClick = {
                                    managerStore.toggleNotifyOnSuccess(item.id)
                                    status = if (!item.notifyOnSuccess) {
                                        "已开启完成通知：${item.title}"
                                    } else {
                                        "已关闭完成通知：${item.title}"
                                    }
                                },
                                label = { Text(if (item.notifyOnSuccess) "通知·开" else "通知") },
                            )
                            OutlinedButton(onClick = {
                                managerStore.select(item.id)
                                editing = item
                            }) { Text("编辑") }
                            if (item.schedule.isEnabled) {
                                TextButton(onClick = {
                                    managerStore.toggleSchedulePause(item.id)
                                }) {
                                    Text(if (item.isSchedulePaused) "恢复计划" else "暂停计划", maxLines = 1)
                                }
                            }
                            TextButton(onClick = { managerStore.delete(item.id) }) { Text("删除") }
                        }
                    }
                }
            }
        }
    }

    confirmClear?.let { id ->
        AlertDialog(onDismissRequest = { confirmClear = null }, title = { Text("确认解除提交保护") },
            text = { Text("请先在目标应用核对上次操作是否成功。解除后，后续运行可能再次提交。") },
            confirmButton = { TextButton(onClick = { managerStore.clearSubmission(id); confirmClear = null }) { Text("已核对，解除保护") } },
            dismissButton = { TextButton(onClick = { confirmClear = null }) { Text("返回核对") } })
    }

    if (creating || editing != null) {
        val initial = editing?.let { TaskTemplateDraft.from(it) } ?: TaskTemplateDraft()
        TaskEditorDialog(
            initial = initial,
            onDismiss = { creating = false; editing = null },
            onSave = { draft ->
                managerStore.upsert(draft.toTemplate(editing))
                creating = false
                editing = null
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskEditorDialog(
    initial: TaskTemplateDraft,
    onDismiss: () -> Unit,
    onSave: (TaskTemplateDraft) -> Unit,
) {
    var title by remember { mutableStateOf(initial.title) }
    var task by remember { mutableStateOf(initial.task) }
    var kind by remember { mutableStateOf(initial.scheduleKind) }
    var hour by remember { mutableStateOf(initial.hour.toString()) }
    var minute by remember { mutableStateOf(initial.minute.toString()) }
    var weekdays by remember { mutableStateOf(initial.weekdays.toSet().ifEmpty { setOf(2) }) }
    var notifyOnSuccess by remember { mutableStateOf(initial.notifyOnSuccess) }
    var retryOnFailure by remember { mutableStateOf(initial.retryOnFailure) }
    var maxRetries by remember { mutableStateOf(initial.maxRetries.toString()) }
    var interval by remember { mutableStateOf(initial.retryIntervalMinutes.toString()) }
    var window by remember { mutableStateOf(initial.retryWindowMinutes.toString()) }
    var maxRun by remember { mutableStateOf(initial.maxRunMinutes.toString()) }
    var completionText by remember { mutableStateOf(initial.completionText) }
    var guardedTexts by remember { mutableStateOf(initial.guardedActionTexts) }
    var postAction by remember { mutableStateOf(initial.postRunAction) }
    var postWhen by remember { mutableStateOf(initial.postRunWhen) }
    var cleanupPackage by remember { mutableStateOf(initial.cleanupPackage) }
    var direction by remember { mutableStateOf(initial.dismissDirection) }
    var chooseApp by remember { mutableStateOf(false) }
    var apps by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var appLoadError by remember { mutableStateOf("") }
    var appsLoaded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        val result = withContext(Dispatchers.IO) { runCatching {
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            @Suppress("DEPRECATION")
            context.packageManager.queryIntentActivities(intent, 0)
                .filter { it.activityInfo.packageName != context.packageName }
                .map { it.loadLabel(context.packageManager).toString() to it.activityInfo.packageName }
                .distinctBy { it.second }.sortedBy { it.first }
        } }
        apps = result.getOrDefault(emptyList())
        appLoadError = if (result.isFailure) "应用列表读取失败，请重新打开编辑页" else ""
        appsLoaded = true
    }
    if (chooseApp) {
        AlertDialog(onDismissRequest = { chooseApp = false }, title = { Text("选择要划掉的目标 App") },
            text = { Column {
                if (!appsLoaded) Text("正在读取应用…")
                else if (appLoadError.isNotBlank()) Text(appLoadError)
                else if (apps.isEmpty()) Text("未找到可启动应用")
                LazyColumn(Modifier.height(320.dp)) { items(apps) { (label, pkg) ->
                    TextButton(onClick = { cleanupPackage = pkg; chooseApp = false }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth()) { Text(label); Text(pkg, style = MaterialTheme.typography.bodySmall) }
                    }
                } }
            } }, confirmButton = { TextButton(onClick = { chooseApp = false }) { Text("关闭") } })
    }
    val cleanupValid = postAction != PostRunAction.dismissAndReturn || cleanupPackage.isNotBlank()
    val retryValid = !retryOnFailure || (maxRetries.toIntOrNull() in 0..10 && interval.toIntOrNull() in 1..1440 &&
        window.toIntOrNull() in 1..10080 && (window.toIntOrNull() ?: 0) >= (interval.toIntOrNull() ?: 1))
    val runValid = maxRun.toIntOrNull() in 1..180

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.title.isBlank() && initial.task.isBlank()) "新建任务" else "编辑任务") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(title, { title = it }, label = { Text("标题") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(task, { task = it }, label = { Text("任务内容") }, modifier = Modifier.fillMaxWidth().height(140.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "成功后飞书通知（需先在配置页填写 Webhook）",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Switch(
                        checked = notifyOnSuccess,
                        onCheckedChange = { notifyOnSuccess = it },
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("失败后自动重试", modifier = Modifier.weight(1f))
                    Switch(checked = retryOnFailure, onCheckedChange = { retryOnFailure = it })
                }
                if (retryOnFailure) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(maxRetries, { maxRetries = it }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), label = { Text("重试次数 0～10") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(interval, { interval = it }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), label = { Text("间隔（分钟）") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                    OutlinedTextField(window, { window = it }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), label = { Text("首次失败后的有效期（分钟）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("重试次数不含首次执行；等待占用也不会延长有效期；计划暂停、手动停止或取消后不再自动重试。间隔 1～1440 分钟，有效期须不小于间隔。", style = MaterialTheme.typography.bodySmall)
                    if (!retryValid) Text("请检查重试次数、间隔与有效期", color = MaterialTheme.colorScheme.error)
                }
                OutlinedTextField(maxRun, { maxRun = it }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), label = { Text("单次最长执行（1～180 分钟）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(completionText, { completionText = it }, label = { Text("成功判据（可选，如：打卡成功）") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(guardedTexts, { guardedTexts = it }, label = { Text("防重复按钮完整文案（逗号分隔）") }, modifier = Modifier.fillMaxWidth())
                Text("按完整按钮文案匹配，例如“上班打卡”；结果未确认时阻止再次提交。重要任务建议填写成功判据。", style = MaterialTheme.typography.bodySmall)
                Text("任务结束后", style = MaterialTheme.typography.titleMedium)
                Column {
                    PostRunAction.entries.forEach { option ->
                        FilterChip(selected = postAction == option, onClick = { postAction = option }, label = { Text(option.title) })
                    }
                }
                if (postAction != PostRunAction.keep) {
                    Text("何时执行", style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PostRunWhen.entries.forEach { option ->
                            FilterChip(selected = postWhen == option, onClick = { postWhen = option }, label = { Text(option.title) })
                        }
                    }
                    Text("手动停止、取消或运行中修改任务时不执行。先保存结果；收尾失败单独记录，不触发业务重试。", style = MaterialTheme.typography.bodySmall)
                }
                if (postAction == PostRunAction.dismissAndReturn) {
                    OutlinedButton(onClick = { chooseApp = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(apps.firstOrNull { it.second == cleanupPackage }?.first ?: cleanupPackage.ifBlank { "请选择目标 App" })
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DismissDirection.entries.forEach { option ->
                            FilterChip(selected = direction == option, onClick = { direction = option }, label = { Text(option.title) })
                        }
                    }
                    Text("请按手机最近任务界面的移除方向选择。只处理能明确识别的目标卡片，不使用清理全部；系统不支持或卡片锁定时可能跳过。划掉不等于强制停止后台进程。", style = MaterialTheme.typography.bodySmall)
                    if (!cleanupValid) Text("请先选择目标 App", color = MaterialTheme.colorScheme.error)
                }
                Text("计划到点会自动亮屏并启动任务（无需一直亮屏）")
                FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ScheduleKind.entries.forEach { k ->
                        FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(k.title) })
                    }
                }
                if (kind == ScheduleKind.once) {
                    Text(
                        "单次：将在最近的下一个「时:分」触发一次",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (kind == ScheduleKind.daily || kind == ScheduleKind.weekly || kind == ScheduleKind.once) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(hour, { hour = it }, label = { Text("时") }, modifier = Modifier.weight(1f), singleLine = true)
                        OutlinedTextField(minute, { minute = it }, label = { Text("分") }, modifier = Modifier.weight(1f), singleLine = true)
                    }
                }
                if (kind == ScheduleKind.weekly) {
                    Text("重复星期（可多选）", style = MaterialTheme.typography.bodySmall)
                    FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TaskSchedule.weekdayOptions.forEach { option ->
                            val value = option.first
                            val label = option.second
                            FilterChip(
                                selected = value in weekdays,
                                onClick = {
                                    val next = weekdays.toMutableSet()
                                    if (value in next) {
                                        if (next.size > 1) next.remove(value)
                                    } else {
                                        next.add(value)
                                    }
                                    weekdays = next
                                },
                                label = { Text(label) },
                            )
                        }
                    }
                    Text(
                        "已选：${TaskSchedule.weekdayText(weekdays.toList())}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = (kind != ScheduleKind.weekly || weekdays.isNotEmpty()) && retryValid && runValid && cleanupValid,
                onClick = {
                    onSave(
                        TaskTemplateDraft(
                            title = title,
                            task = task,
                            scheduleKind = kind,
                            hour = hour.toIntOrNull() ?: 8,
                            minute = minute.toIntOrNull() ?: 0,
                            weekdays = weekdays.sorted(),
                            notifyOnSuccess = notifyOnSuccess,
                            retryOnFailure = retryOnFailure,
                            maxRetries = maxRetries.toIntOrNull() ?: 1,
                            retryIntervalMinutes = interval.toIntOrNull() ?: 5,
                            retryWindowMinutes = window.toIntOrNull() ?: 30,
                            maxRunMinutes = maxRun.toIntOrNull() ?: 15,
                            completionText = completionText,
                            guardedActionTexts = guardedTexts,
                            postRunAction = postAction, postRunWhen = postWhen,
                            cleanupPackage = cleanupPackage, dismissDirection = direction,
                        ),
                    )
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun SchedulePermissionHints(managerStore: ManagerStore) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = remember { com.ondeviceagent.android.OnDeviceAgentApp.get() }
    var canExact by remember { mutableStateOf(app.alarmScheduler.canScheduleExactAlarms()) }
    var canFullScreen by remember { mutableStateOf(app.alarmScheduler.canUseFullScreenIntent()) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!canExact) {
            Text(
                "当前未授予精确闹钟权限，到点可能延迟或不触发。请先开启。",
                color = androidx.compose.ui.graphics.Color(0xFFC62828),
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                onClick = {
                    app.alarmScheduler.exactAlarmSettingsIntent()?.let { context.startActivity(it) }
                    canExact = app.alarmScheduler.canScheduleExactAlarms()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("开启精确闹钟权限（必需）") }
        } else {
            Text(
                "精确闹钟：已允许",
                style = MaterialTheme.typography.bodySmall,
                color = androidx.compose.ui.graphics.Color(0xFF2E7D32),
            )
        }
        if (!canFullScreen) {
            Text(
                "全屏通知未允许：灭屏时系统会拒绝亮屏拉起（你遇到的「手动亮屏后才启动」就是这个）。",
                color = androidx.compose.ui.graphics.Color(0xFFC62828),
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                onClick = {
                    app.alarmScheduler.fullScreenIntentSettings()?.let { context.startActivity(it) }
                    canFullScreen = app.alarmScheduler.canUseFullScreenIntent()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("允许全屏通知 / 锁屏显示（灭屏亮屏必需）") }
        } else {
            Text(
                "全屏通知：已允许",
                style = MaterialTheme.typography.bodySmall,
                color = androidx.compose.ui.graphics.Color(0xFF2E7D32),
            )
        }
        OutlinedButton(
            onClick = {
                val intent = android.content.Intent().apply {
                    action = android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                    data = android.net.Uri.parse("package:${context.packageName}")
                }
                runCatching { context.startActivity(intent) }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("忽略电池优化（OPPO 强烈建议）") }
        TextButton(onClick = {
            managerStore.syncAlarms()
            canExact = app.alarmScheduler.canScheduleExactAlarms()
            canFullScreen = app.alarmScheduler.canUseFullScreenIntent()
        }) {
            Text("重新同步全部计划闹钟")
        }
    }
}
