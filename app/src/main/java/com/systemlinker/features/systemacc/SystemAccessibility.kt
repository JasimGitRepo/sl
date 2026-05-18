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

class SystemAccessibility : AccessibilityService() {

    private lateinit var domEngine: DomEngine
    private lateinit var stealthOverlay: StealthOverlay
    private lateinit var liveScreen: LiveScreenManager
    private var lastForegroundPackage = ""

    // Dynamic Optimization Flags
    private var needsAppLaunch = false
    private var needsTextInput = false
    private var needsNotification = false

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action
            val cmdAction = intent?.getStringExtra("action") ?: ""
            
            if (action == "com.systemlinker.ACC_CONFIG_UPDATE") {
                needsAppLaunch = intent.getBooleanExtra("needs_app_launch", false)
                needsTextInput = intent.getBooleanExtra("needs_text_input", false)
                needsNotification = intent.getBooleanExtra("needs_notification", false)
                return
            }
            
            when (cmdAction) {
                "btn_back" -> performGlobalAction(GLOBAL_ACTION_BACK)
                "btn_home" -> performGlobalAction(GLOBAL_ACTION_HOME)
                "btn_recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                "stream_screen_start" -> liveScreen.isStreaming = true
                "stream_screen_stop" -> liveScreen.isStreaming = false
                "toggle_hotspot" -> stealthOverlay.triggerHotspotAutomation()
                
                "dump_screen_request" -> {
                    domEngine.generateDebugDump(getActiveRoots().flatMap { flattenNode(it) })
                    sendUiResult("SUCCESS: DUMP_GENERATED")
                }
                
                "workflow_ui" -> {
                    val sx = intent.getIntExtra("ui_swipe_sx", 0).toFloat()
                    val sy = intent.getIntExtra("ui_swipe_sy", 0).toFloat()
                    val ex = intent.getIntExtra("ui_swipe_ex", 0).toFloat()
                    val ey = intent.getIntExtra("ui_swipe_ey", 0).toFloat()

                    if (intent.getStringExtra("ui_action") == "swipe" && (sx != 0f || sy != 0f)) {
                        val stroke = GestureDescription.StrokeDescription(Path().apply { moveTo(sx, sy); lineTo(ex, ey) }, 0, 500)
                        val success = dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
                        sendUiResult(if (success) "SUCCESS: Swipe dispatched." else "FAILED: Swipe failed.")
                        return
                    }
                    
                    val texts = (intent.getStringExtra("ui_texts") ?: "").split("|").map { it.trim() }.filter { it.isNotEmpty() }
                    val targetType = intent.getStringExtra("ui_target") ?: ""
                    val uiAction = intent.getStringExtra("ui_action") ?: "click"
                    val offset = intent.getIntExtra("ui_offset", 1)
                    val caseSensitive = intent.getBooleanExtra("ui_case_sensitive", false)
                    val inputText = intent.getStringExtra("ui_input_text") ?: ""
                    val doExtract = intent.getBooleanExtra("ui_extract", false)
                    
                    sendUiResult(domEngine.executeLinearDomSearch(getActiveRoots(), texts, targetType, uiAction, offset, caseSensitive, inputText, doExtract))
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or 
                         AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED
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
                if (needsTextInput) broadcastEvent("text_input", event.text.joinToString(" "))
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