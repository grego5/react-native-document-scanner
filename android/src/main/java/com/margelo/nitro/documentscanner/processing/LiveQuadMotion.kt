package com.margelo.nitro.documentscanner.processing

import com.margelo.nitro.documentscanner.DocumentPoint
import com.margelo.nitro.documentscanner.DocumentQuad
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Moves a normalized upright quad from the frame at sensor time [fromNs] to where it appears at [toNs].
 * Null when the motion is unknown; callers then keep the quad unmoved.
 */
internal fun interface LiveQuadMotion {
  fun warp(quad: DocumentQuad, fromNs: Long, toNs: Long): DocumentQuad?

  companion object {
    val NONE = LiveQuadMotion { _, _, _ -> null }
  }
}

/** Back-camera optics in active-array pixels; [sensorOrientation] as Camera2 reports it. [calibrated] marks a focal
 * length from the HAL intrinsics rather than lens focal length over pixel pitch.
 */
internal data class LiveCameraOptics(
  val focalLengthPx: Double,
  val activeWidth: Int,
  val activeHeight: Int,
  val sensorOrientation: Int,
  val calibrated: Boolean = false,
)

/** Pinhole geometry of one upright analysis frame: focal length and principal point in upright pixels, and the
 * clockwise rotation from the device's natural upright view to this frame.
 */
internal data class LiveFrameGeometry(
  val width: Int,
  val height: Int,
  val focalPx: Double,
  val centerX: Double,
  val centerY: Double,
  val axisRotationDegrees: Int,
) {
  companion object {
    /** The analysis buffer scales the active array, center-cropped to its own aspect ratio. */
    fun of(
      optics: LiveCameraOptics, bufferWidth: Int, bufferHeight: Int,
      cropLeft: Int, cropTop: Int, cropRight: Int, cropBottom: Int, rotationDegrees: Int,
    ): LiveFrameGeometry {
      val scale = max(bufferWidth.toDouble() / optics.activeWidth, bufferHeight.toDouble() / optics.activeHeight)
      val cropWidth = cropRight - cropLeft
      val cropHeight = cropBottom - cropTop
      val x = bufferWidth / 2.0 - cropLeft
      val y = bufferHeight / 2.0 - cropTop
      // Rotate the buffer center into the upright crop, clockwise by rotationDegrees.
      val (centerX, centerY) = when (rotationDegrees) {
        90 -> cropHeight - y to x
        180 -> cropWidth - x to cropHeight - y
        270 -> y to cropWidth - x
        else -> x to y
      }
      val rotated = rotationDegrees % 180 != 0
      return LiveFrameGeometry(
        if (rotated) cropHeight else cropWidth, if (rotated) cropWidth else cropHeight,
        optics.focalLengthPx * scale, centerX, centerY,
        ((rotationDegrees - optics.sensorOrientation) % 360 + 360) % 360,
      )
    }
  }
}

/** Pure camera rotation moves every image point by H = K A R^T A^T K^-1, whatever the scene depth. R is the device
 * rotation from [GyroHistory]; A maps device axes to the frame's camera axes (x right, y down, z forward).
 */
internal class GyroQuadMotion(private val gyro: GyroHistory, private val frame: LiveFrameGeometry) : LiveQuadMotion {
  override fun warp(quad: DocumentQuad, fromNs: Long, toNs: Long): DocumentQuad? {
    if (fromNs == toNs) return quad
    val rotation = gyro.rotation(fromNs, toNs) ?: return null
    return RotationHomography.warp(quad, frame, rotation)
  }
}

internal object RotationHomography {
  // Rear camera, natural upright view: device x right, y up, z out of the screen.
  private val DEVICE_TO_NATURAL_CAMERA = doubleArrayOf(1.0, 0.0, 0.0, 0.0, -1.0, 0.0, 0.0, 0.0, -1.0)

  fun warp(quad: DocumentQuad, frame: LiveFrameGeometry, deviceRotation: DoubleArray): DocumentQuad? {
    val axes = GyroHistory.multiply(imageRotation(frame.axisRotationDegrees), DEVICE_TO_NATURAL_CAMERA)
    val r = GyroHistory.multiply(GyroHistory.multiply(axes, GyroHistory.transpose(deviceRotation)), GyroHistory.transpose(axes))
    fun move(point: DocumentPoint): DocumentPoint? {
      val x = (point.x * frame.width - frame.centerX) / frame.focalPx
      val y = (point.y * frame.height - frame.centerY) / frame.focalPx
      val z = r[6] * x + r[7] * y + r[8]
      if (z <= 1e-6) return null
      val u = frame.focalPx * (r[0] * x + r[1] * y + r[2]) / z + frame.centerX
      val v = frame.focalPx * (r[3] * x + r[4] * y + r[5]) / z + frame.centerY
      return DocumentPoint(u / frame.width, v / frame.height)
    }
    return DocumentQuad(
      move(quad.topLeft) ?: return null, move(quad.topRight) ?: return null,
      move(quad.bottomRight) ?: return null, move(quad.bottomLeft) ?: return null,
    )
  }

  /** Clockwise image rotation in camera axes (y down), about the optical axis. */
  private fun imageRotation(degrees: Int): DoubleArray {
    val radians = Math.toRadians(degrees.toDouble())
    val c = cos(radians)
    val s = sin(radians)
    return doubleArrayOf(c, -s, 0.0, s, c, 0.0, 0.0, 0.0, 1.0)
  }
}
