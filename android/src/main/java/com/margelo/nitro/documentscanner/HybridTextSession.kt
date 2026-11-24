package com.margelo.nitro.documentscanner

import com.margelo.nitro.core.Promise

/**
 * One text scan, apart from any camera view: the capture being picked from and the attached camera's status. It
 * outlives the views that show it, so a remounted camera finds the capture still there; one camera shows it at a time.
 * Every change publishes a whole snapshot with a new version, delivered outside the lock, so an observer that sees an
 * older version after a newer one drops it. Any thread.
 */
class HybridTextSession : HybridTextSessionSpec() {
  /** What the attached camera does for the session. */
  internal interface Camera {
    /** Takes a capture within [timeout] milliseconds, calling [done] once with it, or with the failure's code. Any
     * thread. */
    fun capture(timeout: Long, done: (TextCapture?, ScannerErrorCode?) -> Unit)
  }

  private val lock = Any()
  private var version = 0L
  private var closed = false
  private var camera: Camera? = null
  private var status = TextScanStatus.OFF
  private var capture: TextCapture? = null
  private var nextObserver = 0L
  private val observers = LinkedHashMap<Long, (TextSessionSnapshot) -> Unit>()
  /** Captures not yet settled; close fails them, so a later result finds them gone. */
  private val waiting = mutableSetOf<Promise<Variant_ScannerFailure_TextSessionSnapshot>>()

  /** Shows the session in [camera]; false while another camera shows it. */
  internal fun attach(camera: Camera): Boolean {
    change {
      if (this.camera === camera) return true
      if (this.camera != null) return false
      this.camera = camera
    }
    return true
  }

  internal fun detach(camera: Camera) {
    change {
      if (this.camera !== camera) return
      this.camera = null
      status = TextScanStatus.OFF
    }
  }

  /** The attached [camera]'s scan does [status]. */
  internal fun show(camera: Camera, status: TextScanStatus) {
    change {
      if (this.camera !== camera || this.status == status) return
      this.status = status
    }
  }

  override fun captureResult(timeout: Double?): Promise<Variant_ScannerFailure_TextSessionSnapshot> {
    val promise = Promise<Variant_ScannerFailure_TextSessionSnapshot>()
    val camera = synchronized(lock) {
      when {
        closed -> null.also { promise.resolve(failure(ScannerErrorCode.PROCESSOR_DISPOSED)) }
        camera == null -> null.also { promise.resolve(failure(ScannerErrorCode.CAMERA_UNAVAILABLE)) }
        else -> camera.also { waiting += promise }
      }
    } ?: return promise
    // Milliseconds.
    val wait = (timeout ?: 2000.0).toLong().coerceIn(0, 30_000)
    camera.capture(wait) { taken, code ->
      var settled: TextSessionSnapshot? = null
      val delivery = synchronized(lock) {
        if (!waiting.remove(promise)) return@capture
        if (taken == null) null else {
          capture = taken
          version++
          snapshotLocked().also { settled = it } to observers.values.toList()
        }
      }
      delivery?.let { (snapshot, to) -> to.forEach { it(snapshot) } }
      promise.resolve(settled?.let { Variant_ScannerFailure_TextSessionSnapshot.create(it) }
        ?: failure(code ?: ScannerErrorCode.CAPTURE_FAILED))
    }
    return promise
  }

  override fun clearCapture() {
    change {
      if (capture == null) return
      capture = null
    }
  }

  override fun getSnapshot(): TextSessionSnapshot = synchronized(lock) { snapshotLocked() }

  override fun observeResult(onChange: (snapshot: TextSessionSnapshot) -> Unit): Variant_TextSessionObservation_ScannerFailure {
    val (id, snapshot) = synchronized(lock) {
      val id = nextObserver++
      if (!closed) observers[id] = onChange
      id to snapshotLocked()
    }
    val unsubscribe = Func_void_java { synchronized(lock) { observers.remove(id) } }
    return Variant_TextSessionObservation_ScannerFailure.create(TextSessionObservation(snapshot, unsubscribe))
  }

  override fun closeResult(): Promise<Variant_Boolean_ScannerFailure> {
    val (failed, delivery) = synchronized(lock) {
      if (closed) return Promise.resolved(Variant_Boolean_ScannerFailure.create(true))
      closed = true
      capture = null
      camera = null
      status = TextScanStatus.OFF
      version++
      val failed = waiting.toList()
      waiting.clear()
      val delivery = snapshotLocked() to observers.values.toList()
      observers.clear()
      failed to delivery
    }
    failed.forEach { it.resolve(failure(ScannerErrorCode.PROCESSOR_DISPOSED)) }
    delivery.second.forEach { it(delivery.first) }
    return Promise.resolved(Variant_Boolean_ScannerFailure.create(true))
  }

  /** Runs [block] under the lock and publishes a new version unless it returned early; a closed session changes no more. */
  private inline fun change(block: () -> Unit) {
    val delivery = synchronized(lock) {
      if (closed) return
      block()
      version++
      snapshotLocked() to observers.values.toList()
    }
    delivery.second.forEach { it(delivery.first) }
  }

  private fun snapshotLocked() = TextSessionSnapshot(version.toDouble(), closed, status, capture)

  private fun failure(code: ScannerErrorCode) = Variant_ScannerFailure_TextSessionSnapshot.create(ScannerFailure(code))
}
