package com.margelo.nitro.documentscanner

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Rank device-advertised sizes, orientation-independently. Bounded 4:3 sizes always come first.
 * Within 4:3, a normal text-sized photo keeps its capture rate over slow high-resolution sizes.
 * Other aspects remain fallbacks for CameraX negotiation; over-cap sizes follow bounded ones. */
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
      long(size) > MAX_LONG_SIDE || pixels(size) > MAX_PIXELS -> 4
      aspect(size) < FOUR_THIRDS - 0.01 -> 3
      abs(aspect(size) - FOUR_THIRDS) >= 0.01 -> 2
      !slow(size) && short(size) >= TEXT_SHORT_SIDE -> 0
      else -> 1
    }
    return sizes.sortedWith(compareBy<T> { tier(it) }
      .thenBy { if (tier(it) == 4) pixels(it) else -short(it).toLong() }
      .thenBy { abs(aspect(it) - FOUR_THIRDS) }
      .thenBy { pixels(it) }
      .thenBy { long(it) })
  }

  /** A short side of 1080 px, as 1440x1080: enough for an ordinary photo at a fraction of the file size. */
  const val STANDARD_SHORT_SIDE = 1080

  /** Prefer 4:3, then wider, then narrower fallback sizes. Within 4:3 and wider groups, choose
   * the smallest short side reaching 1080, or the largest smaller one; narrower sizes rank largest first. Shared vectors live
   * in standard-resolution.tsv. */
  fun <T> rankStandard(sizes: List<T>, width: (T) -> Int, height: (T) -> Int): List<T> {
    fun long(size: T) = max(width(size), height(size))
    fun short(size: T) = min(width(size), height(size))
    fun aspect(size: T) = long(size).toDouble() / short(size)
    fun tier(size: T) = when {
      abs(aspect(size) - FOUR_THIRDS) < 0.01 -> if (short(size) >= STANDARD_SHORT_SIDE) 0 else 1
      aspect(size) > FOUR_THIRDS -> if (short(size) >= STANDARD_SHORT_SIDE) 2 else 3
      else -> 4
    }
    return sizes.sortedWith(compareBy<T> { tier(it) }
      .thenBy { if (tier(it) == 0 || tier(it) == 2) short(it).toLong() else -short(it).toLong() }
      .thenBy { abs(aspect(it) - FOUR_THIRDS) }
      .thenBy { width(it).toLong() * height(it) })
  }
}
