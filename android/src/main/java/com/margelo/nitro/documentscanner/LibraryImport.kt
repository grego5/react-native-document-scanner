package com.margelo.nitro.documentscanner

/**
 * One `importFromLibrary` call, from presentation through admission. It holds its frozen defaults through [admit],
 * submits at most [maxCount] picks (0: no limit) in picked order, so the session's serial image worker keeps that
 * order, and settles [onResult] once: when every pick is admitted or has failed, on a picker failure, or when the
 * session closes first. [T] is the platform's pick; tests use plain values.
 */
internal class LibraryImport<T>(
  private val maxCount: Int,
  /** Runs a picker handle on the thread the picker requires. */
  private val post: (() -> Unit) -> Unit,
  private val admit: (pick: T, onDone: (Result<String>) -> Unit) -> Unit,
  private val onResult: (Result<LibraryImportResult>) -> Unit,
) {
  private val lock = Any()
  private var settled = false
  private var unregister: (() -> Unit)? = null
  private var endPick: (() -> Unit)? = null

  /**
   * [register] ties the call to its session's close and returns the unregistration; [present] shows the picker,
   * reports through its callback and returns a handle that ends the platform request.
   */
  fun start(register: (cancel: () -> Unit) -> () -> Unit, present: (onPicked: (Result<List<T>>) -> Unit) -> () -> Unit) {
    try {
      val unregistered = register { cancel(ScannerException(ScannerErrorCode.PROCESSOR_DISPOSED)) }
      if (!synchronized(lock) { if (!settled) unregister = unregistered; !settled }) return unregistered()
      val end = present(::picked)
      // Close may have won while presenting: the picker is then ended straight away.
      if (synchronized(lock) { if (!settled) endPick = end; settled }) post(end)
    } catch (error: Throwable) {
      settle(Result.failure(error))
    }
  }

  /** Settles with [error] unless already settled; a still-open picker is ended and its slot released. */
  fun cancel(error: Throwable) = settle(Result.failure(error))

  private fun picked(result: Result<List<T>>) {
    // The document-picker fallback ignores the limit; picks past it are dropped, keeping the first in returned order.
    val picks = result.getOrElse { return settle(Result.failure(it)) }.let { if (maxCount > 0) it.take(maxCount) else it }
    if (synchronized(lock) { settled }) return
    if (picks.isEmpty()) return settle(Result.success(LibraryImportResult(emptyArray(), 0.0)))
    val outcomes = arrayOfNulls<Result<String>>(picks.size)
    var remaining = picks.size
    picks.forEachIndexed { index, pick ->
      val onDone = { outcome: Result<String> ->
        val done = synchronized(lock) {
          outcomes[index] = outcome
          --remaining == 0
        }
        if (done) {
          val ids = outcomes.mapNotNull { it?.getOrNull() }
          settle(Result.success(LibraryImportResult(ids.toTypedArray(), (picks.size - ids.size).toDouble())))
        }
      }
      try { admit(pick, onDone) } catch (error: Throwable) { onDone(Result.failure(error)) }
    }
  }

  private fun settle(result: Result<LibraryImportResult>) {
    val (unregistered, end) = synchronized(lock) {
      if (settled) return
      settled = true
      Pair(unregister, endPick).also { unregister = null; endPick = null }
    }
    unregistered?.invoke()
    end?.let(post)
    onResult(result)
  }
}
