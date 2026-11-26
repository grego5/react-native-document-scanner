package com.margelo.nitro.documentscanner

import com.margelo.nitro.documentscanner.FocusCycle.Aim
import com.margelo.nitro.documentscanner.FocusCycle.Lens
import com.margelo.nitro.documentscanner.FocusCycle.Print
import com.margelo.nitro.documentscanner.FocusCycle.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import com.margelo.nitro.documentscanner.processing.FocusReading
import com.margelo.nitro.documentscanner.processing.FocusVerdict
import org.junit.Test

class FocusCycleTest {
  private val ms = 1_000_000L

  private fun frame(lens: Lens = Lens.FREE, quiet: Boolean = true, turned: Boolean = false,
    target: Boolean = true, rescaled: Boolean = false, found: Boolean? = null, motionReleases: Boolean = true,
    canLight: Boolean = false, dark: Boolean = false, lightWanted: Boolean = false, lit: Boolean = false,
    aim: Aim = Aim.TARGET, print: Print = Print.UNJUDGED, generation: Long = 0) =
    FocusCycle.Frame(quiet, turned, lens, target, rescaled, found, motionReleases, canLight, dark, lightWanted, lit, aim,
      print, generation)

  /** [step] as the camera takes it. */
  private fun FocusCycle.take(step: Step): Step =
    step.also { if (it is Step.Lock || it is Step.Relock || it is Step.Replace) started() }

  @Test fun aSteadyHoldWithATargetLocksAtOnce() {
    val cycle = FocusCycle()
    assertEquals(Step.None, cycle.next(frame(quiet = false)))
    assertEquals(Step.None, cycle.next(frame(target = false)))
    assertEquals(Step.Lock("a steady hold"), cycle.take(cycle.next(frame())))
    assertEquals(Step.None, cycle.next(frame(Lens.SCANNING)))
    repeat(60) { assertEquals(Step.None, cycle.next(frame(Lens.LOCKED, found = true))) }
  }

  @Test fun aLockTheCameraRefusedIsAskedForAgain() {
    val cycle = FocusCycle()
    repeat(3) { assertEquals(Step.Lock("a steady hold"), cycle.next(frame())) }
    cycle.take(cycle.next(frame()))
    assertEquals(Step.None, cycle.next(frame(Lens.LOCKED, found = true)))
  }

  @Test fun aLockThatFindsNothingFinishesTheCycleUntilSomethingChanges() {
    val cycle = FocusCycle()
    cycle.take(cycle.next(frame()))
    assertEquals(Step.Finish("nothing found under the lock"), cycle.next(frame(Lens.LOCKED, found = false)))
    assertTrue(cycle.finished)
    repeat(50) { assertEquals(Step.None, cycle.next(frame(Lens.LOCKED, found = false))) }
    // No count and no timer starts it over: a steady hold alone does not.
    repeat(50) { assertEquals(Step.None, cycle.next(frame())) }
  }

  @Test fun aDarkSceneLightsTheTorchForTheNextLockThenFinishes() {
    val cycle = FocusCycle()
    cycle.take(cycle.next(frame(canLight = true, dark = true)))
    assertEquals(Step.Light("nothing found under the lock"),
      cycle.next(frame(Lens.LOCKED, found = false, canLight = true, dark = true)))
    assertTrue(cycle.lighting)
    assertEquals(Step.None, cycle.next(frame(Lens.LOCKED, found = false, lightWanted = true)))
    assertEquals(Step.Relock("torch lit"),
      cycle.take(cycle.next(frame(Lens.LOCKED, found = false, lightWanted = true, lit = true))))
    assertFalse(cycle.lighting)
    assertEquals(Step.Finish("nothing found under the lock"),
      cycle.next(frame(Lens.LOCKED, found = false, dark = true, lightWanted = true, lit = true)))
  }

  @Test fun aTorchNoLongerWantedEndsTheWait() {
    val cycle = FocusCycle()
    cycle.next(frame(canLight = true))
    assertEquals(Step.Light("lock failed"), cycle.failed(canLight = true))
    assertEquals(Step.None, cycle.next(frame(lightWanted = true)))
    // The torch mode turned off: the cycle locks without it.
    assertEquals(Step.Lock("a steady hold"), cycle.next(frame()))
    assertFalse(cycle.lighting)
  }

