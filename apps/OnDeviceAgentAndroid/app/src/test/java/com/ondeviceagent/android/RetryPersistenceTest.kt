package com.ondeviceagent.android

import android.app.AlarmManager
import android.app.Application
import com.ondeviceagent.android.trace.TraceRecorder
import com.ondeviceagent.android.manager.*
import com.ondeviceagent.android.manager.ManagerStore
import com.ondeviceagent.android.manager.TaskTemplate
import com.ondeviceagent.android.manager.TaskTemplateDraft
import com.ondeviceagent.android.schedule.TaskAlarmScheduler
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RetryPersistenceTest {
    private lateinit var app: Application
    private lateinit var store: ManagerStore
    private lateinit var alarms: AlarmManager

    @Before fun prepare() {
        app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("ondevice_agent_manager", 0).edit().clear().commit()
        alarms = app.getSystemService(AlarmManager::class.java)
        store = ManagerStore(app, TaskAlarmScheduler(app))
        store.upsert(TaskTemplate(id = "task-a", title = "测试", task = "打开测试页面"))
    }

    @Test fun `并发领取同一任务只有一个运行成功`() {
        val revision = store.templates.value.single().executionRevision
        val results = java.util.Collections.synchronizedList(mutableListOf<TaskTemplate?>())
        val start = java.util.concurrent.CountDownLatch(1)
        val workers = (1..4).map { i -> Thread { start.await(); results += store.claimRun("task-a", RunClaim(revision), "r$i") }.also { it.start() } }
        start.countDown(); workers.forEach { it.join() }
        assertEquals(1, results.count { it != null })
    }

    @Test fun `取消和修改后旧领取资格失效`() {
        val old = store.templates.value.single()
        store.cancelRetry(old.id)
        assertNull(store.claimRun(old.id, RunClaim(old.executionRevision), "old-run"))
        val current = store.templates.value.single()
        store.toggleSchedulePause(current.id)
        assertNull(store.claimRun(current.id, RunClaim(current.executionRevision), "paused-run"))
    }

    @Test fun `被编辑的单次计划不会被旧请求清除`() {
        val now = System.currentTimeMillis()
        store.upsert(store.templates.value.single().copy(schedule = TaskSchedule(ScheduleKind.once, now)))
        val original = store.templates.value.single()
        val signature = ScheduleCalculator.occurrenceSignature(original.schedule, now)!!
        store.upsert(original.copy(schedule = TaskSchedule(kind = ScheduleKind.weekly)))
        assertNull(store.claimRun(original.id, RunClaim(original.executionRevision, occurrence = signature), "old", now))
        assertEquals(ScheduleKind.weekly, store.templates.value.single().schedule.kind)
    }

    @Test fun `提交检查点不允许重复写入且手动确认才能解除`() {
        val task = store.templates.value.single()
        store.claimRun(task.id, RunClaim(task.executionRevision), "run")
        assertTrue(store.markSubmission(task.id, "run", "打卡"))
        assertFalse(store.markSubmission(task.id, "run", "打卡"))
        store.finishRun(task.id, "run", task.executionRevision, false, "parse_error", "失败", "进度")
        assertEquals("打卡", ManagerStore(app).templates.value.single().submissionPending)
        store.clearSubmission(task.id)
        assertEquals("", store.templates.value.single().submissionPending)
    }

    @Test fun `编辑或取消旧运行后不会安排新重试`() {
        val task = store.templates.value.single()
        store.claimRun(task.id, RunClaim(task.executionRevision), "run")
        store.cancelRetry(task.id)
        store.finishRun(task.id, "run", task.executionRevision, false, "parse_error", "错误", "")
        assertNull(store.templates.value.single().pendingRetryAtEpochMs)
    }

    @Test fun `重建后旧运行被对账为中断且不盲目重放`() {
        val task = store.templates.value.single()
        store.claimRun(task.id, RunClaim(task.executionRevision), "run")
        val reloaded = ManagerStore(app, TaskAlarmScheduler(app))
        reloaded.recoverInterrupted()
        val recovered = reloaded.templates.value.single()
        assertEquals(TaskRunState.interrupted, recovered.lastRun.state)
        assertNull(recovered.activeRunId); assertNull(recovered.pendingRetryAtEpochMs)
    }

    @Test fun `旧的待重试记录迁移时补上原始截止时间`() {
        val old = """[{"id":"legacy","title":"旧任务","task":"测试","pendingRetryAtEpochMs":300000,"retryAttempt":1}]"""
        app.getSharedPreferences("ondevice_agent_manager", 0).edit().putString("templates", old).commit()
        val restored = ManagerStore(app).templates.value.single()
        assertEquals(1800000L, restored.retryDeadlineAtEpochMs)
    }

    @Test fun `损坏数据保留副本并恢复上次有效备份`() {
        val prefs = app.getSharedPreferences("ondevice_agent_manager", 0)
        prefs.edit().putString("templates", "bad-json").commit()
        val restored = ManagerStore(app)
        assertTrue(restored.storageWarning.isNotBlank())
        assertEquals("bad-json", prefs.getString("templates_corrupt_backup", ""))
        assertEquals("task-a", restored.templates.value.single().id)
    }

    @Test fun `多个配置重试完整运行周期不会越过次数上限`() {
        store.upsert(store.templates.value.single().copy(maxRetries = 3, retryIntervalMinutes = 1, retryWindowMinutes = 30))
        var owner = store.templates.value.single()
        store.claimRun(owner.id, RunClaim(owner.executionRevision), "r0", 0)
        store.finishRun(owner.id, "r0", owner.executionRevision, false, "parse_error", "失败", "进度")
        for (attempt in 1..3) {
            owner = store.templates.value.single()
            assertEquals(attempt, owner.retryAttempt)
            val at = owner.pendingRetryAtEpochMs!!
            assertNotNull(store.claimRun(owner.id, RunClaim(owner.executionRevision, retryAt = at), "r$attempt", at))
            store.finishRun(owner.id, "r$attempt", owner.executionRevision, false, "parse_error", "失败", "进度")
        }
        assertNull(store.templates.value.single().pendingRetryAtEpochMs)
    }

    @Test fun `同任务计划与正在执行的手动运行合并避免重复补跑`() {
        val now = System.currentTimeMillis()
        store.upsert(store.templates.value.single().copy(schedule = TaskSchedule(ScheduleKind.once, now)))
        val owner = store.templates.value.single()
        store.claimRun(owner.id, RunClaim(owner.executionRevision), "manual", now)
        val occurrence = ScheduleCalculator.occurrenceSignature(owner.schedule, now)!!
        store.recordStartFailure(owner.id, RunClaim(owner.executionRevision, occurrence = occurrence), "runtime_busy", "已有执行")
        val current = store.templates.value.single()
        assertEquals("manual", current.activeRunId)
        assertEquals(TaskRunState.running, current.lastRun.state)
        assertEquals(occurrence, current.lastTriggeredSignature)
        assertEquals(ScheduleKind.none, current.schedule.kind)
        assertNull(current.pendingRetryAtEpochMs)
    }

    @Test fun `历史记录保留完整模型输出且换行引号可正确回读`() {
        val recorder = TraceRecorder(app)
        val raw = "错误输出\n\"工作台\"" + "x".repeat(5000)
        recorder.begin("test-trace", "测试历史")
        recorder.saveModelOutput(0, 0, raw, "length")
        recorder.appendTurn(0, raw, "parse_error", false, "首行\n第二行\"错误\"")
        recorder.finish(false, "格式错误", "parse_error")
        val detail = recorder.loadDetail("test-trace")!!
        assertEquals(raw, detail.turns.single().model)
        assertEquals("首行\n第二行\"错误\"", detail.turns.single().error)
        assertTrue(java.io.File(app.filesDir, "traces/test-trace/model-0-0.json").readText().contains("length"))
    }

    @Test fun `失败持久化并注册五分钟闹钟且重新创建后保留`() {
        val before = System.currentTimeMillis()
        store.queueFailureRetry("task-a", "打开测试页面", "run-1", "parse_error", "已经登录")
        val task = store.templates.value.single()
        assertTrue(task.pendingRetryAtEpochMs!! >= before + 300000)
        assertEquals(task.pendingRetryAtEpochMs, alarms.nextAlarmClock.triggerTime)
        val reloaded = ManagerStore(app, TaskAlarmScheduler(app))
        assertEquals(task, reloaded.templates.value.single())
    }

    @Test fun `取消重试同时移除闹钟且持久化`() {
        store.queueFailureRetry("task-a", "打开测试页面", "run-1", "model_error", "")
        assertNotNull(alarms.nextAlarmClock)
        store.cancelRetry("task-a")
        assertNull(alarms.nextAlarmClock)
        assertNull(ManagerStore(app).templates.value.single().pendingRetryAtEpochMs)
    }

    @Test fun `编辑后旧运行不能修改新版本的重试状态`() {
        store.queueFailureRetry("task-a", "打开测试页面", "run-1", "parse_error", "旧进度")
        val old = store.templates.value.single()
        store.upsert(TaskTemplateDraft.from(old).copy(task = "新任务").toTemplate(old))
        val edited = store.templates.value.single()
        assertTrue(edited.executionRevision > old.executionRevision)
        assertNull(edited.pendingRetryAtEpochMs)
        store.queueFailureRetry("task-a", "打开测试页面", "old-run", "parse_error", "旧进度")
        assertNull(store.templates.value.single().pendingRetryAtEpochMs)
    }

    @Test fun `暂停或删除任务会撤销重试闹钟`() {
        store.queueFailureRetry("task-a", "打开测试页面", "r", "parse_error", "")
        store.toggleSchedulePause("task-a")
        assertNull(alarms.nextAlarmClock)
        assertNull(store.templates.value.single().pendingRetryAtEpochMs)
        store.toggleSchedulePause("task-a")
        store.queueFailureRetry("task-a", "打开测试页面", "r2", "parse_error", "")
        assertNotNull(alarms.nextAlarmClock)
        store.delete("task-a")
        assertNull(alarms.nextAlarmClock)
        assertTrue(store.templates.value.isEmpty())
    }

    @Test fun `其他任务占用时延后且不增加重试额度`() {
        store.queueFailureRetry("task-a", "打开测试页面", "r", "parse_error", "已经登录")
        store.deferRetry("task-a")
        val deferred = store.templates.value.single()
        assertEquals(1, deferred.retryAttempt)
        assertEquals("已经登录", deferred.retryContext)
        assertEquals(deferred.pendingRetryAtEpochMs, alarms.nextAlarmClock.triggerTime)
    }

    @Test fun `消耗重试后再次失败不注册下一轮闹钟`() {
        store.queueFailureRetry("task-a", "打开测试页面", "r", "parse_error", "")
        store.prepareRun("task-a", true)
        assertNull(alarms.nextAlarmClock)
        store.queueFailureRetry("task-a", "打开测试页面", "r2", "parse_error", "")
        assertNull(store.templates.value.single().pendingRetryAtEpochMs)
        assertNull(alarms.nextAlarmClock)
    }
}
