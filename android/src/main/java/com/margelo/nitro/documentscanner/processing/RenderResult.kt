package com.margelo.nitro.documentscanner.processing

import com.margelo.nitro.documentscanner.DocumentPoint

/** Private renderer artifacts; sessions own their lifetime and never publish these paths to JS. */
internal data class RenderResult(
  val originalUri: String,
  val outputUri: String,
  val thumbnailUri: String,
  val width: Double,
  val height: Double,
  val validContentPolygon: Array<DocumentPoint>,
)
