package com.margelo.nitro.documentscanner.processing

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.margelo.nitro.documentscanner.DocumentPoint
import com.margelo.nitro.documentscanner.DocumentQuad
import java.nio.ByteBuffer
import kotlin.random.Random
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/** Synthetic text-like pages moved between two frames by a known affine transform. */
@RunWith(AndroidJUnit4::class)
class FlowMotionEstimatorTest {
  private val estimator = FlowMotionEstimator()
  private val page by lazy { texturedPage() }

  @After
  fun release() {
    estimator.close()
    page.release()
  }

  @Test
  fun stillFramesMeasureNearZero() {
    val estimate = measure(frame(), frame())
    assertEquals(FlowMotionStatus.MEASURED, estimate.status)
    assertTrue("speed=${estimate.speedPxPerSecond}", estimate.speedPxPerSecond!! < 3.0)
    assertEquals(WIDTH, estimate.sourceWidth)
    assertTrue(estimate.validTracks >= FlowMotionEstimator.MIN_TRACKS)
  }

  @Test
  fun sidewaysMovementMeasuresSourcePixelsPerSecond() {
    val estimate = measure(frame(), frame(dx = 6.0))
    // 6 source pixels over one 30 fps interval, tracked at 224 px.
    assertEquals(180.0, estimate.speedPxPerSecond!!, 18.0)
    assertEquals(BASE_NS, estimate.fromSensorNs)
    assertEquals(BASE_NS + FRAME_NS, estimate.toSensorNs)
  }

  @Test
  fun croppedFramesStillReportSourcePixels() {
    val estimate = measure(frame(), frame(dx = 6.0), cropLeft = 80, cropRight = WIDTH - 80)
    assertEquals(180.0, estimate.speedPxPerSecond!!, 18.0)
    assertEquals(WIDTH, estimate.sourceWidth)
  }

  @Test
  fun rotationAndApproachRetreatAreMotion() {
    for ((label, moved) in listOf(
      "rotation" to frame(angleDegrees = 2.0), "approach" to frame(scale = 1.03), "retreat" to frame(scale = 1 / 1.03),
    )) {
      estimator.reset()
      val estimate = measure(frame(), moved)
      assertEquals(label, FlowMotionStatus.MEASURED, estimate.status)
      assertTrue("$label speed=${estimate.speedPxPerSecond}", estimate.speedPxPerSecond!! > 60.0)
    }
  }

  @Test
  fun oneRegionMovingWhileTheRestHoldsStillDecides() {
    // The top-right ninth of the tracked central region slides 6 px, like a page corner lifting; the rest holds.
    val estimate = measure(frame(), patched(frame(dx = 6.0)))
    assertEquals(FlowMotionStatus.MEASURED, estimate.status)
    assertTrue("speed=${estimate.speedPxPerSecond}", estimate.speedPxPerSecond!! > 120.0)
    assertEquals(0, estimate.lostRegions)
  }

  @Test
  fun aRegionThatLosesItsFeaturesIsCountedLost() {
    val estimate = measure(frame(), patched(blank()))
    assertEquals(FlowMotionStatus.MEASURED, estimate.status)
    assertTrue("lost=${estimate.lostRegions}", estimate.lostRegions >= 1)
    assertEquals(0, measure(frame(), frame()).lostRegions)
  }

  @Test
  fun doublingTheIntervalHalvesTheSpeed() {
    val still = frame()
    val moved = frame(dx = 6.0)
    val fast = measure(still, moved).speedPxPerSecond!!
    estimator.reset()
    val slow = measure(still, moved, intervalNs = 2 * FRAME_NS).speedPxPerSecond!!
    assertEquals(0.5, slow / fast, 1e-6)
  }

  @Test
  fun blankFramesAreUnknown() {
    val estimate = measure(blank(), blank())
    assertEquals(FlowMotionStatus.TOO_FEW_FEATURES, estimate.status)
    assertNull(estimate.speedPxPerSecond)
  }

  @Test
  fun failedTrackingIsUnknown() {
    val estimate = measure(frame(), blank())
    assertNotEquals(FlowMotionStatus.MEASURED, estimate.status)
    assertNull(estimate.speedPxPerSecond)
  }

  @Test
  fun resetsWithoutReference() {
    val first = frame()
    val second = frame(dx = 6.0)
    update(first, BASE_NS)
    assertEquals(FlowMotionStatus.NO_REFERENCE, update(second, BASE_NS).status) // Non-increasing timestamp.
    assertEquals(FlowMotionStatus.NO_REFERENCE,
      update(first, BASE_NS + FlowMotionEstimator.MAX_GAP_NS + 1).status) // Excessive gap.
    assertEquals(FlowMotionStatus.NO_REFERENCE,
      update(second, BASE_NS + FlowMotionEstimator.MAX_GAP_NS + 1 + FRAME_NS, rotation = 90).status) // Geometry.
    assertEquals(FlowMotionStatus.MEASURED,
      update(first, BASE_NS + FlowMotionEstimator.MAX_GAP_NS + 1 + 2 * FRAME_NS, rotation = 90).status)
  }

  @Test
  fun pageQuadSelectsFeaturesInRotatedFrames() {
    val quad = DocumentQuad(DocumentPoint(0.1, 0.1), DocumentPoint(0.9, 0.1), DocumentPoint(0.9, 0.9), DocumentPoint(0.1, 0.9))
    update(frame(), BASE_NS, rotation = 90, region = quad)
    val estimate = update(frame(dx = 6.0), BASE_NS + FRAME_NS, rotation = 90, region = quad)
    assertEquals(FlowMotionStatus.MEASURED, estimate.status)
    assertEquals(180.0, estimate.speedPxPerSecond!!, 18.0)
  }

