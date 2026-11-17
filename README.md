# ELOQUENCE Pilot 1 Android App

Android research prototype for ELOQUENCE Pilot 1: local wake-word detection, spoken questions, and conversation recording with backend transcription and retrieval-augmented answers.

Wake-word detection and query endpointing run on the device. Transcription, diarization, vector-store ingestion, retrieval, and answer generation require a compatible ELOQUENCE backend, which is not included in this repository. Answers are displayed as text; this client does not implement text-to-speech playback.

## Features

- Foreground microphone service with a notification and local PyTorch Lite wake-word detection ("OK Aura").
- Query endpointing using Silero VAD through ONNX Runtime, with configurable silence duration.
- Query transcription using `Whisper` or `MEUSLI-SLAM-ASR`, selected in the UI.
- Backend task `RAG_with_Wiki` when a conversation is selected, or `Wiki` when none is selected.
- Retrieved-snippet viewer, including time labels when the backend supplies `start_time` metadata.
- Named conversation recording, sequential WhisperX diarization and ingestion, and upload retries.
- Conversation selection/deletion, in-memory dialogue history, and a live audio-level visualization.

## Repository layout

| Path | Purpose |
| --- | --- |
| `app/` | Android application, Jetpack Compose UI, audio service, and backend client |
| `savr-library/` | Additional audio-recording library module; included in Gradle settings but not currently an app dependency |
| `gradle/wrapper/` | Gradle wrapper |
| `app/src/main/assets/` | Runtime model assets |

Main application sources are under `app/src/main/java/com/example/wuw_service_app/`:

- `MainActivity.kt`: UI, runtime microphone permission, and broadcast receivers.
- `MainViewModel.kt`: UI state, settings, and service commands.
- `EloquenceAPI.kt`: HTTP requests, dialogue history, and backend response handling.
- `ConversationRecorder.kt`: chunk capture, upload queue, retries, and final-chunk handling.
- `service/wuw/AudioService.kt`: foreground service, wake-word pipeline, and short-query capture.
- `service/wuw/vad/`: Silero inference, framing, and end-of-speech detection.

## Build prerequisites

Use Android Studio, an Android SDK installation, and a JDK compatible with the checked-in Android Gradle Plugin and Gradle wrapper. Configure the Gradle JDK separately from the Java/Kotlin bytecode target; the app targets Java 8 bytecode while `savr-library` targets Java 17.

Checked-in configuration:

| Component | Value |
| --- | --- |
| Android Gradle Plugin | `8.13.2` |
| Gradle wrapper | `9.3.1` |
| Kotlin / Compose plugin | `2.0.21` |
| App compile / target SDK | `33` / `33` |
| App minimum SDK | `26` (Android 8.0) |
| Library compile / minimum SDK | `34` / `24` |

These values describe the source configuration, not a verified compatibility matrix. Validate Gradle sync and a clean build in your Android environment before publishing a release. Install SDK platforms 33 and 34 to build both included modules. Configure `sdk.dir` in an untracked `local.properties` file if needed.

## Required model assets

This source repository does not include model weights or other runtime model
assets. The app expects models under `app/src/main/assets/` when the associated
features are used. Obtain and use them only under their respective terms.

[`MODEL_ASSETS.md`](MODEL_ASSETS.md) identifies the expected filenames, the
official Silero VAD source and checksum, and the status of the proprietary
Telefónica wake-word weights.

## Backend configuration

Open **Configurations** and select the server. The current UI offers:

- `https://eloquence.lt.bsc.es` (default)
- `192.168.24.120` (local development)

For a value beginning with `http`, the client appends endpoint paths directly. For a bare host/IP, it constructs `http://<host>:8082/<endpoint>`. Avoid a trailing slash. To add a different server to the UI, update `serverOptions` in `MainActivity.kt`; default values also exist in `MainViewModel.kt` and `EloquenceAPI.kt`.

`RETRIEVER_ADDRESS` is currently the backend selector `public`, not a client connection to port 7997.

| Endpoint | Method | Client use |
| --- | --- | --- |
| `/query` | POST | Query transcription, WhisperX diarization, and generated answers |
| `/ingest` | POST | Transcribed text, speaker turns, and snippet metadata |
| `/list_vs` | GET | Available conversation indices (`available` array) |
| `/delete_vs` | DELETE | Delete a conversation index |

The backend must support task names `Transcription`, `Diarization`, `RAG_with_Wiki`, and `Wiki`, as well as the configured model identifiers. Transcription/ingestion requests use multipart forms; answer requests use a form-encoded `body` JSON field. The client expects WhisperX `text` and `transcription_turns`, and ingestion confirmation `{"status":"success"}`. Answer responses may include `text`, `documents`, and `documents_metadata`.

