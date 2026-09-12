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

    /**
     * How long a synthesised tap holds the screen down. Some targets ignore a touch
     * that is too brief, so this is adjustable rather than a constant.
     */
    var tapMs: Int
        get() = prefs.getInt(KEY_TAP_MS, DEFAULT_TAP_MS)
        set(value) = prefs.edit().putInt(KEY_TAP_MS, value).apply()

    /** How long a synthesised long-press holds. Must clear the platform's 500 ms. */
    var tapLongPressMs: Int
        get() = prefs.getInt(KEY_TAP_LONG_MS, DEFAULT_TAP_LONG_MS)
        set(value) = prefs.edit().putInt(KEY_TAP_LONG_MS, value).apply()

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
            // Re-binding invalidates where we were in a cycle: the new points are not
            // the old ones, so resuming mid-way would tap the wrong thing once.
            .putInt("cycle_${gesture.key}", 0)
            .apply()
    }

    // --- cycle position --------------------------------------------------------

    /**
     * Which point [ActionType.TAP_CYCLE] will tap next.
     *
     * Persisted rather than held in memory so the cycle survives the service being
     * restarted - you should not lose your place because Android reclaimed the process
     * between two shots.
     */
    fun cycleIndex(gesture: Gesture): Int = prefs.getInt("cycle_${gesture.key}", 0)

    fun setCycleIndex(gesture: Gesture, index: Int) {
        prefs.edit().putInt("cycle_${gesture.key}", index).apply()
    }

    /**
     * When nothing needs a second press we can fire the single press on key-up instead
     * of waiting out the double-press window. Worth it: it is the difference between an
     * instant shutter and a visibly laggy one.
     *
     * Press-then-hold counts here as well as double press. Both begin with a tap and a
     * release, so both need us to hold the single press back and see what follows.
     */
    val isSecondPressBound: Boolean
        get() = action(Gesture.DOUBLE).type != ActionType.NONE ||
            action(Gesture.SHORT_THEN_LONG).type != ActionType.NONE

    companion object {
        const val UNSET = Int.MIN_VALUE
        const val DEFAULT_LONG_MS = 500
        const val DEFAULT_DOUBLE_MS = 280
        const val DEFAULT_TAP_MS = 60
        const val DEFAULT_TAP_LONG_MS = 700

        private const val PREFS = "button_remapper"
        private const val KEY_SCAN = "scan_code"
        private const val KEY_KEYCODE = "key_code"
        private const val KEY_DEVICE = "device_id"
        private const val KEY_MATCH_DEVICE = "match_device_id"
        private const val KEY_LONG_MS = "long_press_ms"
        private const val KEY_DOUBLE_MS = "double_press_ms"
        private const val KEY_TAP_MS = "tap_ms"
        private const val KEY_TAP_LONG_MS = "tap_long_press_ms"
    }
}
