package com.margelo.nitro.documentscanner.processing

import com.margelo.nitro.documentscanner.DocumentPoint
import com.margelo.nitro.documentscanner.DocumentQuad
import org.junit.Assert.*
import org.junit.Test

class PhotoInferenceCropTest {
  private fun quad(l: Double, t: Double, r: Double, b: Double) = DocumentQuad(
    DocumentPoint(l, t), DocumentPoint(r, t), DocumentPoint(r, b), DocumentPoint(l, b),
  )

  @Test fun centersWithTenPercentMarginAndOriginalAspect() {
    val crop = PhotoInferenceCrop.from(quad(.3, .3, .7, .7), 1000, 2000)!!
    assertEquals(PhotoInferenceCrop(260, 520, 480, 960), crop)
    val mapped = crop.toPhoto(quad(0.0, 0.0, 1.0, 1.0), 1000, 2000)
    assertEquals(.26, mapped.topLeft.x, 1e-9)
    assertEquals(.74, mapped.bottomRight.y, 1e-9)
  }

  @Test fun expandsShortAxisAndShiftsAtPhotoEdgeWithoutClippingSeed() {
    val crop = PhotoInferenceCrop.from(quad(.01, .2, .31, .8), 1000, 2000)!!
    assertTrue(crop.left >= 0 && crop.top >= 0)
    assertTrue(crop.left <= 10 && crop.left + crop.width >= 340)
    assertTrue(crop.top <= 280 && crop.top + crop.height >= 1720)
    assertEquals(.5, crop.width.toDouble() / crop.height, .002)
  }

  @Test fun fullFrameAndInvalidSeeds() {
    assertEquals(PhotoInferenceCrop(0, 0, 1200, 800),
      PhotoInferenceCrop.from(quad(0.0, 0.0, 1.0, 1.0), 1200, 800))
    assertNull(PhotoInferenceCrop.from(null, 1000, 2000))
    assertNull(PhotoInferenceCrop.from(quad(-.1, .2, .8, .9), 1000, 2000))
    assertNull(PhotoInferenceCrop.from(quad(.8, .2, .1, .9), 1000, 2000))
  }
}
