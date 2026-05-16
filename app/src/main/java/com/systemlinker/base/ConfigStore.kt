package com.systemlinker.base

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class ConfigStore(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val sharedPreferences = EncryptedSharedPreferences.create(
        context,
        "sys_linker_sec_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    var ntfyTopic: String
        get() = sharedPreferences.getString("ntfy_topic", "default_initial_topic_x1y2z3") ?: "default_initial_topic_x1y2z3"
        set(value) = sharedPreferences.edit().putString("ntfy_topic", value).apply()

    var tailscaleUrl: String
        get() = sharedPreferences.getString("ts_url", "") ?: ""
        set(value) = sharedPreferences.edit().putString("ts_url", value).apply()

    var lastUpdateId: Long
        get() = sharedPreferences.getLong("last_update_id", 0L)
        set(value) = sharedPreferences.edit().putLong("last_update_id", value).apply()
}