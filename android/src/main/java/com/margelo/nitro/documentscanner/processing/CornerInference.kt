package com.margelo.nitro.documentscanner.processing

import android.content.Context
import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import com.margelo.nitro.documentscanner.DocumentCandidate
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/** One CPU interpreter and tensor workspace shared by live and photo inference, on one worker held to the prime cores.
 * Callers wait on their existing background executors, keeping ImageProxy/Bitmap ownership local.
 */
internal object CornerInference {
  private val worker = Executors.newSingleThreadExecutor { task ->
    Thread(task, "DocumentScannerModel").apply { isDaemon = true }
  }
  // All fields below are confined to worker.
  private var users = 0
  private var selection = QuadModelStore.selected
  private var session: DocCornerSession? = null

  private fun <T> onWorker(operation: () -> T): T {
    // Pinned anew each time, since moving between foreground and background resets affinity.
    val future = worker.submit<T> { FastCores.prime(); operation() }
    // Do not return ownership of borrowed image memory until the worker has finished with it.
    var interrupted = false
    try {
      while (true) {
        try { return future.get() }
        catch (_: InterruptedException) { interrupted = true }
        catch (error: ExecutionException) { throw error.cause ?: error }
      }
    } finally {
      if (interrupted) Thread.currentThread().interrupt()
    }
  }

  fun validate(context: Context, selection: QuadModelStore.Selection) = onWorker {
    selection.open(context).use { it.warmUp() }
  }

  fun acquire(context: Context): Lease {
    onWorker { users++ }
    return Lease(context.applicationContext)
  }

  private fun open(context: Context): DocCornerSession {
    val selected = QuadModelStore.current
    if (selected !== selection) {
      session?.close()
      session = null
      selection = selected
    }
    return session ?: selected.open(context).also { session = it }
  }

  /** Each owner confines its lease and timing values to its own executor. */
  class Lease internal constructor(private val context: Context) : LiveQuadModel {
    private var closed = false
    override var lastPreprocessingMs = 0.0
      private set
    override var lastInferenceMs = 0.0
      private set
    override var lastScore: Double? = null
      private set
    /** The model the last call ran, which a swap since then does not change. */
    var lastSelection: QuadModelStore.Selection? = null
      private set

    private fun <T> useSession(operation: (DocCornerSession) -> T): T {
      check(!closed) { "The corner model lease is closed." }
      return onWorker {
        val model = open(context)
        operation(model).also {
          lastPreprocessingMs = model.lastPreprocessingMs
          lastInferenceMs = model.lastInferenceMs
          lastScore = model.lastScore
          lastSelection = selection
        }
      }
    }

    override fun warmUp() = useSession { it.warmUp() }
    override fun analyze(image: ImageProxy): DocumentCandidate? = useSession { it.analyze(image) }
    fun analyze(bitmap: Bitmap): DocumentCandidate? = useSession { it.analyze(bitmap) }

    override fun close() {
      if (closed) return
      closed = true
      onWorker {
        users--
        if (users == 0) {
          session?.close()
          session = null
        }
      }
    }
  }
}
