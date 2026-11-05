package com.margelo.nitro.documentscanner

import org.junit.Assert.*
import org.junit.Test

class CaptureResolutionPolicyTest {
  private data class Dimensions(val width: Int, val height: Int)
  private fun rank(vararg sizes: Dimensions) = CaptureResolutionPolicy.rank(sizes.toList(), { it.width }, { it.height })
  private fun sizes(list: String) = list.split(", ").map { it.split("x").let { (w, h) -> Dimensions(w.toInt(), h.toInt()) } }

  @Test
  fun widestShortSideWinsWithoutDiscardingFallbacks() {
    val sizes = arrayOf(Dimensions(3200, 2400), Dimensions(640, 480), Dimensions(4000, 3000), Dimensions(3840, 2160))
    val ranked = rank(*sizes)
    assertEquals(Dimensions(4000, 3000), ranked.first())
    assertEquals(sizes.toSet(), ranked.toSet())
  }

  @Test
  fun equalShortSidePrefersTheAspectClosestTo4By3() {
    // Samsung SM-A175F rear without high-resolution sizes: 2336x1080 comes from a strip 62% of the sensor's width.
    val a17 = sizes("2336x1080, 1920x1080, 1920x888, 1440x1440, 1440x1080, 1088x1088, 1280x720, 960x720, 720x720, " +
      "720x480, 640x480, 640x360, 480x320, 352x288, 320x240, 176x144")
    assertEquals(Dimensions(1440, 1080), CaptureResolutionPolicy.rank(a17, { it.width }, { it.height }).first())
    assertEquals(Dimensions(4080, 3060),
      CaptureResolutionPolicy.rank(a17 + Dimensions(4080, 3060), { it.width }, { it.height }).first())
  }

  @Test
  fun squareSizesFollowEvenWithAWiderShortSide() {
    // Samsung SM-G998U rear: a square capture would crop the shared viewport, and live analysis with it.
    assertEquals(listOf(Dimensions(4000, 3000), Dimensions(3840, 2160), Dimensions(2992, 2992)),
      rank(Dimensions(3840, 2160), Dimensions(2992, 2992), Dimensions(4000, 3000)))
    assertEquals(Dimensions(1600, 1200), rank(Dimensions(1600, 1200), Dimensions(3000, 3000)).first())
  }

  @Test
  fun sizesOverTheCapComeLastSmallestFirst() {
    // Samsung S26 Ultra rear: 4080x3060 fits 12,582,912 pixels; 50 and 200 MP modes do not.
    assertEquals(listOf(Dimensions(4080, 3060), Dimensions(3648, 2736), Dimensions(8160, 6120), Dimensions(16320, 12240)),
      rank(Dimensions(16320, 12240), Dimensions(3648, 2736), Dimensions(8160, 6120), Dimensions(4080, 3060)))
    assertEquals(Dimensions(3072, 2304), rank(Dimensions(4256, 3192), Dimensions(2592, 1944), Dimensions(3072, 2304)).first())
  }

  @Test
  fun rankingIsOrientationIndependentAndSmallOnlyDevicesStillWork() {
    assertEquals(Dimensions(3000, 4000), rank(Dimensions(3000, 4000), Dimensions(2400, 3200)).first())
    assertEquals(Dimensions(1920, 1080), rank(Dimensions(640, 480), Dimensions(1280, 720), Dimensions(1920, 1080)).first())
    assertTrue(rank().isEmpty())
  }
}
