package com.margelo.nitro.documentscanner.processing

import com.margelo.nitro.documentscanner.DocumentPoint
import com.margelo.nitro.documentscanner.DocumentCorners
import org.junit.Assert.*
import org.junit.Test
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.tan

class LiveDocumentMotionTest {
  // Samsung SM-G998U rear camera, portrait: 640 x 480 analysis buffer of the 4000 x 3000 active array.
  private val optics = LiveCameraOptics(2791.6665, 4000, 3000, 90)
  private val portrait = LiveFrameGeometry.of(optics, 640, 480, 0, 0, 640, 480, 90)
  private val corners = DocumentCorners(DocumentPoint(.3, .3), DocumentPoint(.7, .3), DocumentPoint(.7, .7), DocumentPoint(.3, .7))

  @Test
  fun integratesHeldRatesAndInvertsReversedSpans() {
    val gyro = GyroHistory()
    for (i in 0..20) gyro.add(i * 5_000_000L, 0.0, 0.0, 2.0)
    val r = gyro.rotation(10_000_000, 60_000_000)!!
    assertEquals(0.1, acos(r[0]), 1e-9)
    assertEquals(0.1, kotlin.math.asin(r[3]), 1e-9)
    assertArrayEquals(doubleArrayOf(r[0], r[3], r[6], r[1], r[4], r[7], r[2], r[5], r[8]), gyro.rotation(60_000_000, 10_000_000)!!, 1e-12)
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
    // A small corners at the principal point, where the shift is exactly f * tan(angle).
    val corners = DocumentCorners(DocumentPoint(.499, .499), DocumentPoint(.501, .499), DocumentPoint(.501, .501), DocumentPoint(.499, .501))
    val moved = GyroDocumentMotion(gyro, portrait).warp(corners, 0, 100_000_000)!!
    val center = { q: DocumentCorners -> (q.topLeft.y + q.bottomRight.y) / 2 * portrait.height }
    assertEquals(portrait.focalPx * tan(angle), center(moved) - center(corners), 0.05)
    assertEquals(corners.topLeft.x, moved.topLeft.x, 1e-4)

    val landscape = LiveFrameGeometry.of(optics, 640, 480, 0, 0, 640, 480, 0)
    val turned = GyroDocumentMotion(gyro, landscape).warp(corners, 0, 100_000_000)!!
    val dx = (turned.topLeft.x + turned.bottomRight.x - corners.topLeft.x - corners.bottomRight.x) / 2 * landscape.width
    val dy = (turned.topLeft.y + turned.bottomRight.y - corners.topLeft.y - corners.bottomRight.y) / 2 * landscape.height
    assertEquals(landscape.focalPx * tan(angle), abs(dx), 0.05)
    assertEquals(0.0, dy, 0.05)
  }

  @Test
  fun recordedMotionEvidenceMatchesPreMigrationMath() {
    val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "live-guide-traces")
    val files = directory.listFiles()?.filter { it.extension == "tsv" }.orEmpty()
    assumeTrue("Recorded pixel-free motion traces are optional device fixtures", files.isNotEmpty())
    var flowChecks = 0
    var gyroChecks = 0
    var rotationChecks = 0
    for (file in files) {
      val lines = file.readLines()
      val camera = lines.first { it.startsWith("# camera ") }.removePrefix("# camera ")
        .split(' ').associate { it.substringBefore('=') to it.substringAfter('=') }
      assertEquals("REALTIME", camera.getValue("timestampSource"))
      val optics = LiveCameraOptics(camera.getValue("focalLengthPx").toDouble(), camera.getValue("activeWidth").toInt(),
        camera.getValue("activeHeight").toInt(), camera.getValue("sensorOrientation").toInt())
      val data = lines.filterNot { it.startsWith('#') }
      val columns = data.first().split('\t')
      val rows = data.drop(1).map { columns.zip(it.split('\t')).toMap() }
      val samples = rows.filter { it.getValue("kind") == "gyro" }
      val shared = GyroHistory(samples.size)
      val reference = ReferenceGyroHistory(samples.size)
      for (sample in samples) {
        val rates = sample.getValue("rates").split(',').map(String::toDouble)
        val timestamp = sample.getValue("sensorNs").toLong()
        shared.add(timestamp, rates[0], rates[1], rates[2]); reference.add(timestamp, rates[0], rates[1], rates[2])
      }
      var previous: Long? = null
      for (row in rows.filter { it.getValue("kind") == "frame" }) {
        val sensor = row.getValue("sensorNs").toLong()
        val exposure = row.getValue("exposureNs").toLongOrNull() ?: continue
        val skew = row.getValue("skewNs").toLongOrNull() ?: continue
        val from = sensor - GYRO_MARGIN_NS
        val to = sensor + skew + exposure + GYRO_MARGIN_NS
        val oldRate = reference.peakRate(from, to)
        val expected = oldRate?.let { it * exposure * 1e-9 * optics.focalLengthPx / optics.activeWidth }
        val actual = motionBlur(shared, optics, from, to, exposure)
        if (expected == null) assertNull(actual) else { assertEquals(expected, actual!!, 1e-12); gyroChecks++ }
        previous?.let { start ->
          val oldRotation = reference.rotation(start, sensor)
          val newRotation = shared.rotation(start, sensor)
          if (oldRotation == null) assertNull(newRotation) else { assertArrayEquals(oldRotation, newRotation!!, 1e-12); rotationChecks++ }
        }
        previous = sensor
        val speed = row.getValue("flowSpeed").toDoubleOrNull()
        val width = row.getValue("flowSourceWidth").toIntOrNull()
        if (speed != null && width != null) {
          val estimate = FlowMotionEstimate(FlowMotionStatus.MEASURED, speed, width,
            row.getValue("flowFromNs").toLong(), row.getValue("flowToNs").toLong(), 1.0, 0)
          assertEquals(speed * exposure * 1e-9 / width, flowBlur(estimate, exposure)!!, 1e-12)
          flowChecks++
        }
      }
    }
    assertTrue(flowChecks > 0 && gyroChecks > 0 && rotationChecks > 0)
    println("Recorded motion parity: files=${files.size} flow=$flowChecks gyro=$gyroChecks rotation=$rotationChecks")
  }

  @Test
  fun noRotationKeepsTheCornersAndUnknownMotionReturnsNull() {
    val still = GyroHistory().apply { add(0, 0.0, 0.0, 0.0); add(10_000_000, 0.0, 0.0, 0.0) }
    val kept = GyroDocumentMotion(still, portrait).warp(corners, 0, 10_000_000)!!
    assertEquals(corners.topLeft.x, kept.topLeft.x, 1e-12)
    assertEquals(corners.bottomRight.y, kept.bottomRight.y, 1e-12)
    assertNull(GyroDocumentMotion(GyroHistory(), portrait).warp(corners, 0, 10_000_000))
    assertNull(LiveDocumentMotion.NONE.warp(corners, 0, 10))
  }
}
