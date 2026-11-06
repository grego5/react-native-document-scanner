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
  fun aHighResolutionSizeWinsOnlyWhenTheOthersAreTooSmallForText() {
    fun rankSlow(normal: List<Dimensions>, slow: List<Dimensions>) =
      CaptureResolutionPolicy.rank(normal + slow, { it.width }, { it.height }, { it in slow }).first()
    // Samsung SM-A175F: 4080x3060 is listed only as high-resolution.
    assertEquals(Dimensions(4080, 3060), rankSlow(sizes("2336x1080, 1440x1080, 640x480"), sizes("4080x3060, 4080x2296")))
    // A text-sized normal 4:3 keeps its capture rate and zero shutter lag over a slightly wider slow one.
    assertEquals(Dimensions(4000, 3000), rankSlow(sizes("4000x3000, 1920x1080"), sizes("4080x3060")))
    // Samsung SM-G998U: the slow sizes exceed the cap anyway.
    assertEquals(Dimensions(4000, 3000), rankSlow(sizes("4000x3000, 3840x2160"), sizes("12000x9000")))
    // A normal size under the text short side does not hold off a wider slow one.
    assertEquals(Dimensions(3264, 2448), rankSlow(sizes("2560x1920"), sizes("3264x2448")))
    assertEquals(Dimensions(2560, 1920), rankSlow(sizes("2560x1920"), sizes("2048x1536")))
  }

  @Test
  fun theStandardPhotoIsTheSmallest4By3WithA1080ShortSide() {
    fun standard(list: String) = CaptureResolutionPolicy.rankStandard(sizes(list), { it.width }, { it.height })
    // Samsung SM-G998U-like list: 1440x1080 beats the 12 MP size and the wide 1080p one.
    assertEquals(Dimensions(1440, 1080), standard("4000x3000, 3840x2160, 1920x1080, 1440x1080, 1440x1440, 640x480").first())
    // Without a 1080 short side 4:3, the next larger wins, then the widest smaller one.
    assertEquals(Dimensions(1600, 1200), standard("4000x3000, 1600x1200, 1280x960").first())
    // A larger 4:3 still beats a wider size with a smaller short side.
    assertEquals(Dimensions(1600, 1200), standard("1920x1080, 1600x1200, 640x480").first())
    assertEquals(Dimensions(1280, 960), standard("1280x960, 640x480, 960x960").first())
    assertEquals(Dimensions(1920, 1080), standard("2336x1080, 1920x1080, 1088x1088").first())
    assertTrue(CaptureResolutionPolicy.rankStandard(emptyList<Dimensions>(), { it.width }, { it.height }).isEmpty())
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
