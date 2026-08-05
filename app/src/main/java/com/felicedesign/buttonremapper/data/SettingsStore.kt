package com.felicedesign.buttonremapper.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Plain SharedPreferences. The accessibility service reads these on every key press,
 * which is cheap because the values are held in memory by the framework, and it means
 * changes from the UI take effect immediately with no wiring between the two.
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // --- the learned key -------------------------------------------------------

    /**
     * The Essential Key reports keyCode 0 (KEYCODE_UNKNOWN), so the scan code is the
     * real identity. keyCode is stored only as a fallback for keys that do report one.
     */
    var scanCode: Int
        get() = prefs.getInt(KEY_SCAN, UNSET)
        set(value) = prefs.edit().putInt(KEY_SCAN, value).apply()

    var keyCode: Int
        get() = prefs.getInt(KEY_KEYCODE, UNSET)
        set(value) = prefs.edit().putInt(KEY_KEYCODE, value).apply()

    var deviceId: Int
        get() = prefs.getInt(KEY_DEVICE, UNSET)
        set(value) = prefs.edit().putInt(KEY_DEVICE, value).apply()

    /** Device ids are not guaranteed stable across reboots, so this defaults to off. */
    var matchDeviceId: Boolean
        get() = prefs.getBoolean(KEY_MATCH_DEVICE, false)
        set(value) = prefs.edit().putBoolean(KEY_MATCH_DEVICE, value).apply()

    val isKeyLearned: Boolean
        get() = scanCode != UNSET || keyCode != UNSET

    fun learnKey(scanCode: Int, keyCode: Int, deviceId: Int) {
        prefs.edit()
            .putInt(KEY_SCAN, scanCode)
            .putInt(KEY_KEYCODE, keyCode)
            .putInt(KEY_DEVICE, deviceId)
            .apply()
    }

    fun forgetKey() {
        prefs.edit()
            .remove(KEY_SCAN)
            .remove(KEY_KEYCODE)
            .remove(KEY_DEVICE)
            .apply()
    }

    // --- timings ---------------------------------------------------------------

    var longPressMs: Int
        get() = prefs.getInt(KEY_LONG_MS, DEFAULT_LONG_MS)
        set(value) = prefs.edit().putInt(KEY_LONG_MS, value).apply()

    var doublePressMs: Int
        get() = prefs.getInt(KEY_DOUBLE_MS, DEFAULT_DOUBLE_MS)
        set(value) = prefs.edit().putInt(KEY_DOUBLE_MS, value).apply()

    // --- bindings --------------------------------------------------------------

    fun action(gesture: Gesture): ActionSpec {
        val type = ActionType.fromKey(prefs.getString("action_${gesture.key}", null))
        val arg = prefs.getString("arg_${gesture.key}", null)
        return ActionSpec(type, arg)
    }

    fun setAction(gesture: Gesture, spec: ActionSpec) {
        prefs.edit()
            .putString("action_${gesture.key}", spec.type.name)
            .putString("arg_${gesture.key}", spec.arg)
            .apply()
    }

    /**
     * When nothing is bound to a double press we can fire the single press on key-up
     * instead of waiting out the double-press window. Worth it: it is the difference
     * between an instant flashlight and a visibly laggy one.
     */
    val isDoublePressBound: Boolean
        get() = action(Gesture.DOUBLE).type != ActionType.NONE

    companion object {
        const val UNSET = Int.MIN_VALUE
        const val DEFAULT_LONG_MS = 500
        const val DEFAULT_DOUBLE_MS = 280

        private const val PREFS = "button_remapper"
        private const val KEY_SCAN = "scan_code"
        private const val KEY_KEYCODE = "key_code"
        private const val KEY_DEVICE = "device_id"
        private const val KEY_MATCH_DEVICE = "match_device_id"
        private const val KEY_LONG_MS = "long_press_ms"
        private const val KEY_DOUBLE_MS = "double_press_ms"
    }
}
