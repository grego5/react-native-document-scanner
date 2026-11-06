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

internal data class ImageCorners(
  val topLeft: ImagePoint,
  val topRight: ImagePoint,
  val bottomRight: ImagePoint,
  val bottomLeft: ImagePoint,
)

internal data class DocumentCorners(
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

  fun toNormalized(corners: ImageCorners, width: Int, height: Int): DocumentCorners =
    NativeGeometry.coordinates(corners.coordinates(), width, height, normalized = true).let {
      DocumentCorners(NormalizedPoint(it[0], it[1]), NormalizedPoint(it[2], it[3]),
        NormalizedPoint(it[4], it[5]), NormalizedPoint(it[6], it[7]))
    }

  fun toImage(corners: DocumentCorners, width: Int, height: Int): ImageCorners =
    NativeGeometry.coordinates(doubleArrayOf(corners.topLeft.x, corners.topLeft.y, corners.topRight.x, corners.topRight.y,
      corners.bottomRight.x, corners.bottomRight.y, corners.bottomLeft.x, corners.bottomLeft.y), width, height, normalized = false).let {
      ImageCorners(ImagePoint(it[0], it[1]), ImagePoint(it[2], it[3]), ImagePoint(it[4], it[5]), ImagePoint(it[6], it[7]))
    }
}
