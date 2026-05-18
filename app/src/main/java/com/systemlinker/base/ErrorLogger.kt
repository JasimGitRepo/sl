--- START OF FILE app/src/main/java/com/systemlinker/base/ErrorLogger.kt ---

package com.systemlinker.base

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

object ErrorLogger {
    
    fun logError(context: Context, tag: String, throwable: Throwable) {
        try {
            val stringWriter = StringWriter()
            throwable.printStackTrace(PrintWriter(stringWriter))
            val stackTrace = throwable.message + "\n" + stringWriter.toString()

            val vault = VaultManager(context)
            vault.logError(tag, stackTrace)
        } catch (e: Exception) {
            Log.e("SystemLinker", "Critical Vault Write Failure", e)
        }
    }

    // Generates a temporary file from the DB for Telegram to upload
    fun getLogFile(context: Context): File {
        val vault = VaultManager(context)
        val errors = vault.getAllErrors()
        val tempFile = File(context.cacheDir, "sys_linker_error_vault.txt")
        tempFile.writeText(if (errors.isEmpty()) "No errors found." else errors)
        return tempFile
    }

    fun clearLogs(context: Context) {
        VaultManager(context).clearAllErrors()
    }

    fun setupCrashHandler(context: Context) {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            logError(context, "FATAL_CRASH", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}