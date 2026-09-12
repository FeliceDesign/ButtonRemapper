package com.felicedesign.buttonremapper.data

/** The three ways the key can be pressed. */
enum class Gesture(val label: String, val key: String) {
    SINGLE("Single press", "single"),
    DOUBLE("Double press", "double"),
    LONG("Long press", "long")
}

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
 * TAP_POINT / LONG_PRESS_POINT are stateless in the same sense: they fire at a fixed
 * screen coordinate you calibrated yourself. We never look at what is *under* that
 * coordinate, which is exactly why they need no window content - and also why they
 * will happily tap whatever app happens to be in front of you.
 */
enum class ActionType(
    val label: String,
    val group: String,
    /** True if the action starts an activity, which needs the overlay permission. */
    val needsOverlayPermission: Boolean = false,
    /** True if the action needs an argument (currently only a package name). */
    val needsApp: Boolean = false,
    /** True if the action needs a calibrated screen coordinate as its argument. */
    val needsPoint: Boolean = false
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

    TAP_POINT("Tap a spot on screen", "Screen", needsPoint = true),
    LONG_PRESS_POINT("Long-press a spot on screen", "Screen", needsPoint = true),

    POWER_DIALOG("Power menu", "System"),
    LOCK_SCREEN("Lock screen", "System"),
    SCREENSHOT("Screenshot", "System");

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
 * gesture carries its own point - single press can tap the shutter while double press
 * taps the video-mode tab, with no extra storage wiring.
 */
data class ScreenPoint(val x: Int, val y: Int) {

    fun encode(): String = "$x,$y"

    override fun toString(): String = "$x, $y"

    companion object {
        fun decode(raw: String?): ScreenPoint? {
            val parts = raw?.split(',') ?: return null
            if (parts.size != 2) return null
            val x = parts[0].trim().toIntOrNull() ?: return null
            val y = parts[1].trim().toIntOrNull() ?: return null
            return ScreenPoint(x, y)
        }
    }
}
