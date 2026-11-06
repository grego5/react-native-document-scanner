package com.margelo.nitro.documentscanner.processing

import android.hardware.camera2.CameraCharacteristics

/** Reads the camera geometry gyro motion compensation needs. */
internal object CameraOpticsReader {
  /** Optics in active-array pixels, or null without the needed characteristics. The HAL intrinsic focal length is
   * defined on the pre-correction array, so it is used only when that array equals the active array; otherwise the
   * focal length is the lens focal length over the pixel pitch.
   */
  fun read(characteristics: CameraCharacteristics): LiveCameraOptics? {
    val active = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null
    val orientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: return null
    val intrinsic = characteristics.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
      ?.firstOrNull()?.toDouble()?.takeIf { it > 0 }
    val preCorrection = characteristics.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
    if (intrinsic != null && preCorrection == active) {
      return LiveCameraOptics(intrinsic, active.width(), active.height(), orientation, calibrated = true)
    }
    val focalMm = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return null
    val physical = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
    val pixels = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE) ?: return null
    if (physical.width <= 0f || pixels.width <= 0) return null
    return LiveCameraOptics(focalMm.toDouble() * pixels.width / physical.width, active.width(), active.height(), orientation)
  }

  /** Only REALTIME camera timestamps are comparable with gyroscope event timestamps. */
  fun sharesGyroClock(characteristics: CameraCharacteristics): Boolean =
    characteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
      CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
}
