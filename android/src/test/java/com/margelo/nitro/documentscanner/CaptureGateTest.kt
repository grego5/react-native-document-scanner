package com.margelo.nitro.documentscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureGateTest {
  private val ready = CaptureState(
    nowMs = 0, focusCheckEnabled = true, documentRequired = true, requireFocus = true, focusTimeoutMs = 2_000,
    documentFound = true, focused = true, zslCheckEnabled = true, zslReady = true, torchReady = true, motionCheckEnabled = true, steady = true,
  )

  @Test
  fun perShotOverrideBypassesEveryWaitWithoutChangingTheNextShotsGate() {
    val blocked = ready.copy(documentFound = false, focused = false, zslReady = false, torchReady = false, steady = false)
    assertEquals(CaptureDecision.Shoot, CaptureGate(1_500, skipQualityGate = true).next(blocked))
    assertEquals(CaptureDecision.Wait(WaitReason.DOCUMENT, 2_000), CaptureGate(1_500).next(blocked))
  }

  @Test
  fun shootsOnTheFirstJudgmentWhereEverythingHolds() {
    assertEquals(CaptureDecision.Shoot, CaptureGate(1_500).next(ready))
  }

  @Test
  fun reportsTheFirstBlockerInOrderUntilTheFocusDeadline() {
    val gate = CaptureGate(1_500)
    assertEquals(CaptureDecision.Wait(WaitReason.DOCUMENT, 2_000),
      gate.next(ready.copy(documentFound = false, focused = false, zslReady = false, steady = false)))
    assertEquals(CaptureDecision.Wait(WaitReason.FOCUS, 2_000), gate.next(ready.copy(nowMs = 500, focused = false)))
    // Focus held: the steady deadline starts there, and later blockers report against it.
    assertEquals(CaptureDecision.Wait(WaitReason.LIGHT, 2_100), gate.next(ready.copy(nowMs = 600, zslReady = false)))
    assertEquals(CaptureDecision.Wait(WaitReason.STEADY, 2_100), gate.next(ready.copy(nowMs = 700, steady = false)))
  }

  @Test
  fun focusThatNeverSettlesRejectsWhenRequired() {
    val gate = CaptureGate(1_500)
    gate.next(ready.copy(focused = false))
    assertEquals(CaptureDecision.Reject(ScannerErrorCode.CAPTURE_FOCUS_TIMEOUT, WaitReason.FOCUS),
      gate.next(ready.copy(nowMs = 2_000, focused = false)))
  }

  @Test
  fun focusLostDuringTheSteadyWaitKeepsTheSameDeadline() {
    val gate = CaptureGate(1_500)
    assertEquals(CaptureDecision.Wait(WaitReason.STEADY, 1_500), gate.next(ready.copy(steady = false)))
    // Losing focus later does not start a fresh focus timeout, nor hit an expired one.
    assertEquals(CaptureDecision.Wait(WaitReason.FOCUS, 1_500), gate.next(ready.copy(nowMs = 1_000, focused = false)))
    assertEquals(CaptureDecision.Reject(ScannerErrorCode.CAPTURE_FOCUS_TIMEOUT, WaitReason.FOCUS),
      gate.next(ready.copy(nowMs = 1_500, focused = false)))
  }

  @Test
  fun withoutRequiredFocusEitherDeadlineWaivesFocusButNotLightOrSteadiness() {
    val optional = ready.copy(requireFocus = false)
    val first = CaptureGate(1_500)
    first.next(optional.copy(focused = false))
    assertEquals(CaptureDecision.Shoot, first.next(optional.copy(nowMs = 2_000, focused = false)))
    assertTrue(first.focusSkipped)

    val later = CaptureGate(1_500)
    later.next(optional)
    assertEquals(CaptureDecision.Wait(WaitReason.DOCUMENT, 1_500), later.next(optional.copy(nowMs = 10, documentFound = false)))
    // At the deadline focus is waived, but a capture that is still moving rejects for motion, not focus.
    assertEquals(CaptureDecision.Reject(ScannerErrorCode.CAPTURE_MOTION_TIMEOUT, WaitReason.STEADY),
      later.next(optional.copy(nowMs = 1_500, documentFound = false, steady = false)))
  }

  @Test
  fun optionalFocusTimeoutSkipsZslInTheSameEvaluation() {
    val optional = ready.copy(requireFocus = false, focused = false, zslReady = false, motionCheckEnabled = false)
    val gate = CaptureGate(1_500)
    assertEquals(CaptureDecision.Wait(WaitReason.FOCUS, 2_000), gate.next(optional))
    assertEquals(CaptureDecision.Shoot, gate.next(optional.copy(nowMs = 2_000)))
    assertTrue(gate.focusSkipped)

    val later = CaptureGate(1_500)
    later.next(ready.copy(requireFocus = false, steady = false))
    assertEquals(CaptureDecision.Shoot, later.next(optional.copy(nowMs = 1_500)))
  }

  @Test
  fun skippedFocusStillWaitsForTorchAndMotion() {
    val optional = ready.copy(requireFocus = false, focused = false, zslReady = false)
    val torch = CaptureGate(1_500)
    torch.next(optional.copy(torchReady = false))
    assertEquals(CaptureDecision.Wait(WaitReason.LIGHT, 3_500),
      torch.next(optional.copy(nowMs = 2_000, torchReady = false)))
    assertEquals(CaptureDecision.Reject(ScannerErrorCode.CAPTURE_FAILED, WaitReason.LIGHT),
      torch.next(optional.copy(nowMs = 3_500, torchReady = false)))

    val motion = CaptureGate(1_500)
    motion.next(optional.copy(steady = false))
    assertEquals(CaptureDecision.Wait(WaitReason.STEADY, 3_500), motion.next(optional.copy(nowMs = 2_000, steady = false)))
  }

  @Test
  fun lightAndMotionKeepTheirErrorCodes() {
    val dark = CaptureGate(1_500)
    dark.next(ready.copy(zslReady = false))
    assertEquals(CaptureDecision.Reject(ScannerErrorCode.CAPTURE_FAILED, WaitReason.LIGHT), dark.next(ready.copy(nowMs = 1_500, zslReady = false)))
    val shaky = CaptureGate(1_500)
    shaky.next(ready.copy(steady = false))
    assertEquals(CaptureDecision.Reject(ScannerErrorCode.CAPTURE_MOTION_TIMEOUT, WaitReason.STEADY),
      shaky.next(ready.copy(nowMs = 1_500, steady = false)))
  }

  @Test
  fun unjudgedFocusAndMotionDoNotWait() {
    val gate = CaptureGate(1_500)
    assertEquals(CaptureDecision.Shoot,
      gate.next(ready.copy(focusCheckEnabled = false, focused = false, documentFound = false, motionCheckEnabled = false, steady = false)))
    assertFalse(gate.focusSkipped)
  }

  @Test
  fun aZeroFocusTimeoutJudgesFocusAtThePressAlone() {
    assertEquals(CaptureDecision.Reject(ScannerErrorCode.CAPTURE_FOCUS_TIMEOUT, WaitReason.FOCUS),
      CaptureGate(1_500).next(ready.copy(focusTimeoutMs = 0, focused = false)))
  }
}
