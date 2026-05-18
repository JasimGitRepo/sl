package com.systemlinker.features.workflow

import java.io.File

object WorkflowParser {
    
    fun parse(file: File): List<Task> {
        return parseString(file.readText())
    }

    fun parseString(content: String): List<Task> {
        val tasks = mutableListOf<Task>()
        val lines = content.lines()
        
        var currentMap = mutableMapOf<String, String>()

        fun buildTask() {
            if (currentMap.isEmpty()) return
            val type = currentMap["type"] ?: return
            
            val task: Task? = when (type) {
                "meta" -> MetaTask(currentMap["lifecycle"] ?: "temp", currentMap["trigger"] ?: "")
                "command" -> CmdTask(currentMap["cmd"] ?: "", currentMap["arg"] ?: "")
                "delay" -> DelayTask(currentMap["cmd"]?.toLongOrNull() ?: 1000L)
                "set_var" -> VarTask(currentMap["var"] ?: "", currentMap["value"] ?: "")
                "ui" -> UiTask(
                    texts = currentMap["text"] ?: "",
                    target = currentMap["target"] ?: "",
                    action = currentMap["action"] ?: "click",
                    offset = currentMap["offset"]?.toIntOrNull() ?: 1,
                    caseSensitive = currentMap["case_sensitive"]?.toBooleanStrictOrNull() ?: false,
                    inputText = currentMap["input"] ?: "",
                    extractToVar = currentMap["extract_to"] ?: "",
                    startX = currentMap["startX"]?.toIntOrNull() ?: 0,
                    startY = currentMap["startY"]?.toIntOrNull() ?: 0,
                    endX = currentMap["endX"]?.toIntOrNull() ?: 0,
                    endY = currentMap["endY"]?.toIntOrNull() ?: 0
                )
                "wait_event" -> WaitEventTask(currentMap["event"] ?: "", currentMap["event_target"] ?: "", currentMap["timeout"]?.toLongOrNull() ?: 300000L)
                "if" -> IfTask(currentMap["var"] ?: "", currentMap["value"] ?: "", currentMap["operator"] ?: "==")
                "end_if" -> EndIfTask()
                "loop" -> LoopTask(currentMap["count"]?.toIntOrNull() ?: 1)
                "end_loop" -> EndLoopTask()
                "try" -> TryTask()
                "catch" -> CatchTask()
                "end_try" -> EndTryTask()
                else -> null
            }
            task?.let { tasks.add(it) }
            currentMap.clear()
        }

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("#") || trimmed.isEmpty()) continue
            
            if (trimmed.startsWith("- type:")) {
                buildTask()
                currentMap["type"] = trimmed.substringAfter("type:").trim().replace("\"", "")
            } else if (trimmed.contains(":")) {
                val key = trimmed.substringBefore(":").trim()
                val value = trimmed.substringAfter(":").trim().replace("\"", "")
                currentMap[key] = value
            }
        }
        buildTask() 
        
        return tasks
    }
}