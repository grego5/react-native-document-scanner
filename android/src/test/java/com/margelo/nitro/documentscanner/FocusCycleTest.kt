package com.margelo.nitro.documentscanner

import com.margelo.nitro.documentscanner.FocusCycle.Aim
import com.margelo.nitro.documentscanner.FocusCycle.Lens
import com.margelo.nitro.documentscanner.FocusCycle.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusCycleTest {
  private val ms = 1_000_000L

  private fun frame(lens: Lens = Lens.FREE, quiet: Boolean = true, turned: Boolean = false,
    target: Boolean = true, rescaled: Boolean = false, found: Boolean? = null, motionReleases: Boolean = true,
    canLight: Boolean = false, dark: Boolean = false, lightWanted: Boolean = false, lit: Boolean = false,
    aim: Aim = Aim.TARGET) =
    FocusCycle.Frame(quiet, turned, lens, target, rescaled, found, motionReleases, canLight, dark, lightWanted, lit, aim)

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

  @Test fun aLockTheCameraRefusedIsAskedForAgainWithoutCounting() {
    val cycle = FocusCycle()
    repeat(3) { assertEquals(Step.Lock("a steady hold"), cycle.next(frame())) }
    cycle.take(cycle.next(frame()))
    cycle.take(cycle.next(frame()))
    assertEquals(Step.GiveUp("the hold's locks are spent"), cycle.next(frame()))
  }

  @Test fun aLockThatFindsNothingRelocksOnceThenGivesUp() {
    val cycle = FocusCycle()
    cycle.take(cycle.next(frame()))
    assertEquals(Step.Relock("nothing found under the lock"), cycle.take(cycle.next(frame(Lens.LOCKED, found = false))))
    cycle.next(frame(Lens.SCANNING))
    assertEquals(Step.GiveUp("nothing found under the lock"), cycle.next(frame(Lens.LOCKED, found = false)))
    assertTrue(cycle.gaveUp)
    repeat(50) { assertEquals(Step.None, cycle.next(frame(Lens.LOCKED, found = false))) }
    assertEquals(Step.None, cycle.next(frame()))
  }

  @Test fun aDarkSceneLightsTheTorchForTheNextLock() {
    val cycle = FocusCycle()
    cycle.take(cycle.next(frame(canLight = true, dark = true)))
    assertEquals(Step.Light("nothing found under the lock"),
      cycle.next(frame(Lens.LOCKED, found = false, canLight = true, dark = true)))
    assertTrue(cycle.lighting)
    assertEquals(Step.None, cycle.next(frame(Lens.LOCKED, found = false, lightWanted = true)))
    assertEquals(Step.Relock("torch lit"), cycle.next(frame(Lens.LOCKED, found = false, lightWanted = true, lit = true)))
    assertFalse(cycle.lighting)
  }

  @Test fun aTorchNoLongerWantedEndsTheWait() {
    val cycle = FocusCycle()
    cycle.next(frame(canLight = true))
    assertEquals(Step.Light("lock failed"), cycle.failed())
    assertEquals(Step.None, cycle.next(frame(lightWanted = true)))
    // The torch mode turned off: the cycle locks without it.
    assertEquals(Step.Lock("a steady hold"), cycle.next(frame()))
    assertFalse(cycle.lighting)
  }

  @Test fun aFailedLockLightsTheTorchEvenInLightAndOtherwiseLocksAgain() {
    val lit = FocusCycle()
    lit.next(frame(canLight = true))
    assertEquals(Step.Light("lock failed"), lit.failed())
    assertEquals(Step.Lock("torch lit"), lit.next(frame(lightWanted = true, lit = true)))
    val unlit = FocusCycle()
    unlit.take(unlit.next(frame()))
    assertEquals(Step.None, unlit.failed())
    assertEquals(Step.Lock("a steady hold"), unlit.take(unlit.next(frame())))
    assertEquals(Step.GiveUp("lock failed"), unlit.failed())
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

  @Test fun aMoveBetweenTwoFramesTheCycleSeesStillRestartsASpentCycle() {
    val cycle = FocusCycle()
    cycle.take(cycle.next(frame()))
    cycle.take(cycle.next(frame()))
    assertEquals(Step.GiveUp("the hold's locks are spent"), cycle.next(frame()))
    // Frames in between move for a second, then settle; the next frame the cycle sees is steady.
    for (t in 300L..1_300L step 40) cycle.motion(t * ms, false)
    for (t in 1_340L..1_700L step 40) cycle.motion(t * ms, true)
    val restarts = cycle.restarts
    // That frame's target was picked before the restart, so the lock waits for the next one.
    assertEquals(Step.None, cycle.next(frame()))
    assertTrue(cycle.restarts > restarts)
    assertEquals(Step.Lock("a steady hold"), cycle.next(frame()))
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
    // The count started over: the relock may still take one more lock.
    assertEquals(Step.Relock("nothing found under the lock"), cycle.next(frame(Lens.LOCKED, found = false)))
  }

  @Test fun aNewTargetStartsTheCountOver() {
    val cycle = FocusCycle()
    cycle.take(cycle.next(frame()))
    cycle.take(cycle.next(frame()))
    assertEquals(Step.GiveUp("the hold's locks are spent"), cycle.next(frame()))
    cycle.reset()
    assertFalse(cycle.gaveUp)
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

  @Test fun aTargetFoundOffTheLocksPointReplacesItWithACountedLock() {
    val cycle = FocusCycle()
    cycle.take(cycle.next(frame()))
    // The page's arrival starts the count over.
    cycle.reset()
    // The replacement waits for a steady hold and its target.
    assertEquals(Step.None, cycle.next(frame(Lens.LOCKED, quiet = false, aim = Aim.OFF_NEW_TARGET)))
    assertEquals(Step.None, cycle.next(frame(Lens.LOCKED, target = false, aim = Aim.OFF_NEW_TARGET)))
    assertEquals(Step.Replace("a target found off the lock's point"),
      cycle.take(cycle.next(frame(Lens.LOCKED, aim = Aim.OFF_NEW_TARGET))))
    // Its trigger fails at once: the replacement already counted, so one more lock is left, then the cycle gives up.
    assertEquals(Step.None, cycle.failed())
    assertEquals(Step.Lock("a steady hold"), cycle.take(cycle.next(frame())))
    assertEquals(Step.GiveUp("lock failed"), cycle.failed())
  }

  @Test fun aReplacementDropsARelockPendingForTheLockItReplaces() {
    val cycle = FocusCycle()
    cycle.take(cycle.next(frame()))
    // A relock the camera did not take yet stays pending.
    assertEquals(Step.Relock("nothing found under the lock"), cycle.next(frame(Lens.LOCKED, found = false)))
    // A target found off the lock's point, not yet ready, drops it: it would scan what the lock focused.
    assertEquals(Step.None, cycle.next(frame(Lens.LOCKED, target = false, aim = Aim.OFF_NEW_TARGET)))
    assertEquals(Step.None, cycle.next(frame(Lens.LOCKED, found = false)))
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
}
