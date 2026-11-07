package com.margelo.nitro.documentscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureGateTest {
  private val ready = CaptureState(
    nowMs = 0, focusCheckEnabled = true, documentRequired = true, requireFocus = true, focusTimeoutMs = 2_000,
    documentFound = true, focused = true, zslCheckEnabled = true, zslReady = true, lightReady = true, motionCheckEnabled = true, steady = true,
  )

  @Test
  fun publicWaitReasonTracksTheChangingLowQualityControlBlocker() {
    val gate = CaptureGate(1_500)
    val states = listOf(
      ready.copy(documentFound = false, focused = false),
      ready.copy(nowMs = 100, focused = false),
      ready.copy(nowMs = 200, lightReady = false),
      ready.copy(nowMs = 300, steady = false),
    )
    val reasons = states.map { (gate.next(it) as CaptureDecision.Wait).reason.toCaptureWaitReason() }
    assertEquals(listOf(CaptureWaitReason.DOCUMENT, CaptureWaitReason.FOCUS, CaptureWaitReason.LIGHT, CaptureWaitReason.STEADY), reasons)
    assertEquals(CaptureDecision.Shoot, gate.next(ready.copy(nowMs = 400)))
  }

  @Test
  fun perShotOverrideBypassesEveryWaitWithoutChangingTheNextShotsGate() {
    val blocked = ready.copy(documentFound = false, focused = false, zslReady = false, lightReady = false, steady = false)
    // Without a focus scan to wait for, nothing blocks the overridden shot.
    assertEquals(CaptureDecision.Shoot, CaptureGate(1_500, skipLowQualityControl = true).next(blocked.copy(focusCheckEnabled = false)))
    assertEquals(CaptureDecision.Wait(WaitReason.DOCUMENT, 2_000), CaptureGate(1_500).next(blocked))
  }

  @Test
  fun perShotOverrideWaitsOnlyForItsFocusScanToSettle() {
    val gate = CaptureGate(1_500, skipLowQualityControl = true)
    val scanning = ready.copy(documentFound = false, focused = false, zslReady = false, steady = false,
      focusingUntilMs = Long.MAX_VALUE)
    // A pending scan wakes the gate by settling, not by a timer.
    assertEquals(CaptureDecision.Wait(WaitReason.FOCUS, Long.MAX_VALUE), gate.next(scanning))
    assertEquals(CaptureDecision.Shoot, gate.next(scanning.copy(nowMs = 2_000, focused = true, focusingUntilMs = null)))
    assertEquals(CaptureDecision.Shoot, CaptureGate(1_500, skipLowQualityControl = true).next(scanning.copy(focusingUntilMs = null)))
  }

  @Test
  fun autofocusStillMovingOutlastsTheFocusDeadlineUntilItsEvidenceGoesStale() {
    val gate = CaptureGate(1_500)
    val moving = ready.copy(focused = false, focusingUntilMs = 2_300)
    assertEquals(CaptureDecision.Wait(WaitReason.FOCUS, 2_000), gate.next(moving))
    // Past the deadline, the wait wakes when the newest scanning frame turns stale.
    assertEquals(CaptureDecision.Wait(WaitReason.FOCUS, 2_300), gate.next(moving.copy(nowMs = 2_000)))
    assertEquals(CaptureDecision.Wait(WaitReason.FOCUS, 6_400), gate.next(moving.copy(nowMs = 6_000, focusingUntilMs = 6_400)))
    assertEquals(CaptureDecision.Reject(ScannerErrorCode.CAPTURE_FOCUS_TIMEOUT, WaitReason.FOCUS),
      gate.next(moving.copy(nowMs = 6_400)))
    assertEquals(CaptureDecision.Reject(ScannerErrorCode.CAPTURE_FOCUS_TIMEOUT, WaitReason.FOCUS),
      CaptureGate(1_500).apply { next(moving) }.next(moving.copy(nowMs = 2_000, focusingUntilMs = null)))
  }

  @Test
  fun autofocusStillMovingOutlastsTheSteadyDeadlineButAPageNeverDoes() {
    val gate = CaptureGate(1_500)
    assertEquals(CaptureDecision.Wait(WaitReason.STEADY, 1_500), gate.next(ready.copy(steady = false)))
    assertEquals(CaptureDecision.Wait(WaitReason.FOCUS, 1_800),
      gate.next(ready.copy(nowMs = 1_500, focused = false, focusingUntilMs = 1_800)))
    assertEquals(CaptureDecision.Shoot, gate.next(ready.copy(nowMs = 1_700)))
    val searching = CaptureGate(1_500)
    searching.next(ready.copy(documentFound = false, focusingUntilMs = Long.MAX_VALUE))
    assertEquals(CaptureDecision.Reject(ScannerErrorCode.CAPTURE_FOCUS_TIMEOUT, WaitReason.DOCUMENT),
      searching.next(ready.copy(nowMs = 2_000, documentFound = false, focusingUntilMs = Long.MAX_VALUE)))
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
  fun optionalFocusNeverWaivesZeroShutterLagReadiness() {
    val optional = ready.copy(requireFocus = false, focused = false, zslReady = false, motionCheckEnabled = false)
    val gate = CaptureGate(1_500)
    assertEquals(CaptureDecision.Wait(WaitReason.FOCUS, 2_000), gate.next(optional))
    // Focus is waived at its deadline, but the buffer would return an old frame: the shot waits for a ready one.
    assertEquals(CaptureDecision.Wait(WaitReason.LIGHT, 3_500), gate.next(optional.copy(nowMs = 2_000)))
    assertTrue(gate.focusSkipped)
    assertEquals(CaptureDecision.Shoot, gate.next(optional.copy(nowMs = 2_100, zslReady = true)))

    val later = CaptureGate(1_500)
    later.next(ready.copy(requireFocus = false, steady = false))
    assertEquals(CaptureDecision.Reject(ScannerErrorCode.CAPTURE_FAILED, WaitReason.LIGHT), later.next(optional.copy(nowMs = 1_500)))
  }

  @Test
  fun aFailedScanRetriesOnceAfterItsTriggerIsGoneAndMotionNeverStartsAScan() {
    val gate = CaptureGate(1_500)
    val base = ready.copy(focused = false, scanAvailable = true)
    assertEquals(CaptureDecision.StartFocusScan(ScanTrigger.STALE_FOCUS, 1),
      gate.next(base.copy(scanTrigger = ScanTrigger.STALE_FOCUS)))
    // Owning the lens ends the stale focus that triggered the scan; the retry does not need it.
    assertEquals(CaptureDecision.Wait(WaitReason.FOCUS, 2_100), gate.next(base.copy(nowMs = 100, ownScan = OwnScan.SCANNING)))
    assertEquals(CaptureDecision.StartFocusScan(ScanTrigger.STALE_FOCUS, 2),
      gate.next(base.copy(nowMs = 600, ownScan = OwnScan.FAILED)))
    gate.next(base.copy(nowMs = 700, ownScan = OwnScan.SCANNING, steady = false))
    // The second failure is the last scan, however the camera moved.
    assertEquals(CaptureDecision.Wait(WaitReason.FOCUS, 2_100),
      gate.next(base.copy(nowMs = 1_200, ownScan = OwnScan.FAILED, steady = false)))
    assertEquals(2, gate.scans)
  }

  @Test
  fun anAdmittedRescueScanOutlastsThePageDeadlineAndGetsOneFrameAfterItSettles() {
    val missing = ready.copy(documentFound = false, focused = false, scanAvailable = true)
    val gate = CaptureGate(1_500)
    assertEquals(CaptureDecision.StartFocusScan(ScanTrigger.UNSEEN_PAGE, 1),
      gate.next(missing.copy(scanTrigger = ScanTrigger.UNSEEN_PAGE)))
    assertEquals(CaptureDecision.Wait(WaitReason.DOCUMENT, 2_100),
      gate.next(missing.copy(nowMs = 100, ownScan = OwnScan.SCANNING, frameSinceFocusChange = false)))
    assertEquals(CaptureDecision.Wait(WaitReason.DOCUMENT, Long.MAX_VALUE),
      gate.next(missing.copy(nowMs = 2_500, ownScan = OwnScan.SCANNING, frameSinceFocusChange = false)))
    // Locked past the deadline: the first frame exposed after the lock decides.
    val locked = missing.copy(nowMs = 3_000, ownScan = OwnScan.LOCKED, frameSinceFocusChange = false)
    assertEquals(CaptureDecision.Wait(WaitReason.DOCUMENT, 3_500), gate.next(locked))
    assertEquals(CaptureDecision.Shoot, gate.next(ready.copy(nowMs = 3_100, ownScan = OwnScan.LOCKED)))

    val unseen = CaptureGate(1_500)
    unseen.next(missing.copy(scanTrigger = ScanTrigger.UNSEEN_PAGE))
    unseen.next(missing.copy(nowMs = 100, ownScan = OwnScan.SCANNING))
    unseen.next(missing.copy(nowMs = 2_500, ownScan = OwnScan.SCANNING))
    assertEquals(CaptureDecision.Reject(ScannerErrorCode.CAPTURE_FOCUS_TIMEOUT, WaitReason.DOCUMENT),
      unseen.next(missing.copy(nowMs = 2_600, ownScan = OwnScan.LOCKED)))
  }

  @Test
  fun aFirstScanIsAdmittedOnlyBeforeTheFocusDeadlineAndNeverWhenUnavailableOrSkipped() {
    val gate = CaptureGate(1_500)
    val late = ready.copy(focused = false, scanAvailable = true)
    gate.next(late)
    assertEquals(CaptureDecision.Reject(ScannerErrorCode.CAPTURE_FOCUS_TIMEOUT, WaitReason.FOCUS),
      gate.next(late.copy(nowMs = 2_000, scanTrigger = ScanTrigger.DARK)))
    assertEquals(CaptureDecision.Wait(WaitReason.FOCUS, 2_000),
      CaptureGate(1_500).next(late.copy(scanAvailable = false, scanTrigger = ScanTrigger.DARK)))
    assertEquals(CaptureDecision.Shoot,
      CaptureGate(1_500, skipLowQualityControl = true).next(late.copy(focusCheckEnabled = false, scanTrigger = ScanTrigger.DARK)))
  }

  @Test
  fun skippedFocusStillWaitsForTorchAndMotion() {
    val optional = ready.copy(requireFocus = false, focused = false, zslReady = true)
    val torch = CaptureGate(1_500)
    torch.next(optional.copy(lightReady = false))
    assertEquals(CaptureDecision.Wait(WaitReason.LIGHT, 3_500),
      torch.next(optional.copy(nowMs = 2_000, lightReady = false)))
    assertEquals(CaptureDecision.Reject(ScannerErrorCode.CAPTURE_FAILED, WaitReason.LIGHT),
      torch.next(optional.copy(nowMs = 3_500, lightReady = false)))

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
