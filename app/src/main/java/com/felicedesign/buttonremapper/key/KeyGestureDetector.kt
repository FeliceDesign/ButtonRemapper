package com.felicedesign.buttonremapper.key

import android.os.Handler
import com.felicedesign.buttonremapper.data.Gesture

/**
 * Turns a stream of key down/up events into single, double, long and press-then-hold
 * gestures.
 *
 * We do this ourselves rather than leaning on the framework because we own the raw
 * key: nothing else in the system is going to interpret it for us.
 */
class KeyGestureDetector(
    private val handler: Handler,
    private val config: () -> Config,
    private val onGesture: (Gesture) -> Unit
) {

    data class Config(
        val longPressMs: Long,
        val doublePressMs: Long,
        /**
         * True if anything needs us to wait and see whether a second press is coming -
         * a double press or a press-then-hold. When nothing does, a single press fires
         * the instant the key comes up instead of waiting out the window.
         */
        val secondPressBound: Boolean
    )

    private var pressCount = 0
    private var longPressFired = false

    private val longPressRunnable = Runnable {
        longPressFired = true
        // The hold is the second press of a tap-then-hold, not a plain long press.
        // pressCount is already sitting at 2 because the second key-down bumped it and
        // restarted this timer, so the two gestures cost exactly the same bookkeeping.
        val gesture = if (pressCount >= 2) Gesture.SHORT_THEN_LONG else Gesture.LONG
        pressCount = 0
        onGesture(gesture)
    }

    private val singlePressRunnable = Runnable {
        pressCount = 0
        onGesture(Gesture.SINGLE)
    }

    fun onDown(repeatCount: Int) {
        // Auto-repeat while the key is held is not a new press.
        if (repeatCount > 0) return

        // A press arriving while we are waiting out the double-press window means
        // this is the second half of a double press.
        handler.removeCallbacks(singlePressRunnable)

        longPressFired = false
        pressCount++
        handler.postDelayed(longPressRunnable, config().longPressMs)
    }

    fun onUp() {
        handler.removeCallbacks(longPressRunnable)

        // The long press already fired while the key was held; the release is not a press.
        if (longPressFired) {
            longPressFired = false
            pressCount = 0
            return
        }

        val cfg = config()

        if (pressCount >= 2) {
            pressCount = 0
            onGesture(Gesture.DOUBLE)
            return
        }

        if (!cfg.secondPressBound) {
            pressCount = 0
            onGesture(Gesture.SINGLE)
            return
        }

        handler.postDelayed(singlePressRunnable, cfg.doublePressMs)
    }

    /** Drop any pending gesture, e.g. when the service disconnects. */
    fun reset() {
        handler.removeCallbacks(longPressRunnable)
        handler.removeCallbacks(singlePressRunnable)
        pressCount = 0
        longPressFired = false
    }
}
