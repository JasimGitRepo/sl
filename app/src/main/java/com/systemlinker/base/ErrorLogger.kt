package com.systemlinker.base

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ErrorLogger {
    private const val LOG_FILE_NAME = "sys_linker_log.txt"

    fun logError(context: Context, tag: String, throwable: Throwable) {
        try {
            val logFile = File(context.filesDir, LOG_FILE_NAME)
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            
            val stringWriter = StringWriter()
            throwable.printStackTrace(PrintWriter(stringWriter))
            val stackTrace = stringWriter.toString()

            FileWriter(logFile, true).use { writer ->
                writer.append("\n[$timestamp] [$tag] ERROR:\n")
                writer.append(throwable.message ?: "Unknown Error")
                writer.append("\n")
                writer.append(stackTrace)
                writer.append("--------------------------------------------------\n")
            }
        } catch (e: Exception) {
            Log.e("SystemLinker", "Failed to write to local log file", e)
        }
    }

    fun getLogFile(context: Context): File {
        return File(context.filesDir, LOG_FILE_NAME)
    }

    fun clearLogs(context: Context) {
        val logFile = File(context.filesDir, LOG_FILE_NAME)
        if (logFile.exists()) {
            logFile.delete()
        }
    }

    fun setupCrashHandler(context: Context) {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            logError(context, "FATAL_CRASH", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}