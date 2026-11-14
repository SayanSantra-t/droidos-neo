package com.droidos.neo

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class McpHttpServer(private val port: Int = 9239) {

    @Volatile private var isRunning: Boolean = false
    private var serverSocket: ServerSocket? = null
    private val threadPool: ExecutorService = Executors.newCachedThreadPool()
    private var onLogMessage: ((String) -> Unit)? = null

    fun setOnLogMessage(callback: (String) -> Unit) {
        onLogMessage = callback
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────
    fun start() {
        if (isRunning) return
        isRunning = true
        threadPool.execute { acceptLoop() }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        log("DroidOS MCP Server stopped")
    }

    private fun log(msg: String) {
        onLogMessage?.invoke(msg)
    }

    // ── Accept loop ──────────────────────────────────────────────────────────
    private fun acceptLoop() {
        try {
            val ss = ServerSocket(port)
            serverSocket = ss
            log("DroidOS MCP Server listening on port $port")
            while (isRunning) {
                val client = ss.accept()
                threadPool.execute { handleClient(client) }
            }
        } catch (_: Exception) {
            if (isRunning) log("Server error: socket closed unexpectedly")
        }
    }

    // ── Handle one HTTP connection ───────────────────────────────────────────
    private fun handleClient(clientSocket: Socket) {
        try {
            clientSocket.use { socket ->
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val writer = PrintWriter(socket.getOutputStream(), true)

                // --- parse request line + headers ---
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val path = parts[1]

                // consume headers
                val headers = mutableMapOf<String, String>()
                var contentLength = 0
                var line = reader.readLine()
                while (!line.isNullOrEmpty()) {
                    val colonIdx = line.indexOf(':')
                    if (colonIdx > 0) {
                        val key = line.substring(0, colonIdx).trim().lowercase()
                        val value = line.substring(colonIdx + 1).trim()
                        headers[key] = value
                        if (key == "content-length") contentLength = value.toIntOrNull() ?: 0
                    }
                    line = reader.readLine()
                }

                // read body
                val body = if (contentLength > 0) {
                    val chars = CharArray(contentLength)
                    reader.read(chars, 0, contentLength)
                    String(chars)
                } else ""

                // --- CORS pre-flight ---
                val corsHeaders = "\r\nAccess-Control-Allow-Origin: *\r\n" +
                    "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                    "Access-Control-Allow-Headers: Content-Type\r\n" +
                    "Connection: close\r\n\r\n"

                if (method == "OPTIONS") {
                    writer.print("HTTP/1.1 204 No Content$corsHeaders")
                    writer.flush()
                    return
                }

                // --- route ---
                val (statusCode, statusText, contentType, responseBody) = when {
                    path == "/mcp" && method == "POST" -> handleMcpPost(body)
                    path == "/snapshot" && method == "GET" -> handleSnapshot()
                    path == "/status" && method == "GET" -> handleStatus()
                    else -> listOf(
                        404, "Not Found", "application/json; charset=UTF-8",
                        JSONObject().put("error", "Endpoint not found: $path").toString()
                    )
                }

                val responseBytes = responseBody.toString().toByteArray(Charsets.UTF_8)
                writer.print(
                    "HTTP/1.1 $statusCode $statusText" +
                        "\r\nContent-Type: $contentType" +
                        "\r\nContent-Length: ${responseBytes.size}" +
                        corsHeaders
                )
                writer.flush()
                socket.getOutputStream().write(responseBytes)
                socket.getOutputStream().flush()
            }
        } catch (_: Exception) {}
    }

    // ── Route handlers ───────────────────────────────────────────────────────

    private fun handleStatus(): List<Any> {
        val ip = NetworkUtils.getLocalIpAddress()
        val body = JSONObject().apply {
            put("status", "active")
            put("port", port)
            put("ip", ip)
            put("endpoint", "http://$ip:$port/mcp")
        }
        return listOf(200, "OK", "application/json; charset=UTF-8", body.toString())
    }

    private fun handleSnapshot(): List<Any> {
        val service = DroidOSAccessibilityService.instance
            ?: return listOf(
                503, "Service Unavailable", "application/json; charset=UTF-8",
                JSONObject().put("error", "Error: DroidOS Accessibility Service is not enabled on this device.").toString()
            )
        val snapshot = service.getCompactSnapshot()
        return listOf(200, "OK", "application/json; charset=UTF-8", snapshot.toString())
    }

    private fun handleMcpPost(body: String): List<Any> {
        return try {
            val request = JSONObject(body)
            val id = request.opt("id")
            val method = request.optString("method", "")
            val params = request.optJSONObject("params") ?: JSONObject()

            val result: JSONObject = when (method) {
                "initialize" -> buildInitializeResponse()
                "tools/list"  -> buildToolsListResponse()
                "tools/call"  -> handleToolsCall(params)
                else -> JSONObject().put("error",
                    JSONObject().put("code", -32601).put("message", "Method not found: $method"))
            }

            val response = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", id)
                // If result has an "error" key at top level wrap it properly
                if (result.has("error") && !result.has("content")) {
                    put("error", result.get("error"))
                } else {
                    put("result", result)
                }
            }
            listOf(200, "OK", "application/json; charset=UTF-8", response.toString())
        } catch (e: Exception) {
            val errResp = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", JSONObject.NULL)
                put("error", JSONObject().put("code", -32700).put("message", "Parse error"))
            }
            listOf(400, "Bad Request", "application/json; charset=UTF-8", errResp.toString())
        }
    }

    // ── MCP protocol builders ────────────────────────────────────────────────

    private fun buildInitializeResponse(): JSONObject = JSONObject().apply {
        put("protocolVersion", "2024-11-05")
        put("capabilities", JSONObject().put("tools", JSONObject()))
        put("serverInfo", JSONObject().apply {
            put("name", "droidos-neo-android")
            put("version", "1.0.0")
        })
    }

    private fun buildToolsListResponse(): JSONObject = JSONObject().apply {
        put("tools", getMcpToolsList())
    }

    private fun handleToolsCall(params: JSONObject): JSONObject {
        val toolName = params.optString("name", "")
        val args = params.optJSONObject("arguments") ?: JSONObject()

        // run_macro doesn't need the accessibility service directly
        if (toolName == "run_macro") {
            val steps = args.optString("steps", "[]")
            val macroResult = MacroRunner(this).runMacro(steps)
            return JSONObject().apply {
                put("isError", false)
                put("content", JSONArray().put(
                    JSONObject().put("type", "text").put("text", macroResult)
                ))
            }
        }

        val service = DroidOSAccessibilityService.instance
            ?: return JSONObject().apply {
                put("isError", true)
                put("content", JSONArray().put(
                    JSONObject().put("type", "text")
                        .put("text", "Error: DroidOS Accessibility Service is not enabled on this device.")
                ))
            }

        val toolResult = executeTool(service, toolName, args)

        // Wrap result as MCP content
        val isError = toolResult.has("error")
        val text = if (isError) toolResult.optString("error") else toolResult.toString()

        return JSONObject().apply {
            put("isError", isError)
            put("content", JSONArray().put(
                JSONObject().put("type", "text").put("text", text)
            ))
        }
    }

    /**
     * Public entry point for MacroRunner – executes a named tool and returns the raw result.
     */
    fun executeToolCall(toolName: String, args: JSONObject): JSONObject {
        if (toolName == "run_macro") {
            val steps = args.optString("steps", "[]")
            val macroResult = MacroRunner(this).runMacro(steps)
            return JSONObject().put("macro_results", macroResult)
        }
        val service = DroidOSAccessibilityService.instance
            ?: return JSONObject().put("error", "DroidOS Accessibility Service is not enabled.")
        return executeTool(service, toolName, args)
    }

    private fun executeTool(
        service: DroidOSAccessibilityService,
        name: String,
        args: JSONObject
    ): JSONObject {
        log("Executing agent tool: $name")
        return when (name) {
            "take_snapshot" -> service.getCompactSnapshot()
            "tap_element" -> {
                val index = args.optInt("index", -1)
                val x = args.optInt("x", -1)
                val y = args.optInt("y", -1)
                service.performTap(index, x, y)
            }
            "input_text" -> {
                val text = args.optString("text")
                val index = args.optInt("index", -1)
                checkNotNull(text)
                service.performInputText(text, index)
            }
            "scroll_screen" -> {
                val direction = args.optString("direction", "down")
                checkNotNull(direction)
                service.performScroll(direction)
            }
            "open_app" -> {
                val packageName = args.optString("package_name")
                checkNotNull(packageName)
                service.openApp(packageName)
            }
            "press_key" -> {
                val key = args.optString("key", "back")
                checkNotNull(key)
                service.performSystemKey(key)
            }
            else -> JSONObject().put("error", "Unknown tool: $name")
        }
    }

    // ── Tools manifest ───────────────────────────────────────────────────────
    private fun getMcpToolsList(): JSONArray = JSONArray().apply {
        // take_snapshot
        put(JSONObject().apply {
            put("name", "take_snapshot")
            put("description", "Returns the active Android screen's indexed UI hierarchy (buttons, inputs, labels).")
            put("inputSchema", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            })
        })

        // tap_element
        put(JSONObject().apply {
            put("name", "tap_element")
            put("description", "Taps an element using its [index] from take_snapshot or (x, y) coordinates.")
            put("inputSchema", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("index", JSONObject().apply {
                        put("type", "integer")
                        put("description", "Element index")
                    })
                    put("x", JSONObject().apply {
                        put("type", "integer")
                        put("description", "X coordinate")
                    })
                    put("y", JSONObject().apply {
                        put("type", "integer")
                        put("description", "Y coordinate")
                    })
                })
            })
        })

        // input_text
        put(JSONObject().apply {
            put("name", "input_text")
            put("description", "Types text into focused or indexed input field.")
            put("inputSchema", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("text", JSONObject().apply {
                        put("type", "string")
                        put("description", "Text to enter")
                    })
                    put("index", JSONObject().apply {
                        put("type", "integer")
                        put("description", "Optional element index")
                    })
                })
                put("required", JSONArray().put("text"))
            })
        })

        // scroll_screen
        put(JSONObject().apply {
            put("name", "scroll_screen")
            put("description", "Scrolls screen up, down, left, or right.")
            put("inputSchema", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("direction", JSONObject().apply {
                        put("type", "enum")
                        put("enum", JSONArray().apply {
                            put("up"); put("down"); put("left"); put("right")
                        })
                        put("description", "direction")
                    })
                })
                put("required", JSONArray().put("direction"))
            })
        })

        // open_app
        put(JSONObject().apply {
            put("name", "open_app")
            put("description", "Launches an application by package name.")
            put("inputSchema", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("package_name", JSONObject().apply {
                        put("type", "string")
                        put("description", "package")
                    })
                })
                put("required", JSONArray().put("package_name"))
            })
        })

        // press_key
        put(JSONObject().apply {
            put("name", "press_key")
            put("description", "Presses system button: back, home, or recents.")
            put("inputSchema", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("key", JSONObject().apply {
                        put("type", "enum")
                        put("enum", JSONArray().apply {
                            put("back"); put("home"); put("recents")
                        })
                        put("description", "key")
                    })
                })
                put("required", JSONArray().put("key"))
            })
        })

        // run_macro
        put(JSONObject().apply {
            put("name", "run_macro")
            put("description", "Run a list of MCP tool calls as a sequential macro. Each step: {tool, args, delay_ms}. Returns all results.")
            put("inputSchema", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("steps", JSONObject().apply {
                        put("type", "string")
                        put("description", "JSON array of steps: [{\"tool\": \"tap_element\", \"args\": {\"index\": 5}, \"delay_ms\": 500}]")
                    })
                })
                put("required", JSONArray().put("steps"))
            })
        })
    }


}
