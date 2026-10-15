# Mama TV

IPTV player for Android TV / Google TV / Fire TV, built for the remote control.
Works with any provider that uses the **Xtream Codes** API (the usual `get.php?username=…&password=…` links).
The app contains no channels or accounts — you sign in with your own subscription.

Languages: Hrvatski · English · Italiano · Deutsch

## Features

- **Live TV** with categories, favourites, channel logos and now/next programme
- **TV guide** grid (TiviMate style)
- **Movies & series** with search, favourites, "continue watching", watched marks and resume
- **Multiple profiles** (several subscriptions, switch in settings)
- **Type from your phone**: scan a QR code on the TV and use the phone keyboard for any text field
- Auto-start when the TV turns on, resume last channel, selectable background and app name/colour

## Install (Android TV, Google TV, Fire TV)

The APK is not on Google Play; install it with the **Downloader** app:

1. Install **Downloader** (by AFTVnews) from the Play Store / Amazon Appstore.
2. Fire TV only: Settings → My Fire TV → Developer options → Install unknown apps → allow Downloader.
   Google TV / Android TV: Downloader asks for the permission the first time.
3. Open Downloader and enter this address:

   ```
   https://github.com/lukacaterpilar/iptvStreamer/releases/latest/download/MamaTV.apk
   ```

4. Install, open Mama TV and sign in with your provider's server, username and password
   (or paste your full M3U link into the server field — press OK on a field to type from your phone).

**Updating:** repeat step 3 — the new version installs over the old one and keeps your login and favourites.

Requirements: Android 7.0 or newer (Fire TV Stick 4K and newer; the original 1st/2nd-gen Fire TV Stick is not supported).

### Optional: start automatically when the TV turns on

Android 10+ only allows this once the app may display over other apps. From a computer with adb:

```bash
adb connect <tv-ip>:5555
adb shell appops set hr.mojatv.app SYSTEM_ALERT_WINDOW allow
```

## Remote control

| Screen | Keys |
|---|---|
| Live player | ▲▼ or CH± change channel · OK info bar (OK again: channel list) · ◀ channel list · 0-9 channel number · Back exit |
| Movies / series | OK pause · ◀▶ ±10 s · ⏪⏩ ±60 s · Back exit (position is remembered) |
| Lists | long OK on a channel / poster = favourite · long OK on an episode = play from the start |

## Build from source

```bash
export ANDROID_HOME=~/Android/Sdk          # SDK with platform 35 + build-tools 35
gradle assembleRelease                      # Gradle 8.9, JDK 17+
# -> app/build/outputs/apk/release/MamaTV.apk
```

Without a `keystore.properties` file the APK is signed with the debug key. Releases are signed with the
project's release key (`keystore.properties` → `storeFile`, `storePassword`, `keyAlias`, `keyPassword`; not in git).

## Tech

Kotlin · Jetpack Compose · Media3/ExoPlayer · OkHttp · Coil · ZXing (QR). Min SDK 24, target SDK 35.
