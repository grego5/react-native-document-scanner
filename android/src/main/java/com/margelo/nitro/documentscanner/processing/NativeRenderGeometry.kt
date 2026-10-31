package com.margelo.nitro.documentscanner.processing

import com.facebook.proguard.annotations.DoNotStrip
import com.margelo.nitro.documentscanner.DocumentPoint
import com.margelo.nitro.documentscanner.ReactNativeDocumentScannerOnLoad
import org.opencv.core.Mat

/** Synchronous owning conversion: source is borrowed; into receives independent pre-filter RGBA pixels. */
@DoNotStrip
internal object NativeRenderGeometry {
  init { ReactNativeDocumentScannerOnLoad.initializeNative() }
  data class Result(val width: Int, val height: Int, val validContentPolygon: List<DocumentPoint>, val curved: Boolean)
  fun render(source: Mat, into: Mat, quad: DoubleArray, originalScale: Double,
    paperLongEdge: Double?, followPageEdges: Boolean): Result {
    val values = renderNative(source.nativeObj, into.nativeObj, quad, originalScale, paperLongEdge ?: -1.0, followPageEdges)
    return Result(values[0].toInt(), values[1].toInt(), values.drop(3).chunked(2).map { DocumentPoint(it[0], it[1]) }, values[2] != 0.0)
  }
  @JvmStatic @DoNotStrip private external fun renderNative(source: Long, into: Long, quad: DoubleArray,
    originalScale: Double, paperLongEdge: Double, followPageEdges: Boolean): DoubleArray
}
