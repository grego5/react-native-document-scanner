package com.margelo.nitro.documentscanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import java.io.File
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import com.margelo.nitro.documentscanner.processing.QuadModelStore

/** Synthetic sources and controllable image work; no model or camera. Main thread only. */
internal class SessionViewFixture(context: Context) : AutoCloseable {
  class Worker : Executor {
    private val tasks = ArrayDeque<Runnable>()
    override fun execute(command: Runnable) { tasks.addLast(command) }
    fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
  }
  val worker = Worker()
  private val cache = File(context.cacheDir, "session-view-test-" + UUID.randomUUID()).apply { mkdirs() }
  private val queue = ScannerWorkQueue(worker) { throw AssertionError(it) }
  private val registry = ScanSessionRegistry(LocalSessionFiles, queue, Executor { it.run() }, {}, { throw AssertionError(it) })
  val resources = registry.open(cache)
  var failRender = false
  val state = ScanSessionState(resources, object : ScanSessionState.Actions {
    override fun prepareAnalysis(original: SessionResources.Resource, liveQuad: DocumentQuad?, selection: QuadModelStore.Selection?) =
      { DocumentAnalysis(40.0, 40.0, 0.0, null) }
    override fun render(original: SessionResources.Resource, key: ScanPage.RenderKey): ScanPage.Artifact {
      check(!failRender) { "synthetic render failure" }
      return ScanPage.Artifact(image(SessionResources.Kind.RENDER, Color.BLUE),
        image(SessionResources.Kind.THUMBNAIL, Color.BLUE), 40.0, 40.0, emptyList())
    }
    override fun close() {}
  }, dispatcher = Executor { it.run() })

  fun image(kind: SessionResources.Kind, color: Int): SessionResources.Resource = resources.output(kind).use { output ->
    val pixels = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
    try { output.file.outputStream().use { check(pixels.compress(Bitmap.CompressFormat.JPEG, 100, it)) } }
    finally { pixels.recycle() }
    output.commit()
  }
  fun admit(id: String) {
    state.admit(id, image(SessionResources.Kind.ORIGINAL, Color.RED),
      image(SessionResources.Kind.FRAME, Color.GREEN), null, null)
  }
  override fun close() {
    val closing = state.close()
    worker.drain()
    closing.get(5, TimeUnit.SECONDS)
    check(cache.canonicalFile.parentFile == cache.parentFile.canonicalFile)
    check(cache.name.startsWith("session-view-test-"))
    cache.deleteRecursively()
  }
}
