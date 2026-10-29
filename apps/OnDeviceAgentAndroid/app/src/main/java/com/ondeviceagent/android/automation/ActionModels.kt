package com.ondeviceagent.android.automation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class AgentActionRequest(
    val action: AgentActionBody? = null,
    val name: String? = null,
    val params: JsonObject? = null,
)

@Serializable
data class AgentActionBody(
    val name: String = "",
    val params: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class AgentActionResult(
    val ok: Boolean,
    val error: String = "",
    @SerialName("error_kind") val errorKind: String = "",
    val name: String = "",
    val resolvedElement: List<Double> = emptyList(),
)

data class ParsedAction(
    val name: String,
    val params: JsonObject,
)

fun normalizeActionName(raw: String): String {
    return raw.trim()
        .lowercase()
        .replace('-', '_')
        .replace(Regex("\\s+"), "_")
}

fun parseActionRequest(request: AgentActionRequest): ParsedAction? {
    val nested = request.action
    val name = normalizeActionName(nested?.name?.takeIf { it.isNotBlank() } ?: request.name.orEmpty())
    if (name.isBlank()) return null
    val params = nested?.params ?: request.params ?: JsonObject(emptyMap())
    return ParsedAction(name = name, params = params)
}

fun JsonObject.stringParam(vararg keys: String): String? {
    for (key in keys) {
        val value = this[key] ?: continue
        val text = value.asFlexibleString()?.trim().orEmpty()
        if (text.isNotEmpty()) return text
    }
    return null
}

fun JsonObject.doubleParam(vararg keys: String, default: Double = 0.0): Double {
    for (key in keys) {
        val value = this[key] ?: continue
        val number = value.asFlexibleDouble()
        if (number != null) return number
    }
    return default
}

fun JsonObject.point1000(key: String): Pair<Double, Double>? {
    val element = this[key] ?: return null
    val arr = runCatching { element.jsonArray }.getOrNull() ?: return null
    if (arr.size < 2) return null
    val x = arr[0].asFlexibleDouble() ?: return null
    val y = arr[1].asFlexibleDouble() ?: return null
    if (x !in 0.0..1000.0 || y !in 0.0..1000.0) return null
    return x to y
}

private fun JsonElement.asFlexibleString(): String? {
    val primitive = runCatching { jsonPrimitive }.getOrNull() ?: return null
    return primitive.contentOrNull ?: primitive.toString().trim('"')
}

private fun JsonElement.asFlexibleDouble(): Double? {
    val primitive = runCatching { jsonPrimitive }.getOrNull() ?: return null
    primitive.doubleOrNull?.let { return it }
    return primitive.contentOrNull?.trim()?.toDoubleOrNull()
}
