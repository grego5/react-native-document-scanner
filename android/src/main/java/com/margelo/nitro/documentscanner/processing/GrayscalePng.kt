package com.margelo.nitro.documentscanner.processing

import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.math.abs
import kotlin.math.min

/** Sixteen evenly spaced gray levels, no palette, alpha channel or dithering. Caller owns each stream. */
internal object GrayscalePng {
  /** [readRow] fills an 8-bit gray row; quantization changes a value by at most 8/255. */
  fun write4(output: OutputStream, width: Int, height: Int, readRow: (Int, ByteArray) -> Unit) {
    require(width > 0 && height > 0) { "PNG dimensions must be positive." }
    val stream = DataOutputStream(output)
    stream.write(SIGNATURE)
    val header = java.io.ByteArrayOutputStream().also { bytes ->
      DataOutputStream(bytes).apply {
        writeInt(width)
        writeInt(height)
        write(byteArrayOf(4, 0, 0, 0, 0)) // 4 bits, grayscale, deflate, standard filters, no interlacing.
      }
    }.toByteArray()
    chunk(stream, "IHDR", header, header.size)
    compress4(width, height, readRow) { bytes, size -> chunk(stream, "IDAT", bytes, size) }
    chunk(stream, "IEND", ByteArray(0), 0)
  }

  /** The zlib/PNG-predictor stream, for a PDF image dictionary with Predictor 15 and BitsPerComponent 4. */
  fun writeCompressed4(output: OutputStream, width: Int, height: Int, readRow: (Int, ByteArray) -> Unit) {
    require(width > 0 && height > 0) { "Image dimensions must be positive." }
    compress4(width, height, readRow) { bytes, size -> output.write(bytes, 0, size) }
  }

  private fun compress4(width: Int, height: Int, readRow: (Int, ByteArray) -> Unit, emit: (ByteArray, Int) -> Unit) {
    val rowBytes = width / 2 + width % 2
    val gray = ByteArray(width)
    val packed = ByteArray(rowBytes)
    val previous = ByteArray(rowBytes)
    val candidates = Array(5) { kind -> ByteArray(rowBytes + 1).also { it[0] = kind.toByte() } }
    val scores = IntArray(5)
    val compressed = ByteArray(64 * 1024)
    val deflater = Deflater(DEFLATE_LEVEL)
    try {
      for (y in 0 until height) {
        readRow(y, gray)
        packed.fill(0)
        for (x in 0 until width) {
          val level = ((gray[x].toInt() and 255) + 8) / 17
          val shift = if (x % 2 == 0) 4 else 0
          packed[x / 2] = (packed[x / 2].toInt() or (level shl shift)).toByte()
        }
        scores.fill(0)
        for (x in 0 until rowBytes) {
          val value = packed[x].toInt() and 255
          val left = if (x == 0) 0 else packed[x - 1].toInt() and 255
          val above = previous[x].toInt() and 255
          val upperLeft = if (x == 0) 0 else previous[x - 1].toInt() and 255
          val p = left + above - upperLeft
          val a = abs(p - left)
          val b = abs(p - above)
          val c = abs(p - upperLeft)
          val paeth = if (a <= b && a <= c) left else if (b <= c) above else upperLeft
          for (kind in candidates.indices) {
            val predictor = when (kind) { 0 -> 0; 1 -> left; 2 -> above; 3 -> (left + above) / 2; else -> paeth }
            val residual = (value - predictor).toByte()
            candidates[kind][x + 1] = residual
            scores[kind] += abs(residual.toInt())
          }
        }
        val best = scores.indices.minBy { scores[it] }
        deflater.setInput(candidates[best])
        while (!deflater.needsInput()) {
          val count = deflater.deflate(compressed)
          if (count > 0) emit(compressed, count)
        }
        packed.copyInto(previous)
      }
      deflater.finish()
      while (!deflater.finished()) {
        val count = deflater.deflate(compressed)
        if (count > 0) emit(compressed, count)
      }
    } finally {
      deflater.end()
    }
  }

  private fun chunk(output: DataOutputStream, type: String, data: ByteArray, size: Int) {
    val kind = type.toByteArray(Charsets.US_ASCII)
    val crc = CRC32().apply { update(kind); update(data, 0, size) }
    output.writeInt(size)
    output.write(kind)
    output.write(data, 0, size)
    output.writeInt(crc.value.toInt())
  }

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

  /**
   * On a 2896x4096 black-and-white page on an SM-G998U, level 9 took 3.8 s for 1631 KB, level 6 0.84 s for 1662 KB and
   * level 4 0.37 s for 1694 KB; about 0.27 s of that is the row filters, which level 0 also pays.
   */
  private const val DEFLATE_LEVEL = 4
}
