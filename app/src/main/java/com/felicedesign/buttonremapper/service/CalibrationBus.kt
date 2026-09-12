package com.felicedesign.buttonremapper.service

import com.felicedesign.buttonremapper.data.ScreenPoint

/**
 * Connects the "pick a spot" UI to the accessibility service, the same way
 * [KeyCaptureBus] connects the "press your key" UI.
 *
 * The indirection exists for one reason: the crosshair has to stay on screen *after*
 * you leave ButtonRemapper and open your camera. A window owned by our activity dies
 * with the activity, and a plain background Service gets reaped under Android's
 * background execution limits. The accessibility service is already running and stays
 * running, so it hosts the overlay instead.
 */
object CalibrationBus {

    /** Implemented by the accessibility service. */
    interface Host {
        fun showCalibrationOverlay(
            prompt: String,
            allowMore: Boolean,
            cancelLabel: String,
            onResult: (ScreenPoint?, Boolean) -> Unit
        )

        fun hideCalibrationOverlay()
    }

    @Volatile
    private var host: Host? = null

    /** False when the accessibility service is not connected - nothing can host a window. */
    val isAvailable: Boolean
        get() = host != null

    internal fun registerHost(candidate: Host?) {
        host = candidate
    }

    /**
     * Puts the crosshair on screen.
     *
     * [prompt] is shown in the floating panel, so a multi-point calibration can say
     * which point is being aimed. [allowMore] adds a second save button for "save this
     * one and keep going", which is how a cycle collects an arbitrary number of points.
     *
     * [onResult] gets the chosen point (or null if cancelled from either side) and
     * whether the user asked to add another.
     */
    fun start(
        prompt: String,
        allowMore: Boolean = false,
        cancelLabel: String = "Cancel",
        onResult: (ScreenPoint?, Boolean) -> Unit
    ): Boolean {
        val current = host ?: return false
        current.showCalibrationOverlay(prompt, allowMore, cancelLabel, onResult)
        return true
    }

    /** Called when the UI goes away before a point was picked. */
    fun cancel() {
        host?.hideCalibrationOverlay()
    }
}
