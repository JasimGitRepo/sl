package com.systemlinker.features.workflow

interface Task {
    val type: String
}

data class MetaTask(
    val lifecycle: String, 
    val trigger: String,
    val abortOnError: Boolean = true,
    val taskDelayMs: Long = 1000L
) : Task { override val type = "meta" }

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