package com.margelo.nitro.documentscanner

import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns immutable session files. Tokens do not expose paths; every file user holds a lease, including
 * decoders, views, sample/export operations and writers. Retirement prevents new leases immediately.
 */
internal class SessionResources internal constructor(
  private val registry: ScanSessionRegistry,
  internal val cache: File,
  val directory: File,
  val workOwner: ScannerWorkQueue.Owner,
) {
  enum class Kind { ORIGINAL, FRAME, RENDER, THUMBNAIL }

  class Resource internal constructor(
    val id: String,
    val kind: Kind,
    internal val owner: SessionResources,
    internal val paths: SessionFiles.Output,
  ) {
    // Guarded by owner.lock.
    internal var owned = true
    internal var published = false
    internal var pins = 1 // The producer holds the initial pin until Output.close.
    internal var cleanupScheduled = false
    internal val deleted = CompletableFuture<Unit>()
  }

  class Lease internal constructor(internal val resource: Resource) : AutoCloseable {
    val file: File get() = resource.paths.destination
    private val released = AtomicBoolean()
    override fun close() {
      if (released.compareAndSet(false, true)) resource.owner.unpin(resource)
    }
  }

  /** Single-producer transaction. Use with use{} so exceptions and stale publication retire outputs. */
  inner class Output internal constructor(val resource: Resource) : AutoCloseable {
    val file: File get() = resource.paths.temporary
    val destination: File get() = resource.paths.destination
    internal val owner: SessionResources get() = this@SessionResources
    private var ended = false
    private var committed = false

    fun commit(): Resource {
      synchronized(lock) {
        check(!ended && !committed) { "Output has already ended or committed." }
        requireOpen()
        if (!resource.owned) throw ScannerException(ScannerErrorCode.PROCESSOR_SUPERSEDED)
      }
      registry.files.publish(resource.paths)
      synchronized(lock) {
        // Close may happen during encoding or publication. The producer pin still protects both paths.
        requireOpen()
        if (!resource.owned) throw ScannerException(ScannerErrorCode.PROCESSOR_SUPERSEDED)
        resource.published = true
        committed = true
      }
      return resource
    }

    /** Retires even a committed output, e.g. when the other half of a render fails or a revision is stale. */
    fun discard() {
      synchronized(lock) { resource.owned = false }
      cleanupReady()
    }

    override fun close() {
      synchronized(lock) {
        if (ended) return
        ended = true
        if (!committed) resource.owned = false
        resource.pins--
      }
      cleanupReady()
    }
  }

  class RenderDestination internal constructor(val image: Output, val thumbnail: Output) : AutoCloseable {
    init { require(image.owner === thumbnail.owner) }
    internal val owner: SessionResources get() = image.owner
    fun discard() { image.discard(); thumbnail.discard() }
    override fun close() { thumbnail.close(); image.close() }
  }

  val sessionId: String get() = workOwner.sessionId
  private val lock = Any()
  private val records = LinkedHashSet<Resource>()
  private var closed = false
  private var workDrained = false
  private var directoryCleanupScheduled = false
  private val completion = CompletableFuture<Unit>()

  fun requireExternalOutput(file: File) = registry.requireExternalOutput(file)

  fun output(kind: Kind, extension: String = "jpg"): Output {
    val id = UUID.randomUUID().toString()
    val paths = registry.files.output(directory, id, kind, extension)
    return synchronized(lock) {
      requireOpen()
      val resource = Resource(id, kind, this, paths)
      records.add(resource)
      Output(resource)
    }
  }

  fun renderDestination(extension: String): RenderDestination {
    val image = output(Kind.RENDER, extension)
    try {
      return RenderDestination(image, output(Kind.THUMBNAIL))
    } catch (error: Throwable) {
      image.close()
      throw error
    }
  }

  fun lease(resource: Resource): Lease = synchronized(lock) {
    require(resource.owner === this) { "Resource belongs to another session." }
    requireOpen()
    requireInput(resource.owned && resource.published) { "Resource has been retired or is not published." }
    resource.pins++
    Lease(resource)
  }

  fun remove(resource: Resource): CompletableFuture<Unit> {
    require(resource.owner === this) { "Resource belongs to another session." }
    synchronized(lock) { resource.owned = false }
    cleanupReady()
    return resource.deleted
  }

  private fun unpin(resource: Resource) {
    synchronized(lock) { resource.pins-- }
    cleanupReady()
  }

  /** Idempotent and nonblocking. The directory stays registered through all running work and leases. */
  fun close(): CompletableFuture<Unit> {
    synchronized(lock) {
      if (closed) return completion
      closed = true
      records.forEach { it.owned = false }
    }
    // Queue cancellation invokes failure cleanup; never hold the resource lock across it.
    registry.imageQueue.close(workOwner).whenComplete { _, error ->
      if (error != null) registry.failure(error)
      synchronized(lock) { workDrained = true }
      cleanupReady()
    }
    cleanupReady()
    return completion
  }

  private fun requireOpen() {
    if (closed) throw ScannerException(ScannerErrorCode.PROCESSOR_DISPOSED)
  }

  private fun cleanupReady() {
    var directoryReady = false
    val ready = synchronized(lock) {
      val ready = records.filter { !it.owned && it.pins == 0 && !it.cleanupScheduled }
      ready.forEach { it.cleanupScheduled = true }
      if (closed && workDrained && records.isEmpty() && !directoryCleanupScheduled) {
        directoryCleanupScheduled = true
        directoryReady = true
      }
      ready
    }
    ready.forEach { resource ->
      registry.execute({
        val result = runCatching {
          registry.forget(resource.paths)
          registry.files.remove(resource.paths)
        }
        finishRemoval(resource, result.exceptionOrNull())
      }, { finishRemoval(resource, it) })
    }
    if (directoryReady) {
      registry.execute({
        try {
          registry.release(this)
          completion.complete(Unit)
        } catch (error: Throwable) {
          registry.failure(error)
          completion.completeExceptionally(error)
        }
      }, { completion.completeExceptionally(it) })
    }
  }

  private fun finishRemoval(resource: Resource, error: Throwable?) {
    synchronized(lock) { records.remove(resource) }
    if (error == null) resource.deleted.complete(Unit) else {
      registry.failure(error)
      resource.deleted.completeExceptionally(error)
    }
    // A failed file removal may still be recovered by final directory cleanup. Directory failure
    // leaves registration in place, is observable on close, and is retried only after process restart.
    cleanupReady()
  }
}
