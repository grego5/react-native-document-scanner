package com.margelo.nitro.documentscanner

import android.net.Uri

/** One pin shared by the binding and queued reads; retirement cannot invalidate an admitted read. */
internal class SessionPageSource(val key: String, private val leases: List<SessionResources.Lease>, cacheKey: String? = null) : AutoCloseable {
  internal val workOwner get() = leases.first().resource.owner.workOwner
  val uri: String = Uri.fromFile(leases.first().file).toString()
  val cacheUri = cacheKey ?: uri
  private var readers = 1
  @Synchronized fun retain(): SessionPageSource { check(readers > 0); readers++; return this }
  @Synchronized override fun close() {
    check(readers > 0)
    if (--readers == 0) leases.forEach { it.close() }
  }
}

/**
 * Main-owner observation of one session page for native views. The binding alone decides what an absent page means,
 * the same way on registration, refresh, event delivery and restart: a page it has seen (in a registration snapshot
 * or a read) is removed or closed, which clears the source silently; a page it never saw is invalid input. No JS
 * metadata publication participates in image replacement.
 */
internal class SessionPageBinding(
  private val state: ScanSessionState,
  private val id: String,
  private val original: Boolean = false,
  private val thumbnailOnly: Boolean = false,
  private val changed: (SessionPageSource?) -> Unit,
  private val failed: (ScannerException) -> Unit,
) : AutoCloseable {
  /** What this binding knows about its page. GONE is not terminal: a restart that finds the ID again is AVAILABLE. */
  private enum class Target { UNRESOLVED, AVAILABLE, GONE }

  private var unsubscribe: (() -> Unit)? = null
  private var generation = 0
  private var key: String? = null
  private var target = Target.UNRESOLVED

  /** [afterRegistration] is a test seam between observation and the first read. */
  fun start(afterRegistration: () -> Unit = {}) {
    if (unsubscribe != null) return
    val current = ++generation
    val registration = try {
      state.observe { snapshot ->
        if (generation != current) return@observe
        if (snapshot.closed || id in snapshot.removedPageIds) missing(null)
        else if (id in snapshot.updatedPages) refresh()
      }
    } catch (error: Throwable) {
      missing(error) // Only a closed session refuses observation.
      return
    }
    unsubscribe = registration.unsubscribe
    // Seen atomically with registration, so a removal racing the first read is removal.
    if (id in registration.snapshot.pageIds) target = Target.AVAILABLE
    afterRegistration()
    refresh()
  }

  fun valid(): Boolean = state.hasPage(id)

  private fun refresh() {
    // A read fails only when the page or its session is gone, including mid-read.
    val source = try {
      if (thumbnailOnly) state.thumbnailSource(id) else state.imageSource(id, original)
    } catch (error: Throwable) {
      missing(error)
      return
    }
    target = Target.AVAILABLE
    if (source?.key == key) source?.close() else {
      key = source?.key
      changed(source) // Receiver owns the source, including on a detached view.
    }
  }

  /** The one classification of an absent page. */
  private fun missing(error: Throwable?) {
    val seen = target != Target.UNRESOLVED
    if (seen) target = Target.GONE
    close()
    changed(null)
    if (!seen) failed(ScannerException(ScannerErrorCode.INPUT_INVALID, error ?: InvalidInputException("The configured session page is unavailable.")))
  }

  override fun close() {
    generation++
    unsubscribe?.invoke()
    unsubscribe = null
    key = null
  }
}
