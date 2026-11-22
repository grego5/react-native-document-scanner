package com.margelo.nitro.documentscanner

import com.margelo.nitro.documentscanner.TextFocusCycle.Lens
import com.margelo.nitro.documentscanner.TextFocusCycle.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextFocusCycleTest {
  private val ms = 1_000_000L

  private fun frame(atMs: Long, lens: Lens = Lens.FREE, quiet: Boolean = true, turned: Boolean = false,
    settled: Boolean = false, unfocused: Boolean = false, lockRead: Boolean? = null, dark: Boolean = false,
    rescaled: Boolean = false) = TextFocusCycle.Frame(atMs * ms, quiet, turned, settled, unfocused, lockRead, lens, dark, rescaled)

  @Test fun aDarkSceneLocksOnASteadyHoldWithoutWaiting() {
    val cycle = TextFocusCycle()
    assertEquals(Step.None, cycle.next(frame(0, quiet = false, dark = true)))
    assertEquals(Step.Lock("a dark scene"), cycle.next(frame(40, dark = true, lockRead = true)))
    // The dark lock holds while text reads; the hold's budget still bounds it.
    for (t in 600L..3_000L step 40) assertEquals(Step.None, cycle.next(frame(t, Lens.LOCKED, dark = true, lockRead = true)))
  }

  @Test fun continuousAutofocusSettlingOnASteadyHoldIsConfirmedByALockEvenWhileTextReads() {
    val cycle = TextFocusCycle()
    assertEquals(Step.Lock("confirming continuous autofocus's focus"), cycle.next(frame(0, settled = true, lockRead = true)))
    // The confirmed lock holds while the text reads.
    assertEquals(Step.None, cycle.next(frame(100, Lens.SCANNING)))
    for (t in 600L..3_000L step 40) assertEquals(Step.None, cycle.next(frame(t, Lens.LOCKED, lockRead = true)))
  }

  @Test fun noFocusLocksAtOnce() {
    val cycle = TextFocusCycle()
    assertEquals(Step.Lock("continuous autofocus reported no focus"), cycle.next(frame(0, unfocused = true)))
  }

  @Test fun continuousAutofocusThatNeverSettlesLocksAfterTheWait() {
    val cycle = TextFocusCycle()
    assertEquals(Step.None, cycle.next(frame(0)))
    assertEquals(Step.None, cycle.next(frame(560)))
    assertEquals(Step.Lock("continuous autofocus did not settle"), cycle.next(frame(600)))
  }

  @Test fun aLockUnderWhichNothingReadsRunsOnceMoreThenStops() {
    val cycle = TextFocusCycle()
    assertTrue(cycle.next(frame(0, settled = true)) is Step.Lock)
    // Scanning frames do not count toward the wait.
    assertEquals(Step.None, cycle.next(frame(100, Lens.SCANNING)))
    // The lock waits for its reading to end.
    assertEquals(Step.None, cycle.next(frame(1_000, Lens.LOCKED)))
    assertEquals(Step.Lock("nothing read under the lock"), cycle.next(frame(1_040, Lens.LOCKED, lockRead = false)))
    cycle.next(frame(1_100, Lens.SCANNING))
    for (t in 2_500L..6_000L step 40) assertEquals(Step.None, cycle.next(frame(t, Lens.LOCKED, lockRead = false)))
    // A failed lock hands the lens back; its settling no longer locks once the hold's locks are spent.
    assertEquals(Step.None, cycle.next(frame(6_040, settled = true)))
  }

  @Test fun aTurnReleasesTheLockAndStartsTheCountOver() {
    val cycle = TextFocusCycle()
    cycle.next(frame(0, settled = true))
    cycle.next(frame(1_000, Lens.LOCKED))
    cycle.next(frame(1_600, Lens.LOCKED))
    assertEquals(Step.Release("phone turned"), cycle.next(frame(2_000, Lens.LOCKED, quiet = false, turned = true)))
    assertEquals(Step.None, cycle.next(frame(2_300, quiet = false)))
    assertTrue(cycle.next(frame(2_500, settled = true)) is Step.Lock)
  }

  @Test fun aTremorKeepsTheLockButADeliberateMoveReleasesIt() {
    val cycle = TextFocusCycle()
    // One spike reads as motion for the 300 ms the hand's quiet is judged over.
    for (t in 0L..300L step 40) assertEquals(Step.None, cycle.next(frame(t, Lens.LOCKED, quiet = false, lockRead = true)))
    assertEquals(Step.None, cycle.next(frame(340, Lens.LOCKED, lockRead = true)))
    for (t in 400L..960L step 40) assertEquals(Step.None, cycle.next(frame(t, Lens.LOCKED, quiet = false, lockRead = true)))
    assertEquals(Step.Release("phone moved"), cycle.next(frame(1_000, Lens.LOCKED, quiet = false, lockRead = true)))
    assertEquals(Step.None, cycle.next(frame(1_400, quiet = false, settled = true)))
  }

  @Test fun aChangeOfDistanceReleasesTheLockAndStartsTheCountOver() {
    val cycle = TextFocusCycle()
    cycle.next(frame(0, settled = true))
    assertEquals(Step.None, cycle.next(frame(1_000, Lens.LOCKED, lockRead = true)))
    // Moving straight back is quiet: no turn, no shake, only the text shrinking.
    assertEquals(Step.Release("distance changed"), cycle.next(frame(1_400, Lens.LOCKED, lockRead = true, rescaled = true)))
    assertTrue(cycle.next(frame(1_800, settled = true)) is Step.Lock)
  }

  @Test fun aLockWhoseReadingFoundTextHolds() {
    val cycle = TextFocusCycle()
    cycle.next(frame(0, settled = true))
    for (t in 100L..5_000L step 40) assertEquals(Step.None, cycle.next(frame(t, Lens.LOCKED, lockRead = true.takeIf { t >= 400 })))
  }
}
