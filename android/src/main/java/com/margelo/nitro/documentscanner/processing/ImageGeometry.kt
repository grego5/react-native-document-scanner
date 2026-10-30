package com.margelo.nitro.documentscanner.processing

internal data class ImagePoint(val x: Double, val y: Double) {
  init {
    require(x.isFinite() && y.isFinite()) { "Image coordinates must be finite." }
  }
}

internal data class NormalizedPoint(val x: Double, val y: Double) {
  init {
    require(x.isFinite() && y.isFinite()) { "Normalized coordinates must be finite." }
  }
}

internal data class ImageQuad(
  val topLeft: ImagePoint,
  val topRight: ImagePoint,
  val bottomRight: ImagePoint,
  val bottomLeft: ImagePoint,
)

internal data class DocumentQuad(
  val topLeft: NormalizedPoint,
  val topRight: NormalizedPoint,
  val bottomRight: NormalizedPoint,
  val bottomLeft: NormalizedPoint,
)

internal object ImageGeometry {
  fun toNormalized(point: ImagePoint, width: Int, height: Int): NormalizedPoint {
    requireDimensions(width, height)
    return NormalizedPoint(point.x / width, point.y / height)
  }

  fun toImage(point: NormalizedPoint, width: Int, height: Int): ImagePoint {
    requireDimensions(width, height)
    return ImagePoint(point.x * width, point.y * height)
  }

  fun toNormalized(quad: ImageQuad, width: Int, height: Int): DocumentQuad = DocumentQuad(
    topLeft = toNormalized(quad.topLeft, width, height),
    topRight = toNormalized(quad.topRight, width, height),
    bottomRight = toNormalized(quad.bottomRight, width, height),
    bottomLeft = toNormalized(quad.bottomLeft, width, height),
  )

  fun toImage(quad: DocumentQuad, width: Int, height: Int): ImageQuad = ImageQuad(
    topLeft = toImage(quad.topLeft, width, height),
    topRight = toImage(quad.topRight, width, height),
    bottomRight = toImage(quad.bottomRight, width, height),
    bottomLeft = toImage(quad.bottomLeft, width, height),
  )

  private fun requireDimensions(width: Int, height: Int) {
    require(width > 0 && height > 0) { "Image dimensions must be positive." }
  }
}
