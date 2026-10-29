package com.ondeviceagent.android.trace

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.ondeviceagent.android.model.AgentTraceSummary
import com.ondeviceagent.android.model.AgentTraceCounts
import com.ondeviceagent.android.model.AgentTraceFinalStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant

@Serializable
data class TraceManifest(
    val run_id: String,
    val task: String = "",
    val started_at: String,
    val finished_at: String = "",
    val task_id: String = "",
    val parent_run_id: String = "",
    val attempt: Int = 0,
    val schema: String = "wda.training_turn.v1",
    val counts: Counts = Counts(),
    val final_status: FinalStatus = FinalStatus(),
)

@Serializable
data class Counts(
    val logs: Int = 0,
    val events: Int = 0,
    val turns: Int = 0,
    val images: Int = 0,
)

@Serializable
data class FinalStatus(
    val success: Boolean = false,
    val message: String = "",
    val stop_reason: String = "",
)

@Serializable
data class TraceTurnRecord(
    val step: Int = 0,
    val ts: String = "",
    val action: String = "",
    val ok: Boolean = true,
    val error: String = "",
    val model: String = "",
    val element: List<Double> = emptyList(),
    val duration_ms: Long = 0,
)

data class TraceDetail(
    val manifest: TraceManifest,
    val turns: List<TraceTurnRecord>,
)

class TraceRecorder(context: Context) {
    private val root = File(context.filesDir, "traces").also { it.mkdirs() }
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Volatile private var activeDir: File? = null
    @Volatile private var activeRunId: String = ""
    @Volatile private var turns: Int = 0
    @Volatile private var images: Int = 0
    @Volatile private var task: String = ""
    @Volatile private var startedAt: String = ""

    private var taskId = ""
    private var parentRunId = ""
    private var attempt = 0

    fun begin(runId: String, taskText: String, ownerId: String = "", parentId: String = "", attemptIndex: Int = 0) {
        taskId = ownerId
        parentRunId = parentId
        attempt = attemptIndex
        activeRunId = runId
        task = taskText
        startedAt = Instant.now().toString()
        turns = 0
        images = 0
        activeDir = File(root, runId).also { it.mkdirs(); File(it, "images").mkdirs() }
        writeManifest(finished = false)
    }

    fun appendTurn(step: Int, rawModel: String, actionName: String, ok: Boolean, error: String, element: List<Double> = emptyList(), durationMs: Long = 0) {
        val dir = activeDir ?: return
        turns += 1
        val line = json.encodeToString(TraceTurnRecord(
            step = step,
            ts = Instant.now().toString(),
            action = actionName,
            ok = ok,
            error = error,
            model = rawModel, element = element, duration_ms = durationMs,
        ))
        File(dir, "turns.jsonl").appendText(line + "\n", Charsets.UTF_8)
        writeManifest(finished = false)
    }

    /** 保留完整原始输出及结束原因，便于区分语法错误和模型截断。 */
    fun saveModelOutput(step: Int, attempt: Int, text: String, finishReason: String) {
        val dir = activeDir ?: return
        val record = kotlinx.serialization.json.buildJsonObject {
            put("text", kotlinx.serialization.json.JsonPrimitive(text))
            put("finish_reason", kotlinx.serialization.json.JsonPrimitive(finishReason))
        }
        File(dir, "model-$step-$attempt.json").writeText(record.toString(), Charsets.UTF_8)
    }

    fun saveImageJpegBase64(step: Int, b64: String?) {
        if (b64.isNullOrBlank()) return
        val dir = activeDir ?: return
        val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
        File(dir, "images/step_$step.jpg").writeBytes(bytes)
        images += 1
        writeManifest(finished = false)
    }

    fun finish(success: Boolean, message: String, stopReason: String) {
        writeManifest(
            finished = true,
            success = success,
            message = message,
            stopReason = stopReason,
            finishedAt = Instant.now().toString(),
        )
        activeDir = null
        activeRunId = ""
    }

    fun statusSnapshot(): Map<String, Any?> = mapOf(
        "run_id" to activeRunId,
        "active" to (activeDir != null),
    )

    fun loadModelOutputs(runId: String, step: Int): List<String> {
        val dir = File(root, runId)
        return (0..2).mapNotNull { attempt ->
            val file = File(dir, "model-$step-$attempt.json")
            if (file.isFile) "尝试 $attempt：\n${file.readText()}" else null
        }
    }

