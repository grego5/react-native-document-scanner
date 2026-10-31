package com.margelo.nitro.documentscanner.processing

import com.margelo.nitro.documentscanner.DocumentPoint
import com.margelo.nitro.documentscanner.DocumentQuad
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import androidx.test.platform.app.InstrumentationRegistry
import com.facebook.soloader.SoLoader
import com.margelo.nitro.JNIOnLoad

class DocumentFocusPolicyTest {
  @Before fun initializeNative() {
    SoLoader.init(InstrumentationRegistry.getInstrumentation().targetContext, false)
    JNIOnLoad.initializeNativeNitro()
  }
  private val page = rect(.2, .2, .8, .8)

  @Test fun nativePolicyMatchesFormerPolicyAcrossRecordedValueSequences() {
    DocumentFocusPolicy().use { actual ->
      val expected = ReferenceDocumentFocusPolicy()
      for (i in 0 until 500) {
        val now = i * 73L
        if (i % 67 == 0) { actual.reset(); expected.reset() }
        if (i % 9 == 0) { actual.settled((i / 9) % 3 != 0, now); expected.settled((i / 9) % 3 != 0, now) }
        val q = when (i % 11) {
          0, 1 -> null
          2 -> rect(.4,.4,.5,.5)
          3, 4 -> rect(.16,.16,.84,.84)
          5, 6 -> rect(.24,.24,.76,.76)
          else -> page
        }
        val trusted = i % 4 != 0
        val failed = i % 20 >= 10
        val blocked = i % 37 == 0
        val parked = i % 8 < 4
        assertEquals("row $i", expected.observe(q,trusted,failed,now,blocked,parked),
          actual.observe(q,trusted,failed,now,blocked,parked))
      }
    }
  }
  @Test fun nativeOwnershipDeadlinesAndUnknownCompletionsCrossWithoutClockOrBooleanCoercion() {
    NativeFocus().use { p ->
      val time = 9_007_199_254_740_993L
      val tap = p.startNative(0, time)
      assertEquals(time + 5_000_000_000, p.deadlinesNative(time)[0])
      assertTrue(p.completeNative(tap, -1, time + 1))
      assertEquals(-1L, p.snapshotNative()[2])
      assertEquals(0L, p.snapshotNative()[5])
      assertFalse(p.completeNative(tap, 1, time + 2))
      assertFalse(p.expireTapNative(tap, time + 5_000_000_000, true))
      assertTrue(p.afterCaptureNative(time + 5_000_000_000, true))
      val generation = p.snapshotNative()[0]
      p.unbindNative()
      assertTrue(p.snapshotNative()[0] > generation)
      assertFalse(p.completeNative(tap, 1, time + 6_000_000_000))
    }
  }
  @Test fun learnedPositionSurvivesPolicyRecreationInAdapterSelectedUnits() {
    val previous = LearnedFocusPosition.value()
    try {
      LearnedFocusPosition.learn(2.25)
      NativeFocus().use { it.unbindNative() }
      assertEquals(2.25, LearnedFocusPosition.value()!!, 0.0)
      LearnedFocusPosition.learn(Double.NaN)
      assertNull(LearnedFocusPosition.value())
    } finally { LearnedFocusPosition.learn(previous) }
  }
  @Test fun fixedFocusAndEdofCamerasDoNotWaitForAScan() {
    assertFalse(hasAdjustableAutofocus(null))
    assertFalse(hasAdjustableAutofocus(intArrayOf(0, 5))) // OFF, EDOF.
    assertTrue(hasAdjustableAutofocus(intArrayOf(0, 1, 4))) // AUTO, CONTINUOUS_PICTURE.
  }

  @Test fun aLensParksOnlyWithManualFocusAndAKnownRange() {
    val modes = intArrayOf(0, 1, 4) // OFF, AUTO, CONTINUOUS_PICTURE.
    val manual = intArrayOf(0, 1) // BACKWARD_COMPATIBLE, MANUAL_SENSOR.
    assertTrue(canParkFocus(modes, manual, 10f))
    assertFalse(canParkFocus(intArrayOf(1, 4), manual, 10f))
    assertFalse(canParkFocus(modes, intArrayOf(0), 10f))
    assertFalse(canParkFocus(modes, manual, 0f))
    assertFalse(canParkFocus(modes, manual, null))
    assertFalse(canParkFocus(intArrayOf(0), manual, 10f)) // Fixed focus has nothing to scan.
  }

  @Test fun theParkedDistanceIsTheLastLockWithinTheLensRange() {
    assertEquals(DOCUMENT_PARK_DIOPTERS, parkDiopters(null, 10f), 0f)
    assertEquals(2f, parkDiopters(null, 2f), 0f)
    assertEquals(5f, parkDiopters(5f, 10f), 0f)
    assertEquals(10f, parkDiopters(12f, 10f), 0f)
    assertEquals(DOCUMENT_PARK_DIOPTERS, parkDiopters(Float.NaN, 10f), 0f)
  }

  @Test fun aParkedLensWaitsForAConfirmedGuideBeforeItsFirstScan() {
    val policy = DocumentFocusPolicy()
    repeat(3) { assertEquals(DocumentFocusAction.NONE, observe(policy, page, it * 33L, parked = true)) }
    assertEquals(DocumentFocusAction.START, observe(policy, page, 100, trusted = true, parked = true))
    assertEquals(DocumentFocusAction.NONE, observe(policy, page, 133, trusted = true, parked = true))
  }

