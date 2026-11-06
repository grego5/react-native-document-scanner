package com.margelo.nitro.documentscanner.processing

import com.margelo.nitro.documentscanner.ImageFit

/** Native writer input. Only session snapshots supply source paths; it is not a bridge contract. */
internal data class PdfWriteOptions(
  val outputPath: String,
  val pages: Array<String>,
  val imageFit: ImageFit? = null,
  val autoRotateExif: Boolean? = null,
  val jpegQuality: Double? = null,
  val targetDpi: Double? = null,
  val pageWidth: Double? = null,
  val pageHeight: Double? = null,
  val pageAspectRatio: Double? = null,
)
