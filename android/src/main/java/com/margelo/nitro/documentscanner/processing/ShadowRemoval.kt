package com.margelo.nitro.documentscanner.processing

import org.opencv.core.Mat

/** Diagnostic stage adapter; rendering calls the combined shared filter stage. */
internal object ShadowRemoval {
  /** Corrects caller-owned RGBA in place; alpha is unchanged. */
  fun apply(rgba: Mat) = NativeDocumentFilters.shadows(rgba)
}
