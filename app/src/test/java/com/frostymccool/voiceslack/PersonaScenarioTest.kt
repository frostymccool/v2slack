package com.frostymccool.voiceslack

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.frostymccool.voiceslack.actions.ActionResult
import com.frostymccool.voiceslack.actions.RemoteAction
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * End-to-end scenarios driven through [VoiceController] exactly as the real app would, one
 * distinct user persona per scenario. This is the suite called out as non-negotiable in the
 * spec: every path (offline transcription, webhook post, deep-link post) exercised across
 * personas and the required edge cases (no network, denied mic permission, empty/garbled
 * transcription, webhook failure, Slack app not installed, fold-state change mid-recording).
 *
 * [FakeSpeechRecognizerFactory]/[FakeSpeechRecognizerHandle] stand in for the offline speech
 * stack, and [FakeRemoteAction] stands in for the two real [RemoteAction]s, so every scenario
 * runs on the JVM with no device, no network, and no Slack app required.
 */
@RunWith(RobolectricTestRunner::class)
class PersonaScenarioTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scope = CoroutineScope(UnconfinedTestDispatcher())

    private lateinit var recognizerFactory: FakeSpeechRecognizerFactory
    private lateinit var engine: TranscriptionEngine
    private lateinit var settings: SettingsStore
    private lateinit var webhookAction: FakeRemoteAction
    private lateinit var deepLinkAction: FakeRemoteAction
    private lateinit var controller: VoiceController

    @Before
    fun setUp() {
        recognizerFactory = FakeSpeechRecognizerFactory(onDeviceAvailable = true)
        engine = TranscriptionEngine(context, recognizerFactory)
        settings = SettingsStore(context)
        webhookAction = FakeRemoteAction("slack_webhook", "Post via Slack webhook")
        deepLinkAction = FakeRemoteAction("slack_deep_link", "Open in Slack (prefilled)")
        controller = VoiceController(
            scope = scope,
            appContext = context,
            settings = settings,
            engine = engine,
            actions = mapOf(OutputMode.WEBHOOK to webhookAction, OutputMode.DEEP_LINK to deepLinkAction),
        )
    }

    // -- Hana: happy path, webhook configured and working ------------------------------------

    @Test
    fun `Hana records a clear note and it posts via the configured webhook`() {
        settings.webhookUrl = "https://hooks.slack.com/services/T00/B00/xyz"
        controller.onOutputModeSelected(OutputMode.WEBHOOK)

        controller.onRecordTapped(hasMicPermission = true)
        val handle = recognizerFactory.lastHandle!!
        handle.emitReadyForSpeech()
        handle.emitEndOfSpeech()
        handle.emitResults("standup notes: shipped the release, no blockers")

        assertThat(controller.uiState.value.editableText)
            .isEqualTo("standup notes: shipped the release, no blockers")

        controller.send()

        assertThat(webhookAction.invocations).containsExactly("standup notes: shipped the release, no blockers")
        assertThat(controller.uiState.value.sendState)
            .isEqualTo(SendState.Sent(webhookAction.label))
    }

    // -- Dana: mic permission denied ----------------------------------------------------------

    @Test
    fun `Dana denies the microphone permission and gets a clear, immediate error`() {
        controller.onRecordTapped(hasMicPermission = false)

        assertThat(controller.uiState.value.transcription)
            .isEqualTo(TranscriptionState.Error(TranscriptionError.NO_PERMISSION))
        assertThat(recognizerFactory.createCount).isEqualTo(0)
        assertThat(controller.uiState.value.editableText).isEmpty()
    }

    // -- Gary: garbled speech, recognizer returns nothing usable ------------------------------

    @Test
    fun `Gary mumbles and the recognizer returns no usable text`() {
        controller.onRecordTapped(hasMicPermission = true)
        val handle = recognizerFactory.lastHandle!!
        handle.emitReadyForSpeech()
        handle.emitEndOfSpeech()

        handle.emitResults("")

        assertThat(controller.uiState.value.transcription)
            .isEqualTo(TranscriptionState.Error(TranscriptionError.EMPTY_RESULT))
        assertThat(webhookAction.invocations).isEmpty()
        assertThat(deepLinkAction.invocations).isEmpty()

        controller.onDismissError()
        assertThat(controller.uiState.value.transcription).isEqualTo(TranscriptionState.Idle)
    }

    // -- Nick: webhook fails because there's no network, falls back to deep link -------------

    @Test
    fun `Nick has no network for the webhook, sees a retryable failure, then succeeds via deep link`() {
        settings.webhookUrl = "https://hooks.slack.com/services/T00/B00/xyz"
        controller.onOutputModeSelected(OutputMode.WEBHOOK)
        webhookAction.results.clear()
        webhookAction.results += ActionResult.Failure("Network error: unable to resolve host", retryable = true)

        controller.onRecordTapped(hasMicPermission = true)
        recognizerFactory.lastHandle!!.let {
            it.emitReadyForSpeech()
            it.emitEndOfSpeech()
            it.emitResults("reminder: renew the parking permit")
        }
        controller.send()

        val afterWebhookFailure = controller.uiState.value.sendState as SendState.Failed
        assertThat(afterWebhookFailure.retryable).isTrue()
        // Text survives the failed send so the user doesn't have to re-record.
        assertThat(controller.uiState.value.editableText).isEqualTo("reminder: renew the parking permit")

        // Nick switches to the deep-link path instead of waiting for network to come back.
        controller.onOutputModeSelected(OutputMode.DEEP_LINK)
        controller.send()

        assertThat(deepLinkAction.invocations).containsExactly("reminder: renew the parking permit")
        assertThat(controller.uiState.value.sendState).isEqualTo(SendState.Sent(deepLinkAction.label))
    }

    // -- Sam: Slack isn't installed, deep-link path fails cleanly -----------------------------

    @Test
    fun `Sam doesn't have Slack installed, deep-link fails with a clear non-retryable message`() {
        controller.onOutputModeSelected(OutputMode.DEEP_LINK)
        deepLinkAction.results.clear()
        deepLinkAction.results += ActionResult.Failure("Slack app isn't installed on this device", retryable = false)

        controller.onRecordTapped(hasMicPermission = true)
        recognizerFactory.lastHandle!!.let {
            it.emitReadyForSpeech()
            it.emitResults("call the vendor back about the invoice")
        }
        controller.send()

        val failure = controller.uiState.value.sendState as SendState.Failed
        assertThat(failure.retryable).isFalse()
        assertThat(failure.message).contains("not installed")
        // Text is preserved so Sam can at least copy it out manually.
        assertThat(controller.uiState.value.editableText).isEqualTo("call the vendor back about the invoice")
    }

    // -- Fiona: fold-state change partway through recording -----------------------------------

    @Test
    fun `Fiona folds the phone mid-recording and the capture is unaffected`() {
        controller.onRecordTapped(hasMicPermission = true)
        val handle = recognizerFactory.lastHandle!!
        handle.emitReadyForSpeech()
        assertThat(controller.uiState.value.transcription).isEqualTo(TranscriptionState.Listening)

        // The hosting Activity would be recreated here on a real Fold7/8; the engine and
        // controller are owned by the ViewModel, which survives that, so nothing changes.
        engine.onHostConfigurationChanging()
        assertThat(controller.uiState.value.transcription).isEqualTo(TranscriptionState.Listening)
        assertThat(handle.destroyCalled).isFalse()

        handle.emitEndOfSpeech()
        handle.emitResults("finished this note after unfolding the phone")

        assertThat(controller.uiState.value.transcription)
            .isEqualTo(TranscriptionState.Success("finished this note after unfolding the phone"))
    }

    // -- Eve: tries to send with nothing recorded ----------------------------------------------

    @Test
    fun `Eve taps send with no transcript and nothing is dispatched`() {
        controller.send()

        val failure = controller.uiState.value.sendState as SendState.Failed
        assertThat(failure.retryable).isFalse()
        assertThat(webhookAction.invocations).isEmpty()
        assertThat(deepLinkAction.invocations).isEmpty()
    }

    // -- Priya: prefers webhook but hasn't configured a URL yet -> silent, safe fallback ------

    @Test
    fun `Priya prefers webhook but never configured a URL, so send silently uses deep link instead`() {
        controller.onOutputModeSelected(OutputMode.WEBHOOK)
        assertThat(settings.webhookUrl).isNull()

        controller.onRecordTapped(hasMicPermission = true)
        recognizerFactory.lastHandle!!.let {
            it.emitReadyForSpeech()
            it.emitResults("book the conference room for 3pm")
        }
        controller.send()

        assertThat(webhookAction.invocations).isEmpty()
        assertThat(deepLinkAction.invocations).containsExactly("book the conference room for 3pm")
    }

    // -- Wade: webhook fails once, retries, succeeds the second time --------------------------

    @Test
    fun `Wade retries after a transient webhook failure and the retry succeeds`() {
        settings.webhookUrl = "https://hooks.slack.com/services/T00/B00/xyz"
        controller.onOutputModeSelected(OutputMode.WEBHOOK)
        webhookAction.results.clear()
        webhookAction.results += ActionResult.Failure("HTTP 503", retryable = true)
        webhookAction.results += ActionResult.Success

        controller.onRecordTapped(hasMicPermission = true)
        recognizerFactory.lastHandle!!.let {
            it.emitReadyForSpeech()
            it.emitResults("follow up with legal on the contract")
        }

        controller.send()
        assertThat((controller.uiState.value.sendState as SendState.Failed).retryable).isTrue()

        controller.send()
        assertThat(controller.uiState.value.sendState).isEqualTo(SendState.Sent(webhookAction.label))
        assertThat(webhookAction.invocations).hasSize(2)
    }

    // -- Nora: says nothing at all, recognizer times out ---------------------------------------

    @Test
    fun `Nora stays silent after tapping record and gets a no-speech-detected error, not a crash`() {
        controller.onRecordTapped(hasMicPermission = true)
        val handle = recognizerFactory.lastHandle!!

        handle.emitError(android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT)

        assertThat(controller.uiState.value.transcription)
            .isEqualTo(TranscriptionState.Error(TranscriptionError.NO_SPEECH_DETECTED))
    }

    // -- Omar: double-taps the record button while it's already listening ---------------------

    @Test
    fun `Omar double-taps record while already listening and it cancels instead of starting a second session`() {
        controller.onRecordTapped(hasMicPermission = true)
        val handle = recognizerFactory.lastHandle!!
        handle.emitReadyForSpeech()

        controller.onRecordTapped(hasMicPermission = true)

        assertThat(handle.cancelCalled).isTrue()
        assertThat(controller.uiState.value.transcription).isEqualTo(TranscriptionState.Idle)
        assertThat(recognizerFactory.createCount).isEqualTo(1)
    }
}
