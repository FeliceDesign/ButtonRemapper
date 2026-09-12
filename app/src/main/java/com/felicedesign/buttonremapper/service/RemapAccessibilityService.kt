package com.felicedesign.buttonremapper.service

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.felicedesign.buttonremapper.action.ActionRunner
import com.felicedesign.buttonremapper.data.ScreenPoint
import com.felicedesign.buttonremapper.data.SettingsStore
import com.felicedesign.buttonremapper.key.KeyGestureDetector

/**
 * The whole point of this app: catch one hardware key and nothing else.
 *
 * The service is declared with no event types and no window-content capability (see
 * res/xml/accessibility_service_config.xml). `onAccessibilityEvent` is therefore never
 * called - only `onKeyEvent` is.
 */
class RemapAccessibilityService : AccessibilityService(), CalibrationBus.Host {

    private lateinit var settings: SettingsStore
    private lateinit var runner: ActionRunner
    private lateinit var detector: KeyGestureDetector
    private lateinit var calibration: CalibrationOverlay

    override fun onServiceConnected() {
        super.onServiceConnected()
        settings = SettingsStore(this)
        runner = ActionRunner(this, settings)
        detector = KeyGestureDetector(
            handler = Handler(Looper.getMainLooper()),
            config = {
                KeyGestureDetector.Config(
                    longPressMs = settings.longPressMs.toLong(),
                    doublePressMs = settings.doublePressMs.toLong(),
                    secondPressBound = settings.isSecondPressBound
                )
            },
            onGesture = { gesture -> runner.run(gesture, settings.action(gesture)) }
        )
        calibration = CalibrationOverlay(this)
        CalibrationBus.registerHost(this)
        isRunning = true
    }

    // --- CalibrationBus.Host ---------------------------------------------------
    //
    // The crosshair has to outlive the activity that asked for it, because the whole
    // point is to aim it at someone else's app. Hosting it here is what makes that work.

    override fun showCalibrationOverlay(
        prompt: String,
        allowMore: Boolean,
        cancelLabel: String,
        onResult: (ScreenPoint?, Boolean) -> Unit
    ) = calibration.show(prompt, allowMore, cancelLabel, onResult)

    override fun hideCalibrationOverlay() = calibration.hide()

    override fun showPointMarkers(points: List<ScreenPoint>, longPress: Boolean) {
        val duration = if (longPress) settings.tapLongPressMs else settings.tapMs
        calibration.showMarkers(points) { point -> runner.testTap(point, duration.toLong()) }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // Learning mode: capture the next key press and swallow everything so the key
        // being learned cannot fire its old behaviour mid-capture.
        if (KeyCaptureBus.isLearning) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                KeyCaptureBus.report(
                    KeyCaptureBus.CapturedKey(
                        scanCode = event.scanCode,
                        keyCode = event.keyCode,
                        deviceId = event.deviceId
                    )
                )
            }
            return true
        }

        if (!matches(event)) return false

        when (event.action) {
            KeyEvent.ACTION_DOWN -> detector.onDown(event.repeatCount)
            KeyEvent.ACTION_UP -> detector.onUp()
        }

        // Consume it, so the key does not also reach whatever is in the foreground.
        return true
    }

    /**
     * The Essential Key reports keyCode 0 (KEYCODE_UNKNOWN), so the scan code is the
     * identity that matters. keyCode is only consulted for keys that do report one.
     */
    private fun matches(event: KeyEvent): Boolean {
        val storedScan = settings.scanCode
        val storedKeyCode = settings.keyCode

        val identityMatches = when {
            storedScan != SettingsStore.UNSET && storedScan != 0 -> event.scanCode == storedScan
            storedKeyCode != SettingsStore.UNSET && storedKeyCode != 0 -> event.keyCode == storedKeyCode
            else -> false
        }
        if (!identityMatches) return false

        if (!settings.matchDeviceId) return true
        val storedDevice = settings.deviceId
        return storedDevice == SettingsStore.UNSET || event.deviceId == storedDevice
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Never called: the service registers for no event types.
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        isRunning = false
        CalibrationBus.registerHost(null)
        if (::calibration.isInitialized) calibration.hide()
        if (::detector.isInitialized) detector.reset()
        if (::runner.isInitialized) runner.release()
        return super.onUnbind(intent)
    }

    companion object {
        /**
         * Live connection state. The UI also checks Settings.Secure, which is
         * authoritative when our process is not running.
         */
        @Volatile
        var isRunning: Boolean = false
            private set
    }
}
