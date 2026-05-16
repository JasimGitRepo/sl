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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class SystemLinkerService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    
    private lateinit var configStore: ConfigStore
    private lateinit var ntfyManager: NtfyConnectionManager

    companion object {
        private const val CHANNEL_ID = "system_linker_sync_channel"
        private const val NOTIFICATION_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        ErrorLogger.setupCrashHandler(applicationContext)
        
        configStore = ConfigStore(this)
        
        val commandProcessor = CommandProcessor(this, Constants.TELEGRAM_BOT_TOKEN, Constants.TELEGRAM_ADMIN_USER_ID)
        ntfyManager = NtfyConnectionManager(this, configStore, commandProcessor)

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        serviceScope.launch { ntfyManager.startListening() }
    }

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
                CHANNEL_ID,
                "Data Sync Service",
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
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("System Linking")
            .setContentText("Maintaining local state.")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
    }
}