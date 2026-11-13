package com.margelo.nitro.documentscanner.processing

import com.margelo.nitro.documentscanner.DocumentPoint
import com.margelo.nitro.documentscanner.DocumentCorners
import org.junit.Assert.*
import org.junit.Test

class PhotoInferenceCropTest {
  @org.junit.Before fun initialize() {
    com.facebook.soloader.SoLoader.init(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext, false)
    com.margelo.nitro.JNIOnLoad.initializeNativeNitro()
  }

  private fun corners(l: Double, t: Double, r: Double, b: Double) = DocumentCorners(
    DocumentPoint(l, t), DocumentPoint(r, t), DocumentPoint(r, b), DocumentPoint(l, b),
  )

  @Test fun centersWithTenPercentMarginAndSquares() {
    val crop = PhotoInferenceCrop.from(corners(.3, .3, .7, .7), 1000, 2000)!!
    assertEquals(PhotoInferenceCrop(20, 520, 960, 960), crop)
    val mapped = crop.toPhoto(corners(0.0, 0.0, 1.0, 1.0), 1000, 2000)!!
    assertEquals(.02, mapped.topLeft.x, 1e-9)
    assertEquals(.74, mapped.bottomRight.y, 1e-9)
    val back = crop.fromPhoto(mapped, 1000, 2000)!!
    assertEquals(0.0, back.topLeft.x, 1e-9)
    assertEquals(1.0, back.bottomRight.y, 1e-9)
    assertNull("outside the region", crop.fromPhoto(corners(.3, .2, .7, .7), 1000, 2000))
  }

  @Test fun squaresAsFarAsThePhotoAllowsAndShiftsAtItsEdge() {
    val crop = PhotoInferenceCrop.from(corners(.01, .2, .31, .8), 1000, 2000)!!
    // A square needs 1440 px of width; the photo has 1000, so the region keeps the full width.
    assertEquals(PhotoInferenceCrop(0, 280, 1000, 1440), crop)
  }

  @Test fun fullFrameAndInvalidSeeds() {
    assertEquals(PhotoInferenceCrop(0, 0, 1200, 800),
      PhotoInferenceCrop.from(corners(0.0, 0.0, 1.0, 1.0), 1200, 800))
    assertNull(PhotoInferenceCrop.from(null, 1000, 2000))
    assertNull(PhotoInferenceCrop.from(corners(-.1, .2, .8, .9), 1000, 2000))
    assertNull(PhotoInferenceCrop.from(corners(.8, .2, .1, .9), 1000, 2000))
  }
  @Test fun mappedCoordinatesAreRevalidatedBeforePublication() {
    val crop = PhotoInferenceCrop(0, 0, 10, 10)
    // Valid at model scale, too small after mapping into the complete photo.
    assertNull(crop.toPhoto(corners(0.0, 0.0, .05, .05), 1000, 1000))
    assertNull(crop.toPhoto(corners(Double.NaN, 0.0, 1.0, 1.0), 1000, 1000))
    assertNull(crop.toPhoto(corners(0.0, 0.0, 1.0, 1.0), 0, 1000))
    assertNull(PhotoInferenceCrop(-20, 0, 10, 10).toPhoto(corners(0.0, 0.0, 1.0, 1.0), 1000, 1000))
    assertNotNull(crop.toPhoto(corners(0.0, 0.0, 1.0, 1.0), 1000, 1000))
    assertNull(PhotoInferenceCrop.from(corners(0.0, 0.0, 1.0, 1.0), 0, 1000))
  }
}
