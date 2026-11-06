package com.margelo.nitro.documentscanner.processing

import com.margelo.nitro.documentscanner.DocumentCorners
import java.nio.ByteBuffer

/** Why an estimate has no speed. Unknown never means still. */
internal enum class FlowMotionStatus { MEASURED, NO_REFERENCE, TOO_FEW_FEATURES, TRACKING_FAILED, POOR_COVERAGE }

/** Image motion between two analysis frames. [speedPxPerSecond] is the fastest of the page's 3x3 regions, each judged
 * by its median point speed, or the whole page's upper percentile when that is faster, in pixels of the unrotated
 * analysis buffer; null unless [status] is MEASURED. A region moving while the rest holds still, such as a page edge
 * waving, therefore decides. [sourceWidth] is that buffer's full width, so `speed * exposure / sourceWidth` is a blur
 * fraction comparable with the gyroscope's active-array one. Timestamps are sensor timestamps of the reference and
 * current frames. [confidence] is the share of detected features that survived the forward/backward check;
 * [validTracks] is their count. [lostRegions] counts regions that had features but lost most of them, as smeared or
 * deforming motion does; their speed is unknown, so the capture gate counts them as moving.
 */
internal data class FlowMotionEstimate(
  val status: FlowMotionStatus,
  val speedPxPerSecond: Double?,
  val sourceWidth: Int,
  val fromSensorNs: Long,
  val toSensorNs: Long,
  val confidence: Double,
  val validTracks: Int,
  val lostRegions: Int = 0,
  val transform: FlowTransform? = null,
  val transformProcessingNs: Long? = null,
)

/** Analysis-executor adapter. Native state owns its downscaled reference; no frame buffer escapes update. */
internal class FlowMotionEstimator(
  maxEdge: Int = MAX_EDGE, maxFeatures: Int = MAX_FEATURES, maxGapNs: Long = MAX_GAP_NS,
) : AutoCloseable {
  private val native = NativeFlowEstimator(maxEdge, maxFeatures, maxGapNs)
  fun reset() = native.reset()
  fun update(
    pixels: ByteBuffer, rowStride: Int, pixelStride: Int, bufferWidth: Int, bufferHeight: Int,
    cropLeft: Int, cropTop: Int, cropRight: Int, cropBottom: Int, rotationDegrees: Int, sensorTimestampNs: Long,
    region: DocumentCorners?, fitTransform: Boolean = false,
  ): FlowMotionEstimate = native.update(pixels, rowStride, pixelStride, bufferWidth, bufferHeight,
    cropLeft, cropTop, cropRight, cropBottom, rotationDegrees, sensorTimestampNs, region, fitTransform)
  override fun close() = native.close()
  companion object {
    const val MAX_EDGE = 224
    const val MAX_FEATURES = 50
    const val MAX_GAP_NS = 200_000_000L
    const val MIN_TRACKS = 8
    const val SPEED_PERCENTILE = 0.9
  }
}
