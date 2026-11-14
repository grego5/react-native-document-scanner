package com.margelo.nitro.documentscanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.margelo.nitro.documentscanner.processing.DocumentRenderer
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic JPEGs only: a JPEG that already fits keeps its bytes, so its pixels must still decode. */
@RunWith(AndroidJUnit4::class)
class ImageImportDeviceTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val folder = File(context.cacheDir, "image-import-test-" + UUID.randomUUID()).apply { mkdirs() }
  private val options = DocumentRenderer.effectiveOptions(DocumentRenderOptions(null, null, null, null, null, null, null))

  @After fun clean() { folder.deleteRecursively() }

  private fun jpeg(): ByteArray {
    val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
    return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it); bitmap.recycle() }.toByteArray()
  }

  private fun write(bytes: ByteArray): File = File(folder, UUID.randomUUID().toString()).also { destination ->
    ImageImport.writeOriginal({ bytes.inputStream() }, destination, options)
  }

  @Test fun aFittingJpegKeepsItsBytes() {
    val bytes = jpeg()
    assertArrayEquals(bytes, write(bytes).readBytes())
  }

  private fun u16(bytes: ByteArray, at: Int) = ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)

  /**
   * A copy whose first DC Huffman table carries a symbol above 15. libjpeg parses the tables while reading the header,
   * so bounds still decode through the start of scan, but it rejects the table once pixel decoding builds it. The
   * whole scan stays in place.
   */
  private fun withUndecodablePixels(bytes: ByteArray): ByteArray {
    val copy = bytes.copyOf()
    var at = 2
    var invalid = false
    var scan = false
    while (at + 4 <= copy.size && !scan) {
      assertEquals(0xFF.toByte(), copy[at])
      val marker = copy[at + 1].toInt() and 0xFF
      val end = at + 2 + u16(copy, at + 2)
      scan = marker == 0xDA
      var table = at + 4
      while (marker == 0xC4 && !invalid && table < end) {
        val count = (1..16).sumOf { copy[table + it].toInt() and 0xFF }
        if ((copy[table].toInt() and 0xF0) == 0 && count > 0) { copy[table + 17] = 0x20; invalid = true }
        table += 17 + count
      }
      at = end
    }
    assertTrue("The fixture changes a DC table and keeps the start of scan.", invalid && scan)
    return copy
  }

  @Test fun aFittingJpegWithAReadableHeaderButUndecodablePixelsIsAnImageFailure() {
    val corrupt = withUndecodablePixels(jpeg())
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(corrupt, 0, corrupt.size, bounds)
    assertEquals("The header alone still reads.", 64, bounds.outWidth)
    val error = assertThrows(ScannerException::class.java) { write(corrupt) }
    assertEquals(ScannerErrorCode.IMAGE_LOAD_FAILED, error.code)
  }
}
