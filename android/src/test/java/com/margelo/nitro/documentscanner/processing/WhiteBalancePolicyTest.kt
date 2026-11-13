package com.margelo.nitro.documentscanner.processing

import android.hardware.camera2.CaptureResult.CONTROL_AE_STATE_CONVERGED
import android.hardware.camera2.CaptureResult.CONTROL_AE_STATE_SEARCHING
import android.hardware.camera2.CaptureResult.CONTROL_AWB_STATE_CONVERGED
import android.hardware.camera2.CaptureResult.CONTROL_AWB_STATE_SEARCHING
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhiteBalancePolicyTest {
  private val ms = 1_000_000L
  private fun WhiteBalancePolicy.searching(atMs: Long) =
    observe(atMs * ms, CONTROL_AE_STATE_CONVERGED, CONTROL_AWB_STATE_SEARCHING)
  private fun WhiteBalancePolicy.converged(atMs: Long) =
    observe(atMs * ms, CONTROL_AE_STATE_CONVERGED, CONTROL_AWB_STATE_CONVERGED)

  @Test
  fun searchingMustLastASecondEachWay() {
    val p = WhiteBalancePolicy()
    assertFalse(p.searching(0))
    assertFalse(p.searching(999))
    assertTrue(p.searching(1_000))
    assertTrue(p.unsettled)
    assertFalse(p.converged(1_100))
    assertTrue(p.converged(2_100))
    assertFalse(p.unsettled)
  }

  @Test
  fun exposureStillMovingOrAnUnreportedStateRestartsTheSecond() {
    val p = WhiteBalancePolicy()
    p.searching(0)
    assertFalse(p.observe(500 * ms, CONTROL_AE_STATE_SEARCHING, CONTROL_AWB_STATE_SEARCHING))
    assertFalse(p.searching(1_000))
    assertFalse(p.observe(1_500 * ms, CONTROL_AE_STATE_CONVERGED, null))
    assertFalse(p.searching(2_000))
    assertFalse(p.converged(2_500))
    assertFalse(p.searching(2_900))
    assertFalse(p.searching(3_899))
    assertTrue(p.searching(3_900))
  }

  @Test
  fun aStopRestartsPartialEvidenceButKeepsTheEstablishedState() {
    val p = WhiteBalancePolicy()
    p.searching(0)
    p.interrupt()
    assertFalse(p.searching(2_000))
    assertFalse(p.searching(2_999))
    assertTrue(p.searching(3_000))
    p.converged(3_100)
    p.interrupt()
    assertTrue(p.unsettled)
    assertFalse(p.converged(6_000))
    assertTrue(p.converged(7_000))
  }
}
