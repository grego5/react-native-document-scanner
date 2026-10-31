package com.margelo.nitro.documentscanner.processing

import org.junit.Assert.*
import org.junit.Test

class DocCornerGeometryTest {
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
