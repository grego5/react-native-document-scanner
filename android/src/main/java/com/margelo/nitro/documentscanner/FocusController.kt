package com.margelo.nitro.documentscanner

import android.os.SystemClock
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.MeteringPoint
import java.util.concurrent.Executor

/** The bound camera's controls; a new binding gives a new instance. */
internal class BoundCamera(val control: CameraControl, val info: CameraInfo)

/** Who holds the lens: a tap's or a quality capture's scan, each locking focus. */
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
  /** A tap's or a capture's scan still holds the lens, scanning or locked; its own focus regions apply. */
  fun scanHolds(nowMs: Long): Boolean = owner != null && nowMs < heldUntilMs

  /** A tap's or a capture's scan has not settled yet. */
  fun scanning(nowMs: Long): Boolean = scanHolds(nowMs) && locked == null
}

/** Main-thread CameraX adapter. [FocusPolicy] owns scan and tap state and generations; this class owns only hardware
 * requests, callback attempts, wakeups and immutable published snapshots. A scan's owner is published before its request
 * is sent, so nothing reads the old focus while the lens moves. CameraX runs a locking scan in single-shot autofocus and
 * restores continuous autofocus when it is cancelled; between scans the host aims continuous autofocus again.
 */
internal class FocusController(private val host: Host) : AutoCloseable {
  interface Host {
    val camera: BoundCamera?
    val mainExecutor: Executor
    fun shotInFlight(): Boolean
    /** Aims continuous autofocus while no scan holds the lens. */
    fun resumeContinuousFocus()
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

  /** A tap's scan, focusing at [focusPoint] and metering exposure at [exposurePoint]; it holds the lens for
   * [FocusPolicy.TAP_HOLD_NS]. A shot in flight refuses it. */
  fun tap(camera: BoundCamera, focusPoint: MeteringPoint, exposurePoint: MeteringPoint) {
    if (closed || host.shotInFlight()) return
    val next = start(camera, FocusOwner.TAP)
    trigger(next, FocusMeteringAction.Builder(focusPoint, FocusMeteringAction.FLAG_AF)
      .addPoint(exposurePoint, FocusMeteringAction.FLAG_AE).disableAutoCancel().build())
  }
  /** A quality capture's own scan at [point], held until [afterCapture]. */
  fun capture(camera: BoundCamera, point: MeteringPoint) {
    if (closed || host.shotInFlight()) return
    val next = start(camera, FocusOwner.CAPTURE)
    trigger(next, FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF).disableAutoCancel().build())
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
  private fun start(camera: BoundCamera, owner: FocusOwner): Scan {
    val next = Scan(camera, policy.start(owner, host.uptimeNs()))
    host.log("${owner.name.lowercase()} focus scan started")
    scan = next
    publish()
    scheduleDeadlines()
    return next
  }
  private fun pending(target: Scan): Boolean = !closed && scan === target && host.camera === target.camera &&
    policy.scanToken == target.token && policy.pending
  private fun trigger(target: Scan, action: FocusMeteringAction) {
    host.log("focus trigger sent")
    val future = try { target.camera.control.startFocusAndMetering(action) } catch (error: Exception) {
      host.log("focus trigger threw ${error.javaClass.simpleName}")
      if (pending(target)) settle(target, false)
      return
    }
    future.addListener({
      val success = try { future.get().isFocusSuccessful } catch (error: Exception) {
        host.log("focus trigger failed ${error.javaClass.simpleName}: ${error.message}")
        false
      }
      if (pending(target)) settle(target, success)
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
    publish()
    val camera = host.camera
    if (ended == null || ended.camera !== camera) {
      host.resumeContinuousFocus()
      return
    }
    // Cancelling ends the lock and restores continuous autofocus, but also clears its regions.
    val cancelled = try { camera.control.cancelFocusAndMetering() } catch (_: Exception) { null }
    if (cancelled == null) host.resumeContinuousFocus()
    else cancelled.addListener({
      if (!closed && scan == null && host.camera === camera) host.resumeContinuousFocus()
    }, host.mainExecutor)
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
  private class Scan(val camera: BoundCamera, val token: Long)
}
