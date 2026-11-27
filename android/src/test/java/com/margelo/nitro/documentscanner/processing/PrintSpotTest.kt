package com.margelo.nitro.documentscanner.processing

import com.margelo.nitro.documentscanner.DocumentCorners
import com.margelo.nitro.documentscanner.DocumentPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class PrintSpotTest {
  private val width = 64
  private val height = 48

  /** A grey frame with one-pixel black strokes, like print, in the square [x0, x1) by [y0, y1); [blur] spreads them. */
  private fun frame(x0: Int, y0: Int, x1: Int, y1: Int, blur: Boolean): ByteBuffer {
    val pixels = IntArray(width * height) { 200 }
    for (y in y0 until y1) for (x in x0 until x1) if (x % 3 == 0) pixels[y * width + x] = 40
    val out = if (!blur) pixels else IntArray(width * height) { i ->
      val x = i % width
      val y = i / width
      if (x == 0 || y == 0 || x == width - 1 || y == height - 1) pixels[i]
      else (pixels[i] * 4 + pixels[i - 1] + pixels[i + 1] + pixels[i - width] + pixels[i + width]) / 8
    }
    return ByteBuffer.wrap(ByteArray(width * height) { out[it].toByte() })
  }

  private val whole = DocumentCorners(DocumentPoint(.05, .05), DocumentPoint(.95, .05), DocumentPoint(.95, .95),
    DocumentPoint(.05, .95))

  @Test fun pagePointsFollowTheOutline() {
    val p = pagePoint(whole, .5, .25)
    assertEquals(.5, p.x, 1e-9)
    assertEquals(.275, p.y, 1e-9)
  }

  @Test fun theSpotLandsOnThePrintedCell() {
    // Print only in the buffer's top-left area, which is the page's top-left cell when upright.
    val spot = pickPrintSpot(frame(6, 6, 24, 18, false), width, 1, 0, 0, width, height, 0, whole)!!
    assertTrue("$spot", spot.x < .34 && spot.y < .34)
  }

  @Test fun theGuideSpotLandsOnPrintInTheGuideArea() {
    // Print in the guide area's lower right only; the spot is there, in frame fractions.
    val spot = pickGuideSpot(frame(36, 27, 42, 32, true), width, 1, 0, 0, width, height, 0)!!
    assertTrue("$spot", spot.x > .55 && spot.x < .65 && spot.y > .55 && spot.y < .65)
  }

  @Test fun aFeaturelessFrameHasNoPrintOrGuideSpot() {
    val blank = frame(0, 0, 0, 0, false)
    assertNull(pickPrintSpot(blank, width, 1, 0, 0, width, height, 0, whole, requireTexture = true))
    assertNull(pickGuideSpot(blank, width, 1, 0, 0, width, height, 0))
  }

  @Test fun pageCoordinatesInvertPagePoints() {
    val skewed = DocumentCorners(DocumentPoint(.1, .2), DocumentPoint(.8, .1), DocumentPoint(.9, .9),
      DocumentPoint(.2, .8))
    val p = pagePoint(skewed, .3, .7)
    val back = pageCoordinates(skewed, p)!!
    assertEquals(.3, back.x, 1e-6)
    assertEquals(.7, back.y, 1e-6)
    assertTrue(insidePage(skewed, p))
    assertFalse(insidePage(skewed, DocumentPoint(.05, .5)))
  }

  @Test fun tiedCellsPreferTheCentre() {
    // Print everywhere ties every cell; the centre cell wins.
    val spot = pickPrintSpot(frame(0, 0, width, height, false), width, 1, 0, 0, width, height, 0, whole)!!
    assertEquals(.5, spot.x, 1e-9)
    assertEquals(.5, spot.y, 1e-9)
  }
}
