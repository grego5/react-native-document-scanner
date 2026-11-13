package com.margelo.nitro.documentscanner

import kotlin.math.abs

/** TL/TR/BR/BL x,y pairs in upright normalized coordinates; no Android or image allocations. */
internal object NativeCornerGeometry {
  fun coordinates(corners: DocumentCorners): DoubleArray = doubleArrayOf(
    corners.topLeft.x, corners.topLeft.y, corners.topRight.x, corners.topRight.y,
    corners.bottomRight.x, corners.bottomRight.y, corners.bottomLeft.x, corners.bottomLeft.y,
  )

  fun finite(corners: DoubleArray): Boolean = corners.size == 8 && corners.all { it.isFinite() }

  private fun within(corners: DoubleArray, lower: Double, upper: Double): Boolean =
    corners.all { it in lower..upper }

  fun turn(corners: DoubleArray, a: Int, b: Int, c: Int): Double =
    (corners[b * 2] - corners[a * 2]) * (corners[c * 2 + 1] - corners[a * 2 + 1]) -
      (corners[b * 2 + 1] - corners[a * 2 + 1]) * (corners[c * 2] - corners[a * 2])

  fun area(corners: DoubleArray): Double = abs((0 until 4).sumOf { i ->
    val next = (i + 1) % 4
    corners[i * 2] * corners[next * 2 + 1] - corners[next * 2] * corners[i * 2 + 1]
  }) * .5

  private fun clockwise(corners: DoubleArray, minimumTurn: Double): Boolean =
    (0 until 4).all { turn(corners, it, (it + 1) % 4, (it + 2) % 4) > minimumTurn }

  /** Prediction acceptance has no editor minimum area. */
  fun prediction(corners: DoubleArray): Boolean =
    finite(corners) && within(corners, 0.0, 1.0) && clockwise(corners, 1e-6)

  /** Editor props and completed moves must remain inside the photo. */
  fun editor(corners: DoubleArray): Boolean =
    finite(corners) && within(corners, 0.0, 1.0) && clockwise(corners, 1e-8) && area(corners) >= 1e-4

  /** Null for supported render geometry; otherwise the existing input diagnostic. */
  fun renderIssue(corners: DoubleArray): String? = when {
    !finite(corners) -> "Document corners must be finite."
    !within(corners, -.8, 1.8) -> "Document corners exceed the supported extrapolation range."
    !clockwise(corners, 1e-8) || intersects(corners, 0, 1, 2, 3) || intersects(corners, 1, 2, 3, 0) ->
      "Document corners must form a convex clockwise document outline."
    area(corners) !in 1e-4..4.0 -> "Document document outline area is outside the supported range."
    else -> null
  }

  private fun intersects(corners: DoubleArray, a: Int, b: Int, c: Int, d: Int): Boolean =
    turn(corners, a, b, c) * turn(corners, a, b, d) < 0.0 &&
      turn(corners, c, d, a) * turn(corners, c, d, b) < 0.0
}
