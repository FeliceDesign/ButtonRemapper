package com.felicedesign.buttonremapper.action

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.media.AudioManager
import android.util.Log
import android.view.KeyEvent
import com.felicedesign.buttonremapper.data.ActionSpec
import com.felicedesign.buttonremapper.data.ActionType

/**
 * Executes a bound action.
 *
 * Note what is *not* here: nothing inspects the screen, queries the focused app, or
 * reads the view hierarchy. `performGlobalAction` works without the
 * `canRetrieveWindowContent` capability, which is what lets the service stay minimal.
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
    }
}
