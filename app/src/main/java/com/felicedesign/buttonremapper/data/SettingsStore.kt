package com.felicedesign.buttonremapper.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Plain SharedPreferences. The accessibility service reads these on every key press,
 * which is cheap because the values are held in memory by the framework, and it means
 * changes from the UI take effect immediately with no wiring between the two.
 *
 * That property is what makes presets almost free: every read resolves the active
 * preset first, so switching preset is a single int write and the service picks it up
 * on the very next press. There is nothing to notify, restart or invalidate.
 *
 * Per-preset keys are namespaced `p<id>_`. The learned key is deliberately *not* -
 * the scan code identifies a piece of hardware, not a mapping, and having to re-learn
 * the Essential Key every time you switched preset would be absurd.
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    init {
        if (!prefs.contains(KEY_PRESET_IDS)) adoptExistingSettingsAsFirstPreset()
    }

    // --- the learned key (global) ----------------------------------------------

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

    // --- presets ----------------------------------------------------------------

    var activePresetId: Int
        get() {
            val stored = prefs.getInt(KEY_ACTIVE, FIRST_ID)
            // Survive the active preset having been deleted by another code path.
            return if (stored in presetIds()) stored else presetIds().first()
        }
        set(value) = prefs.edit().putInt(KEY_ACTIVE, value).apply()

    val presets: List<Preset>
        get() = presetIds().map { Preset(it, prefs.getString(nameKey(it), null) ?: "Preset $it") }

    /**
     * @param copyFrom an existing preset to clone, which is how you build a variant of
     * a working setup without re-calibrating every point by hand.
     */
    fun createPreset(name: String, copyFrom: Int? = null): Int {
        val id = prefs.getInt(KEY_NEXT_ID, FIRST_ID + 1)
        val editor = prefs.edit()
            .putInt(KEY_NEXT_ID, id + 1)
            .putString(KEY_PRESET_IDS, (presetIds() + id).joinToString(","))
            .putString(nameKey(id), name)

        if (copyFrom != null) copyPreset(editor, from = copyFrom, to = id)
        editor.apply()
        return id
    }

    fun renamePreset(id: Int, name: String) {
        prefs.edit().putString(nameKey(id), name).apply()
    }

    /** Refuses to remove the last preset: there always has to be something active. */
    fun deletePreset(id: Int): Boolean {
        val remaining = presetIds().filterNot { it == id }
        if (remaining.isEmpty()) return false

        val editor = prefs.edit()
            .putString(KEY_PRESET_IDS, remaining.joinToString(","))
            .remove(nameKey(id))
        perPresetKeys().forEach { editor.remove(scopedKey(id, it)) }
        if (activePresetId == id) editor.putInt(KEY_ACTIVE, remaining.first())
        editor.apply()
        return true
    }

    private fun presetIds(): List<Int> =
        prefs.getString(KEY_PRESET_IDS, null)
            ?.split(',')
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(FIRST_ID)

    private fun copyPreset(editor: SharedPreferences.Editor, from: Int, to: Int) {
        val all = prefs.all
        perPresetKeys().forEach { suffix ->
            when (val value = all[scopedKey(from, suffix)]) {
                is String -> editor.putString(scopedKey(to, suffix), value)
                is Int -> editor.putInt(scopedKey(to, suffix), value)
                else -> Unit
            }
        }
    }

    /**
     * Moves a pre-presets install's settings into preset 1 rather than stranding them.
     *
     * Runs once, on the first launch after upgrading: without it every calibrated point
     * would silently read as unset and have to be aimed again.
     */
    private fun adoptExistingSettingsAsFirstPreset() {
        val all = prefs.all
        val editor = prefs.edit()
            .putString(KEY_PRESET_IDS, FIRST_ID.toString())
            .putInt(KEY_ACTIVE, FIRST_ID)
            .putInt(KEY_NEXT_ID, FIRST_ID + 1)
            .putString(nameKey(FIRST_ID), DEFAULT_NAME)

        perPresetKeys().forEach { suffix ->
            when (val value = all[suffix]) {
                is String -> editor.putString(scopedKey(FIRST_ID, suffix), value)
                is Int -> editor.putInt(scopedKey(FIRST_ID, suffix), value)
                else -> Unit
            }
            editor.remove(suffix)
        }
        editor.apply()
    }

    private fun perPresetKeys(): List<String> = buildList {
        Gesture.entries.forEach { gesture ->
            add(actionKey(gesture))
            add(argKey(gesture))
            add(cycleKey(gesture))
        }
        add(KEY_LONG_MS)
        add(KEY_DOUBLE_MS)
        add(KEY_TAP_MS)
        add(KEY_TAP_LONG_MS)
    }

    private fun scopedKey(presetId: Int, suffix: String) = "p${presetId}_$suffix"

    private fun scoped(suffix: String) = scopedKey(activePresetId, suffix)

    private fun nameKey(id: Int) = "preset_name_$id"

    private fun actionKey(gesture: Gesture) = "action_${gesture.key}"

    private fun argKey(gesture: Gesture) = "arg_${gesture.key}"

    private fun cycleKey(gesture: Gesture) = "cycle_${gesture.key}"

    // --- timings (per preset) ---------------------------------------------------

    var longPressMs: Int
        get() = prefs.getInt(scoped(KEY_LONG_MS), DEFAULT_LONG_MS)
        set(value) = prefs.edit().putInt(scoped(KEY_LONG_MS), value).apply()

    var doublePressMs: Int
        get() = prefs.getInt(scoped(KEY_DOUBLE_MS), DEFAULT_DOUBLE_MS)
        set(value) = prefs.edit().putInt(scoped(KEY_DOUBLE_MS), value).apply()

    /**
     * How long a synthesised tap holds the screen down, for points that do not carry
     * their own. Some targets ignore a touch that is too brief, and some read a longer
     * one as a different gesture entirely.
     */
    var tapMs: Int
        get() = prefs.getInt(scoped(KEY_TAP_MS), DEFAULT_TAP_MS)
        set(value) = prefs.edit().putInt(scoped(KEY_TAP_MS), value).apply()

    /** How long a synthesised long-press holds. Must clear the platform's 500 ms. */
    var tapLongPressMs: Int
        get() = prefs.getInt(scoped(KEY_TAP_LONG_MS), DEFAULT_TAP_LONG_MS)
        set(value) = prefs.edit().putInt(scoped(KEY_TAP_LONG_MS), value).apply()

    // --- bindings (per preset) --------------------------------------------------

    fun action(gesture: Gesture): ActionSpec {
        val type = ActionType.fromKey(prefs.getString(scoped(actionKey(gesture)), null))
        val arg = prefs.getString(scoped(argKey(gesture)), null)
        return ActionSpec(type, arg)
    }

    fun setAction(gesture: Gesture, spec: ActionSpec) {
        prefs.edit()
            .putString(scoped(actionKey(gesture)), spec.type.name)
            .putString(scoped(argKey(gesture)), spec.arg)
            // Re-binding invalidates where we were in a cycle: the new points are not
            // the old ones, so resuming mid-way would tap the wrong thing once.
            .putInt(scoped(cycleKey(gesture)), 0)
            .apply()
    }

    /**
     * Updates only the argument, leaving the cycle position alone.
     *
     * Unlike [setAction], which resets it: re-aiming or re-timing a point keeps the
     * same number of points in the same order, so where you are in the cycle is still
     * meaningful and throwing it away would cost a press to recover.
     */
    fun setActionArg(gesture: Gesture, arg: String?) {
        prefs.edit().putString(scoped(argKey(gesture)), arg).apply()
    }

    // --- cycle position (per preset) --------------------------------------------

    /**
     * Which point [ActionType.TAP_CYCLE] will tap next.
     *
     * Persisted rather than held in memory so the cycle survives the service being
     * restarted - you should not lose your place because Android reclaimed the process
     * between two shots. Scoped per preset so switching away and back resumes where
     * that preset was rather than where some other preset happened to be.
     */
    fun cycleIndex(gesture: Gesture): Int = prefs.getInt(scoped(cycleKey(gesture)), 0)

    fun setCycleIndex(gesture: Gesture, index: Int) {
        prefs.edit().putInt(scoped(cycleKey(gesture)), index).apply()
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
        const val DEFAULT_NAME = "Default"

        private const val PREFS = "button_remapper"
        private const val FIRST_ID = 1

        private const val KEY_SCAN = "scan_code"
        private const val KEY_KEYCODE = "key_code"
        private const val KEY_DEVICE = "device_id"
        private const val KEY_MATCH_DEVICE = "match_device_id"

        private const val KEY_PRESET_IDS = "preset_ids"
        private const val KEY_ACTIVE = "preset_active"
        private const val KEY_NEXT_ID = "preset_next_id"

        private const val KEY_LONG_MS = "long_press_ms"
        private const val KEY_DOUBLE_MS = "double_press_ms"
        private const val KEY_TAP_MS = "tap_ms"
        private const val KEY_TAP_LONG_MS = "tap_long_press_ms"
    }
}
