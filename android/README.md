# FreeFlow for Android

This module is a native Android client for the FreeFlow dictation workflow. It
records a 16 kHz mono WAV file, sends it to the configured OpenAI-compatible
`audio/transcriptions` endpoint, and optionally sends the returned text to
`chat/completions` for literal cleanup. The provider key is encrypted with an
Android Keystore AES key. The completed transcript can be copied or shared.

Android cannot provide the macOS app's global keyboard shortcut or paste into an
unrelated application, so dictation is started from this screen and the result
is copied or shared explicitly.

## Build an installable APK

Install Android Studio (or the Android command-line SDK), including JDK 17,
platform `android-35`, and build-tools `35.0.0`. Then run:

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_SDK_ROOT=$HOME/Library/Android/sdk
./android/build-apk.sh
```

The script writes the signed debug APK to
`build/android/FreeFlow-Android-debug.apk`. A debug APK is directly
installable on a device with USB debugging enabled:

```bash
adb install -r build/android/FreeFlow-Android-debug.apk
```

Open FreeFlow, enter an API key for Groq or another OpenAI-compatible provider,
and tap **Start dictation**. The default base URL and Whisper model target
Groq; both can be changed in the app.

Before distributing a build, run this device smoke test: install the APK,
grant microphone permission, dictate a synthetic phrase such as “FreeFlow test
one two three”, confirm that a transcript appears, tap **Copy**, and paste it
into a local note. Repeat once with cleanup disabled to verify the raw
transcription path. This repository was built and signature-checked here, but
the physical-device and real-provider portions remain pending until an Android
device with a test provider key is available. The local API 35 emulator smoke
test already covers installation, launch, microphone permission, recording
start/stop, encrypted settings persistence, the safe failed-provider path, and
successful transcription plus cleanup responses from a synthetic HTTPS
OpenAI-compatible provider.
