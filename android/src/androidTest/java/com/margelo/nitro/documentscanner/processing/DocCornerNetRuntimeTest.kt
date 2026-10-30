package com.margelo.nitro.documentscanner.processing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.facebook.soloader.SoLoader
import com.margelo.nitro.JNIOnLoad
import com.margelo.nitro.documentscanner.DocumentCandidate
import com.margelo.nitro.documentscanner.assumeMeasuring
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.hypot

@RunWith(AndroidJUnit4::class)
class DocCornerNetRuntimeTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context = instrumentation.targetContext
  private val fixtures = instrumentation.context.assets

  @Before
  fun initializeRuntime() {
    SoLoader.init(context, false)
    JNIOnLoad.initializeNativeNitro()
    QuadModelStore.activate(QuadModelStore.prepare(context, "asset:///doccornernet/model.tflite"))
  }

  @Test
  fun concurrentLeasesSerializeInferenceAndClosingOneKeepsTheOtherUsable() {
    val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
    val first = CornerInference.acquire(context)
    val second = CornerInference.acquire(context)
    val callers = java.util.concurrent.Executors.newFixedThreadPool(2)
    try {
      val expected = values(first.analyze(bitmap))
      val jobs = listOf(first, second).map { lease ->
        callers.submit {
          repeat(5) { assertArrayEquals(expected, values(lease.analyze(bitmap)), 1e-6) }
        }
      }
      jobs.forEach { it.get(30, java.util.concurrent.TimeUnit.SECONDS) }
      first.close()
      assertThrows(IllegalStateException::class.java) { first.analyze(bitmap) }
      assertArrayEquals(expected, values(second.analyze(bitmap)), 1e-6)
      assertFalse(bitmap.isRecycled)
    } finally {
      callers.shutdown()
      callers.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)
      first.close()
      second.close()
      bitmap.recycle()
    }
  }

  @Test
  fun replacementUsesOwnedBytesAndFailuresKeepPreviousModel() {
    val previous = QuadModelStore.current
    val file = File.createTempFile("corner-model-", ".tflite", context.cacheDir)
    try {
      file.writeBytes(context.assets.open("doccornernet/model.tflite").use { it.readBytes() })
      val replacement = QuadModelStore.prepare(context, Uri.fromFile(file).toString())
      assertEquals(224, replacement.size.width)
      assertEquals(224, replacement.size.height)
      QuadModelStore.activate(replacement)
      assertSame(replacement, QuadModelStore.current)
      assertSame(replacement, QuadModelStore.current)
      file.writeBytes(byteArrayOf(1, 2, 3))
      assertThrows(Exception::class.java) { QuadModelStore.prepare(context, file.absolutePath) }
      assertSame(replacement, QuadModelStore.current)
      file.delete()
      replacement.open(context).use { it.warmUp() }
      assertThrows(Exception::class.java) { QuadModelStore.prepare(context, file.absolutePath) }
      assertThrows(Exception::class.java) { QuadModelStore.prepare(context, "https://example.com/model.tflite") }
      assertThrows(Exception::class.java) { QuadModelStore.prepare(context, "relative.tflite") }
      assertSame(replacement, QuadModelStore.current)
      QuadModelStore.activate(QuadModelStore.prepare(context, "asset:///doccornernet/model.tflite"))
      QuadModelStore.current.open(context).use { it.warmUp() }
    } finally {
      QuadModelStore.activate(previous)
      file.delete()
    }
  }

  @Test
  fun repeatedSessionCallsBorrowBitmapAndCloseIsIdempotent() {
    val bitmap = Bitmap.createBitmap(224, 224, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
    val session = DocCornerSession(context)
    try {
      val first = values(session.analyze(bitmap))
      repeat(5) { assertArrayEquals(first, values(session.analyze(bitmap)), 1e-6) }
      assertFalse(bitmap.isRecycled)
      session.close()
      session.close()
      assertThrows(IllegalStateException::class.java) { session.analyze(bitmap) }
      assertFalse(bitmap.isRecycled)
    } finally {
      session.close()
      bitmap.recycle()
    }
  }

  @Test
  fun missingAndIncompatibleAssetsFailWithoutPoisoningNextSession() {
    assertThrows(java.io.FileNotFoundException::class.java) { DocCornerSession(context, "missing-model.tflite") }
    requireFixtures()
    repeat(5) {
      assertThrows(IllegalStateException::class.java) {
        DocCornerSession(instrumentation.context, "doccornernet-runtime/wrong-shape.tflite")
      }
    }
    DocCornerSession(context).use { /* Initialization after rejected assets remains usable. */ }
  }

  @Test
  fun privatePhotosMeetNativeParityBudgetAndRecordWarmTimings() {
    assumeMeasuring()
    requireFixtures()
    val references = JSONObject(fixtures.open("doccornernet-runtime/references.json").bufferedReader().use { it.readText() })
    val modelBytes = context.assets.open("doccornernet/model.tflite").use { it.readBytes() }
    assertEquals(references.getString("modelSha256"), sha256(modelBytes))
    val model = ByteBuffer.allocateDirect(modelBytes.size).order(ByteOrder.nativeOrder()).apply { put(modelBytes); rewind() }
    val rows = JSONArray()
    val pngErrors = mutableListOf<Double>()
    val jpegErrors = mutableListOf<Double>()
    val reports = File(context.filesDir, "doccornernet-runtime").apply { mkdirs() }
    val report = JSONObject().put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
      .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList())).put("modelSha256", sha256(modelBytes)).put("photos", rows)
      .put("photoMaxDimension", DocCornerSession.MAX_PHOTO_DIMENSION)
    Interpreter(model, Interpreter.Options().setNumThreads(2)).use { tensorRuntime ->
      DocCornerSession(context).use { session ->
        val photos = references.getJSONArray("photos")
        for (index in 0 until photos.length()) {
          val reference = photos.getJSONObject(index)
          val name = reference.getString("name")
          val expected = reference.getJSONArray("tensorflow")
          val tensor = fixtures.open("doccornernet-runtime/$name.input.f32").use { it.readBytes() }
          val input = ByteBuffer.allocateDirect(tensor.size).order(ByteOrder.LITTLE_ENDIAN).apply { put(tensor); rewind() }
          val output = Array(1) { FloatArray(9) }
          tensorRuntime.run(input, output)
          val exportError = (0..8).maxOf { kotlin.math.abs(output[0][it] - expected.getDouble(it)) }
          assertTrue("$name tensor/model parity $exportError", exportError <= 2e-5)

          val upright = fixtures.open("doccornernet-runtime/$name.png").use { BitmapFactory.decodeStream(it)!! }
          val png: DoubleArray
          try {
            png = values(session.analyze(upright))
          } finally { upright.recycle() }
          val file = File.createTempFile("parity-", ".jpg", context.cacheDir)
          val jpeg: DoubleArray
          var baseline = DoubleArray(0)
          var decodedWidth = 0
          var decodedHeight = 0
          var decodeMs = 0.0
          try {
            val bytes = fixtures.open("doccornernet-runtime/$name.jpg").use { it.readBytes() }
            assertEquals(reference.getString("sha256"), sha256(bytes))
            file.writeBytes(bytes)
            SourceImageLoader.load(Uri.fromFile(file).toString(), 1600).use { source ->
              baseline = values(session.analyze(source.bitmap))
            }
            val decodeStartedAt = System.nanoTime()
            SourceImageLoader.load(Uri.fromFile(file).toString(), DocCornerSession.MAX_PHOTO_DIMENSION).use { source ->
              decodeMs = (System.nanoTime() - decodeStartedAt) / 1_000_000.0
              decodedWidth = source.uprightWidth
              decodedHeight = source.uprightHeight
              val rotated = source.exifOrientation in 5..8
              assertEquals(reference.getInt("width"), if (rotated) source.encodedHeight else source.encodedWidth)
              assertEquals(reference.getInt("height"), if (rotated) source.encodedWidth else source.encodedHeight)
              jpeg = values(session.analyze(source.bitmap))
              val warm = values(session.analyze(source.bitmap))
              assertArrayEquals(jpeg, warm, 1e-6)
            }
          } finally { file.delete() }
          val row = JSONObject().put("name", name).put("exportMaxError", exportError)
            .put("uprightPngOutput", JSONArray(png.toList())).put("sampledJpegOutput", JSONArray(jpeg.toList()))
            .put("baseline1600Output", JSONArray(baseline.toList())).put("reference", expected)
            .put("decodedWidth", decodedWidth).put("decodedHeight", decodedHeight)
            .put("decodeMs", decodeMs).put("warmPipelineMs", decodeMs + session.lastPreprocessingMs + session.lastInferenceMs)
            .put("preprocessingMs", session.lastPreprocessingMs).put("inferenceMs", session.lastInferenceMs)
          rows.put(row)
          for (corner in 0..3) {
            pngErrors.add(hypot(png[corner * 2] - expected.getDouble(corner * 2), png[corner * 2 + 1] - expected.getDouble(corner * 2 + 1)))
            jpegErrors.add(hypot(jpeg[corner * 2] - expected.getDouble(corner * 2), jpeg[corner * 2 + 1] - expected.getDouble(corner * 2 + 1)))
          }
          // Keep the diagnostic even when the final aggregate budget fails.
          File(reports, "report.json").writeText(report.toString(2) + "\n")
          if (name == "sample-13") {
            assertTrue("Missing top-right corner reconstruction changed", hypot(jpeg[2] - expected.getDouble(2), jpeg[3] - expected.getDouble(3)) <= .015)
          }
        }
      }
    }
    report.put("pngMeanCornerError", pngErrors.average()).put("pngWorstCornerError", pngErrors.maxOrNull())
      .put("jpegMeanCornerError", jpegErrors.average()).put("jpegWorstCornerError", jpegErrors.maxOrNull())
    File(reports, "report.json").writeText(report.toString(2) + "\n")
    assertTrue("Upright PNG resampling parity", pngErrors.average() <= .005 && pngErrors.max() <= .015)
    assertTrue("Production sampled JPEG parity", jpegErrors.average() <= .005 && jpegErrors.max() <= .015)
  }

  private fun requireFixtures() = assumeTrue("Pass -PdocCornerFixtures=<prepared assets>; private fixtures are not bundled by default.",
    fixtures.list("doccornernet-runtime")?.contains("references.json") == true)

  private fun values(candidate: DocumentCandidate?): DoubleArray {
    assertNotNull("Model did not return a valid quad", candidate)
    val quad = candidate!!.quad
    return doubleArrayOf(quad.topLeft.x, quad.topLeft.y, quad.topRight.x, quad.topRight.y,
      quad.bottomRight.x, quad.bottomRight.y, quad.bottomLeft.x, quad.bottomLeft.y, candidate.score)
  }

  private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
