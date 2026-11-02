package com.deepseekharness.app.phone

/** What a tool call returns to the model: one text block, optionally an image, and the MCP error flag. */
class ToolOutput(
    val text: String,
    val isError: Boolean = false,
    val image: ByteArray? = null,
    val imageMime: String? = null,
)

/**
 * The MCP side of the bridge: newline-delimited JSON-RPC 2.0 as MCP's stdio transport speaks it
 * (the guest relay forwards the bytes unchanged). Serves `initialize`, `ping`, `tools/list` and
 * `tools/call`; notifications and responses are ignored; anything else is "method not found"
 * (that is also how DSH 0.2.1's `server/discover` probe falls back to `initialize`).
 *
 * [dispatch] answers cheap methods immediately and hands tool calls back as a [Dispatch.Call], so
 * the connection keeps reading (and answering pings) while a call waits for the person.
 */
class McpServer(
    private val tools: List<Map<String, Any?>>,
    private val call: (name: String, args: Map<String, Any?>) -> ToolOutput,
    private val serverVersion: String = "1",
) {
    sealed interface Dispatch {
        /** Nothing to send (notification, response, blank line). */
        object None : Dispatch

        class Reply(val line: String) : Dispatch

        class Call(val id: Any?, val name: String, val args: Map<String, Any?>, private val server: McpServer) : Dispatch {
            /** Runs the tool (blocking) and returns the response line. */
            fun run(): String = server.runCall(id, name, args)
        }
    }

    /** Synchronous convenience: dispatch and run any tool call inline. */
    fun handle(line: String): String? = when (val d = dispatch(line)) {
        Dispatch.None -> null
        is Dispatch.Reply -> d.line
        is Dispatch.Call -> d.run()
    }

    fun dispatch(line: String): Dispatch {
        if (line.isBlank()) return Dispatch.None
        val msg = try {
            Json.parse(line)
        } catch (e: Json.JsonException) {
            return Dispatch.Reply(error(null, PARSE_ERROR, "Parse error"))
        }
        if (msg !is Map<*, *>) return Dispatch.Reply(error(null, INVALID_REQUEST, "Invalid Request"))
        val hasId = msg.containsKey("id")
        val id = msg["id"]
        val method = msg["method"] as? String
        if (method == null) {
            // A response to a request we never send, or garbage: nothing to answer unless it carries an id.
            return if (hasId && !msg.containsKey("result") && !msg.containsKey("error")) {
                Dispatch.Reply(error(id, INVALID_REQUEST, "Invalid Request"))
            } else {
                Dispatch.None
            }
        }
        if (!hasId) return Dispatch.None // notifications/initialized, notifications/cancelled, …
        val params = msg["params"] as? Map<*, *> ?: emptyMap<String, Any?>()
        return when (method) {
            "initialize" -> Dispatch.Reply(result(id, initializeResult(params["protocolVersion"] as? String)))
            "ping" -> Dispatch.Reply(result(id, emptyMap<String, Any?>()))
            "tools/list" -> Dispatch.Reply(result(id, mapOf("tools" to tools)))
            "tools/call" -> {
                val name = params["name"] as? String
                    ?: return Dispatch.Reply(error(id, INVALID_PARAMS, "tools/call needs a tool name"))
                @Suppress("UNCHECKED_CAST")
                val args = (params["arguments"] as? Map<String, Any?>) ?: emptyMap()
                Dispatch.Call(id, name, args, this)
            }
            else -> Dispatch.Reply(error(id, METHOD_NOT_FOUND, "Method not found"))
        }
    }

    private fun initializeResult(requested: String?): Map<String, Any?> = linkedMapOf(
        "protocolVersion" to (requested?.takeIf { it in KNOWN_VERSIONS } ?: LATEST_VERSION),
        "capabilities" to mapOf("tools" to mapOf("listChanged" to false)),
        "serverInfo" to linkedMapOf("name" to "dsh-app-phone", "version" to serverVersion),
    )

    internal fun runCall(id: Any?, name: String, args: Map<String, Any?>): String {
        val out = try {
            call(name, args)
        } catch (e: Throwable) {
            // l10n:keep agent tool error
            ToolOutput("手机操控出错：${e.javaClass.simpleName}${e.message?.let { "：$it" } ?: ""}", isError = true)
        }
        return result(id, toolResult(out))
    }

    companion object {
        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602

        /** Revisions whose tools surface is what we serve; anything else gets [LATEST_VERSION]. */
        val KNOWN_VERSIONS = listOf("2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25")
        const val LATEST_VERSION = "2025-11-25"

        fun toolResult(out: ToolOutput): Map<String, Any?> {
            val content = ArrayList<Map<String, Any?>>()
            content += linkedMapOf("type" to "text", "text" to out.text)
            val image = out.image
            if (image != null && out.imageMime != null) {
                content += linkedMapOf(
                    "type" to "image",
                    "data" to java.util.Base64.getEncoder().encodeToString(image),
                    "mimeType" to out.imageMime,
                )
            }
            return linkedMapOf("content" to content, "isError" to out.isError)
        }

        fun result(id: Any?, result: Any?): String = Json.write(linkedMapOf("jsonrpc" to "2.0", "id" to id, "result" to result))

        fun error(id: Any?, code: Int, message: String): String =
            Json.write(linkedMapOf("jsonrpc" to "2.0", "id" to id, "error" to linkedMapOf("code" to code, "message" to message)))
    }
}
