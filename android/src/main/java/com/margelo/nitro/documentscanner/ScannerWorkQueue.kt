package com.margelo.nitro.documentscanner

import android.util.Log
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Serial metadata FIFO. Admission, replacement and publication decisions are synchronized; actions,
 * settlement callbacks and close-barrier continuations always run outside the admission lock.
 *
 * A null page ID is unkeyed work. Keyed replacement uses session + kind + page ID, removes only
 * waiting entries, and appends the replacement at the tail. Owners never shut down the worker.
 */
internal class ScannerWorkQueue(
  private val worker: Executor,
  private val reportCallbackError: (Throwable) -> Unit,
) {
  enum class Kind { CAPTURE, ANALYSIS, RENDER, DECODE, PDF }

  data class Metadata(val sessionId: String, val kind: Kind, val pageId: String?)

  class Owner internal constructor(internal val queue: ScannerWorkQueue) {
    val sessionId: String = UUID.randomUUID().toString()
    // Guarded by queue.lock. Outstanding includes settlement/cleanup callbacks still in progress.
    internal var generation = 0L
    internal var closed = false
    internal var outstanding = 0
    internal val completion = CompletableFuture<Unit>()
  }

  private val lock = Any()
  private val waiting = ArrayDeque<Entry>()
  private var draining = false

  fun newOwner() = Owner(this)

  /**
   * Callbacks own success/failure cleanup. onDiscard releases a successful result that cannot be
   * published; queued rejection and action failure go through onFailure without inventing a result.
   */
  fun <T> submit(
    owner: Owner,
    kind: Kind,
    pageId: String? = null,
    operation: () -> T,
    onSuccess: (T) -> Unit,
    onFailure: (Throwable) -> Unit,
    onDiscard: (T) -> Unit = {},
  ) {
    require(owner.queue === this) { "Owner belongs to another queue." }
    val removed = mutableListOf<Entry>()
    var start = false
    val accepted = synchronized(lock) {
      if (owner.closed) false else {
        if (pageId != null) {
          val iterator = waiting.iterator()
          while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.owner === owner && entry.metadata.kind == kind && entry.metadata.pageId == pageId) {
              iterator.remove()
              removed.add(entry)
            }
          }
        }
        waiting.addLast(Job(owner, Metadata(owner.sessionId, kind, pageId), owner.generation,
          operation, onSuccess, onFailure, onDiscard))
        owner.outstanding++
        if (!draining) {
          draining = true
          start = true
        }
        true
      }
    }
    if (!accepted) {
      callback { onFailure(ScannerException(ScannerErrorCode.PROCESSOR_DISPOSED)) }
      return
    }
    removed.forEach { it.reject(ScannerException(ScannerErrorCode.PROCESSOR_SUPERSEDED)) }
    if (start) dispatch()
  }

  /**
   * Cancels waiting work only for this owner; running actions finish without interruption and
   * discard their results if the generation closed before publication was claimed.
   *
   * The barrier completes after that owner's actions and settlement/cleanup callbacks finish.
   * Chain eventual resource close onto it; never wait for it from an action or callback.
   */
  fun close(owner: Owner): CompletableFuture<Unit> {
    require(owner.queue === this) { "Owner belongs to another queue." }
    val removed = mutableListOf<Entry>()
    val idle = synchronized(lock) {
      if (!owner.closed) {
        owner.closed = true
        owner.generation++
        val iterator = waiting.iterator()
        while (iterator.hasNext()) {
          val entry = iterator.next()
          if (entry.owner === owner) {
            iterator.remove()
            removed.add(entry)
          }
        }
      }
      owner.outstanding == 0
    }
    removed.forEach { it.reject(ScannerException(ScannerErrorCode.PROCESSOR_DISPOSED)) }
    if (idle) owner.completion.complete(Unit)
    return owner.completion
  }

  private fun dispatch() {
    try {
      worker.execute(::drain)
    } catch (error: Throwable) {
      val rejected = synchronized(lock) {
        val entries = waiting.toList()
        waiting.clear()
        draining = false
        entries
      }
      rejected.forEach { it.reject(ScannerException(ScannerErrorCode.PROCESSOR_UNAVAILABLE, error)) }
    }
  }

  private fun drain() {
    while (true) {
      val entry = synchronized(lock) {
        if (waiting.isEmpty()) {
          draining = false
          null
        } else waiting.removeFirst()
      } ?: return
      entry.execute()
    }
  }

  private abstract class Entry(val owner: Owner, val metadata: Metadata) {
    abstract fun execute()
    abstract fun reject(error: Throwable)
  }

  private inner class Job<T>(
    owner: Owner,
    metadata: Metadata,
    private val generation: Long,
    private val operation: () -> T,
    private val onSuccess: (T) -> Unit,
    private val onFailure: (Throwable) -> Unit,
    private val onDiscard: (T) -> Unit,
  ) : Entry(owner, metadata) {
    override fun execute() {
      val result = runCatching(operation)
      // Linearization point: a publication claimed before close is already committed.
      val publish = synchronized(lock) { !owner.closed && owner.generation == generation }
      try {
        if (!publish) {
          result.onSuccess { value -> callback { onDiscard(value) } }
          callback { onFailure(ScannerException(ScannerErrorCode.PROCESSOR_DISPOSED)) }
        } else {
          result.fold(
            { value -> callback { onSuccess(value) } },
            { error -> callback { onFailure(error) } },
          )
        }
      } finally {
        finished(owner)
      }
    }

    override fun reject(error: Throwable) {
      try {
        callback { onFailure(error) }
      } finally {
        finished(owner)
      }
    }
  }

  private fun finished(owner: Owner) {
    val complete = synchronized(lock) {
      owner.outstanding--
      owner.closed && owner.outstanding == 0
    }
    if (complete) owner.completion.complete(Unit)
  }

  private fun callback(action: () -> Unit) {
    try {
      action()
    } catch (error: Throwable) {
      // A throwing consumer must not strand unrelated jobs or its own close barrier.
      try { reportCallbackError(error) } catch (_: Throwable) { /* Reporting cannot stop the FIFO. */ }
    }
  }

  companion object {
    private fun shared(name: String) = ScannerWorkQueue(
      Executors.newSingleThreadExecutor { task -> Thread(task, name).apply { isDaemon = true } },
      { error -> Log.e("RNDocumentScanner", "Scanner queue callback failed.", error) },
    )

    val images: ScannerWorkQueue = shared("DocumentScannerImages")
  }
}
