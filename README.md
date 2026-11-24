# Cinema: a home screen for the Sony BRAVIA

A custom Android TV launcher for the BRAVIA 4K UR3 (Android TV 10), built from mockup A "Cinema":

- **Continue watching** from your Plex server, with the focused show's artwork filling the screen
- **Your media apps in two rows of seven** (more rows scroll down): Plex, Netflix, YouTube, Disney+, TV Vlaanderen, VTM GO, VRT MAX, Play, Telenet TV, DAZN, Apple TV, Red Bull TV, YouTube Music and Live TV.
  The focused app fills the screen with its own artwork: what you were last watching in it, or the top of the rows
  it publishes for the TV home screen. Plex shows what you'd resume. Apps that publish no artwork (YouTube, DAZN,
  VRT MAX, Play, YouTube Music) show their own banner, large and dimmed, on a wall of its background colour.
- **Inputs, All apps and Settings** in the top bar, so nothing from the old home screen is out of reach

## Using it

| Remote | What happens |
|---|---|
| OK on a Continue watching tile | Resumes it in Plex |
| OK on an app | Opens the app |
| Hold OK on an app | Move it one place left or right, or remove it from the home screen |
| All apps (top right) | Every app; hold OK on one to add it to the row or remove it |
| Home or Back | Back to the start of Continue watching |
| Google Play button on the remote | Opens Plex (once switched on, see below) |

The first time, the Continue watching row shows **Link Plex**. Press OK and enter the 4-character code at
**plex.tv/link** on your phone or computer. The TV never sees your Plex password; the token it gets back
stays in the launcher's private storage on the TV.

Apps you install later join the row automatically, unless they're utilities (store, help, timers…).

## Install and make it the home screen

Download `cinema-launcher-1.0.apk` from the [Releases page](https://github.com/PieterLingier/bravia-cinema-launcher/releases)
(or build it, see below). Switch on developer mode on the TV (Settings > Device Preferences > About, press OK on
"Build" seven times) and USB or network debugging in Developer options, connect with `adb connect <TV-IP>:5555`, then:

```powershell
adb install -r cinema-launcher-1.0.apk
```

Let Cinema send Plex the episode link while Plex is on screen. Android files this under "display over other apps",
but Cinema never draws over other apps:

```powershell
adb shell appops set local.cinema.launcher SYSTEM_ALERT_WINDOW allow
```

Let Cinema read the artwork other apps publish for the TV home screen (their Watch Next items and rows), for the
app backdrops. Android calls this "read TV listings"; without it, every app gets its banner backdrop instead:

```powershell
adb shell pm grant local.cinema.launcher android.permission.READ_TV_LISTINGS
```

Android TV gives the Home button to Google's home screen for as long as it's enabled: its Home entry outranks any
downloaded launcher, so the "default home app" setting is ignored. Disable it and Cinema takes over:

```powershell
adb shell pm disable-user --user 0 com.google.android.tvlauncher
```

Google's home screen stays installed. Without it you lose its Home/Shop/Apps tabs, its sponsored and recommendation
rows and its notification panel; voice search, the Assistant, Sony's settings and every app keep working.

## Google Play button opens Plex

On this BRAVIA the remote's Google Play button sends key code 195 (`KEYCODE_BUTTON_8`), which Sony's vendor service
turns into "open Google TV". Cinema's accessibility service takes that one key before Sony's handler sees it and
opens Plex instead; every other key passes through unchanged, and it reads nothing from the screen.

Switch it on with the remote: **Settings > Device Preferences > Accessibility > Cinema: Google Play button opens Plex**,
then confirm Android's warning. Switch it off there to get the original behaviour back. To remap a different button,
change `REMAPPED_KEY` in `ButtonService.kt`.

## Go back to the Google home screen

```powershell
adb shell pm enable com.google.android.tvlauncher
```

To remove Cinema completely, go back first, then run `adb uninstall local.cinema.launcher`.

## Build

Needs JDK 17 and the Android SDK with platform 35 and build-tools 35.0.0. Set `JAVA_HOME` and `ANDROID_HOME`, then:

```powershell
.\gradlew.bat assembleRelease
```

The APK lands in `build\outputs\apk\release\`. On Windows, a deeply nested folder can exceed the path limit;
`-Pcinema.buildDir=C:\some\short\path` moves the build output.

Release builds are signed with the building PC's debug key. A build from another PC can't update the installed
app in place: uninstall it first.

## How it works

| File | Role |
|---|---|
| `src/main/kotlin/HomeActivity.kt` | The home screen: hero, both rows, top bar, backdrop and app-colour glow |
| `src/main/kotlin/TvArt.kt` | The artwork apps publish for the TV home screen, for their backdrops |
| `src/main/kotlin/BannerArt.kt` | The banner backdrop for apps that publish no artwork |
| `src/main/kotlin/Plex.kt` | Plex sign-in (plex.tv/link code), server discovery, Continue watching, resized artwork |
| `src/main/kotlin/Apps.kt` | Which apps are in the row and in what order; launching apps |
| `src/main/kotlin/Rows.kt` | The tiles of both rows and the focus zoom |
| `src/main/kotlin/PlexLinkActivity.kt` | The "enter this code at plex.tv/link" screen |
| `src/main/kotlin/AllAppsActivity.kt` | The All apps grid |
| `src/main/kotlin/ButtonService.kt` | Google Play button → Plex (accessibility key filter) |

- **Finding the server:** the launcher asks plex.tv for your servers and uses the first address that answers from
  the TV: home network over HTTPS, then plain HTTP, then remote, then Plex's relay. It remembers the one that worked
  and looks again when it stops answering.
- **Continue watching:** `/hubs/continueWatching/items` on the server, falling back to older endpoints and finally
  `/library/onDeck`. It refreshes every time you come back to the home screen.
- **App backdrops:** apps keep publishing artwork for the TV home screen (Watch Next items and their rows, or
  "channels") to Android's TV provider, even with Google's home screen off. With "read TV listings" Cinema may read
  it. For the focused app it shows the newest Watch Next item, otherwise the top of the rows Google's home screen
  showed, then the app's other rows. Only landscape images are used, since a portrait poster would crop badly. The
  list refreshes every time you come back to the home screen. Without artwork (or when it won't load), Cinema draws
  the app's banner at the highest density the app ships, right of the title, on its edge colour; bright banners are
  dimmed so the text stays readable and a dark room stays dark. The colour glow is left for apps without a banner.
- **Opening an item:** OK opens Plex normally, waits 5.5 s, then sends Plex's own link format (found inside the Plex app),
  `plex://watch/video?uri=server://<server>/com.plexapp.plugins.library/library/metadata/<id>&offset=<seconds>`, every
  3 s until the server reports the item playing (at most 36 s). The offset makes Plex resume where you stopped.
  The wait is needed because a Plex that is *started* by a link ignores links for minutes, and right after starting
  it ignores them for a few seconds; extra links while it loads do no harm. All of this was measured on this TV.
  A 4K file whose audio the server converts takes about 15 s from OK to picture.

Cinema is released under the MIT License, see `LICENSE`. Space Grotesk font: SIL Open Font License, see
`licenses/SpaceGrotesk-OFL.txt`. The settings icon is adapted from
Feather Icons (MIT).
