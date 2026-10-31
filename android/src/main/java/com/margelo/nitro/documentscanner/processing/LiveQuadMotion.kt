package com.margelo.nitro.documentscanner.processing

import com.margelo.nitro.documentscanner.DocumentQuad

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
      val f = NativeMotion.geometryNative(doubleArrayOf(optics.focalLengthPx, optics.activeWidth.toDouble(),
        optics.activeHeight.toDouble(), optics.sensorOrientation.toDouble()),
        intArrayOf(bufferWidth, bufferHeight, cropLeft, cropTop, cropRight, cropBottom, rotationDegrees))
      return LiveFrameGeometry(f[0].toInt(), f[1].toInt(), f[2], f[3], f[4], f[5].toInt())
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
  fun warp(quad: DocumentQuad, frame: LiveFrameGeometry, deviceRotation: DoubleArray): DocumentQuad? =
    NativeMotion.rotationWarpNative(quad.motionValues(), doubleArrayOf(frame.width.toDouble(), frame.height.toDouble(),
      frame.focalPx, frame.centerX, frame.centerY, frame.axisRotationDegrees.toDouble()), deviceRotation)?.motionQuad()
}
