package com.margelo.nitro.documentscanner

import android.net.Uri
import android.util.Log
import com.margelo.nitro.documentscanner.processing.SampleCollection
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** Process-wide directory protection, independent of JS contexts and Fast Refresh. */
internal class ScanSessionRegistry(
  internal val files: SessionFiles,
  internal val imageQueue: ScannerWorkQueue,
  private val cleanup: Executor,
  private val forget: (Collection<File>) -> Unit,
  private val report: (Throwable) -> Unit,
) {
  private val lock = Any()
  private val registered = HashMap<File, SessionResources>()
  private val cleaning = HashSet<File>()
  private val states = HashMap<String, ScanSessionState>()

  fun open(cache: File): SessionResources {
    val owner = imageQueue.newOwner()
    val directory = files.directory(cache, owner.sessionId)
    val resources = SessionResources(this, cache, directory, owner)
    synchronized(lock) {
      check(directory !in registered && directory !in cleaning) { "Session directory is already reserved." }
      registered[directory] = resources
    }
    // Registration precedes mkdir, staging files, and every operation that might create an output.
    try {
      files.createDirectory(directory)
      // Also covers headless callers that never instantiate React view managers. Failures are logged.
      cleanupAbandoned(cache)
      return resources
    } catch (error: Throwable) {
      resources.close()
      throw error
    }
  }

  /** Explicit lifetime: state/pages survive JS wrapper collection until close finishes deleting files. */
  fun createState(cache: File, actions: (SessionResources) -> ScanSessionState.Actions): ScanSessionState {
    val resources = open(cache)
    try {
      val state = ScanSessionState(resources, actions(resources))
      synchronized(lock) { states[resources.sessionId] = state }
      return state
    } catch (error: Throwable) {
      resources.close()
      throw error
    }
  }

  /** Every startup/context may call this. Registered and failed-close directories remain protected. */
  fun cleanupAbandoned(cache: File): CompletableFuture<Unit> {
    val completed = CompletableFuture<Unit>()
    execute({
      try {
        for (directory in files.sessions(cache)) {
          val claimed = synchronized(lock) { directory !in registered && cleaning.add(directory) }
          if (!claimed) continue
          try {
            files.removeDirectory(cache, directory)
          } finally {
            synchronized(lock) { cleaning.remove(directory) }
          }
        }
        files.removeLegacyImages(cache)
        completed.complete(Unit)
      } catch (error: Throwable) {
        failure(error)
        completed.completeExceptionally(error)
      }
    }, { completed.completeExceptionally(it) })
    return completed
  }

  fun requireExternalOutput(file: File) {
    val path = file.canonicalFile
    synchronized(lock) {
      requireInput(registered.keys.none { (path == it.canonicalFile || path.path.startsWith(it.canonicalPath + File.separator)) }) {
        "Export output must be outside active session directories."
      }
    }
    // Also reject reserved session-shaped paths before that session is registered.
    var ancestor: File? = file.canonicalFile
    while (ancestor != null) {
      val item = ancestor
      requireInput(!(item.parentFile?.name == LocalSessionFiles.NAMESPACE && item.name.startsWith("session-"))) {
        "Export output must be outside session directories."
      }
      ancestor = item.parentFile
    }
  }

  internal fun execute(action: () -> Unit, rejected: (Throwable) -> Unit) {
    try { cleanup.execute(action) } catch (error: Throwable) { failure(error); rejected(error) }
  }

  internal fun forget(output: SessionFiles.Output) = forget(listOf(output.temporary, output.destination))

  internal fun release(resources: SessionResources) {
    files.removeDirectory(resources.cache, resources.directory)
    synchronized(lock) {
      check(registered[resources.directory] === resources)
      registered.remove(resources.directory)
      states.remove(resources.sessionId)
    }
  }

  internal fun failure(error: Throwable) {
    try { report(error) } catch (_: Throwable) { /* A diagnostic must not strand cleanup futures. */ }
  }

  companion object {
    val shared = ScanSessionRegistry(
      LocalSessionFiles,
      ScannerWorkQueue.images,
      Executors.newSingleThreadExecutor { task -> Thread(task, "DocumentScannerSessionCleanup").apply { isDaemon = true } },
      { paths ->
        PageThumbnails.forget(paths.map { Uri.fromFile(it).toString() })
        SampleCollection.forget(paths.map { it.name })
      },
      { error -> Log.w("RNDocumentScanner", "Session resource cleanup failed.", error) },
    )
  }
}
