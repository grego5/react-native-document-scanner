package com.margelo.nitro.documentscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScannerPressTest {
  private fun ScannerPress.at(nowMs: Long, accepted: Boolean = false, gaveUp: Boolean = false,
    finalScan: OwnScan = OwnScan.NONE, lensFree: Boolean = true) = next(nowMs, accepted, gaveUp, finalScan, lensFree)

  @Test
  fun anAcceptedLockResolvesAtOnceAndWaitsForAFreshFrame() {
    val press = ScannerPress(0)
    assertEquals(PressFocus.ACCEPTED, press.at(100, accepted = true))
    assertEquals(8_000L, press.holdsUntilMs())
    assertEquals(PressFocus.ACCEPTED, press.at(7_900, accepted = true))
    // Accepted just before the deadline still leaves time for a frame after the lock.
    val late = ScannerPress(0)
    late.at(7_900, accepted = true)
    assertEquals(8_400L, late.holdsUntilMs())
  }

  @Test
  fun aCycleThatGivesUpStartsOneFinalScan() {
    val press = ScannerPress(0)
    assertEquals(PressFocus.WORKING, press.at(1_000))
    assertEquals(PressFocus.START_FINAL_SCAN, press.at(5_000, gaveUp = true))
    assertEquals(PressFocus.WORKING, press.at(5_100, gaveUp = true, finalScan = OwnScan.SCANNING))
    assertEquals(PressFocus.ACCEPTED, press.at(6_000, accepted = true))
    val failed = ScannerPress(0)
    failed.at(5_000, gaveUp = true)
    assertEquals(PressFocus.EXHAUSTED, failed.at(6_000, gaveUp = true, finalScan = OwnScan.FAILED))
    assertNull(failed.holdsUntilMs())
    // Exhausted is final: a later lock does not revive the press.
    assertEquals(PressFocus.EXHAUSTED, failed.at(6_100, accepted = true))
  }

  @Test
  fun aCycleStillWaitingIsOvertakenWithTheReserveLeft() {
    val press = ScannerPress(0)
    assertEquals(PressFocus.WORKING, press.at(5_999))
    assertEquals(PressFocus.START_FINAL_SCAN, press.at(6_000))
  }

  @Test
  fun theBudgetEndsTheWaitWithoutAnyEvent() {
    val press = ScannerPress(0)
    press.at(6_000)
    assertEquals(PressFocus.EXHAUSTED, press.at(8_000, finalScan = OwnScan.SCANNING))
  }

  @Test
  fun aTapsLockKeepsTheFinalScanWaiting() {
    val press = ScannerPress(0)
    assertEquals(PressFocus.WORKING, press.at(6_500, gaveUp = true, lensFree = false))
    assertEquals(PressFocus.START_FINAL_SCAN, press.at(6_600, gaveUp = true))
  }
}
