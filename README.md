# VedaReader

VedaReader is an offline Android reader for Bhaktivedanta VedaBase libraries. It is built with Kotlin, Jetpack Compose, and a native WebView-based reading surface.

## Requirements

- Android Studio with JDK 17
- Android SDK 35
- Android 8.0 (API 26) or newer

Create `local.properties` with your Android SDK path, or set `ANDROID_HOME`.

## Build

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```

Local reference databases, generated APKs, build outputs, and signing keys are intentionally excluded from this repository.