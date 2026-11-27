package com.margelo.nitro.documentscanner

import com.margelo.nitro.documentscanner.FocusCycle.Lens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextFocusCycleTest {
  private val cycle = TextFocusCycle()
  private val sharp = 2.0
  private val blurred = 4.0
  private var now = 0L

  private fun frame(lens: Lens, edges: Double?, quiet: Boolean = true, turned: Boolean = false,
    generation: Long = 1,
  ): Boolean {
    now += 33_000_000L
    return cycle.wantsScan(now, quiet, turned, lens, generation, edges)
  }

  @Test fun theFirstSteadyHoldScansEvenWithSharpOrUnmeasurableText() {
    assertFalse(frame(Lens.FREE, null, quiet = false))
    assertTrue(frame(Lens.FREE, sharp))
    assertEquals("initial steady hold", cycle.started())
    assertFalse(frame(Lens.SCANNING, null))
    assertTrue(TextFocusCycle().wantsScan(now, true, false, Lens.FREE, 1, null))
  }

  @Test fun steadyBlurAfterTheFirstAttemptDoesNotRetry() {
    cycle.started()
    repeat(100) { assertFalse(frame(Lens.LOCKED, blurred)) }
    assertFalse(cycle.verified)
    cycle.failed()
    repeat(100) { assertFalse(frame(Lens.FREE, sharp)) }
  }

  @Test fun steadySharpFramesVerifyOnlyTheirOwnLock() {
    cycle.started()
    repeat(BlurWatch.LEAVE - 1) { assertFalse(frame(Lens.LOCKED, sharp)) }
    assertFalse(cycle.verified)
    assertFalse(frame(Lens.LOCKED, sharp))
    assertTrue(cycle.verified)
    assertFalse(frame(Lens.LOCKED, sharp, generation = 2))
    assertFalse(cycle.verified)
  }

  @Test fun aTurnKeepsTheLockUntilSteadyEnoughToReplaceIt() {
    cycle.started()
    assertFalse(frame(Lens.LOCKED, sharp, quiet = false, turned = true))
    assertFalse(frame(Lens.LOCKED, sharp, quiet = false, turned = true))
    assertTrue(frame(Lens.LOCKED, sharp))
    assertEquals("phone turned", cycle.started())
    assertFalse(frame(Lens.LOCKED, sharp, generation = 2))
  }

  @Test fun lossOfSharpnessAllowsOneCorrectionUntilTheViewChanges() {
    cycle.started()
    repeat(BlurWatch.LEAVE) { frame(Lens.LOCKED, sharp) }
    repeat(BlurWatch.ENTER - 1) { assertFalse(frame(Lens.LOCKED, blurred)) }
    assertTrue(frame(Lens.LOCKED, blurred))
    assertEquals("previously sharp print defocused", cycle.started())
    repeat(BlurWatch.LEAVE) { frame(Lens.LOCKED, sharp, generation = 2) }
    repeat(BlurWatch.ENTER) { assertFalse(frame(Lens.LOCKED, blurred, generation = 2)) }
    cycle.sceneChanged()
    assertTrue(frame(Lens.LOCKED, blurred, generation = 2))
    assertEquals("text view changed", cycle.started())
  }
}
