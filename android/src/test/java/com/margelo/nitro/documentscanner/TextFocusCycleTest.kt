package com.margelo.nitro.documentscanner

import com.margelo.nitro.documentscanner.TextFocusCycle.Lens
import com.margelo.nitro.documentscanner.TextFocusCycle.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextFocusCycleTest {
  private val frameNs = 33_000_000L
  private val sharp = 2.0
  private val blurred = 4.0
  private var now = 0L

  /** One frame, 33 ms after the previous one, its motion reported first as the view does. */
  private fun TextFocusCycle.frame(lens: Lens, edges: Double?, quiet: Boolean = true, turned: Boolean = false,
    atNearLimit: Boolean = false): Step {
    now += frameNs
    motion(now, quiet)
    return next(TextFocusCycle.Frame(now, quiet, turned, lens, edges, atNearLimit)).also { if (it is Step.Lock) started() }
  }

  /** [count] frames; the steps other than [Step.None]. */
  private fun TextFocusCycle.frames(count: Int, lens: Lens, edges: Double?, quiet: Boolean = true,
    atNearLimit: Boolean = false): List<Step> =
    (1..count).map { frame(lens, edges, quiet, atNearLimit = atNearLimit) }.filter { it != Step.None }

  @Test fun sharpPrintUnderContinuousAutofocusNeverLocks() {
    val cycle = TextFocusCycle()
    assertEquals(emptyList<Step>(), cycle.frames(100, Lens.FREE, sharp))
    assertEquals(emptyList<Step>(), cycle.frames(100, Lens.FREE, null))
  }

  @Test fun aSteadyBlurredHoldLocksOnceAfterContinuousAutofocusHadItsTime() {
    val cycle = TextFocusCycle()
    // Moving: no lock however long.
    assertEquals(emptyList<Step>(), cycle.frames(40, Lens.FREE, blurred, quiet = false))
    val steps = cycle.frames(40, Lens.FREE, blurred)
    assertEquals(listOf(Step.Lock("print blurred under continuous autofocus")), steps)
    assertFalse(cycle.armed)
  }

  @Test fun aHoldNearerThanTheLensFocusesKeepsItsOneLock() {
    val cycle = TextFocusCycle()
    cycle.frames(20, Lens.FREE, blurred)
    // Blurred from the start at the near limit: the lock stays, and nothing locks again.
    assertEquals(emptyList<Step>(), cycle.frames(200, Lens.LOCKED, blurred, atNearLimit = true))
  }

  @Test fun movingBackThroughTheLockedDistanceReleasesOnceThePrintBlursAgain() {
    val cycle = TextFocusCycle()
    cycle.frames(20, Lens.FREE, blurred)
    cycle.frames(30, Lens.LOCKED, blurred, atNearLimit = true)
    assertEquals(emptyList<Step>(), cycle.frames(5, Lens.LOCKED, sharp, atNearLimit = true))
    assertEquals(listOf(Step.Release("print blurred off the lock's distance")),
      cycle.frames(10, Lens.LOCKED, blurred, atNearLimit = true))
    assertTrue(cycle.armed)
    // Continuous autofocus refocuses; still blurred on a steady hold, the hold locks again.
    assertEquals(listOf(Step.Lock("print blurred under continuous autofocus")), cycle.frames(40, Lens.FREE, blurred))
  }

  @Test fun aLockThatLeftThePrintBlurredAwayFromTheLimitIsReleasedWithoutAnotherTry() {
    val cycle = TextFocusCycle()
    cycle.frames(20, Lens.FREE, blurred)
    assertEquals(listOf(Step.Release("lock left the print blurred")), cycle.frames(20, Lens.LOCKED, blurred))
    assertEquals(emptyList<Step>(), cycle.frames(100, Lens.FREE, blurred))
  }

  @Test fun sharpPrintSinceTheLastLockArmsTheHoldAgain() {
    val cycle = TextFocusCycle()
    cycle.frames(20, Lens.FREE, blurred)
    cycle.frames(20, Lens.LOCKED, blurred)
    cycle.frames(3, Lens.FREE, sharp)
    assertTrue(cycle.armed)
    assertEquals(listOf(Step.Lock("print blurred under continuous autofocus")), cycle.frames(40, Lens.FREE, blurred))
  }

  @Test fun aMoveOrTurnReleasesTheLockAndArmsTheHold() {
    val cycle = TextFocusCycle()
    cycle.frames(20, Lens.FREE, blurred)
    cycle.frames(30, Lens.LOCKED, blurred, atNearLimit = true)
    assertEquals(listOf(Step.Release("phone moved")), cycle.frames(30, Lens.LOCKED, blurred, quiet = false))
    assertTrue(cycle.armed)
    cycle.frames(20, Lens.FREE, blurred)
    assertEquals(Step.Release("phone turned"), cycle.frame(Lens.LOCKED, blurred, turned = true))
    assertTrue(cycle.armed)
  }

  @Test fun aSharpLockStaysAndUnmeasuredFramesChangeNothing() {
    val cycle = TextFocusCycle()
    cycle.frames(20, Lens.FREE, blurred)
    assertEquals(emptyList<Step>(), cycle.frames(50, Lens.LOCKED, sharp))
    assertEquals(emptyList<Step>(), cycle.frames(50, Lens.LOCKED, null))
  }
}
