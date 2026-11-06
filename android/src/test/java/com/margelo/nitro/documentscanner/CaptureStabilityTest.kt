package com.margelo.nitro.documentscanner

import com.margelo.nitro.documentscanner.processing.CameraFrameState
import com.margelo.nitro.documentscanner.processing.FlowMotionEstimate
import com.margelo.nitro.documentscanner.processing.FlowMotionStatus
import com.margelo.nitro.documentscanner.processing.FrameRecord
import com.margelo.nitro.documentscanner.processing.GateMotion
import com.margelo.nitro.documentscanner.processing.MotionSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureStabilityTest {
  private val page = rect(.2, .2, .8, .8)

  @Test
  fun fourConsecutiveSteadyFramesAreStable() {
    val stability = CaptureStability()
    for (i in 1..3) assertFalse(stability.observe(frame(i), requireDocument = true, BLUR_LIMIT))
    assertTrue(stability.observe(frame(4), requireDocument = true, BLUR_LIMIT))
    assertEquals(4L * FRAME_NS, stability.latestSensorNs)
    assertEquals(FRAME_NS, stability.firstSensorNs)
  }

  @Test
  fun movementRestartsTheRunWhateverTheExposure() {
    val stability = CaptureStability()
    for (i in 1..3) stability.observe(frame(i), requireDocument = true, BLUR_LIMIT)
    // 6% of the width per second: little smear at a short exposure, but the framing is moving.
    assertFalse(stability.observe(frame(4, speedFraction = .06), requireDocument = true, BLUR_LIMIT))
    assertEquals(0, stability.count)
    assertEquals(StabilityBreak.MOVING, stability.lastBreak)
  }

  @Test
  fun smearIsJudgedApartFromFramingAndUnknownSmearNeverCounts() {
    for (smeared in listOf(frame(2, blur = .003), frame(2, blur = null))) {
      val stability = CaptureStability()
      stability.observe(frame(1), requireDocument = true, BLUR_LIMIT)
      assertFalse(stability.observe(smeared, requireDocument = true, BLUR_LIMIT))
      assertEquals(StabilityBreak.SMEARED, stability.lastBreak)
    }
    // Without a blur limit, smear is not evaluated.
    val unlimited = CaptureStability()
    for (i in 1..4) unlimited.observe(frame(i, blur = null), requireDocument = true, 0.0)
    assertTrue(unlimited.stable)
  }

  @Test
  fun aFrameWithoutItsResultKeepsTheRunButDoesNotCount() {
    val stability = CaptureStability()
    for (i in 1..3) stability.observe(frame(i), requireDocument = true, BLUR_LIMIT)
    assertFalse(stability.observe(frame(4, blur = null, result = false), requireDocument = true, BLUR_LIMIT))
    assertEquals(3, stability.count)
    // The next frame's flow starts at the uncounted one and completes the run, which ends at a counted frame.
    assertTrue(stability.observe(frame(5), requireDocument = true, BLUR_LIMIT))
    assertEquals(5L * FRAME_NS, stability.latestSensorNs)
  }

  @Test
  fun aFrameWithoutItsResultStillChecksSpeedAndThePage() {
    for (bad in listOf(frame(2, speedFraction = .06, result = false), frame(2, guide = null, result = false))) {
      val stability = CaptureStability()
      stability.observe(frame(1), requireDocument = true, BLUR_LIMIT)
      stability.observe(bad, requireDocument = true, BLUR_LIMIT)
      assertEquals(0, stability.count)
    }
  }

  @Test
  fun brokenOrMissingFlowRestarts() {
    val broken = listOf(frame(2, flowLost = 1), frame(2, status = FlowMotionStatus.TRACKING_FAILED),
      frame(2, status = FlowMotionStatus.NO_REFERENCE), frame(2).copy(flow = null))
    for (next in broken) {
      val stability = CaptureStability()
      stability.observe(frame(1), requireDocument = true, BLUR_LIMIT)
      assertFalse(stability.observe(next, requireDocument = true, BLUR_LIMIT))
      assertEquals(0, stability.count)
      assertEquals(StabilityBreak.TRACKING_LOST, stability.lastBreak)
    }
  }

  @Test
  fun aSkippedFrameCannotBridgeTheRunButStartsANewOne() {
    val stability = CaptureStability()
    for (i in 1..3) stability.observe(frame(i), requireDocument = true, BLUR_LIMIT)
    assertFalse(stability.observe(frame(5), requireDocument = true, BLUR_LIMIT))
    assertEquals(1, stability.count)
    assertEquals(StabilityBreak.TRACKING_LOST, stability.lastBreak)
    for (i in 6..7) assertFalse(stability.observe(frame(i), requireDocument = true, BLUR_LIMIT))
    assertTrue(stability.observe(frame(8), requireDocument = true, BLUR_LIMIT))
  }

  @Test
  fun anInterruptionNeedsAWholeNewRun() {
    val stability = CaptureStability()
    for (i in 1..4) stability.observe(frame(i), requireDocument = true, BLUR_LIMIT)
    stability.interrupt()
    assertEquals(StabilityBreak.TRACKING_LOST, stability.lastBreak)
    for (i in 5..7) assertFalse(stability.observe(frame(i), requireDocument = true, BLUR_LIMIT))
    assertTrue(stability.observe(frame(8), requireDocument = true, BLUR_LIMIT))
  }

  @Test
  fun texturelessFlowFallsBackToTheGyroscope() {
    val stability = CaptureStability()
    val blank = { i: Int, gyro: Double? -> frame(i, status = FlowMotionStatus.TOO_FEW_FEATURES, gyroSpeed = gyro) }
    stability.observe(blank(1, .01), requireDocument = true, BLUR_LIMIT)
    assertEquals(1, stability.count)
    stability.observe(blank(2, .2), requireDocument = true, BLUR_LIMIT)
    assertEquals(StabilityBreak.MOVING, stability.lastBreak)
    stability.observe(blank(3, null), requireDocument = true, BLUR_LIMIT)
    assertEquals(StabilityBreak.TRACKING_LOST, stability.lastBreak)
  }

  @Test
  fun aRequiredPageMustStayFound() {
    val stability = CaptureStability()
    stability.observe(frame(1), requireDocument = true, BLUR_LIMIT)
    assertFalse(stability.observe(frame(2, guide = null), requireDocument = true, BLUR_LIMIT))
    assertEquals(StabilityBreak.NO_DOCUMENT, stability.lastBreak)
    for (i in 3..6) stability.observe(frame(i, guide = null), requireDocument = false, BLUR_LIMIT)
    assertTrue(stability.stable)
  }

  @Test
  fun slowDriftOfThePageStartsANewRunFromThatFrame() {
    val stability = CaptureStability()
    stability.observe(frame(1), requireDocument = true, BLUR_LIMIT)
    stability.observe(frame(2, guide = rect(.21, .2, .81, .8)), requireDocument = true, BLUR_LIMIT)
    stability.observe(frame(3, guide = rect(.23, .2, .83, .8)), requireDocument = true, BLUR_LIMIT)
    assertEquals(StabilityBreak.DOCUMENT_MOVED, stability.lastBreak)
    assertEquals(1, stability.count)
  }

  @Test
  fun driftJudgesPositionScaleAndShapeSeparately() {
    assertNull(CaptureStability.drift(page, rect(.205, .205, .805, .805)))
    assertEquals("position", CaptureStability.drift(page, rect(.23, .2, .83, .8)))
    assertEquals("scale", CaptureStability.drift(page, rect(.18, .18, .82, .82)))
    val keystoned = DocumentQuad(DocumentPoint(.23, .2), DocumentPoint(.77, .2), DocumentPoint(.8, .8), DocumentPoint(.2, .8))
    assertEquals("shape", CaptureStability.drift(page, keystoned))
  }

  private fun frame(
    index: Int, speedFraction: Double = .01, status: FlowMotionStatus = FlowMotionStatus.MEASURED, flowLost: Int = 0,
    blur: Double? = .001, gyroSpeed: Double? = null, guide: DocumentQuad? = page, result: Boolean = true,
  ) = FrameRecord(
    index.toLong(), index * FRAME_NS, index * FRAME_NS, index * FRAME_NS,
    CameraFrameState(30_000_000, 0, false).takeIf { result },
    FlowMotionEstimate(status, if (status == FlowMotionStatus.MEASURED) speedFraction * WIDTH else null, WIDTH,
      (index - 1) * FRAME_NS, index * FRAME_NS, 1.0, 40, flowLost),
    blur?.let { GateMotion(it, if (status == FlowMotionStatus.MEASURED) MotionSource.FLOW else MotionSource.GYRO, flowLost) },
    gyroSpeed, guide,
  )

  private fun rect(l: Double, t: Double, r: Double, b: Double) = DocumentQuad(
    DocumentPoint(l, t), DocumentPoint(r, t), DocumentPoint(r, b), DocumentPoint(l, b))

  private companion object {
    const val WIDTH = 320
    const val FRAME_NS = 33_000_000L
    const val BLUR_LIMIT = .002
  }
}
