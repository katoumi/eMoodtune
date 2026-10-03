# eMoodtune (MoodSync)

eMoodtune is an AI-powered, emotion-adaptive music recommendation and mood forecasting Android application built using Kotlin, Jetpack Compose / ViewBinding, MediaPipe Face Landmarker AI, Spotify App Remote SDK, and Firebase.

---

##  Setup Instructions for Developers

### 1. Prerequisites
* **Android Studio**: Jellyfish | 2023.3.1 or newer.
* **JDK**: Java 11 or Java 17.
* **Android Device / Emulator**: Running Android 7.0 (API Level 24) or higher.
* **Spotify App**: Installed on the device (for App Remote playback).

---

### 2. Configuration Setup

#### A. Firebase Configuration
1. Register your app in the [Firebase Console](https://console.firebase.google.com/).
2. Download your `google-services.json` configuration file.
3. Place `google-services.json` inside the `app/` directory (`app/google-services.json`). You can use `app/google-services.json.template` as a reference guide.

#### B. Spotify API Setup
1. Register an application on the [Spotify Developer Dashboard](https://developer.spotify.com/dashboard).
2. Set the Redirect URI in the Spotify Dashboard to `moodsync://callback`.
3. Copy your Spotify **Client ID**.
4. Open (or create) the `local.properties` file in the project root directory and add your key:

```properties
sdk.dir=YOUR_SDK_PATH
SPOTIFY_CLIENT_ID=YOUR_SPOTIFY_CLIENT_ID
```

---

### 3. Build & Run
1. Sync project with Gradle files (`File -> Sync Project with Gradle Files`).
2. Build and run the `:app` target on your Android device.

---

##  License & Copyright

© 2026 eMoodtune. All Rights Reserved.

This repository and its contents are the intellectual property of eMoodtune. 
This code is provided for portfolio and educational evaluation purposes only. 
You may not copy, reproduce, distribute, publish, display, perform, modify, create derivative works, transmit, or in any way exploit any such content, nor may you distribute any part of this content over any network, including a local area network, sell or offer it for sale, or use such content to construct any kind of database.
