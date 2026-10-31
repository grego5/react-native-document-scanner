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
    return RandomAccessFile(file, "r").use { input ->
      if (input.length() < 8) return null
      if (!ByteArray(8).also { input.readFully(it) }.contentEquals(SIGNATURE)) return null
      var width = 0
      var height = 0
      var transparent: Int? = null
      var ended = false
      val chunks = mutableListOf<Pair<Long, Int>>()
      while (input.filePointer + 12 <= input.length()) {
        val length = input.readInt()
        val type = ByteArray(4).also { input.readFully(it) }.toString(Charsets.US_ASCII)
        val start = input.filePointer
        if (length < 0 || start + length.toLong() + 4 > input.length()) return null
        if (width == 0 && type != "IHDR") return null
        when (type) {
          "IHDR" -> {
            if (length != 13 || width != 0) return null
            width = input.readInt()
            height = input.readInt()
            val header = ByteArray(5).also { input.readFully(it) }
            if (width <= 0 || height <= 0 || !header.contentEquals(byteArrayOf(4, 0, 0, 0, 0))) return null
          }
          "tRNS" -> {
            if (length != 2 || transparent != null) return null
            transparent = input.readUnsignedShort()
            if (transparent !in 0..15) return null
          }
          "IDAT" -> chunks += start to length
          "IEND" -> {
            if (length != 0) return null
            ended = true
            break
          }
          else -> if (type[0].isUpperCase()) return null
        }
        input.seek(start + length + 4)
      }
      if (!ended || chunks.sumOf { it.second.toLong() } == 0L) return null
      Image(width, height, transparent, file, chunks.toList())
    }
  }

  private val SIGNATURE = byteArrayOf(0x89.toByte(), 80, 78, 71, 13, 10, 26, 10)

}
