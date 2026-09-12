package com.felicedesign.buttonremapper.action

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.media.AudioManager
import android.util.Log
import android.view.KeyEvent
import com.felicedesign.buttonremapper.data.ActionSpec
import com.felicedesign.buttonremapper.data.ActionType
import com.felicedesign.buttonremapper.data.ScreenPoint

/**
 * Executes a bound action.
 *
 * Note what is *not* here: nothing inspects the screen, queries the focused app, or
 * reads the view hierarchy. `performGlobalAction` works without the
 * `canRetrieveWindowContent` capability, which is what lets the service stay minimal.
 * `dispatchGesture` is the same deal - it fires a touch at a coordinate without ever
 * asking what is drawn there.
 */
class ActionRunner(private val service: AccessibilityService) {

    private val torch = TorchController(service)
    private val audioManager = service.getSystemService(AudioManager::class.java)

    fun run(spec: ActionSpec) {
        when (spec.type) {
            ActionType.NONE -> Unit

            ActionType.TORCH -> torch.toggle()
            ActionType.LAUNCH_APP -> launchApp(spec.arg)

            ActionType.MEDIA_PLAY_PAUSE -> mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            ActionType.MEDIA_NEXT -> mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            ActionType.MEDIA_PREVIOUS -> mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)

            ActionType.VOLUME_UP -> adjustVolume(AudioManager.ADJUST_RAISE)
            ActionType.VOLUME_DOWN -> adjustVolume(AudioManager.ADJUST_LOWER)
            ActionType.VOLUME_MUTE -> adjustVolume(AudioManager.ADJUST_TOGGLE_MUTE)

            ActionType.BACK -> global(AccessibilityService.GLOBAL_ACTION_BACK)
            ActionType.HOME -> global(AccessibilityService.GLOBAL_ACTION_HOME)
            ActionType.RECENTS -> global(AccessibilityService.GLOBAL_ACTION_RECENTS)
            ActionType.NOTIFICATIONS -> global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            ActionType.QUICK_SETTINGS -> global(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)

            ActionType.TAP_POINT -> tap(spec.arg, TAP_MS)
            ActionType.LONG_PRESS_POINT -> tap(spec.arg, LONG_PRESS_MS)

            ActionType.POWER_DIALOG -> global(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)
            ActionType.LOCK_SCREEN -> global(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
            ActionType.SCREENSHOT -> global(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
        }
    }

    fun release() = torch.release()

    private fun global(action: Int) {
        service.performGlobalAction(action)
    }

    private fun mediaKey(keyCode: Int) {
        // Both halves are required or the session sees a stuck key.
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    private fun adjustVolume(direction: Int) {
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            direction,
            AudioManager.FLAG_SHOW_UI
        )
    }

    /**
     * Synthesises a touch at an absolute display coordinate.
     *
     * This is the only input-injection route Android grants a normal app: everything
     * that fakes a *key* press (InputManager.injectInputEvent, uinput, Instrumentation)
     * is gated behind the signature-level INJECT_EVENTS. Accessibility gesture dispatch
     * has its own privileged channel, which is why this works at all.
     */
    private fun tap(arg: String?, durationMs: Long) {
        val point = ScreenPoint.decode(arg)
        if (point == null) {
            Log.w(TAG, "Tap action has no calibrated point")
            return
        }

        // StrokeDescription rejects an empty path, and moveTo alone counts as empty, so
        // the stroke travels one pixel. That is well under touch slop, so the target
        // still sees a clean tap rather than a drag.
        val path = Path().apply {
            moveTo(point.x.toFloat(), point.y.toFloat())
            lineTo(point.x + 1f, point.y + 1f)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val dispatched = service.dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(),
            null,
            null
        )
        if (!dispatched) Log.w(TAG, "Gesture dispatch refused at $point")
    }

    private fun launchApp(packageName: String?) {
        if (packageName.isNullOrEmpty()) return
        val intent: Intent? = service.packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) {
            Log.w(TAG, "No launch intent for $packageName")
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            service.startActivity(intent)
        } catch (e: SecurityException) {
            // Almost always the missing "Display over other apps" permission, which is
            // the documented background-activity-launch exemption. The UI warns about
            // this when a launch action is bound.
            Log.w(TAG, "Blocked from starting $packageName from the background", e)
        }
    }

    private companion object {
        const val TAG = "ActionRunner"

        /** Long enough to register everywhere, short enough not to read as a hold. */
        const val TAP_MS = 60L

        /** Comfortably past the platform long-press threshold (500 ms). */
        const val LONG_PRESS_MS = 700L
    }
}
