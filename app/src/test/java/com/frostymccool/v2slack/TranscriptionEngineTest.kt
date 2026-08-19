package com.frostymccool.v2slack

import android.speech.SpeechRecognizer
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Exercises the offline transcription state machine in isolation, via [FakeSpeechRecognizerFactory]
 * so no real device or speech service is needed. Covers the transcription-side edge cases called
 * out as non-negotiable: denied mic permission, offline model unavailable, empty/garbled results,
 * recognizer errors, and duplicate-tap / fold-survival behavior.
 */
@RunWith(RobolectricTestRunner::class)
class TranscriptionEngineTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var factory: FakeSpeechRecognizerFactory
    private lateinit var engine: TranscriptionEngine

    @Before
    fun setUp() {
        factory = FakeSpeechRecognizerFactory(onDeviceAvailable = true)
        engine = TranscriptionEngine(context, factory)
    }

    @Test
    fun `denied mic permission surfaces error without creating a recognizer`() {
        engine.start(hasMicPermission = false)

        assertThat(engine.state.value).isEqualTo(TranscriptionState.Error(TranscriptionError.NO_PERMISSION))
        assertThat(factory.createCount).isEqualTo(0)
    }

    @Test
    fun `offline model unavailable surfaces error without creating a recognizer`() {
        val unavailableFactory = FakeSpeechRecognizerFactory(onDeviceAvailable = false)
        val engineWithNoModel = TranscriptionEngine(context, unavailableFactory)

        engineWithNoModel.start(hasMicPermission = true)

        assertThat(engineWithNoModel.state.value)
            .isEqualTo(TranscriptionState.Error(TranscriptionError.OFFLINE_MODEL_UNAVAILABLE))
        assertThat(unavailableFactory.createCount).isEqualTo(0)
    }

    @Test
    fun `happy path moves through listening, processing, to success with transcribed text`() {
        engine.start(hasMicPermission = true)
        val handle = factory.lastHandle!!

        handle.emitReadyForSpeech()
        assertThat(engine.state.value).isEqualTo(TranscriptionState.Listening)

        handle.emitEndOfSpeech()
        assertThat(engine.state.value).isEqualTo(TranscriptionState.Processing)

        handle.emitResults("pick up milk on the way home")
        assertThat(engine.state.value)
            .isEqualTo(TranscriptionState.Success("pick up milk on the way home"))
        assertThat(handle.destroyCalled).isTrue()
    }

    @Test
    fun `blank recognition result surfaces empty-result error, not a false success`() {
        engine.start(hasMicPermission = true)
        val handle = factory.lastHandle!!
        handle.emitReadyForSpeech()

        handle.emitResults("   ")

        assertThat(engine.state.value).isEqualTo(TranscriptionState.Error(TranscriptionError.EMPTY_RESULT))
    }

    @Test
    fun `no results list at all surfaces empty-result error`() {
        engine.start(hasMicPermission = true)
        val handle = factory.lastHandle!!
        handle.emitReadyForSpeech()

        handle.emitResults(null)

        assertThat(engine.state.value).isEqualTo(TranscriptionState.Error(TranscriptionError.EMPTY_RESULT))
    }

    @Test
    fun `recognizer no-match error maps to empty-result (garbled speech persona)`() {
        engine.start(hasMicPermission = true)
        val handle = factory.lastHandle!!

        handle.emitError(SpeechRecognizer.ERROR_NO_MATCH)

        assertThat(engine.state.value).isEqualTo(TranscriptionState.Error(TranscriptionError.EMPTY_RESULT))
        assertThat(handle.destroyCalled).isTrue()
    }

    @Test
    fun `recognizer timeout error maps to no-speech-detected (silence persona)`() {
        engine.start(hasMicPermission = true)
        val handle = factory.lastHandle!!

        handle.emitError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)

        assertThat(engine.state.value).isEqualTo(TranscriptionState.Error(TranscriptionError.NO_SPEECH_DETECTED))
    }

    @Test
    fun `network-shaped recognizer error maps to offline-model-unavailable`() {
        engine.start(hasMicPermission = true)
        val handle = factory.lastHandle!!

        handle.emitError(SpeechRecognizer.ERROR_NETWORK)

        assertThat(engine.state.value)
            .isEqualTo(TranscriptionState.Error(TranscriptionError.OFFLINE_MODEL_UNAVAILABLE))
    }

    @Test
    fun `cancel while listening stops the recognizer and returns to idle`() {
        engine.start(hasMicPermission = true)
        val handle = factory.lastHandle!!
        handle.emitReadyForSpeech()

        engine.cancel()

        assertThat(handle.cancelCalled).isTrue()
        assertThat(handle.destroyCalled).isTrue()
        assertThat(engine.state.value).isEqualTo(TranscriptionState.Idle)
    }

    @Test
    fun `stop while listening tells the recognizer to finalize, not cancel -- the tap-to-stop button`() {
        engine.start(hasMicPermission = true)
        val handle = factory.lastHandle!!
        handle.emitReadyForSpeech()

        engine.stop()

        assertThat(handle.stopCalled).isTrue()
        assertThat(handle.cancelCalled).isFalse()
        // Still listening from the engine's point of view until the recognizer actually
        // reports back -- stop() doesn't fabricate a state change, it just asks for one.
        assertThat(engine.state.value).isEqualTo(TranscriptionState.Listening)

        handle.emitEndOfSpeech()
        assertThat(engine.state.value).isEqualTo(TranscriptionState.Processing)
        handle.emitResults("stop tap finalized this note")
        assertThat(engine.state.value)
            .isEqualTo(TranscriptionState.Success("stop tap finalized this note"))
    }

    @Test
    fun `stop when not listening is a no-op`() {
        engine.stop()

        assertThat(engine.state.value).isEqualTo(TranscriptionState.Idle)
        assertThat(factory.createCount).isEqualTo(0)
    }

    @Test
    fun `duplicate start while already listening is ignored, not a second session`() {
        engine.start(hasMicPermission = true)
        factory.lastHandle!!.emitReadyForSpeech()

        engine.start(hasMicPermission = true)

        assertThat(factory.createCount).isEqualTo(1)
    }

    @Test
    fun `reset clears a terminal error back to idle but leaves an active session alone`() {
        engine.start(hasMicPermission = true)
        factory.lastHandle!!.emitError(SpeechRecognizer.ERROR_NO_MATCH)

        engine.reset()
        assertThat(engine.state.value).isEqualTo(TranscriptionState.Idle)

        engine.start(hasMicPermission = true)
        factory.lastHandle!!.emitReadyForSpeech()
        engine.reset()
        assertThat(engine.state.value).isEqualTo(TranscriptionState.Listening)
    }

    @Test
    fun `fold-triggered host configuration change does not interrupt an in-flight session`() {
        // Simulates the Activity being recreated under the ViewModel mid-recording (Fold7/8
        // hinge change). The engine and its handle are owned by the ViewModel, not the
        // Activity, so this must be a no-op for the running session.
        engine.start(hasMicPermission = true)
        val handle = factory.lastHandle!!
        handle.emitReadyForSpeech()

        engine.onHostConfigurationChanging()

        assertThat(engine.state.value).isEqualTo(TranscriptionState.Listening)
        assertThat(handle.destroyCalled).isFalse()
        assertThat(handle.cancelCalled).isFalse()

        handle.emitResults("still capturing across the fold event")
        assertThat(engine.state.value)
            .isEqualTo(TranscriptionState.Success("still capturing across the fold event"))
    }
}
