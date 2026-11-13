package com.margelo.nitro.documentscanner

import org.junit.Assert.*
import org.junit.Test

class GuideTransitionTest {
  private fun corners(x: Float) = floatArrayOf(x, 0f, x + 100f, 0f, x + 100f, 100f, x, 100f)
  private val frameNs = 16_000_000L

  /** The share of the gap one frame closes, from a guide at 0 toward `x`. */
  private fun firstFrameShare(x: Float): Float {
    val transition = GuideTransition()
    transition.retarget(corners(0f), 0, 200f)
    transition.retarget(corners(x), 0, 200f)
    transition.advance(frameNs)
    return transition.points[0] / x
  }

  @Test fun firstResultIsImmediateAndTheGuideLandsExactlyOnItsTarget() {
    val transition = GuideTransition()
    transition.retarget(corners(0f), 0, 200f)
    assertTrue(transition.hasPoints)
    assertFalse(transition.isAnimating)
    assertArrayEquals(corners(0f), transition.points, 0f)
    transition.retarget(corners(20f), 0, 200f)
    assertTrue(transition.isAnimating)
    transition.advance(frameNs)
    assertTrue(transition.points[0] in 0f..20f)
    var now = frameNs
    while (transition.isAnimating && now < 2_000_000_000L) { now += frameNs; transition.advance(now) }
    assertFalse(transition.isAnimating)
    assertArrayEquals(corners(20f), transition.points, 0f)
  }

  @Test fun smallGapsAreSmoothedAndLargeOnesFollowedFast() {
    // A 4 px jitter keeps the 80 ms time constant; a 90 px move of a 200 px snap distance nears the 20 ms one.
    val jitter = firstFrameShare(4f)
    val move = firstFrameShare(90f)
    assertEquals(1 - kotlin.math.exp(-16.0 / 80).toFloat(), jitter, .001f)
    assertTrue("a large move closes more of its gap per frame", move > jitter * 2)
  }

  @Test fun zeroDurationSnaps() {
    val transition = GuideTransition()
    transition.durationNs = 0
    transition.retarget(corners(0f), 0, 200f)
    transition.retarget(corners(40f), 1, 200f)
    assertFalse(transition.isAnimating)
    assertArrayEquals(corners(40f), transition.points, 0f)
  }

  @Test fun newResultRetargetsFromDisplayedPositionWithoutJumping() {
    val transition = GuideTransition()
    transition.retarget(corners(0f), 0, 200f)
    transition.retarget(corners(20f), 0, 200f)
    transition.advance(frameNs)
    val drawn = transition.points.copyOf()
    transition.retarget(corners(30f), frameNs, 200f)
    assertArrayEquals(drawn, transition.points, 0f)
    transition.advance(2 * frameNs)
    assertTrue(transition.points[0] > drawn[0])
  }

  @Test fun largeJumpInterruptsAnimationAndSnapsToNewDocument() {
    val transition = GuideTransition()
    transition.retarget(corners(0f), 0, 200f)
    transition.retarget(corners(20f), 0, 200f)
    transition.advance(frameNs)
    transition.retarget(corners(400f), frameNs, 200f)
    assertArrayEquals(corners(400f), transition.points, 0f)
    assertFalse(transition.isAnimating)
  }

  @Test fun clearCancelsPendingMovementAndNextResultAppearsImmediately() {
    val transition = GuideTransition()
    transition.retarget(corners(0f), 0, 200f)
    transition.retarget(corners(20f), 0, 200f)
    transition.clear()
    transition.advance(100_000_000)
    assertFalse(transition.hasPoints)
    assertFalse(transition.isAnimating)
    transition.retarget(corners(5f), 100_000_000, 200f)
    assertArrayEquals(corners(5f), transition.points, 0f)
    assertFalse(transition.isAnimating)
  }

  @Test fun stationaryResultsDoNotScheduleAnimationOrMutateInput() {
    val transition = GuideTransition()
    val input = corners(0f)
    transition.retarget(input, 0, 200f)
    transition.retarget(input, 1, 200f)
    assertFalse(transition.isAnimating)
    transition.retarget(corners(20f), 2, 200f)
    transition.advance(frameNs)
    assertArrayEquals(corners(0f), input, 0f)
  }
}
