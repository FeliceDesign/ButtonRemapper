# Design constraint: why this app reads no screen content

## The rule

**The accessibility service must never request window content or accessibility events.**

`res/xml/accessibility_service_config.xml` declares:

```xml
android:canRetrieveWindowContent="false"
android:accessibilityFlags="flagRequestFilterKeyEvents"
android:canRequestFilterKeyEvents="true"
android:canPerformGestures="true"
```

Nothing else. No `flagDefault`, no `flagIncludeNotImportantViews`, no
`canRequestTouchExplorationMode`.

`canPerformGestures` is a later addition and the only capability beyond key filtering —
see "The second exception" below for why it is believed safe and what still needs
testing.

`accessibilityEventTypes` is deliberately **absent**. It is a flags mask with no
"none" constant, so omitting it is how a service registers for zero event types —
writing `typesNone` is a resource-linking error.

## Why

Existing remappers such as Key Mapper break drag-and-drop and scrolling in WebView apps
(Obsidian is the usual casualty). The cause is not "an accessibility service is enabled" —
it is narrower than that.

Per [Chromium's Android accessibility
docs](https://chromium.googlesource.com/chromium/src.git/+/HEAD/docs/accessibility/browser/android.md),
WebView's accessibility engine is lazily initialised and tailored to whoever is asking:

- It initialises when `getAccessibilityNodeProvider` is first called, which happens for
  services that want window content.
- **Custom AXModes**: Chromium "queries the list of running services, and sets a specific
  AXMode based on the services that are running, to tailor the native accessibility engine
  to the current situation."
- **On-demand event dispatch**: events outside the set services registered for are dropped.
- **Auto-disable**: the engine is torn down when no service needs it.

General-purpose remappers must request window content because they detect the focused app
(for per-app key maps and constraints). That flips WebView into full accessibility mode
everywhere, rebuilding its node tree and changing touch, long-press and drag handling.

> The trigger is **"a running service asks for window content"**, not "an accessibility
> service exists". Key-event filtering and content retrieval are separate capabilities, and
> we only need the first.

## What this costs us

No per-app behaviour. We cannot offer "do X only in app Y", because knowing what app is
focused is exactly the capability we are refusing.

This is a deliberate trade, not an oversight. Every action in `ActionType` is a stateless
system operation that does not care what is on screen:

- `performGlobalAction` works **without** `canRetrieveWindowContent` (back, home, recents,
  notifications, quick settings, power menu, lock screen, screenshot).
- Torch is `CameraManager.setTorchMode` — no permission, no accessibility involvement.
- Media and volume go through `AudioManager` — no accessibility involvement.
- Launching apps is `startActivity`.

## The one exception, and why it is not accessibility

Android has **no** background-activity-launch exemption for accessibility services. The
documented, user-grantable exemption is `SYSTEM_ALERT_WINDOW` ("Display over other apps"),
so that permission is requested — but only when a "Launch app" action is actually bound.
It has nothing to do with accessibility and does not affect WebView.

## The second exception: `canPerformGestures`

### Why it is here

Underwater housings only expose one button, and it lands on the Essential Key. The goal
was to make that key fire the camera shutter. Every route to faking a *key* press is
closed to a normal app:

| Mechanism | Why not |
|---|---|
| `InputManager.injectInputEvent()` | `INJECT_EVENTS`, `protectionLevel="signature"` |
| `Instrumentation.sendKeyDownUpSync()` | Wraps the same call; `SecurityException` cross-app |
| `UiAutomation.injectInputEvent()` | Instrumentation tests only |
| IME `InputConnection.sendKeyEvent()` | Reaches the focused *text field* only |
| `AudioManager.dispatchMediaKeyEvent()` | Filtered by `KeyEvent.isMediaSessionKey()`; volume is not one |
| `GLOBAL_ACTION_KEYCODE_HEADSETHOOK` | Routed to the MediaSession, not the focused window |
| `BluetoothHidDevice` | Targets a remote host; cannot address its own device |
| `/dev/uinput` | SELinux denies the app domain |
| `.kl` keylayout remap | The correct answer, needs root |
| Shizuku / embedded ADB | Works, but shell privilege and re-arming after every reboot |

Accessibility gesture dispatch is the **only** input-injection channel Android opens to
an unprivileged app. It injects touch, not keys — so `TAP_POINT` taps a coordinate the
user calibrated rather than pressing a volume key.

### Why it should not wake WebView

The trigger documented above is *"a running service asks for window content"*.
`canPerformGestures` is a dispatch capability: it grants an output path, and causes no
call to `getAccessibilityNodeProvider`. `canRetrieveWindowContent` stays `false` and no
event types are registered, so Chromium's AXMode inputs are unchanged.

**This is reasoning, not a measurement.** Re-run the Obsidian drag test.

### What it does not do

`TAP_POINT` cannot know what is under the coordinate — that would need window content,
which is the whole thing we are refusing. It fires wherever you happen to be. Calibration
is likewise blind: the user aims a crosshair, the app never reads the screen.

The crosshair itself uses `TYPE_ACCESSIBILITY_OVERLAY`, which is granted to accessibility
services directly, so calibration needs no `SYSTEM_ALERT_WINDOW`. It is hosted by the
accessibility service rather than by an activity because it has to stay up while the user
switches to the app they are aiming at.

## Before adding a capability

If you ever add a capability to the service config, re-run the drag-and-drop test in
Obsidian (see README). That test is the reason this app exists in its current shape.
