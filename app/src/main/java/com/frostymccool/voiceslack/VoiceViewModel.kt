package com.frostymccool.voiceslack

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.frostymccool.voiceslack.actions.ActionResult
import com.frostymccool.voiceslack.actions.RemoteAction
import com.frostymccool.voiceslack.actions.SlackDeepLinkAction
import com.frostymccool.voiceslack.actions.SlackWebhookAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface SendState {
    data object Idle : SendState
    data object Sending : SendState
    data class Sent(val via: String) : SendState
    data class Failed(val message: String, val retryable: Boolean) : SendState
}

data class VoiceUiState(
    val transcription: TranscriptionState = TranscriptionState.Idle,
    val editableText: String = "",
    val sendState: SendState = SendState.Idle,
    val outputMode: OutputMode = OutputMode.DEEP_LINK,
    val webhookUrl: String = "",
    val channelHint: String = "",
)

/**
 * All of the record -> edit -> send orchestration, as a plain class with no Android
 * framework dependency beyond a [Context] handed to [RemoteAction.execute]. Kept separate
 * from [VoiceViewModel] so unit tests can drive it directly with fakes (fake engine, fake
 * actions, a Robolectric application context) instead of fighting ViewModel construction.
 */
class VoiceController(
    private val scope: CoroutineScope,
    private val appContext: Context,
    private val settings: SettingsStore,
    private val engine: TranscriptionEngine,
    private val actions: Map<OutputMode, RemoteAction>,
) {
    private val _uiState = MutableStateFlow(
        VoiceUiState(
            outputMode = settings.outputMode,
            webhookUrl = settings.webhookUrl.orEmpty(),
            channelHint = settings.channelHint.orEmpty(),
        ),
    )
    val uiState: StateFlow<VoiceUiState> = _uiState.asStateFlow()

    init {
        scope.launch {
            engine.state.collect { transcription ->
                _uiState.value = _uiState.value.copy(
                    transcription = transcription,
                    editableText = when (transcription) {
                        is TranscriptionState.Success -> transcription.text
                        else -> _uiState.value.editableText
                    },
                )
            }
        }
    }

    fun onRecordTapped(hasMicPermission: Boolean) {
        when (_uiState.value.transcription) {
            is TranscriptionState.Listening -> engine.cancel()
            is TranscriptionState.Processing -> Unit
            else -> {
                _uiState.value = _uiState.value.copy(editableText = "", sendState = SendState.Idle)
                engine.start(hasMicPermission)
            }
        }
    }

    fun onTextEdited(text: String) {
        _uiState.value = _uiState.value.copy(editableText = text)
    }

    fun onOutputModeSelected(mode: OutputMode) {
        settings.outputMode = mode
        _uiState.value = _uiState.value.copy(outputMode = mode)
    }

    fun onWebhookUrlChanged(url: String) {
        settings.webhookUrl = url
        _uiState.value = _uiState.value.copy(webhookUrl = url)
    }

    fun onChannelHintChanged(hint: String) {
        settings.channelHint = hint
        _uiState.value = _uiState.value.copy(channelHint = hint)
    }

    fun onDismissError() {
        engine.reset()
        _uiState.value = _uiState.value.copy(sendState = SendState.Idle)
    }

    fun send() {
        val text = _uiState.value.editableText.trim()
        if (text.isEmpty()) {
            _uiState.value = _uiState.value.copy(
                sendState = SendState.Failed("Nothing to send yet", retryable = false),
            )
            return
        }
        val action = effectiveAction()
        _uiState.value = _uiState.value.copy(sendState = SendState.Sending)
        scope.launch {
            val result = action.execute(appContext, text)
            _uiState.value = _uiState.value.copy(
                sendState = when (result) {
                    is ActionResult.Success -> SendState.Sent(action.label)
                    is ActionResult.Failure -> SendState.Failed(result.message, result.retryable)
                },
            )
        }
    }

    /** A WEBHOOK preference with no URL configured can never succeed, so silently fall back
     * to the deep-link path rather than surfacing a "no webhook configured" dead end. */
    private fun effectiveAction(): RemoteAction {
        val preferred = _uiState.value.outputMode
        return if (preferred == OutputMode.WEBHOOK && settings.webhookUrl.isNullOrBlank()) {
            actions.getValue(OutputMode.DEEP_LINK)
        } else {
            actions.getValue(preferred)
        }
    }

    fun onCleared() {
        engine.cancel()
    }
}

/** Thin Android wrapper: real dependencies in, delegate everything to [VoiceController]. */
class VoiceViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = SettingsStore(application)
    private val engine = TranscriptionEngine(application.applicationContext)
    private val actions: Map<OutputMode, RemoteAction> = mapOf(
        OutputMode.WEBHOOK to SlackWebhookAction(webhookUrlProvider = { settings.webhookUrl }),
        OutputMode.DEEP_LINK to SlackDeepLinkAction(channelHintProvider = { settings.channelHint }),
    )
    private val controller = VoiceController(
        scope = viewModelScope,
        appContext = application.applicationContext,
        settings = settings,
        engine = engine,
        actions = actions,
    )

    val uiState: StateFlow<VoiceUiState> get() = controller.uiState

    fun onRecordTapped(hasMicPermission: Boolean) = controller.onRecordTapped(hasMicPermission)
    fun onTextEdited(text: String) = controller.onTextEdited(text)
    fun onOutputModeSelected(mode: OutputMode) = controller.onOutputModeSelected(mode)
    fun onWebhookUrlChanged(url: String) = controller.onWebhookUrlChanged(url)
    fun onChannelHintChanged(hint: String) = controller.onChannelHintChanged(hint)
    fun onDismissError() = controller.onDismissError()
    fun send() = controller.send()

    override fun onCleared() {
        controller.onCleared()
        super.onCleared()
    }
}