  @Test
  fun optionalTransformUsesTheExistingTracksAndHonorsRotation() {
    val quad = DocumentQuad(DocumentPoint(.2, .2), DocumentPoint(.8, .2), DocumentPoint(.8, .8), DocumentPoint(.2, .8))
    for (rotation in listOf(0, 90, 180, 270)) {
      estimator.reset()
      update(frame(), BASE_NS, rotation = rotation, fitTransform = true)
      val estimate = update(frame(dx = 6.0), BASE_NS + FRAME_NS, rotation = rotation, fitTransform = true)
      assertEquals(FlowMotionStatus.MEASURED, estimate.status)
      val moved = estimate.transform!!.warp(quad)!!
      val expectedX = when (rotation) { 0 -> 6.0 / WIDTH; 180 -> -6.0 / WIDTH; else -> 0.0 }
      val expectedY = when (rotation) { 90 -> 6.0 / WIDTH; 270 -> -6.0 / WIDTH; else -> 0.0 }
      assertEquals(quad.topLeft.x + expectedX, moved.topLeft.x, .005)
      assertEquals(quad.topLeft.y + expectedY, moved.topLeft.y, .005)
      assertTrue(estimate.transformProcessingNs!! > 0)
    }
    estimator.reset()
    val without = measure(frame(), frame(dx = 6.0))
    assertNull(without.transform)
    assertNull(without.transformProcessingNs)
  }

  private fun measure(
    first: ByteBuffer, second: ByteBuffer, intervalNs: Long = FRAME_NS, cropLeft: Int = 0, cropRight: Int = WIDTH,
  ): FlowMotionEstimate {
    update(first, BASE_NS, cropLeft = cropLeft, cropRight = cropRight)
    return update(second, BASE_NS + intervalNs, cropLeft = cropLeft, cropRight = cropRight)
  }

  private fun update(
    pixels: ByteBuffer, sensorNs: Long, rotation: Int = 0, region: DocumentQuad? = null, cropLeft: Int = 0,
    cropRight: Int = WIDTH, fitTransform: Boolean = false,
  ) = estimator.update(pixels, WIDTH * 4, 4, WIDTH, HEIGHT, cropLeft, 0, cropRight, HEIGHT, rotation, sensorNs, region, fitTransform)

  /** The page moved by [dx] frame pixels, turned [angleDegrees] and scaled by [scale] about the frame centre. */
  private fun frame(dx: Double = 0.0, angleDegrees: Double = 0.0, scale: Double = 1.0): ByteBuffer =
    rgba(frameGrey(dx, angleDegrees, scale))

  /** The still page with the top-right ninth of the tracked central region taken from [patch]. */
  private fun patched(patch: ByteBuffer): ByteBuffer {
    val still = frameGrey()
    val source = Mat(HEIGHT, WIDTH, CvType.CV_8UC4)
    val bytes = ByteArray(WIDTH * HEIGHT * 4)
    patch.get(bytes)
    source.put(0, 0, bytes)
    val grey = Mat()
    Imgproc.cvtColor(source, grey, Imgproc.COLOR_RGBA2GRAY)
    val corner = Rect(390, 0, WIDTH - 390, 190)
    grey.submat(corner).copyTo(still.submat(corner))
    source.release()
    grey.release()
    return rgba(still)
  }

  private fun frameGrey(dx: Double = 0.0, angleDegrees: Double = 0.0, scale: Double = 1.0): Mat {
    val transform = Imgproc.getRotationMatrix2D(Point(page.cols() / 2.0, page.rows() / 2.0), angleDegrees, scale)
    transform.put(0, 2, transform.get(0, 2)[0] + (WIDTH - page.cols()) / 2.0 + dx)
    transform.put(1, 2, transform.get(1, 2)[0] + (HEIGHT - page.rows()) / 2.0)
    val grey = Mat()
    Imgproc.warpAffine(page, grey, transform, Size(WIDTH.toDouble(), HEIGHT.toDouble()), Imgproc.INTER_LINEAR)
    transform.release()
    return grey
  }

  private fun blank(): ByteBuffer = rgba(Mat(HEIGHT, WIDTH, CvType.CV_8UC1, Scalar(200.0)))

  private fun rgba(grey: Mat): ByteBuffer {
    val rgba = Mat()
    Imgproc.cvtColor(grey, rgba, Imgproc.COLOR_GRAY2RGBA)
    val bytes = ByteArray(WIDTH * HEIGHT * 4)
    rgba.get(0, 0, bytes)
    grey.release()
    rgba.release()
    return ByteBuffer.allocateDirect(bytes.size).put(bytes).apply { rewind() }
  }

  /** Dark word-like bars in lines on a light page, larger than the frame so transforms never reveal its edge. */
  private fun texturedPage(): Mat {
    OpenCvRuntime.ensureLoaded()
    val random = Random(7)
    val page = Mat(HEIGHT * 2, WIDTH * 2, CvType.CV_8UC1, Scalar(225.0))
    var y = 12
    while (y < page.rows() - 20) {
      var x = 10
      while (x < page.cols() - 40) {
        val word = 12 + random.nextInt(48)
        val height = 6 + random.nextInt(8)
        Imgproc.rectangle(page, Point(x.toDouble(), y.toDouble()), Point((x + word).toDouble(), (y + height).toDouble()),
          Scalar(30.0 + random.nextInt(60)), Core.FILLED)
        x += word + 6 + random.nextInt(10)
      }
      y += 22 + random.nextInt(6)
    }
    return page
  }

  private companion object {
    const val WIDTH = 640
    const val HEIGHT = 480
    const val BASE_NS = 1_000_000_000L
    const val FRAME_NS = 33_333_333L
  }
}
