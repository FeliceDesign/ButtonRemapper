# ButtonRemapper — Plan

Remap the **Essential Key** on the Nothing Phone (4a) / (4a) Pro to toggle the flashlight.
No root. One-time setup performed **inside the app** over wireless ADB (no PC).

---

## 1. Why the accessibility-only approach fails

This is the part worth getting right, because it explains both failure modes.

**Failure 1 — the key never reaches you.** The Essential Key is claimed by Nothing OS
*above* the normal input dispatch path and routed straight to Essential Space. It is
consumed before `AccessibilityService.onKeyEvent()` is ever called, so an accessibility
service with `flagRequestFilterKeyEvents` sees nothing at all. No amount of accessibility
configuration fixes this — the event is gone before your process is in the loop.

The fix is to remove the consumer:

```
pm disable-user --user 0 com.nothing.ntessentialspace
pm disable-user --user 0 com.nothing.ntessentialrecorder
```

With those disabled, the key falls through to ordinary dispatch and **accessibility does
receive it**. This is the mechanism behind every working community remap (Key Mapper etc.).

**Failure 2 — you receive it but don't recognise it.** Once it does arrive, the event
reports `keyCode == 0` (`KEYCODE_UNKNOWN`). Key Mapper displays it as *"unknown keycode 0"*.
Any handler written as `if (event.keyCode == KEYCODE_X)` silently never matches.

> **The key must be identified by `event.scanCode` (+ `event.deviceId`), not `keyCode`.**

So accessibility *is* the right capture mechanism — it just needs (a) the system consumer
disabled and (b) scan-code matching. Both are handled below.

### Persistence

`pm disable-user` is a persistent package state, and the enabled accessibility service is a
persistent setting. Neither resets on reboot. The ADB step is therefore genuinely **one
time** — wireless debugging can be switched off again immediately afterwards and the remap
keeps working forever.

---

## 2. Architecture

Four pieces, cleanly separated:

| Module | Responsibility |
|---|---|
| `adb/` | One-time wireless-ADB pairing + command execution (embedded ADB client) |
| `service/` | `AccessibilityService` that filters and consumes the key |
| `action/` | Torch controller (and future actions) |
| `ui/` | Setup wizard, key-learn screen, binding config |

### 2.1 Embedded ADB — [`libadb-android`](https://github.com/MuntashirAkon/libadb-android)

`implementation 'com.github.MuntashirAkon:libadb-android:3.1.1'` (JitPack).
Apache-2.0 / GPL-3 dual licensed. Speaks the ADB protocol from inside the app, supports
Android 11+ wireless-debugging **pairing codes**, and exposes the `shell:` service.

This is what makes the setup "a thing in the app" rather than "plug into a laptop":
the app pairs with the phone's *own* ADB daemon over loopback.

- `AdbMdns` discovers `_adb-tls-pairing._tcp` and `_adb-tls-connect._tcp`, so the app
  finds the ports itself — the user only types the 6-digit pairing code.
- The RSA keypair is generated once and persisted; the phone remembers the pairing, but
  we never need to reconnect anyway.

### 2.2 Setup command sequence

Run once, in order, with output surfaced in the UI:

1. `pm list packages | grep essential` — **discover** the real package names rather than
   hardcoding them. The 3a-era names above are the expected ones, but confirm on-device.
2. `pm disable-user --user 0 <essential space pkg>`
3. `pm disable-user --user 0 <essential recorder pkg>` (frees long-press too)
4. `pm grant <our.pkg> android.permission.WRITE_SECURE_SETTINGS` — lets the app enable
   and self-heal its own accessibility service without sending the user into Settings.
5. Verify: `pm list packages -d` shows both as disabled.

Every step reversible; see §6.

### 2.3 Capture service

```xml
<accessibility-service
    android:canRequestFilterKeyEvents="true"
    android:accessibilityFlags="flagRequestFilterKeyEvents|flagDefault" />
```

`onKeyEvent(KeyEvent)`:
- match on `scanCode` + `deviceId` against the stored binding
- run our own press-pattern detection from `ACTION_DOWN`/`ACTION_UP` timing
  (single / double / long) — since we now own the raw key, all three gestures are ours
- `return true` to consume, so nothing else reacts

### 2.4 Learn-the-key flow

Never hardcode the scan code. A "Press the Essential Key now" screen captures the next
unrecognised event, records `scanCode` + `deviceId`, and stores it in DataStore. This also
makes the app work unchanged on the 3a, Phone (3), and CMF devices.

