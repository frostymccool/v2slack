package com.frostymccool.v2slack

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import kotlinx.coroutines.flow.map

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = voiceSlackColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    VoiceApp()
                }
            }
        }
    }
}

private enum class Screen { Record, Settings }

@Composable
fun voiceSlackColorScheme() = MaterialTheme.colorScheme.copy(primary = Color(0xFF4A154B))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceApp(viewModel: VoiceViewModel = viewModel()) {
    var screen by rememberSaveable { mutableStateOf(Screen.Record) }
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (screen == Screen.Record) "V2Slack" else "Settings") },
                navigationIcon = {
                    if (screen == Screen.Settings) {
                        IconButton(onClick = { screen = Screen.Record }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    if (screen == Screen.Record) {
                        IconButton(onClick = { screen = Screen.Settings }) {
                            Icon(Icons.Filled.Settings, contentDescription = "Settings")
                        }
                    }
                },
            )
        },
    ) { padding ->
        when (screen) {
            Screen.Record -> RecordScreen(
                uiState = uiState,
                onRecordTapped = viewModel::onRecordTapped,
                onTextEdited = viewModel::onTextEdited,
                onSend = viewModel::send,
                onDismissError = viewModel::onDismissError,
                onClearTranscript = viewModel::onClearTranscript,
                padding = padding,
            )
            Screen.Settings -> SettingsScreen(
                uiState = uiState,
                onOutputModeSelected = viewModel::onOutputModeSelected,
                onWebhookUrlChanged = viewModel::onWebhookUrlChanged,
                onChannelHintChanged = viewModel::onChannelHintChanged,
                padding = padding,
            )
        }
    }
}

@Composable
private fun RecordScreen(
    uiState: VoiceUiState,
    onRecordTapped: (hasMicPermission: Boolean) -> Unit,
    onTextEdited: (String) -> Unit,
    onSend: () -> Unit,
    onDismissError: () -> Unit,
    onClearTranscript: () -> Unit,
    padding: PaddingValues,
) {
    val context = LocalContext.current
    val activity = context as? Activity

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> onRecordTapped(granted) }

    val onTap = {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted || uiState.transcription is TranscriptionState.Listening) {
            onRecordTapped(granted)
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val isHalfOpened = activity?.let { rememberIsHalfOpened(it) }?.value ?: false

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
    ) {
        if (isHalfOpened) {
            Text(
                text = "Layout adjusted for fold state — recording is unaffected",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        Spacer(Modifier.height(24.dp))
        RecordButton(state = uiState.transcription, onClick = onTap)
        Spacer(Modifier.height(16.dp))
        Text(text = statusLabel(uiState.transcription), style = MaterialTheme.typography.titleMedium)

        val errorReason = (uiState.transcription as? TranscriptionState.Error)?.reason
        if (errorReason != null) {
            Text(
                text = errorDetail(errorReason),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            TextButton(onClick = onDismissError) { Text("Dismiss") }
        }

        Spacer(Modifier.height(24.dp))

        if (uiState.editableText.isNotEmpty() || uiState.transcription is TranscriptionState.Success) {
            OutlinedTextField(
                value = uiState.editableText,
                onValueChange = onTextEdited,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Transcript (edit before sending)") },
                minLines = 3,
            )
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onClearTranscript,
                    enabled = uiState.sendState !is SendState.Sending,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Clear")
                }
                Button(
                    onClick = onSend,
                    enabled = uiState.editableText.isNotBlank() && uiState.sendState !is SendState.Sending,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        when (uiState.outputMode) {
                            OutputMode.WEBHOOK -> "Post to Slack"
                            OutputMode.DEEP_LINK -> "Open in Slack"
                        },
                    )
                }
            }
        }

        SendStatusLine(uiState.sendState)
        Spacer(Modifier.weight(1f))
        BuildInfoFooter()
    }
}

/** Small, low-contrast build stamp pinned to the bottom of the screen so it's obvious --
 * without hunting through app info -- whether a freshly installed APK actually took. Shows
 * version, git commit, and when this specific build was produced. */
@Composable
private fun BuildInfoFooter() {
    Text(
        text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${BuildConfig.GIT_SHA} · built ${BuildConfig.BUILD_TIME}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
        modifier = Modifier.padding(top = 16.dp),
    )
}