The manifest permits cleartext HTTP for the local development option. This client does not add an authorization header; backend authentication/access control must be considered separately.

## Build and install

From the project root on Linux/macOS:

```bash
chmod +x gradlew
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:installDebug
```

On Windows, use `gradlew.bat` instead of `./gradlew`. The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Alternatively, open `wuw-service-app` in Android Studio and run the `app` configuration.

The existing tests are template tests; passing them does not validate microphone capture or backend integration.

## Usage

1. Launch the app and grant microphone permission. The foreground service starts automatically.
2. Open **Configurations**, choose backend/model/language parameters, and save them.
3. For a new conversation, choose **Record Conversation**, enter a name and chunk duration, then start. Use **Stop Conversation** to stop capture; final-chunk processing and queued uploads continue afterward.
4. Wait until processing finishes before starting another conversation. Select the desired stored conversation for contextual answers.
5. Say "OK Aura", then ask a question. Capture ends after the configured continuous silence; transcription and answer generation run on the backend.
6. Inspect retrieved snippets when available. **Clear History** clears local dialogue memory and the current answer/snippet state; it does not delete stored indices. Use the conversation deletion control for that.

Conversation names are sanitized and suffixed with a local timestamp in `dd-MM-yyyy_HH:mm` format. The normal UI chunk-duration default is **30 seconds**; invalid integer input falls back to 10 seconds. Use a positive duration.

## Default settings

| Setting | Default |
| --- | --- |
| Server | `https://eloquence.lt.bsc.es` |
| LLM | `Gemma 4 31B` |
| Query transcriber | `Whisper` |
| Language | `es` (UI options: `en`, `es`, `it`) |
| Query silence | `2.0` seconds |
| Retrieved documents (`docs_k`) | `10` |
| Temperature / top-p | `0.7` / `0.9` |
| Maximum tokens | `1000` |
| Conversation audio chunks | `30` seconds |
| Text chunk size | `500` |
| Embeddings | `sentence-transformers/all-MiniLM-L6-v2` |
| Splitting strategy / percentile | `recursive` / `0.9` |

Settings and dialogue history are held in memory; they are not persisted across process restarts. Long conversation chunks always use WhisperX, independently of the short-query transcriber selection.

## Recording and data handling

Audio is captured as 16 kHz mono PCM and encoded as 16-bit WAV. Short queries are assembled in memory and uploaded. Long recordings are saved temporarily in the app cache and processed one chunk at a time.

The first successfully ingested chunk of each recording session uses `append=false`; later successful chunks use `append=true`. Do not assume an existing index will be preserved if a new session reuses its name. Silent chunks do not advance the ingestion counter. Failed chunks receive up to three retries after the initial attempt, with nominal delays of one, two, and three seconds; after exhaustion they are discarded and processing continues.

Audio files are deleted after successful ingestion, no-speech responses, or exhausted retries. Stopping capture does not mean all uploads have completed. The upload queue is in memory and does not survive process death. Remote retention/deletion is controlled by the backend, not guaranteed by this client. Debug logs include transcriptions, request details, and responses.

## Permissions and troubleshooting

The manifest declares microphone, internet, foreground-service, notification, legacy external-storage, and microphone/data-sync foreground-service permissions. The startup runtime prompt requests microphone access only. Notification permission is declared but has no dedicated runtime request flow in this snapshot.

- **Wake word unavailable:** obtain `sgru-1.0.31.ptl` through an authorized internal channel, place it in the assets directory, check microphone permission, and inspect Logcat for model-loading errors.
- **VAD initialization fails:** verify `silero_vad.onnx` exists and the ONNX runtime loads on the device.
- **Network requests fail:** confirm device access to the selected server and the backend's expected model/task names. Local development uses port 8082.
- **No stored conversations:** check `/list_vs` and retriever selector `public`; allow queued ingestion to finish.
- **Audio is silent or capture fails:** test on a physical device and check competing microphone users, including the app's wake-word/query/conversation capture paths.
- **Service remains active after closing the screen:** closing the activity does not explicitly stop the started foreground service.

Before release, test permission denial, repeated start/stop, leaving the activity, backend failure/recovery, and both query modes on a physical device. This repository is a research prototype; these instructions do not constitute a completed device validation.

## License

The source code is licensed under the [Apache License 2.0](LICENSE). See
[NOTICE](NOTICE) for attribution notices and [MODEL_ASSETS.md](MODEL_ASSETS.md)
for runtime assets that are not distributed with this repository.
