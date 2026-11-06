package com.margelo.nitro.documentscanner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.margelo.nitro.documentscanner.processing.CachedImageFiles
import com.margelo.nitro.documentscanner.processing.DocumentRenderer
import com.margelo.nitro.documentscanner.processing.LatestFrameCapture
import com.margelo.nitro.documentscanner.processing.LiveFrame
import java.io.File
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionResourcesDeviceTest {
  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val base = File(context.cacheDir.canonicalFile, "session-resources-test-${UUID.randomUUID()}")
    .apply { check(mkdirs()) }
  private val cache = File(base, CachedImageFiles.DIRECTORY_NAME).apply { check(mkdirs()) }
  private val registry = ScanSessionRegistry.shared
  private val sessions = mutableListOf<SessionResources>()
  private fun open() = registry.open(cache).also { sessions.add(it) }

  @After fun cleanup() {
    sessions.forEach { it.close().get(10, TimeUnit.SECONDS) }
    registry.cleanupAbandoned(cache).get(10, TimeUnit.SECONDS)
    check(base.parentFile == context.cacheDir.canonicalFile && base.name.startsWith("session-resources-test-"))
    check(base.deleteRecursively())
  }

  @Test fun cleanupTouchesOnlyUnregisteredSessionDirectoriesAcrossContexts() {
    val firstContext = open()
    val secondContext = open()
    val abandoned = LocalSessionFiles.directory(cache, UUID.randomUUID().toString())
    LocalSessionFiles.createDirectory(abandoned)
    File(abandoned, "interrupted.tmp.jpg").writeText("partial")
    val export = File(cache, "export.pdf").apply { writeText("export") }
    val unrelated = File(cache, "other.jpg").apply { writeText("other") }
    val foreignNamespaceEntry = File(File(cache, LocalSessionFiles.NAMESPACE), "not-a-session")
      .apply { check(mkdirs()) }
    registry.cleanupAbandoned(cache).get(10, TimeUnit.SECONDS)
    assertFalse(abandoned.exists())
    assertTrue(firstContext.directory.isDirectory)
    assertTrue(secondContext.directory.isDirectory)
    assertEquals("export", export.readText())
    assertEquals("other", unrelated.readText())
    assertTrue(foreignNamespaceEntry.isDirectory)
  }

  @Test fun upgradeCleanupRemovesOnlyLegacyImagesAndNeverFollowsLinks() {
    val session = open()
    registry.cleanupAbandoned(cache).get(10, TimeUnit.SECONDS)
    val stamp = "123-${UUID.randomUUID()}"
    val legacy = listOf("document-scan-$stamp.jpg", "frame-document-scan-$stamp.jpg",
      "corrected-$stamp.jpg", "corrected-$stamp.png", "thumbnail-corrected-$stamp.jpg")
      .map { File(cache, it).apply { writeText("legacy") } }
    val unrelated = listOf("export.pdf", "corrected-other.jpg", "document-scan-$stamp.png")
      .map { File(cache, it).apply { writeText("keep") } }
    val directory = File(cache, "corrected-456-${UUID.randomUUID()}.jpg").apply { check(mkdirs()) }
    val nested = File(directory, "keep.jpg").apply { writeText("keep") }
    val outside = File(base, "keep.jpg").apply { writeText("outside") }
    val link = File(cache, "document-scan-789-${UUID.randomUUID()}.jpg")
    Os.symlink(outside.path, link.path)
    try {
      registry.cleanupAbandoned(cache).get(10, TimeUnit.SECONDS)
      assertTrue(legacy.none { it.exists() })
      assertTrue(unrelated.all { it.readText() == "keep" })
      assertEquals("keep", nested.readText())
      assertEquals("outside", outside.readText())
      assertTrue(ScannerFiles.isSymbolicLink(link))
      assertTrue(session.directory.isDirectory)
      registry.cleanupAbandoned(cache).get(10, TimeUnit.SECONDS) // Idempotent.
    } finally { check(link.delete()) }
  }

  @Test fun pinsBlockPhysicalDeletionAndRetiredResourcesRejectNewLeases() {
    val session = open()
    val output = session.output(SessionResources.Kind.ORIGINAL)
    val resource = output.use { it.file.writeText("original"); it.commit() }
    val decoder = session.lease(resource)
    val retired = session.remove(resource)
    assertFalse(retired.isDone)
    assertEquals("original", decoder.file.readText())
    assertThrows(InvalidInputException::class.java) { session.lease(resource) }
    val closing = session.close()
    assertSame(closing, session.close())
    assertFalse(closing.isDone)
    decoder.close()
    closing.get(10, TimeUnit.SECONDS)
    assertFalse(session.directory.exists())
  }

  @Test fun canonicalConstraintsRejectTraversalAndNamespaceLinksWithoutTouchingTheirTargets() {
    assertThrows(InvalidInputException::class.java) { LocalSessionFiles.directory(cache, "../outside") }
    assertThrows(InvalidInputException::class.java) { LocalSessionFiles.removeDirectory(cache, cache) }
    val outside = File(base, "outside").apply { check(mkdirs()) }
    val original = File(outside, "keep.jpg").apply { writeText("keep") }
    val namespace = File(cache, LocalSessionFiles.NAMESPACE)
    Os.symlink(outside.path, namespace.path)
    try {
      assertThrows(InvalidInputException::class.java) {
        LocalSessionFiles.directory(cache, UUID.randomUUID().toString())
      }
      assertThrows(ExecutionException::class.java) { registry.cleanupAbandoned(cache).get(10, TimeUnit.SECONDS) }
      assertEquals("keep", original.readText())
    } finally {
      check(namespace.delete()) // Unlinks the known test symlink, never its target.
    }
  }

  @Test fun sessionDirectoryAndOutputSymlinksCannotEscapeTheirRegisteredRoot() {
    val outside = File(base, "outside").apply { check(mkdirs()) }
    val keep = File(outside, "keep.jpg").apply { writeText("keep") }
    val id = UUID.randomUUID().toString()
    val directory = LocalSessionFiles.directory(cache, id)
    check(directory.parentFile!!.mkdirs())
    Os.symlink(outside.path, directory.path)
    try {
      assertThrows(InvalidInputException::class.java) { LocalSessionFiles.directory(cache, id) }
      registry.cleanupAbandoned(cache).get(10, TimeUnit.SECONDS)
      assertEquals("keep", keep.readText())
    } finally {
      check(directory.delete())
    }

    val session = open()
    val output = session.output(SessionResources.Kind.FRAME)
    Os.symlink(keep.path, output.file.path)
    assertThrows(InvalidInputException::class.java) { output.use { it.commit() } }
    assertThrows(ExecutionException::class.java) { output.resource.deleted.get(10, TimeUnit.SECONDS) }
    // Final namespace cleanup unlinks the staged symlink without following it.
    session.close().get(10, TimeUnit.SECONDS)
    assertEquals("keep", keep.readText())
  }

  @Test fun publishedOriginalsCannotBeOverwritten() {
    val session = open()
    val output = session.output(SessionResources.Kind.ORIGINAL)
    val resource = output.use { it.file.writeText("immutable"); it.commit() }
    output.file.writeText("replacement")
    assertThrows(java.io.IOException::class.java) { LocalSessionFiles.publish(resource.paths) }
    session.lease(resource).use { assertEquals("immutable", it.file.readText()) }
  }

  @Test fun renderingAndFramesUseExplicitSessionDestinationsAndForgottenBitmapsRemainDrawable() {
    val session = open()
    val original = session.output(SessionResources.Kind.ORIGINAL).use { output ->
      val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
      try {
        bitmap.eraseColor(Color.WHITE)
        output.file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
      } finally {
        bitmap.recycle()
      }
      output.commit()
    }
    val destination = session.renderDestination("jpg")
    val rendered = DocumentRenderer.render(original,
      DocumentQuad(DocumentPoint(0.0, 0.0), DocumentPoint(1.0, 0.0),
        DocumentPoint(1.0, 1.0), DocumentPoint(0.0, 1.0)),
      DocumentRenderOptions(false, null, DocumentFilter.NONE, DocumentImageFormat.JPEG, null, null, null),
      destination)
    assertEquals(session.directory, File(Uri.parse(rendered.outputUri).path!!).parentFile)
    assertEquals(session.directory, File(Uri.parse(rendered.thumbnailUri).path!!).parentFile)
    val shown = requireNotNull(PageThumbnails.cached(rendered.outputUri))
    session.remove(destination.image.resource).get(10, TimeUnit.SECONDS)
    session.remove(destination.thumbnail.resource).get(10, TimeUnit.SECONDS)
    assertNull(PageThumbnails.cached(rendered.outputUri))
    assertFalse(shown.isRecycled)
    val canvasBitmap = Bitmap.createBitmap(shown.width, shown.height, Bitmap.Config.ARGB_8888)
    try { Canvas(canvasBitmap).drawBitmap(shown, 0f, 0f, Paint()) } finally { canvasBitmap.recycle() }

    val frameOutput = session.output(SessionResources.Kind.FRAME)
    val frame = LiveFrame(ByteArray(64 * 64 * 4) { if (it % 4 == 3) 255.toByte() else 127.toByte() }, 64, 64, 0)
    val frameResource = LatestFrameCapture.write(frame, null, frameOutput)
    session.lease(frameResource).use { assertEquals(session.directory, it.file.parentFile); assertTrue(it.file.isFile) }
    assertNotNull(PageThumbnails.cached(Uri.fromFile(frameOutput.destination).toString()))
    assertNull(PageThumbnails.cached(Uri.fromFile(frameOutput.file).toString()))
    session.lease(original).use { assertTrue(it.file.isFile) }
  }

  @Test fun standaloneAlgorithmHelpersRejectOwnedSessionSources() {
    val session = open()
    val original = session.output(SessionResources.Kind.ORIGINAL).use { it.file.writeText("source"); it.commit() }
    session.lease(original).use { source ->
      val fullFrame = DocumentQuad(DocumentPoint(0.0, 0.0), DocumentPoint(1.0, 0.0),
        DocumentPoint(1.0, 1.0), DocumentPoint(0.0, 1.0))
      assertThrows(InvalidInputException::class.java) {
        DocumentRenderer.render(Uri.fromFile(source.file).toString(), fullFrame, null)
      }
    }
    session.output(SessionResources.Kind.FRAME).use { output ->
      val frame = LiveFrame(ByteArray(4), 1, 1, 0)
      assertThrows(InvalidInputException::class.java) { LatestFrameCapture.write(frame, null, output.file) }
    }
  }

  @Test fun invalidRenderInputRetiresEveryReservedOutputBeforeReturningFailure() {
    val session = open()
    val original = session.output(SessionResources.Kind.ORIGINAL).use { it.file.writeText("source"); it.commit() }
    val destination = session.renderDestination("jpg")
    val crossed = DocumentQuad(DocumentPoint(0.0, 0.0), DocumentPoint(1.0, 1.0),
      DocumentPoint(1.0, 0.0), DocumentPoint(0.0, 1.0))
    assertThrows(InvalidInputException::class.java) {
      DocumentRenderer.render(original, crossed, null, destination)
    }
    destination.image.resource.deleted.get(10, TimeUnit.SECONDS)
    destination.thumbnail.resource.deleted.get(10, TimeUnit.SECONDS)
    assertFalse(destination.image.file.exists())
    assertFalse(destination.thumbnail.file.exists())
    session.lease(original).use { assertEquals("source", it.file.readText()) }
  }
}
