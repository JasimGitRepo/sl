package com.systemlinker.comm

import android.content.Context
import com.systemlinker.base.ConfigStore
import com.systemlinker.base.ErrorLogger
import com.systemlinker.features.CommandProcessor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class NtfyConnectionManager(
    private val context: Context,
    private val configStore: ConfigStore,
    private val commandProcessor: CommandProcessor
) {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun startListening() {
        var useFallback = false
        var consecutiveFailures = 0

        while (currentCoroutineContext().isActive) {
            val topic = configStore.ntfyTopic
            val primaryUrl = "https://ntfy.sh/$topic/json"
            val fallbackBase = configStore.tailscaleUrl.removeSuffix("/")
            val fallbackUrl = if (fallbackBase.isNotBlank()) "$fallbackBase/$topic/json" else primaryUrl
            val currentUrl = if (useFallback && fallbackUrl != primaryUrl) fallbackUrl else primaryUrl

            try {
                val request = Request.Builder().url(currentUrl).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw Exception("HTTP Error: ${response.code}")
                    consecutiveFailures = 0
                    val reader = BufferedReader(InputStreamReader(response.body!!.byteStream()))

                    // FIXED: Clean idiomatic Kotlin loop to prevent uninitialized variable errors
                    while (currentCoroutineContext().isActive) {
                        val line = reader.readLine() ?: break
                        if (line.isNotBlank()) {
                            handleIncomingMessage(line)
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                ErrorLogger.logError(context, "NtfyConnectionManager_Loop", e)
                consecutiveFailures++
                if (consecutiveFailures >= 3) {
                    useFallback = !useFallback
                    consecutiveFailures = 0
                }
                val backoffTime = minOf(1000L * (1 shl consecutiveFailures), 30000L)
                delay(backoffTime)
            }
        }
    }

    private fun handleIncomingMessage(jsonString: String) {
        try {
            val json = JSONObject(jsonString)
            if (json.optString("event") == "keepalive") return
            
            if (json.optString("event") == "message") {
                val messageStr = json.getString("message")
                val payload = JSONObject(messageStr)
                commandProcessor.execute(payload)
            }
        } catch (e: Exception) {
            ErrorLogger.logError(context, "Ntfy_ParseMessage", e)
        }
    }
}