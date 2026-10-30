package com.margelo.nitro.documentscanner.processing

import com.margelo.nitro.documentscanner.requireInput

/** Resolved once at model selection. Keeps allocations and JNI dimensions within signed Int bounds. */
internal data class CornerModelSize(val width: Int, val height: Int) {
  val inputBytes: Int get() = (width.toLong() * height * 3 * 4).toInt()

  companion object {
    fun resolve(width: Double?, height: Double?): CornerModelSize {
      val w = width ?: height ?: 256.0
      val h = height ?: width ?: 256.0
      requireInput(w.isFinite() && h.isFinite() && w >= 1 && h >= 1 &&
        w <= Int.MAX_VALUE && h <= Int.MAX_VALUE && w % 1.0 == 0.0 && h % 1.0 == 0.0) {
        "Corner model dimensions must be positive integers."
      }
      requireInput(w * h <= Int.MAX_VALUE / 12) { "Corner model input tensor is too large." }
      return CornerModelSize(w.toInt(), h.toInt())
    }
  }
}
