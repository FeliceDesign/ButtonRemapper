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
**tap a spot** · **long-press a spot** · **cycle through spots**.

All of them are stateless system operations — none needs to know what is on screen. That is
what keeps the service minimal.

## Presets

Named sets of bindings you switch between — a daily mapping and a diving mapping, say.
Create, duplicate, rename and delete them from the card at the top of the screen.

**Bindings, calibrated points, cycle positions and all four timings live in the preset.
The learned key does not** — a scan code identifies hardware, not a mapping, and re-learning
the Essential Key on every switch would be absurd.

Switching costs one integer write. Every read resolves the active preset first, so the
accessibility service picks up the new mapping on the very next press: nothing to notify,
restart or invalidate.

**Duplicate** clones every binding, point and timing, which is how you build a variant of a
working setup without re-aiming anything. Cycle positions are per preset too, so switching
away and back resumes where that preset was.

Upgrading from a version without presets moves your existing settings into a preset named
*Default* rather than stranding them.

## Gestures

Single press · double press · long press · **press, then hold** ("tap taaaap").

Press-then-hold is a fourth gesture that costs nothing. The second key-down of a double
press already starts the long-press timer, so the gesture is just "that timer fired while
the press count was 2" — three lines in `KeyGestureDetector`. A triple press would have
been the obvious way to buy a fourth slot and the wrong one: it forces *every* single
press to wait out two double-press windows before it can be ruled out.

Single presses still fire instantly whenever nothing is bound to a double press **or** a
press-then-hold — both start with a tap and a release, so both need the window.

## Tap a spot on screen

Fires a touch at a coordinate you calibrate. It exists because no unprivileged app can fake
a *key* press (`INJECT_EVENTS` is signature-level), so "make the Essential Key act as the
camera shutter" is impossible — but tapping the shutter *button* is not. The original use
case is an underwater housing whose only usable button sits over the Essential Key.

**To calibrate:** bind the action, and a crosshair appears on top of everything. Leave
ButtonRemapper, open the app you want to control, drag the crosshair onto the button, press
Save. Touches outside the crosshair and the panel pass straight through, so the app
underneath stays usable while you aim.

Each gesture stores its own points, so single press can hit the shutter while double press
hits the video-mode tab.

### Cycle through spots

One binding, several points, tapped in turn. Two points make a toggle (1× ⇄ 3.5×, photo ⇄
video); more make a carousel. This is what keeps a five-item wishlist inside four gesture
slots — you do not need separate "zoom in" and "zoom out" bindings for something that
strictly alternates.

**Calibrate each step from the state it fires in.** Aim at `3.5` while you are at 1×, then
switch to 3.5× and aim at `1`. Camera chip rows re-flow around the selected item, so each
point gets measured in the layout it will actually meet.

It counts rather than looks — seeing which zoom is selected would need window content. So
changing the setting by hand puts the cycle out of phase until the next press catches up.
The binding row marks the next step with `▸` and offers **Reset to step 1**.

Two things to know:

- **It taps blind.** Knowing whether your camera is actually in front would require window
  content, which is exactly what this app refuses to request (see `docs/DESIGN.md`). Bound
  to a gesture, it fires wherever you are — rebind it when you are done.
- **Coordinates are absolute**, so a point calibrated in portrait is wrong in landscape —
  and a point calibrated in photo mode may be wrong in video mode. On the Nothing camera
  the photo chip row is `0.6 1 2 3.5 7` and the video row is `0.6 1 3.5`: `3.5` lands in
  almost the same place in both, but photo's `1` sits roughly where video's `0.6` does.
  Calibrate in the orientation *and* mode you will shoot in.
- **Hold time is per-point**, under **Tune** on the binding, because it selects *which
  gesture* the target thinks it got rather than just tuning reliability. A shutter button
  may want 120 ms while a zoom chip row — which snaps on a tap and opens a continuous
  slider on a hold — needs 10–30 ms. One global value cannot satisfy both; the Timing card
  only sets the fallback for points that have no value of their own.
- **Tune** also re-aims a single point, so fixing one step of a cycle does not mean
  re-calibrating all of them.

### When a tap does nothing

Press **Check points** on the binding. Numbered markers appear on every saved coordinate,
so you can hold them up against the control and see whether the dot is actually on the
button. Pressing a number fires a real tap there and reports whether the system
*delivered* it or *cancelled* it — two completely different bugs:

- **Dot is off the control** → re-calibrate. Calibrate in the same orientation, camera
  mode and zoom state the tap will fire in.
- **Delivered, but the wrong thing happened** → the target read the touch as a different
  gesture. A tap that opens a slider or a menu is being read as a *hold*: press **Tune**
  on the binding and take that point's hold time down to 10–30 ms.
- **Delivered, nothing happened** → the app got the touch and ignored it. Try a longer
  hold.
- **Cancelled** → something interrupted the gesture before it landed.

The overlay pulls itself off screen before a test tap. A non-touchable overlay still marks
touches beneath it as obscured, and an app calling `setFilterTouchesWhenObscured` would
reject the tap and give a false negative.

This is the app's only capability beyond key filtering (`canPerformGestures`). It should not
affect WebView, but **re-run the Obsidian drag-and-drop test below** after installing this
version.
