package com.margelo.nitro.documentscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextZoomTest {
  private val zoom = TextZoom()

  @Test fun theFirstRequestReadsNothingUntilAnsweredAndSeen() {
    val first = zoom.request(2f)!!
    assertEquals(Long.MAX_VALUE, zoom.readFromNs)
    assertTrue(zoom.settle(first, fallbackFromNs = null))
    // Answered, but the view's capture results have not shown the ratio yet.
    assertNull(zoom.settled)
    assertEquals(Long.MAX_VALUE, zoom.readFromNs)
    assertTrue(zoom.seen(first.id, 500))
    assertEquals(2f, zoom.settled!!, 0f)
    assertEquals(500L, zoom.readFromNs)
    assertNull(zoom.request(2f))
  }

  @Test fun aCaptureResultSeenBeforeTheAnswerSettlesOnTheAnswer() {
    val first = zoom.request(2f)!!
    assertTrue(zoom.seen(first.id, 400))
    assertFalse(zoom.seen(first.id, 450))
    assertNull(zoom.settled)
    assertTrue(zoom.settle(first, fallbackFromNs = 900))
    assertEquals(400L, zoom.readFromNs)
  }

  @Test fun withoutZoomInCaptureResultsTheFallbackBoundaryApplies() {
    val first = zoom.request(1.5f)!!
    assertTrue(zoom.settle(first, fallbackFromNs = 700))
    assertEquals(1.5f, zoom.settled!!, 0f)
    assertEquals(700L, zoom.readFromNs)
  }

  @Test fun onlyTheNewestOfQuickRequestsForOneRatioSettles() {
    val a = zoom.request(1.5f)!!
    val b = zoom.request(2f)!!
    val c = zoom.request(1.5f)!!
    assertFalse(zoom.seen(a.id, 100))
    assertFalse(zoom.settle(a, null))
    assertFalse(zoom.settle(b, null))
    assertEquals(Long.MAX_VALUE, zoom.readFromNs)
    assertTrue(zoom.seen(c.id, 300))
    assertTrue(zoom.settle(c, null))
    assertEquals(1.5f, zoom.settled!!, 0f)
  }

  @Test fun aRefusedRatioKeepsWhatCaptureResultsShowAndIsNotAskedAgain() {
    val refused = zoom.request(8f)!!
    assertTrue(zoom.refuse(refused, kept = 1f, fromNs = 50))
    assertEquals(1f, zoom.settled!!, 0f)
    assertEquals(50L, zoom.readFromNs)
    assertNull(zoom.request(8f))
  }

  @Test fun aRefusalCaptureResultsCannotTellKeepsTheLastConfirmedRatio() {
    val first = zoom.request(2f)!!
    zoom.settle(first, fallbackFromNs = 10)
    val refused = zoom.request(8f)!!
    assertEquals(2f, zoom.lastConfirmed!!, 0f)
    assertNull(zoom.settled)
    assertTrue(zoom.refuse(refused, kept = null, fromNs = 60))
    assertEquals(2f, zoom.settled!!, 0f)
  }

  @Test fun anAnswerAfterAResetSettlesNothing() {
    val old = zoom.request(2f)!!
    zoom.reset()
    assertFalse(zoom.settle(old, 10))
    assertFalse(zoom.seen(old.id, 10))
    assertNull(zoom.settled)
    assertTrue(zoom.request(2f) != null)
  }
}
