# ELOQUENCE Pilot 1 Android App

Pilot 1 is an Android wake-word and conversation-recording prototype for the ELOQUENCE WP5 pilot work. The app continuously runs a foreground microphone service, detects a local wake word model, records user speech, and sends audio or transcribed conversation chunks to the ELOQUENCE backend for transcription, RAG, and vector-store indexing.

## Project Structure

```text
eloquence_pilot1/
├── app/                  # Main Android application
├── gradle/wrapper/       # Gradle wrapper files
├── build.gradle          # Root Gradle configuration
├── settings.gradle       # Module registration
└── gradlew               # Linux/macOS Gradle wrapper
```

Main app code lives under:

```text
app/src/main/java/com/example/wuw_service_app/
```

Important classes:

- `MainActivity.kt`: Jetpack Compose UI and permission bootstrap.
- `MainViewModel.kt`: UI state, configuration updates, and service commands.
- `AudioService.kt`: foreground service for wake-word detection and short query recording.
- `ConversationRecorder.kt`: long-form conversation chunk recording.
- `EloquenceAPI.kt`: HTTP integration with transcription, RAG, ingestion, and vector-store management endpoints.

## Main Features

- Local wake-word detection using the PyTorch Lite model in `app/src/main/assets/sgru.ptl`.
- Foreground Android service for continuous microphone monitoring.
- Short query flow:
  - wake word is detected;
  - the user query is recorded until silence;
  - audio is sent to Whisper through the ELOQUENCE backend;
  - the transcribed query is answered with RAG and shown in the app.
- Conversation recording flow:
  - user starts a named conversation from the UI;
  - audio is split into fixed-duration chunks;
  - chunks are transcribed with WhisperX diarization;
  - transcriptions are ingested into a vector-store index.
- UI controls for:
  - selecting the active conversation context;
  - deleting vector-store conversations;
  - clearing local chat history;
  - editing LLM, RAG, language, and vector-store parameters.

## Requirements

- Android Studio with Android Gradle Plugin support.
- JDK compatible with Java 8 bytecode used by the app module.
- Android SDK 33.
- Android device or emulator running API 26 or newer.
- Microphone access on the target device.
- Network access from the device to the configured ELOQUENCE backend.

The checked-in Gradle wrapper uses Gradle `8.13`.

## Backend Configuration

The app currently uses a hard-coded backend IP in `EloquenceAPI.kt`:

```kotlin
private const val BASE_IP = "192.168.24.120"
```

Derived endpoints:

- `http://192.168.24.120:8082/query`
- `http://192.168.24.120:8082/ingest`
- `http://192.168.24.120:8082/delete_vs`
- `http://192.168.24.120:8082/list_vs`
- `http://192.168.24.120:7997` for the retriever/vector-store address

Update `BASE_IP` before building if the backend is running on another host. The Android manifest enables cleartext HTTP traffic with `android:usesCleartextTraffic="true"`.

## Wake-Word Model Weights

The wake-word model weights are expected locally at:

```text
app/src/main/assets/sgru.ptl
```

This file is not included in the online repository because the model is proprietary to Telefonica. Developers who need to run the wake-word flow must obtain the model through the appropriate internal channel and place it at the path above before building the app.

## Build

From this directory:

```bash
./gradlew assembleDebug
```

To run unit tests:

```bash
./gradlew test
```

To install the debug APK on a connected device:

```bash
./gradlew installDebug
```

You can also open `eloquence_pilot1` in Android Studio and run the `app` configuration.

## Runtime Permissions

The app requests microphone permission at startup. The manifest also declares:

- `RECORD_AUDIO`
- `FOREGROUND_SERVICE`
- `INTERNET`
- `POST_NOTIFICATIONS`
- `WRITE_EXTERNAL_STORAGE`

On newer Android versions, notification permission may also need to be granted so the foreground service notification is visible.

## App Workflow

1. Start the app.
2. Grant microphone permission.
3. The foreground `AudioService` starts and loads the local wake-word model.
4. Use **Configurations** to set model, RAG, language, and vector-store parameters.
5. Use **Record Conversation** to create or append a named conversation context.
6. Use **Change Conversation** to select a vector-store index for RAG answers.
7. Say the wake word to trigger a short query. The app records until silence, sends the audio to the backend, and displays the generated answer.

## Conversation Recording

When a conversation recording starts, the app asks for:

- conversation name, used as the vector-store `index_name`;
- chunk duration in seconds, defaulting to `10`.

Each chunk is written temporarily as a WAV file in the app cache directory, uploaded to the backend, and deleted after ingestion. The first chunk creates or replaces the index, and later chunks append to it.

## Default In-App Configuration

Current defaults in `MainViewModel.kt` and `EloquenceAPI.kt` include:

- LLM name: `Gemma 4 (TID)`
- language: `es`
- `docs_k`: `5`
- temperature: `0.7`
- `top_p`: `0.9`
- max tokens: `1000` in the UI, `500` in `EloquenceAPI`
- embedding model: `sentence-transformers/all-MiniLM-L6-v2`
- chunk size: `500`
- splitting strategy: `recursive`
- percentile: `0.9`

## Troubleshooting

- If wake-word detection does not start, verify that `app/src/main/assets/sgru.ptl` is present and that microphone permission is granted.
- If backend requests fail, confirm the device can reach `192.168.24.120` on ports `8082` and `7997`, or update `BASE_IP`.
- If recording cannot start, another app or service may already be using the microphone.
- If vector-store conversations are missing, check `/list_vs` on the backend and confirm the retriever address is reachable.
- For Android 13 and newer, grant notification permission if the foreground service notification is blocked.

## Notes

- The app package is `com.example.wuw_service_app`.
- The displayed app name is `wuw-service-app`.
