# ABS Lite

A minimal, native Android client for [Audiobookshelf](https://www.audiobookshelf.org/),
built to turn an old Android tablet into a dedicated audiobook player: one app,
one server, one user, nothing else.

ABS Lite starts at boot, shows your library with covers, plays your books and
reports your progress back to the server. It has no web view and no
dependencies beyond the Android framework, so it runs where the official app and
a modern browser no longer can (Android 5.0, API 21, and up).

## Use case: a kiosk on an old Android device

The intended deployment is an old tablet that is otherwise unused. Install ABS
Lite, make it the device's home app, and the tablet becomes an appliance:

* The app registers itself as a home launcher, so it starts at boot and the Home
  button returns to it.
* It runs full screen (immersive mode) and can optionally ask Android to pin it
  (screen pinning), so nothing else is reachable by accident.
* The server address and login are compiled into the app. There is no sign-in
  screen to type into.

Setting a particular device up as a kiosk (choosing the default home app,
removing clutter, enabling developer options) differs per vendor and Android
version, and is outside the scope of this project.

## Features

* Library list with covers, a "continue listening" shelf, progress bars, search.
* Player with cover, seek bar, skip back/forward (10 and 30 seconds), chapter
  navigation, and a sleep timer.
* Multi-file books, and playback that continues with the screen off.
* Listening progress is synced to the server every 15 seconds and on pause.

## Requirements

To build (on Windows; the build script is PowerShell):

* JDK 17 or newer (`javac` with `--release 8`, and `keytool`).
* Android SDK build-tools: `aapt2`, `d8`, `zipalign` and `apksigner`.
* An Android platform `android.jar`, API 21 or newer.
* PowerShell 5.1 or newer.

No Gradle, AndroidX or other libraries are used.

To run:

* An Android device with Android 5.0 (API 21) or newer, on the same network as
  your Audiobookshelf server.
* An Audiobookshelf server with a book library. ABS Lite was developed against
  server version 2.37.2.

## Configuration

The server address and login are read from `config.json` at build time and
become part of the APK. Start from the example:

```
copy config.example.json config.json
```

```json
{
  "absUrl": "http://192.0.2.10:3333/audiobookshelf",
  "username": "your-user",
  "password": "your-password",
  "lockTask": true
}
```

* `absUrl`: the server URL **including its router base path**
  (`/audiobookshelf` by default).
* `username`, `password`: the account the app signs in with.
* `lockTask`: ask Android to pin the app on start (screen pinning).
  Defaults to `true`.

`config.json` is git-ignored. Note that the credentials are stored in the APK
in plain form; anyone who has the APK can read them. Use a dedicated,
low-privilege account and do not distribute the APK.

## Building

```
powershell -File native\build.ps1
```

This generates `Config.java` from `config.json`, compiles, converts to DEX,
packages, aligns and signs the app with a local debug keystore (created on first
run), and writes `audiobookshelf-lite.apk` at the top of the tree.

The script looks for the SDK pieces in `..\android-sdk\bt-unpack\android-14` and
`..\android-sdk\plat-unpack\android-5.1.1\android.jar`. Point it elsewhere with
environment variables:

```
$env:ABS_BUILD_TOOLS = 'C:\path\to\build-tools\34.0.0'
$env:ABS_ANDROID_JAR = 'C:\path\to\platforms\android-22\android.jar'
```

## Installing

With the device connected and USB debugging enabled:

```
adb install -r audiobookshelf-lite.apk
```

Then start "ABS Lite" from the app list. To make it the kiosk, select it as the
home app when Android asks which launcher to use.

## Limitations

* **No playback speed control.** The Android framework `MediaPlayer` cannot
  change speed before Android 6. Supporting it would mean a different audio
  pipeline.
* One server, one account, and only the first book library is shown.
  Podcasts and e-books are not supported.
* Plain HTTP on a trusted local network is the expected setup. There is no
  certificate handling beyond what the platform provides.
* Android's screen pinning is not a hard lock; leaving it requires a key
  combination.

## Testing

`test/mock-abs.js` is a small fake Audiobookshelf server (login `test`/`test`)
for trying the app without touching a real server:

```
node test/mock-abs.js
adb reverse tcp:3335 tcp:3335
adb shell am start -n lite.abs/.MainActivity --es base http://127.0.0.1:3335/audiobookshelf --es user test --es pass test
```

The `base`, `user` and `pass` intent extras override the compiled-in settings
for that run only.

## Layout

* `native/` - the Android app (`src/lite/abs/`), its manifest and build script.
* `test/` - the mock Audiobookshelf server.
* `public/`, `server.js` - an earlier, experimental web client and a small
  same-origin proxy for it. Not needed for the app.
* `config.example.json` - template for the build-time configuration.

## Acknowledgements

ABS Lite exists only because of [Audiobookshelf](https://github.com/advplyr/audiobookshelf)
and the people behind it. Thank you to its maintainers and contributors, and to
the self-hosted audiobook community around it, for a server that is a pleasure
to use and whose server and mobile app sources made this client possible to
write.

ABS Lite uses nothing but the server's public HTTP API: login, libraries, items,
covers, playback sessions and progress sync. We rely on that API staying stable,
and we trust it will; where it does change, ABS Lite will follow.

## License

No license has been chosen yet.

## Reporting bugs

Open an issue on the project's GitHub page. Please include the Android version,
the Audiobookshelf server version, and what the app showed.
