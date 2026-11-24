# My AOD

Personal always-on display for Android (8.0+). Kotlin + Jetpack Compose. Not affiliated with AOA.

## Install
1. Push to GitHub. The **Build APK** workflow produces `aod-debug-apk` under Actions → latest run → Artifacts.
2. Download, unzip, sideload the APK.
3. Open the app and grant: Notification access, Draw over other apps (both required), plus optional Calendar, Location, notification and battery permissions.
4. Turn on **Enable always-on display**, then switch the screen off.

## How it works
A foreground service watches for screen-off and launches a black, dimmed full-screen activity over the lock screen.
- Triple-tap wakes the phone (to the lock screen).
- Power button turns the screen fully off; it shows the AOD again after the normal screen timeout.
- Face-down or in a pocket: the display goes solid black.

Build locally: `gradle assembleDebug` (needs JDK 17 and the Android SDK).
