package com.margelo.nitro.documentscanner

import com.margelo.nitro.documentscanner.processing.hasAdjustableAutofocus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusPolicyTest {
  private val second = 1_000_000_000L

  @Test fun aTapHoldsTheLensForFiveSecondsAndEndsAfterCapture() {
    val f = FocusPolicy()
    val tap = f.start(FocusOwner.TAP, 0)
    assertTrue(f.owner == FocusOwner.TAP && f.pending && f.locked == null)
    assertEquals(5 * second, f.tapDeadline())
    assertNull(f.scanDeadline())
    assertTrue(f.complete(tap, true))
    assertTrue(f.generation > tap && f.locked == true && !f.pending)
    assertFalse(f.complete(tap, false))
    assertFalse(f.expireTap(tap, 5 * second, true)) // A shot in flight keeps it.
    assertFalse(f.afterCapture(4 * second))
    assertEquals(FocusOwner.TAP, f.owner)
    assertTrue(f.afterCapture(5 * second))
    assertNull(f.owner)
    assertNull(f.tapDeadline())
  }

  @Test fun aCaptureScanHasADeadlineAndEndsWithItsShot() {
    val f = FocusPolicy()
    val shot = f.start(FocusOwner.CAPTURE, 7 * second)
    assertTrue(f.owner == FocusOwner.CAPTURE && f.pending)
    assertEquals(Long.MAX_VALUE, f.heldUntilNs)
    assertEquals(9 * second, f.scanDeadline())
    assertNull(f.tapDeadline())
    assertTrue(f.complete(shot, false))
    assertTrue(f.locked == false && f.scanDeadline() == null)
    assertTrue(f.afterCapture(8 * second))
    assertNull(f.owner)
    assertFalse(f.afterCapture(8 * second))
  }

  @Test fun unbindEndsStaleScansAndUnknownResultsSettleWithoutALock() {
    val f = FocusPolicy()
    val stale = f.start(FocusOwner.TAP, 12 * second)
    val generation = f.generation
    f.unbind()
    assertTrue(f.generation > generation && f.owner == null)
    assertFalse(f.complete(stale, true))
    val newer = f.start(FocusOwner.TAP, 13 * second)
    assertFalse(f.expireTap(stale, 100 * second, false))
    assertTrue(f.complete(newer, null))
    assertTrue(f.locked == null && !f.pending)
    assertTrue(f.expireTap(newer, 18 * second, false))
  }

  @Test fun fixedFocusAndEdofCamerasDoNotWaitForAScan() {
    assertFalse(hasAdjustableAutofocus(null))
    assertFalse(hasAdjustableAutofocus(intArrayOf(0, 5))) // OFF, EDOF.
    assertTrue(hasAdjustableAutofocus(intArrayOf(0, 1, 4))) // AUTO, CONTINUOUS_PICTURE.
  }
}
