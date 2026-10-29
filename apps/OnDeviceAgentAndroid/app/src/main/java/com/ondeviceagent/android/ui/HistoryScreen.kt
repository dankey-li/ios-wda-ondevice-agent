package com.ondeviceagent.android.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.ondeviceagent.android.model.AgentTraceSummary
import com.ondeviceagent.android.runtime.AgentRuntime
import com.ondeviceagent.android.trace.TraceDetail
import com.ondeviceagent.android.trace.TraceTurnRecord

private enum class HistoryFilter {
    ALL, SUCCESS, FAILED;

    val title: String
        get() = when (this) {
            ALL -> "全部"
            SUCCESS -> "成功"
            FAILED -> "失败"
        }
}

@Composable
fun HistoryScreen(
    runtime: AgentRuntime,
    initialRunId: String? = null,
    onConsumedInitialRunId: () -> Unit = {},
) {
    val state by runtime.uiState.collectAsState()
    var refreshTick by remember { mutableStateOf(0) }
    var traces by remember { mutableStateOf(emptyList<AgentTraceSummary>()) }
    var clearHistory by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(state.running, state.lastRunResult, refreshTick) {
        traces = withContext(Dispatchers.IO) { runtime.traces() }
    }
    var filter by remember { mutableStateOf(HistoryFilter.ALL) }
    var search by remember { mutableStateOf("") }
    var selectedRunId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(initialRunId) {
        if (!initialRunId.isNullOrBlank()) {
            selectedRunId = initialRunId
            onConsumedInitialRunId()
        }
    }

    val selected = selectedRunId
    if (selected != null) {
        TraceDetailScreen(
            runtime = runtime,
            runId = selected,
            onBack = { selectedRunId = null },
        )
        return
    }

    val filtered = remember(traces, filter, search) {
        traces.filter { item ->
            val matchesFilter = when (filter) {
                HistoryFilter.ALL -> true
                HistoryFilter.SUCCESS -> item.finalStatus.success
                HistoryFilter.FAILED -> !item.finalStatus.success
            }
            if (!matchesFilter) return@filter false
            val keyword = search.trim()
            if (keyword.isEmpty()) return@filter true
            val haystack = listOf(
                item.runId,
                item.parentRunId,
                item.task,
                item.finalStatus.message,
                item.startedAt,
            ).joinToString(" ").lowercase()
            haystack.contains(keyword.lowercase())
        }
    }

    if (clearHistory) {
        AlertDialog(onDismissRequest = { clearHistory = false }, title = { Text("清理已结束的历史") },
            text = { Text("将删除已结束运行的截图和模型输出，正在运行的记录会保留。") },
            confirmButton = { TextButton(onClick = { scope.launch {
                withContext(Dispatchers.IO) { runtime.clearCompletedHistory() }
                clearHistory = false; refreshTick++
            } }) { Text("确认清理") } }, dismissButton = { TextButton(onClick = { clearHistory = false }) { Text("取消") } })
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("任务历史", style = MaterialTheme.typography.headlineSmall)
            Row {
                TextButton(onClick = { refreshTick += 1 }) { Text("刷新") }
                TextButton(onClick = { clearHistory = true }) { Text("清理") }
            }
        }

        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("搜索任务 / runId / 结果") },
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HistoryFilter.entries.forEach { item ->
                FilterChip(
                    selected = filter == item,
                    onClick = { filter = item },
                    label = { Text(item.title) },
                )
            }
        }

        if (filtered.isEmpty()) {
            Text("没有匹配到历史记录", style = MaterialTheme.typography.titleMedium)
            Text(
                "可以换个筛选条件，或者先去任务中心启动一次任务。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f),
            ) {
                items(filtered, key = { it.runId }) { item ->
                    HistoryListItem(item = item, onClick = { selectedRunId = item.runId })
                }
            }
        }
    }
}

