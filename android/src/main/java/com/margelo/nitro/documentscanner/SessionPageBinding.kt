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

/** Main-owner observation. No JS metadata publication participates in image replacement. */
internal class SessionPageBinding(
  private val state: ScanSessionState,
  private val id: String,
  private val original: Boolean = false,
  private val thumbnailOnly: Boolean = false,
  private val changed: (SessionPageSource?) -> Unit,
  private val failed: (ScannerException) -> Unit,
) : AutoCloseable {
  private var unsubscribe: (() -> Unit)? = null
  private var generation = 0
  private var key: String? = null

  fun start() {
    if (unsubscribe != null) return
    val current = ++generation
    try {
      val registration = state.observe { snapshot ->
        if (generation == current && (snapshot.closed || id in snapshot.removedPageIds || id in snapshot.updatedPages)) {
          if (snapshot.closed || id in snapshot.removedPageIds) { close(); changed(null) }
          else refresh()
        }
      }
      unsubscribe = registration.unsubscribe
      refresh()
    } catch (error: Throwable) { invalid(error) }
  }

  fun valid(): Boolean = state.hasPage(id)

  private fun refresh() {
    if (!state.hasPage(id)) {
      invalid(InvalidInputException("The configured session page is unavailable."))
      return
    }
    try {
      val source = if (thumbnailOnly) state.thumbnailSource(id) else state.imageSource(id, original)
      if (source?.key == key) source?.close() else {
        key = source?.key
        changed(source) // Receiver owns the source, including on a detached view.
      }
    } catch (error: Throwable) { invalid(error) }
  }

  private fun invalid(error: Throwable) {
    close()
    changed(null)
    failed(ScannerException(ScannerErrorCode.INPUT_INVALID, error))
  }

  override fun close() {
    generation++
    unsubscribe?.invoke()
    unsubscribe = null
    key = null
  }
}
