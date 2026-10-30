package com.margelo.nitro.documentscanner.processing

import kotlin.math.abs
import kotlin.math.max

/** Test oracle: separable, antialiased bilinear RGB resampling with Pillow's 8-bit rounding contract.
 * Pixel centers and the widened downsampling kernel match PIL.Image.Resampling.BILINEAR.
 * See https://github.com/python-pillow/Pillow/blob/main/src/libImaging/Resample.c.
 * Confined to the model executor; scratch space is one input row plus targetWidth * sourceHeight.
 */
internal class BilinearRgbResampler {
  private var row = IntArray(0)
  private var horizontal = IntArray(0)

  fun resize(
    width: Int,
    height: Int,
    targetWidth: Int,
    targetHeight: Int,
    result: IntArray,
    readRow: (Int, IntArray) -> Unit,
  ) {
    require(width > 0 && height > 0 && targetWidth > 0 && targetHeight > 0)
    require(result.size == targetWidth * targetHeight)
    if (row.size != width) row = IntArray(width)
    if (horizontal.size != targetWidth * height) horizontal = IntArray(targetWidth * height)
    val xWeights = weights(width, targetWidth)
    val yWeights = weights(height, targetHeight)
    for (y in 0 until height) {
      readRow(y, row)
      for (x in 0 until targetWidth) {
        horizontal[y * targetWidth + x] = filtered(row, xWeights[x], 1, 0)
      }
    }
    for (y in 0 until targetHeight) {
      for (x in 0 until targetWidth) {
        result[y * targetWidth + x] = filtered(horizontal, yWeights[y], targetWidth, x)
      }
    }
  }

  private data class Weights(val start: Int, val coefficients: IntArray)

  private fun weights(source: Int, target: Int): Array<Weights> {
    val scale = source.toDouble() / target
    val filterScale = max(1.0, scale)
    return Array(target) { index ->
      val center = (index + 0.5) * scale
      val start = (center - filterScale + 0.5).toInt().coerceAtLeast(0)
      val end = (center + filterScale + 0.5).toInt().coerceAtMost(source)
      val raw = DoubleArray(end - start) { offset ->
        max(0.0, 1.0 - abs((start + offset - center + 0.5) / filterScale))
      }
      val sum = raw.sum()
      Weights(start, IntArray(raw.size) { ((raw[it] / sum) * PRECISION + 0.5).toInt() })
    }
  }

  private fun filtered(pixels: IntArray, weights: Weights, stride: Int, offset: Int): Int {
    var red = ROUNDING
    var green = ROUNDING
    var blue = ROUNDING
    for (index in weights.coefficients.indices) {
      val pixel = pixels[(weights.start + index) * stride + offset]
      val weight = weights.coefficients[index]
      red += ((pixel ushr 16) and 255) * weight
      green += ((pixel ushr 8) and 255) * weight
      blue += (pixel and 255) * weight
    }
    return (255 shl 24) or ((red ushr 22).coerceAtMost(255) shl 16) or
      ((green ushr 22).coerceAtMost(255) shl 8) or (blue ushr 22).coerceAtMost(255)
  }

  private companion object {
    const val PRECISION = 1 shl 22
    const val ROUNDING = 1 shl 21
  }
}
