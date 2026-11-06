package com.margelo.nitro.documentscanner.processing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DarkScenePolicyTest {
  private val ms = 1_000_000L
  // Exposure x ISO in ms: 100 ms at ISO 1000 is 100,000 (dark), 15 ms at ISO 1000 is 15,000 (a lit room on the SM-G998U).
  private fun DarkScenePolicy.dark(atMs: Long) = observe(atMs * ms, 100 * ms, 1000)
  private fun DarkScenePolicy.bright(atMs: Long) = observe(atMs * ms, 15 * ms, 1000)

  @Test
  fun darknessMustLastASecondEachWay() {
    val p = DarkScenePolicy()
    assertFalse(p.dark(0))
    assertFalse(p.dark(999))
    assertTrue(p.dark(1_000))
    assertTrue(p.dark)
    assertFalse(p.bright(1_100))
    assertTrue(p.bright(2_100))
    assertFalse(p.dark)
  }

  @Test
  fun aBreakRestartsTheSecondAndUnreportedExposureHoldsTheState() {
    val p = DarkScenePolicy()
    p.dark(0)
    p.bright(500)
    assertFalse(p.dark(1_000))
    assertFalse(p.dark(1_500))
    assertTrue(p.dark(2_000))
    assertFalse(p.bright(2_500))
    assertFalse(p.dark(3_000))
    assertFalse(p.bright(3_500))
    assertTrue(p.dark)
    assertFalse(p.observe(4_000 * ms, 15 * ms, null))
    assertFalse(p.bright(4_400))
    assertTrue(p.dark)
  }

  @Test
  fun aStopRestartsPartialEvidenceButKeepsTheEstablishedState() {
    val p = DarkScenePolicy()
    p.dark(0)
    p.interrupt()
    // Resumed two seconds later: the stop's time does not count, so the first dark frame starts a new second.
    assertFalse(p.dark(2_000))
    assertFalse(p.dark(2_999))
    assertTrue(p.dark(3_000))
    p.bright(3_100)
    p.interrupt()
    assertTrue(p.dark)
    assertFalse(p.bright(6_000))
    assertTrue(p.bright(7_000))
  }
}
