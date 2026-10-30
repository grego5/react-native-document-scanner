package com.margelo.nitro.documentscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureFeedbackTimelineTest {
  @Test
  fun shootingLineSlowsBelowItsCapUntilTheShotIsExposed() {
    val atEstimate = CaptureFeedbackTimeline.shootingSweep(CaptureFeedbackTimeline.SHOOT_ESTIMATE_MS)
    assertEquals(0.76f, atEstimate, 0.02f)
    val late = CaptureFeedbackTimeline.shooting(5_000.0)
    assertTrue(late.sweep < CaptureFeedbackTimeline.SWEEP_CAP + 1e-6f)
    assertFalse(late.finished)
  }

  @Test
  fun capturedClosesTheLineFromWhereItStoppedThenFades() {
    val start = CaptureFeedbackTimeline.captured(0.0, 0.5f)
    assertEquals(0.5f, start.sweep, 1e-6f)
    val closed = CaptureFeedbackTimeline.captured(CaptureFeedbackTimeline.CLOSE_MS, 0.5f)
    assertEquals(1f, closed.sweep, 1e-6f)
    assertEquals(0.3f, closed.fillAlpha, 1e-6f)
    val end = CaptureFeedbackTimeline.CLOSE_MS + CaptureFeedbackTimeline.FADE_MS
    assertTrue(CaptureFeedbackTimeline.captured(end, 0.5f).finished)
  }

  @Test
  fun waitingPulsesWithoutEnding() {
    assertEquals(1f, CaptureFeedbackTimeline.waiting(0.0).outlineAlpha, 1e-6f)
    assertEquals(0.45f, CaptureFeedbackTimeline.waiting(CaptureFeedbackTimeline.PULSE_MS / 2).outlineAlpha, 1e-6f)
    assertFalse(CaptureFeedbackTimeline.waiting(60_000.0).finished)
  }

  @Test
  fun failureBlinksThenFinishes() {
    val blink = CaptureFeedbackTimeline.FAIL_BLINK_MS
    assertEquals(1f, CaptureFeedbackTimeline.failed(0.0).outlineAlpha, 1e-6f)
    assertEquals(0.25f, CaptureFeedbackTimeline.failed(blink * 1.5).outlineAlpha, 1e-6f)
    val end = blink * 2 * CaptureFeedbackTimeline.FAIL_BLINKS + CaptureFeedbackTimeline.FADE_MS
    assertTrue(CaptureFeedbackTimeline.failed(end).finished)
  }
}
