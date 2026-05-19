package com.systemlinker.features.workflow

class WorkflowContext(val workflowName: String, private val logger: (String) -> Unit) {
    val variables = mutableMapOf<String, String>()
    var lastError: String? = null
    var shouldStop = false
    var stepCount = 0

    fun log(msg: String) {
        logger(msg)
    }

    fun resolve(text: String): String {
        var resolved = text
        variables.forEach { (key, value) -> resolved = resolved.replace("\${$key}", value) }
        return resolved
    }

    fun dumpVariables() {
        log("--- Current State of Variables ---")
        if (variables.isEmpty()) {
            log("  (No variables currently set)")
        } else {
            variables.forEach { (k, v) -> log("  $$k = $v") }
        }
        log("----------------------------------")
    }
}