package com.margelo.nitro.documentscanner

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.facebook.soloader.SoLoader
import com.margelo.nitro.JNIOnLoad
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HybridDocumentScannerProcessorTest {
  private val context = InstrumentationRegistry.getInstrumentation().targetContext

  @Test
  fun analysisFailureRejectsNitroPromiseOnce() {
    initializeNitro()
    val processor = HybridDocumentScannerProcessor(context)
    val missing = File(context.cacheDir, "document-scanner-missing-${System.nanoTime()}.jpg")
    val completed = CountDownLatch(1)
    val callbacks = AtomicInteger()
    var failure: Throwable? = null

    try {
      processor.analyzePhoto(Uri.fromFile(missing).toString())
        .then {
          callbacks.incrementAndGet()
          completed.countDown()
        }
        .catch {
          failure = it
          callbacks.incrementAndGet()
          completed.countDown()
        }

      assertTrue("Analysis failure promise did not settle", completed.await(10, TimeUnit.SECONDS))
      assertNotNull(failure)
      assertEquals(1, callbacks.get())
    } finally {
      processor.dispose()
    }
  }

  @Test
  fun requestAfterProcessorDisposalRejectsNitroPromiseOnce() {
    initializeNitro()
    val processor = HybridDocumentScannerProcessor(context)
    processor.dispose()

    val completed = CountDownLatch(1)
    val callbacks = AtomicInteger()
    var failure: Throwable? = null
    processor.analyzePhoto("file:///unused.jpg")
      .then {
        callbacks.incrementAndGet()
        completed.countDown()
      }
      .catch {
        failure = it
        callbacks.incrementAndGet()
        completed.countDown()
      }

    assertTrue("Disposed processor promise did not settle", completed.await(10, TimeUnit.SECONDS))
    assertNotNull(failure)
    assertEquals(ScannerErrorCode.PROCESSOR_DISPOSED, (failure as? ScannerException)?.code)
    assertEquals(1, callbacks.get())
  }

  @Test
  fun removeCachedImagesDeletesOwnedFilesAndRejectsForeignOnes() {
    initializeNitro()
    val processor = HybridDocumentScannerProcessor(context)
    val owned = File(File(context.cacheDir, "react-native-document-scanner").apply { mkdirs() }, "removal-test.jpg")
      .apply { writeBytes(byteArrayOf(1)) }
    val foreign = File(context.cacheDir, "removal-test-foreign.jpg").apply { writeBytes(byteArrayOf(1)) }

    fun settle(uris: Array<String>): Throwable? {
      val completed = CountDownLatch(1)
      var failure: Throwable? = null
      processor.removeCachedImages(uris)
        .then { completed.countDown() }
        .catch {
          failure = it
          completed.countDown()
        }
      assertTrue("Removal promise did not settle", completed.await(10, TimeUnit.SECONDS))
      return failure
    }

    try {
      assertNotNull(settle(arrayOf(Uri.fromFile(owned).toString(), Uri.fromFile(foreign).toString())))
      assertTrue(owned.exists() && foreign.exists())
      assertNull(settle(arrayOf(Uri.fromFile(owned).toString())))
      assertTrue(!owned.exists() && foreign.exists())
    } finally {
      processor.dispose()
      owned.delete()
      foreign.delete()
    }
  }

  @Test
  fun saturatedProcessorRejectsExcessRequestsAndSettlesEachPromiseOnce() {
    initializeNitro()
    val image = createStressFixture()
    val processor = HybridDocumentScannerProcessor(context)
    val requestCount = 20
    val completed = CountDownLatch(requestCount)
    val callbacks = Array(requestCount) { AtomicInteger() }
    val busyRejections = AtomicInteger()

    try {
      repeat(requestCount) { index ->
        processor.analyzePhoto(Uri.fromFile(image).toString())
          .then {
            callbacks[index].incrementAndGet()
            completed.countDown()
          }
          .catch { error ->
            if ((error as? ScannerException)?.code == ScannerErrorCode.PROCESSOR_BUSY) busyRejections.incrementAndGet()
            callbacks[index].incrementAndGet()
            completed.countDown()
          }
      }

      assertTrue("Processor requests did not all settle", completed.await(30, TimeUnit.SECONDS))
      assertTrue("Saturated processor accepted every request", busyRejections.get() > 0)
      assertTrue("A request settled more than once", callbacks.all { it.get() == 1 })
    } finally {
      processor.dispose()
      image.delete()
    }
  }

  @Test
  fun disposalWithPendingAnalysisSettlesEveryPromiseOnce() {
    initializeNitro()
    val image = createStressFixture()
    val processor = HybridDocumentScannerProcessor(context)
    val completed = CountDownLatch(3)
    val callbacks = Array(3) { AtomicInteger() }
    try {
      repeat(3) { index ->
        processor.analyzePhoto(Uri.fromFile(image).toString())
          .then { callbacks[index].incrementAndGet(); completed.countDown() }
          .catch { callbacks[index].incrementAndGet(); completed.countDown() }
      }
      processor.dispose()
      assertTrue("Disposal left a promise pending", completed.await(15, TimeUnit.SECONDS))
      // Disposal removes promise ownership before the executor drains/ closes.
      // A late result cannot settle the same promise after the tracked rejection.
      assertTrue(callbacks.all { it.get() == 1 })
    } finally {
      processor.dispose()
      image.delete()
    }
  }

  private fun createStressFixture(): File {
    val image = File(context.cacheDir, "document-scanner-queue-${System.nanoTime()}.jpg")
    val bitmap = Bitmap.createBitmap(2400, 2400, Bitmap.Config.ARGB_8888)
    try {
      val canvas = Canvas(bitmap)
      val paint = Paint()
      val tileSize = 12
      for (y in 0 until bitmap.height step tileSize) {
        for (x in 0 until bitmap.width step tileSize) {
          paint.color = if ((x / tileSize + y / tileSize) % 2 == 0) Color.WHITE else Color.BLACK
          canvas.drawRect(x.toFloat(), y.toFloat(), (x + tileSize).toFloat(), (y + tileSize).toFloat(), paint)
        }
      }
      image.outputStream().use { output ->
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output))
      }
    } finally {
      bitmap.recycle()
    }
    return image
  }

  private fun initializeNitro() {
    SoLoader.init(context, false)
    JNIOnLoad.initializeNativeNitro()
    ReactNativeDocumentScannerOnLoad.initializeNative()
  }
}