@Composable
private fun HistoryListItem(item: AgentTraceSummary, onClick: () -> Unit) {
    val success = item.finalStatus.success
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    if (success) "成功" else "失败",
                    color = if (success) Color(0xFF2E7D32) else Color(0xFFC62828),
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    item.startedAt.take(19).replace('T', ' '),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                item.task.ifBlank { item.runId },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
            )
            if (item.finalStatus.message.isNotBlank()) {
                Text(
                    item.finalStatus.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                )
            }
            Text(
                "步骤 ${item.counts.turns} · 截图 ${item.counts.images} · ${item.finalStatus.stopReason}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TraceDetailScreen(
    runtime: AgentRuntime,
    runId: String,
    onBack: () -> Unit,
) {
    var detail by remember(runId) { mutableStateOf<TraceDetail?>(null) }
    var error by remember(runId) { mutableStateOf("") }

    LaunchedEffect(runId) {
        detail = runtime.loadTraceDetail(runId)
        error = if (detail == null) "找不到该运行记录" else ""
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onBack) { Text("返回") }
            Text("运行详情", style = MaterialTheme.typography.headlineSmall)
        }

        val d = detail
        if (d == null) {
            Text(if (error.isNotBlank()) error else "加载中…")
            return
        }

        val success = d.manifest.final_status.success
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("任务", style = MaterialTheme.typography.titleMedium)
                        Text(d.manifest.task.ifBlank { runId })
                    }
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            when {
                                success -> "执行成功"
                                d.manifest.final_status.stop_reason == "blocked" -> "需要人工核对"
                                d.manifest.final_status.stop_reason == "interrupted" -> "运行中断"
                                d.manifest.final_status.stop_reason == "stopped" -> "已停止"
                                else -> "执行失败"
                            },
                            color = if (success) Color(0xFF2E7D32) else Color(0xFFC62828),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (d.manifest.final_status.message.isNotBlank()) {
                            Text(d.manifest.final_status.message)
                        }
                        Text("开始：${d.manifest.started_at}")
                        Text("结束：${d.manifest.finished_at.ifBlank { "-" }}")
                        if (d.manifest.attempt > 0) Text("第 ${d.manifest.attempt} 次重试 · 原运行：${d.manifest.parent_run_id.ifBlank { "启动失败后的补跑" }}")
                        Text("步骤数：${d.turns.size} · 原因：${d.manifest.final_status.stop_reason}")
                    }
                }
            }
            item {
                Text("步骤", style = MaterialTheme.typography.titleMedium)
            }
            items(d.turns, key = { "${it.step}-${it.ts}-${it.action}" }) { turn ->
                TraceTurnCard(runtime = runtime, runId = runId, turn = turn)
            }
        }
    }
}

@Composable
private fun TraceTurnCard(
    runtime: AgentRuntime,
    runId: String,
    turn: TraceTurnRecord,
) {
    var bitmap by remember(runId, turn.step) { mutableStateOf<Bitmap?>(null) }
    var diagnostics by remember(runId, turn.step) { mutableStateOf<List<String>?>(null) }
    var expanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(runId, turn.step) {
        bitmap = withContext(Dispatchers.IO) { runtime.loadTraceStepBitmap(runId, turn.step) }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Step ${turn.step}", style = MaterialTheme.typography.titleSmall)
                Text(
                    turn.action.ifBlank { "-" },
                    color = if (turn.ok) MaterialTheme.colorScheme.primary else Color(0xFFC62828),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            val bmp = bitmap
            if (bmp != null) {
                Box {
                    Image(bitmap = bmp.asImageBitmap(), contentDescription = "step ${turn.step}",
                        modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp), contentScale = ContentScale.Fit)
                    if (turn.element.size >= 2) Canvas(Modifier.matchParentSize()) {
                        val scale = minOf(size.width / bmp.width, size.height / bmp.height)
                        val width = bmp.width * scale; val height = bmp.height * scale
                        val center = Offset((size.width - width) / 2 + (turn.element[0] / 1000 * width).toFloat(),
                            (size.height - height) / 2 + (turn.element[1] / 1000 * height).toFloat())
                        drawCircle(Color.Red, 8.dp.toPx(), center, style = Stroke(2.dp.toPx()))
                    }
                }
                if (turn.element.isNotEmpty()) Text("红圈：本步执行目标中心 · ${turn.duration_ms} ms", style = MaterialTheme.typography.bodySmall)

            }
            if (turn.error.isNotBlank()) {
                Text(turn.error, color = Color(0xFFC62828), style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = {
                expanded = !expanded
                if (expanded && diagnostics == null) scope.launch {
                    diagnostics = withContext(Dispatchers.IO) { runtime.loadModelOutputs(runId, turn.step) }
                }
            }) { Text(if (expanded) "收起模型输出" else "展开完整输出与格式补救") }
            if (expanded) diagnostics?.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (turn.model.isNotBlank()) {
                Text(
                    if (expanded) turn.model else turn.model.take(500),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
