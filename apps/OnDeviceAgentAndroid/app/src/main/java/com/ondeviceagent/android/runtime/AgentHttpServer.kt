package com.ondeviceagent.android.runtime

import com.ondeviceagent.android.model.AgentChatPayload
import com.ondeviceagent.android.model.AgentConfigRequest
import com.ondeviceagent.android.model.AgentLogsPayload
import com.ondeviceagent.android.model.AgentTraceListPayload
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Local HTTP surface compatible with iOS WDA /agent routes (subset for v0). */
class AgentHttpServer(
    private val runtime: AgentRuntime,
) : NanoHTTPD(HOST, PORT) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri.substringBefore('?')
        val method = session.method

        return try {
            when {
                method == Method.GET && uri == "/agent/status" -> {
                    val include = session.parameters["include_default_system_prompt"]?.firstOrNull() == "1"
                    val status = runBlocking { runtime.status(include) }
                    jsonOk(json.encodeToString(status))
                }

                method == Method.GET && uri == "/agent/logs" -> {
                    val lines = runBlocking { runtime.logs() }
                    jsonOk(json.encodeToString(AgentLogsPayload(lines)))
                }

                method == Method.GET && uri == "/agent/chat" -> {
                    val items = runBlocking { runtime.chat() }
                    jsonOk(json.encodeToString(AgentChatPayload(items)))
                }

                method == Method.GET && uri == "/agent/traces" -> {
                    jsonOk(json.encodeToString(AgentTraceListPayload(runtime.traces())))
                }

                method == Method.GET && (uri == "/agent" || uri == "/") -> {
                    htmlOk(INDEX_HTML)
                }

                method == Method.POST && uri == "/agent/config" -> {
                    val body = readBody(session)
                    val req = json.decodeFromString<AgentConfigRequest>(body.ifBlank { "{}" })
                    val status = runBlocking { runtime.applyConfig(req) }
                    jsonOk(json.encodeToString(status))
                }

                method == Method.POST && uri == "/agent/start" -> {
                    val body = readBody(session)
                    val req = if (body.isBlank()) null else json.decodeFromString<AgentConfigRequest>(body)
                    val payload = runBlocking { runtime.start(req) }
                    jsonOk(json.encodeToString(payload))
                }

                method == Method.POST && uri == "/agent/stop" -> {
                    readBody(session)
                    val status = runBlocking { runtime.stop() }
                    jsonOk(json.encodeToString(status))
                }

                method == Method.POST && uri == "/agent/reset" -> {
                    readBody(session)
                    val status = runBlocking { runtime.reset() }
                    jsonOk(json.encodeToString(status))
                }

                method == Method.POST && uri == "/agent/action" -> {
                    val body = readBody(session)
                    val req = json.decodeFromString<com.ondeviceagent.android.automation.AgentActionRequest>(
                        body.ifBlank { "{}" },
                    )
                    val result = runBlocking { runtime.performAction(req) }
                    jsonOk(json.encodeToString(result))
                }

                method == Method.POST && uri == "/agent/factory_reset" -> {
                    readBody(session)
                    val status = runBlocking { runtime.factoryReset() }
                    jsonOk(json.encodeToString(status))
                }

                method == Method.POST && uri == "/agent/notify/test" -> {
                    readBody(session)
                    val result = runBlocking { runtime.sendTestFeishuNotify() }
                    jsonOk(
                        """{"ok":${result.ok},"detail":${json.encodeToString(result.detail)}}""",
                    )
                }

                else -> newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    "application/json",
                    """{"error":"not found","path":"$uri"}""",
                )
            }
        } catch (t: Throwable) {
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                """{"error":${json.encodeToString(t.message ?: t::class.java.simpleName)}}""",
            )
        }
    }

    private fun readBody(session: IHTTPSession): String {
        val files = HashMap<String, String>()
        return try {
            session.parseBody(files)
            files["postData"] ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun jsonOk(body: String): Response =
        newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", body)

    private fun htmlOk(body: String): Response =
        newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", body)

    companion object {
        const val HOST = "127.0.0.1"
        const val PORT = 8100

        private val INDEX_HTML = """
            <!doctype html>
            <html><head><meta charset="utf-8"><title>OnDevice Agent (Android)</title>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>
              body{font-family:system-ui;margin:24px;background:#f6f7f4;color:#1b4332}
              code{background:#e9ece6;padding:2px 6px;border-radius:4px}
            </style></head>
            <body>
              <h1>OnDevice Agent (Android)</h1>
              <p>Local API mirrors iOS WDA <code>/agent</code> routes.</p>
              <ul>
                <li><a href="/agent/status">GET /agent/status</a></li>
                <li><a href="/agent/logs">GET /agent/logs</a></li>
                <li><a href="/agent/chat">GET /agent/chat</a></li>
              </ul>
              <p>Use the Android app UI or POST <code>/agent/start</code> to run the scaffold loop.</p>
            </body></html>
        """.trimIndent()
    }
}
