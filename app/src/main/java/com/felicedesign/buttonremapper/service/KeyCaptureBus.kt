package com.felicedesign.buttonremapper.service

/**
 * Connects the "press your key now" screen to the accessibility service.
 *
 * The service and the UI live in the same process and accessibility callbacks arrive
 * on the main thread, so a plain listener is enough - no IPC, no coroutines.
 */
object KeyCaptureBus {

    data class CapturedKey(
        val scanCode: Int,
        val keyCode: Int,
        val deviceId: Int
    )

    /** True while the UI is waiting for a key. The service swallows keys during this. */
    @Volatile
    var isLearning: Boolean = false
        private set

    private var listener: ((CapturedKey) -> Unit)? = null

    fun startLearning(onCaptured: (CapturedKey) -> Unit) {
        listener = onCaptured
        isLearning = true
    }

    fun stopLearning() {
        isLearning = false
        listener = null
    }

    internal fun report(key: CapturedKey) {
        listener?.invoke(key)
    }
}