  @Test fun onePlausibleProvisionalDetectionStartsOnlyOneScan() {
    val policy = DocumentFocusPolicy()
    assertEquals(DocumentFocusAction.NONE, observe(policy, null, 0))
    assertEquals(DocumentFocusAction.NONE, observe(policy, rect(.4, .4, .5, .5), 10))
    assertEquals(DocumentFocusAction.START, observe(policy, page, 20))
    repeat(20) { assertEquals(DocumentFocusAction.NONE, observe(policy, page, 100L + it * 66)) }
  }

  @Test fun firstTrustedGeometryReplacesTheRoughReferenceWithoutRefocusing() {
    val policy = DocumentFocusPolicy()
    observe(policy, rect(.1, .1, .9, .9), 0)
    policy.settled(true, 500)
    assertEquals(DocumentFocusAction.NONE, observe(policy, page, 600, trusted = true))
    repeat(20) { assertEquals(DocumentFocusAction.NONE, observe(policy, page, 700L + it * 66, trusted = true)) }
  }

  @Test fun sidewaysMovementAndInPlaneRotationKeepTheLock() {
    val policy = locked()
    assertEquals(DocumentFocusAction.NONE, observe(policy, rect(.3, .15, .9, .75), 2000, trusted = true))
    val diamond = DocumentQuad(DocumentPoint(.5, .0757359313), DocumentPoint(.9242640687, .5),
      DocumentPoint(.5, .9242640687), DocumentPoint(.0757359313, .5))
    repeat(4) { assertEquals(DocumentFocusAction.NONE, observe(policy, diamond, 2100L + it * 66, trusted = true)) }
  }

  @Test fun twoTrustedScaleChangesRefocusWithoutHoldingAStaleDistanceForTheCooldown() {
    for (changed in listOf(rect(.16, .16, .84, .84), rect(.24, .24, .76, .76))) {
      val policy = locked()
      assertEquals(DocumentFocusAction.NONE, observe(policy, changed, 700, trusted = true))
      assertEquals(DocumentFocusAction.START, observe(policy, changed, 766, trusted = true))
      assertEquals(DocumentFocusAction.NONE, observe(policy, changed, 832, trusted = true))
    }
  }

  @Test fun provisionalOutliersNeverChangeTheLockedScaleReference() {
    val policy = locked()
    repeat(10) { assertEquals(DocumentFocusAction.NONE, observe(policy, rect(.1, .1, .9, .9), 1600L + it * 66)) }
    assertEquals(DocumentFocusAction.NONE, observe(policy, page, 2400, trusted = true))
  }

  @Test fun observedTargetLossReleasesTheLockButBriefMissesDoNot() {
    val policy = locked()
    assertEquals(DocumentFocusAction.NONE, observe(policy, null, 1200))
    assertEquals(DocumentFocusAction.NONE, observe(policy, page, 1300, trusted = true))
    assertEquals(DocumentFocusAction.NONE, observe(policy, null, 2200))
    assertEquals(DocumentFocusAction.RELEASE, observe(policy, null, 2300))
    assertEquals(DocumentFocusAction.START, observe(policy, page, 2400))
  }

  @Test fun failedScansRetryWithACooldownAndPersistentFailedFocusCanRecover() {
    val policy = DocumentFocusPolicy()
    observe(policy, page, 0)
    policy.settled(false, 500)
    assertEquals(DocumentFocusAction.NONE, observe(policy, page, 1499))
    assertEquals(DocumentFocusAction.START, observe(policy, page, 1500))
    policy.settled(true, 2000)
    assertEquals(DocumentFocusAction.NONE, observe(policy, page, 2600, failed = true))
    assertEquals(DocumentFocusAction.START, observe(policy, page, 3100, failed = true))
  }

  @Test fun tapsAndCapturesBlockAutomaticActionsAndResetDropsOldCompletion() {
    val policy = locked()
    assertEquals(DocumentFocusAction.NONE, observe(policy, null, 5000, blocked = true))
    assertEquals(DocumentFocusAction.NONE, observe(policy, rect(.1, .1, .9, .9), 5100, trusted = true, blocked = true))
    policy.reset()
    policy.settled(true, 5200) // Completion from before the reset has no pending request to settle.
    assertEquals(DocumentFocusAction.NONE, observe(policy, page, 5300, blocked = true))
    assertEquals(DocumentFocusAction.START, observe(policy, page, 5400))
  }

  private fun locked() = DocumentFocusPolicy().apply {
    observe(this, page, 0)
    settled(true, 500)
    observe(this, page, 600, trusted = true)
  }

  private fun observe(policy: DocumentFocusPolicy, quad: DocumentQuad?, time: Long, trusted: Boolean = false,
    failed: Boolean = false, blocked: Boolean = false, parked: Boolean = false,
  ) = policy.observe(quad, trusted, failed, time, blocked, acquireTrusted = parked)

  private fun rect(l: Double, t: Double, r: Double, b: Double) = DocumentQuad(
    DocumentPoint(l, t), DocumentPoint(r, t), DocumentPoint(r, b), DocumentPoint(l, b))
}
