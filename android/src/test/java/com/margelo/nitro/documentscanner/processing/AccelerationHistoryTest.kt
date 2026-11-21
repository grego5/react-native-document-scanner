package com.margelo.nitro.documentscanner.processing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccelerationHistoryTest {
  private val ms = 1_000_000L

  @Test fun thePeakIsTheLargestMagnitudeInTheSpan() {
    val history = AccelerationHistory()
    for (t in 0..30) history.add(t * 10 * ms, .1, 0.0, 0.0)
    history.add(305 * ms, 0.0, 3.0, 4.0)
    history.add(400 * ms, .2, 0.0, 0.0)
    assertEquals(5.0, history.peak(100 * ms, 400 * ms)!!, 1e-9)
    assertEquals(.1, history.peak(0, 300 * ms)!!, 1e-9)
  }

  @Test fun aSpanTheSamplesDoNotReachHasNoPeak() {
    val history = AccelerationHistory()
    for (t in 10..20) history.add(t * 10 * ms, .1, 0.0, 0.0)
    assertNull(history.peak(0, 200 * ms))
    assertNull(history.peak(100 * ms, 400 * ms))
    assertNull(AccelerationHistory().peak(0, 100 * ms))
  }
}
