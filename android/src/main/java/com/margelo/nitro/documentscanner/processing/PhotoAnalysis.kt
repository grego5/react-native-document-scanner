package com.margelo.nitro.documentscanner.processing

import android.graphics.Bitmap
import com.margelo.nitro.documentscanner.DocumentAnalysis
import com.margelo.nitro.documentscanner.DocumentQuad

/** Upright-photo analysis for owned session originals. */
internal object PhotoAnalysis {
  /** The bitmap passed to inference and the photo region it covers; the caller recycles [bitmap]. */
  class Input(val region: PhotoInferenceCrop, val bitmap: Bitmap)

  fun analyze(
    originalUri: String,
    liveQuad: DocumentQuad?,
    session: CornerInference.Lease,
    selection: QuadModelStore.Selection? = null,
    startedAt: Long = System.nanoTime(),
  ): DocumentAnalysis {
    val header = SourceImageLoader.inspect(originalUri)
    val width = header.uprightWidth
    val height = header.uprightHeight
    val input = loadInput(header, liveQuad)
    val candidate = try {
      val result = if (selection == null) session.analyze(input.bitmap) else session.analyze(input.bitmap, selection)
      result?.let { input.region.toPhoto(it.quad, width, height)?.let { quad -> it.copy(quad = quad) } }
    } finally {
      input.bitmap.recycle()
    }
    return DocumentAnalysis(width.toDouble(), height.toDouble(), (System.nanoTime() - startedAt) / 1_000_000.0, candidate)
  }

  /**
   * The region around [liveQuad], or the whole photo without one, decoded at half resolution: the model reads a far
   * smaller square, and the halving decode costs about half the time of a full-resolution one.
   */
  fun loadInput(header: SourceImageHeader, liveQuad: DocumentQuad?): Input {
    val region = PhotoInferenceCrop.from(liveQuad, header.uprightWidth, header.uprightHeight)
      ?: PhotoInferenceCrop(0, 0, header.uprightWidth, header.uprightHeight)
    return Input(region, SourceImageLoader.loadRegion(header, region, INPUT_SAMPLE_SIZE))
  }

  private const val INPUT_SAMPLE_SIZE = 2
}
