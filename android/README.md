# DS100 Datalogger for Android

Android version of the DS100 logger app (Kotlin, Jetpack Compose). See the
[main README](../README.md) for installing the APK and for the features.

## Structure

| File | Content |
|---|---|
| `core/Transport.kt` | memory commands of the logger, simulated logger for demo mode and tests |
| `core/Logger.kt` | header, settings, history download, clock, clearing (no Android dependencies) |
| `UsbTransport.kt` | USB host access: HID SET_REPORT out, interrupt IN, one 8-byte packet per read |
| `Db.kt` | SQLite database (same tables as the Linux app) |
| `Chart.kt` | graph renderer (android.graphics), used on screen and for PNG/PDF export |
| `Export.kt` | CSV, PNG and PDF export, number/date formatting |
| `AppModel.kt` | app state, background jobs |
| `MainActivity.kt` | USB attach/permission/detach handling |
| `ui/App.kt` | the screens |

The app starts when the logger is plugged in (`res/xml/device_filter.xml`, vendor 6465 /
product 32801) and gets USB access that way; if it is opened by hand it asks for permission.

## Build

Needs JDK 17 or newer and the Android SDK with platform 35 and build-tools 35.0.0.

```sh
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
./gradlew testDebugUnitTest assembleRelease
```

* `app/build/outputs/apk/release/app-release.apk` – the APK (signed with the local debug key)
* `app/build/shots/` – screenshots of all screens in demo mode, made by the unit tests
  (Robolectric + Roborazzi)
