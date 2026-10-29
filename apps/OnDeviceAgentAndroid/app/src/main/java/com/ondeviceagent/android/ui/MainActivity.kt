package com.ondeviceagent.android.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import com.ondeviceagent.android.OnDeviceAgentApp
import com.ondeviceagent.android.runtime.AgentForegroundService
import com.ondeviceagent.android.runtime.RunCompletionNotifier
import com.ondeviceagent.android.schedule.ScheduleLaunchNotifier
import com.ondeviceagent.android.schedule.ScreenWakeHelper
import com.ondeviceagent.android.screenshot.ScreenshotSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private data class DeepLink(val openHistory: Boolean = false, val runId: String? = null)

    private val deepLink = MutableStateFlow(DeepLink())
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val screenshotPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val data = result.data!!
                val code = result.resultCode
                Thread {
                    kotlinx.coroutines.runBlocking {
                        ScreenshotSession.bind(applicationContext, code, data)
                        OnDeviceAgentApp.get().runtime.refreshUi()
                    }
                }.start()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        maybeRequestNotificationPermission()
        AgentForegroundService.start(this)
        RunCompletionNotifier.ensureChannel(this)
        ScheduleLaunchNotifier.ensureChannel(this)
        deepLink.value = deepLinkFrom(intent)
        if (intent?.getBooleanExtra(ScreenWakeHelper.EXTRA_WAKE_FROM_SCHEDULE, false) == true) {
            ScreenWakeHelper.applyTurnScreenOn(this)
        }

        setContent {
            val app = OnDeviceAgentApp.get()
            val state by app.runtime.uiState.collectAsState()
            val link by deepLink.collectAsState()
            var tab by remember { mutableIntStateOf(if (link.openHistory) 2 else 0) }
            var pendingRunId by remember { mutableStateOf(link.runId) }
            val result = state.lastRunResult
            val showResultDialog = result != null && !result.dismissed

            androidx.compose.runtime.LaunchedEffect(link) {
                if (link.openHistory) {
                    tab = 2
                    pendingRunId = link.runId
                }
            }

            androidx.compose.material3.MaterialTheme {
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = tab == 0,
                                onClick = { tab = 0 },
                                icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                label = { Text("配置") },
                            )
                            NavigationBarItem(
                                selected = tab == 1,
                                onClick = { tab = 1 },
                                icon = {
                                    BadgedBox(
                                        badge = {
                                            if (showResultDialog) {
                                                Badge(
                                                    containerColor = if (result!!.success) {
                                                        Color(0xFF2E7D32)
                                                    } else {
                                                        Color(0xFFC62828)
                                                    },
                                                )
                                            }
                                        },
                                    ) {
                                        Icon(Icons.Default.List, contentDescription = null)
                                    }
                                },
                                label = { Text("任务") },
                            )
                            NavigationBarItem(
                                selected = tab == 2,
                                onClick = { tab = 2 },
                                icon = { Icon(Icons.Default.History, contentDescription = null) },
                                label = { Text("历史") },
                            )
                        }
                    },
                ) { padding ->
                    Box(Modifier.padding(padding)) {
                        when (tab) {
                            0 -> ConfigScreen(
                                runtime = app.runtime,
                                onRequestScreenshotPermission = {
                                    screenshotPermissionLauncher.launch(
                                        ScreenshotSession.createPermissionIntent(this@MainActivity),
                                    )
                                },
                                onOpenAccessibilitySettings = {
                                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                },
                            )
                            1 -> TasksScreen(runtime = app.runtime, managerStore = app.managerStore)
                            else -> HistoryScreen(
                                runtime = app.runtime,
                                initialRunId = pendingRunId,
                                onConsumedInitialRunId = { pendingRunId = null },
                            )
                        }
                    }
                }

                if (showResultDialog && result != null) {
                    AlertDialog(
                        onDismissRequest = { app.runtime.dismissLastRunResult() },
                        title = {
                            Text(if (result.success) "任务已完成" else "任务未完成")
                        },
                        text = {
                            Text(
                                buildString {
                                    if (result.task.isNotBlank()) {
                                        append(result.task.trim()).append("\n\n")
                                    }
                                    append(result.message.ifBlank { result.stopReason })
                                },
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    app.runtime.dismissLastRunResult()
                                    tab = 2
                                    pendingRunId = result.runId
                                },
                            ) { Text("查看详情") }
                        },
                        dismissButton = {
                            TextButton(onClick = { app.runtime.dismissLastRunResult() }) {
                                Text("知道了")
                            }
                        },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        activityScope.launch {
            val app = OnDeviceAgentApp.get()
            app.managerStore.syncAlarms()
            app.scheduledRunCoordinator.catchUpOverdue()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLink.value = deepLinkFrom(intent)
        if (intent.getBooleanExtra(ScreenWakeHelper.EXTRA_WAKE_FROM_SCHEDULE, false)) {
            ScreenWakeHelper.applyTurnScreenOn(this)
        }
    }

    private fun deepLinkFrom(intent: Intent?): DeepLink {
        if (intent == null) return DeepLink()
        return DeepLink(
            openHistory = intent.getBooleanExtra(RunCompletionNotifier.EXTRA_OPEN_HISTORY, false),
            runId = intent.getStringExtra(RunCompletionNotifier.EXTRA_RUN_ID),
        )
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
