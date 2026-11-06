package com.margelo.nitro.documentscanner

import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

/** Invocation-owned ordered pins. Resolved only on the session's serial image worker. */
internal class SessionExportSnapshot(
  val pages: List<Page>,
  private val resources: SessionResources,
  private val actions: ScanSessionState.Actions,
) : AutoCloseable {
  class Page(
    val id: String,
    val revision: Long,
    private val requested: ScanPage.RenderKey,
    private val automatic: Boolean,
    private val analysis: CompletableFuture<DocumentAnalysis>,
    val source: SessionResources.Lease,
    internal var image: SessionResources.Lease?,
    private val releaseAnalysis: () -> Unit,
  ) : AutoCloseable {
    private val closed = java.util.concurrent.atomic.AtomicBoolean()
    fun key(): ScanPage.RenderKey {
      if (!automatic) return requested
      // FIFO guarantees the already-admitted analysis has settled before this export. Never wait on the worker.
      check(analysis.isDone) { "Analysis must precede export in the serial FIFO." }
      val result = try { analysis.getNow(null) } catch (error: CompletionException) {
        throw ScannerException(ScannerErrorCode.ANALYSIS_FAILED, error.cause ?: error)
      }
      val candidate = result?.candidate?.quad
      val quad = candidate?.takeIf { NativeQuadGeometry.renderIssue(NativeQuadGeometry.coordinates(it)) == null }
        ?: ScanPage.fullFrame()
      return requested.copy(quad = quad)
    }
    override fun close() {
      if (!closed.compareAndSet(false, true)) return
      image?.close(); image = null; source.close(); releaseAnalysis()
    }
  }

  fun files(): List<File> = pages.map { page ->
    page.image?.file ?: run {
      val key = page.key()
      val artifact = try { actions.renderSnapshot(page.source, key) }
      catch (error: Throwable) { throw (error as? ScannerException ?: ScannerException(ScannerErrorCode.RENDER_FAILED, error)) }
      try {
        page.image = resources.lease(artifact.image)
        requireNotNull(page.image).file
      } finally {
        resources.remove(artifact.image)
        resources.remove(artifact.thumbnail)
      }
    }
  }

  override fun close() { pages.forEach { it.close() } }
}
