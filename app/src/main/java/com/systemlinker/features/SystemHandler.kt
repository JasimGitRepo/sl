package com.systemlinker.features

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.BatteryManager
import com.systemlinker.base.ErrorLogger
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.tasks.await

class SystemHandler(private val context: Context) {

    @SuppressLint("MissingPermission")
    suspend fun getLocation(): String {
        return try {
            val client = LocationServices.getFusedLocationProviderClient(context)
            val location = client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await()
            if (location != null) {
                "`Location:` ${location.latitude}, ${location.longitude}\n`Accuracy:` ${location.accuracy}m"
            } else {
                "Location unavailable."
            }
        } catch (e: Exception) {
            "Location failed: ${e.message}"
        }
    }

    fun getBatteryStatus(): String {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val isCharging = bm.isCharging
        return "`Battery:` $level% (${if (isCharging) "Charging" else "Discharging"})"
    }

    fun setFlashlight(enable: Boolean): String {
        return try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val flashCamId = manager.cameraIdList.firstOrNull { 
                manager.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true 
            }
            if (flashCamId != null) {
                manager.setTorchMode(flashCamId, enable)
                "Flashlight ${if (enable) "ON" else "OFF"}"
            } else {
                "No flashlight found."
            }
        } catch (e: Exception) {
            "Flashlight error: ${e.message}"
        }
    }

    fun setVolume(levelPercent: Int) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val target = (max * (levelPercent / 100f)).toInt()
            am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        } catch (e: Exception) {
            ErrorLogger.logError(context, "SH_setVolume", e)
        }
    }
}