package com.margelo.nitro.documentscanner

import java.util.concurrent.CompletableFuture

/** Admits one pick; [onDone] runs once. */
internal typealias AdmitPickedImage<T> = (pick: T, onDone: (Result<String>) -> Unit) -> Unit

/**
 * Presents the platform picker, which reports through `onPicked` at most once. Throws when the launch failed, and
 * then no request holds the one-picker slot.
 */
internal typealias PresentPicker<T> = (onPicked: (Result<List<T>>) -> Unit) -> PickerRequest

/** One platform picker request, which owns its slot and the platform cleanup. */
internal interface PickerRequest {
  /** Completes once this request no longer holds the one-picker slot. */
  val pickerReleased: CompletableFuture<Unit>

  /** Ends the request on the picker's thread: a result still to come is dropped and the slot released. */
  fun end()
}

/**
 * One `importFromLibrary` call, from presentation through admission. It submits at most [maxCount] picks (0: no limit)
 * in picked order through [admit], so the session's serial image worker keeps that order, and settles [onResult] once:
 * with the admitted page IDs in picked order once every pick has settled, on a picker or destination failure, or with
 * `processor/disposed` when the session closes first. Unreadable picks are skipped; cancelling or admitting none
 * resolves `[]`. [T] is the platform's pick; tests use plain values.
 *
 * Its close registration decides who settles: a normal outcome claims it with `tryUnregister()`, and once close has
 * taken it only the close cancellation settles. The registration stays until the picker has released its slot, and
 * close awaits that release.
 */
internal class LibraryImport<T>(
  private val maxCount: Int,
  /** Posts to the picker's thread. Returns false if the task was rejected. */
  private val post: (() -> Unit) -> Boolean,
  private val admit: AdmitPickedImage<T>,
  private val onResult: (Result<List<String>>) -> Unit,
) {
  private val lock = Any()
  private lateinit var closeRegistration: ScanSessionState.CloseRegistration
  /** The result is delivered, or left to the close cancellation; no picker may open any more. */
  private var settled = false
  private var presenting = false
  private var pickerRequest: PickerRequest? = null
  /** Completes once no picker request of this call holds the slot. */
  private val pickerReleased = CompletableFuture<Unit>()

  /**
   * [register] ties the call to its session's close; [present] then runs through [post] unless close has taken the
   * registration first.
   */
  fun start(register: (cancel: () -> CompletableFuture<Unit>) -> ScanSessionState.CloseRegistration, present: PresentPicker<T>) {
    closeRegistration = try {
      register(::cancelForClose)
    } catch (error: Throwable) {
      // No cancellation was installed, so nothing else settles the call.
      return onResult(Result.failure(error))
    }
    try {
      if (!post { open(present) }) throw ScannerException(ScannerErrorCode.PROCESSOR_UNAVAILABLE)
    } catch (error: Throwable) {
      // Nothing was presented, so no request holds the slot.
      pickerReleased.complete(Unit)
      complete(Result.failure(error))
    }
  }

  /** Presents only while still registered; close that takes the registration later waits for the request it makes. */
  private fun open(present: PresentPicker<T>) {
    if (!synchronized(lock) { (!settled && closeRegistration.isRegistered).also { presenting = it } }) return
    val request = try {
      present(::picked)
    } catch (error: Throwable) {
      synchronized(lock) { presenting = false }
      // The launch failed without holding the slot.
      pickerReleased.complete(Unit)
      return complete(Result.failure(error))
    }
    val ending = synchronized(lock) {
      presenting = false
      pickerRequest = request
      settled
    }
    request.pickerReleased.whenComplete { _, _ -> pickerReleased.complete(Unit) }
    // Cancellation arrived while the presenter ran: end the request it returned, here on the picker's thread.
    if (ending) request.end()
  }

  private fun picked(result: Result<List<T>>) {
    // The document-picker fallback ignores the limit; picks past it are dropped, keeping the first in returned order.
    val picks = result.getOrElse { return complete(Result.failure(it)) }.let { if (maxCount > 0) it.take(maxCount) else it }
    // Close may have taken the registration without running its cancellation yet; that cancellation settles the call.
    if (synchronized(lock) { settled || !closeRegistration.isRegistered }) return
    if (picks.isEmpty()) return complete(Result.success(emptyList()))
    val outcomes = arrayOfNulls<Result<String>>(picks.size)
    var remaining = picks.size
    picks.forEachIndexed { index, pick ->
      val onDone = { outcome: Result<String> ->
        val done = synchronized(lock) {
          outcomes[index] = outcome
          --remaining == 0
        }
        if (done) {
          // Admitted pages stay in the session even when another pick's destination failure rejects the call.
          val failure = outcomes.firstNotNullOfOrNull { outcome -> outcome?.exceptionOrNull()?.takeUnless(::unreadable) }
          complete(failure?.let { Result.failure(it) } ?: Result.success(outcomes.mapNotNull { it?.getOrNull() }))
        }
      }
      try { admit(pick, onDone) } catch (error: Throwable) { onDone(Result.failure(error)) }
    }
  }

  /** A pick whose source could not be read or decoded is skipped; any other failure rejects the call. */
  private fun unreadable(error: Throwable) = error is ScannerException && error.code == ScannerErrorCode.IMAGE_LOAD_FAILED

  /**
   * A normal outcome, delivered once the picker has released its slot and only if it claims the registration first;
   * otherwise the close cancellation owns settlement. The picker dismisses itself before it reports, so nothing is
   * ended here.
   */
  private fun complete(result: Result<List<String>>) {
    pickerReleased.whenComplete { _, _ ->
      val claimed = synchronized(lock) {
        (!settled && closeRegistration.tryUnregister()).also { if (it) settled = true }
      }
      if (claimed) onResult(result)
    }
  }

  /**
   * The close cancellation: settles with `processor/disposed`, ends this call's picker and returns its release, which
   * close awaits. A presenter still running ends the request it returns; with no request, nothing holds the slot.
   */
  private fun cancelForClose(): CompletableFuture<Unit> {
    val (presenter, request) = synchronized(lock) {
      settled = true
      presenting to pickerRequest
    }
    when {
      presenter -> Unit
      request == null -> pickerReleased.complete(Unit)
      !pickerReleased.isDone -> try {
        if (!post(request::end)) throw ScannerException(ScannerErrorCode.PROCESSOR_UNAVAILABLE)
      } catch (error: Throwable) {
        // The request is still owned by the picker thread. Report the failed cleanup to close.
        pickerReleased.completeExceptionally(error)
      }
    }
    onResult(Result.failure(ScannerException(ScannerErrorCode.PROCESSOR_DISPOSED)))
    return pickerReleased
  }
}
