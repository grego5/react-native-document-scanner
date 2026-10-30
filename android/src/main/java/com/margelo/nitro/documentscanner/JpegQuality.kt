package com.margelo.nitro.documentscanner

import kotlin.math.roundToInt

/** One public range for capture, rendering and PDF; platform encoders consume percentages. */
internal fun jpegQualityPercent(value: Double): Int {
  requireInput(value.isFinite() && value in 0.0..1.0) { "jpegQuality must be a finite number from 0 to 1." }
  return (value * 100).roundToInt()
}
