# Cinema: a home screen for the Sony BRAVIA

A custom Android TV launcher for the BRAVIA 4K UR3 (Android TV 10), built from mockup A "Cinema":

- **Continue watching** from your Plex server (or from Netflix, or a row for each), with the focused show's artwork filling the screen
- **Your media apps in two rows of seven** (more rows scroll down; one row that scrolls sideways when there are two Continue watching rows): Plex, Netflix, YouTube, Disney+, TV Vlaanderen, VTM GO, VRT MAX, Play, Telenet TV, DAZN, Apple TV, Red Bull TV, YouTube Music and Live TV.
  The focused app fills the screen with its own artwork: what you were last watching in it, or the top of the rows
  it publishes for the TV home screen. Plex shows what you'd resume. Apps that publish no artwork (YouTube, DAZN,
  VRT MAX, Play, YouTube Music) show their own banner, large and dimmed, on a wall of its background colour.
  An app whose tile banner is only a logo on a see-through or plain grey background (SmartTube's is) gets a plain
  white tile instead (black behind a light logo), with its icon if the banner was grey, so it matches the others.
- **Inputs, All apps and Settings** in the top bar, so nothing from the old home screen is out of reach

## Using it

| Remote | What happens |
|---|---|
| OK on a Continue watching tile | Resumes it in Plex |
| OK on an app | Opens the app |
| Hold OK on an app | Move it, remove it from the home screen, or **Assign a remote button** to it (see below) |
| Hold OK on Netflix or Plex | Also: **Use for Continue watching**, to fill that row from Netflix instead of Plex, or back (Plex is the default) |
| | Or **Add a second Continue watching row**: Plex and Netflix each get a row (slightly smaller tiles), and the apps move into one row that scrolls sideways. **Remove its Continue watching row** goes back to one; **Show its row on top** swaps them |
| Move | The app lifts (larger, orange ring); the arrows carry it through the grid, OK puts it down, Back cancels |
| All apps (top right) | Every app; hold OK on one to add it to the row or remove it |
| Home or Back | Back to the start of Continue watching |
| Google Play button on the remote | Opens Plex, until you assign it to another app (once switched on, see below) |
| A button you assigned | Opens its app |

The first time, the Continue watching row shows **Link Plex**. Press OK and enter the 4-character code at
**plex.tv/link** on your phone or computer. The TV never sees your Plex password; the token it gets back
stays in the launcher's private storage on the TV.

Apps you install later join the row automatically, unless they're utilities (store, help, timers…).

## Install and make it the home screen

Download the newest `cinema-launcher-<version>.apk` from the [Releases page](https://github.com/PieterLingier/bravia-cinema-launcher/releases)
(or build it, see below). Switch on developer mode on the TV (Settings > Device Preferences > About, press OK on
"Build" seven times) and USB or network debugging in Developer options, connect with `adb connect <TV-IP>:5555`, then:

```powershell
adb install -r cinema-launcher-1.8.apk
```

A newer version installs the same way, over the old one; your Plex link and app order stay.

Let Cinema send Plex the episode link while Plex is on screen. Android files this under "display over other apps",
but Cinema never draws over other apps:

```powershell
adb shell appops set local.cinema.launcher SYSTEM_ALERT_WINDOW allow
```

Let Cinema read the artwork other apps publish for the TV home screen (their Watch Next items and rows), for the
app backdrops and for Continue watching from Netflix. Android calls this "read TV listings"; without it, every app
gets its banner backdrop instead:

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

## Remote buttons that open an app

Cinema's accessibility service sees the remote's buttons before the TV's own software does, so a button can open an
app of your choice instead of what it did before:

- **Assign one:** hold OK on an app in the home row, pick **Assign a remote button**, and press the button. Any button
  works except the arrows, OK, Back, Home, volume and power, which keep their own job. A button opens one app; assigning
  it again moves it to the new app. An app can have several buttons.
- **Take one back:** hold OK on the app and pick **Remove the button "…"**. The button then does what it did before.
- **Out of the box,** the Google Play button opens Plex. On this BRAVIA it sends key code 195 (`KEYCODE_BUTTON_8`),
  which Sony's software turns into "open Google TV".

Some buttons may never reach the service, if the TV handles them before Android passes keys on. Then pressing it while
Cinema asks for a button does nothing.

Every other key passes through unchanged, and the service reads nothing from the screen. It also notices when Plex has
finished loading (from the type of Plex's window events only), so a resume link goes out as soon as Plex can take it.

Switch it on with the remote: **Settings > Device Preferences > Accessibility > Cinema: remote buttons**, then confirm
Android's warning. Switch it off there to get the original behaviour back.

## Keeping Google's home screen (Google TV)

On Google TV, Google's home screen (`com.google.android.apps.tv.launcherx`) also runs the settings panel, so
disabling it can break the remote's Settings button. Instead, leave it on and switch on Cinema's accessibility
service (above). While another app is the default home screen, the service:

- makes **Home open Cinema**;
- **opens Cinema at start-up**: when the TV starts or wakes from standby, Android shows its own home screen
  without any button press. For the first minute after that, until you press a button on the remote, the service
  replaces it by Cinema as soon as it appears. After your first button press it stops, so whatever you open on
  purpose (such as Google TV's settings panel) stays. Google's home screen may show for a moment first (longer
  after a cold start, while Android starts Cinema's service). The service
  only notices which app a window belongs to.

When Cinema is the home screen itself, as after the `pm disable-user` step, the service leaves both alone.

Holding Home then no longer reaches Google's home screen. Untested on Google TV so far, including the start-up switch; the rest of Cinema should
work there, but Inputs needs a TV with inputs, and the Google Play button may not exist on that remote (assign another button
instead). On
Android 13 and newer the accessibility switch can be greyed out for apps installed outside the Play Store: allow it
under the app's info ("Allow restricted settings"), or switch it on over adb (this replaces the list of enabled
accessibility services, so only use it when no others are on):

```powershell
adb shell settings put secure enabled_accessibility_services local.cinema.launcher/local.cinema.launcher.ButtonService
```

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
| `src/main/kotlin/TvArt.kt` | What apps publish for the TV home screen: artwork for their backdrops, and Netflix's Watch Next for Continue watching |
| `src/main/kotlin/BannerArt.kt` | The banner backdrop for apps that publish no artwork |
| `src/main/kotlin/Plex.kt` | Plex sign-in (plex.tv/link code), server discovery, Continue watching, resized artwork |
| `src/main/kotlin/Apps.kt` | Which apps are in the row and in what order; launching apps |
| `src/main/kotlin/Rows.kt` | The tiles of both rows and the focus zoom |
| `src/main/kotlin/PlexLinkActivity.kt` | The "enter this code at plex.tv/link" screen |
| `src/main/kotlin/AllAppsActivity.kt` | The All apps grid |
| `src/main/kotlin/ButtonService.kt` | Assigned remote buttons → their app, and Home → Cinema when it isn't the home app (accessibility key filter) |
| `src/main/kotlin/RemoteButtons.kt` | Which buttons can be assigned, the default (Google Play → Plex), and button names |
| `src/main/kotlin/PlexWatch.kt` | Tells when Plex is ready for a resume link, from Plex's accessibility events |

- **Finding the server:** the launcher asks plex.tv for your servers and uses the first address that answers from
  the TV: home network over HTTPS, then plain HTTP, then remote, then Plex's relay. It remembers the one that worked
  and looks again when it stops answering.
- **Continue watching:** `/hubs/continueWatching/items` on the server, falling back to older endpoints and finally
  `/library/onDeck`. It refreshes every time you come back to the home screen.
- **Continue watching from Netflix:** Netflix has no public API, and on this TV it publishes no Android "Watch Next"
  items either. But its row for the TV home screen starts with the titles you're watching, each described as
  "Continue watching <episode>". With "read TV listings" Cinema shows those (and any Watch Next items, should Netflix
  add them). OK sends Netflix's own link, which resumes the episode where you stopped. Netflix shares no episode
  number or progress, so the tiles show Netflix's own artwork (with its progress bar) and the hero the episode name.
  Without the permission the row says so. Netflix's line is in English; with Netflix in another language the row stays
  empty.
- **App backdrops:** apps keep publishing artwork for the TV home screen (Watch Next items and their rows, or
  "channels") to Android's TV provider, even with Google's home screen off. With "read TV listings" Cinema may read
  it. For the focused app it shows the newest Watch Next item, otherwise the top of the rows Google's home screen
  showed, then the app's other rows. Only landscape images are used, since a portrait poster would crop badly. The
  list refreshes every time you come back to the home screen. Without artwork (or when it won't load), Cinema draws
  the app's banner at the highest density the app ships, right of the title, on its edge colour; bright banners are
  dimmed so the text stays readable and a dark room stays dark. The colour glow is left for apps without a banner.
- **Opening an item:** OK opens Plex normally, waits until Plex is ready, then sends Plex's own link format (found inside the Plex app),
  `plex://watch/video?uri=server://<server>/com.plexapp.plugins.library/library/metadata/<id>&offset=<seconds>`, once.
  The offset makes Plex resume where you stopped. Only if the server reports nothing playing 25 s later does it send
  the link a second time. Measured on this TV: a Plex that is *started* by a link ignores links for minutes; a Plex
  started from scratch shows "Can't reach server" for a link at 6 s and plays it cleanly at 9 s; and playback then
  takes 5 to 17 s to start, during which a new link restarts it with an "Item not known" error.
  "Ready" comes from Plex's accessibility events, seen by Cinema's accessibility service (event type and class name
  only): from scratch, Plex is ready when its home list first changes, once your server's data arrives (7 to 11 s);
  when it was already running, focus lands within a split second of its window appearing. Without the service
  Cinema waits a fixed 9 s. See `PlexWatch.kt`.

Cinema is released under the MIT License, see `LICENSE`. Space Grotesk font: SIL Open Font License, see
`licenses/SpaceGrotesk-OFL.txt`. The settings icon is adapted from
Feather Icons (MIT).
