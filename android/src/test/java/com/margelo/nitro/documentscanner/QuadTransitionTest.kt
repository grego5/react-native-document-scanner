package com.margelo.nitro.documentscanner

import org.junit.Assert.*
import org.junit.Test

class QuadTransitionTest {
  private fun quad(x: Float) = floatArrayOf(x, 0f, x + 100f, 0f, x + 100f, 100f, x, 100f)

  @Test fun firstResultIsImmediateAndSmallMovementSettlesWithinFiftyMilliseconds() {
    val transition = QuadTransition()
    transition.retarget(quad(0f), 0, 200f)
    assertTrue(transition.hasPoints)
    assertFalse(transition.isAnimating)
    assertArrayEquals(quad(0f), transition.points, 0f)
    transition.retarget(quad(20f), 10_000_000, 200f)
    transition.advance(35_000_000)
    assertArrayEquals(quad(10f), transition.points, .001f)
    assertTrue(transition.isAnimating)
    transition.advance(60_000_000)
    assertArrayEquals(quad(20f), transition.points, 0f)
    assertFalse(transition.isAnimating)
  }

  @Test fun durationIsConfigurableAndZeroSnaps() {
    val transition = QuadTransition()
    transition.durationNs = 16_000_000
    transition.retarget(quad(0f), 0, 200f)
    transition.retarget(quad(20f), 0, 200f)
    transition.advance(8_000_000)
    assertArrayEquals(quad(10f), transition.points, .001f)
    transition.durationNs = 0
    transition.retarget(quad(40f), 8_000_000, 200f)
    assertFalse(transition.isAnimating)
    assertArrayEquals(quad(40f), transition.points, 0f)
  }

  @Test fun newResultRetargetsFromDisplayedPositionWithoutJumping() {
    val transition = QuadTransition()
    transition.retarget(quad(0f), 0, 200f)
    transition.retarget(quad(20f), 0, 200f)
    transition.advance(25_000_000)
    transition.retarget(quad(30f), 25_000_000, 200f)
    assertArrayEquals(quad(10f), transition.points, .001f)
    transition.advance(50_000_000)
    assertArrayEquals(quad(20f), transition.points, .001f)
    transition.advance(75_000_000)
    assertArrayEquals(quad(30f), transition.points, 0f)
  }

  @Test fun largeJumpInterruptsAnimationAndSnapsToNewDocument() {
    val transition = QuadTransition()
    transition.retarget(quad(0f), 0, 200f)
    transition.retarget(quad(20f), 0, 200f)
    transition.advance(25_000_000)
    transition.retarget(quad(400f), 25_000_000, 200f)
    transition.advance(100_000_000)
    assertArrayEquals(quad(400f), transition.points, 0f)
    assertFalse(transition.isAnimating)
  }

  @Test fun clearCancelsPendingMovementAndNextResultAppearsImmediately() {
    val transition = QuadTransition()
    transition.retarget(quad(0f), 0, 200f)
    transition.retarget(quad(20f), 0, 200f)
    transition.clear()
    transition.advance(100_000_000)
    assertFalse(transition.hasPoints)
    assertFalse(transition.isAnimating)
    transition.retarget(quad(5f), 100_000_000, 200f)
    assertArrayEquals(quad(5f), transition.points, 0f)
    assertFalse(transition.isAnimating)
  }

  @Test fun stationaryResultsDoNotScheduleAnimationOrMutateInput() {
    val transition = QuadTransition()
    val input = quad(0f)
    transition.retarget(input, 0, 200f)
    transition.retarget(input, 1, 200f)
    assertFalse(transition.isAnimating)
    transition.retarget(quad(20f), 2, 200f)
    transition.advance(25_000_002)
    assertArrayEquals(quad(0f), input, 0f)
  }
}
