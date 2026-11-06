package com.margelo.nitro.documentscanner.processing

import org.junit.Assert.*
import org.junit.Test

class LiveDetectionStateTest {
  @Test
  fun throttlesSchedulingAndInvalidatesResultsAcrossPauseLayoutAndStop() {
    val state = LiveDetectionState()
    assertNull(state.acquire(0))
    state.begin(true)
    val first = state.acquire(0)!!
    assertNull(state.acquire(59_999_999))
    assertEquals(first, state.acquire(60_000_000))
    state.setEnabled(false)
    assertFalse(state.isCurrent(first))
    assertNull(state.acquire(300_000_000))
    state.setEnabled(true)
    val resumed = state.acquire(300_000_000)!!
    state.invalidate()
    assertFalse(state.isCurrent(resumed))
    val resized = state.acquire(300_000_001)!!
    state.stop()
    assertFalse(state.isCurrent(resized))
    assertNull(state.acquire(500_000_000))
    state.dispose()
    assertThrows(IllegalStateException::class.java) { state.begin(true) }
  }

  @Test
  fun replacementModelRecoversFailureAndRejectsOldResults() {
    val state = LiveDetectionState()
    state.begin(true)
    val old = state.acquire(0)!!
    assertTrue(state.fail(old))
    state.modelChanged()
    assertFalse(state.hasFailed())
    assertFalse(state.isCurrent(old))
    assertFalse(state.fail(old))
    assertNotNull(state.acquire(1))
    state.stop()
    state.modelChanged()
    assertNull(state.acquire(2))
  }

  @Test
  fun acquisitionGapIsCallerChosen() {
    val state = LiveDetectionState()
    state.begin(true)
    val first = state.acquire(0, 0)!!
    assertEquals(first, state.acquire(1, 0))
    assertNull(state.acquire(2))
    assertEquals(first, state.acquire(1 + LiveDetectionState.TRACKING_GAP_NS))
  }

  @Test
  fun failureRecoveryRequiresRebindAndStaleErrorsCannotPoisonIt() {
    val state = LiveDetectionState()
    state.begin(true)
    val failed = state.acquire(0)!!
    assertTrue(state.fail(failed))
    assertNull(state.acquire(500_000_000))
    state.setEnabled(false)
    state.setEnabled(true)
    assertNull(state.acquire(1_000_000_000))
    state.stop()
    state.begin(true)
    val rebound = state.acquire(2_000_000_000)!!
    assertFalse(state.fail(failed))
    assertFalse(state.hasFailed())
    assertTrue(state.isCurrent(rebound))
  }
}
