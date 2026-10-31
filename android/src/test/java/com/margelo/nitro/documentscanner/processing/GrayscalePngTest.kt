package com.margelo.nitro.documentscanner.processing

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.Random
import java.util.zip.CRC32
import java.util.zip.InflaterInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GrayscalePngTest {
  @get:Rule val directory = TemporaryFolder()

  @Test
  fun multipleDataChunksCopyAsOneCompleteZlibStream() {
    val random = Random(7)
    val rows = Array(400) { ByteArray(1025).also(random::nextBytes) }
    val file = fixture("multi-idat.png")
    val metadata = GrayscalePng.read4(file)!!
    val compressed = ByteArrayOutputStream().also(metadata::copyCompressedTo).toByteArray()
    assertEquals(compressed.size.toLong(), metadata.compressedLength)
    val filtered = InflaterInputStream(ByteArrayInputStream(compressed)).use { it.readBytes() }
    assertEquals(400 * (513 + 1), filtered.size)
    val decoded = decode(file)
    assertEquals(1025, decoded.width)
    assertEquals(400, decoded.height)
    val factor = if (decoded.sampleBits == 8) 17 else 1
    for (y in rows.indices) for (x in listOf(0, 127, 1024)) {
      assertEquals("random pixel $x,$y", (((rows[y][x].toInt() and 255) + 8) / 17) * factor,
        decoded.sample(x, y))
    }
    val input = DataInputStream(ByteArrayInputStream(file.readBytes()))
    input.skipBytes(8)
    var count = 0
    while (input.available() > 0) {
      val length = input.readInt()
      val type = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
      if (type == "IDAT") count++
      input.skipBytes(length + 4)
    }
    assertTrue("Exercise more than one IDAT chunk", count > 1)
  }

  @Test
  fun fourBitTransparencyIsExposedForPdfMasking() {
    val file = fixture("transparency-source.png")
    val before = file.readBytes()
    val transparent = byteArrayOf(0, 7)
    val type = "tRNS".toByteArray(Charsets.US_ASCII)
    val crc = CRC32().apply { update(type); update(transparent) }
    file.outputStream().use { raw ->
      val output = DataOutputStream(raw)
      output.write(before, 0, 33)
      output.writeInt(2)
      output.write(type)
      output.write(transparent)
      output.writeInt(crc.value.toInt())
      output.write(before, 33, before.size - 33)
    }
    assertEquals(7, GrayscalePng.read4(file)!!.transparentSample)
  }

  @Test
  fun eightBitAndBinaryInputsDoNotUseFourBitPassthrough() {
    for (type in listOf(10, 12)) {
      val file = directory.newFile("other-$type.png")
      val imageClass = Class.forName("java.awt.image.BufferedImage")
      val image = imageClass.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        Int::class.javaPrimitiveType).newInstance(8, 8, type)
      val written = Class.forName("javax.imageio.ImageIO").getMethod("write",
        Class.forName("java.awt.image.RenderedImage"), String::class.java, File::class.java).invoke(null, image, "png", file)
      assertEquals(true, written)
      assertNull(GrayscalePng.read4(file))
    }
  }

  @Test
  fun aTruncatedPngDoesNotQualifyForPassthrough() {
    val file = fixture("truncated-source.png")
    file.writeBytes(file.readBytes().dropLast(12).toByteArray())
    assertNull(GrayscalePng.read4(file))
  }

  // Android's compile classpath omits java.desktop; the host JVM still supplies this independent PNG decoder.
  private fun decode(file: File): Decoded {
    val image = Class.forName("javax.imageio.ImageIO").getMethod("read", File::class.java).invoke(null, file)
    assertNotNull(image)
    return Decoded(image)
  }

  private class Decoded(private val image: Any) {
    private val imageClass = Class.forName("java.awt.image.BufferedImage")
    private val raster = imageClass.getMethod("getRaster").invoke(image)
    private val rasterClass = Class.forName("java.awt.image.Raster")
    val width = imageClass.getMethod("getWidth").invoke(image) as Int
    val height = imageClass.getMethod("getHeight").invoke(image) as Int
    val sampleBits: Int = run {
      val model = rasterClass.getMethod("getSampleModel").invoke(raster)
      Class.forName("java.awt.image.SampleModel").getMethod("getSampleSize", Int::class.javaPrimitiveType)
        .invoke(model, 0) as Int
    }
    fun sample(x: Int, y: Int): Int = rasterClass.getMethod("getSample", Int::class.javaPrimitiveType,
      Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(raster, x, y, 0) as Int
  }

  // Independent synthetic PNGs keep parser/PDF-copy assertions on the JVM after
  // quantization/packing/compression assertions moved to portable GrayscalePngTest.cpp.
  private fun fixture(name: String): File = directory.newFile().also { file ->
    val resource = javaClass.classLoader!!.getResourceAsStream("parity/gray-png/$name")
      ?: error("Missing PNG parser fixture $name")
    resource.use { input -> file.outputStream().use { input.copyTo(it) } }
  }
}
