package com.margelo.nitro.documentscanner

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LensArrivalTest {
  @Test
  fun aFrameOfThePreviousRequestDoesNotArriveEvenWhenItsReadingIsClose() {
    val arrival = LensArrival(7f)
    // Still under the 7.00 request, reading within tolerance of 7.05.
    assertFalse(arrival.arrived(7.05f, 7f, 7.02f, lensStateReported = true))
    assertTrue(arrival.arrived(7.05f, 7.05f, 7.02f, lensStateReported = true))
  }

  @Test
  fun aReportedStillLensArrivesOnItsFirstFrameThere() {
    val arrival = LensArrival(7f)
    assertFalse(arrival.arrived(7.25f, 7.25f, 7.1f, lensStateReported = true))
    assertTrue(arrival.arrived(7.25f, 7.25f, 7.24f, lensStateReported = true))
  }

  @Test
  fun withoutLensStateAReadingThereMustRepeat() {
    val arrival = LensArrival(7f)
    // Travelling through the tolerance on the way: the next reading differs, so neither frame counts.
    assertFalse(arrival.arrived(7.25f, 7.25f, 7.18f, lensStateReported = false))
    assertFalse(arrival.arrived(7.25f, 7.25f, 7.24f, lensStateReported = false))
    assertTrue(arrival.arrived(7.25f, 7.25f, 7.24f, lensStateReported = false))
  }

  @Test
  fun withNeitherLensStateNorAReadingFramesUnderTheMoveMustPass() {
    val arrival = LensArrival()
    repeat(LensArrival.UNCONFIRMED_FRAMES) { assertFalse(arrival.arrived(7.25f, 7.25f, null, lensStateReported = false)) }
    assertTrue(arrival.arrived(7.25f, 7.25f, null, lensStateReported = false))
    // A new target starts the count again.
    assertFalse(arrival.arrived(7.5f, 7.5f, null, lensStateReported = false))
  }

  @Test
  fun aReportedStillLensWithoutAReadingArrivesAtOnce() {
    assertTrue(LensArrival().arrived(7.25f, 7.25f, null, lensStateReported = true))
  }

  @Test
  fun aLensWhoseReadingNeverMatchesArrivesOnceItsReadingChangesAndHolds() {
    val arrival = LensArrival(7f)
    assertFalse(arrival.arrived(7.25f, 7.25f, 7f, lensStateReported = true))
    assertFalse(arrival.arrived(7.25f, 7.25f, 7.4f, lensStateReported = true))
    assertTrue(arrival.arrived(7.25f, 7.25f, 7.4f, lensStateReported = true))
  }

  @Test
  fun aFrameWithoutItsRequestCountsAsTheTargetsMove() {
    assertTrue(LensArrival(7f).arrived(7.25f, null, 7.25f, lensStateReported = true))
  }
}
