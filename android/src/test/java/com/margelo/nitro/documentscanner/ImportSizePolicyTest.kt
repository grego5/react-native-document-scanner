package com.margelo.nitro.documentscanner

import com.margelo.nitro.documentscanner.processing.DocumentRenderer
import org.junit.Assert.assertEquals
import org.junit.Test

class ImportSizePolicyTest {
  private fun options(width: Double? = null, height: Double? = null, dpi: Double? = null) = DocumentRenderer.effectiveOptions(
    DocumentRenderOptions(null, null, null, null, width?.let { PaperSize(it, requireNotNull(height)) }, dpi, null))
  private fun target(width: Int, height: Int, options: DocumentRenderOptions) =
    ImportSizePolicy.target(width, height, options).let { listOf(it.width, it.height) }

  @Test fun coversA4At200DpiInTheImagesOwnOrientation() {
    val a4 = options(21.0, 29.7)
    // A4 at the default 200 dpi is 1654 x 2339 px; the scale keeps both sides at least that large.
    assertEquals(listOf(1754, 2339), target(3000, 4000, a4))
    assertEquals(listOf(2339, 1754), target(4000, 3000, a4))
    // A long receipt-shaped image is limited by its short side.
    assertEquals(listOf(1654, 6615), target(2000, 8000, a4))
  }

  @Test fun followsTheConfiguredDpiAndIgnoresWhichPaperSideIsLonger() {
    assertEquals(target(3000, 4000, options(21.0, 29.7, 300.0)), target(3000, 4000, options(29.7, 21.0, 300.0)))
    assertEquals(listOf(2631, 3508), target(3000, 4000, options(21.0, 29.7, 300.0)))
  }

  @Test fun neverEnlargesAndUsesTheRenderLimitWithoutPaper() {
    assertEquals(listOf(1200, 1600), target(1200, 1600, options(21.0, 29.7)))
    assertEquals(listOf(3072, 4096), target(6000, 8000, options()))
    assertEquals(listOf(4000, 3000), target(4000, 3000, options()))
  }
}
