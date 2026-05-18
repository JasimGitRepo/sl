package com.systemlinker.base

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class VaultManager(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "sys_linker_vault.db"
        private const val DATABASE_VERSION = 2

        const val TABLE_WORKFLOWS = "workflows"
        const val TABLE_LOGS = "workflow_logs"
        const val TABLE_ERRORS = "error_logs"

        private const val KEY_ALIAS = "syslinker_vault_key"
    }

    init {
        initCrypto()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE $TABLE_WORKFLOWS (name TEXT PRIMARY KEY, type TEXT, trigger_event TEXT, state TEXT DEFAULT 'active', content_encrypted TEXT)")
        db.execSQL("CREATE TABLE $TABLE_LOGS (id INTEGER PRIMARY KEY AUTOINCREMENT, workflow_name TEXT, timestamp INTEGER, log_data_encrypted TEXT)")
        db.execSQL("CREATE TABLE $TABLE_ERRORS (id INTEGER PRIMARY KEY AUTOINCREMENT, timestamp INTEGER, tag TEXT, error_encrypted TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_WORKFLOWS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_LOGS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_ERRORS")
        onCreate(db)
    }

    private fun initCrypto() {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            keyGenerator.init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            keyGenerator.generateKey()
        }
    }

    private fun getSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return keyStore.getKey(KEY_ALIAS, null) as SecretKey
    }

    private fun encrypt(data: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(data.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv, Base64.NO_WRAP) + "]" + Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(encryptedData: String): String {
        try {
            val parts = encryptedData.split("]")
            if (parts.size != 2) return encryptedData
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val cipherText = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), GCMParameterSpec(128, iv))
            return String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (e: Exception) {
            return "DECRYPTION_FAILED"
        }
    }

    fun saveWorkflow(name: String, type: String, triggerEvent: String, content: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("name", name)
            put("type", type)
            put("trigger_event", triggerEvent)
            put("state", "active")
            put("content_encrypted", encrypt(content))
        }
        db.replace(TABLE_WORKFLOWS, null, values)
    }
    
    fun setWorkflowState(name: String, state: String) {
        val db = writableDatabase
        if (name == "all") {
            val values = ContentValues().apply { put("state", state) }
            db.update(TABLE_WORKFLOWS, values, null, null)
        } else {
            val values = ContentValues().apply { put("state", state) }
            db.update(TABLE_WORKFLOWS, values, "name = ?", arrayOf(name))
        }
    }

    fun getAllActiveWorkflows(): Map<String, String> {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT name, content_encrypted FROM $TABLE_WORKFLOWS WHERE state = 'active'", null)
        val map = mutableMapOf<String, String>()
        while (cursor.moveToNext()) {
            map[cursor.getString(0)] = decrypt(cursor.getString(1))
        }
        cursor.close()
        return map
    }
    
    fun getWorkflow(name: String): String? {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT content_encrypted FROM $TABLE_WORKFLOWS WHERE name = ?", arrayOf(name))
        var content: String? = null
        if (cursor.moveToFirst()) {
            content = decrypt(cursor.getString(0))
        }
        cursor.close()
        return content
    }

    fun deleteWorkflow(name: String) {
        writableDatabase.delete(TABLE_WORKFLOWS, "name = ?", arrayOf(name))
    }

    fun getWorkflowsByTrigger(triggerEvent: String): List<String> {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT name FROM $TABLE_WORKFLOWS WHERE type = 'semi' AND state = 'active' AND trigger_event = ?", arrayOf(triggerEvent))
        val list = mutableListOf<String>()
        while (cursor.moveToNext()) list.add(cursor.getString(0))
        cursor.close()
        return list
    }

    fun appendLog(workflowName: String, logText: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("workflow_name", workflowName)
            put("timestamp", System.currentTimeMillis())
            put("log_data_encrypted", encrypt(logText))
        }
        db.insert(TABLE_LOGS, null, values)
    }

    fun getLogsForWorkflow(workflowName: String): String {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT log_data_encrypted FROM $TABLE_LOGS WHERE workflow_name = ? ORDER BY timestamp ASC", arrayOf(workflowName))
        val sb = StringBuilder()
        while (cursor.moveToNext()) {
            sb.append(decrypt(cursor.getString(0))).append("\n")
        }
        cursor.close()
        return sb.toString()
    }

    fun clearLogs(workflowName: String) {
        writableDatabase.delete(TABLE_LOGS, "workflow_name = ?", arrayOf(workflowName))
    }

    fun logError(tag: String, errorText: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("timestamp", System.currentTimeMillis())
            put("tag", tag)
            put("error_encrypted", encrypt(errorText))
        }
        db.insert(TABLE_ERRORS, null, values)
    }

    fun getAllErrors(): String {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT timestamp, tag, error_encrypted FROM $TABLE_ERRORS ORDER BY timestamp DESC", null)
        val sb = StringBuilder()
        while (cursor.moveToNext()) {
            val ts = cursor.getLong(0)
            val tag = cursor.getString(1)
            val err = decrypt(cursor.getString(2))
            sb.append("[$ts] [$tag] $err\n")
        }
        cursor.close()
        return sb.toString()
    }

    fun clearAllErrors() {
        writableDatabase.delete(TABLE_ERRORS, null, null)
    }
}