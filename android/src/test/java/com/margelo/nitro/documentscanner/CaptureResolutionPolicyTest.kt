package com.margelo.nitro.documentscanner

import org.junit.Assert.*
import org.junit.Test

class CaptureResolutionPolicyTest {
  private data class Dimensions(val width: Int, val height: Int)
  private fun rank(vararg sizes: Dimensions) = CaptureResolutionPolicy.rank(sizes.toList(), { it.width }, { it.height })

  @Test
  fun exactTargetWinsWithoutDiscardingFallbacks() {
    val sizes = arrayOf(Dimensions(4000, 3000), Dimensions(640, 480), Dimensions(3200, 2400), Dimensions(3840, 2160))
    val ranked = rank(*sizes)
    assertEquals(Dimensions(3200, 2400), ranked.first())
    assertEquals(sizes.toSet(), ranked.toSet())
  }

  @Test
  fun fullSize4By3BeatsCloserOtherAspectsWhichCompeteByDimensionDistance() {
    // Samsung SM-G998U rear: a square capture would crop the shared viewport, and live analysis with it.
    assertEquals(listOf(Dimensions(4000, 3000), Dimensions(2992, 2992), Dimensions(3840, 2160)),
      rank(Dimensions(3840, 2160), Dimensions(2992, 2992), Dimensions(4000, 3000)))
    assertEquals(listOf(Dimensions(4000, 3000), Dimensions(3000, 3000), Dimensions(3840, 2160)),
      rank(Dimensions(4000, 3000), Dimensions(3840, 2160), Dimensions(3000, 3000)))
    assertEquals(Dimensions(3072, 2304), rank(Dimensions(4256, 3192), Dimensions(2592, 1944), Dimensions(3072, 2304)).first())
  }

  @Test
  fun small4By3SizesDoNotOutrankLargerOtherAspects() {
    assertEquals(Dimensions(3000, 3000), rank(Dimensions(1600, 1200), Dimensions(3000, 3000)).first())
  }

  @Test
  fun rankingIsOrientationIndependentAndSmallOnlyDevicesStillWork() {
    assertEquals(Dimensions(2400, 3200), rank(Dimensions(3000, 4000), Dimensions(2400, 3200)).first())
    assertEquals(Dimensions(1920, 1080), rank(Dimensions(640, 480), Dimensions(1280, 720), Dimensions(1920, 1080)).first())
    assertTrue(rank().isEmpty())
  }

  @Test
  fun equalDistancePrefersFewerPixelsRegardlessOfEnumerationOrder() {
    val a = Dimensions(3000, 2600)
    val b = Dimensions(3400, 2200)
    assertEquals(b, rank(a, b).first())
    assertEquals(b, rank(b, a).first())
  }
}