  @Test fun aFailedLockLightsTheTorchEvenInLightAndOtherwiseFinishes() {
    val lit = FocusCycle()
    lit.next(frame(canLight = true))
    assertEquals(Step.Light("lock failed"), lit.failed(canLight = true))
    assertEquals(Step.Lock("torch lit"), lit.next(frame(lightWanted = true, lit = true)))
    val unlit = FocusCycle()
    unlit.take(unlit.next(frame()))
    assertEquals(Step.Finish("lock failed"), unlit.failed(canLight = false))
    assertEquals(Step.None, unlit.next(frame()))
  }

  @Test fun aFailureJudgesTheTorchAsItIsNowNotAsTheLastFrameSaw() {
    // A scan started before a press on `zsl` fails after its admission, before another frame: the torch cannot light.
    val cycle = FocusCycle()
    cycle.take(cycle.next(frame(canLight = true)))
    assertEquals(Step.Finish("lock failed"), cycle.failed(canLight = false))
    assertFalse(cycle.lighting)
  }

  @Test fun aTurnReleasesAndStartsOver() {
    val cycle = FocusCycle()
    cycle.take(cycle.next(frame()))
    val restarts = cycle.restarts
    assertEquals(Step.Release("phone turned"), cycle.next(frame(Lens.LOCKED, quiet = false, turned = true)))
    assertTrue(cycle.restarts > restarts)
    assertEquals(Step.Lock("a steady hold"), cycle.next(frame()))
  }

  @Test fun aDeliberateMoveReleasesOnlyALockThatFollowsNoSize() {
    val cycle = FocusCycle()
    // One spike reads as motion for the 300 ms the hand's quiet is judged over.
    for (t in 0L..300L step 40) cycle.motion(t * ms, false)
    cycle.motion(340 * ms, true)
    assertEquals(Step.None, cycle.next(frame(Lens.LOCKED)))
    for (t in 400L..1_000L step 40) cycle.motion(t * ms, false)
    assertEquals(Step.Release("phone moved"), cycle.next(frame(Lens.LOCKED, quiet = false)))
    val page = FocusCycle()
    for (t in 0L..1_000L step 40) page.motion(t * ms, false)
    assertEquals(Step.None, page.next(frame(Lens.LOCKED, quiet = false, motionReleases = false)))
  }

  @Test fun aMoveBetweenTwoFramesTheCycleSeesStillRestartsAFinishedCycle() {
    val cycle = FocusCycle()
    cycle.take(cycle.next(frame()))
    assertEquals(Step.Finish("lock failed"), cycle.failed(canLight = false))
    // Frames in between move for a second, then settle; the next frame the cycle sees is steady.
    for (t in 300L..1_300L step 40) cycle.motion(t * ms, false)
    for (t in 1_340L..1_700L step 40) cycle.motion(t * ms, true)
    val restarts = cycle.restarts
    // That frame's target was picked before the restart, so the lock waits for the next one.
    assertEquals(Step.None, cycle.next(frame()))
    assertTrue(cycle.restarts > restarts)
    assertFalse(cycle.finished)
    assertEquals(Step.Lock("a steady hold"), cycle.next(frame()))
  }

  @Test fun motionShorterThanADeliberateMoveOnlyPausesTheCycle() {
    val cycle = FocusCycle()
    cycle.take(cycle.next(frame()))
    for (t in 0L..400L step 40) cycle.motion(t * ms, false)
    assertEquals(Step.None, cycle.next(frame(Lens.LOCKED, quiet = false)))
    cycle.motion(440 * ms, true)
    val restarts = cycle.restarts
    assertEquals(Step.None, cycle.next(frame(Lens.LOCKED, found = true)))
    assertEquals(restarts, cycle.restarts)
  }

