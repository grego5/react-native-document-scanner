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
  fun toNormalized(point: ImagePoint, width: Int, height: Int): NormalizedPoint =
    NativeGeometry.coordinates(doubleArrayOf(point.x, point.y), width, height, normalized = true)
      .let { NormalizedPoint(it[0], it[1]) }

  fun toImage(point: NormalizedPoint, width: Int, height: Int): ImagePoint =
    NativeGeometry.coordinates(doubleArrayOf(point.x, point.y), width, height, normalized = false)
      .let { ImagePoint(it[0], it[1]) }

  fun toNormalized(quad: ImageQuad, width: Int, height: Int): DocumentQuad =
    NativeGeometry.coordinates(quad.coordinates(), width, height, normalized = true).let {
      DocumentQuad(NormalizedPoint(it[0], it[1]), NormalizedPoint(it[2], it[3]),
        NormalizedPoint(it[4], it[5]), NormalizedPoint(it[6], it[7]))
    }

  fun toImage(quad: DocumentQuad, width: Int, height: Int): ImageQuad =
    NativeGeometry.coordinates(doubleArrayOf(quad.topLeft.x, quad.topLeft.y, quad.topRight.x, quad.topRight.y,
      quad.bottomRight.x, quad.bottomRight.y, quad.bottomLeft.x, quad.bottomLeft.y), width, height, normalized = false).let {
      ImageQuad(ImagePoint(it[0], it[1]), ImagePoint(it[2], it[3]), ImagePoint(it[4], it[5]), ImagePoint(it[6], it[7]))
    }
}
