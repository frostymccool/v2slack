package com.frostymccool.voiceslack.actions

import android.app.Application
import android.content.Intent
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Covers the deep-link fallback path, including the "Slack app not installed" persona called
 * out as a required test scenario, plus the (rarer, but real) case of Slack being present but
 * unable to actually field the share intent.
 */
@RunWith(RobolectricTestRunner::class)
class SlackDeepLinkActionTest {

    private val application = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `slack not installed is a non-retryable failure -- Slack not installed persona`() = runBlocking {
        val action = SlackDeepLinkAction(channelHintProvider = { null })

        val result = action.execute(application, "text") as ActionResult.Failure

        assertThat(result.retryable).isFalse()
        assertThat(result.message.lowercase()).contains("not installed")
    }

    @Test
    fun `slack installed launches a share intent targeted at the Slack package with the text prefilled`() = runBlocking {
        installFakeSlackApp()
        val action = SlackDeepLinkAction(channelHintProvider = { null })

        val result = action.execute(application, "pick up milk on the way home")

        assertThat(result).isEqualTo(ActionResult.Success)
        val started: Intent? = shadowOf(application).nextStartedActivity
        assertThat(started).isNotNull()
        assertThat(started!!.action).isEqualTo(Intent.ACTION_SEND)
        assertThat(started.getPackage()).isEqualTo(SlackDeepLinkAction.SLACK_PACKAGE)
        assertThat(started.getStringExtra(Intent.EXTRA_TEXT)).isEqualTo("pick up milk on the way home")
    }

    @Test
    fun `configured channel hint is prefixed onto the message as a visible reminder`() = runBlocking {
        installFakeSlackApp()
        val action = SlackDeepLinkAction(channelHintProvider = { "team-updates" })

        action.execute(application, "we shipped the release")

        val started = shadowOf(application).nextStartedActivity
        assertThat(started.getStringExtra(Intent.EXTRA_TEXT)).isEqualTo("#team-updates: we shipped the release")
    }

    @Test
    fun `blank channel hint is not prefixed`() = runBlocking {
        installFakeSlackApp()
        val action = SlackDeepLinkAction(channelHintProvider = { "   " })

        action.execute(application, "we shipped the release")

        val started = shadowOf(application).nextStartedActivity
        assertThat(started.getStringExtra(Intent.EXTRA_TEXT)).isEqualTo("we shipped the release")
    }

    @Test
    fun `slack present but unable to field the share intent is a non-retryable failure`() = runBlocking {
        installFakeSlackApp()
        shadowOf(application).checkActivities(true) // no activity registered to handle ACTION_SEND -> throws
        val action = SlackDeepLinkAction(channelHintProvider = { null })

        val result = action.execute(application, "text") as ActionResult.Failure

        assertThat(result.retryable).isFalse()
    }

    private fun installFakeSlackApp() {
        val packageInfo = PackageInfo().apply { packageName = SlackDeepLinkAction.SLACK_PACKAGE }
        shadowOf(application.packageManager).installPackage(packageInfo)
    }
}