  @Test fun aChangeOfSizeRelocksOnceSteadyWithItsTargetWithoutARelease() {
    val cycle = FocusCycle()
    cycle.take(cycle.next(frame()))
    assertEquals(Step.None, cycle.next(frame(Lens.LOCKED, quiet = false, rescaled = true, motionReleases = false)))
    // Steady, but the page's outline dropped out: the relock waits for it.
    assertEquals(Step.None, cycle.next(frame(Lens.LOCKED, target = false)))
    // The camera refuses it, as during a shot: it is asked for again.
    assertEquals(Step.Relock("distance changed"), cycle.next(frame(Lens.LOCKED)))
    assertEquals(Step.Relock("distance changed"), cycle.take(cycle.next(frame(Lens.LOCKED))))
    cycle.next(frame(Lens.SCANNING))
    assertEquals(Step.Finish("nothing found under the lock"), cycle.next(frame(Lens.LOCKED, found = false)))
  }

  @Test fun aNewTargetRestartsAFinishedCycle() {
    val cycle = FocusCycle()
    cycle.take(cycle.next(frame()))
    assertEquals(Step.Finish("lock failed"), cycle.failed(canLight = false))
    cycle.reset()
    assertFalse(cycle.finished)
    assertEquals(Step.Lock("a steady hold"), cycle.next(frame()))
  }

  @Test fun aTargetFoundOnTheLocksPointKeepsItOnlyOnceTurnsAndMovesAreHandled() {
    val cycle = FocusCycle()
    cycle.take(cycle.next(frame()))
    cycle.reset()
    // A page found while the phone turns: the turn releases the lock, which is not kept.
    assertEquals(Step.Release("phone turned"), cycle.next(frame(Lens.LOCKED, turned = true, aim = Aim.ON_NEW_TARGET)))
    // A page found as a deliberate move ends releases the lock too.
    val moving = FocusCycle()
    moving.take(moving.next(frame()))
    for (t in 100L..800L step 100) moving.motion(t * ms, quiet = false)
    assertEquals(Step.Release("phone moved"), moving.next(frame(Lens.LOCKED, quiet = false, aim = Aim.ON_NEW_TARGET)))
    // Held still or merely unsteady, it is kept without another scan.
    val still = FocusCycle()
    still.take(still.next(frame()))
    assertEquals(Step.Keep, still.next(frame(Lens.LOCKED, quiet = false, aim = Aim.ON_NEW_TARGET)))
  }

  @Test fun aTargetFoundOffTheLocksPointReplacesIt() {
    val cycle = FocusCycle()
    cycle.take(cycle.next(frame()))
    cycle.reset()
    // The replacement waits for a steady hold and its target.
    assertEquals(Step.None, cycle.next(frame(Lens.LOCKED, quiet = false, aim = Aim.OFF_NEW_TARGET)))
    assertEquals(Step.None, cycle.next(frame(Lens.LOCKED, target = false, aim = Aim.OFF_NEW_TARGET)))
    assertEquals(Step.Replace("a target found off the lock's point"),
      cycle.take(cycle.next(frame(Lens.LOCKED, aim = Aim.OFF_NEW_TARGET))))
    // Its trigger fails at once: the cycle finishes.
    assertEquals(Step.Finish("lock failed"), cycle.failed(canLight = false))
  }

  @Test fun aReplacementDropsARelockPendingForTheLockItReplaces() {
    val cycle = FocusCycle()
    cycle.take(cycle.next(frame()))
    // A relock the camera did not take yet stays pending.
    cycle.next(frame(Lens.LOCKED, quiet = false, rescaled = true, motionReleases = false))
    // A target found off the lock's point, not yet ready, drops it: it would scan what the lock focused.
    assertEquals(Step.None, cycle.next(frame(Lens.LOCKED, target = false, aim = Aim.OFF_NEW_TARGET)))
    assertEquals(Step.None, cycle.next(frame(Lens.LOCKED, found = true)))
  }

