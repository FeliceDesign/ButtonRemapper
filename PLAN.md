# ButtonRemapper — Plan

Remap the **Essential Key** on the Nothing Phone (4a) / (4a) Pro to toggle the flashlight,
**without leaving a content-reading accessibility service enabled all the time**.

Status: the Essential Key has already been freed (Essential Space disabled via ADB) and
verified working with Key Mapper. The remaining problem is Key Mapper's accessibility
service causing drag/scroll collisions in other apps (Obsidian). This plan is about
replacing that capture mechanism.

---

## 1. Background: freeing the key (already done)

Nothing OS claims the Essential Key above normal input dispatch and routes it to Essential
Space, so it never reaches `onKeyEvent()`. Disabling the consumer frees it:

```
pm disable-user --user 0 com.nothing.ntessentialspace
pm disable-user --user 0 com.nothing.ntessentialrecorder
```

This is persistent package state — it survives reboot. Undo with `pm enable <pkg>`.

Once freed, the key reports `keyCode == 0` (`KEYCODE_UNKNOWN`), so it must be identified by
**`scanCode` + `deviceId`**, never by `keyCode`.

---

## 2. Root cause of the drag/scroll collisions

This is not "accessibility services break drag and drop" in general. The real variable is
narrower, and it decides the whole design.

