package com.margelo.nitro.documentscanner.processing

import com.margelo.nitro.documentscanner.DocumentPoint
import com.margelo.nitro.documentscanner.DocumentQuad
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.tan

class LiveQuadMotionTest {
  // Samsung SM-G998U rear camera, portrait: 640 x 480 analysis buffer of the 4000 x 3000 active array.
  private val optics = LiveCameraOptics(2791.6665, 4000, 3000, 90)
  private val portrait = LiveFrameGeometry.of(optics, 640, 480, 0, 0, 640, 480, 90)
  private val quad = DocumentQuad(DocumentPoint(.3, .3), DocumentPoint(.7, .3), DocumentPoint(.7, .7), DocumentPoint(.3, .7))

  @Test
  fun integratesHeldRatesAndInvertsReversedSpans() {
    val gyro = GyroHistory()
    for (i in 0..20) gyro.add(i * 5_000_000L, 0.0, 0.0, 2.0)
    val r = gyro.rotation(10_000_000, 60_000_000)!!
    assertEquals(0.1, acos(r[0]), 1e-9)
    assertEquals(0.1, kotlin.math.asin(r[3]), 1e-9)
    assertArrayEquals(GyroHistory.transpose(r), gyro.rotation(60_000_000, 10_000_000)!!, 1e-12)
    // The newest rate holds for 50 ms; before the oldest sample and beyond that there is no answer.
    assertNotNull(gyro.rotation(90_000_000, 150_000_000))
    assertNull(gyro.rotation(90_000_000, 150_000_001))
    assertNull(gyro.rotation(-1, 10_000_000))
    gyro.clear()
    assertNull(gyro.rotation(0, 0))
  }

  @Test
  fun peakRateCoversOnlySamplesInsideTheWindow() {
    val gyro = GyroHistory().apply {
      add(0, 3.0, 0.0, 0.0)
      add(10_000_000, 0.0, 0.3, 0.4)
      add(20_000_000, 0.1, 0.0, 0.0)
      add(30_000_000, 0.0, 0.0, 9.0)
    }
    assertEquals(0.5, gyro.peakRate(5_000_000, 25_000_000)!!, 1e-12)
    assertEquals(9.0, gyro.peakRate(0, 30_000_000)!!, 1e-12)
    assertNull(gyro.peakRate(31_000_000, 40_000_000))
  }

  @Test
  fun motionBlurIsPeakRateTimesExposureInFrameWidths() {
    val optics = LiveCameraOptics(3000.0, 4000, 3000, 90)
    val gyro = GyroHistory().apply { add(0, 0.0, 0.6, 0.8); add(5_000_000, 0.1, 0.0, 0.0) }
    // 1 rad/s for 10 ms is 0.01 rad, or 30 px of a 3000 px focal length across a 4000 px wide frame.
    assertEquals(0.0075, motionBlur(gyro, optics, 0, 5_000_000, 10_000_000)!!, 1e-12)
    assertNull(motionBlur(gyro, optics, 6_000_000, 9_000_000, 10_000_000))
  }

  @Test
  fun frameHistoryMatchesOnlyExactSensorTimestamps() {
    val frames = CameraFrameHistory(capacity = 2)
    assertNull(frames.at(0))
    val first = CameraFrameState(10, 1, false)
    val second = CameraFrameState(20, 2, true)
    val third = CameraFrameState(30, 3, false)
    frames.add(100, first)
    frames.add(200, second)
    assertEquals(first, frames.at(100))
    assertNull(frames.at(300))
    frames.add(300, third)
    assertNull(frames.at(100)) // Evicted.
    assertEquals(second, frames.at(200))
    assertEquals(third, frames.at(300))
    frames.clear()
    assertNull(frames.at(300))
  }

  @Test
  fun frameGeometryScalesTheActiveArrayAndRotatesTheBufferCenterUpright() {
    assertEquals(LiveFrameGeometry(480, 640, 2791.6665 * 0.16, 240.0, 320.0, 0), portrait)
    // A viewport crop moves the principal point; a landscape display turns the axes.
    val cropped = LiveFrameGeometry.of(optics, 640, 480, 80, 0, 560, 480, 90)
    assertEquals(480, cropped.width)
    assertEquals(480, cropped.height)
    assertEquals(240.0, cropped.centerX, 0.0)
    assertEquals(240.0, cropped.centerY, 0.0)
    assertEquals(270, LiveFrameGeometry.of(optics, 640, 480, 0, 0, 640, 480, 0).axisRotationDegrees)
  }

  @Test
  fun pitchMovesTheImageVerticallyByFocalTangentAndRotatedFramesTurnIt() {
    val angle = 0.05
    val gyro = GyroHistory().apply { add(0, angle * 10, 0.0, 0.0); add(100_000_000, angle * 10, 0.0, 0.0) }
    // A small quad at the principal point, where the shift is exactly f * tan(angle).
    val quad = DocumentQuad(DocumentPoint(.499, .499), DocumentPoint(.501, .499), DocumentPoint(.501, .501), DocumentPoint(.499, .501))
    val moved = GyroQuadMotion(gyro, portrait).warp(quad, 0, 100_000_000)!!
    val center = { q: DocumentQuad -> (q.topLeft.y + q.bottomRight.y) / 2 * portrait.height }
    assertEquals(portrait.focalPx * tan(angle), center(moved) - center(quad), 0.05)
    assertEquals(quad.topLeft.x, moved.topLeft.x, 1e-4)

    val landscape = LiveFrameGeometry.of(optics, 640, 480, 0, 0, 640, 480, 0)
    val turned = GyroQuadMotion(gyro, landscape).warp(quad, 0, 100_000_000)!!
    val dx = (turned.topLeft.x + turned.bottomRight.x - quad.topLeft.x - quad.bottomRight.x) / 2 * landscape.width
    val dy = (turned.topLeft.y + turned.bottomRight.y - quad.topLeft.y - quad.bottomRight.y) / 2 * landscape.height
    assertEquals(landscape.focalPx * tan(angle), abs(dx), 0.05)
    assertEquals(0.0, dy, 0.05)
  }

  @Test
  fun noRotationKeepsTheQuadAndUnknownMotionReturnsNull() {
    val still = GyroHistory().apply { add(0, 0.0, 0.0, 0.0); add(10_000_000, 0.0, 0.0, 0.0) }
    val kept = GyroQuadMotion(still, portrait).warp(quad, 0, 10_000_000)!!
    assertEquals(quad.topLeft.x, kept.topLeft.x, 1e-12)
    assertEquals(quad.bottomRight.y, kept.bottomRight.y, 1e-12)
    assertNull(GyroQuadMotion(GyroHistory(), portrait).warp(quad, 0, 10_000_000))
    assertNull(LiveQuadMotion.NONE.warp(quad, 0, 10))
  }
}
