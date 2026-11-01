package com.margelo.nitro.documentscanner.processing

import com.facebook.proguard.annotations.DoNotStrip
import com.margelo.nitro.documentscanner.DocumentPoint
import com.margelo.nitro.documentscanner.DocumentFilter
import com.margelo.nitro.documentscanner.ReactNativeDocumentScannerOnLoad
import org.opencv.core.Mat

/** Synchronous owning conversion: source is borrowed; into receives independent RGBA or filtered BGR/gray. */
@DoNotStrip
internal object NativeRenderGeometry {
  init { ReactNativeDocumentScannerOnLoad.initializeNative() }
  data class Result(val width: Int, val height: Int, val validContentPolygon: List<DocumentPoint>, val curved: Boolean)
  fun render(source: Mat, into: Mat, quad: DoubleArray, originalScale: Double,
    paperLongEdge: Double?, followPageEdges: Boolean, filter: DocumentFilter? = null, removeShadows: Boolean = false): Result {
    val filterValue = when (filter) {
      null -> -1
      DocumentFilter.NONE -> 0
      DocumentFilter.ENHANCE -> 1
      DocumentFilter.GRAYSCALE -> 2
      DocumentFilter.BLACKWHITE -> 3
    }
    val values = renderNative(source.nativeObj, into.nativeObj, quad, originalScale, paperLongEdge ?: -1.0,
      followPageEdges, filterValue, removeShadows)
    return Result(values[0].toInt(), values[1].toInt(), values.drop(3).chunked(2).map { DocumentPoint(it[0], it[1]) }, values[2] != 0.0)
  }
  @JvmStatic @DoNotStrip private external fun renderNative(source: Long, into: Long, quad: DoubleArray,
    originalScale: Double, paperLongEdge: Double, followPageEdges: Boolean, filter: Int, removeShadows: Boolean): DoubleArray
}