@Composable
private fun SendStatusLine(sendState: SendState) {
    when (sendState) {
        SendState.Idle -> Unit
        SendState.Sending -> Row(
            modifier = Modifier.padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp))
            Text("Sending…", modifier = Modifier.padding(start = 8.dp))
        }
        is SendState.Sent -> Text(
            "Sent via ${sendState.via}",
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 12.dp),
        )
        is SendState.Failed -> Text(
            if (sendState.retryable) "${sendState.message} — you can try again" else sendState.message,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun RecordButton(state: TranscriptionState, onClick: () -> Unit) {
    val (color, icon) = when (state) {
        TranscriptionState.Listening -> Color(0xFFE01E5A) to Icons.Filled.Stop
        TranscriptionState.Processing -> MaterialTheme.colorScheme.surfaceVariant to Icons.Filled.Mic
        is TranscriptionState.Error -> MaterialTheme.colorScheme.errorContainer to Icons.Filled.Mic
        else -> MaterialTheme.colorScheme.primary to Icons.Filled.Mic
    }
    val isListening = state is TranscriptionState.Listening

    // Expanding, fading ring repeated on a loop while listening -- an unmistakable "I'm
    // actively recording" cue, distinct from the button just sitting there in a different
    // color. Only animates while isListening; harmless (values just hold at rest) otherwise.
    val pulseTransition = rememberInfiniteTransition(label = "listeningPulse")
    val pulseScale by pulseTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isListening) 1.7f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pulseScale",
    )
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pulseAlpha",
    )

    Box(
        modifier = Modifier.size(160.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (isListening) {
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .scale(pulseScale)
                    .background(color = color.copy(alpha = pulseAlpha), shape = CircleShape),
            )
        }
        Box(
            modifier = Modifier
                .size(120.dp)
                .clickable(enabled = state !is TranscriptionState.Processing, onClick = onClick)
                .background(color = color, shape = CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (state is TranscriptionState.Processing) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = "Record",
                    modifier = Modifier.size(48.dp),
                    tint = Color.White,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    uiState: VoiceUiState,
    onOutputModeSelected: (OutputMode) -> Unit,
    onWebhookUrlChanged: (String) -> Unit,
    onChannelHintChanged: (String) -> Unit,
    padding: PaddingValues,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(24.dp),
    ) {
        Text("Output path", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = when (uiState.outputMode) {
                    OutputMode.WEBHOOK -> "Incoming webhook (auto-post)"
                    OutputMode.DEEP_LINK -> "Deep-link to Slack (prefilled, tap send)"
                },
                onValueChange = {},
                readOnly = true,
                label = { Text("Mode") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true),
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text("Incoming webhook (auto-post)") },
                    onClick = { onOutputModeSelected(OutputMode.WEBHOOK); expanded = false },
                )
                DropdownMenuItem(
                    text = { Text("Deep-link to Slack (prefilled, tap send)") },
                    onClick = { onOutputModeSelected(OutputMode.DEEP_LINK); expanded = false },
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        Text("Slack incoming webhook URL", style = MaterialTheme.typography.titleMedium)
        Text(
            "Used by the webhook path. Leave blank to always use the deep-link fallback.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = uiState.webhookUrl,
            onValueChange = onWebhookUrlChanged,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("https://hooks.slack.com/services/...") },
        )

        Spacer(Modifier.height(24.dp))
        Text("Target channel (reminder, not enforced)", style = MaterialTheme.typography.titleMedium)
        Text(
            "Slack's share sheet can't be told which channel to preselect, so this is " +
                "prefixed onto the message as a visible reminder when using the deep-link path.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = uiState.channelHint,
            onValueChange = onChannelHintChanged,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("team-updates") },
        )
        Spacer(Modifier.weight(1f))
        BuildInfoFooter()
    }
}

private fun statusLabel(state: TranscriptionState): String = when (state) {
    TranscriptionState.Idle -> "Tap to record"
    TranscriptionState.Listening -> "Listening…"
    TranscriptionState.Processing -> "Transcribing…"
    is TranscriptionState.Success -> "Got it - review below or push to clear and start again"
    is TranscriptionState.Error -> "Couldn't transcribe that"
}

private fun errorDetail(reason: TranscriptionError): String = when (reason) {
    TranscriptionError.NO_PERMISSION -> "Microphone permission is required to record."
    TranscriptionError.OFFLINE_MODEL_UNAVAILABLE ->
        "On-device speech model isn't available. Install it under Settings > System > Languages > On-device recognition."
    TranscriptionError.NO_SPEECH_DETECTED -> "Didn't hear anything — try again."
    TranscriptionError.EMPTY_RESULT -> "Couldn't make out any words — try again."
    TranscriptionError.RECOGNIZER_BUSY -> "Recognizer is busy — try again in a moment."
    TranscriptionError.CLIENT_ERROR -> "Something went wrong starting the recognizer."
    TranscriptionError.UNKNOWN -> "Unexpected error — try again."
}

@Composable
private fun rememberIsHalfOpened(activity: Activity): State<Boolean> {
    val flow = remember(activity) {
        WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity)
            .map { info ->
                info.displayFeatures
                    .filterIsInstance<FoldingFeature>()
                    .any { it.state == FoldingFeature.State.HALF_OPENED }
            }
    }
    return flow.collectAsState(initial = false)
}
