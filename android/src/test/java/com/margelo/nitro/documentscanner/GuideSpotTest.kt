package com.margelo.nitro.documentscanner

import com.margelo.nitro.documentscanner.GuideSpot.State
import com.margelo.nitro.documentscanner.processing.SampledSpot
import com.margelo.nitro.documentscanner.processing.SpotRequests
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideSpotTest {
  private val spot = DocumentPoint(.4, .6)
  private var ids = 0L
  private val ask = { ++ids }

  @Test fun anAnsweredRequestPicksTheSpotAndAFailedPickTakesTheCentre() {
    val guide = GuideSpot()
    guide.update(null, SpotRequests(), 100, wanted = true, ask)
    assertEquals(State.Requested(1), guide.state)
    assertFalse(guide.ready)
    // The detector holds the request: it is not lost.
    guide.update(null, SpotRequests(pending = 1), 140, wanted = true, ask)
    assertEquals(State.Requested(1), guide.state)
    guide.update(SampledSpot(1, spot), SpotRequests(taken = 1, takenNs = 180), 180, wanted = true, ask)
    assertEquals(spot, guide.point)
    assertTrue(guide.ready)

    val failed = GuideSpot()
    failed.update(null, SpotRequests(), 100, wanted = true, ask)
    failed.update(SampledSpot(ids, null), SpotRequests(taken = ids, takenNs = 140), 140, wanted = true, ask)
    assertEquals(State.Failed, failed.state)
    assertTrue(failed.ready)
    assertNull(failed.point)
  }

  @Test fun anAnswerStillOnItsWayIsAwaited() {
    val guide = GuideSpot()
    guide.update(null, SpotRequests(), 100, wanted = true, ask)
    // A frame analysed before the one that took the request arrives first.
    guide.update(null, SpotRequests(taken = 1, takenNs = 180), 140, wanted = true, ask)
    assertEquals(State.Requested(1), guide.state)
  }

  @Test fun anAnswerDroppedOnItsWayIsAskedForAgain() {
    val guide = GuideSpot()
    guide.update(null, SpotRequests(), 100, wanted = true, ask)
    // The frame that took the request had its candidate dropped for age; a later frame arrives without the answer.
    guide.update(null, SpotRequests(taken = 1, takenNs = 140), 180, wanted = true, ask)
    assertEquals(State.Requested(2), guide.state)
    // The old answer, should it arrive after all, is not this request's.
    guide.update(SampledSpot(1, spot), SpotRequests(pending = 2, taken = 1, takenNs = 140), 220, wanted = true, ask)
    assertEquals(State.Requested(2), guide.state)
  }

  @Test fun anAnswerDroppedDuringATapIsAskedForAgainAfterIt() {
    val guide = GuideSpot()
    guide.update(null, SpotRequests(), 100, wanted = true, ask)
    // A tap holds the lens: the view cancels, and the answer taken meanwhile is never seen.
    guide.cancel()
    // Once the tap ends, the guide asks afresh.
    guide.update(null, SpotRequests(taken = 1, takenNs = 140), 2_000, wanted = true, ask)
    assertEquals(State.Requested(2), guide.state)
    // Without the cancel, the lost answer is found the same way.
    val uncancelled = GuideSpot()
    uncancelled.update(null, SpotRequests(), 100, wanted = true, ask)
    uncancelled.update(null, SpotRequests(taken = ids, takenNs = 140), 2_000, wanted = true, ask)
    assertEquals(State.Requested(ids), uncancelled.state)
  }

  @Test fun aRequestTheDetectorDroppedUnansweredIsAskedForAgain() {
    val guide = GuideSpot()
    guide.update(null, SpotRequests(), 100, wanted = true, ask)
    // Another request replaced it before any frame took it.
    guide.update(null, SpotRequests(pending = 9), 140, wanted = true, ask)
    assertEquals(State.Requested(2), guide.state)
  }

  @Test fun aPageArrivingWithAGuideRequestPendingKeepsThePagesRequest() {
    // The guide asked; the page's print spot asks after it, replacing it in the detector.
    val requests = SpotRequests().request(1).request(2)
    // The page's restart cancels the guide's request by its id, which leaves the page's in place.
    assertEquals(2L, requests.cancel(1).pending)
    assertEquals(0L, requests.cancel(2).pending)
    // The camera stopping withdraws whatever is pending.
    assertEquals(0L, requests.cancelAll().pending)
    // A frame takes the pending request and records when.
    assertEquals(SpotRequests(0, 2, 500), requests.take(500))
    assertEquals(SpotRequests(), SpotRequests().take(500))
  }

  @Test fun aDelayedAnswerAfterTheHandMovesIsIgnored() {
    val guide = GuideSpot()
    guide.update(null, SpotRequests(), 100, wanted = true, ask)
    // The hand moves: the view cancels the request.
    guide.cancel()
    guide.update(SampledSpot(1, spot), SpotRequests(taken = 1, takenNs = 140), 140, wanted = false, ask)
    assertEquals(State.None, guide.state)
    assertNull(guide.point)
    // Steady again, a new request, and the old answer still does not count.
    guide.update(null, SpotRequests(taken = 1, takenNs = 140), 600, wanted = true, ask)
    assertEquals(State.Requested(2), guide.state)
    guide.update(SampledSpot(1, spot), SpotRequests(pending = 2, taken = 1, takenNs = 140), 640, wanted = true, ask)
    assertEquals(State.Requested(2), guide.state)
  }
}
