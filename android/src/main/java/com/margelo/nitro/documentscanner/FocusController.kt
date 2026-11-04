package com.margelo.nitro.documentscanner

import android.os.SystemClock
import androidx.camera.core.Camera
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.MeteringPoint
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executor

/** Who holds the lens: a tap's or a quality capture's scan, each in single-shot autofocus. */
internal enum class FocusOwner { TAP, CAPTURE }

/**
 * Focus as everything else reads it. [generation] changes whenever a scan starts, settles or ends, and [changedNs] and
 * [changedRealtimeNs] are that moment on System.nanoTime() and elapsedRealtimeNanos(). [owner] is null while nothing
 * holds the lens and continuous autofocus runs. [locked] is null while a scan scans.
 */
internal data class FocusSnapshot(
  val generation: Long,
  val owner: FocusOwner?,
  val locked: Boolean?,
  val heldUntilMs: Long,
  val changedNs: Long,
  val changedRealtimeNs: Long,
) {
  /** A tap's or a capture's scan still holds the lens, scanning or locked; CameraX's own focus regions apply. */
  fun scanHolds(nowMs: Long): Boolean = owner != null && nowMs < heldUntilMs

  /** A tap's or a capture's scan has not settled yet. */
  fun scanning(nowMs: Long): Boolean = scanHolds(nowMs) && locked == null
}

/** Main-thread CameraX adapter. [FocusPolicy] owns scan and tap state and generations; this class owns only hardware
 * requests, callback attempts, wakeups and immutable published snapshots. Between scans continuous autofocus runs
 * untouched.
 */
internal class FocusController(private val host: Host) : AutoCloseable {
  interface Host {
    val camera: Camera?
    val mainExecutor: Executor
    fun shotInFlight(): Boolean
    /** Applies the Camera2 options for the current snapshot; the future completes once the camera has them. */
    fun applyCaptureOptions(): ListenableFuture<Void>?
    fun postDelayed(delayMs: Long, block: () -> Unit)
    fun log(message: String)
    fun focusChanged(snapshot: FocusSnapshot)
    fun uptimeNs(): Long = SystemClock.uptimeMillis() * 1_000_000
  }
  private val policy = FocusPolicy()
  private var closed = false
  private var scan: Scan? = null
  /** Platform timer revision only; it never represents focus ownership. */
  private var wakeRevision = 0L
  var snapshot = FocusSnapshot(0, null, null, 0, 0, 0)
    private set

  fun tap(camera: Camera, point: MeteringPoint) {
    if (closed || host.shotInFlight()) return
    start(camera, FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
      .disableAutoCancel().build(), FocusOwner.TAP)
  }
  /** A quality capture's own scan at [point], held until [afterCapture]. */
  fun capture(camera: Camera, point: MeteringPoint) {
    if (closed || host.shotInFlight()) return
    start(camera, FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF).disableAutoCancel().build(),
      FocusOwner.CAPTURE)
  }
  fun afterCapture() {
    if (closed) return
    if (policy.afterCapture(host.uptimeNs())) released()
    scheduleDeadlines()
  }
  fun unbind() {
    if (closed) return
    scan = null
    ++wakeRevision
    policy.unbind()
    publish()
  }
  private fun start(camera: Camera, action: FocusMeteringAction, owner: FocusOwner) {
    val startedNs = host.uptimeNs()
    val next = Scan(camera, policy.start(owner, startedNs))
    host.log("${owner.name.lowercase()} focus scan started")
    scan = next
    publish() // Options must see the new owner before touching the lens.
    val lifted = host.applyCaptureOptions()
    scheduleDeadlines()
    // The trigger waits for the options that switch to single-shot autofocus: a trigger that arrives during a passive
    // scan of continuous autofocus can be ignored for good.
    if (lifted == null) trigger(next, action)
    else lifted.addListener({
      host.log("focus options applied after=${(host.uptimeNs() - startedNs) / 1_000_000}ms pending=${pending(next)}")
      if (pending(next)) trigger(next, action)
    }, host.mainExecutor)
  }
  private fun pending(target: Scan): Boolean = !closed && scan === target && host.camera === target.camera &&
    policy.scanToken == target.token && policy.pending
  private fun trigger(target: Scan, action: FocusMeteringAction) {
    val attempt = ++target.triggers
    host.log("focus trigger sent attempt=$attempt")
    val future = try { target.camera.cameraControl.startFocusAndMetering(action) } catch (error: Exception) {
      host.log("focus trigger threw ${error.javaClass.simpleName}")
      if (pending(target)) settle(target, false)
      return
    }
    future.addListener({
      val success = try { future.get().isFocusSuccessful } catch (error: Exception) {
        host.log("focus trigger failed ${error.javaClass.simpleName}: ${error.message}")
        false
      }
      if (pending(target) && target.triggers == attempt) settle(target, success)
    }, host.mainExecutor)
  }
  private fun settle(target: Scan, success: Boolean) {
    if (!pending(target) || !policy.complete(target.token, success)) return
    publish()
    host.log("${snapshot.owner?.name?.lowercase()} focus settled success=$success")
    scheduleDeadlines()
  }
  private fun released() {
    val ended = scan
    scan = null
    publish() // Options must see ownership end before restoring continuous AF.
    if (ended != null && ended.camera === host.camera) {
      try { ended.camera.cameraControl.cancelFocusAndMetering() } catch (_: Exception) { }
    }
    host.applyCaptureOptions()
  }
  private fun publish() {
    val heldUntilNs = policy.heldUntilNs
    snapshot = FocusSnapshot(policy.generation, policy.owner, policy.locked,
      if (heldUntilNs == Long.MAX_VALUE) Long.MAX_VALUE else heldUntilNs / 1_000_000,
      System.nanoTime(), SystemClock.elapsedRealtimeNanos())
    host.focusChanged(snapshot)
  }
  private fun scheduleDeadlines() {
    val revision = ++wakeRevision
    if (closed) return
    val now = host.uptimeNs()
    // An overdue tap waits for the shot in flight; [afterCapture] ends it.
    val tap = policy.tapDeadline()?.takeIf { it > now || !host.shotInFlight() }
    val deadline = listOfNotNull(tap, policy.scanDeadline()).minOrNull() ?: return
    host.postDelayed((deadline - now).coerceAtLeast(0).let { (it + 999_999) / 1_000_000 }) {
      if (closed || revision != wakeRevision) return@postDelayed
      val current = scan
      val time = host.uptimeNs()
      val tapUntil = policy.tapDeadline()
      if (current != null && tapUntil != null && time >= tapUntil &&
        policy.expireTap(current.token, time, host.shotInFlight())) released()
      val scanUntil = policy.scanDeadline()
      if (current != null && scanUntil != null && time >= scanUntil && pending(current)) settle(current, false)
      host.focusChanged(snapshot)
      scheduleDeadlines()
    }
  }
  override fun close() {
    if (closed) return
    closed = true; ++wakeRevision; scan = null
  }
  private class Scan(val camera: Camera, val token: Long) { var triggers = 0 }
}
