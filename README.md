# Amply

Amply is a sideloaded Android volume controller that intercepts hardware volume keys through an Accessibility Service and uses a Shizuku UserService for privileged per-app audio-session control. It supports Android 10+ (API 29), targets Android 16/API 36 for production, and continuously supports build-only API 37 compatibility testing.

## What it does

- Displays an accessibility-owned volume pill without the system overlay permission.
- Adjusts Android system streams and independent app-player gain.
- Persists profile-aware per-app volume, pin, and hide settings.
- Lets selected packages receive the full hardware-key gesture through Stand-Down.
- Exposes truthful runtime health, retry, pause, and recovery controls.
- Supports explicit JSON export/import; Android cloud and device-transfer backup are disabled.
- Provides sanitized diagnostics that exclude app labels and package names by default.

## Requirements and permissions

| Dependency | Required | Purpose |
| --- | --- | --- |
| Android 10/API 29+ | Yes | Minimum supported platform |
| [Shizuku](https://github.com/RikkaApps/Shizuku/releases) | Yes | Discovers privileged playback sessions and changes per-app gain |
| Amply Accessibility Service | Yes | Observes hardware volume keys and owns the accessibility overlay window |
| Notifications | Recommended | Shows the foreground runtime state and recovery actions |
| Phone state | Optional | Improves incoming-call detection; without it, ambiguous call-like keys pass through |
| Notification policy access | Diagnostics only | Used only by explicitly launched advanced ringer compatibility checks |
| Network access | Automatic updates | Checks Amply’s latest public GitHub Release at most once per 24 hours while online |

Amply does **not** request `SYSTEM_ALERT_WINDOW`. Its Accessibility Service does not traverse or collect window content.

## Sideload installation

1. Download the versioned APK and matching `.sha256` file from GitHub Releases.
2. Verify the checksum (for example, `sha256sum -c Amply-vX.Y.Z.apk.sha256`).
3. Install and start Shizuku, then grant Amply permission.
4. Install the APK and complete Amply's readiness checklist.
5. Enable the specific Amply Accessibility Service when Android settings opens.

Readiness is always derived from current Shizuku and Accessibility state. Revoking either requirement returns the app to recovery setup; optional permission denial does not block use.

## Safety and limitations

- Shizuku must be installed, running, granted, and connected for per-app controls.
- Some native audio engines may not expose a controllable Android player.
- Android does not expose the power key to ordinary accessibility key callbacks. Amply therefore does not claim to preserve or synthesize screenshot chords; screenshot behavior is device/firmware dependent and must be tested on physical devices.
- When phone-state permission is absent or call state is ambiguous, Amply passes call-like volume gestures through to Android.
- A failed volume operation is passed through rather than consumed. Failed overlay attachment is expired after one second and reported as a recoverable runtime error.
- Android 17/API 37 compatibility builds are test-only. Published releases must target API 36 until the documented background-audio hardening matrix passes.
- Advanced ringer diagnostics temporarily modify ringer mode and ring/notification indexes. They require confirmation, are blocked during calls/ringing/alarms/DND, serialize runs, and restore the captured state in cleanup.

## Settings backup and diagnostics

The Apps tab can export schema-v3 JSON and preview imports. Import files are capped at 2 MiB and 10,000 app records; malformed identities, duplicate records, unsupported schemas, invalid enums/ranges, and non-finite values are rejected before an atomic commit.

- **Merge** overwrites matching imported app identities, keeps other records, unions Stand-Down packages, and replaces global settings.
- **Replace** makes the validated backup the complete app/Stand-Down state and replaces global settings.

If storage is corrupt, affected controls are disabled and recovery remains available through export or reset. Advanced Diagnostics can copy or share build, protocol, permission, connection, and error states without app identities.

## Building

Prerequisites: JDK 17+, Android SDK 37, and a configured `local.properties`.

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease
```

Production builds default to target SDK 36. A non-publishable API 37 compatibility build can be made with:

```bash
./gradlew assembleDebug -PamplyTargetSdk=37
```

Release signing is configured only when all four environment variables exist:

- `AMPLY_KEYSTORE_PATH`
- `AMPLY_KEYSTORE_PASSWORD`
- `AMPLY_KEY_ALIAS`
- `AMPLY_KEY_PASSWORD`

The `vX.Y.Z` GitHub Actions workflow decodes `AMPLY_KEYSTORE_BASE64`, validates tag/version/target metadata, builds a minified signed APK, verifies the signature, and publishes only the APK, checksum, and generated release notes. R8 mappings remain private workflow artifacts.

## Baseline profiles and benchmarks

The app uses `saveInSrc=true`; generated Baseline Profiles live in `app/src/main/generated/baselineProfiles` so a clean release build can rewrite and package them through R8. Regenerate on an API 33+ physical device:

```bash
./gradlew :app:generateBaselineProfile
```

Macrobenchmarks fail if requested expand/collapse targets are absent. A physical-device run is still required for the real Accessibility/Shizuku key-to-overlay path and API 36/37 audio-hardening acceptance matrix.

## License and notices

Amply is licensed under the [Apache License 2.0](LICENSE). Project notices are in [NOTICE](NOTICE), and packaged third-party attributions are in `app/src/main/assets/third_party_attributions.html`.
