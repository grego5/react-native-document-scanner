package com.margelo.nitro.documentscanner

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.facebook.soloader.SoLoader
import com.margelo.nitro.JNIOnLoad
import com.margelo.nitro.core.Promise
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises real generated result/callback structs and Nitro promises; never invokes a camera. */
@RunWith(AndroidJUnit4::class)
class ScanSessionBridgeTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context = instrumentation.targetContext

  private fun initialize() {
    SoLoader.init(context, false)
    JNIOnLoad.initializeNativeNitro()
    ReactNativeDocumentScannerOnLoad.initializeNative()
  }

  private fun <T> settled(promise: Promise<T>): T {
    val done = CountDownLatch(1)
    val count = AtomicInteger()
    var value: T? = null
    var error: Throwable? = null
    promise.then { value = it; count.incrementAndGet(); done.countDown() }
      .catch { error = it; count.incrementAndGet(); done.countDown() }
    assertTrue("Native promise did not settle", done.await(10, TimeUnit.SECONDS))
    assertNull(error)
    assertEquals(1, count.get())
    return requireNotNull(value)
  }

  private fun original(session: HybridScanSession): SessionResources.Resource =
    session.state.resources.output(SessionResources.Kind.ORIGINAL).use { output ->
      val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
      try {
        bitmap.eraseColor(Color.WHITE)
        output.file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
      } finally { bitmap.recycle() }
      output.commit()
    }

  private fun blockedImages(action: () -> Unit) {
    val queue = ScannerWorkQueue.images
    val owner = queue.newOwner()
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    queue.submit(owner, ScannerWorkQueue.Kind.RENDER,
      operation = { entered.countDown(); check(release.await(30, TimeUnit.SECONDS)) },
      onSuccess = {}, onFailure = {})
    assertTrue(entered.await(10, TimeUnit.SECONDS))
    try { action() } finally { release.countDown(); queue.close(owner).get(10, TimeUnit.SECONDS) }
  }

  @Test fun factoryCreatesIndependentExplicitLifetimes() {
    initialize()
    val processor = HybridDocumentScannerProcessor(context)
    val first = processor.createSessionResult().asFirstOrNull() as HybridScanSession
    val second = processor.createSessionResult().asFirstOrNull() as HybridScanSession
    try {
      assertNotEquals(first.state.resources.sessionId, second.state.resources.sessionId)
      assertFalse(first.getSession().closed)
      assertFalse(second.getSession().closed)
      assertTrue(settled(first.closeResult()).asFirstOrNull() == true)
      assertTrue(settled(first.closeResult()).asFirstOrNull() == true)
      assertFalse(second.getSession().closed)
      assertEquals(ScannerErrorCode.PROCESSOR_DISPOSED,
        settled(first.setFilterResult("missing", DocumentFilter.NONE)).asSecondOrNull()?.code)
    } finally {
      settled(first.closeResult())
      settled(second.closeResult())
    }
  }

  @Test fun failedFactoryAdmissionDoesNotCloseExistingSessions() {
    initialize()
    val session = HybridScanSession(context)
    val brokenContext = object : android.content.ContextWrapper(context) {
      override fun getApplicationContext(): android.content.Context = this
      override fun getCacheDir(): java.io.File = throw IllegalStateException("Cache unavailable")
    }
    try {
      val processor = HybridDocumentScannerProcessor(brokenContext)
      assertEquals(ScannerErrorCode.PROCESSOR_UNAVAILABLE,
        processor.createSessionResult().asSecondOrNull()?.code)
      assertFalse(session.getSession().closed)
      assertTrue(settled(session.closeResult()).asFirstOrNull() == true)
    } finally { settled(session.closeResult()) }
  }

  @Test fun idCommandsAcknowledgeAdmissionWithoutWaitingForImageWorkerAndRejectInvalidInput() {
    initialize()
    blockedImages {
      val session = HybridScanSession(context)
      try {
        assertEquals(ScannerErrorCode.INPUT_INVALID,
          settled(session.setFilterResult("missing", DocumentFilter.NONE)).asSecondOrNull()?.code)
        assertEquals(ScannerErrorCode.INPUT_INVALID,
          settled(session.removePageResult("missing")).asSecondOrNull()?.code)
        val full = ScanPage.fullFrame()
        session.state.admit("page", original(session), null, null, null)
        assertTrue(settled(session.setFilterResult("page", DocumentFilter.GRAYSCALE)).asFirstOrNull() == true)
        assertTrue(settled(session.setShadowRemovalResult("page", false)).asFirstOrNull() == true)
        assertTrue(settled(session.setDocumentCropResult("page", full)).asFirstOrNull() == true)
        val snapshot = session.getSession()
        assertTrue(requireNotNull(snapshot.pages["page"]).analyzing)
        assertEquals(DocumentFilter.GRAYSCALE, snapshot.pages["page"]?.options?.filter)
        assertEquals(false, snapshot.pages["page"]?.options?.removeShadows)
        val invalid = full.copy(topLeft = DocumentPoint(Double.NaN, 0.0))
        assertEquals(ScannerErrorCode.INPUT_INVALID,
          settled(session.setDocumentCropResult("page", invalid)).asSecondOrNull()?.code)
        assertEquals(snapshot, session.getSession())
        assertTrue(settled(session.removePageResult("page")).asFirstOrNull() == true)
        assertTrue(session.getSession().pages.isEmpty())
      } finally { settled(session.closeResult()) }
    }
  }

  @Test fun registrationReturnsInitialVersionThenMainOwnerChangesAndExactlyOneTerminalEvent() {
    initialize()
    blockedImages {
      val session = HybridScanSession(context)
      val changes = Collections.synchronizedList(mutableListOf<ScanSessionChange>())
      val edits = CountDownLatch(2)
      val terminal = CountDownLatch(1)
      val wrongThread = AtomicInteger()
      val registration = requireNotNull(session.observeResult {
        if (Looper.myLooper() != Looper.getMainLooper()) wrongThread.incrementAndGet()
        changes.add(it)
        if (it.closed) terminal.countDown() else edits.countDown()
      }.asFirstOrNull())
      try {
        assertEquals(0.0, registration.snapshot.sourceVersion, 0.0)
        instrumentation.runOnMainSync {}
        assertTrue(changes.isEmpty())
        session.state.admit("page", original(session), null, null, null)
        settled(session.setShadowRemovalResult("page", false))
        assertTrue(edits.await(10, TimeUnit.SECONDS))
        assertEquals(listOf(1.0, 2.0), changes.map { it.sourceVersion })
        assertEquals(0, wrongThread.get())
        assertTrue(settled(session.closeResult()).asFirstOrNull() == true)
        assertTrue(terminal.await(10, TimeUnit.SECONDS))
        settled(session.closeResult())
        instrumentation.runOnMainSync {}
        assertEquals(1, changes.count { it.closed })
        assertEquals(3.0, changes.last().sourceVersion, 0.0)
        assertTrue(changes.last().updatedPages.isEmpty())
        assertEquals(ScannerErrorCode.PROCESSOR_DISPOSED, session.observeResult {}.asSecondOrNull()?.code)
        registration.unsubscribe()
        registration.unsubscribe()
      } finally {
        registration.unsubscribe()
        settled(session.closeResult())
      }
    }
  }
}
