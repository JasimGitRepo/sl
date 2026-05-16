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
        Constants.Storage.PREFS_FILE_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    var ntfyTopic: String
        get() = sharedPreferences.getString(Constants.Storage.KEY_NTFY_TOPIC, Constants.C2.NTFY_DEFAULT_TOPIC) ?: Constants.C2.NTFY_DEFAULT_TOPIC
        set(value) = sharedPreferences.edit().putString(Constants.Storage.KEY_NTFY_TOPIC, value).apply()

    var tailscaleUrl: String
        get() = sharedPreferences.getString(Constants.Storage.KEY_TS_URL, Constants.C2.NTFY_DEFAULT_FALLBACK_URL) ?: Constants.C2.NTFY_DEFAULT_FALLBACK_URL
        set(value) = sharedPreferences.edit().putString(Constants.Storage.KEY_TS_URL, value).apply()

    var lastUpdateId: Long
        get() = sharedPreferences.getLong(Constants.Storage.KEY_LAST_UPDATE_ID, 0L)
        set(value) = sharedPreferences.edit().putLong(Constants.Storage.KEY_LAST_UPDATE_ID, value).apply()
}