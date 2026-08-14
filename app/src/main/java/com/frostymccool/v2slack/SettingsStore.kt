package com.frostymccool.v2slack

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

enum class OutputMode { WEBHOOK, DEEP_LINK }

/**
 * Persists the two things a corporate deployment needs to configure: which output path to
 * prefer, and the webhook URL for it (a bearer credential in all but name -- anyone holding it
 * can post to the channel -- hence the encrypted store). Falls back to a plain store rather
 * than crashing if the Keystore-backed encryption is unavailable on a given device/OS state.
 */
class SettingsStore(context: Context) {
    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    var webhookUrl: String?
        get() = prefs.getString(KEY_WEBHOOK_URL, null)
        set(value) = prefs.edit().putString(KEY_WEBHOOK_URL, value).apply()

    var channelHint: String?
        get() = prefs.getString(KEY_CHANNEL_HINT, null)
        set(value) = prefs.edit().putString(KEY_CHANNEL_HINT, value).apply()

    /** User's preferred path. Note that [VoiceController]'s effective-action selection is what
     * actually gets used -- it auto-falls-back to DEEP_LINK when no webhook URL is set, since a
     * WEBHOOK preference with nothing configured can never succeed. */
    var outputMode: OutputMode
        get() = parseOutputMode(prefs.getString(KEY_OUTPUT_MODE, null))
        set(value) = prefs.edit().putString(KEY_OUTPUT_MODE, value.name).apply()

    companion object {
        private const val PREFS_NAME = "v2slack_settings"
        private const val KEY_WEBHOOK_URL = "webhook_url"
        private const val KEY_CHANNEL_HINT = "channel_hint"
        private const val KEY_OUTPUT_MODE = "output_mode"
        internal val DEFAULT_MODE = OutputMode.DEEP_LINK

        /** Falls back to [DEFAULT_MODE] for anything unset or no longer a valid enum name
         * (e.g. a value written by a future app version this build doesn't know about). */
        internal fun parseOutputMode(raw: String?): OutputMode =
            raw?.let { name -> runCatching { OutputMode.valueOf(name) }.getOrNull() } ?: DEFAULT_MODE
    }
}
