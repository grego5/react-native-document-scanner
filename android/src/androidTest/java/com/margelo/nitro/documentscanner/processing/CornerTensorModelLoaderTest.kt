package com.margelo.nitro.documentscanner.processing

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.facebook.soloader.SoLoader
import com.margelo.nitro.JNIOnLoad
import com.margelo.nitro.core.Promise
import com.margelo.nitro.documentscanner.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CornerTensorModelLoaderTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context = instrumentation.targetContext

  @Before fun initialize() {
    SoLoader.init(context, false)
    JNIOnLoad.initializeNativeNitro()
    ReactNativeDocumentScannerOnLoad.initializeNative()
  }

  private fun <T> await(promise: Promise<T>): T {
    val done = CountDownLatch(1)
    val value = AtomicReference<T>()
    val failure = AtomicReference<Throwable>()
    promise.then { value.set(it); done.countDown() }.catch { failure.set(it); done.countDown() }
    check(done.await(30, TimeUnit.SECONDS)) { "Model operation timed out." }
    failure.get()?.let { throw it }
    return value.get()
  }

  @Test fun missingSelectionHasNoBundledFallback() {
    val previous = CornerModelStore.selected
    try {
      CornerModelStore.activate(null)
      assertThrows(ScannerException::class.java) { CornerModelStore.current }
    } finally {
      CornerModelStore.activate(previous)
    }
  }

  @Test fun localModelOwnsItsBytesAndFailedLoadsKeepIt() {
    val file = File(context.cacheDir, "test-corner-model.tflite")
    file.writeBytes(context.assets.open(DocCornerSession.BUNDLED_ASSET).use { it.readBytes() })
    try {
      assertTrue(await(CornerTensorModelLoader.load(context, file.toURI().toString())))
      val active = CornerModelStore.current
      assertEquals(224, active.size.width)
      assertEquals(224, active.size.height)
      file.delete()
      active.open(context).use { it.warmUp() }
      assertThrows(Exception::class.java) {
        await(CornerTensorModelLoader.load(context, "/missing-scanner-model.tflite"))
      }
      assertSame(active, CornerModelStore.current)
      assertThrows(Exception::class.java) {
        await(CornerTensorModelLoader.load(context, "https://scanner-test.invalid/model.tflite"))
      }
      assertSame(active, CornerModelStore.current)
    } finally {
      await(CornerTensorModelLoader.load(context, "asset:///doccornernet/model.tflite"))
      file.delete()
    }
  }

  @Test fun packagedAssetCanBeActivated() {
    try {
      assertTrue(await(CornerTensorModelLoader.load(context, "asset:///doccornernet/model.tflite")))
      CornerModelStore.current.open(context).use { it.warmUp() }
    } finally {
      await(CornerTensorModelLoader.load(context, "asset:///doccornernet/model.tflite"))
    }
  }
}
