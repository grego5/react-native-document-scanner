package com.margelo.nitro.documentscanner.processing

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import kotlin.math.min

/** Sixteen evenly spaced gray levels, no palette, alpha channel or dithering. Caller owns each stream. */
internal object GrayscalePng {
  /** [readRow] fills one borrowed gray row; the shared writer owns quantization/PNG/zlib. */
  fun write4(output: OutputStream, width: Int, height: Int, readRow: (Int, ByteArray) -> Unit) =
    NativeGrayscalePng.write(output, width, height, false, readRow)

  /** Zlib/PNG-predictor stream for a four-bit PDF image. Caller owns the stream. */
  fun writeCompressed4(output: OutputStream, width: Int, height: Int, readRow: (Int, ByteArray) -> Unit) =
    NativeGrayscalePng.write(output, width, height, true, readRow)

  /** Compressed IDAT data stays in the source file until [copyCompressedTo] is called. */
  class Image internal constructor(
    val width: Int,
    val height: Int,
    val transparentSample: Int?,
    private val file: File,
    private val chunks: List<Pair<Long, Int>>,
  ) {
    val compressedLength: Long = chunks.sumOf { it.second.toLong() }

    fun copyCompressedTo(output: OutputStream) {
      RandomAccessFile(file, "r").use { source ->
        val buffer = ByteArray(64 * 1024)
        for ((offset, length) in chunks) {
          source.seek(offset)
          var remaining = length
          while (remaining > 0) {
            val count = source.read(buffer, 0, min(buffer.size, remaining))
            if (count < 0) throw IOException("PNG '${file.path}' ended early.")
            output.write(buffer, 0, count)
            remaining -= count
          }
        }
      }
    }
  }

  /** Null unless this is a non-interlaced 4-bit grayscale PNG. Other images use the ordinary bitmap decoder. */
  fun read4(file: File): Image? {
    val data = NativePdfWriter.probe4(file)
    if (data.isEmpty()) return null
    return Image(data[0].toInt(), data[1].toInt(), data[2].takeIf { it >= 0 }?.toInt(), file,
      data.drop(3).chunked(2).map { it[0] to it[1].toInt() })
  }
}
