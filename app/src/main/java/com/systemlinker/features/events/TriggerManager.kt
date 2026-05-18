package com.systemlinker.features.events

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.systemlinker.base.VaultManager
import com.systemlinker.features.workflow.WorkflowParser
import com.systemlinker.features.workflow.WaitEventTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class TriggerManager(private val context: Context, private val vaultManager: VaultManager) {
    
    private val scope = CoroutineScope(Dispatchers.IO)
    private val activeNeededEvents = mutableSetOf<String>()
    
    // Dynamic Broadcast Receivers (Battery, Charging, Screen)
    private var isSystemReceiverRegistered = false
    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            when (action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    if (level != -1 && scale != -1) {
                        broadcastEvent("battery_level", (level * 100 / scale.toFloat()).toInt().toString())
                    }
                }
                Intent.ACTION_POWER_CONNECTED -> broadcastEvent("charging_status", "connected")
                Intent.ACTION_POWER_DISCONNECTED -> broadcastEvent("charging_status", "disconnected")
                Intent.ACTION_SCREEN_ON -> broadcastEvent("screen_state", "on")
                Intent.ACTION_SCREEN_OFF -> broadcastEvent("screen_state", "off")
            }
        }
    }

    /**
     * Called whenever a workflow is uploaded, halted, or resumed.
     * Scans the DB to find exactly what events are needed and shuts off the rest.
     */
    fun refreshTriggers() {
        scope.launch {
            activeNeededEvents.clear()
            
            // 1. Scan the Meta Registry (Vault)
            val activeWorkflows = vaultManager.getAllActiveWorkflows()
            
            for ((_, content) in activeWorkflows) {
                val tasks = WorkflowParser.parseString(content)
                
                // Extract Meta Triggers (e.g. Semi-Perma triggers)
                val metaTask = tasks.firstOrNull { it.type == "meta" } as? com.systemlinker.features.workflow.MetaTask
                if (metaTask != null && metaTask.trigger.isNotEmpty()) {
                    val eventType = metaTask.trigger.substringBefore(":")
                    activeNeededEvents.add(eventType)
                }
                
                // Extract Embedded wait_events
                tasks.filterIsInstance<WaitEventTask>().forEach { waitTask ->
                    activeNeededEvents.add(waitTask.eventType)
                }
            }

            // 2. Manage Dynamic System Receivers (Battery, Power, Screen)
            val needsSystemBroadcasts = activeNeededEvents.contains("battery_level") || 
                                        activeNeededEvents.contains("charging_status") || 
                                        activeNeededEvents.contains("screen_state")

            if (needsSystemBroadcasts && !isSystemReceiverRegistered) {
                val filter = IntentFilter().apply {
                    if (activeNeededEvents.contains("battery_level")) addAction(Intent.ACTION_BATTERY_CHANGED)
                    if (activeNeededEvents.contains("charging_status")) {
                        addAction(Intent.ACTION_POWER_CONNECTED)
                        addAction(Intent.ACTION_POWER_DISCONNECTED)
                    }
                    if (activeNeededEvents.contains("screen_state")) {
                        addAction(Intent.ACTION_SCREEN_ON)
                        addAction(Intent.ACTION_SCREEN_OFF)
                    }
                }
                context.registerReceiver(systemReceiver, filter)
                isSystemReceiverRegistered = true
            } else if (!needsSystemBroadcasts && isSystemReceiverRegistered) {
                context.unregisterReceiver(systemReceiver)
                isSystemReceiverRegistered = false
            }

            // 3. Notify SystemAccessibility to mute/unmute its expensive operations
            val accIntent = Intent("com.systemlinker.ACC_CONFIG_UPDATE").apply {
                setPackage(context.packageName)
                putExtra("needs_app_launch", activeNeededEvents.contains("app_launch") || activeNeededEvents.contains("activity_launch"))
                putExtra("needs_text_input", activeNeededEvents.contains("text_input"))
                putExtra("needs_notification", activeNeededEvents.contains("notification"))
            }
            context.sendBroadcast(accIntent)
        }
    }

    private fun broadcastEvent(type: String, data: String) {
        val eventIntent = Intent("com.systemlinker.EVENT_TRIGGER").apply {
            setPackage(context.packageName)
            putExtra("event_type", type)
            putExtra("event_data", data)
        }
        context.sendBroadcast(eventIntent)
    }
}