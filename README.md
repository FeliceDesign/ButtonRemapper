# ButtonRemapper

Remaps the Nothing Phone Essential Key to shortcuts you choose — single, double and long
press — using an accessibility service that **reads no screen content**, so it does not
break drag-and-drop and scrolling in WebView apps the way general-purpose remappers do.

See [docs/DESIGN.md](docs/DESIGN.md) for why that constraint exists and what it costs.

> **Compiles and lints clean, but has never run on a device.** Every push builds a debug
> APK in CI — grab it from the **buttonremapper-debug** artifact on the latest
> [Actions run](https://github.com/FeliceDesign/ButtonRemapper/actions) if you would rather
> not build locally. Runtime behaviour, including the WebView test below, is still unverified.

## Prerequisites

The Essential Key must already be freed from Essential Space, which is a one-time ADB step:

```
adb shell pm disable-user --user 0 com.nothing.ntessentialspace
adb shell pm disable-user --user 0 com.nothing.ntessentialrecorder
```

This persists across reboots. To undo it, `adb shell pm enable <package>`.

Without this the key is consumed by Nothing OS above normal input dispatch and never
reaches any app.

## Build

```
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Set up

1. Open the app, enable the accessibility service when prompted.
2. Tap **Learn key**, press the Essential Key once. It will report `keyCode 0`
   (`KEYCODE_UNKNOWN`) — that is expected, which is why the app matches on **scan code**.
3. Assign actions to single, double and long press.
4. Only if you bind a **Launch app** action: grant "Display over other apps".

## "Restricted setting" — the accessibility toggle is greyed out

Expected on Android 13+, and nothing to do with debug vs release builds or with how the
APK is signed. Android blocks accessibility and notification-listener toggles for any app
installed **outside an app store**, because that is how malware abuses the accessibility
API. Sideloaded is sideloaded, whichever build you install.

Two ways past it:

**Per install, on the phone.** Settings → Apps → See all apps → ButtonRemapper → ⋮ (top
right) → **Allow restricted settings** → confirm with your PIN. Then enable the service in
Accessibility → Downloaded apps. The menu entry may only appear after you have tapped the
greyed-out toggle once.

**At install time, from ADB.** Marking an app store as the installer avoids the
restriction entirely:

```
adb install -i com.android.vending app-debug.apk
```

Worth using for the CI artifact, since a reinstall otherwise means redoing the menu dance.

This app deliberately does **not** declare `android:isAccessibilityTool`. That attribute is
reserved for tools that help people with disabilities; Google explicitly classes automation
tools like remappers as ineligible. It would not lift the restriction anyway.

## The test that matters

The premise of this app is that a minimal, key-only accessibility service does not cause
the WebView breakage that Key Mapper does. That is reasoned from Chromium's documented
AXMode behaviour, **not** from a confirmed report of this exact configuration. Verify it:

1. Turn **off** Key Mapper's accessibility service.
2. Turn **on** ButtonRemapper's.
3. In Obsidian, drag to reorder items in the file explorer, outline and bookmarks.
4. Check scrolling in a long note.

If drag-and-drop works, the approach holds. If it does not, the fallback is a shell-hosted
`getevent` reader that uses no accessibility service at all — see `PLAN.md` §3 Option B.

## Known unknowns

- **Screen off / locked.** Whether `onKeyEvent` is delivered with the screen off is
  untested. This matters most for the flashlight, which is the main use case.
- **Scan code stability.** Device ids are not guaranteed stable across reboots, so
  device-id matching is off by default. If the key stops working after a reboot, re-learn it
  and leave that setting off.
- **OS updates.** A Nothing OS update may re-enable Essential Space, which would take the
  key back. Re-run the ADB commands if that happens.

## Actions

Torch · launch app · play/pause · next · previous · volume up/down/mute · back · home ·
recents · notification shade · quick settings · power menu · lock screen · screenshot ·
**tap a spot on screen** · **long-press a spot on screen**.

All of them are stateless system operations — none needs to know what is on screen. That is
what keeps the service minimal.

## Tap a spot on screen

Fires a touch at a coordinate you calibrate. It exists because no unprivileged app can fake
a *key* press (`INJECT_EVENTS` is signature-level), so "make the Essential Key act as the
camera shutter" is impossible — but tapping the shutter *button* is not. The original use
case is an underwater housing whose only usable button sits over the Essential Key.

**To calibrate:** bind the action, and a crosshair appears on top of everything. Leave
ButtonRemapper, open the app you want to control, drag the crosshair onto the button, press
Save. Touches outside the crosshair and the panel pass straight through, so the app
underneath stays usable while you aim.

Each gesture stores its own point, so single press can hit the shutter while double press
hits the video-mode tab.

Two things to know:

- **It taps blind.** Knowing whether your camera is actually in front would require window
  content, which is exactly what this app refuses to request (see `docs/DESIGN.md`). Bound
  to a gesture, it fires wherever you are — rebind it when you are done.
- **Coordinates are absolute**, so a point calibrated in portrait is wrong in landscape.
  Calibrate in the orientation you will shoot in.

This is the app's only capability beyond key filtering (`canPerformGestures`). It should not
affect WebView, but **re-run the Obsidian drag-and-drop test below** after installing this
version.
