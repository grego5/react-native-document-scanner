package com.margelo.nitro.documentscanner

import java.io.File
import java.io.IOException
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class SessionResourcesTest {
  private class ManualExecutor : Executor {
    private val tasks = ArrayDeque<Runnable>()
    override fun execute(command: Runnable) { tasks.addLast(command) }
    fun drain() {
      var count = 0
      while (tasks.isNotEmpty()) {
        check(count++ < 1000) { "Cleanup failed to quiesce." }
        tasks.removeFirst().run()
      }
    }
  }

  /** Simulated files only. Real canonical-path, symlink and Android I/O assertions live in device tests. */
  private class FakeFiles : SessionFiles {
    val directories = linkedSetOf<File>()
    val existing = linkedSetOf<File>()
    val removedDirectories = mutableListOf<File>()
    var beforeCreate: (File) -> Unit = {}
    var afterPublish: () -> Unit = {}
    var failCreate = false
    var failFiles = false
    var failDirectory = false

    override fun directory(cache: File, sessionId: String) = File(File(cache, "sessions"), "session-$sessionId")
    override fun createDirectory(directory: File) {
      beforeCreate(directory)
      directories.add(directory)
      if (failCreate) throw IOException("mkdir")
    }
    override fun sessions(cache: File) = directories.filter { it.parentFile == File(cache, "sessions") }
    override fun output(directory: File, id: String, kind: SessionResources.Kind, extension: String) =
      SessionFiles.Output(directory, File(directory, ".$id.tmp.$extension"), File(directory, "$id.$extension"))
    override fun publish(output: SessionFiles.Output) {
      check(existing.remove(output.temporary)) { "No staged file." }
      check(existing.add(output.destination)) { "Output was overwritten." }
      afterPublish()
    }
    override fun remove(output: SessionFiles.Output) {
      if (failFiles) throw IOException("file cleanup")
      existing.remove(output.temporary)
      existing.remove(output.destination)
    }
    override fun removeDirectory(cache: File, directory: File) {
      check(directory.parentFile == File(cache, "sessions"))
      if (failDirectory) throw IOException("directory cleanup")
      removedDirectories.add(directory)
      existing.removeAll { it.parentFile == directory }
      directories.remove(directory)
    }
  }

  private val cache = File("session-resource-state-test").absoluteFile
  private val files = FakeFiles()
  private val imageExecutor = ManualExecutor()
  private val cleanup = ManualExecutor()
  private val errors = mutableListOf<Throwable>()
  private val forgotten = mutableListOf<File>()
  private val queue = ScannerWorkQueue(imageExecutor) { errors.add(it) }
  private fun registry() = ScanSessionRegistry(files, queue, cleanup, { forgotten.addAll(it) }, { errors.add(it) })
  private val registry = registry()

  private fun publish(session: SessionResources, kind: SessionResources.Kind = SessionResources.Kind.ORIGINAL) =
    session.output(kind).use { output -> files.existing.add(output.file); output.commit() }

  private fun finish(future: CompletableFuture<Unit>) {
    cleanup.drain()
    future.get(1, TimeUnit.SECONDS)
  }

  @Test fun registrationPrecedesDirectoryCreationAndEveryContextCleanup() {
    files.beforeCreate = { directory ->
      finish(registry.cleanupAbandoned(cache))
      assertFalse(directory in files.removedDirectories)
    }
    val firstContext = registry.open(cache)
    val secondContext = registry.open(cache)
    assertNotEquals(firstContext.sessionId, secondContext.sessionId)
    val abandoned = files.directory(cache, UUID.randomUUID().toString())
    files.directories.add(abandoned)
    val export = File(cache, "export.pdf")
    val unrelated = File(cache, "other.jpg")
    files.existing.addAll(listOf(export, unrelated))
    finish(registry.cleanupAbandoned(cache))
    assertEquals(listOf(abandoned), files.removedDirectories)
    assertTrue(firstContext.directory in files.directories)
    assertTrue(secondContext.directory in files.directories)
    assertTrue(files.existing.containsAll(listOf(export, unrelated)))
    finish(firstContext.close())
    finish(secondContext.close())
  }

  @Test fun allResourceKindsRemainPinnedUntilTheirLastUserReleases() {
    val session = registry.open(cache)
    for (kind in SessionResources.Kind.entries) {
      val resource = publish(session, kind)
      val decoder = session.lease(resource)
      val view = session.lease(resource)
      val sampleOrExport = session.lease(resource)
      val path = decoder.file
      val removed = session.remove(resource)
      assertThrows(InvalidInputException::class.java) { session.lease(resource) }
      cleanup.drain()
      assertFalse(removed.isDone)
      assertTrue(path in files.existing)
      decoder.close()
      decoder.close() // A lease releases exactly once.
      view.close()
      cleanup.drain()
      assertTrue(path in files.existing)
      sampleOrExport.close()
      finish(removed)
      assertFalse(path in files.existing)
      assertTrue(path in forgotten)
      assertSame(removed, session.remove(resource))
    }
    finish(session.close())
    assertFalse(session.directory in files.directories)
    assertTrue(errors.isEmpty())
  }

  @Test fun failureAndStaleCompletionCleanTemporaryAndPublishedFiles() {
    val session = registry.open(cache)
    val failed = session.output(SessionResources.Kind.FRAME)
    assertThrows(IOException::class.java) {
      failed.use { files.existing.add(it.file); throw IOException("encoding") }
    }
    finish(failed.resource.deleted)
    assertFalse(failed.file in files.existing)
    val stale = session.output(SessionResources.Kind.RENDER)
    files.existing.add(stale.file)
    var closing: CompletableFuture<Unit>? = null
    files.afterPublish = { closing = session.close() }
    assertThrows(ScannerException::class.java) { stale.use { it.commit() } }
    finish(closing!!)
    assertFalse(stale.file in files.existing)
    assertFalse(stale.destination in files.existing)
    assertFalse(session.directory in files.directories)
  }

  @Test fun retiringAnActiveWriterRejectsPublicationAndWaitsForItsPin() {
    val session = registry.open(cache)
    val output = session.output(SessionResources.Kind.FRAME)
    files.existing.add(output.file)
    val retired = session.remove(output.resource)
    cleanup.drain()
    assertFalse(retired.isDone)
    val error = assertThrows(ScannerException::class.java) { output.use { it.commit() } }
    assertEquals(ScannerErrorCode.PROCESSOR_SUPERSEDED, error.code)
    finish(retired)
    assertFalse(output.file in files.existing)
    assertFalse(output.destination in files.existing)
    finish(session.close())
  }

  @Test fun partialRenderFailureRetiresBothOutputsIncludingTheCommittedOne() {
    val session = registry.open(cache)
    val output = session.renderDestination("jpg")
    assertThrows(IllegalStateException::class.java) {
      output.use {
        try {
          files.existing.add(it.image.file)
          it.image.commit()
          it.thumbnail.commit() // Missing staged thumbnail.
        } catch (error: Throwable) {
          it.discard()
          throw error
        }
      }
    }
    finish(output.image.resource.deleted)
    finish(output.thumbnail.resource.deleted)
    assertTrue(files.existing.isEmpty())
    finish(session.close())
  }

  @Test fun closeIsIdempotentAndWaitsForLeasesAndRunningWorkWithoutBlockingThatWorker() {
    val session = registry.open(cache)
    val source = publish(session)
    val lease = session.lease(source)
    var closing: CompletableFuture<Unit>? = null
    var discarded = 0
    var failures = 0
    queue.submit(session.workOwner, ScannerWorkQueue.Kind.ANALYSIS,
      operation = {
        closing = session.close()
        assertSame(closing, session.close())
        cleanup.drain()
        assertFalse(closing!!.isDone)
        assertTrue(lease.file in files.existing)
        lease.close()
        cleanup.drain()
        assertTrue(session.directory in files.directories) // Queue action is still running.
        42
      },
      onSuccess = { fail("Closed generation published.") },
      onFailure = {
        assertEquals(ScannerErrorCode.PROCESSOR_DISPOSED, (it as ScannerException).code)
        failures++
      },
      onDiscard = { assertEquals(42, it); discarded++ })
    imageExecutor.drain()
    finish(closing!!)
    assertEquals(1, discarded)
    assertEquals(1, failures)
    assertFalse(session.directory in files.directories)
    assertThrows(ScannerException::class.java) { session.output(SessionResources.Kind.ORIGINAL) }
    assertTrue(errors.isEmpty())
  }

  @Test fun closeCancelsQueuedWritersAndTheirFailureCleanupReleasesTheProducerPin() {
    val session = registry.open(cache)
    val output = session.output(SessionResources.Kind.ORIGINAL)
    var failures = 0
    queue.submit(session.workOwner, ScannerWorkQueue.Kind.ANALYSIS,
      operation = { fail("Cancelled writer ran.") },
      onSuccess = { fail("Cancelled writer succeeded.") },
      onFailure = { output.close(); failures++ })
    finish(session.close())
    imageExecutor.drain()
    assertEquals(1, failures)
    assertFalse(session.directory in files.directories)
  }

  @Test fun foreignAndUnpublishedResourcesCannotBeLeased() {
    val a = registry.open(cache)
    val b = registry.open(cache)
    a.output(SessionResources.Kind.ORIGINAL).use {
      assertThrows(InvalidInputException::class.java) { a.lease(it.resource) }
      assertThrows(IllegalArgumentException::class.java) { b.lease(it.resource) }
      assertThrows(IllegalArgumentException::class.java) { b.remove(it.resource) }
    }
    finish(a.close())
    finish(b.close())
  }

  @Test fun cleanupFailureKeepsRegistrationUntilALaterProcessStartup() {
    val session = registry.open(cache)
    val original = publish(session)
    files.failFiles = true
    val removed = session.remove(original)
    cleanup.drain()
    assertTrue(removed.isCompletedExceptionally)
    files.failDirectory = true
    val closed = session.close()
    cleanup.drain()
    assertTrue(closed.isCompletedExceptionally)
    assertSame(closed, session.close())
    assertTrue(errors.any { it.message == "directory cleanup" })
    files.failDirectory = false
    files.failFiles = false
    finish(registry.cleanupAbandoned(cache))
    assertTrue(session.directory in files.directories) // Same process still protects failed close.
    finish(registry().cleanupAbandoned(cache)) // A new process registry sees an abandoned directory.
    assertFalse(session.directory in files.directories)
  }

  @Test fun cleanupExecutorRejectionSettlesCloseAndRetainsDirectoryProtection() {
    val rejected = ScanSessionRegistry(files, queue,
      Executor { throw java.util.concurrent.RejectedExecutionException("cleanup unavailable") },
      { forgotten.addAll(it) }, { errors.add(it) })
    val session = rejected.open(cache)
    val closed = session.close()
    assertTrue(closed.isCompletedExceptionally)
    assertSame(closed, session.close())
    assertTrue(session.directory in files.directories)
    assertTrue(errors.any { it.message == "cleanup unavailable" })
  }

  @Test fun failedDirectoryCreationIsRegisteredUntilItsAsynchronousCleanupFinishes() {
    files.failCreate = true
    assertThrows(IOException::class.java) { registry.open(cache) }
    assertEquals(1, files.directories.size)
    cleanup.drain()
    assertTrue(files.directories.isEmpty())
  }
}
