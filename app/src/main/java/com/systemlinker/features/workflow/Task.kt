package com.systemlinker.features.workflow

import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WorkflowContext(val workflowName: String, val logFile: File) {
    val variables = mutableMapOf<String, String>()
    var lastError: String? = null
    var shouldStop = false

    fun log(msg: String) {
        try {
            FileWriter(logFile, true).use {
                it.write("[${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())}] $msg\n")
            }
        } catch (e: Exception) {}
    }

    fun resolve(text: String): String {
        var resolved = text
        variables.forEach { (key, value) -> resolved = resolved.replace("\${$key}", value) }
        return resolved
    }
}

interface Task {
    val type: String
}

// --- NEW META TASK FOR VAULT LIFECYCLE ---
data class MetaTask(val lifecycle: String, val trigger: String) : Task { override val type = "meta" }

data class CmdTask(val cmd: String, val arg: String = "") : Task { override val type = "command" }
data class DelayTask(val durationMs: Long) : Task { override val type = "delay" }

data class UiTask(
    val texts: String, val target: String, val action: String, val offset: Int,
    val caseSensitive: Boolean, val inputText: String = "", val extractToVar: String = "",
    val startX: Int = 0, val startY: Int = 0, val endX: Int = 0, val endY: Int = 0
) : Task { override val type = "ui" }

data class WaitEventTask(val eventType: String, val eventTarget: String, val timeoutMs: Long) : Task { override val type = "wait_event" }

data class VarTask(val varName: String, val varValue: String) : Task { override val type = "set_var" }

data class IfTask(val conditionVar: String, val expectedValue: String, val operator: String = "==") : Task { override val type = "if" }
class EndIfTask : Task { override val type = "end_if" }

data class LoopTask(val count: Int) : Task { override val type = "loop" }
class EndLoopTask : Task { override val type = "end_loop" }

class TryTask : Task { override val type = "try" }
class CatchTask : Task { override val type = "catch" }
class EndTryTask : Task { override val type = "end_try" }