package com.margelo.nitro.documentscanner.processing

import org.junit.Assert.assertEquals
import org.junit.Test

class GateMotionTest {
  private val exposureNs = 25_000_000L

  /** A measured estimate whose blur over [exposureNs] is [blur] of a 320 px wide frame. */
  private fun flow(blur: Double, status: FlowMotionStatus = FlowMotionStatus.MEASURED) = FlowMotionEstimate(
    status, if (status == FlowMotionStatus.MEASURED) blur * 320 / (exposureNs * 1e-9) else null, 320, 0, 33_000_000,
    1.0, 50,
  )

  @Test
  fun flowBlurScalesWithExposure() {
    val estimate = flow(0.001)
    assertEquals(0.002, flowBlur(estimate, 2 * exposureNs)!!, 1e-12)
  }
}
