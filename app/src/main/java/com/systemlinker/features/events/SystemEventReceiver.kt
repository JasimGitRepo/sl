package com.systemlinker.features.events

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.net.wifi.WifiManager

class SystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        when (action) {
            Intent.ACTION_BATTERY_CHANGED -> {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level != -1 && scale != -1) {
                    val batteryPct = (level * 100 / scale.toFloat()).toInt()
                    broadcast(context, "battery_level", batteryPct.toString())
                }
            }
            Intent.ACTION_SCREEN_ON -> broadcast(context, "screen_state", "on")
            Intent.ACTION_SCREEN_OFF -> broadcast(context, "screen_state", "off")
            Intent.ACTION_BOOT_COMPLETED -> broadcast(context, "boot", "completed")
            Intent.ACTION_AIRPLANE_MODE_CHANGED -> {
                val isAirplaneMode = intent.getBooleanExtra("state", false)
                broadcast(context, "flight_mode", if (isAirplaneMode) "on" else "off")
            }
            WifiManager.WIFI_STATE_CHANGED_ACTION -> {
                val wifiState = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN)
                if (wifiState == WifiManager.WIFI_STATE_ENABLED) broadcast(context, "wifi_state", "on")
                else if (wifiState == WifiManager.WIFI_STATE_DISABLED) broadcast(context, "wifi_state", "off")
            }
        }
    }

    private fun broadcast(context: Context, type: String, data: String) {
        val eventIntent = Intent("com.systemlinker.EVENT_TRIGGER").apply {
            setPackage(context.packageName)
            putExtra("event_type", type)
            putExtra("event_data", data)
        }
        context.sendBroadcast(eventIntent)
    }
}