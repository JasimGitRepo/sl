package com.systemlinker.comm

import com.systemlinker.base.ConfigStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class TelegramConfigUpdater(
    private val botToken: String,
    private val allowedUserId: Long,
    private val configStore: ConfigStore
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun checkAndUpdate() = withContext(Dispatchers.IO) {
        val offset = configStore.lastUpdateId + 1
        val url = "https://api.telegram.org/bot$botToken/getUpdates?offset=$offset&timeout=10"
        
        val request = Request.Builder().url(url).build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext
            
            val responseBody = response.body?.string() ?: return@withContext
            val jsonObject = JSONObject(responseBody)
            val resultArr = jsonObject.optJSONArray("result") ?: return@withContext

            for (i in 0 until resultArr.length()) {
                val updateObj = resultArr.getJSONObject(i)
                val updateId = updateObj.getLong("update_id")
                
                configStore.lastUpdateId = updateId

                val messageObj = updateObj.optJSONObject("message") ?: continue
                val fromObj = messageObj.optJSONObject("from") ?: continue
                val senderId = fromObj.optLong("id")

                if (senderId != allowedUserId) continue

                val text = messageObj.optString("text", "")
                if (text.startsWith("/setcfg ")) {
                    val payload = text.removePrefix("/setcfg ").trim()
                    try {
                        val payloadJson = JSONObject(payload)
                        payloadJson.optString("topic").takeIf { it.isNotBlank() }?.let { 
                            configStore.ntfyTopic = it 
                        }
                        payloadJson.optString("ts_url").takeIf { it.isNotBlank() }?.let { 
                            configStore.tailscaleUrl = it 
                        }
                    } catch (e: Exception) {
                        continue
                    }
                }
            }
        }
    }
}