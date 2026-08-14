# Voice Slack

One-tap Android note-taker: tap, speak, the phone transcribes the note fully on-device
(offline, no cloud), you review/edit the text, then it goes to a Slack channel.

Built for a locked-down corporate Slack where nothing should leave the phone except the
final text, and where webhook approval isn't guaranteed — so there are two output paths.

## How it works

- **Transcription** (`TranscriptionEngine.kt`) uses `SpeechRecognizer.createOnDeviceSpeechRecognizer`,
  the strict on-device recognizer (Android 12+/API 31+) that never falls back to a network
  recognition service — unlike `EXTRA_PREFER_OFFLINE`, which is only a hint.
- **Output paths** (`actions/Actions.kt`) are both implementations of a small `RemoteAction`
  interface, so more one-tap actions can be added later without touching the record/transcribe
  flow (see "Extensibility" below):
  - `SlackWebhookAction` — POSTs to an incoming webhook URL (primary path, once IT approves one).
  - `SlackDeepLinkAction` — shares the text to the Slack app (`ACTION_SEND`) so the user picks
    the channel and taps send (fallback path, works without any webhook approval).
- **Settings** (`SettingsStore.kt`) persist the webhook URL, channel hint, and preferred output
  mode in `EncryptedSharedPreferences` (falling back to plain prefs if Keystore is unavailable).
  A WEBHOOK preference with no URL configured silently uses the deep-link path instead of
  dead-ending.
- **UI** (`MainActivity.kt`) is a single-activity Jetpack Compose app: a record screen and a
  settings screen. Record/transcribe state lives in `VoiceController` (`VoiceViewModel.kt`),
  owned by a `ViewModel` so it survives Activity recreation — including the Fold7/8 hinge
  triggering a screen-size configuration change mid-recording.

## Extensibility

`RemoteAction` is the seam for growing this into a general "remote control" app: add a new
class implementing `execute(context, text): ActionResult`, wire it into the `actions` map in
`VoiceViewModel`, and it's a new one-tap destination with no changes to transcription or UI.

## Building

```
./gradlew assembleDebug   # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest
```

GitHub Actions (`.github/workflows/build.yml`) builds the debug APK and runs unit tests on
every push; download the APK from the workflow run's artifacts to sideload it.

## Testing

`app/src/test/java/.../PersonaScenarioTest.kt` drives the app end-to-end through fakes (no
device, no network, no Slack app needed) across distinct personas and the required edge cases:
denied mic permission, garbled/empty transcription, no-network webhook failure with fallback
to deep-link, Slack not installed, and a fold-state change mid-recording. `TranscriptionEngineTest`,
`SlackWebhookActionTest`, `SlackDeepLinkActionTest`, and `SettingsStoreTest` cover each unit in
isolation in more depth.
