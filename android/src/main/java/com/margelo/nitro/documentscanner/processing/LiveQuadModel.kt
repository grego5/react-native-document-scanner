package com.margelo.nitro.documentscanner.processing

import androidx.camera.core.ImageProxy
import com.margelo.nitro.documentscanner.DocumentCandidate

/** Live inference seam; confined to the detector's analysis executor. */
internal interface LiveQuadModel : AutoCloseable {
  fun analyze(image: ImageProxy): DocumentCandidate?
  /** Runs any first-inference setup ahead of the first frame. */
  fun warmUp() = Unit
  val lastPreprocessingMs: Double
  val lastInferenceMs: Double
  /** Raw confidence from the last successful inference, even when its corner geometry was invalid. */
  val lastScore: Double?
}
