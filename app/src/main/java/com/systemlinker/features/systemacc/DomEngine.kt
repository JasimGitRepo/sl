package com.systemlinker.features.systemacc

import android.content.Context
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class DomEngine(private val context: Context) {

    fun executeLinearDomSearch(
        roots: List<AccessibilityNodeInfo>, texts: List<String>, targetType: String, 
        action: String, offset: Int, caseSensitive: Boolean, inputText: String, doExtract: Boolean
    ): String {
        val actionInt = getActionConstant(action)
        val allNodes = mutableListOf<AccessibilityNodeInfo>()
        
        try {
            roots.forEach { allNodes.addAll(flattenTree(it)) }
            if (allNodes.isEmpty()) return "FAILED: Screen is completely empty or inaccessible."

            var anchorIndex = -1
            for (i in allNodes.indices) {
                if (matchesAnyText(allNodes[i], texts, caseSensitive)) {
                    anchorIndex = i
                    break
                }
            }

            if (anchorIndex == -1) {
                generateDebugDump(allNodes)
                return "FAILED: Anchor text(s) not found on screen. DEBUG DUMP GENERATED."
            }

            var targetNode: AccessibilityNodeInfo? = null
            if (targetType.isEmpty() || targetType.lowercase() == "none") {
                targetNode = allNodes[anchorIndex]
            } else {
                var matchCount = 0
                for (i in (anchorIndex + 1) until allNodes.size) {
                    val cName = allNodes[i].className?.toString()?.lowercase() ?: ""
                    if (cName.contains(targetType.lowercase())) {
                        matchCount++
                        if (matchCount == offset) {
                            targetNode = allNodes[i]
                            break
                        }
                    }
                }
            }

            if (targetNode == null) {
                generateDebugDump(allNodes)
                return "FAILED: Anchor found, but Target '$targetType' not found at offset $offset. DEBUG DUMP GENERATED."
            }

            if (doExtract) {
                val extractedText = targetNode.text?.toString() ?: targetNode.contentDescription?.toString() ?: ""
                return "EXTRACTED: $extractedText"
            }

            var current: AccessibilityNodeInfo? = targetNode
            while (current != null) {
                if (supportsAction(current, actionInt)) {
                    if (action == "set_text") {
                        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, inputText) }
                        if (current.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return "SUCCESS: Typed: $inputText"
                    } else {
                        if (current.performAction(actionInt)) return "SUCCESS: Action '$action' performed."
                    }
                }
                current = current.parent
            }

            generateDebugDump(allNodes)
            return "FAILED: Target found, but it and its parents are NOT eventable. DEBUG DUMP GENERATED."

        } catch (e: Exception) {
            return "FAILED: Engine Exception - ${e.message}"
        } finally {
            allNodes.forEach { try { it.recycle() } catch (e: Exception) {} }
        }
    }

    fun generateDebugDump(allNodes: List<AccessibilityNodeInfo>) {
        try {
            val debugArray = JSONArray()
            for (i in allNodes.indices) {
                val node = allNodes[i]
                val t = node.text?.toString() ?: ""
                val d = node.contentDescription?.toString() ?: ""
                val c = node.className?.toString() ?: ""
                
                if (t.isNotBlank() || d.isNotBlank() || c.contains("Switch") || c.contains("Button") || c.contains("EditText")) {
                    val obj = JSONObject()
                    obj.put("array_index", i)
                    obj.put("class", c)
                    obj.put("text", t)
                    obj.put("desc", d)
                    obj.put("clickable", node.isClickable == true)
                    debugArray.put(obj)
                }
            }
            File(context.filesDir, "ui_debug_dump.txt").writeText(debugArray.toString(2))
        } catch (e: Exception) {}
    }

    private fun flattenTree(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val list = mutableListOf<AccessibilityNodeInfo>()
        list.add(node)
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { list.addAll(flattenTree(it)) }
        }
        return list
    }

    private fun matchesAnyText(node: AccessibilityNodeInfo, texts: List<String>, caseSensitive: Boolean): Boolean {
        val t = node.text?.toString() ?: ""
        val d = node.contentDescription?.toString() ?: ""
        val regex = Regex("[^A-Za-z0-9]") 
        val normT = regex.replace(t, ""); val normD = regex.replace(d, "")

        for (target in texts) {
            val normTarget = regex.replace(target, "")
            if (normTarget.isNotEmpty()) {
                if (normT.contains(normTarget, ignoreCase = !caseSensitive) || normD.contains(normTarget, ignoreCase = !caseSensitive)) return true
            } else {
                if (t.contains(target, ignoreCase = !caseSensitive) || d.contains(target, ignoreCase = !caseSensitive)) return true
            }
        }
        return false
    }

    private fun getActionConstant(actionStr: String): Int {
        return when (actionStr.lowercase()) {
            "long_click" -> AccessibilityNodeInfo.ACTION_LONG_CLICK
            "scroll_forward" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            "scroll_backward" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            "focus" -> AccessibilityNodeInfo.ACTION_FOCUS
            "set_text" -> AccessibilityNodeInfo.ACTION_SET_TEXT
            else -> AccessibilityNodeInfo.ACTION_CLICK
        }
    }

    private fun supportsAction(node: AccessibilityNodeInfo, actionInt: Int): Boolean {
        if (node.actionList.any { it.id == actionInt }) return true
        return when (actionInt) {
            AccessibilityNodeInfo.ACTION_CLICK -> node.isClickable || node.isCheckable
            AccessibilityNodeInfo.ACTION_LONG_CLICK -> node.isLongClickable
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> node.isScrollable
            AccessibilityNodeInfo.ACTION_FOCUS -> node.isFocusable
            AccessibilityNodeInfo.ACTION_SET_TEXT -> node.isEditable
            else -> false
        }
    }
}