### 2.5 Torch

`CameraManager.setTorchMode(id, on)` — **no permission required**. Pick the camera whose
`FLASH_INFO_AVAILABLE` is true, preferring `LENS_FACING_BACK`. Track real state with
`registerTorchCallback` rather than a local boolean, so the toggle stays correct when
another app or the QS tile changes the torch.

---

## 3. Setup UX

```
1. Explain what will change (Essential Space stops working) + consent
2. "Enable Developer options → Wireless debugging"  [deep-link to the settings page]
3. App auto-discovers the pairing port via mDNS
4. User enters the 6-digit pairing code   ← the only manual input
5. App runs the command sequence, shows a live log
6. "Press the Essential Key" → learn scan code
7. Enable accessibility service (automatic via WRITE_SECURE_SETTINGS)
8. Done — "you can turn wireless debugging back off"
```

Android's pairing dialog can't be read programmatically, so step 4 wants split-screen or
a floating window; the wizard should say so explicitly with a screenshot.

---

## 4. Implementation phases

**Phase 1 — Prove the mechanism.** Skeleton app + accessibility service that logs every
`onKeyEvent` (scanCode, deviceId, action). Disable the two packages *manually* via PC ADB.
Confirm the key arrives and note its scan code. **Nothing else is worth building until this
is confirmed on your actual device.**

**Phase 2 — Torch.** `TorchController` + torch callback state tracking. Hardcode the scan
code from Phase 1. At this point the feature works end to end.

**Phase 3 — Embedded ADB.** Add libadb-android, mDNS discovery, pairing UI, command runner
with live log. Replaces the manual PC step.

**Phase 4 — Polish.** Learn-key flow, single/double/long binding config, action types
beyond torch (launch app, media, custom intent), undo/restore screen, setup-health check
that detects a re-enabled Essential Space.

Suggested stack: Kotlin, Compose, `minSdk 30` (wireless pairing is Android 11+), DataStore.

---

## 5. Risks and fallbacks

| Risk | Mitigation |
|---|---|
| **Google is moving to restrict local/on-device ADB** (would break Shizuku and libadb-based apps alike) | Keep the ADB layer behind an interface with three backends: embedded ADB, **Shizuku** (if installed), and **manual PC commands** (show copyable text). The remap itself is unaffected once applied — only re-setup would need a PC. |
| Package names differ on 4a Pro / future Nothing OS | Discover via `pm list packages` instead of hardcoding (§2.2 step 1) |
| Accessibility *still* doesn't get the key after disabling | Plan B: persistent ADB shell running `getevent -lq` on the input node, parsed by the app. Reads raw evdev below the framework, so it cannot be swallowed — but needs a live ADB connection, so it loses the "one-time" property. Only pursue if Phase 1 fails. |
| A Nothing OS update re-enables the packages | Health check on launch; offer one-tap re-run of setup |
| Losing Essential Space | Stated up front in the consent screen; fully reversible |

---

## 6. Undo

```
pm enable com.nothing.ntessentialspace
pm enable com.nothing.ntessentialrecorder
```

Ship this as a "Restore Essential Key" button, not just documentation. Packages are only
disabled, never uninstalled — no data is lost.

---

## 7. Open questions to resolve on-device

- [ ] Exact package names on the (4a) Pro's Nothing OS build
- [ ] The Essential Key's `scanCode` and `deviceId`
- [ ] Whether long-press is fully freed by disabling the recorder, or handled elsewhere
- [ ] Whether the key still reaches accessibility with the screen off / locked
      (matters a lot for a flashlight — this is the main use case)

---

## References

- [z3phydev — How to remap or disable the Essential Key](https://github.com/z3phydev/How-to-remap-or-disable-the-Essential-Key)
- [Beebom — Nothing Essential Key remapped with ADB](https://beebom.com/nothing-essential-key-user-remaps-button-with-adb/)
- [Android Authority — Phone (3a) Essential Key remap](https://www.androidauthority.com/nothing-phone-3a-essential-key-remap-3543275/)
- [libadb-android](https://github.com/MuntashirAkon/libadb-android)
- [LADB — on-device ADB reference implementation](https://github.com/tytydraco/LADB)
- [Remap the Essential Key without a PC](https://wreck2053.github.io/essential-key/remap-essential-key-without-pc/)
- [Kitsumed — Android may restrict on-device ADB](https://kitsumed.github.io/blog/posts/android-may-soon-restrict-on-device-adb/)
