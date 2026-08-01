# Amply (1)

Per-app volume control for Android, designed with Nothing OS aesthetics.

Amply (1) intercepts hardware volume button presses and displays a custom overlay that allows independent volume control for each currently playing audio application. It uses Shizuku for privileged access without requiring root.

---

## Table of Contents

- [Features](#features)
- [Requirements](#requirements)
- [Installation](#installation)
- [How It Works](#how-it-works)
- [Comparison with Similar Projects](#comparison-with-similar-projects)
- [Limitations](#limitations)
- [Building from Source](#building-from-source)
- [License](#license)

---

## Features

- **Per-App Volume Control**: Adjust the volume of individual applications independently
- **Volume Overlay**: Custom overlay appears when volume buttons are pressed, replacing or supplementing the system volume UI
- **Auto-Apply Volume**: Volume settings persist across song changes and new audio players within the same app
- **Real-time Detection**: Uses AudioPlaybackCallback for immediate detection of new audio streams
- **Nothing OS Design**: Black and red color scheme inspired by Nothing Phone aesthetics
- **No Root Required**: Uses Shizuku for privileged operations without root access
- **Volume Persistence**: Remembers volume levels for each app by UID
- **Logarithmic Volume Curve**: Natural-feeling volume control that matches human hearing perception
- **Per-App Volume-Key Stand-Down**: Choose installed apps that should receive hardware volume buttons directly
- **Temporary Pause**: Pause Amply from the circular control above the expanded pill, with a configurable duration and an in-app Restore now action
- **Tabbed Control Center**: Separate Apps, Pill, Keys, and Access pages keep related controls together
- **Pinned Overlay Apps**: Keep previously detected audio apps in the expanded overlay even while they are idle

---

## Requirements

| Requirement | Details |
|-------------|---------|
| **Minimum Android Version** | Android 10 (API 29) |
| **Target Android Version** | Android 14 (API 34) |
| **Shizuku** | Required, version 11 or higher |
| **Permissions** | Overlay permission, Accessibility Service |

### Tested Devices

- Nothing Phone (2a) running Android 15 / Nothing OS
- Should work on any device running Android 10 or higher with Shizuku support

---

## Installation

1. Install [Shizuku](https://github.com/RikkaApps/Shizuku/releases) and start the Shizuku service
2. Install Amply (1) APK
3. Follow the setup wizard:
   - Grant Shizuku permission when prompted
   - Enable the Accessibility Service for volume key detection
   - Grant overlay permission
4. Press a hardware volume button to see the overlay

---

## How It Works

Amply (1) uses a multi-layered approach to achieve per-app volume control:

### 1. Audio Session Detection

The app uses `AudioManager.getActivePlaybackConfigurations()` to enumerate all currently active audio players. Each `AudioPlaybackConfiguration` represents an audio stream from an application.

### 2. Privileged Access via Shizuku

Because Android sanitizes UID and player proxy information when accessed from regular app processes, Amply (1) runs a UserService in Shizuku's privileged shell process (UID 2000). This allows access to:

- `AudioPlaybackConfiguration.getClientUid()` - Identifies which app owns the audio stream
- `AudioPlaybackConfiguration.getPlayerProxy()` - Returns the IPlayer interface for volume control

### 3. Volume Control via IPlayer

The `IPlayer.setVolume()` hidden API is used to set the internal volume multiplier (0.0 to 1.0) for each audio player. This is the same API used by MIUI's "Adjust media sound in multiple apps" feature.

### 4. Volume Key Interception

An Accessibility Service monitors volume key events. When detected, it triggers the overlay display and routes volume changes through the custom UI.

### 5. Real-time Callback

`AudioManager.registerAudioPlaybackCallback()` provides real-time notifications when new audio players start or stop. This allows immediate application of saved volume settings to new streams.

### Volume-key routing

Android sends accessibility key callbacks before the foreground app, so Amply cannot learn afterward whether that app would have consumed a specific press. The **Volume Key Handling** settings provide explicit routing instead: selected packages receive the entire down/repeat/up sequence, while Amply intercepts keys elsewhere. If a selected app declines the event, Android continues with its normal volume behavior and pill.

For one-off situations, expand Amply's pill and press the circular power button above it. Amply then passes volume keys through globally for the configured period (five minutes by default). The duration can be changed in settings, and **Restore now** re-enables Amply immediately.

The pause duration can also be set to **Turn back on only manually**. This state persists until **Restore now** is selected, including after a reboot.

The expanded overlay normally contains currently playing apps, pinned known-audio apps, and the foreground app after it produces audio during its current foreground visit. That foreground row remains available after playback stops, but leaving and returning to the app starts a new visit that must produce audio again. Hidden apps remain excluded in every case. If Shizuku disconnects, these controls are replaced with a compact disconnected indicator until the privileged service recovers.

---

## Comparison with Similar Projects

| Feature | Amply (1) | VolumeManager | AudioHQ |
|---------|-----------|---------------|---------|
| **Root Required** | No | No | Yes |
| **Shizuku Required** | Yes | Yes | No |
| **Minimum Android** | Android 10 | Android 10 | Android 7 |
| **Volume Control Method** | IPlayer.setVolume() | IPlayer.setVolume() | Native AudioFlinger patches |
| **Volume Persistence** | Yes (per UID) | No | Yes |
| **Auto-apply to New Streams** | Yes | Yes | Yes |
| **Volume Key Override** | Yes (Accessibility) | Yes (Accessibility) | No |
| **Custom Overlay** | Yes | Yes | No (in-app UI only) |
| **App Detection** | Shizuku UserService | Shizuku direct | Root shell |
| **Design Style** | Nothing OS | Material You | Material Design |
| **Open Source** | Yes | Yes | Yes |

### Key Differences

**vs VolumeManager (yume-chan)**

Both projects use the same underlying IPlayer.setVolume() API via Shizuku. The main differences are:

- Amply (1) persists volume settings per UID and auto-applies them to new audio players
- Amply (1) uses a Shizuku UserService to access privileged AudioPlaybackConfiguration data, while VolumeManager may use different approaches
- Design philosophy: Amply (1) targets Nothing OS aesthetics, VolumeManager uses Material You

**vs AudioHQ (Alcatraz323)**

AudioHQ requires root access and uses native AudioFlinger patches for volume control. This provides more complete control but requires a rooted device. Amply (1) achieves similar results without root by using Shizuku and the IPlayer API.

---

## Limitations

1. **Native Audio Engines**: Some games and apps using OpenSL ES, AAudio, or audio middleware (FMOD, Wwise) may not respond to volume changes. The IPlayer API only controls audio streams that go through Android's Java audio APIs.

2. **Volume Reading**: The IPlayer API is write-only. Volume set by one app is not reflected in another app reading the volume.

3. **Screen Recording**: Audio captured by screen recording may not reflect per-app volume changes, as recording may capture audio before the volume multiplier is applied.

4. **Shizuku Dependency**: Shizuku must be running for the app to function. If Shizuku stops, per-app volume control will not work.

---

## Building from Source

### Prerequisites

- Android Studio Hedgehog or newer
- JDK 17 or higher
- Android SDK 34

### Build Steps

```bash
# Clone the repository
git clone https://github.com/farizanjum/amply-1.git
cd amply-1

# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease
```

APK output locations:
- Debug: `app/build/outputs/apk/debug/app-debug.apk`
- Release: `app/build/outputs/apk/release/app-release-unsigned.apk`

---

## Project Structure

```
app/src/main/java/com/amply/one/
├── MainActivity.kt           # Main activity and setup UI
├── AmplyApplication.kt       # Application class
├── audio/
│   ├── AudioSessionManager.kt    # Core session management and volume control
│   ├── AudioSessionDetector.kt   # Dumpsys-based fallback detection
│   └── PlayerVolumeController.kt # Local reflection fallback
├── service/
│   ├── OverlayManager.kt         # Overlay window management
│   ├── OverlayService.kt         # Foreground service for overlay
│   └── VolumeKeyService.kt       # Accessibility service for key detection
├── shizuku/
│   ├── ShizukuRepository.kt      # Shizuku permission and shell commands
│   ├── ShizukuVolumeManager.kt   # UserService client
│   ├── VolumeUserService.kt      # Privileged UserService (runs in Shizuku)
│   └── IVolumeService.kt         # AIDL interface
└── ui/
    ├── overlay/VolumeOverlay.kt  # Overlay UI composables
    ├── setup/                    # Setup wizard screens
    └── theme/                    # Nothing OS color theme
```

---

## License

This project is provided as-is for educational and personal use.

---

## Acknowledgments

- [Shizuku](https://github.com/RikkaApps/Shizuku) by RikkaApps - Privileged API access without root
- [VolumeManager](https://github.com/yume-chan/VolumeManager) by yume-chan - Inspiration and IPlayer API documentation
- [AudioHQ](https://github.com/Alcatraz323/audiohq_md2) by Alcatraz323 - Prior art in per-app volume control
- [HiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass) by LSPosed - Hidden API access
