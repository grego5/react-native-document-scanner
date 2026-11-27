package com.margelo.nitro.documentscanner

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.camera2.interop.applyCamera2InteropAsync
import androidx.camera.core.CameraControl
import androidx.camera.core.Preview

/** A diopter seed is meaningful only on a calibrated or approximately calibrated adjustable lens. */
internal fun startupFocusDistance(characteristics: CameraCharacteristics, requested: Float): Float? {
  val calibration = characteristics.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION)
  if (calibration != CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_CALIBRATED &&
    calibration != CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_APPROXIMATE) return null
  val modes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
  if (modes?.contains(CaptureRequest.CONTROL_AF_MODE_OFF) != true ||
    characteristics.availableCaptureRequestKeys?.contains(CaptureRequest.LENS_FOCUS_DISTANCE) != true) return null
  val maximum = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
    ?.takeIf { it.isFinite() && it > 0f } ?: return null
  return requested.coerceIn(0f, maximum)
}

internal fun CameraControl.applyStartupFocus(diopters: Float) = applyCamera2InteropAsync {
  setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
  setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, diopters)
}

internal fun CameraControl.clearStartupFocus() = applyCamera2InteropAsync {
  clearCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE)
  clearCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE)
}

/** Bind in single-shot mode from the first request. With no trigger, the lens stays idle. */
@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
internal fun Preview.Builder.singleShotFocus(enabled: Boolean): Preview.Builder {
  if (enabled) Camera2Interop.Extender(this)
    .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
  return this
}

/** Replace the manual seed with single-shot mode in a single update. */
internal fun CameraControl.enableSingleShotFocus() = applyCamera2InteropAsync {
  setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
  clearCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE)
}

/** Three consecutive stationary, matching and sharp manual frames can verify a preview hold. */
internal class ManualFocusEvidence {
  private var soft = 0
  private var sharp = 0
  val verified: Boolean get() = sharp >= FocusRules.VERDICT_FRAMES
  val rejected: Boolean get() = soft >= FocusRules.VERDICT_FRAMES

  fun observe(usable: Boolean, sharpPrint: Boolean) {
    if (!usable) {
      reset()
      return
    }
    if (sharpPrint) {
      sharp++
      soft = 0
    } else {
      sharp = 0
      soft++
    }
  }

  fun reset() {
    soft = 0
    sharp = 0
  }
}
