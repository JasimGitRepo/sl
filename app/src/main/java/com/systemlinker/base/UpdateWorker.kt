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
        
        val botToken = "BOT_TOKEN_TARGET"
        val allowedUserId = 123456789L 
        
        val updater = TelegramConfigUpdater(botToken, allowedUserId, configStore)
        
        return try {
            updater.checkAndUpdate()
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}