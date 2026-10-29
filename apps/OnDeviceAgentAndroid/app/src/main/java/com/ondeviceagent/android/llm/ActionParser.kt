package com.ondeviceagent.android.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class ParsedModelOutput(
    val think: String,
    val plan: List<PlanItem>,
    val actionName: String,
    val actionParams: JsonObject,
    val rawJson: String,
)

data class PlanItem(
    val id: String = "",
    val text: String,
    val done: Boolean = false,
)

object ActionParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(modelText: String): ParsedModelOutput {
        val extracted = extractJsonObject(modelText)
            ?: error("Model output is not a JSON object")
        val candidates = listOf(extracted, repairCommonJsonIssues(extracted)).distinct()
        var lastError: Throwable? = null
        for (candidate in candidates) {
            try {
                return parseObject(candidate)
            } catch (t: Throwable) {
                lastError = t
            }
        }
        throw lastError ?: error("Failed to parse model JSON")
    }

    private fun parseObject(extracted: String): ParsedModelOutput {
        val obj = json.parseToJsonElement(extracted).jsonObject
        val think = obj["think"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val plan = obj["plan"]?.jsonArray?.mapNotNull { el ->
            val o = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
            PlanItem(
                id = o["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                text = o["text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                done = o["done"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        }.orEmpty()
        val action = obj["action"]?.jsonObject
            ?: error("Missing action object")
        val name = action["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (name.isBlank()) error("Missing action.name")
        val params = action["params"]?.jsonObject ?: JsonObject(emptyMap())
        return ParsedModelOutput(
            think = think,
            plan = plan,
            actionName = name,
            actionParams = params,
            rawJson = extracted,
        )
    }

    /** 只修正字符串外的漏逗号、尾逗号，绝不改写密码、文案或字符串转义。 */
    fun repairCommonJsonIssues(raw: String): String {
        val out = StringBuilder()
        var inString = false
        var escaped = false
        var index = 0
        while (index < raw.length) {
            val c = raw[index]
            if (inString) {
                out.append(c)
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') inString = false
                index++
                continue
            }
            if (c == '"') inString = true
            if (c == ',') {
                val next = raw.drop(index + 1).firstOrNull { !it.isWhitespace() }
                if (next == ']' || next == '}') { index++; continue }
            }
            if (c.isWhitespace() && out.lastOrNull()?.isDigit() == true) {
                var next = index
                while (next < raw.length && raw[next].isWhitespace()) next++
                if (next < raw.length && (raw[next].isDigit() || raw[next] == '-')) out.append(',')
            }
            out.append(c)
            index++
        }
        return out.toString().trim()
    }

    fun mergePlan(previous: List<PlanItem>, next: List<PlanItem>): List<PlanItem> {
        fun key(text: String) = text.lowercase().filter { it.isLetterOrDigit() }
        fun identified(item: PlanItem) = if (item.id.isBlank()) item.copy(id = java.util.UUID.randomUUID().toString().take(12)) else item
        val merged = previous.filter { it.text.isNotBlank() }.map { identified(it) }.toMutableList()
        for (item in next.filter { it.text.isNotBlank() }) {
            val index = merged.indexOfFirst { (item.id.isNotBlank() && item.id == it.id) || key(item.text) == key(it.text) }
            if (index >= 0) merged[index] = merged[index].copy(done = merged[index].done || item.done)
            else if (merged.size < 50) merged += identified(item)
        }
        return merged.take(50)
    }

    private fun extractJsonObject(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) return trimmed
        val fence = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
            .find(trimmed)?.groupValues?.getOrNull(1)?.trim()
        if (!fence.isNullOrBlank() && fence.startsWith("{")) return fence
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start >= 0 && end > start) return trimmed.substring(start, end + 1)
        return null
    }
}
