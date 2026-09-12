package com.felicedesign.buttonremapper.data

/**
 * A named set of bindings and timings.
 *
 * The learned key is not part of one: a scan code identifies hardware, not a mapping.
 */
data class Preset(val id: Int, val name: String)

/** The ways the key can be pressed. */
enum class Gesture(val label: String, val key: String) {
    SINGLE("Single press", "single"),
    DOUBLE("Double press", "double"),
    LONG("Long press", "long"),

    /**
     * Tap, then press and hold - "tap taaaap".
     *
     * Cheaper than a triple press, which is the obvious way to buy a fourth gesture and
     * the wrong one: a triple press forces every single press to wait out two
     * double-press windows before it can be ruled out. This one costs nothing. The
     * second key-down of a double press already starts the long-press timer, so the
     * gesture is just "that timer fired while the press count was 2".
     */
    SHORT_THEN_LONG("Press, then hold", "short_long")
}

/** How many calibrated screen points an action needs. */
enum class PointNeed { NONE, ONE, MANY }

/**
 * Everything this app can do.
 *
 * Deliberate constraint: every action here is a stateless system operation. None of
 * them needs to know what is currently on screen, which is why the accessibility
 * service can run with `canRetrieveWindowContent="false"`. Anything of the form
 * "do X only when app Y is focused" would require window content and would
 * reintroduce the WebView drag-and-drop breakage. That feature is intentionally
 * absent - see docs/DESIGN.md.
 *
 * The tap actions are stateless in the same sense: they fire at coordinates you
 * calibrated yourself. We never look at what is *under* a coordinate, which is exactly
 * why they need no window content - and also why they will happily tap whatever app
 * happens to be in front of you.
 */
enum class ActionType(
    val label: String,
    val group: String,
    /** True if the action starts an activity, which needs the overlay permission. */
    val needsOverlayPermission: Boolean = false,
    /** True if the action needs an argument (currently only a package name). */
    val needsApp: Boolean = false,
    /** How many calibrated screen points the action needs. */
    val points: PointNeed = PointNeed.NONE
) {
    NONE("Do nothing", "General"),

    TORCH("Toggle flashlight", "General"),
    LAUNCH_APP("Launch app", "General", needsOverlayPermission = true, needsApp = true),

    MEDIA_PLAY_PAUSE("Play / pause", "Media"),
    MEDIA_NEXT("Next track", "Media"),
    MEDIA_PREVIOUS("Previous track", "Media"),
    VOLUME_UP("Volume up", "Media"),
    VOLUME_DOWN("Volume down", "Media"),
    VOLUME_MUTE("Mute / unmute", "Media"),

    BACK("Back", "Navigation"),
    HOME("Home", "Navigation"),
    RECENTS("Recent apps", "Navigation"),
    NOTIFICATIONS("Notification shade", "Navigation"),
    QUICK_SETTINGS("Quick settings", "Navigation"),

    TAP_POINT("Tap a spot", "Screen", points = PointNeed.ONE),
    LONG_PRESS_POINT("Long-press a spot", "Screen", points = PointNeed.ONE),

    /**
     * Taps a different spot each time, looping back to the first.
     *
     * Two points is a toggle (1x <-> 3.5x, photo <-> video); more is a carousel. It
     * counts rather than looks - the app cannot see which zoom level is selected
     * without window content - so changing the setting by hand puts the cycle out of
     * phase until the next press catches it up. The UI shows which step is next and
     * offers a reset for exactly that.
     *
     * Calibrating each point from the state it fires in is what makes this robust:
     * aim at "3.5" while sitting at 1x, and at "1" while sitting at 3.5x, so a UI that
     * re-flows around the selected item is measured in the layout it will meet.
     */
    TAP_CYCLE("Cycle through spots", "Screen", points = PointNeed.MANY),

    POWER_DIALOG("Power menu", "System"),
    LOCK_SCREEN("Lock screen", "System"),
    SCREENSHOT("Screenshot", "System");

    val needsPoints: Boolean
        get() = points != PointNeed.NONE

    companion object {
        fun fromKey(key: String?): ActionType =
            entries.firstOrNull { it.name == key } ?: NONE
    }
}

/** A chosen action, plus its argument if it needs one. */
data class ActionSpec(
    val type: ActionType = ActionType.NONE,
    val arg: String? = null
)

/**
 * An absolute display coordinate, in the same space `dispatchGesture` expects: pixels
 * from the top-left of the physical display, status and navigation bars included.
 *
 * Stored in [ActionSpec.arg] rather than in its own preference key so that each
 * gesture carries its own points with no extra storage wiring.
 *
 * [durationMs] is how long the touch is held at this point, and it is per-point for a
 * reason: how long you hold is not a detail, it selects which gesture the target thinks
 * it received. A camera shutter may want a leisurely touch, while a zoom chip row that
 * snaps on a tap and opens a continuous slider on a hold wants the briefest one
 * possible. One global duration cannot satisfy both. Null falls back to the global
 * setting for the action.
 */
data class ScreenPoint(val x: Int, val y: Int, val durationMs: Int? = null) {

    fun encode(): String = if (durationMs == null) "$x,$y" else "$x,$y,$durationMs"

    override fun toString(): String = "$x, $y"

    companion object {
        /** Accepts "x,y" as well as "x,y,ms", so points saved before durations still load. */
        fun decode(raw: String?): ScreenPoint? {
            val parts = raw?.split(',') ?: return null
            if (parts.size !in 2..3) return null
            val x = parts[0].trim().toIntOrNull() ?: return null
            val y = parts[1].trim().toIntOrNull() ?: return null
            val duration = if (parts.size == 3) parts[2].trim().toIntOrNull() else null
            return ScreenPoint(x, y, duration)
        }

        fun encodeList(points: List<ScreenPoint>): String =
            points.joinToString(";") { it.encode() }

        fun decodeList(raw: String?): List<ScreenPoint> =
            raw?.split(';')?.mapNotNull { decode(it) } ?: emptyList()
    }
}
