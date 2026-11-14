package com.droidos.neo

import org.json.JSONArray
import org.json.JSONObject

/**
 * MacroRunner – executes a list of MCP tool calls sequentially.
 *
 * Input JSON format:
 * [
 *   {"tool": "tap_element", "args": {"index": 5}, "delay_ms": 500},
 *   {"tool": "input_text",  "args": {"text": "hello"}},
 *   {"tool": "take_snapshot", "args": {}}
 * ]
 *
 * Returns a JSON array of per-step results:
 * [{"step": 0, "tool": "tap_element", "result": {...}}, ...]
 * On per-step failure the result contains {"error": "..."} and execution continues.
 */
class MacroRunner(private val server: McpHttpServer) {

    fun runMacro(stepsJson: String): String {
        val steps = try {
            JSONArray(stepsJson)
        } catch (e: Exception) {
            return JSONArray().put(
                JSONObject()
                    .put("step", -1)
                    .put("tool", "")
                    .put("result", JSONObject().put("error", "Invalid stepsJson: ${e.message}"))
            ).toString()
        }

        val results = JSONArray()

        for (i in 0 until steps.length()) {
            val step = steps.optJSONObject(i) ?: continue
            val tool    = step.optString("tool", "")
            val args    = step.optJSONObject("args") ?: JSONObject()
            val delayMs = step.optInt("delay_ms", 0)

            val stepResult = JSONObject().put("step", i).put("tool", tool)

            try {
                val toolResult = server.executeToolCall(tool, args)
                stepResult.put("result", toolResult)
            } catch (e: Exception) {
                stepResult.put("result", JSONObject().put("error", e.message ?: "Unknown error"))
            }

            results.put(stepResult)

            if (delayMs > 0) {
                try { Thread.sleep(delayMs.toLong()) } catch (_: InterruptedException) { break }
            }
        }

        return results.toString()
    }
}
