package com.margelo.nitro.documentscanner.processing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GateMotionTest {
  private val limit = 0.002 // The default 0.2% capture limit.
  private val exposureNs = 25_000_000L
  private val quietGyro = 0.0005

  /** A measured estimate whose blur over [exposureNs] is [blur] of a 320 px wide frame. */
  private fun flow(blur: Double, status: FlowMotionStatus = FlowMotionStatus.MEASURED) = FlowMotionEstimate(
    status, if (status == FlowMotionStatus.MEASURED) blur * 320 / (exposureNs * 1e-9) else null, 320, 0, 33_000_000,
    1.0, 50,
  )

  @Test
  fun translationAndApproachHoldCaptureDespiteAQuietGyro() {
    // Recorded moving captures measured 0.31-1.47% flow blur while the stabilizer kept the image's rotation small.
    for (blur in listOf(0.0031, 0.0097)) {
      val motion = gateMotion(flow(blur), exposureNs, quietGyro)!!
      assertEquals(MotionSource.FLOW, motion.source)
      assertEquals(blur, motion.blur, 1e-12)
      assertTrue(motion.blur > limit)
    }
  }

  @Test
  fun steadyFlowReleasesCaptureDespiteANoisyGyro() {
    val motion = gateMotion(flow(0.0006), exposureNs, 0.0187)!!
    assertEquals(MotionSource.FLOW, motion.source)
    assertTrue(motion.blur <= limit)
  }

  @Test
  fun unknownFlowFallsBackToTheGyro() {
    for (estimate in listOf(
      flow(0.0, FlowMotionStatus.TOO_FEW_FEATURES), flow(0.0, FlowMotionStatus.NO_REFERENCE), null,
    )) {
      assertEquals(GateMotion(0.0097, MotionSource.GYRO), gateMotion(estimate, exposureNs, 0.0097))
    }
  }

  @Test
  fun aLostRegionHoldsCaptureEvenWhenTheRestIsSteady() {
    val waving = flow(0.0006).copy(lostRegions = 1)
    val motion = gateMotion(waving, exposureNs, quietGyro)!!
    assertEquals(MotionSource.FLOW, motion.source)
    assertTrue(motion.blur <= limit)
    assertEquals(1, motion.lostRegions)
  }

  @Test
  fun unknownFlowWithoutAGyroLeavesNoEstimate() {
    assertNull(gateMotion(flow(0.0, FlowMotionStatus.TRACKING_FAILED), exposureNs, null))
  }

  @Test
  fun flowBlurScalesWithExposure() {
    val estimate = flow(0.001)
    assertEquals(0.002, flowBlur(estimate, 2 * exposureNs)!!, 1e-12)
  }
}
