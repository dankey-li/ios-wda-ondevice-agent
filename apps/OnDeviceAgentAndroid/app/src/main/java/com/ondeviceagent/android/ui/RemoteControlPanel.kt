package com.ondeviceagent.android.ui

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ondeviceagent.android.OnDeviceAgentApp
import com.ondeviceagent.android.manager.*
import com.ondeviceagent.android.remote.RemoteCommands
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RemoteControlPanel(onSaveCredentials: suspend () -> Unit) {
    val context = LocalContext.current
    val control = remember { OnDeviceAgentApp.get().remoteControl }
    val connection by control.connectionState.collectAsState()
    var settings by remember { mutableStateOf(control.settings()) }
    var ttl by remember { mutableStateOf(settings.commandTtlMinutes.toString()) }
    var limit by remember { mutableStateOf(settings.maxRunMinutes.toString()) }
    var message by remember { mutableStateOf("") }
    var choosing by remember { mutableStateOf(false) }
    var apps by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var appsLoaded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(choosing) {
        if (choosing && !appsLoaded) {
            apps = withContext(Dispatchers.IO) { runCatching {
                @Suppress("DEPRECATION")
                context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                    .filter { it.activityInfo.packageName != context.packageName }
                    .map { it.loadLabel(context.packageManager).toString() to it.activityInfo.packageName }.distinctBy { it.second }.sortedBy { it.first }
            }.getOrDefault(emptyList()) }
            appsLoaded = true
        }
    }
    if (choosing) AlertDialog(onDismissRequest = { choosing = false }, title = { Text("选择一次性任务结束后划掉的 App") },
        text = { Column {
            if (!appsLoaded) Text("读取应用中…") else if (apps.isEmpty()) Text("未读取到可启动应用，请检查手机权限")
            LazyColumn(Modifier.height(300.dp)) { items(apps) { (label, pkg) ->
                TextButton(onClick = { settings = settings.copy(cleanupPackage = pkg); choosing = false }) { Text("$label\n$pkg") }
            } }
        } }, confirmButton = { TextButton(onClick = { choosing = false }) { Text("关闭") } })
    Text("飞书远程控制", style = MaterialTheme.typography.titleMedium)
    Text("连接状态：$connection", style = MaterialTheme.typography.bodySmall)
    Row {
        Text("允许飞书指令控制本机", Modifier.weight(1f))
        Switch(settings.enabled, { settings = settings.copy(enabled = it) })
    }
    Text("需要自建应用机器人、接收消息事件和长连接。复用上方 App ID／App Secret；群 Webhook 不能接收指令。", style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(settings.allowedUsers, { settings = settings.copy(allowedUsers = it) }, label = { Text("允许的用户 open_id（ou_ 开头，逗号分隔）") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(settings.allowedChats, { settings = settings.copy(allowedChats = it) }, label = { Text("允许的群 chat_id（oc_ 开头，留空仅单聊）") }, modifier = Modifier.fillMaxWidth())
    Text("仅白名单用户可控制；群内还需 @当前机器人。普通聊天不执行。手机忙碌不排队，过期消息不补执行。", style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(ttl, { ttl = it }, label = { Text("指令有效期（1～30 分钟）") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
    Text("/执行 一次性任务的默认设置", style = MaterialTheme.typography.titleSmall)
    Text("只执行一次、不保存模板、不创建定时计划、不自动重试。已有任务使用自己的设置。", style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(limit, { limit = it }, label = { Text("最长执行（1～180 分钟）") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(settings.completionText, { settings = settings.copy(completionText = it.take(80)) }, label = { Text("成功判据（可选）") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(settings.guardedActionTexts, { settings = settings.copy(guardedActionTexts = it.take(200)) }, label = { Text("防重复按钮完整文案（逗号分隔）") }, modifier = Modifier.fillMaxWidth())
    Text("一次性任务结束后")
    PostRunAction.entries.forEach { option -> FilterChip(settings.postRunAction == option,
        { settings = settings.copy(postRunAction = option) }, label = { Text(option.title) }) }
    if (settings.postRunAction != PostRunAction.keep) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PostRunWhen.entries.forEach { option -> FilterChip(settings.postRunWhen == option,
                { settings = settings.copy(postRunWhen = option) }, label = { Text(option.title) }) }
        }
    }
    if (settings.postRunAction == PostRunAction.dismissAndReturn) {
        OutlinedButton(onClick = { choosing = true }) { Text(apps.firstOrNull { it.second == settings.cleanupPackage }?.first ?: settings.cleanupPackage.ifBlank { "选择目标 App" }) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DismissDirection.entries.forEach { option -> FilterChip(settings.dismissDirection == option,
                { settings = settings.copy(dismissDirection = option) }, label = { Text(option.title) }) }
        }
        Text("仅处理可安全识别的指定卡片；划掉不等于强制停止进程。", style = MaterialTheme.typography.bodySmall)
    }
    Button(onClick = { scope.launch {
        val candidate = settings.copy(commandTtlMinutes = ttl.toIntOrNull() ?: 0, maxRunMinutes = limit.toIntOrNull() ?: 0)
        val error = candidate.validationError()
        if (error != null) message = error else {
            try { onSaveCredentials(); control.save(candidate); message = "远程设置已保存" }
            catch (_: Exception) { message = "保存失败，请检查配置后重试" }
        }
    } }, modifier = Modifier.fillMaxWidth()) { Text("保存远程设置并重新连接") }
    if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
    Text(RemoteCommands.help, style = MaterialTheme.typography.bodySmall)
}
