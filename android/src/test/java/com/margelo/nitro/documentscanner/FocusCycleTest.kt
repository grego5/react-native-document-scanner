package com.margelo.nitro.documentscanner

import com.margelo.nitro.documentscanner.FocusCycle.Lens
import com.margelo.nitro.documentscanner.FocusCycle.PressFocus
import com.margelo.nitro.documentscanner.FocusCycle.Print
import com.margelo.nitro.documentscanner.processing.FocusReading
import com.margelo.nitro.documentscanner.processing.FocusVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusCycleTest {
  @Test fun manualFocusRequiresASeparateUninterruptedRunToAcceptOrReject() {
    val evidence = ManualFocusEvidence()
    repeat(FocusRules.VERDICT_FRAMES - 1) { evidence.observe(true, false) }
    evidence.observe(true, true)
    assertFalse(evidence.rejected)
    assertFalse(evidence.verified)
    evidence.observe(false, true)
    repeat(FocusRules.VERDICT_FRAMES) { evidence.observe(true, true) }
    assertTrue(evidence.verified)
    evidence.reset()
    assertFalse(evidence.verified)
    repeat(FocusRules.VERDICT_FRAMES) { evidence.observe(true, false) }
    assertTrue(evidence.rejected)
  }

  @Test fun aSteadySceneNeedsOneScanEvenWithSharpPassiveFocus() {
    val cycle = FocusCycle()
    assertTrue(cycle.needsScan)
    assertEquals(PressFocus.WORKING, cycle.pressFocus(Lens.FREE, Print.SHARP))
    assertEquals("initial steady hold", cycle.started())
    repeat(20) { assertFalse(cycle.observe(Lens.LOCKED, 1, Print.SHARP, false, null)) }
    assertFalse(cycle.needsScan)
    assertEquals(PressFocus.ACCEPTED, cycle.pressFocus(Lens.LOCKED, Print.SHARP))
  }

  @Test fun aLostPageArmsOneReplacementWhileTheOldLockIsStillHeld() {
    val cycle = FocusCycle()
    cycle.started()
    assertTrue(cycle.observe(Lens.LOCKED, 1, Print.SHARP, false, "page lost"))
    assertEquals(PressFocus.WORKING, cycle.pressFocus(Lens.LOCKED, Print.SHARP))
    repeat(10) { assertFalse(cycle.observe(Lens.LOCKED, 1, Print.SHARP, false, null)) }
    assertEquals("page lost", cycle.started())
    assertEquals(PressFocus.WORKING, cycle.pressFocus(Lens.SCANNING, Print.UNSURE))
    assertEquals(PressFocus.WORKING, cycle.pressFocus(Lens.LOCKED, Print.UNSURE))
    assertEquals(PressFocus.ACCEPTED, cycle.pressFocus(Lens.LOCKED, Print.UNCLEAR))
  }

  @Test fun aBadScanDoesNotRetryEvenIfTheFreeLensFindsSharpPrint() {
    val cycle = FocusCycle()
    cycle.started()
    assertEquals("lock failed", cycle.failed("lock failed"))
    repeat(20) { assertFalse(cycle.observe(Lens.FREE, 2, Print.SHARP, false, null)) }
    assertFalse(cycle.needsScan)
    assertEquals(PressFocus.UNCONFIRMED, cycle.pressFocus(Lens.FREE, Print.SHARP))
    assertTrue(cycle.observe(Lens.FREE, 2, Print.UNSURE, false, "page found"))
    assertEquals("page found", cycle.started())
  }

  @Test fun anInitiallyDefocusedLockDoesNotScanAgain() {
    val cycle = FocusCycle()
    cycle.started()
    assertFalse(cycle.observe(Lens.LOCKED, 1, Print.DEFOCUSED, false, null))
    assertEquals("defocused", cycle.failed("defocused"))
    assertEquals(PressFocus.UNCONFIRMED, cycle.pressFocus(Lens.LOCKED, Print.DEFOCUSED))
  }

  @Test fun aSharpLockGoingSoftCanScanAgainOncePerScene() {
    val cycle = FocusCycle()
    cycle.started()
    cycle.observe(Lens.LOCKED, 1, Print.SHARP, false, null)
    assertTrue(cycle.observe(Lens.LOCKED, 1, Print.DEFOCUSED, false, null))
    assertEquals("previously sharp print defocused", cycle.started())
    cycle.observe(Lens.LOCKED, 2, Print.SHARP, false, null)
    assertFalse(cycle.observe(Lens.LOCKED, 2, Print.DEFOCUSED, false, null))
    assertFalse(cycle.needsScan)
    assertTrue(cycle.observe(Lens.LOCKED, 2, Print.DEFOCUSED, false, "distance changed"))
    assertEquals("distance changed", cycle.started())
    cycle.observe(Lens.LOCKED, 3, Print.SHARP, false, null)
    assertTrue(cycle.observe(Lens.LOCKED, 3, Print.DEFOCUSED, false, null))
  }

  @Test fun aNewSceneDuringAScanSurvivesThatScansFailure() {
    val cycle = FocusCycle()
    cycle.started()
    assertTrue(cycle.observe(Lens.SCANNING, 1, Print.UNSURE, true, null))
    assertNull(cycle.failed("lock failed"))
    assertEquals("phone turned", cycle.started())
    assertFalse(cycle.observe(Lens.LOCKED, 2, Print.SHARP, true, null))
    assertFalse(cycle.needsScan)
  }

  @Test fun sustainedMotionArmsOneScanAndBriefMotionDoesNot() {
    val cycle = FocusCycle()
    cycle.started()
    cycle.motion(0, false)
    cycle.motion(FocusRules.MOVED_NS - 1, false)
    cycle.motion(FocusRules.MOVED_NS, true)
    assertFalse(cycle.observe(Lens.LOCKED, 1, Print.SHARP, false, null))
    cycle.motion(FocusRules.MOVED_NS, false)
    cycle.motion(FocusRules.MOVED_NS * 2, false)
    cycle.motion(FocusRules.MOVED_NS * 2 + 1, true)
    assertTrue(cycle.observe(Lens.LOCKED, 1, Print.SHARP, false, null))
    assertEquals("phone moved", cycle.started())
    assertFalse(cycle.observe(Lens.LOCKED, 2, Print.SHARP, false, null))
  }

  @Test fun anAnchorHoldsOnlyUnderItsOwnGeneration() {
    val run = PrintFocusRun()
    repeat(FocusRules.VERDICT_FRAMES) { run.observe(1, FocusReading(FocusVerdict.SHARP, .85), false, 4.2, .321) }
    assertTrue(run.anchoredAt(1, 4.2))
    assertFalse(run.anchoredAt(2, 4.2))
  }

  @Test fun theRestingLensWithoutASharpPrintReadsUnclear() {
    val run = PrintFocusRun()
    val unknown = FocusReading(FocusVerdict.UNKNOWN, 1.6)
    repeat(FocusRules.UNCLEAR_FRAMES - 1) { run.observe(1, unknown, false, 4.0, .321) }
    assertEquals(Print.UNSURE, run.print(1))
    // The lens moving starts the count over.
    run.observe(1, unknown, true, 4.2, .321)
    repeat(FocusRules.UNCLEAR_FRAMES - 1) { run.observe(1, unknown, false, 4.2, .321) }
    assertEquals(Print.UNSURE, run.print(1))
    run.observe(1, unknown, false, 4.2, .321)
    assertEquals(Print.UNCLEAR, run.print(1))
  }

  @Test fun aPrintRunNeedsFramesInARowUnderOneLensGeneration() {
    val run = PrintFocusRun()
    val sharp = FocusReading(FocusVerdict.SHARP, 1.6)
    val defocused = FocusReading(FocusVerdict.DEFOCUSED, 1.6)
    assertEquals(Print.UNSURE, run.print(1))
    repeat(FocusRules.VERDICT_FRAMES - 1) { run.observe(1, sharp, false, null, null) }
    // A smeared frame says nothing either way.
    run.observe(1, FocusReading(FocusVerdict.SMEARED, 1.6), false, null, null)
    assertEquals(Print.UNSURE, run.print(1))
    run.observe(1, sharp, false, null, null)
    assertEquals(Print.SHARP, run.print(1))
    assertEquals(Print.UNSURE, run.print(2))
    run.observe(1, defocused, false, null, null)
    assertEquals(Print.UNSURE, run.print(1))
    repeat(FocusRules.VERDICT_FRAMES - 1) { run.observe(1, defocused, false, null, null) }
    assertEquals(Print.DEFOCUSED, run.print(1))
    // The lens moving ends the run; so does a new generation.
    run.observe(1, FocusReading(FocusVerdict.UNKNOWN, 1.6), true, null, null)
    assertEquals(Print.UNSURE, run.print(1))
    repeat(FocusRules.VERDICT_FRAMES) { run.observe(1, defocused, false, null, null) }
    run.observe(2, sharp, false, null, null)
    assertEquals(Print.UNSURE, run.print(2))
  }

  @Test fun aSharpRunAnchorsTheLensDistanceThroughContinuousAutofocusWandering() {
    val run = PrintFocusRun()
    val sharp = FocusReading(FocusVerdict.SHARP, .85)
    val unknown = FocusReading(FocusVerdict.UNKNOWN, .85)
    // Recorded at 01:49:53: continuous autofocus searched again on a sharp page, moving the lens 4.20 to 4.50 diopters.
    repeat(FocusRules.VERDICT_FRAMES) { run.observe(1, sharp, false, 4.20, .321) }
    for (d in listOf(4.33, 4.38, 4.42, 4.50)) {
      run.observe(1, unknown, true, d, .321)
      assertEquals(Print.SHARP, run.print(1))
      assertTrue(run.anchoredAt(1, d))
    }
    // Past the depth of field, the edges judge again.
    run.observe(1, unknown, true, 4.60, .321)
    assertEquals(Print.UNSURE, run.print(1))
    assertFalse(run.anchoredAt(1, 4.25))
    repeat(FocusRules.VERDICT_FRAMES) { run.observe(1, sharp, false, 4.60, .321) }
    assertTrue(run.anchoredAt(1, 4.40))
    // The page's distance changing under a still lens: a defocused run drops the anchor.
    val defocused = FocusReading(FocusVerdict.DEFOCUSED, .85)
    repeat(FocusRules.VERDICT_FRAMES) { run.observe(1, defocused, false, 4.60, .321) }
    assertEquals(Print.DEFOCUSED, run.print(1))
    assertFalse(run.anchoredAt(1, 4.60))
    // A new lens owner starts over.
    repeat(FocusRules.VERDICT_FRAMES) { run.observe(2, sharp, false, 4.0, .321) }
    run.observe(3, unknown, true, 4.0, .321)
    assertEquals(Print.UNSURE, run.print(3))
  }
}
