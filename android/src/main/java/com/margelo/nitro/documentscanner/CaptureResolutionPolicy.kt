package com.margelo.nitro.documentscanner

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Rank device-advertised sizes by distance to the orientation-independent capture target.
 * Full-size 4:3 sizes come first: the shared viewport crops Preview, Capture and Analysis to the field of
 * view all three share, so a capture of another aspect would crop live analysis and preview with it.
 * Keep every fallback: CameraX must still negotiate Preview + Capture + Analysis together.
 */
internal object CaptureResolutionPolicy {
  fun <T> rank(sizes: List<T>, width: (T) -> Int, height: (T) -> Int): List<T> =
    sizes.sortedWith(compareBy<T> {
      val long = max(width(it), height(it))
      val short = min(width(it), height(it))
      if (long >= 2400 && abs(long.toDouble() / short - 4.0 / 3.0) < 0.01) 0 else 1
    }.thenBy {
      val longDelta = max(width(it), height(it)).toLong() - 3200L
      val shortDelta = min(width(it), height(it)).toLong() - 2400L
      longDelta * longDelta + shortDelta * shortDelta
    }.thenBy { width(it).toLong() * height(it) }
      .thenBy { max(width(it), height(it)) }
      .thenBy { min(width(it), height(it)) })
}
