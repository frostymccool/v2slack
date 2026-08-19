package com.frostymccool.v2slack

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import com.frostymccool.v2slack.actions.ActionResult
import com.frostymccool.v2slack.actions.RemoteAction

/**
 * Drivable stand-in for the real [SpeechRecognizerHandle] so tests can push
 * onReadyForSpeech/onResults/onError callbacks on demand instead of needing a device.
 */
class FakeSpeechRecognizerHandle(private val listener: RecognitionListener) : SpeechRecognizerHandle {
    var startedIntent: Intent? = null
        private set
    var stopCalled = false
        private set
    var cancelCalled = false
        private set
    var destroyCalled = false
        private set

    override fun startListening(intent: Intent) {
        startedIntent = intent
    }

    override fun stopListening() {
        stopCalled = true
    }

    override fun cancel() {
        cancelCalled = true
    }

    override fun destroy() {
        destroyCalled = true
    }

    fun emitReadyForSpeech() = listener.onReadyForSpeech(Bundle())

    fun emitEndOfSpeech() = listener.onEndOfSpeech()

    fun emitResults(text: String?) {
        val bundle = Bundle()
        val results = if (text != null) arrayListOf(text) else arrayListOf()
        bundle.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, results)
        listener.onResults(bundle)
    }

    fun emitError(errorCode: Int) = listener.onError(errorCode)
}

class FakeSpeechRecognizerFactory(
    private val onDeviceAvailable: Boolean = true,
) : SpeechRecognizerFactory {
    var lastHandle: FakeSpeechRecognizerHandle? = null
        private set
    var createCount = 0
        private set

    override fun isOnDeviceRecognitionAvailable(context: Context): Boolean = onDeviceAvailable

    override fun create(context: Context, listener: RecognitionListener): SpeechRecognizerHandle {
        createCount++
        val handle = FakeSpeechRecognizerHandle(listener)
        lastHandle = handle
        return handle
    }
}

/** Test double for [RemoteAction] so persona tests can isolate [VoiceController] behavior
 * from real networking / Slack app plumbing. [results] is consumed in order, one entry per
 * call, so a persona can script "fails then succeeds on retry". */
class FakeRemoteAction(
    override val id: String,
    override val label: String,
    val results: MutableList<ActionResult> = mutableListOf(ActionResult.Success),
) : RemoteAction {
    val invocations = mutableListOf<String>()

    override suspend fun execute(context: Context, text: String): ActionResult {
        invocations += text
        return if (results.size > 1) results.removeAt(0) else results.first()
    }
}
