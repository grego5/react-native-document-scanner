package com.margelo.nitro.documentscanner.processing

import com.facebook.proguard.annotations.DoNotStrip
import com.margelo.nitro.documentscanner.ReactNativeDocumentScannerOnLoad
import java.io.OutputStream

@DoNotStrip
internal fun interface GrayPngRows {
  fun read(y: Int, row: ByteArray)
}

/** Synchronous row/sink adapter. Caller owns the stream, files and bitmap/Mat lifetimes. */
@DoNotStrip
internal object NativeGrayscalePng {
  init { ReactNativeDocumentScannerOnLoad.initializeNative() }
  fun write(output: OutputStream, width: Int, height: Int, compressedOnly: Boolean, readRow: (Int, ByteArray) -> Unit) {
    require(width > 0 && height > 0) { "PNG dimensions must be positive." }
    writeNative(output, width, height, GrayPngRows { y, row -> readRow(y, row) }, compressedOnly)
  }
  @JvmStatic @DoNotStrip private external fun writeNative(output: OutputStream, width: Int, height: Int,
    rows: GrayPngRows, compressedOnly: Boolean)
}
