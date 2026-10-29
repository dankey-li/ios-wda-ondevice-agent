package com.ondeviceagent.android.llm

import kotlinx.coroutines.CancellationException

/** 格式纠错期间不执行设备动作，也不接受补救输出对任务计划的修改。 */
object ActionOutputRecovery {
    data class Result(val output: ParsedModelOutput, val repairs: Int)

    suspend fun parse(
        initial: String,
        repair: suspend (badOutput: String, error: String, attempt: Int) -> String,
    ): Result {
        var content = initial
        var error: Throwable? = null
        for (attempt in 0..2) {
            try {
                val parsed = ActionParser.parse(content)
                return Result(if (attempt == 0) parsed else parsed.copy(plan = emptyList()), attempt)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure
            }
            if (attempt < 2) content = repair(content, error?.message.orEmpty(), attempt + 1)
        }
        throw IllegalArgumentException("模型输出连续 3 次无法解析，已停止执行，避免误操作", error)
    }

    fun repairPrompt(badOutput: String, error: String): String = """
        上一次模型输出无法解析为合法 JSON，尚未执行其中的动作。
        错误：${error.take(500)}
        请只纠正格式，保持原动作意图；仅输出一个包含 action 对象的合法 JSON 对象。
        action 必须为 {"name":"Tap","params":{"element":[100,200]}} 这样的结构。
        不要输出或修改 plan，不要增加动作，不要输出 Markdown。字符串中的双引号必须转义。
        若原输出已截断且无法判断动作，请返回 {"action":{"name":"Wait","params":{"seconds":1}}}，等待下一轮重新观察。
        上一条输出：
        ${badOutput.take(8000)}
    """.trimIndent()
}
