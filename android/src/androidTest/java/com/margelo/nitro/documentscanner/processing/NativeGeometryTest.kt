package com.margelo.nitro.documentscanner.processing

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.margelo.nitro.documentscanner.CropCornerEditing
import com.margelo.nitro.documentscanner.DocumentPoint
import com.margelo.nitro.documentscanner.DocumentCorners
import com.margelo.nitro.documentscanner.InvalidInputException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** JNI integration only; pure geometry assertions live in cpp/tests/GeometryTest.cpp. */
@RunWith(AndroidJUnit4::class)
class NativeGeometryTest {
  private fun corners(l: Double, t: Double, r: Double, b: Double) = DocumentCorners(
    DocumentPoint(l, t), DocumentPoint(r, t), DocumentPoint(r, b), DocumentPoint(l, b),
  )

  @Test fun photoRegionRoundTripUsesTheSharedCrop() {
    assertNull(PhotoInferenceCrop.from(null, 1000, 2000))
    val crop = PhotoInferenceCrop.from(corners(.3, .3, .7, .7), 1000, 2000)!!
    assertEquals(PhotoInferenceCrop(20, 520, 960, 960), crop)
    assertArrayEquals(doubleArrayOf(.02, .26, .98, .26, .98, .74, .02, .74),
      crop.toPhoto(corners(0.0, 0.0, 1.0, 1.0), 1000, 2000)!!.coordinates(), 1e-9)
    assertNull(PhotoInferenceCrop.from(corners(-.1, .2, .8, .9), 1000, 2000))
  }

  @Test fun editsMarshalNullableResultsWithoutMutatingTheGestureSnapshot() {
    val q = corners(.1, .1, .9, .9).coordinates()
    val snapshot = q.copyOf()
    assertArrayEquals(doubleArrayOf(.2, 0.0, 1.0, 0.0, .9, .9, .1, .9),
      CropCornerEditing.moveEdge(q, 0, .3, -.5), 1e-9)
    assertNull(CropCornerEditing.moveCorner(q, 0, .6, .6))
    assertArrayEquals(snapshot, q, 0.0)
    assertTrue(CropCornerEditing.isValid(q))
    assertFalse(CropCornerEditing.isValid(doubleArrayOf(0.0)))
    assertThrows(IllegalArgumentException::class.java) { CropCornerEditing.moveCorner(q, 4, .2, .2) }
    assertThrows(IllegalArgumentException::class.java) { CropCornerEditing.clampToFrame(doubleArrayOf(0.0)) }
  }

  @Test fun coordinatesAndRenderFailuresKeepTheirAndroidBoundaryContract() {
    val point = ImagePoint(-20.0, 90.0)
    val normalized = ImageGeometry.toNormalized(point, 100, 80)
    val roundTrip = ImageGeometry.toImage(normalized, 100, 80)
    assertEquals(point.x, roundTrip.x, 1e-9)
    assertEquals(point.y, roundTrip.y, 1e-9)
    assertThrows(InvalidInputException::class.java) {
      NativeGeometry.validateRender(ImageCorners(ImagePoint(-.81, 0.0), ImagePoint(1.0, 0.0), ImagePoint(1.0, 1.0), ImagePoint(0.0, 1.0)))
    }
    assertThrows(InvalidInputException::class.java) {
      NativeGeometry.target(listOf(ImagePoint(0.0, 0.0), ImagePoint(100.0, 0.0), ImagePoint(100.0, 10.0), ImagePoint(0.0, 10.0)), 1.0, null)
    }
    assertThrows(IllegalArgumentException::class.java) { NativeGeometry.uprightSize(0, 10, 1) }
  }
}