    fun recoverIncompleteRuns() {
        root.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            val file = File(dir, "manifest.json")
            runCatching {
                val manifest = json.decodeFromString<TraceManifest>(file.readText())
                if (manifest.finished_at.isBlank()) file.writeText(json.encodeToString(manifest.copy(
                    finished_at = Instant.now().toString(),
                    final_status = FinalStatus(false, "上次运行因进程中断，请核对结果", "interrupted"),
                )), Charsets.UTF_8)
            }
        }
    }

    fun clearCompletedHistory() {
        root.listFiles()?.filter { it.isDirectory && it != activeDir }?.forEach { dir ->
            val complete = runCatching { json.decodeFromString<TraceManifest>(File(dir, "manifest.json").readText()).finished_at.isNotBlank() }.getOrDefault(false)
            if (complete) dir.deleteRecursively()
        }
    }

    fun listSummaries(): List<AgentTraceSummary> {
        return root.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir -> toSummary(dir) }
            ?.sortedByDescending { it.startedAt }
            .orEmpty()
    }

    fun loadDetail(runId: String): TraceDetail? {
        val dir = File(root, runId)
        if (!dir.isDirectory) return null
        val manifestFile = File(dir, "manifest.json")
        if (!manifestFile.exists()) return null
        val manifest = runCatching {
            json.decodeFromString<TraceManifest>(manifestFile.readText())
        }.getOrNull() ?: return null
        val turnsFile = File(dir, "turns.jsonl")
        val turns = if (!turnsFile.exists()) {
            emptyList()
        } else {
            turnsFile.readLines()
                .mapNotNull { line ->
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) null
                    else runCatching { json.decodeFromString<TraceTurnRecord>(trimmed) }.getOrNull()
                }
        }
        return TraceDetail(manifest = manifest, turns = turns)
    }

    fun loadStepBitmap(runId: String, step: Int): Bitmap? {
        val file = File(root, "$runId/images/step_$step.jpg")
        if (!file.exists()) return null
        return BitmapFactory.decodeFile(file.absolutePath)
    }

    /** Highest-numbered step_*.jpg for a finished/active run, if any. */
    fun lastImageFile(runId: String): File? {
        if (runId.isBlank()) return null
        val dir = File(root, "$runId/images")
        if (!dir.isDirectory) return null
        val re = Regex("""^step_(\d+)\.jpg$""")
        return dir.listFiles()
            ?.mapNotNull { f ->
                val m = re.matchEntire(f.name) ?: return@mapNotNull null
                m.groupValues[1].toIntOrNull()?.let { it to f }
            }
            ?.maxByOrNull { it.first }
            ?.second
            ?.takeIf { it.exists() && it.length() > 0L }
    }

    fun lastImageBytes(runId: String): ByteArray? =
        lastImageFile(runId)?.readBytes()

    private fun toSummary(dir: File): AgentTraceSummary? {
        val manifestFile = File(dir, "manifest.json")
        if (!manifestFile.exists()) return null
        return runCatching {
            val m = json.decodeFromString<TraceManifest>(manifestFile.readText())
            AgentTraceSummary(
                runId = m.run_id,
                task = m.task,
                startedAt = m.started_at,
                finishedAt = m.finished_at,
                schema = m.schema, parentRunId = m.parent_run_id, attempt = m.attempt,
                counts = AgentTraceCounts(
                    logs = m.counts.logs,
                    events = m.counts.events,
                    turns = m.counts.turns,
                    images = m.counts.images,
                ),
                finalStatus = AgentTraceFinalStatus(
                    success = m.final_status.success,
                    message = m.final_status.message,
                    stopReason = m.final_status.stop_reason,
                ),
            )
        }.getOrNull()
    }

    private fun writeManifest(
        finished: Boolean,
        success: Boolean = false,
        message: String = "",
        stopReason: String = "",
        finishedAt: String = "",
    ) {
        val dir = activeDir ?: return
        val manifest = TraceManifest(
            run_id = activeRunId,
            task_id = taskId, parent_run_id = parentRunId, attempt = attempt,
            task = task,
            started_at = startedAt,
            finished_at = if (finished) finishedAt else "",
            counts = Counts(turns = turns, images = images),
            final_status = FinalStatus(success, message, stopReason),
        )
        File(dir, "manifest.json").writeText(json.encodeToString(manifest))
    }
}
