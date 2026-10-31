package com.margelo.nitro.documentscanner.processing

import com.facebook.proguard.annotations.DoNotStrip
import com.margelo.nitro.documentscanner.CreatePdfOptions
import com.margelo.nitro.documentscanner.ImageFit
import com.margelo.nitro.documentscanner.ReactNativeDocumentScannerOnLoad
import java.io.File
import java.io.OutputStream

@DoNotStrip
internal object NativePdfWriter {
  init { ReactNativeDocumentScannerOnLoad.initializeNative() }
  fun write(output: OutputStream, images: PdfImageAdapter, options: CreatePdfOptions) {
    val optional = arrayOf(options.pageWidth, options.pageHeight, options.pageAspectRatio, options.targetDpi, options.jpegQuality)
    var mask = 0
    val values = DoubleArray(optional.size) { index ->
      optional[index]?.also { mask = mask or (1 shl index) } ?: 0.0
    }
    val fit = when (options.imageFit ?: ImageFit.NONE) {
      ImageFit.NONE -> 0
      ImageFit.FILL -> 1
      ImageFit.CONTAIN -> 2
      ImageFit.COVER -> 3
    }
    writeNative(output, images, options.pages.size, values, mask, fit, options.autoRotateExif ?: true)
  }
  fun probe4(file: File): LongArray = AndroidPdfImages(arrayOf(file.absolutePath)).use { images ->
    val length = images.open(0)
    probeNative(images, length)
  }
  @JvmStatic @DoNotStrip private external fun writeNative(output: OutputStream, images: PdfImageAdapter, pageCount: Int,
    values: DoubleArray, mask: Int, fit: Int, autoRotateExif: Boolean)
  @JvmStatic @DoNotStrip private external fun probeNative(images: PdfImageAdapter, length: Long): LongArray
}
