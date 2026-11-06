package com.margelo.nitro.documentscanner

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Rank device-advertised sizes, orientation-independently, for still capture.
 * Within 4096 px on the long side and 12,582,912 pixels, sizes of 4:3 or wider come first by the widest short side,
 * then by the aspect closest to 4:3: an upright page fills the width, and a camera makes a wider size from a narrower
 * strip of the sensor, which the shared viewport then crops Preview and Analysis to. Square and narrower sizes cut
 * the page's length, so they follow; sizes over the cap come last, smallest first.
 * Ahead of all, a size that is not [slow] and already has a short side of [TEXT_SHORT_SIDE]: a camera's
 * high-resolution sizes capture below 20 fps and generally without zero shutter lag, so one wins only when the other
 * sizes are too small for text. Keep every fallback: CameraX must still negotiate Preview + Capture + Analysis together.
 */
internal object CaptureResolutionPolicy {
  private const val MAX_LONG_SIDE = 4096
  private const val MAX_PIXELS = 12_582_912L
  private const val FOUR_THIRDS = 4.0 / 3.0
  /** About 260 dpi across an A4 page that fills nine tenths of the photo's width. */
  const val TEXT_SHORT_SIDE = 2400

  fun <T> rank(sizes: List<T>, width: (T) -> Int, height: (T) -> Int, slow: (T) -> Boolean = { false }): List<T> {
    fun long(size: T) = max(width(size), height(size))
    fun short(size: T) = min(width(size), height(size))
    fun pixels(size: T) = width(size).toLong() * height(size)
    fun aspect(size: T) = long(size).toDouble() / short(size)
    fun tier(size: T) = when {
      long(size) > MAX_LONG_SIDE || pixels(size) > MAX_PIXELS -> 3
      aspect(size) < FOUR_THIRDS - 0.01 -> 2
      !slow(size) && short(size) >= TEXT_SHORT_SIDE -> 0
      else -> 1
    }
    return sizes.sortedWith(compareBy<T> { tier(it) }
      .thenBy { if (tier(it) == 3) pixels(it) else -short(it).toLong() }
      .thenBy { abs(aspect(it) - FOUR_THIRDS) }
      .thenBy { pixels(it) }
      .thenBy { long(it) })
  }

  /** A short side of 1080 px, as 1440x1080: enough for an ordinary photo at a fraction of the file size. */
  const val STANDARD_SHORT_SIDE = 1080

  /** Ranks for `camera` mode's standard photo: 4:3-or-wider sizes with a short side of at least
   * [STANDARD_SHORT_SIDE] first, the smallest such short side, then the aspect closest to 4:3, so 1440x1080 beats
   * 1920x1080 and 4000x3000; then smaller 4:3-or-wider sizes, widest short side first; square and narrower last. */
  fun <T> rankStandard(sizes: List<T>, width: (T) -> Int, height: (T) -> Int): List<T> {
    fun long(size: T) = max(width(size), height(size))
    fun short(size: T) = min(width(size), height(size))
    fun aspect(size: T) = long(size).toDouble() / short(size)
    fun tier(size: T) = when {
      aspect(size) < FOUR_THIRDS - 0.01 -> 2
      short(size) >= STANDARD_SHORT_SIDE -> 0
      else -> 1
    }
    return sizes.sortedWith(compareBy<T> { tier(it) }
      .thenBy { if (tier(it) == 0) short(it).toLong() else -short(it).toLong() }
      .thenBy { abs(aspect(it) - FOUR_THIRDS) }
      .thenBy { width(it).toLong() * height(it) })
  }
}
