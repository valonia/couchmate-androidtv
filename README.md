# Couchmate for Android TV

The Android TV app for [Couchmate](https://github.com/valonia/couchmate) – watch together, in sync.

The app is a front end: it plays video in sync with everyone else and has search, favourites, history,
settings and translation built in. Everything else – the menu, the screens, what is played and how the player
reacts to the remote – comes from the plugins on the Couchmate server. The app draws what they describe.

Kotlin, Compose for TV and ExoPlayer (Media3).

## Install

The easiest: open `http://<your Couchmate server>:8787/download` on the TV (or a phone) and get the app from there.

By hand: enable developer mode and network debugging on the TV, then

```bash
adb connect <tv-ip>
adb install app-debug.apk
```

Start the app, write the server's address (e.g. `192.168.1.10:8787`), your name and the room's PIN code.

## Build

Open the folder in Android Studio, or run `gradlew.bat assembleDebug` / `./gradlew assembleDebug` (needs the
Android SDK). The APK ends up in `app/build/outputs/apk/debug/`.

During development the app runs in an Android TV emulator; the emulator reaches a server on the same computer at
`http://10.0.2.2:8787`.

## How it fits together

| File | |
|---|---|
| `SyncClient.kt` | The WebSocket to the server: the room's state, favourites, reminders, chat and every command |
| `PlaybackSync.kt` | Makes the player follow the room – speed corrections, seeks, buffering the next song |
| `Api.kt` | The server's HTTP API: menu, views, search, plugin data |
| `ui/ViewRenderer.kt`, `ui/ViewBlocks.kt` | Draws the screens plugins build from building blocks |
| `ui/PlayerScreen.kt` | The player: the plugin's keys, overlay and settings, the queue, chat and "next" |
| `I18n.kt` | The app's texts, translated by the server into the TV's language |

[`docs/store-listing.md`](docs/store-listing.md) holds the texts for the app stores.
