package com.ondeviceagent.android

import android.app.Application
import com.ondeviceagent.android.manager.ManagerStore
import com.ondeviceagent.android.runtime.AgentRuntime
import com.ondeviceagent.android.schedule.ScheduledRunCoordinator
import com.ondeviceagent.android.schedule.TaskAlarmScheduler
import com.ondeviceagent.android.trace.TraceRecorder

class OnDeviceAgentApp : Application() {
    val scheduleScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    lateinit var runtime: AgentRuntime
        private set
    lateinit var managerStore: ManagerStore
        private set
    lateinit var traceRecorder: TraceRecorder
        private set
    lateinit var alarmScheduler: TaskAlarmScheduler
        private set
    lateinit var remoteControl: com.ondeviceagent.android.remote.RemoteControl
        private set
    lateinit var scheduledRunCoordinator: ScheduledRunCoordinator
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        alarmScheduler = TaskAlarmScheduler(this)
        // ManagerStore created first without scheduler, then we attach via secondary ctor pattern:
        managerStore = ManagerStore(this, alarmScheduler)
        traceRecorder = TraceRecorder(this)
        traceRecorder.recoverIncompleteRuns()
        managerStore.recoverInterrupted()
        runtime = AgentRuntime(this, traceRecorder)
        scheduledRunCoordinator = ScheduledRunCoordinator(
            appContext = this,
            managerStore = managerStore,
            runtime = runtime,
            alarmScheduler = alarmScheduler,
        )
        remoteControl = com.ondeviceagent.android.remote.RemoteControl(this, runtime, managerStore)
        managerStore.syncAlarms()
    }

    companion object {
        @Volatile
        private var instance: OnDeviceAgentApp? = null

        fun get(): OnDeviceAgentApp =
            instance ?: error("OnDeviceAgentApp not initialized")
    }
}