Obsidian's UI is a WebView (CodeMirror). Per [Chromium's Android accessibility
docs](https://chromium.googlesource.com/chromium/src.git/+/HEAD/docs/accessibility/browser/android.md),
WebView's accessibility engine is **lazily initialised and tailored to whoever is asking**:

- It initialises when `getAccessibilityNodeProvider` is first called — which only happens
  for services that actually want window content.
- **Custom AXModes**: Chromium "queries the list of running services, and sets a specific
  AXMode based on the services that are running, to tailor the native accessibility engine
  to the current situation."
- **On-demand event dispatch**: services register which event types they need, and events
  outside that set are dropped.
- **Auto-disable**: "When we detect that a user has not been using the accessibility engine
  and no longer has an accessibility service running, we stop the engine and teardown all
  the related objects to improve performance."

Key Mapper trips all of this because it uses the accessibility API to **detect the focused
app** (for app-specific key maps and constraints). That requires
`canRetrieveWindowContent="true"` and broad event types — `typeAllMask`-style registration
forces the system to notify it of essentially every accessibility event OS-wide.

The result: with Key Mapper enabled, WebView flips into full accessibility mode everywhere,
rebuilding its node tree and altering touch/long-press/drag handling. That is the collision.

> **The trigger is "a running service asks for window content", not "an accessibility
> service exists".** Key event filtering and content retrieval are separate capabilities.

This is a design consequence of Key Mapper being a general-purpose remapper. It will not be
"fixed" — the feature that causes it is the feature people want from that app. We don't
need that feature.

---

## 3. Capture options

### Option A — minimal, key-only accessibility service *(recommended first attempt)*

Declare the narrowest service Android allows:

```xml
<accessibility-service
    android:canRequestFilterKeyEvents="true"
    android:accessibilityFlags="flagRequestFilterKeyEvents"
    android:accessibilityEventTypes="typesNone"
    android:canRetrieveWindowContent="false"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:notificationTimeout="0" />
```

No window content, no event types, no touch exploration, no
`flagIncludeNotImportantViews`, no `flagDefault`. Under Chromium's AXMode logic this should
compute to an empty/minimal mode, so WebView never enables its tree and Obsidian's drag
handling is untouched.

- **Pros:** keeps the one-time-setup property; no persistent shell process; works from boot
  with zero re-arming; can consume the key (`return true`).
- **Cons / honest caveat:** this is reasoned from Chromium's documented architecture, not
  from a confirmed report of this exact configuration. `AccessibilityManager.isEnabled()`
  still returns true globally, so an app that naively branches on *that* would still change
  behaviour. Obsidian's problem is WebView-internal and AXMode-driven, so it should be
  clear — but it must be tested.
- **The test is cheap and decisive:** build a stub service with the config above that only
  logs key events, enable it, disable Key Mapper, and try dragging in Obsidian. ~30 minutes,
  and it settles the entire architecture question.

### Option B — no accessibility service at all: shell-hosted evdev reader

Since Essential Space is already disabled, the key does nothing. We don't need to
*intercept* it — only to *observe* it. So read the raw input device directly.

`/dev/input/event*` is readable by the **shell** uid (this is why `adb shell getevent` works
without root) but **not** by a normal app uid — SELinux blocks the app domain. So the reader
has to run in a shell-uid process:

- **Shizuku user service**, or
- our own persistent embedded-ADB `shell:` session running `getevent -lq`

The app parses the event stream, matches the scan code, and toggles the torch. Zero
accessibility services enabled anywhere on the device — the collision becomes structurally
impossible.

- **Pros:** completely sidesteps the problem class. Passive read, so it doesn't consume or
  alter any other input.
- **Cons:** needs a live shell process. After reboot it must be re-armed, which is exactly
  the recurring friction you wanted to avoid. Also a long-lived connection to budget for
  (battery, process death).
- **Mitigation:** grant `WRITE_SECURE_SETTINGS` once during setup, then have the app set
  `settings put global adb_wifi_enabled 1` on boot and reconnect itself automatically.
  Prior art: [adb-auto-enable](https://github.com/mouldybread/adb-auto-enable) does exactly
  this, including self-granting the permission after pairing. Fiddly, but it restores
  hands-off operation.
- **Risk:** Google is moving to restrict local/on-device ADB, which would hit both Shizuku
  and embedded-ADB variants of this path.

### Option C — root

With an unlocked bootloader, remap the scan code in a `.kl` key layout file or handle it in
a proper system-level handler. Cleanest and most robust, no services at all. Rejected unless
you're willing to unlock (which wipes the device).

### Recommendation

**Test Option A, fall back to Option B.** Option A preserves the one-time-setup property and
is a genuinely different configuration from Key Mapper's — the diagnosis in §2 gives good
reason to expect it behaves differently. Option B is the guaranteed-correct answer if the
test fails, at the cost of reboot re-arming.

---

## 4. Architecture

| Module | Responsibility |
|---|---|
| `capture/` | `KeyCapture` interface with two implementations (A: accessibility, B: evdev) |
| `action/` | Torch controller |
| `setup/` | One-time ADB/Shizuku setup + health checks |
| `ui/` | Setup wizard, key-learn screen, binding config |

Keeping capture behind an interface means the Option A → B fallback is a swap, not a
rewrite.

**Torch:** `CameraManager.setTorchMode` — no permission required. Select the camera with
`FLASH_INFO_AVAILABLE`, preferring `LENS_FACING_BACK`. Track state via
`registerTorchCallback` rather than a local boolean, so the toggle stays in sync when the QS
tile or another app changes the torch.

**Learn-the-key flow:** never hardcode the scan code. A "press the Essential Key now" screen
captures the next unrecognised event and stores `scanCode` + `deviceId`. Also makes the app
portable to the 3a, Phone (3), and CMF devices.

**Gesture detection:** we own the raw key, so single / double / long press are detected from
`ACTION_DOWN`/`ACTION_UP` timing in our own code.

---

## 5. Phases

**Phase 0 — the decisive experiment.** Stub app + minimal accessibility service (§3 Option
A config) that only logs `scanCode`/`deviceId`. Disable Key Mapper. Confirm (a) the key is
received, (b) Obsidian drag/drop still works. *Everything downstream depends on this result.*

**Phase 1 — Torch.** `TorchController` + torch callback state tracking, wired to the scan
code from Phase 0. Feature complete end to end.

**Phase 2 — Productionise the winning path.** Either polish Option A, or build the Shizuku /
embedded-ADB evdev reader plus boot re-arm.

**Phase 3 — Polish.** Learn-key UI, single/double/long bindings, more action types, health
check that detects Essential Space being re-enabled by an OS update, restore button.

Stack: Kotlin, Compose, `minSdk 30`, DataStore.

---

## 6. Open questions

- [ ] **Does the minimal service avoid the Obsidian collision?** (Phase 0 — gates everything)
- [ ] Does the key still fire with screen off / locked? Critical for a flashlight button.
- [ ] The Essential Key's `scanCode`, `deviceId`, and input device node
- [ ] Whether `adb_wifi_enabled` reliably survives boot on Nothing OS 4 (only if Option B)

---

## References

- [Chromium — Accessibility on Android](https://chromium.googlesource.com/chromium/src.git/+/HEAD/docs/accessibility/browser/android.md) (AXModes, lazy init, auto-disable)
- [AOSP — getevent](https://source.android.com/docs/core/interaction/input/getevent)
- [Obsidian forum — Android drag and drop does not work](https://forum.obsidian.md/t/android-drag-and-drop-does-not-work-in-outline-bookmarks-file-explorer/97843)
- [Key Mapper](https://github.com/keymapperorg/KeyMapper)
- [adb-auto-enable](https://github.com/mouldybread/adb-auto-enable)
- [libadb-android](https://github.com/MuntashirAkon/libadb-android)
- [z3phydev — remap/disable the Essential Key](https://github.com/z3phydev/How-to-remap-or-disable-the-Essential-Key)
