package com.margelo.nitro.documentscanner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import com.margelo.nitro.documentscanner.processing.DocumentRenderer
import java.io.File
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic pixels only: no camera, shutter, private photos or model assets. */
@RunWith(AndroidJUnit4::class)
class ScanSessionPipelineTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val cache = File(context.cacheDir, "session-pipeline-test-" + UUID.randomUUID())
  private val states = mutableListOf<ScanSessionState>()
  private val worker = object : Executor {
    val tasks = ArrayDeque<Runnable>()
    override fun execute(command: Runnable) { tasks.addLast(command) }
    fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
  }
  private val queue = ScannerWorkQueue(worker) { throw AssertionError(it) }
  private val registry = ScanSessionRegistry(LocalSessionFiles, queue, Executor { it.run() }, {}, { throw AssertionError(it) })

  @After fun clean() {
    states.forEach { it.close().get(10, TimeUnit.SECONDS) }
    check(cache.canonicalFile.parentFile == context.cacheDir.canonicalFile)
    check(cache.name.startsWith("session-pipeline-test-"))
    cache.deleteRecursively()
  }

  private fun image(resources: SessionResources, kind: SessionResources.Kind): SessionResources.Resource =
    resources.output(kind).use { output ->
      val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
      try {
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint().apply { color = Color.BLACK; strokeWidth = 3f }
        for (y in 40..200 step 16) canvas.drawLine(40f, y.toFloat(), 280f, y.toFloat(), paint)
        output.file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
      } finally { bitmap.recycle() }
      output.commit()
    }

  @Test fun authoritativeAnalysisPrecedesRealRenderingAndFilterCacheReusesImmutableFiles() {
    cache.mkdirs()
    val resources = registry.open(cache)
    val original = image(resources, SessionResources.Kind.ORIGINAL)
    val frame = image(resources, SessionResources.Kind.FRAME)
    val originalBytes = resources.lease(original).use { it.file.readBytes() }
    val frameBytes = resources.lease(frame).use { it.file.readBytes() }
    val outputs = mutableListOf<ScanPage.Artifact>()
    var analyzed = false
    val processing = SessionPageProcessing(context, resources)
    val actions = object : ScanSessionState.Actions {
      override fun prepareAnalysis(original: SessionResources.Resource, liveCorners: DocumentCorners?, selection: com.margelo.nitro.documentscanner.processing.CornerModelStore.Selection?) = {
        assertTrue(outputs.isEmpty())
        analyzed = true
        DocumentAnalysis(320.0, 240.0, 0.0, null)
      }
      override fun render(original: SessionResources.Resource, key: ScanPage.RenderKey): ScanPage.Artifact {
        assertTrue(analyzed)
        return processing.render(original, key).also { outputs.add(it) }
      }
      override fun close() = processing.close()
    }
    val state = ScanSessionState(resources, actions).also { states.add(it) }
    state.admit("page", original, frame, null,
      DocumentRenderOptions(false, null, DocumentFilter.NONE, null, null, null, false))
    assertNull(state.metadata()["page"]?.readyRevision)
    worker.drain()
    val first = outputs.single()
    assertEquals(320.0, first.width, 0.0)
    assertEquals(240.0, first.height, 0.0)
    state.setFilter("page", DocumentFilter.GRAYSCALE)
    worker.drain()
    assertEquals(2, outputs.size)
    state.setFilter("page", DocumentFilter.NONE)
    assertEquals(state.metadata()["page"]?.requestedRevision, state.metadata()["page"]?.readyRevision)
    worker.drain()
    assertEquals(2, outputs.size)
    resources.lease(first.image).use { assertTrue(it.file.isFile) }
    resources.lease(first.thumbnail).use { assertTrue(it.file.isFile) }
    resources.lease(original).use { assertArrayEquals(originalBytes, it.file.readBytes()) }
    resources.lease(frame).use { assertArrayEquals(frameBytes, it.file.readBytes()) }

    state.setDocumentCrop("page", DocumentCorners(DocumentPoint(.1, .1), DocumentPoint(.9, .1),
      DocumentPoint(.9, .9), DocumentPoint(.1, .9)))
    // Old display survives while its replacement is pending.
    assertFalse(first.image.deleted.isDone)
    worker.drain()
    first.image.deleted.get(10, TimeUnit.SECONDS)
    assertEquals(3, outputs.size)
    assertTrue(outputs.last().width < first.width)
    assertEquals(state.metadata()["page"]?.requestedRevision, state.metadata()["page"]?.readyRevision)
  }

  @Test fun realRenderFailureRetainsDisplayAndEqualSettingsRetryFromOriginal() {
    cache.mkdirs()
    val resources = registry.open(cache)
    val original = image(resources, SessionResources.Kind.ORIGINAL)
    val outputs = mutableListOf<ScanPage.Artifact>()
    var fail = false
    val actions = object : ScanSessionState.Actions {
      override fun prepareAnalysis(original: SessionResources.Resource, liveCorners: DocumentCorners?, selection: com.margelo.nitro.documentscanner.processing.CornerModelStore.Selection?) =
        { DocumentAnalysis(320.0, 240.0, 0.0, null) }
      override fun render(original: SessionResources.Resource, key: ScanPage.RenderKey): ScanPage.Artifact {
        val destination = resources.renderDestination("jpg")
        // Exercise renderer's actual partial-output cleanup on invalid geometry, not a fake exception.
        val corners = if (fail) key.corners.copy(topLeft = DocumentPoint(Double.NaN, 0.0)) else key.corners
        val result = DocumentRenderer.render(original, corners, key.options, destination)
        return ScanPage.Artifact(destination.image.resource, destination.thumbnail.resource,
          result.width, result.height, result.validContentPolygon.toList()).also { outputs.add(it) }
      }
      override fun close() {}
    }
    val state = ScanSessionState(resources, actions).also { states.add(it) }
    state.admit("page", original, null, null,
      DocumentRenderOptions(false, null, DocumentFilter.NONE, null, null, null, false))
    worker.drain()
    val first = outputs.single()
    val revision = state.metadata()["page"]?.readyRevision
    fail = true
    state.setFilter("page", DocumentFilter.GRAYSCALE)
    worker.drain()
    assertNotNull(state.metadata()["page"]?.renderFailure)
    assertEquals(revision, state.metadata()["page"]?.readyRevision)
    resources.lease(first.image).use { assertTrue(it.file.isFile) }
    fail = false
    state.setFilter("page", DocumentFilter.GRAYSCALE)
    worker.drain()
    assertEquals(2, outputs.size)
    assertNull(state.metadata()["page"]?.renderFailure)
    assertEquals(state.metadata()["page"]?.requestedRevision, state.metadata()["page"]?.readyRevision)
  }

  @Test fun captureReservationPublishesSavedPixelsThenRendersOriginalWithCapturedDefaults() {
    cache.mkdirs()
    val resources = registry.open(cache)
    val processing = SessionPageProcessing(context, resources)
    val renders = mutableListOf<ScanPage.RenderKey>()
    val actions = object : ScanSessionState.Actions {
      override fun prepareAnalysis(original: SessionResources.Resource, liveCorners: DocumentCorners?,
        selection: com.margelo.nitro.documentscanner.processing.CornerModelStore.Selection?) =
        { DocumentAnalysis(320.0, 240.0, 0.0, null) }
      override fun render(original: SessionResources.Resource, key: ScanPage.RenderKey): ScanPage.Artifact {
        assertEquals(SessionResources.Kind.ORIGINAL, original.kind)
        renders.add(key)
        return processing.render(original, key)
      }
      override fun close() = processing.close()
    }
    val state = ScanSessionState(resources, actions).also { states.add(it) }
    val capture = SessionCapture.reserve(state,
      DocumentRenderOptions(false, null, DocumentFilter.GRAYSCALE, null, null, null, false))
    try {
      val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
      try {
        bitmap.eraseColor(Color.WHITE)
        capture.original.file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
      } finally { bitmap.recycle() }
      val before = capture.original.file.readBytes()
      capture.original.commit()
      val pixels = ByteArray(64 * 64 * 4) { if (it % 4 == 3) 255.toByte() else 120.toByte() }
      val frame = com.margelo.nitro.documentscanner.processing.LatestFrameCapture.write(
        com.margelo.nitro.documentscanner.processing.LiveFrame(pixels, 64, 64, 0), null, requireNotNull(capture.frame))
      val frameBytes = resources.lease(frame).use { it.file.readBytes() }
      assertTrue(state.snapshot().pages.isEmpty())
      val id = capture.publish(frame, null)
      capture.close()
      assertTrue(renders.isEmpty())
      worker.drain()
      assertTrue(requireNotNull(state.snapshot().pages[id]).ready)
      assertEquals(DocumentFilter.GRAYSCALE, renders.single().options.filter)
      assertEquals(false, renders.single().options.removeShadows)
      state.leaseOriginal(id).use { assertArrayEquals(before, it.file.readBytes()) }
      resources.lease(frame).use { assertArrayEquals(frameBytes, it.file.readBytes()) }
    } finally { capture.close() }
  }}
