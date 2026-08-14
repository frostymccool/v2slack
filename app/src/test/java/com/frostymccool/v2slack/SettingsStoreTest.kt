package com.frostymccool.v2slack

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric doesn't back the AndroidKeyStore provider EncryptedSharedPreferences needs, so
 * these tests double as coverage of [SettingsStore]'s fallback-to-plain-prefs path -- which is
 * exactly what should also happen on a real device with a broken/locked-down Keystore.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsStoreTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `defaults to deep-link mode with no webhook or channel configured`() {
        val settings = SettingsStore(context)

        assertThat(settings.outputMode).isEqualTo(OutputMode.DEEP_LINK)
        assertThat(settings.webhookUrl).isNull()
        assertThat(settings.channelHint).isNull()
    }

    @Test
    fun `webhook url, channel hint, and output mode round-trip`() {
        val settings = SettingsStore(context)

        settings.webhookUrl = "https://hooks.slack.com/services/T00/B00/xyz"
        settings.channelHint = "team-updates"
        settings.outputMode = OutputMode.WEBHOOK

        assertThat(settings.webhookUrl).isEqualTo("https://hooks.slack.com/services/T00/B00/xyz")
        assertThat(settings.channelHint).isEqualTo("team-updates")
        assertThat(settings.outputMode).isEqualTo(OutputMode.WEBHOOK)
    }

    @Test
    fun `settings persist across separate SettingsStore instances backed by the same context`() {
        SettingsStore(context).apply {
            webhookUrl = "https://hooks.slack.com/services/persisted"
            outputMode = OutputMode.WEBHOOK
        }

        val reloaded = SettingsStore(context)

        assertThat(reloaded.webhookUrl).isEqualTo("https://hooks.slack.com/services/persisted")
        assertThat(reloaded.outputMode).isEqualTo(OutputMode.WEBHOOK)
    }

    @Test
    fun `corrupt or unrecognized stored output mode falls back to the default instead of crashing`() {
        assertThat(SettingsStore.parseOutputMode("NOT_A_REAL_MODE")).isEqualTo(SettingsStore.DEFAULT_MODE)
        assertThat(SettingsStore.parseOutputMode(null)).isEqualTo(SettingsStore.DEFAULT_MODE)
        assertThat(SettingsStore.parseOutputMode("")).isEqualTo(SettingsStore.DEFAULT_MODE)
        assertThat(SettingsStore.parseOutputMode(OutputMode.WEBHOOK.name)).isEqualTo(OutputMode.WEBHOOK)
    }
}
