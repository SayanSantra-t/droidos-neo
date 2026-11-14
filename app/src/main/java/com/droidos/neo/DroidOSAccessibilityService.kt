package com.droidos.neo

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

class DroidOSAccessibilityService : AccessibilityService() {

    companion object {
        var instance: DroidOSAccessibilityService? = null
            private set
        var lastLogCallback: ((String) -> Unit)? = null
    }

    // ── Data model ──────────────────────────────────────────────────────────
    data class ElementData(
        val index: Int,
        val className: String,
        val text: String,
        val desc: String,
        val id: String,
        val bounds: Rect,
        val center: Pair<Int, Int>,
        val clickable: Boolean
    )

    private var cachedElements: List<ElementData> = ArrayList()
    private var mcpServer: McpHttpServer? = null

    // ── Lifecycle ────────────────────────────────────────────────────────────
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        log("DroidOS Accessibility Service connected.")

        mcpServer = McpHttpServer().apply {
            setOnLogMessage { msg -> log(msg) }
            start()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanup()
        log("DroidOS Accessibility Service destroyed.")
    }

    override fun onInterrupt() {
        log("DroidOS Accessibility Service interrupted.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No-op; we pull state on demand via getCompactSnapshot.
    }

    private fun cleanup() {
        mcpServer?.stop()
        mcpServer = null
        instance = null
    }

    // ── Logging ──────────────────────────────────────────────────────────────
    private fun log(msg: String) {
        lastLogCallback?.invoke(msg)
    }

    // ── Snapshot ─────────────────────────────────────────────────────────────
    fun getCompactSnapshot(): JSONObject {
        val root = rootInActiveWindow
            ?: return JSONObject().put("error", "No active window found")

        val elements = JSONArray()
        val formattedTree = StringBuilder()
        val counter = intArrayOf(0)
        traverse(counter, elements, formattedTree, root, 0)

        cachedElements = buildList {
            for (i in 0 until elements.length()) {
                val obj = elements.getJSONObject(i)
                val boundsArr = obj.optJSONArray("bounds")
                val rect = if (boundsArr != null) {
                    Rect(
                        boundsArr.getInt(0),
                        boundsArr.getInt(1),
                        boundsArr.getInt(2),
                        boundsArr.getInt(3)
                    )
                } else Rect()
                val centerX = (rect.left + rect.right) / 2
                val centerY = (rect.top + rect.bottom) / 2
                add(
                    ElementData(
                        index = obj.getInt("index"),
                        className = obj.optString("class", ""),
                        text = obj.optString("text", ""),
                        desc = obj.optString("desc", ""),
                        id = obj.optString("id", ""),
                        bounds = rect,
                        center = Pair(centerX, centerY),
                        clickable = obj.optBoolean("clickable", false)
                    )
                )
            }
        }

        val packageName = root.packageName?.toString() ?: ""
        log("Agent snapshot requested ($packageName -> ${cachedElements.size} elements)")

        return JSONObject().apply {
            put("package", packageName)
            put("elementsCount", cachedElements.size)
            put("elements", elements)
            put("formatted_tree", formattedTree.toString())
        }
    }

    private fun traverse(
        counter: IntArray,
        elements: JSONArray,
        sb: StringBuilder,
        node: AccessibilityNodeInfo,
        depth: Int
    ) {
        val rect = Rect()
        node.getBoundsInScreen(rect)

        val rawText = node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: ""
        val rawDesc = node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: ""
        val className = node.className?.toString() ?: ""
        val resourceId = node.viewIdResourceName ?: ""
        val clickable = node.isClickable
        val idx = counter[0]++

        val centerX = (rect.left + rect.right) / 2
        val centerY = (rect.top + rect.bottom) / 2

        val indent = "  ".repeat(depth)
        val tag = when {
            rawText.isNotEmpty() -> rawText
            rawDesc.isNotEmpty() -> rawDesc
            else -> className.substringAfterLast('.')
        }
        val clickTag = if (clickable) " [clickable]" else ""
        sb.append("$indent[$idx] $tag$clickTag\n")

        val elemObj = JSONObject().apply {
            put("index", idx)
            put("class", className)
            put("text", rawText)
            put("desc", rawDesc)
            put("id", resourceId)
            put("bounds", JSONArray().apply {
                put(rect.left); put(rect.top); put(rect.right); put(rect.bottom)
            })
            put("center", JSONArray().apply { put(centerX); put(centerY) })
            put("clickable", clickable)
        }
        elements.put(elemObj)

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverse(counter, elements, sb, child, depth + 1)
        }
    }

