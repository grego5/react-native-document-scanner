package com.margelo.nitro.documentscanner.processing

import android.graphics.Bitmap
import com.margelo.nitro.documentscanner.DocumentAnalysis
import com.margelo.nitro.documentscanner.DocumentCorners

/** Upright-photo analysis for owned session originals. */
internal object PhotoAnalysis {
  /** The bitmap passed to inference and the photo region it covers; the caller recycles [bitmap]. */
  class Input(val region: PhotoInferenceCrop, val bitmap: Bitmap)

  fun analyze(
    originalUri: String,
    liveCorners: DocumentCorners?,
    session: CornerInference.Lease,
    selection: CornerModelStore.Selection? = null,
    startedAt: Long = System.nanoTime(),
  ): DocumentAnalysis {
    val header = SourceImageLoader.inspect(originalUri)
    val width = header.uprightWidth
    val height = header.uprightHeight
    val input = loadInput(header, liveCorners)
    val candidate = try {
      val result = if (selection == null) session.analyze(input.bitmap) else session.analyze(input.bitmap, selection)
      result?.let { input.region.toPhoto(it.corners, width, height)?.let { corners -> it.copy(corners = corners) } }
    } finally {
      input.bitmap.recycle()
    }
    return DocumentAnalysis(width.toDouble(), height.toDouble(), (System.nanoTime() - startedAt) / 1_000_000.0, candidate)
  }

  /**
   * The region around [liveCorners], or the whole photo without one, decoded at half resolution: the model reads a far
   * smaller square, and the halving decode costs about half the time of a full-resolution one.
   */
  fun loadInput(header: SourceImageHeader, liveCorners: DocumentCorners?): Input {
    val region = PhotoInferenceCrop.from(liveCorners, header.uprightWidth, header.uprightHeight)
      ?: PhotoInferenceCrop(0, 0, header.uprightWidth, header.uprightHeight)
    return Input(region, SourceImageLoader.loadRegion(header, region, INPUT_SAMPLE_SIZE))
  }

  private const val INPUT_SAMPLE_SIZE = 2
}
