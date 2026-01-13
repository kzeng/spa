# Seamless Passage Application (SPA)

Android single-screen app using Material 3 + Jetpack Compose.

- Portrait, tablet 10" 16:10 (1200×1920)
- Front camera immersive preview
- Face outline overlay when detected
- Bottom fixed 25% translucent status area (icons + text)
- UI state machine: Idle / FaceDetected / AuthSuccess / Denied / Error
- TTS voice prompts
- Mocked services: `face_auth`, `sip2_check`, `gate_open`

## Requirements
- Android Studio 2025.2 (Otter 2)
- JDK 17
- AGP 8.2.2
- Kotlin 1.9.22
- minSdk 23, targetSdk 33

## Build
1. Open the project in Android Studio (Otter 2).
2. If Gradle wrapper is requested, let Android Studio generate/upgrade it for AGP 8.2.
3. Sync and build.

## Run
- Grant camera + microphone permissions.
- The app mocks detection and will automatically transition through states.

## Notes
- CameraX is integrated for front preview. Real face detection can be wired via ML Kit or internal libs in `onFaceDetected()` trigger.
- Bottom status area height uses `screenHeightDp * 0.25` to ensure it sticks to the bottom with no gap.
