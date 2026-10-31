package com.margelo.nitro.documentscanner.processing

import com.facebook.proguard.annotations.DoNotStrip
import com.margelo.nitro.documentscanner.DocumentFilter
import com.margelo.nitro.documentscanner.ReactNativeDocumentScannerOnLoad
import org.opencv.core.Mat

/** Synchronous core stage; source stays borrowed until the independent output is copied. */
@DoNotStrip
internal object NativeDocumentFilters {
  init { ReactNativeDocumentScannerOnLoad.initializeNative() }
  fun apply(source: Mat, into: Mat, filter: DocumentFilter, shadows: Boolean) {
    val value = when (filter) {
      DocumentFilter.NONE -> 0
      DocumentFilter.ENHANCE -> 1
      DocumentFilter.GRAYSCALE -> 2
      DocumentFilter.BLACKWHITE -> 3
    }
    applyNative(source.nativeObj, into.nativeObj, value, shadows)
  }
  fun shadows(source: Mat) = applyNative(source.nativeObj, source.nativeObj, -1, true)
  @JvmStatic @DoNotStrip private external fun applyNative(source: Long, into: Long, filter: Int, shadows: Boolean)
}