    // ── Tool implementations ─────────────────────────────────────────────────

    fun performTap(index: Int, x: Int, y: Int): JSONObject {
        val tapX: Int
        val tapY: Int

        if (index >= 0 && index < cachedElements.size) {
            val el = cachedElements[index]
            tapX = el.center.first
            tapY = el.center.second
            log("Tapped @ ($tapX, $tapY)")
        } else if (x >= 0 && y >= 0) {
            tapX = x
            tapY = y
            log("Tapped @ ($tapX, $tapY)")
        } else {
            return JSONObject().put("error", "Invalid coordinates or element index")
        }

        val path = Path().apply { moveTo(tapX.toFloat(), tapY.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 100)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)

        return JSONObject().put("success", "Tapped @ ($tapX, $tapY)")
    }

    fun performScroll(direction: String): JSONObject {
        val (startX, startY, endX, endY) = when (direction.lowercase()) {
            "up"    -> listOf(500, 1200, 500, 400)
            "down"  -> listOf(500, 400, 500, 1200)
            "left"  -> listOf(900, 600, 200, 600)
            "right" -> listOf(200, 600, 900, 600)
            else    -> listOf(500, 400, 500, 1200)
        }

        val path = Path().apply {
            moveTo(startX.toFloat(), startY.toFloat())
            lineTo(endX.toFloat(), endY.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 300)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)

        return JSONObject().put("success", "Scrolled $direction")
    }

    fun performInputText(text: String, index: Int = -1): JSONObject {
        val targetNode: AccessibilityNodeInfo? = if (index >= 0 && index < cachedElements.size) {
            val el = cachedElements[index]
            findNodeByBounds(rootInActiveWindow, el.bounds)
        } else {
            findFocusedInput()
        }

        if (targetNode == null) {
            return JSONObject().put("error", "No focused input field on screen")
        }

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

        return JSONObject().put("success", "Typed: $text")
    }

    private fun findFocusedInput(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
    }

    private fun findNodeByBounds(root: AccessibilityNodeInfo?, target: Rect): AccessibilityNodeInfo? {
        root ?: return null
        val rect = Rect()
        root.getBoundsInScreen(rect)
        if (rect == target) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findNodeByBounds(child, target)
            if (found != null) return found
        }
        return null
    }

    fun performSystemKey(key: String): JSONObject {
        return when (key.lowercase()) {
            "back" -> {
                performGlobalAction(GLOBAL_ACTION_BACK)
                JSONObject().put("success", "Triggered system key: back")
            }
            "home" -> {
                performGlobalAction(GLOBAL_ACTION_HOME)
                JSONObject().put("success", "Triggered system key: home")
            }
            "recents" -> {
                performGlobalAction(GLOBAL_ACTION_RECENTS)
                JSONObject().put("success", "Triggered system key: recents")
            }
            else -> JSONObject().put("error", "Unsupported key: $key")
        }
    }

    fun openApp(packageName: String): JSONObject {
        return try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
                ?: return JSONObject().put("error", "App not found: $packageName")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            log("Launched app: $packageName")
            JSONObject().put("success", "Launched app: $packageName")
        } catch (e: Exception) {
            JSONObject().put("error", e.message ?: "Unknown error")
        }
    }
}
