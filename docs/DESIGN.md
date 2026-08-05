# Design constraint: why this app reads no screen content

## The rule

**The accessibility service must never request window content or accessibility events.**

`res/xml/accessibility_service_config.xml` declares:

```xml
android:canRetrieveWindowContent="false"
android:accessibilityEventTypes="typesNone"
android:accessibilityFlags="flagRequestFilterKeyEvents"
android:canRequestFilterKeyEvents="true"
```

Nothing else. No `flagDefault`, no `flagIncludeNotImportantViews`, no
`canRequestTouchExplorationMode`, no `canPerformGestures`.

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

## Before adding a capability

If you ever add a capability to the service config, re-run the drag-and-drop test in
Obsidian (see README). That test is the reason this app exists in its current shape.
