package com.droidos.neo

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * McpCloudflareClient â€“ optional direct-connection mode.
 *
 * Opens a persistent HTTPS long-poll (SSE-style) connection to the Cloudflare
 * Worker endpoint `/__device_connect`, reads newline-delimited JSON-RPC requests,
 * and HTTP-POSTs responses back to `/__device_respond?id=<requestId>`.
 *
 * The connection does NOT auto-start on app launch; call start() manually.
 * State is persisted via SharedPreferences key "cloudflare_mode_enabled".
 */
class McpCloudflareClient(
    private val context: Context,
    private val workerBase: String = "https://YOUR-DROIDOS-WORKER.workers.dev",
    private val autoReconnect: Boolean = true
) {
    companion object {
        const val PREF_NAME = "droidos_prefs"
        const val PREF_CF_ENABLED = "cloudflare_mode_enabled"
    }

    @Volatile var connected: Boolean = false
        private set

    @Volatile private var running = false
    private var pollThread: Thread? = null
    private var onLogMessage: ((String) -> Unit)? = null

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun setOnLogMessage(cb: (String) -> Unit) { onLogMessage = cb }

    /** Starts the Cloudflare polling loop and persists the enabled state. */
    fun start() {
        if (running) return
        running = true
        prefs.edit().putBoolean(PREF_CF_ENABLED, true).apply()
        pollThread = Thread(::pollLoop, "droidos-cf-poll").also {
            it.isDaemon = true
            it.start()
        }
        log("McpCloudflareClient: connecting to $workerBase/__device_connect â€¦")
    }

    /** Stops the polling loop and persists the disabled state. */
    fun stop() {
        running = false
        connected = false
        pollThread?.interrupt()
        pollThread = null
        prefs.edit().putBoolean(PREF_CF_ENABLED, false).apply()
        log("McpCloudflareClient stopped")
    }

    /** Returns the persisted preference (default OFF). */
    fun isModeEnabled(): Boolean = prefs.getBoolean(PREF_CF_ENABLED, false)

    private fun log(msg: String) { onLogMessage?.invoke(msg) }

    // â”€â”€ Long-poll loop â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    private fun pollLoop() {
        while (running) {
            try {
                connectAndStream()
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                connected = false
                log("CF poll error: ${e.message}. Reconnecting in 5sâ€¦")
                if (!autoReconnect) break
                try { Thread.sleep(5_000) } catch (_: InterruptedException) { break }
            }
        }
        connected = false
    }

    /**
     * Opens a streaming GET to `/__device_connect`.
     * Reads one JSON-RPC request per line, dispatches it, and POSTs the response.
     */
    private fun connectAndStream() {
        val url = URL("$workerBase/__device_connect")
        val conn = (url.openConnection() as HttpsURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/x-ndjson")
            connectTimeout = 15_000
            readTimeout = 0          // stream indefinitely
            doInput = true
        }
        conn.connect()
        connected = true
        log("CF: connected (HTTP ${conn.responseCode})")

        BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { br ->
            while (running) {
                val line = br.readLine() ?: break   // null = server closed
                if (line.isBlank()) continue
                handleRequest(line.trim())
            }
        }
        connected = false
        conn.disconnect()
    }

    // â”€â”€ Dispatch one RPC request â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    private fun handleRequest(raw: String) {
        val req = try { JSONObject(raw) } catch (_: Exception) {
            log("CF: bad JSON â†’ $raw"); return
        }
        val id     = req.opt("id")
        val method = req.optString("method", "")
        val params = req.optJSONObject("params") ?: JSONObject()

        log("CF: â† $method (id=$id)")

        val result: JSONObject = when (method) {
            "initialize"  -> buildInit()
            "tools/list"  -> JSONObject().put("tools", buildToolsList())
            "tools/call"  -> dispatchToolsCall(params)
            else -> JSONObject().put("error",
                JSONObject().put("code", -32601).put("message", "Method not found: $method"))
        }

        val response = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            if (result.has("error") && !result.has("content"))
                put("error", result.get("error"))
            else
                put("result", result)
        }

        postResponse(id?.toString() ?: "null", response.toString())
    }

    // â”€â”€ POST response back to Worker â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    private fun postResponse(requestId: String, body: String) {
        try {
            val url = URL("$workerBase/__device_respond?id=$requestId")
            val conn = (url.openConnection() as HttpsURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                doOutput = true
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
            conn.inputStream.close()
            log("CF: â†’ responded to id=$requestId (HTTP ${conn.responseCode})")
            conn.disconnect()
        } catch (e: Exception) {
            log("CF: failed to post response: ${e.message}")
        }
    }

    // â”€â”€ MCP helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    private fun buildInit() = JSONObject().apply {
        put("protocolVersion", "2024-11-05")
        put("capabilities", JSONObject().put("tools", JSONObject()))
        put("serverInfo", JSONObject().apply {
            put("name", "droidos-neo-android")
            put("version", "1.0.0")
        })
    }

    private fun buildToolsList() = org.json.JSONArray().apply {
        listOf("take_snapshot", "tap_element", "input_text",
               "scroll_screen", "open_app", "press_key", "run_macro")
            .forEach { name -> put(JSONObject().put("name", name)) }
    }

    private fun dispatchToolsCall(params: JSONObject): JSONObject {
        val name = params.optString("name", "")
        val args = params.optJSONObject("arguments") ?: JSONObject()
        val service = DroidOSAccessibilityService.instance
            ?: return wrapError("Error: DroidOS Accessibility Service is not enabled.")

        val raw: JSONObject = when (name) {
            "take_snapshot"  -> service.getCompactSnapshot()
            "tap_element"    -> service.performTap(args.optInt("index", -1), args.optInt("x", -1), args.optInt("y", -1))
            "input_text"     -> service.performInputText(args.optString("text", ""), args.optInt("index", -1))
            "scroll_screen"  -> service.performScroll(args.optString("direction", "down"))
            "open_app"       -> service.openApp(args.optString("package_name", ""))
            "press_key"      -> service.performSystemKey(args.optString("key", "back"))
            else             -> JSONObject().put("error", "Unknown tool: $name")
        }

        val isErr = raw.has("error")
        return JSONObject().apply {
            put("isError", isErr)
            put("content", org.json.JSONArray().put(
                JSONObject().put("type", "text").put("text", if (isErr) raw.optString("error") else raw.toString())
            ))
        }
    }

    private fun wrapError(msg: String) = JSONObject().apply {
        put("isError", true)
        put("content", org.json.JSONArray().put(JSONObject().put("type", "text").put("text", msg)))
    }
}