  @Test fun aSizeChangeHoldsForItsFramesAndIsReportedOncePerKey() {
    val watch = SizeWatch()
    val limit = { SizeLimit.fixed(1.1) }
    assertNull(watch.observe(1L, 1.0, limit))
    repeat(FocusRules.SIZE_FRAMES - 1) { assertNull(watch.observe(1L, 1.3, limit)) }
    assertEquals(1.14, watch.observe(1L, 1.3, limit)!!, 1e-2)
    assertNull(watch.observe(1L, 1.3, limit))
    // A new key is a new baseline.
    assertNull(watch.observe(2L, 1.3, limit))
    // A frame inside the limit starts the run over.
    watch.observe(2L, 1.7, limit)
    watch.observe(2L, 1.3, limit)
    repeat(FocusRules.SIZE_FRAMES - 1) { assertNull(watch.observe(2L, 1.7, limit)) }
    assertTrue(watch.observe(2L, 1.7, limit) != null)
  }

  @Test fun aSharpPrintKeepsContinuousAutofocusUntilItReadsDefocused() {
    val cycle = FocusCycle()
    repeat(30) { assertEquals(Step.None, cycle.next(frame(print = Print.SHARP))) }
    assertEquals(Step.Lock("the print reads defocused"), cycle.next(frame(print = Print.DEFOCUSED)))
  }

  @Test fun anUnsurePrintWaitsForTheLensToSettleWithoutAVerdict() {
    val cycle = FocusCycle()
    repeat(30) { assertEquals(Step.None, cycle.next(frame(print = Print.UNSURE))) }
    assertEquals(Step.Lock("no sharp print once the lens settled"), cycle.next(frame(print = Print.UNCLEAR)))
  }

  @Test fun theDarkLocksASharpPrint() {
    assertEquals(Step.Lock("a steady hold in the dark"), FocusCycle().next(frame(print = Print.SHARP, dark = true)))
    assertEquals(Step.None, FocusCycle().next(frame(print = Print.SHARP)))
  }

  @Test fun aLockWhosePrintReadsDefocusedCountsAsOneThatFoundNothing() {
    val cycle = FocusCycle()
    cycle.take(cycle.next(frame(print = Print.DEFOCUSED)))
    assertEquals(Step.None, cycle.next(frame(Lens.LOCKED, print = Print.SHARP)))
    assertEquals(Step.Finish("the print reads defocused under the lock"),
      cycle.next(frame(Lens.LOCKED, print = Print.DEFOCUSED)))
    repeat(10) { assertEquals(Step.None, cycle.next(frame(Lens.LOCKED, print = Print.DEFOCUSED))) }
    // In the dark, it lights the torch first.
    val dark = FocusCycle()
    dark.take(dark.next(frame(dark = true, canLight = true)))
    assertEquals(Step.Light("the print reads defocused under the lock"),
      dark.next(frame(Lens.LOCKED, print = Print.DEFOCUSED, dark = true, canLight = true)))
  }

  @Test fun aSizeRelockVetoesItsLockAcrossAResetAndARefusalUntilTaken() {
    val cycle = FocusCycle()
    cycle.take(cycle.next(frame()))
    assertEquals(Step.Relock("distance changed"), cycle.next(frame(Lens.LOCKED, rescaled = true, generation = 5)))
    assertEquals(FocusCycle.Readiness.WORKING, cycle.lockReadiness(5, Print.SHARP))
    // A new target or a change of light starts the cycle over; the lock still owes its relock.
    cycle.reset()
    assertEquals(FocusCycle.Readiness.WORKING, cycle.lockReadiness(5, Print.SHARP))
    // Refused, as during a shot: still owed, and still asked for.
    assertEquals(Step.Relock("distance changed"), cycle.next(frame(Lens.LOCKED, generation = 5)))
    // Taken: the new scan's generation is judged by its own print.
    cycle.take(cycle.next(frame(Lens.LOCKED, generation = 5)))
    assertEquals(FocusCycle.Readiness.WORKING, cycle.lockReadiness(6, Print.UNSURE))
    assertEquals(FocusCycle.Readiness.ACCEPTED, cycle.lockReadiness(6, Print.SHARP))
  }

