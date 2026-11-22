package com.margelo.nitro.documentscanner

import android.hardware.camera2.CaptureRequest.CONTROL_AF_TRIGGER_CANCEL
import android.hardware.camera2.CaptureRequest.CONTROL_AF_TRIGGER_START
import android.hardware.camera2.CaptureResult.CONTROL_AF_MODE_AUTO
import android.hardware.camera2.CaptureResult.CONTROL_AF_MODE_CONTINUOUS_PICTURE
import android.hardware.camera2.CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN
import android.hardware.camera2.CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
import android.hardware.camera2.CaptureResult.CONTROL_AF_STATE_INACTIVE
import android.hardware.camera2.CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockFrameWatchTest {
  private val continuous = CONTROL_AF_MODE_CONTINUOUS_PICTURE
  private val single = CONTROL_AF_MODE_AUTO
  private val start = CONTROL_AF_TRIGGER_START

  @Test fun aSweptLockIsReportedOnTheFrameThatShowsIt() {
    val watch = LockFrameWatch()
    watch.arm(1)
    assertEquals(LockFrameWatch.Event.NONE, watch.observe(10, start, single, CONTROL_AF_STATE_INACTIVE))
    assertEquals(LockFrameWatch.Event.NONE, watch.observe(11, null, single, CONTROL_AF_STATE_ACTIVE_SCAN))
    assertFalse(watch.lockReported)
    assertEquals(LockFrameWatch.Event.LOCKED, watch.observe(20, null, single, CONTROL_AF_STATE_FOCUSED_LOCKED))
    assertTrue(watch.lockReported)
    // Later frames of the same lock report nothing new.
    assertEquals(LockFrameWatch.Event.NONE, watch.observe(21, null, single, CONTROL_AF_STATE_FOCUSED_LOCKED))
  }

  @Test fun aTriggerThatFrozeContinuousAutofocusIsLostAndNeverALock() {
    val watch = LockFrameWatch()
    watch.arm(1)
    // As on the SM-G998U: the trigger lands while continuous autofocus still runs and locks the lens where it is.
    assertEquals(LockFrameWatch.Event.NONE, watch.observe(323, start, continuous, CONTROL_AF_STATE_FOCUSED_LOCKED))
    assertFalse(watch.lockReported)
    assertEquals(LockFrameWatch.Event.LOST, watch.observe(324, null, single, CONTROL_AF_STATE_INACTIVE))
    assertFalse(watch.lockReported)
    // The resent trigger sweeps in single-shot autofocus.
    watch.arm(2)
    watch.observe(325, start, single, CONTROL_AF_STATE_INACTIVE)
    watch.observe(326, null, single, CONTROL_AF_STATE_ACTIVE_SCAN)
    assertEquals(LockFrameWatch.Event.LOCKED, watch.observe(340, null, single, CONTROL_AF_STATE_FOCUSED_LOCKED))
  }

  @Test fun aLockFromBeforeTheScanDoesNotCount() {
    val watch = LockFrameWatch()
    watch.observe(1, start, single, CONTROL_AF_STATE_INACTIVE)
    assertEquals(LockFrameWatch.Event.LOCKED, watch.observe(5, null, single, CONTROL_AF_STATE_FOCUSED_LOCKED))
    watch.arm(1)
    // The old lock's frames still in the pipeline, before the new trigger's frame.
    assertEquals(LockFrameWatch.Event.NONE, watch.observe(6, null, single, CONTROL_AF_STATE_FOCUSED_LOCKED))
    assertFalse(watch.lockReported)
  }

  @Test fun continuousFocusIsNeitherLostNorLocked() {
    val watch = LockFrameWatch()
    assertEquals(LockFrameWatch.Event.NONE, watch.observe(1, null, continuous, CONTROL_AF_STATE_PASSIVE_FOCUSED))
    assertEquals(LockFrameWatch.Event.NONE, watch.observe(2, null, single, CONTROL_AF_STATE_INACTIVE))
    assertFalse(watch.lockReported)
  }

  @Test fun aScanStartedBeforeItsTriggerIsNotLostByTheOldScansFrames() {
    val watch = LockFrameWatch()
    watch.arm(1)
    watch.observe(1, start, single, CONTROL_AF_STATE_INACTIVE)
    // A tap replaces the pending scan; frames before its trigger, inactive in single-shot autofocus, are not its loss.
    watch.arm(2)
    assertEquals(LockFrameWatch.Event.NONE, watch.observe(2, null, single, CONTROL_AF_STATE_INACTIVE))
    assertEquals(2L, watch.scan)
  }

  @Test fun aCancelEndsTheAwaitedTrigger() {
    val watch = LockFrameWatch()
    watch.arm(1)
    watch.observe(1, start, single, CONTROL_AF_STATE_INACTIVE)
    assertEquals(LockFrameWatch.Event.NONE, watch.observe(2, CONTROL_AF_TRIGGER_CANCEL, single, CONTROL_AF_STATE_INACTIVE))
    assertEquals(LockFrameWatch.Event.NONE, watch.observe(3, null, single, CONTROL_AF_STATE_INACTIVE))
  }

  @Test fun armingTakesTheScansToken() {
    val watch = LockFrameWatch()
    assertEquals(0L, watch.scan)
    watch.arm(7)
    assertEquals(7L, watch.scan)
  }
}
