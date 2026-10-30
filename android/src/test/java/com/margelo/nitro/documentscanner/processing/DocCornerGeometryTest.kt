package com.margelo.nitro.documentscanner.processing

import org.junit.Assert.*
import org.junit.Test

class DocCornerGeometryTest {
  @Test
  fun preservesPredictedCornersAndRejectsInvalidOutputs() {
    val output = floatArrayOf(.1f, .2f, .8f, .1f, .9f, .8f, .2f, .9f, .7f)
    val candidate = DocCornerSession.decodeOutput(output)!!
    assertEquals(output[0].toDouble(), candidate.quad.topLeft.x, 0.0)
    assertEquals(output[4].toDouble(), candidate.quad.bottomRight.x, 0.0)
    assertNull(candidate.a4PageRange)
    assertNull(DocCornerSession.decodeOutput(output.copyOf().apply { this[4] = 1.01f }))
    assertNull(DocCornerSession.decodeOutput(output.copyOf().apply { this[4] = .1f; this[5] = .2f }))
    assertNull(DocCornerSession.decodeOutput(floatArrayOf(.1f, .1f, .9f, .9f, .9f, .1f, .1f, .9f, .7f)))
    assertThrows(IllegalStateException::class.java) {
      DocCornerSession.decodeOutput(output.copyOf().apply { this[8] = Float.NaN })
    }
    assertThrows(IllegalStateException::class.java) {
      DocCornerSession.decodeOutput(output.copyOf().apply { this[0] = Float.POSITIVE_INFINITY })
    }
  }

  @Test
  fun bilinearResamplingMatchesPillowRgbGoldenPixels() {
    // Independent Pillow RGB bilinear output hashes: reduction, enlargement, identity,
    // extreme reduction, and a non-integral camera-sized reduction.
    val cases = listOf(
      intArrayOf(7, 5, 3, 2, 1805764405),
      intArrayOf(3, 2, 7, 5, 1837232507),
      intArrayOf(15, 9, 1, 1, -8422243),
      intArrayOf(224, 224, 224, 224, 1761361665),
      intArrayOf(641, 479, 224, 224, 952188093),
    )
    val resampler = BilinearRgbResampler()
    for ((width, height, targetWidth, targetHeight, expectedHash) in cases) {
      val result = IntArray(targetWidth * targetHeight)
      resampler.resize(width, height, targetWidth, targetHeight, result) { y, row ->
        for (x in 0 until width) {
          row[x] = (255 shl 24) or (((x * 17 + y * 31) % 256) shl 16) or
            (((x * 53 + y * 7) % 256) shl 8) or ((x * 11 + y * 97) % 256)
        }
      }
      assertEquals("$width x $height -> $targetWidth x $targetHeight", expectedHash, result.contentHashCode())
    }
  }
}