  @Test fun aRelockRetiresWithItsLock() {
    // The lens changed generation without the relock, as a tap taking it.
    val handed = FocusCycle()
    handed.take(handed.next(frame()))
    handed.next(frame(Lens.LOCKED, rescaled = true, generation = 5))
    assertEquals(Step.None, handed.next(frame(Lens.LOCKED, generation = 7)))
    assertEquals(FocusCycle.Readiness.ACCEPTED, handed.lockReadiness(7, Print.SHARP))
    // A turn releases the lock; a release that waits for a shot asks no relock meanwhile.
    val turned = FocusCycle()
    turned.take(turned.next(frame()))
    turned.next(frame(Lens.LOCKED, rescaled = true, generation = 5))
    assertEquals(Step.Release("phone turned"), turned.next(frame(Lens.LOCKED, turned = true, generation = 5)))
    assertEquals(Step.None, turned.next(frame(Lens.LOCKED, generation = 5)))
  }

  @Test fun pressFocusTakesAJudgedLockOrSharpContinuousFocusThenTheCyclesEnd() {
    val cycle = FocusCycle()
    assertEquals(FocusCycle.PressFocus.ACCEPTED, cycle.pressFocus(Lens.FREE, 0, Print.SHARP, dark = false))
    assertEquals(FocusCycle.PressFocus.WORKING, cycle.pressFocus(Lens.FREE, 0, Print.SHARP, dark = true))
    assertEquals(FocusCycle.PressFocus.WORKING, cycle.pressFocus(Lens.SCANNING, 0, Print.SHARP, dark = false))
    // Dense print accepts a lock on its sharp verdict; sparse print on its unclear one.
    assertEquals(FocusCycle.PressFocus.ACCEPTED, cycle.pressFocus(Lens.LOCKED, 0, Print.SHARP, dark = false))
    assertEquals(FocusCycle.PressFocus.ACCEPTED, cycle.pressFocus(Lens.LOCKED, 0, Print.UNCLEAR, dark = false))
    assertEquals(FocusCycle.PressFocus.WORKING, cycle.pressFocus(Lens.LOCKED, 0, Print.UNSURE, dark = false))
    cycle.take(cycle.next(frame()))
    cycle.next(frame(Lens.LOCKED, found = false))
    assertTrue(cycle.ended)
    assertEquals(FocusCycle.PressFocus.UNCONFIRMED, cycle.pressFocus(Lens.LOCKED, 0, Print.SHARP, dark = false))
  }

  @Test fun aPressTakesALockOnlyOnceItsOwnFramesJudgedIt() {
    val cycle = FocusCycle()
    cycle.take(cycle.next(frame(print = Print.DEFOCUSED)))
    // Before a verdict forms under the lock, a press keeps waiting.
    assertEquals(FocusCycle.Readiness.WORKING, cycle.lockReadiness(0, Print.UNSURE))
    assertEquals(FocusCycle.Readiness.ACCEPTED, cycle.lockReadiness(0, Print.SHARP))
    assertEquals(FocusCycle.Readiness.ACCEPTED, cycle.lockReadiness(0, Print.UNCLEAR))
    // Once the cycle judged the lock defocused, a later sharp reading under it does not make it the press's.
    cycle.next(frame(Lens.LOCKED, print = Print.DEFOCUSED))
    assertTrue(cycle.finished)
    assertEquals(FocusCycle.Readiness.REJECTED, cycle.lockReadiness(0, Print.SHARP))
    // A relock, as after the torch lit, is judged afresh.
    val lit = FocusCycle()
    lit.take(lit.next(frame(dark = true, canLight = true)))
    lit.next(frame(Lens.LOCKED, print = Print.DEFOCUSED, dark = true, canLight = true))
    assertEquals(FocusCycle.Readiness.REJECTED, lit.lockReadiness(0, Print.UNSURE))
    assertEquals(Step.Relock("torch lit"),
      lit.take(lit.next(frame(Lens.LOCKED, dark = true, lightWanted = true, lit = true))))
    assertEquals(FocusCycle.Readiness.WORKING, lit.lockReadiness(0, Print.UNSURE))
    assertEquals(FocusCycle.Readiness.ACCEPTED, lit.lockReadiness(0, Print.SHARP))
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
