package com.margelo.nitro.documentscanner

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Rank device-advertised sizes, orientation-independently, for still capture.
 * Within 4096 px on the long side and 12,582,912 pixels, sizes of 4:3 or wider come first by the widest short side,
 * then by the aspect closest to 4:3: an upright page fills the width, and a camera makes a wider size from a narrower
 * strip of the sensor, which the shared viewport then crops Preview and Analysis to. Square and narrower sizes cut
 * the page's length, so they follow; sizes over the cap come last, smallest first.
 * Keep every fallback: CameraX must still negotiate Preview + Capture + Analysis together.
 */
internal object CaptureResolutionPolicy {
  private const val MAX_LONG_SIDE = 4096
  private const val MAX_PIXELS = 12_582_912L
  private const val FOUR_THIRDS = 4.0 / 3.0

  fun <T> rank(sizes: List<T>, width: (T) -> Int, height: (T) -> Int): List<T> {
    fun long(size: T) = max(width(size), height(size))
    fun short(size: T) = min(width(size), height(size))
    fun pixels(size: T) = width(size).toLong() * height(size)
    fun aspect(size: T) = long(size).toDouble() / short(size)
    fun tier(size: T) = when {
      long(size) > MAX_LONG_SIDE || pixels(size) > MAX_PIXELS -> 2
      aspect(size) < FOUR_THIRDS - 0.01 -> 1
      else -> 0
    }
    return sizes.sortedWith(compareBy<T> { tier(it) }
      .thenBy { if (tier(it) == 2) pixels(it) else -short(it).toLong() }
      .thenBy { abs(aspect(it) - FOUR_THIRDS) }
      .thenBy { pixels(it) }
      .thenBy { long(it) })
  }
}
