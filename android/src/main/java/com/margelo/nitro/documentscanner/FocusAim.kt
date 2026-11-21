package com.margelo.nitro.documentscanner

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Main thread: where continuous autofocus aims, as a box centred at [x], [y] with side [size], fractions of the
 * preview; the central [AREA] without a page. Its target follows the tracked print every frame, but every region change
 * can restart a focus search, so the aim moves only when the target drifts more than [MOVE] of the box's side from it,
 * or its size changes by more than [RESIZE]; small drift stays inside the box.
 */
internal class FocusAim {
  var x = CENTRE
    private set
  var y = CENTRE
    private set
  var size = AREA
    private set
  val centred: Boolean get() = x == CENTRE && y == CENTRE && size == AREA

  /** The target now; true when the aim moved to it. */
  fun observe(targetX: Float, targetY: Float, targetSize: Float): Boolean {
    if (hypot(targetX - x, targetY - y) <= size * MOVE && abs(targetSize / size - 1) <= RESIZE) return false
    x = targetX
    y = targetY
    size = targetSize
    return true
  }

  fun reset() {
    x = CENTRE
    y = CENTRE
    size = AREA
  }

  companion object {
    const val CENTRE = .5f
    /** Side of the continuous-autofocus box, as a fraction of the preview's width and height. */
    const val AREA = .3f
    /** The smallest box a page too small for [AREA] gets. */
    const val MIN_AREA = .05f
    /** The drift, as a fraction of the box's side, that moves the aim. */
    const val MOVE = .25f
    const val RESIZE = .2f
  }
}

/**
 * A [size] box at ([x], [y]) moved towards the page's centre ([centreX], [centreY]) until it lies inside the convex
 * [outline] (x, y pairs), and shrunk around that centre when it does not fit even there; returned as x, y and size, or
 * null when not even a [FocusAim.MIN_AREA] box fits, so the caller decides where to focus rather than meter past the page.
 * Positions are preview fractions; [size] is a fraction of the camera image, which spans [imageWidth] by [imageHeight]
 * of the preview. Autofocus weighs its whole box, so a box past the page's edge lets the floor or room behind a held
 * page take focus.
 */
internal fun fitInside(
  outline: FloatArray, x: Float, y: Float, centreX: Float, centreY: Float, size: Float,
  imageWidth: Float = 1f, imageHeight: Float = 1f,
): FloatArray? {
  val corners = outline.size / 2
  fun inside(px: Float, py: Float): Boolean {
    var sign = 0
    for (index in 0 until corners) {
      val ax = outline[2 * index]
      val ay = outline[2 * index + 1]
      val bx = outline[2 * ((index + 1) % corners)]
      val by = outline[2 * ((index + 1) % corners) + 1]
      val cross = (bx - ax) * (py - ay) - (by - ay) * (px - ax)
      if (cross == 0f) continue
      val side = if (cross > 0) 1 else -1
      if (sign == 0) sign = side else if (side != sign) return false
    }
    return true
  }
  fun fits(cx: Float, cy: Float, s: Float): Boolean {
    val w = s * imageWidth / 2
    val h = s * imageHeight / 2
    return inside(cx - w, cy - h) && inside(cx + w, cy - h) && inside(cx + w, cy + h) && inside(cx - w, cy + h)
  }
  if (fits(x, y, size)) return floatArrayOf(x, y, size)
  if (fits(centreX, centreY, size)) {
    // Inside a convex outline the positions that fit form a convex set, so the first fitting point on the way to the
    // centre is a single threshold.
    var away = 0f
    var near = 1f
    repeat(12) {
      val t = (away + near) / 2
      if (fits(x + (centreX - x) * t, y + (centreY - y) * t, size)) near = t else away = t
    }
    return floatArrayOf(x + (centreX - x) * near, y + (centreY - y) * near, size)
  }
  var small = 0f
  var large = size
  repeat(12) {
    val s = (small + large) / 2
    if (fits(centreX, centreY, s)) small = s else large = s
  }
  return if (small >= FocusAim.MIN_AREA) floatArrayOf(centreX, centreY, small) else null
}
