package com.ondeviceagent.android.manager

object PostRunPolicy {
    fun shouldRun(task: TaskTemplate, success: Boolean, reason: String, revision: Long?): Boolean =
        task.postRunAction != PostRunAction.keep && revision == task.executionRevision &&
            reason !in setOf("stopped", "cancelled", "interrupted", "task_changed") &&
            (success || task.postRunWhen == PostRunWhen.successOrFailure)
}
