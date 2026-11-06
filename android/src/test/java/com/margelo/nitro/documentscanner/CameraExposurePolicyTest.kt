package com.margelo.nitro.documentscanner

import org.junit.Assert.*
import org.junit.Test

class CameraExposurePolicyTest {
  private data class Fps(val lower: Int, val upper: Int)
  private fun pick(minFps: Int, vararg ranges: Fps) =
    CameraExposurePolicy.targetFpsRange(ranges.toList(), minFps, { it.lower }, { it.upper })

  @Test
  fun compensationRoundsToTheStepAndClampsToTheRange() {
    assertEquals(-2, CameraExposurePolicy.compensationIndex(-0.7, 1.0 / 3, -12, 12))
    assertEquals(1, CameraExposurePolicy.compensationIndex(0.5, 0.5, -4, 4))
    assertEquals(-4, CameraExposurePolicy.compensationIndex(-5.0, 0.5, -4, 4))
    assertEquals(0, CameraExposurePolicy.compensationIndex(-1.0, 0.5, 0, 0))
    assertEquals(0, CameraExposurePolicy.compensationIndex(Double.NaN, 0.5, -4, 4))
  }

  @Test
  fun lowestMinimumReachingTheRequestWinsWithItsMaximumNearest30() {
    val ranges = arrayOf(Fps(15, 30), Fps(24, 24), Fps(24, 30), Fps(30, 30), Fps(30, 60))
    assertEquals(Fps(24, 30), pick(20, *ranges))
    assertEquals(Fps(30, 30), pick(30, *ranges))
    assertEquals(Fps(15, 30), pick(10, *ranges))
  }

  @Test
  fun highestMinimumStandsInWhenNoneReachesAndZeroKeepsTheCameraDefault() {
    assertEquals(Fps(24, 30), pick(60, Fps(15, 30), Fps(24, 30), Fps(7, 30)))
    assertNull(pick(0, Fps(15, 30)))
    assertNull(pick(30))
  }
}
