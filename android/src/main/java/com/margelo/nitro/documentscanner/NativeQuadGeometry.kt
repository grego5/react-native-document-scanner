package com.margelo.nitro.documentscanner

import kotlin.math.abs

/** TL/TR/BR/BL x,y pairs in upright normalized coordinates; no Android or image allocations. */
internal object NativeQuadGeometry {
  fun coordinates(quad: DocumentQuad): DoubleArray = doubleArrayOf(
    quad.topLeft.x, quad.topLeft.y, quad.topRight.x, quad.topRight.y,
    quad.bottomRight.x, quad.bottomRight.y, quad.bottomLeft.x, quad.bottomLeft.y,
  )

  fun finite(quad: DoubleArray): Boolean = quad.size == 8 && quad.all { it.isFinite() }

  private fun within(quad: DoubleArray, lower: Double, upper: Double): Boolean =
    quad.all { it in lower..upper }

  fun turn(quad: DoubleArray, a: Int, b: Int, c: Int): Double =
    (quad[b * 2] - quad[a * 2]) * (quad[c * 2 + 1] - quad[a * 2 + 1]) -
      (quad[b * 2 + 1] - quad[a * 2 + 1]) * (quad[c * 2] - quad[a * 2])

  fun area(quad: DoubleArray): Double = abs((0 until 4).sumOf { i ->
    val next = (i + 1) % 4
    quad[i * 2] * quad[next * 2 + 1] - quad[next * 2] * quad[i * 2 + 1]
  }) * .5

  private fun clockwise(quad: DoubleArray, minimumTurn: Double): Boolean =
    (0 until 4).all { turn(quad, it, (it + 1) % 4, (it + 2) % 4) > minimumTurn }

  /** Prediction acceptance has no editor minimum area. */
  fun prediction(quad: DoubleArray): Boolean =
    finite(quad) && within(quad, 0.0, 1.0) && clockwise(quad, 1e-6)

  /** Editor props and completed moves must remain inside the photo. */
  fun editor(quad: DoubleArray): Boolean =
    finite(quad) && within(quad, 0.0, 1.0) && clockwise(quad, 1e-8) && area(quad) >= 1e-4

  /** Null for supported render geometry; otherwise the existing input diagnostic. */
  fun renderIssue(quad: DoubleArray): String? = when {
    !finite(quad) -> "Document corners must be finite."
    !within(quad, -.8, 1.8) -> "Document corners exceed the supported extrapolation range."
    !clockwise(quad, 1e-8) || intersects(quad, 0, 1, 2, 3) || intersects(quad, 1, 2, 3, 0) ->
      "Document corners must form a convex clockwise quadrilateral."
    area(quad) !in 1e-4..4.0 -> "Document quadrilateral area is outside the supported range."
    else -> null
  }

  private fun intersects(quad: DoubleArray, a: Int, b: Int, c: Int, d: Int): Boolean =
    turn(quad, a, b, c) * turn(quad, a, b, d) < 0.0 &&
      turn(quad, c, d, a) * turn(quad, c, d, b) < 0.0
}
