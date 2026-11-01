package com.margelo.nitro.documentscanner.processing

import com.margelo.nitro.documentscanner.DocumentPoint
import com.margelo.nitro.documentscanner.DocumentQuad
import org.junit.Assert.*
import org.junit.Test

class PhotoInferenceCropTest {
  @org.junit.Before fun initialize() {
    com.facebook.soloader.SoLoader.init(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext, false)
    com.margelo.nitro.JNIOnLoad.initializeNativeNitro()
  }

  private fun quad(l: Double, t: Double, r: Double, b: Double) = DocumentQuad(
    DocumentPoint(l, t), DocumentPoint(r, t), DocumentPoint(r, b), DocumentPoint(l, b),
  )

  @Test fun centersWithTenPercentMarginAndSquares() {
    val crop = PhotoInferenceCrop.from(quad(.3, .3, .7, .7), 1000, 2000)!!
    assertEquals(PhotoInferenceCrop(20, 520, 960, 960), crop)
    val mapped = crop.toPhoto(quad(0.0, 0.0, 1.0, 1.0), 1000, 2000)!!
    assertEquals(.02, mapped.topLeft.x, 1e-9)
    assertEquals(.74, mapped.bottomRight.y, 1e-9)
    val back = crop.fromPhoto(mapped, 1000, 2000)!!
    assertEquals(0.0, back.topLeft.x, 1e-9)
    assertEquals(1.0, back.bottomRight.y, 1e-9)
    assertNull("outside the region", crop.fromPhoto(quad(.3, .2, .7, .7), 1000, 2000))
  }

  @Test fun squaresAsFarAsThePhotoAllowsAndShiftsAtItsEdge() {
    val crop = PhotoInferenceCrop.from(quad(.01, .2, .31, .8), 1000, 2000)!!
    // A square needs 1440 px of width; the photo has 1000, so the region keeps the full width.
    assertEquals(PhotoInferenceCrop(0, 280, 1000, 1440), crop)
  }

  @Test fun fullFrameAndInvalidSeeds() {
    assertEquals(PhotoInferenceCrop(0, 0, 1200, 800),
      PhotoInferenceCrop.from(quad(0.0, 0.0, 1.0, 1.0), 1200, 800))
    assertNull(PhotoInferenceCrop.from(null, 1000, 2000))
    assertNull(PhotoInferenceCrop.from(quad(-.1, .2, .8, .9), 1000, 2000))
    assertNull(PhotoInferenceCrop.from(quad(.8, .2, .1, .9), 1000, 2000))
  }
  @Test fun mappedCoordinatesAreRevalidatedBeforePublication() {
    val crop = PhotoInferenceCrop(0, 0, 10, 10)
    // Valid at model scale, too small after mapping into the complete photo.
    assertNull(crop.toPhoto(quad(0.0, 0.0, .05, .05), 1000, 1000))
    assertNull(crop.toPhoto(quad(Double.NaN, 0.0, 1.0, 1.0), 1000, 1000))
    assertNull(crop.toPhoto(quad(0.0, 0.0, 1.0, 1.0), 0, 1000))
    assertNull(PhotoInferenceCrop(-20, 0, 10, 10).toPhoto(quad(0.0, 0.0, 1.0, 1.0), 1000, 1000))
    assertNotNull(crop.toPhoto(quad(0.0, 0.0, 1.0, 1.0), 1000, 1000))
    assertNull(PhotoInferenceCrop.from(quad(0.0, 0.0, 1.0, 1.0), 0, 1000))
  }
}
