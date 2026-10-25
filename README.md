# Glass Launcher: a tvOS 26-style Home screen for Android TV

A Home screen for Android TV / Google TV laid out like Apple's tvOS 26 (Liquid Glass).
Not affiliated with Apple; no Apple code, assets or branding are used.

| App grid | Glass context menu |
|---|---|
| ![App grid](docs/grid.png) | ![Context menu](docs/menu.png) |

*Screenshots from a Realme Android TV (Android 11, 1280×720).*

**[Download the APK from Releases](../../releases/latest)**, then see [Install it on the TV](#2-install-it-on-the-tv).

## Features

- **Full-screen Top Shelf.** The focused app's artwork fills the whole screen with a slow
  push-in, and its name appears as a big title in the top-left corner.
- **Clock + profile pill** in the top-right corner.
- **Liquid Glass dock.** The top row of 6 apps sits on a frosted rounded panel ~76% down the
  screen; the next row only just peeks in at the bottom.
- **5:3 rounded icons** with a glass rim and sheen. The focused icon grows 1.2×, lifts with a
  shadow, tilts the way you moved, and a specular highlight glides across it.
- **Press Up from the dock** to enter the Top Shelf: the apps slide away, left/right pages
  through the dock apps (page dots at the bottom), OK opens one, Down/Back returns.
- Going down into the grid slides the Top Shelf away; app names appear under the focused icon.
- **Hold OK** (or press Menu) on an app for a glass context menu: Edit Home Screen, Move to Top
  Row, App Info, Hide from Home Screen, Delete App.
- **Hold OK on Settings** for: Rearrange Apps, Glass Launcher Settings, Show Hidden Apps, Reset Layout.

### Rearranging apps (Edit Home Screen)
Every icon wiggles, like tvOS. Browse with the arrows, **OK picks an app up**, the arrows carry it
(up/down jump a whole row, so any app can go straight into the top dock), **OK drops it**, then pick
up the next one. **Back** finishes and saves. Open it from any of:
- hold OK on an app → **Edit Home Screen** (starts with that app picked up), or **Move to Top Row**;
- hold OK on the **Settings** icon → **Rearrange Apps**;
- **Glass Launcher Settings → Home screen → Rearrange apps**.

### Ad blocking
**Glass Launcher Settings → Ad blocking.** Switches Android's built-in Private DNS (DNS-over-TLS) to
AdGuard's free DNS, so ad and tracker servers are blocked for every app on the TV, with no VPN or extra
app. Options: ads + trackers, ads + trackers + adult sites (AdGuard Family), or off. Glass checks
that a test ad server is blocked and that the internet still works. If your network blocks
secure DNS, it switches itself back off. DNS blocking can't remove YouTube/Netflix in-video ads,
which are served from the same servers as the videos.

### Top Shelf artwork
- **Streaming apps** (Netflix, Prime Video, YouTube, Apple TV, JioHotstar, Disney+, Hulu, Max,
  Paramount+, Peacock) show the **newest releases** on that service: brand-new series first, then
  new seasons, then new episodes, from the last 10 days. Each comes with a full-screen backdrop,
  the show's title, and a line like *"New season · Season 4 · Yesterday"*, rotating every 9 seconds.
  In the Top Shelf, left/right moves between shows.
  Data and images come from [TVmaze](https://www.tvmaze.com) (free API, CC BY-SA), checked every
  3 hours and cached on the TV. It can be switched off in Glass Launcher Settings → Home screen.
- **Other apps** show their own TV banner (or icon), heavily blurred, with the app's name.

Android only lets its built-in launcher read the poster "channels" apps publish (the permission
is reserved for system apps), so a third-party launcher can't show each app's own library images.

### Lock as main launcher + remove bloatware
Open **Glass Launcher Settings** (it opens by itself on first launch, or hold OK on Settings):

- **Lock Glass as the main launcher.** Sets Glass as the default Home app, disables the original
  launcher (Google TV / Android TV Home), and turns on **Home Guard**.
- **Home Guard** (an accessibility service) sends the remote's Home button to Glass and closes
  the original launcher whenever anything brings it back (boot, shortcuts, apps).
- **Remove bloatware.** Tick preinstalled apps and press *Disable selected apps*. Apps are
  *disabled*, not deleted: *Restore apps disabled by Glass* brings them all back. Entries marked
  "Warning" turn off a feature (voice search, casting, phone remote).
- **Restore the original launcher** undoes the lock.

These use the TV's own debugging connection (the same access as `adb shell` from a PC):
1. *Settings → Device Preferences (or System) → About*: press OK on **Build** 7 times.
2. *Developer options*: turn on **USB debugging** (and **Network debugging** if listed).
3. Press a button in Glass Launcher Settings and accept **Allow debugging?** (tick *Always allow*).

If it can't connect, your TV only offers *Wireless debugging*: from a PC run
`adb connect <TV-IP>` once, then `adb tcpip 5555`, and try again. Needs Android 8+.

Home Guard doesn't stop the remote's app shortcut buttons (Netflix, YouTube…) opening those apps.
It only makes sure that anything going "Home" lands on Glass.

---

## 1. Build the APK
- **Android Studio:** *File → Open* the `GlassLauncher` folder, then
  *Build → Build App Bundle(s) / APK(s) → Build APK(s)*. The APK appears at
  `app/build/outputs/apk/debug/app-debug.apk`.
- **Command line** (JDK 17, Android SDK 34, Gradle 8.7): `gradle assembleDebug`.

## 2. Install it on the TV
- **LocalSend:** send the APK to LocalSend on the TV, accept, open it, allow
  "Install unknown apps" for LocalSend (or open it with a file manager), then press Install.
- **USB stick:** copy the APK over, open it with a file manager on the TV (e.g. File Commander).
- **ADB over Wi-Fi:** `adb connect <TV-IP>:5555`, then `adb install -r GlassLauncher-v3.0.apk`.

Installing a new version over an old one keeps your layout.

## 3. Make it the Home screen
Open Glass Launcher once, then use **Lock Glass as the main launcher** (above).
Older Android TVs also let you pick it by pressing Home and choosing **Glass Launcher → Always**.

Manual ADB equivalent, from a PC:
```
adb shell cmd package set-home-activity com.glass.launcher/com.glass.launcher.MainActivity
adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx
adb shell pm disable-user --user 0 com.google.android.tungsten.setupwraith
```
Undo:
```
adb shell pm enable com.google.android.apps.tv.launcherx
adb shell pm enable com.google.android.tungsten.setupwraith
```
(Android TV's stock Home is `com.google.android.tvlauncher`.)

## Project layout
```
app/src/main/java/com/glass/launcher/
  MainActivity.kt      Home screen, Top Shelf, context menu, edit mode, keys
  AppAdapter.kt        icons + Liquid Glass focus / tilt / parallax
  DockDecoration.kt    the glass dock behind the top row
  Metrics.kt           tvOS 26 proportions as fractions of the screen
  Graphics.kt          icon colours, Top Shelf artwork, blur
  AppRepository.kt     installed apps, saved order, hidden apps
  SetupActivity.kt     Glass Launcher Settings (lock, debloat, restore)
  HomeGuardService.kt  keeps Glass as Home (accessibility service)
  TvShell.kt           runs shell commands through the TV's own ADB
  ShelfContent.kt      newest-release show artwork for the Top Shelf (TVmaze)
  AdBlock.kt           system-wide ad blocking via Private DNS (AdGuard)
  Debloat.kt           which preinstalled apps are offered / protected
```
