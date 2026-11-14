package com.margelo.nitro.documentscanner.processing

import android.content.Context
import com.margelo.nitro.documentscanner.*
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** The app-wide corner model's state. Selections stay native. */
internal sealed class CornerModelStatus {
  object Absent : CornerModelStatus()
  class Loading(val source: String) : CornerModelStatus()
  class Ready(val source: String, val selection: CornerModelStore.Selection) : CornerModelStatus()
  class Failed(val source: String, val error: Throwable) : CornerModelStatus()
}

/**
 * The app-wide model loader. Local source I/O runs on its worker, separate from inference, and only the newest
 * request may activate. A request for the source already loading or ready joins it. The plugin default never
 * replaces a requested source and follows whichever request replaces it. Callbacks run outside the lock, on any
 * thread.
 */
internal class CornerTensorModelLoader(
  private val prepare: (Context, String) -> CornerModelStore.Selection = CornerModelStore::prepare,
  private val activate: (CornerModelStore.Selection) -> Unit = CornerModelStore::activate,
  private val active: () -> CornerModelStore.Selection? = { CornerModelStore.selected },
  private val bundledDefault: (Context) -> String? = { defaultSource(it) },
  private val worker: Executor = Executors.newSingleThreadExecutor { task ->
    Thread(task, "ScannerModelSources").apply { isDaemon = true }
  },
) {
  /** Calls never overlap and run without a lock; a status that arrives during a call waits as the latest. */
  private class Observer(val listener: (CornerModelStatus) -> Unit) {
    private var delivered = -1L
    private var latest: Pair<Long, CornerModelStatus>? = null
    private var draining = false
    @Volatile var removed = false

    fun deliver(version: Long, status: CornerModelStatus) {
      synchronized(this) {
        if (version <= (latest?.first ?: delivered)) return
        latest = version to status
        if (draining) return
        draining = true
      }
      while (true) {
        val next = synchronized(this) {
          val pending = latest
          latest = null
          if (pending == null || removed) { draining = false; return }
          delivered = pending.first
          pending.second
        }
        listener(next)
      }
    }
  }

  /** A [follows] waiter moves to a replacing request instead of being superseded. */
  private class Waiter(val follows: Boolean, val done: (Throwable?) -> Unit)

  private val lock = Any()
  private var request = 0L
  private var version = 0L
  private var status: CornerModelStatus = CornerModelStatus.Absent
  /** Callbacks of the loading request: null once it is active, else its error. */
  private var waiters = mutableListOf<Waiter>()
  private val observers = LinkedHashSet<Observer>()

  val current: CornerModelStatus get() = synchronized(lock) { status }

  /** An explicit source: replaces any other source. Without [done], nobody waits on it. */
  fun load(context: Context, source: String, done: ((Throwable?) -> Unit)? = null) = request(context, source, true, done)

  /** Starts the packaged default, if the app contains it, with nobody waiting on it; see [loadCameraSource]. */
  fun startDefault(context: Context) {
    bundledDefault(context)?.let { request(context, it, false, null) }
  }

  /**
   * A camera's source: any non-empty one is explicit. Empty follows the plugin default, which must exist: it starts
   * only when nothing was requested yet, otherwise joins the current request, and a request that replaces it reports
   * to [done] in its place.
   */
  fun loadCameraSource(context: Context, source: String, done: (Throwable?) -> Unit) {
    if (source.isNotEmpty()) return load(context, source, done)
    val fallback = bundledDefault(context) ?: return done(InvalidInputException(
      "guideLiveCorners and autoCrop need a cornerDetectionModel; the app contains no packaged model."))
    request(context, fallback, false, done)
  }

  /**
   * Reports the current status, then the latest status after changes; a burst can collapse into its last status.
   * Calls never overlap and may request loads. The returned removal is idempotent.
   */
  fun observe(listener: (CornerModelStatus) -> Unit): () -> Unit {
    val observer = Observer(listener)
    val (at, snapshot) = synchronized(lock) { observers += observer; version to status }
    observer.deliver(at, snapshot)
    return { synchronized(lock) { observer.removed = true; observers -= observer } }
  }

  /** `true` once the current request is active, `false` without any request; rejects its failure or supersession. */
  fun await(done: (Result<Boolean>) -> Unit) {
    val settled = synchronized(lock) {
      when (val now = status) {
        CornerModelStatus.Absent -> Result.success(false)
        is CornerModelStatus.Ready -> Result.success(true)
        is CornerModelStatus.Failed -> Result.failure(now.error)
        is CornerModelStatus.Loading -> {
          waiters += Waiter(false) { error -> done(if (error == null) Result.success(true) else Result.failure(error)) }
          null
        }
      }
    }
    settled?.let(done)
  }

  /** A null [done] starts or joins the request without waiting on it. */
  private fun request(context: Context, source: String, replace: Boolean, done: ((Throwable?) -> Unit)?) {
    var joined: Result<Unit>? = null
    var superseded: List<Waiter> = emptyList()
    val waiter = done?.let { Waiter(!replace, it) }
    var update: Pair<Long, CornerModelStatus>? = null
    var id = 0L
    synchronized(lock) {
      when (val now = status) {
        is CornerModelStatus.Loading -> if (!replace || now.source == source) { waiter?.let { waiters += it }; return }
        is CornerModelStatus.Ready ->
          if (!replace || (now.source == source && active() === now.selection)) joined = Result.success(Unit)
        is CornerModelStatus.Failed -> if (!replace) joined = Result.failure(now.error)
        CornerModelStatus.Absent -> Unit
      }
      if (joined == null) {
        val (following, replaced) = waiters.partition { it.follows }
        superseded = replaced
        waiters = (following + listOfNotNull(waiter)).toMutableList()
        id = ++request
        update = publish(CornerModelStatus.Loading(source))
      }
    }
    joined?.let { done?.invoke(it.exceptionOrNull()); return }
    val supersession = ScannerException(ScannerErrorCode.MODEL_SUPERSEDED)
    superseded.forEach { it.done(supersession) }
    notify(update)
    worker.execute { run(context, id, source) }
  }

  private fun run(context: Context, id: Long, source: String) {
    if (synchronized(lock) { id != request }) return
    val outcome = runCatching {
      requireInput(source.isNotEmpty()) { "Model URI must not be empty." }
      prepare(context, source)
    }
    val (done, update) = synchronized(lock) {
      if (id != request) return
      val next = outcome.fold({ activate(it); CornerModelStatus.Ready(source, it) },
        { CornerModelStatus.Failed(source, it) })
      waiters.also { waiters = mutableListOf() } to publish(next)
    }
    notify(update)
    done.forEach { it.done(outcome.exceptionOrNull()) }
  }

  /** Caller holds [lock]. */
  private fun publish(next: CornerModelStatus): Pair<Long, CornerModelStatus> {
    status = next
    return ++version to next
  }

  private fun notify(update: Pair<Long, CornerModelStatus>?) {
    val (at, next) = update ?: return
    synchronized(lock) { observers.toList() }.forEach { it.deliver(at, next) }
  }

  companion object {
    val shared = CornerTensorModelLoader()

    /** The app asset the Expo plugin installs `cornerDetectionModel` as. */
    const val DEFAULT_SOURCE = "asset:///corner-detection-model.tflite"

    fun defaultSource(context: Context): String? =
      runCatching { context.assets.open(DEFAULT_SOURCE.removePrefix("asset:///")).close() }
        .map { DEFAULT_SOURCE }.getOrNull()
  }
}
