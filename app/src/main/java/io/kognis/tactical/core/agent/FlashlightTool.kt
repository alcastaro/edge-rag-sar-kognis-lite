package io.kognis.tactical.core.agent

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log

/**
 * Flashlight tool — agentic action exposed to the operator and (future) the LLM.
 *
 * Field utility: signaling, dark-conditions triage, low-light marker placement.
 * Calls [CameraManager.setTorchMode] directly — no CAMERA permission required
 * since API 23, so the Zero-Signal posture stays intact (no camera capture, no
 * data flow off-device).
 *
 * Architectural role: a deterministic local tool the agent loop can invoke
 * either via UI button (current) or via a future structured tool-call from the
 * LLM (e.g. response containing FLASHLIGHT_ON / FLASHLIGHT_OFF tags).
 */
object FlashlightTool {

    private const val TAG = "FlashlightTool"

    @Volatile var isOn: Boolean = false
        private set

    private var cameraId: String? = null

    private fun resolveCameraId(context: Context): String? {
        cameraId?.let { return it }
        return runCatching {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cm.cameraIdList.firstOrNull { id ->
                val ch = cm.getCameraCharacteristics(id)
                ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrNull().also { cameraId = it }
    }

    fun isAvailable(context: Context): Boolean = resolveCameraId(context) != null

    /** Toggle torch. Returns the new state (true = on). Returns current state on failure. */
    fun toggle(context: Context): Boolean {
        val target = !isOn
        return setState(context, target)
    }

    /** Force a specific state. Returns the actual state after the call. */
    fun setState(context: Context, on: Boolean): Boolean {
        val id = resolveCameraId(context) ?: run {
            Log.w(TAG, "No flash-capable camera")
            return isOn
        }
        return runCatching {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cm.setTorchMode(id, on)
            isOn = on
            Log.i(TAG, "Torch ${if (on) "ON" else "OFF"} (camera $id)")
            on
        }.getOrElse {
            Log.e(TAG, "Torch toggle failed: ${it.message}")
            isOn
        }
    }
}
