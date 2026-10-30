package com.margelo.nitro.documentscanner.processing

import com.margelo.nitro.documentscanner.DocumentQuad
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.core.TermCriteria
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video

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

/** Pyramidal Lucas–Kanade tracking of text-like features on a downscaled grey copy of each analysis frame. Owns every
 * OpenCV buffer; confine it to one thread and [close] it there.
 */
internal class FlowMotionEstimator(
  private val maxEdge: Int = MAX_EDGE,
  private val maxFeatures: Int = MAX_FEATURES,
  private val maxGapNs: Long = MAX_GAP_NS,
) : AutoCloseable {
  init { OpenCvRuntime.ensureLoaded() }

  private var row = ByteArray(0)
  private var luma = ByteArray(0)
  private val full = Mat()
  private var previous = Mat()
  private var current = Mat()
  private val mask = Mat()
  private val corners = MatOfPoint()
  private val previousPoints = MatOfPoint2f()
  private val nextPoints = MatOfPoint2f()
  private val backPoints = MatOfPoint2f()
  private val status = MatOfByte()
  private val backStatus = MatOfByte()
  private val error = MatOfFloat()
  private val backError = MatOfFloat()
  private val window = Size(WINDOW_PX.toDouble(), WINDOW_PX.toDouble())
  private val criteria = TermCriteria(TermCriteria.COUNT + TermCriteria.EPS, 20, 0.03)
  private var hasPrevious = false
  private var previousSensorNs = 0L
  private var bufferWidth = 0
  private var bufferHeight = 0
  private var cropLeft = 0
  private var cropTop = 0
  private var cropRight = 0
  private var cropBottom = 0
  private var rotation = 0
  private var closed = false

  /** Drops the reference frame; the next update starts tracking afresh. */
  fun reset() {
    hasPrevious = false
  }

  /** Tracks from the previous frame to this RGBA_8888 one, whose crop lies in a [bufferWidth] x [bufferHeight] buffer.
   * [region], in normalized upright coordinates after [rotationDegrees], only selects where features are sought; null
   * or too small uses the frame's centre. The buffer's position is the first pixel; pixels are copied before returning.
   */
  fun update(
    pixels: ByteBuffer, rowStride: Int, pixelStride: Int, bufferWidth: Int, bufferHeight: Int,
    cropLeft: Int, cropTop: Int, cropRight: Int, cropBottom: Int, rotationDegrees: Int, sensorTimestampNs: Long,
    region: DocumentQuad?,
    fitTransform: Boolean = false,
  ): FlowMotionEstimate {
    check(!closed) { "The motion estimator is closed." }
    val sameGeometry = bufferWidth == this.bufferWidth && bufferHeight == this.bufferHeight &&
      cropLeft == this.cropLeft && cropTop == this.cropTop && cropRight == this.cropRight &&
      cropBottom == this.cropBottom && rotationDegrees == rotation
    val gap = sensorTimestampNs - previousSensorNs
    val tracking = hasPrevious && sameGeometry && gap in 1..maxGapNs
    this.bufferWidth = bufferWidth
    this.bufferHeight = bufferHeight
    this.cropLeft = cropLeft
    this.cropTop = cropTop
    this.cropRight = cropRight
    this.cropBottom = cropBottom
    rotation = rotationDegrees
    val cropWidth = cropRight - cropLeft
    val cropHeight = cropBottom - cropTop
    loadGrey(pixels, rowStride, pixelStride, cropWidth, cropHeight)
    val estimate = if (tracking) track(region, cropWidth, cropHeight, gap, sensorTimestampNs, fitTransform)
      else FlowMotionEstimate(FlowMotionStatus.NO_REFERENCE, null, bufferWidth, sensorTimestampNs, sensorTimestampNs, 0.0, 0)
    previous = current.also { current = previous }
    previousSensorNs = sensorTimestampNs
    hasPrevious = true
    return estimate
  }

  /** Writes the crop's luma into [current], area-downscaled to at most [maxEdge] pixels on its long edge. */
  private fun loadGrey(pixels: ByteBuffer, rowStride: Int, pixelStride: Int, cropWidth: Int, cropHeight: Int) {
    require(cropWidth > 0 && cropHeight > 0) { "The crop must not be empty." }
    val size = cropWidth * cropHeight
    if (luma.size < size) luma = ByteArray(size)
    val rowBytes = (cropWidth - 1) * pixelStride + 4
    if (row.size < rowBytes) row = ByteArray(rowBytes)
    val source = pixels.duplicate()
    val base = pixels.position()
    var index = 0
    for (y in 0 until cropHeight) {
      source.position(base + (cropTop + y) * rowStride + cropLeft * pixelStride)
      source.get(row, 0, rowBytes)
      var offset = 0
      for (x in 0 until cropWidth) {
        val r = row[offset].toInt() and 0xFF
        val g = row[offset + 1].toInt() and 0xFF
        val b = row[offset + 2].toInt() and 0xFF
        luma[index++] = ((77 * r + 150 * g + 29 * b) shr 8).toByte()
        offset += pixelStride
      }
    }
    full.create(cropHeight, cropWidth, CvType.CV_8UC1)
    full.put(0, 0, luma, 0, size)
    val scale = min(1.0, maxEdge.toDouble() / max(cropWidth, cropHeight))
    val width = max(1, (cropWidth * scale).roundToInt())
    val height = max(1, (cropHeight * scale).roundToInt())
    if (width == cropWidth && height == cropHeight) full.copyTo(current)
    else Imgproc.resize(full, current, Size(width.toDouble(), height.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
  }

  private fun track(region: DocumentQuad?, cropWidth: Int, cropHeight: Int, gapNs: Long, sensorNs: Long, fitTransform: Boolean): FlowMotionEstimate {
    fun unknown(status: FlowMotionStatus, confidence: Double = 0.0, tracks: Int = 0) =
      FlowMotionEstimate(status, null, bufferWidth, previousSensorNs, sensorNs, confidence, tracks)
    val width = previous.cols()
    val height = previous.rows()
    val bounds = selectRegion(region, width, height)
    Imgproc.goodFeaturesToTrack(previous, corners, maxFeatures, QUALITY_LEVEL, max(width, height) * MIN_DISTANCE_FRACTION, mask)
    val detected = corners.rows()
    if (detected < MIN_TRACKS) return unknown(FlowMotionStatus.TOO_FEW_FEATURES)
    corners.convertTo(previousPoints, CvType.CV_32FC2)
    Video.calcOpticalFlowPyrLK(previous, current, previousPoints, nextPoints, status, error, window, PYRAMID_LEVELS, criteria)
    Video.calcOpticalFlowPyrLK(current, previous, nextPoints, backPoints, backStatus, backError, window, PYRAMID_LEVELS, criteria)
    val from = previousPoints.toArray()
    val to = nextPoints.toArray()
    val back = backPoints.toArray()
    val forwardOk = status.toArray()
    val backwardOk = backStatus.toArray()
    // Tracker pixels back to source pixels per axis; rounding the downscaled size makes them differ slightly.
    val sx = cropWidth.toDouble() / width
    val sy = cropHeight.toDouble() / height
    val seconds = gapNs / 1e9
    val speeds = ArrayList<Double>(detected)
    val detectedIn = IntArray(COVERAGE_GRID * COVERAGE_GRID)
    val speedsIn = Array(COVERAGE_GRID * COVERAGE_GRID) { ArrayList<Double>() }
    val tracks = if (fitTransform) ArrayList<FlowTrack>(detected) else null
    for (i in from.indices) {
      val column = ((from[i].x - bounds[0]) * COVERAGE_GRID / (bounds[2] - bounds[0])).toInt().coerceIn(0, COVERAGE_GRID - 1)
      val row = ((from[i].y - bounds[1]) * COVERAGE_GRID / (bounds[3] - bounds[1])).toInt().coerceIn(0, COVERAGE_GRID - 1)
      val cell = row * COVERAGE_GRID + column
      detectedIn[cell]++
      if (forwardOk[i].toInt() == 0 || backwardOk[i].toInt() == 0) continue
      if (hypot(back[i].x - from[i].x, back[i].y - from[i].y) > MAX_FORWARD_BACKWARD_PX) continue
      val speed = hypot((to[i].x - from[i].x) * sx, (to[i].y - from[i].y) * sy) / seconds
      speeds.add(speed)
      speedsIn[cell].add(speed)
      if (tracks != null) {
        fun upright(x: Double, y: Double): Pair<Double, Double> = when (rotation) {
          90 -> cropHeight - y to x
          180 -> cropWidth - x to cropHeight - y
          270 -> y to cropWidth - x
          else -> x to y
        }
        val (x, y) = upright(from[i].x * sx, from[i].y * sy)
        val (u, v) = upright(to[i].x * sx, to[i].y * sy)
        tracks.add(FlowTrack(x, y, u, v))
      }
    }
    val confidence = speeds.size.toDouble() / detected
    if (speeds.size < MIN_TRACKS) return unknown(FlowMotionStatus.TRACKING_FAILED, confidence, speeds.size)
    if (speedsIn.count { it.isNotEmpty() } < MIN_COVERED_CELLS) {
      return unknown(FlowMotionStatus.POOR_COVERAGE, confidence, speeds.size)
    }
    val lost = detectedIn.indices.count {
      detectedIn[it] >= MIN_REGION_FEATURES && speedsIn[it].size < detectedIn[it] * MIN_REGION_SURVIVAL
    }
    // Magnitudes, not a mean vector: approaching the page moves points outwards in every direction and would cancel.
    speeds.sort()
    var speed = speeds[(ceil(SPEED_PERCENTILE * speeds.size).toInt() - 1).coerceIn(0, speeds.lastIndex)]
    for (region in speedsIn) {
      if (region.size < MIN_REGION_TRACKS) continue
      region.sort()
      val median = if (region.size % 2 == 1) region[region.size / 2]
        else (region[region.size / 2 - 1] + region[region.size / 2]) / 2
      speed = max(speed, median)
    }
    val fitStarted = if (fitTransform) System.nanoTime() else 0L
    val rotated = rotation % 180 != 0
    // Lost regions keep their surviving tracks out; the consensus fit decides whether the rest still agree.
    val transform = if (tracks == null) null else FlowTransformFitter.fit(tracks,
      if (rotated) cropHeight else cropWidth, if (rotated) cropWidth else cropHeight,
      (bounds[2] - bounds[0]) * sx * (bounds[3] - bounds[1]) * sy, 2 * max(sx, sy))
    return FlowMotionEstimate(
      FlowMotionStatus.MEASURED, speed, bufferWidth, previousSensorNs, sensorNs, confidence, speeds.size, lost,
      transform, if (fitTransform) System.nanoTime() - fitStarted else null,
    )
  }

  /** Fills [mask] with the page quad mapped into tracker pixels, or the central region when it is unusable, and returns
   * the region's bounds (left, top, right, bottom) for coverage.
   */
  private fun selectRegion(region: DocumentQuad?, width: Int, height: Int): DoubleArray {
    mask.create(height, width, CvType.CV_8UC1)
    mask.setTo(Scalar(0.0))
    val points = region?.let { quad ->
      val cx = (quad.topLeft.x + quad.topRight.x + quad.bottomRight.x + quad.bottomLeft.x) / 4
      val cy = (quad.topLeft.y + quad.topRight.y + quad.bottomRight.y + quad.bottomLeft.y) / 4
      listOf(quad.topLeft, quad.topRight, quad.bottomRight, quad.bottomLeft).map { p ->
        val x = cx + (p.x - cx) * .9
        val y = cy + (p.y - cy) * .9
        // Upright normalized coordinates back to the unrotated buffer; rotation turns the buffer clockwise upright.
        val (bx, by) = when (rotation) {
          90 -> y to 1 - x
          180 -> 1 - x to 1 - y
          270 -> 1 - y to x
          else -> x to y
        }
        Point(bx * width, by * height)
      }
    }
    val polygon = points?.let { MatOfPoint(*it.toTypedArray()) }
    try {
      if (polygon != null && Imgproc.contourArea(polygon) >= MIN_REGION_FRACTION * width * height) {
        Imgproc.fillConvexPoly(mask, polygon, Scalar(255.0))
        return doubleArrayOf(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })
      }
    } finally {
      polygon?.release()
    }
    // A supplied page that has left the image is unknown, not permission to track the background instead.
    if (region != null) return doubleArrayOf(0.0, 0.0, width.toDouble(), height.toDouble())
    val left = width * (1 - CENTRAL_FRACTION) / 2
    val top = height * (1 - CENTRAL_FRACTION) / 2
    Imgproc.rectangle(mask, Point(left, top), Point(width - left, height - top), Scalar(255.0), Core.FILLED)
    return doubleArrayOf(left, top, width - left, height - top)
  }

  override fun close() {
    if (closed) return
    closed = true
    hasPrevious = false
    row = ByteArray(0)
    luma = ByteArray(0)
    for (mat in listOf(full, previous, current, mask, corners, previousPoints, nextPoints, backPoints, status, backStatus,
      error, backError)) mat.release()
  }

  companion object {
    const val MAX_EDGE = 224
    const val MAX_FEATURES = 50
    /** Longer than a few dropped frames at 30 fps; a longer pause makes the reference frame meaningless. */
    const val MAX_GAP_NS = 200_000_000L
    const val MIN_TRACKS = 8
    const val SPEED_PERCENTILE = 0.9
    private const val QUALITY_LEVEL = 0.01
    private const val MIN_DISTANCE_FRACTION = 0.05
    private const val WINDOW_PX = 15
    private const val PYRAMID_LEVELS = 3
    private const val MAX_FORWARD_BACKWARD_PX = 1.0
    private const val COVERAGE_GRID = 3
    private const val MIN_COVERED_CELLS = 3
    /** A region's median speed counts once this many of its points track; one point alone is too noisy. */
    private const val MIN_REGION_TRACKS = 2
    /** A region with at least this many features that keeps under [MIN_REGION_SURVIVAL] of them is lost. Recorded
     * steady and moving A4 scenes kept every feature, so losses come from motion tracking cannot follow.
     */
    private const val MIN_REGION_FEATURES = 3
    private const val MIN_REGION_SURVIVAL = 0.5
    private const val MIN_REGION_FRACTION = 0.05
    private const val CENTRAL_FRACTION = 0.6
  }
}
