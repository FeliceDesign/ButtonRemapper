package com.felicedesign.buttonremapper.action

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Toggles the flashlight. `setTorchMode` needs no permission at all.
 *
 * Torch state is tracked through the system callback rather than a local boolean, so
 * the toggle stays correct when the quick-settings tile or another app changes the
 * torch behind our back.
 */
class TorchController(context: Context) {

    private val cameraManager = context.getSystemService(CameraManager::class.java)
    private val cameraId: String? = findFlashCamera()

    @Volatile
    private var isOn = false

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(id: String, enabled: Boolean) {
            if (id == cameraId) isOn = enabled
        }

        override fun onTorchModeUnavailable(id: String) {
            if (id == cameraId) isOn = false
        }
    }

    init {
        if (cameraId != null) {
            cameraManager.registerTorchCallback(torchCallback, Handler(Looper.getMainLooper()))
        } else {
            Log.w(TAG, "No camera with a flash unit found")
        }
    }

    fun toggle() {
        val id = cameraId ?: return
        try {
            cameraManager.setTorchMode(id, !isOn)
        } catch (e: CameraAccessException) {
            // Thrown if the camera is in use by another app; nothing useful to do.
            Log.w(TAG, "Could not toggle torch", e)
        }
    }

    fun release() {
        if (cameraId != null) cameraManager.unregisterTorchCallback(torchCallback)
    }

    /** Prefer the rear flash, but accept any camera that has one. */
    private fun findFlashCamera(): String? = try {
        val withFlash = cameraManager.cameraIdList.filter { id ->
            val chars = cameraManager.getCameraCharacteristics(id)
            chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
        withFlash.firstOrNull { id ->
            val chars = cameraManager.getCameraCharacteristics(id)
            chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: withFlash.firstOrNull()
    } catch (e: CameraAccessException) {
        Log.w(TAG, "Could not enumerate cameras", e)
        null
    }

    private companion object {
        const val TAG = "TorchController"
    }
}
