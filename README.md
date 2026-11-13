<p align="center"><img src="docs/logo.png" width="300" alt="VeloCinema logo"></p>

# VeloCinema — a responsive home-theatre launcher for Chromecast with Google TV (HD)

Part of the Velo family (see VeloGuardOS): built to stay fast and responsive on low-end hardware.

A custom home screen for Android TV / Google TV 14, written in plain Kotlin + Android Views
(no AndroidX) so it stays fast on a 1.4 GB Chromecast HD. UI language: Bulgarian.

## Features
- Google TV layout with big square app tiles, folders, move/hide, smooth spring animations and an
  ambient background that glides (in OkLab colour space) to the focused app's colour.
- **„За вас“ (For you)**: live COTV channels, films on TV today and films from the last 7 days
  (catch-up), now playing, recent apps — built from a public XMLTV guide that the TV keeps for 7 days.
- **Search**: apps, COTV channels, the TV guide (English titles are translated to Bulgarian via
  Wikipedia/Wikidata), and a "most likely" result driven by on-device usage memory
  (e.g. plays a recently heard song straight through Spotify's media session).
- **Quick settings** (gear or *hold Home*): Wi-Fi list + password + link speed + real speed test,
  Bluetooth pairing with battery levels, sleep timer, screensaver, Google Home, audio output,
  notifications.
- Replacement **volume slider** (OK while it is visible → choose TV / headphones / speakers),
  notification pop-ups, earbuds battery card (Galaxy Buds: left / right / case),
  music visualiser screensaver with beat tracking.
- Google TV **profiles** with an old-style row PIN pad for the owner's profile.
- "Send to TV" page on `http://<tv>:8765` for phones (links, channels, remote).

## Build & install
```
tools/install.sh          # builds the R8-optimised release APK; installs via adb (+ AOT compile) or over Wi-Fi
tools/setup.sh            # permissions, helper service, notification access, real default home
tools/push-update.sh      # later updates over Wi-Fi (no USB needed)
tools/privacy.sh          # optional: switch off Google feedback/error reporting
tools/restore-google-tv.sh  # undo everything
```

## Why VeloCinema can be the *real* default on Google TV
Android picks the highest-priority Home activity before it looks at the user's default, and only
privileged apps may use a priority above 0. Google TV's launcher uses 2 and its setup app 1, so
`tools/make-default.sh` disables both (reversible). VeloCinema re-enables Google's launcher for a few
seconds when you switch Google TV profiles.

## Personal files
The owner's profile photo lives at `app/src/main/assets/avatar.png`.
The COTV channel list in `assets/channels.json` was captured from the COTV app.
