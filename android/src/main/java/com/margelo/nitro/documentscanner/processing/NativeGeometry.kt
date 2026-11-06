package com.margelo.nitro.documentscanner.processing

import com.facebook.proguard.annotations.DoNotStrip
import com.margelo.nitro.documentscanner.DocumentPoint
import com.margelo.nitro.documentscanner.DocumentCorners as NitroDocumentCorners
import com.margelo.nitro.documentscanner.ReactNativeDocumentScannerOnLoad

/** Synchronous value-only JNI boundary. No resources, pointers or gesture state escape a call. */
internal object NativeGeometry {
  init { ReactNativeDocumentScannerOnLoad.initializeNative() }

  fun uprightSize(width: Int, height: Int, orientation: Int): IntArray = uprightSizeNative(width, height, orientation)
  fun orientationMatrix(width: Int, height: Int, orientation: Int): FloatArray =
    orientationNative(width, height, orientation).map(Double::toFloat).toFloatArray()
  fun sourcePoint(point: ImagePoint, ew: Int, eh: Int, sw: Int, sh: Int, orientation: Int, inverse: Boolean): ImagePoint =
    sourcePointNative(point.x, point.y, ew, eh, sw, sh, orientation, inverse).let { ImagePoint(it[0], it[1]) }
  fun coordinates(points: DoubleArray, width: Int, height: Int, normalized: Boolean): DoubleArray =
    coordinatesNative(points, width, height, normalized)
  fun inferenceCrop(corners: NitroDocumentCorners, width: Int, height: Int): IntArray? = inferenceCropNative(corners.coordinates(), width, height)
  fun toPhoto(corners: NitroDocumentCorners, crop: PhotoInferenceCrop, width: Int, height: Int): NitroDocumentCorners =
    regionNative(corners.coordinates(), crop.left, crop.top, crop.width, crop.height, width, height).toNitroCorners()
  fun clamp(corners: DoubleArray): DoubleArray = clampNative(corners)
  fun validCrop(corners: DoubleArray): Boolean = validCropNative(corners)
  fun move(corners: DoubleArray, index: Int, x: Double, y: Double, edge: Boolean): DoubleArray? = moveNative(corners, index, x, y, edge)
  fun validateRender(corners: ImageCorners) = validateRenderNative(corners.coordinates())
  fun target(corners: List<ImagePoint>, scale: Double, paperLongEdge: Double?): IntArray =
    targetNative(corners.flatMap { listOf(it.x, it.y) }.toDoubleArray(), scale, paperLongEdge ?: -1.0).map(Double::toInt).toIntArray()
  fun outputPolygon(corners: List<ImagePoint>, width: Int, height: Int, homography: DoubleArray, targetWidth: Int, targetHeight: Int): List<DocumentPoint> =
    outputPolygonNative(corners.flatMap { listOf(it.x, it.y) }.toDoubleArray(), width, height, homography, targetWidth, targetHeight)
      .asList().chunked(2).map { DocumentPoint(it[0], it[1]) }

  @JvmStatic @DoNotStrip private external fun uprightSizeNative(w: Int, h: Int, orientation: Int): IntArray
  @JvmStatic @DoNotStrip private external fun orientationNative(w: Int, h: Int, orientation: Int): DoubleArray
  @JvmStatic @DoNotStrip private external fun sourcePointNative(x: Double, y: Double, ew: Int, eh: Int, sw: Int, sh: Int, orientation: Int, inverse: Boolean): DoubleArray
  @JvmStatic @DoNotStrip private external fun coordinatesNative(points: DoubleArray, w: Int, h: Int, normalized: Boolean): DoubleArray
  @JvmStatic @DoNotStrip private external fun inferenceCropNative(corners: DoubleArray, w: Int, h: Int): IntArray?
  @JvmStatic @DoNotStrip private external fun regionNative(corners: DoubleArray, l: Int, t: Int, w: Int, h: Int, pw: Int, ph: Int): DoubleArray
  @JvmStatic @DoNotStrip private external fun clampNative(corners: DoubleArray): DoubleArray
  @JvmStatic @DoNotStrip private external fun validCropNative(corners: DoubleArray): Boolean
  @JvmStatic @DoNotStrip private external fun moveNative(corners: DoubleArray, index: Int, x: Double, y: Double, edge: Boolean): DoubleArray?
  @JvmStatic @DoNotStrip private external fun validateRenderNative(corners: DoubleArray)
  @JvmStatic @DoNotStrip private external fun targetNative(corners: DoubleArray, scale: Double, paper: Double): DoubleArray
  @JvmStatic @DoNotStrip private external fun outputPolygonNative(corners: DoubleArray, w: Int, h: Int, homography: DoubleArray, tw: Int, th: Int): DoubleArray
}

internal fun NitroDocumentCorners.coordinates(): DoubleArray = doubleArrayOf(
  topLeft.x, topLeft.y, topRight.x, topRight.y, bottomRight.x, bottomRight.y, bottomLeft.x, bottomLeft.y,
)
internal fun ImageCorners.coordinates(): DoubleArray = doubleArrayOf(
  topLeft.x, topLeft.y, topRight.x, topRight.y, bottomRight.x, bottomRight.y, bottomLeft.x, bottomLeft.y,
)
private fun DoubleArray.toNitroCorners() = NitroDocumentCorners(
  DocumentPoint(this[0], this[1]), DocumentPoint(this[2], this[3]), DocumentPoint(this[4], this[5]), DocumentPoint(this[6], this[7]),
)
