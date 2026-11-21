package com.margelo.nitro.documentscanner.processing

import com.margelo.nitro.documentscanner.DocumentCorners
import com.margelo.nitro.documentscanner.DocumentPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class PrintSharpnessTest {
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

  private fun score(buffer: ByteBuffer, rotation: Int, point: DocumentPoint) =
    printSharpness(buffer, width, 1, 0, 0, width, height, rotation, point, .3)

  @Test fun printScoresHigherSharpThanBlurred() {
    val sharp = score(frame(16, 12, 48, 36, false), 0, DocumentPoint(.5, .5))!!
    val blurred = score(frame(16, 12, 48, 36, true), 0, DocumentPoint(.5, .5))!!
    assertTrue("$sharp > $blurred", sharp > blurred * 2)
  }

  @Test fun theUprightPointFindsThePrintInEachRotation() {
    // Print in the buffer's top-left quarter; each rotation turns it to a different upright corner.
    val buffer = frame(2, 2, 30, 22, false)
    val corners = mapOf(0 to DocumentPoint(.25, .25), 90 to DocumentPoint(.75, .25), 180 to DocumentPoint(.75, .75),
      270 to DocumentPoint(.25, .75))
    for ((rotation, onPrint) in corners) {
      val off = DocumentPoint(1 - onPrint.x, 1 - onPrint.y)
      val printed = score(buffer, rotation, onPrint)!!
      val blank = score(buffer, rotation, off)!!
      assertTrue("rotation $rotation: $printed > $blank", printed > blank * 10 + 1)
    }
  }

  /** An RGBA frame of white paper with one-pixel strokes of [ink] (R, G, B) in the middle, as the analysis frames are. */
  private fun rgbaFrame(ink: IntArray): ByteBuffer {
    val bytes = ByteArray(width * height * 4)
    for (y in 0 until height) for (x in 0 until width) {
      val stroke = x in 16 until 48 && y in 12 until 36 && x % 3 == 0
      val colour = if (stroke) ink else intArrayOf(240, 240, 240)
      for (c in 0 until 3) bytes[(y * width + x) * 4 + c] = colour[c].toByte()
      bytes[(y * width + x) * 4 + 3] = -1
    }
    return ByteBuffer.wrap(bytes)
  }

  @Test fun colouredPrintScoresByLumaNotItsRedChannel() {
    // Blue ink on white is all but invisible in red; its luma still shows the strokes.
    val blue = printSharpness(rgbaFrame(intArrayOf(240, 40, 200)), width * 4, 4, 0, 0, width, height, 0,
      DocumentPoint(.5, .5), .3)!!
    val black = printSharpness(rgbaFrame(intArrayOf(30, 30, 30)), width * 4, 4, 0, 0, width, height, 0,
      DocumentPoint(.5, .5), .3)!!
    assertTrue("$blue", blue > black * .1)
    val blank = printSharpness(rgbaFrame(intArrayOf(240, 240, 240)), width * 4, 4, 0, 0, width, height, 0,
      DocumentPoint(.5, .5), .3)!!
    assertTrue("$blue > $blank", blue > blank * 10 + 1)
  }

  @Test fun aSquarePastTheCropHasNoScore() {
    assertNull(score(frame(16, 12, 48, 36, false), 0, DocumentPoint(.02, .5)))
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

  @Test fun tiedCellsPreferTheCentre() {
    // Print everywhere ties every cell; the centre cell wins.
    val spot = pickPrintSpot(frame(0, 0, width, height, false), width, 1, 0, 0, width, height, 0, whole)!!
    assertEquals(.5, spot.x, 1e-9)
    assertEquals(.5, spot.y, 1e-9)
  }
}
