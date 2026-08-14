package com.frostymccool.voiceslack

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** UI-facing state of a single record -> transcribe cycle. */
sealed interface TranscriptionState {
    data object Idle : TranscriptionState
    data object Listening : TranscriptionState
    data object Processing : TranscriptionState
    data class Success(val text: String) : TranscriptionState
    data class Error(val reason: TranscriptionError) : TranscriptionState
}

enum class TranscriptionError {
    NO_PERMISSION,
    OFFLINE_MODEL_UNAVAILABLE,
    NO_SPEECH_DETECTED,
    EMPTY_RESULT,
    RECOGNIZER_BUSY,
    CLIENT_ERROR,
    UNKNOWN,
}

/**
 * Seam over [SpeechRecognizer] so tests can substitute a fake and never touch the
 * real Android speech stack. Production always goes through [AndroidSpeechRecognizerFactory],
 * which is pinned to [SpeechRecognizer.createOnDeviceSpeechRecognizer] -- the strict
 * on-device recognizer that never falls back to a network recognition service. This is
 * what actually enforces "nothing leaves the phone but the final text": EXTRA_PREFER_OFFLINE
 * on the regular recognizer is only a hint and can silently fall back to network.
 */
interface SpeechRecognizerFactory {
    fun isOnDeviceRecognitionAvailable(context: Context): Boolean
    fun create(context: Context, listener: RecognitionListener): SpeechRecognizerHandle
}

/** Minimal surface of [SpeechRecognizer] the engine actually drives. */
interface SpeechRecognizerHandle {
    fun startListening(intent: Intent)
    fun cancel()
    fun destroy()
}

class AndroidSpeechRecognizerFactory : SpeechRecognizerFactory {
    override fun isOnDeviceRecognitionAvailable(context: Context): Boolean =
        SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    override fun create(context: Context, listener: RecognitionListener): SpeechRecognizerHandle {
        val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        recognizer.setRecognitionListener(listener)
        return object : SpeechRecognizerHandle {
            override fun startListening(intent: Intent) = recognizer.startListening(intent)
            override fun cancel() = recognizer.cancel()
            override fun destroy() = recognizer.destroy()
        }
    }
}

/**
 * Drives one on-device speech recognition session at a time and publishes [state] for the UI.
 *
 * Lifecycle note: this class is owned by [VoiceViewModel], which survives Activity recreation.
 * A Fold7/8 hinge change recreates the hosting Activity, but never this engine or its in-flight
 * [SpeechRecognizerHandle] -- so folding mid-recording does not drop the capture. The UI just
 * re-subscribes to [state] after recreation and picks up wherever the session currently is.
 */
class TranscriptionEngine(
    private val appContext: Context,
    private val factory: SpeechRecognizerFactory = AndroidSpeechRecognizerFactory(),
) {
    private val _state = MutableStateFlow<TranscriptionState>(TranscriptionState.Idle)
    val state: StateFlow<TranscriptionState> = _state.asStateFlow()

    private var handle: SpeechRecognizerHandle? = null

    fun start(hasMicPermission: Boolean) {
        if (!hasMicPermission) {
            _state.value = TranscriptionState.Error(TranscriptionError.NO_PERMISSION)
            return
        }
        if (_state.value is TranscriptionState.Listening || _state.value is TranscriptionState.Processing) {
            return
        }
        if (!factory.isOnDeviceRecognitionAvailable(appContext)) {
            _state.value = TranscriptionState.Error(TranscriptionError.OFFLINE_MODEL_UNAVAILABLE)
            return
        }

        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                _state.value = TranscriptionState.Listening
            }

            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                _state.value = TranscriptionState.Processing
            }

            override fun onError(error: Int) {
                _state.value = TranscriptionState.Error(mapError(error))
                releaseHandle()
            }

            override fun onResults(results: Bundle?) {
                val text = extractBestText(results)
                _state.value = if (text.isNullOrBlank()) {
                    TranscriptionState.Error(TranscriptionError.EMPTY_RESULT)
                } else {
                    TranscriptionState.Success(text)
                }
                releaseHandle()
            }

            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        }

        releaseHandle()
        val newHandle = factory.create(appContext, listener)
        handle = newHandle
        _state.value = TranscriptionState.Listening
        newHandle.startListening(buildRecognizerIntent())
    }

    /** User-initiated abort (e.g. tapped the button again while listening). */
    fun cancel() {
        handle?.cancel()
        releaseHandle()
        _state.value = TranscriptionState.Idle
    }

    /** Clears a terminal [TranscriptionState.Success]/[TranscriptionState.Error] back to idle. */
    fun reset() {
        if (_state.value !is TranscriptionState.Listening && _state.value !is TranscriptionState.Processing) {
            _state.value = TranscriptionState.Idle
        }
    }

    private fun releaseHandle() {
        handle?.destroy()
        handle = null
    }

    private fun buildRecognizerIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toString())
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Notes run longer than voice commands; give the user room to pause mid-thought
            // before the recognizer decides speech has ended.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 15000L)
        }

    companion object {
        internal fun extractBestText(results: Bundle?): String? {
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            return list?.firstOrNull()?.trim()
        }

        internal fun mapError(error: Int): TranscriptionError = when (error) {
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> TranscriptionError.NO_PERMISSION
            SpeechRecognizer.ERROR_NO_MATCH -> TranscriptionError.EMPTY_RESULT
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> TranscriptionError.NO_SPEECH_DETECTED
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> TranscriptionError.RECOGNIZER_BUSY
            SpeechRecognizer.ERROR_CLIENT -> TranscriptionError.CLIENT_ERROR
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                TranscriptionError.OFFLINE_MODEL_UNAVAILABLE
            else -> TranscriptionError.UNKNOWN
        }
    }
}
