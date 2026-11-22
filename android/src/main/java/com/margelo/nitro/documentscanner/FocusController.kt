package com.margelo.nitro.documentscanner

import android.os.SystemClock
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.MeteringPoint
import java.util.concurrent.Executor

/** The bound camera's controls; a new binding gives a new instance. */
internal class BoundCamera(val control: CameraControl, val info: CameraInfo)

/** Who holds the lens, each with a scan that locks focus: a tap, a capture, a page lock on a steady page that holds
 * until the page is gone, or a recovery lock at the frame's centre while no page can be found. A [timed] owner holds
 * for [FocusPolicy.TAP_HOLD_NS]. The scanner's own locks, [PAGE] and [RECOVERY], end at once when their scan fails. */
internal enum class FocusOwner(val timed: Boolean) {
  TAP(true), CAPTURE(false), PAGE(false), RECOVERY(true);

  val scanners: Boolean get() = this == PAGE || this == RECOVERY
}

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

/**
 * Main-thread CameraX adapter. [FocusPolicy] owns scan and tap state and generations; this class owns each scan's whole
 * attempt: the hardware request, the frames that prove its lock ([LockFrameWatch], armed with the scan's token), one
 * resend of a lost trigger, deadlines, the lens's release, deferred past a shot in flight, and immutable published
 * snapshots. A scan's owner is published before its request is sent, so nothing reads the old focus while the lens
 * moves. CameraX runs a locking scan in single-shot autofocus and restores continuous autofocus when it is cancelled;
 * between scans the host aims continuous autofocus again. Only [observeFrame] runs off main.
 */
internal class FocusController(private val host: Host) : AutoCloseable {
  /** What [releaseScannerLock] did. After [RELEASED] and [DEFERRED] the controller aims continuous autofocus itself,
   * once the cancellation completes; after [NONE] nothing was held and the caller aims it. */
  enum class Release { RELEASED, DEFERRED, NONE }

  interface Host {
    val camera: BoundCamera?
    val mainExecutor: Executor
    fun shotInFlight(): Boolean
    /** Aims continuous autofocus while no scan holds the lens. */
    fun resumeContinuousFocus()
    fun postDelayed(delayMs: Long, block: () -> Unit)
    fun log(message: String)
    fun focusChanged(snapshot: FocusSnapshot)
    /** A page or recovery lock's scan failed and was released. */
    fun scannerLockFailed(owner: FocusOwner) {}
    /** The bound camera offers single-shot autofocus, whose frames [observeFrame] reads: a lock counts once a frame shows
     * it. Without it CameraX's report stands. */
    val confirmsLockFrames: Boolean get() = false
    fun uptimeNs(): Long = SystemClock.uptimeMillis() * 1_000_000
  }
  private val policy = FocusPolicy()
  private var closed = false
  private var scan: Scan? = null
  /** A scan CameraX reported locked before any frame showed the lock; its lock frame settles it. */
  private var unconfirmed: Scan? = null
  /** The current scan's trigger and lock frames; guarded by itself, since [observeFrame] runs on a camera thread. */
  private val frames = LockFrameWatch()
  /** A scanner lock whose release waits for the shot in flight, and whether its scan failed; [afterCapture] ends it, a
   * newer scan or [unbind] retires it. */
  private var deferredRelease: Scan? = null
  private var deferredFailed = false
  /** The cancellation whose completion aims continuous autofocus; null while none is pending. */
  private var pendingCancel: Any? = null
  /** Platform timer revision only; it never represents focus ownership. */
  private var wakeRevision = 0L
  var snapshot = FocusSnapshot(0, null, null, 0, 0, 0)
    private set
  /** A released lock's cancellation is pending, and its completion aims continuous autofocus at the host's target then:
   * the host does not aim it meanwhile, which would send a second metering action. */
  val resuming: Boolean get() = pendingCancel != null

  /** A tap's scan, focusing at [focusPoint] and metering exposure at [exposurePoint]; it holds the lens for
   * [FocusPolicy.TAP_HOLD_NS]. A shot in flight refuses it. */
  fun tap(camera: BoundCamera, focusPoint: MeteringPoint, exposurePoint: MeteringPoint) {
    if (closed || host.shotInFlight()) return
    trigger(start(camera, FocusOwner.TAP, FocusMeteringAction.Builder(focusPoint, FocusMeteringAction.FLAG_AF)
      .addPoint(exposurePoint, FocusMeteringAction.FLAG_AE).disableAutoCancel().build()))
  }
  /** A capture's own scan at [point], held until [afterCapture]. */
  fun capture(camera: BoundCamera, point: MeteringPoint) {
    if (closed || host.shotInFlight()) return
    trigger(start(camera, FocusOwner.CAPTURE,
      FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF).disableAutoCancel().build()))
  }
  /** The view's own lock, [FocusOwner.PAGE] or [FocusOwner.RECOVERY], scanning at [point], and metering exposure there
   * too with [meterExposure]; only while nothing holds the lens. The text camera's lock is a [FocusOwner.PAGE] one. */
  fun lock(camera: BoundCamera, point: MeteringPoint, owner: FocusOwner, meterExposure: Boolean = false) {
    require(owner.scanners)
    if (closed || host.shotInFlight() || policy.owner != null) return
    val flags = if (meterExposure) FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE else FocusMeteringAction.FLAG_AF
    trigger(start(camera, owner, FocusMeteringAction.Builder(point, flags).disableAutoCancel().build()))
  }
  /**
   * Ends the scanner's own lock, if one holds, and returns the lens to continuous autofocus, which this controller aims
   * once CameraX completes the cancellation. While a shot is in flight cancelling would move the lens during the still,
   * so the release waits for [afterCapture].
   */
  fun releaseScannerLock(): Release {
    if (closed || policy.owner?.scanners != true) return Release.NONE
    val current = scan
    if (host.shotInFlight() && current != null) {
      if (deferredRelease !== current) host.log("${policy.owner?.name?.lowercase()} focus release waits for the shot")
      deferRelease(current, failed = false)
      return Release.DEFERRED
    }
    if (!policy.releaseScannerLock()) return Release.NONE
    released()
    scheduleDeadlines()
    return Release.RELEASED
  }
  /** A shot settled: a tap past its hold and a capture's scan end, and a scanner lock's release that waited for the shot
   * runs now. */
  fun afterCapture() {
    if (closed) return
    if (policy.afterCapture(host.uptimeNs())) released()
    val deferred = deferredRelease
    val failed = deferredFailed
    deferredRelease = null
    deferredFailed = false
    val owner = policy.owner
    if (deferred != null && deferred === scan && owner != null && policy.releaseScannerLock()) {
      host.log("${owner.name.lowercase()} focus released after the shot")
      released()
      if (failed) host.scannerLockFailed(owner)
    }
    scheduleDeadlines()
  }
  fun unbind() {
    if (closed) return
    scan = null
    pendingCancel = null
    unconfirmed = null
    deferredRelease = null
    deferredFailed = false
    synchronized(frames) { frames.arm(0) }
    ++wakeRevision
    policy.unbind()
    publish()
  }
  /**
   * Camera thread: one frame of the bound camera, [trigger] its request's autofocus trigger, with its result's autofocus
   * mode and state. The host forwards only its current binding's frames. A lost trigger or a lock frame is handed to
   * main tagged with the scan it was seen for, which acts on it only while that scan is still pending.
   */
  fun observeFrame(frameNumber: Long, trigger: Int?, afMode: Int?, afState: Int?) {
    var token = 0L
    val event = synchronized(frames) {
      token = frames.scan
      if (token == 0L) LockFrameWatch.Event.NONE else frames.observe(frameNumber, trigger, afMode, afState)
    }
    when (event) {
      LockFrameWatch.Event.LOST -> host.mainExecutor.execute { triggerLost(token) }
      LockFrameWatch.Event.LOCKED -> host.mainExecutor.execute { lockFrameSeen(token) }
      LockFrameWatch.Event.NONE -> Unit
    }
  }
  /**
   * Main thread: the camera dropped scan [token]'s trigger. A trigger that reached continuous autofocus froze the lens
   * without a sweep, and CameraX may report that as a lock. The same action is sent once more as a new scan of the same
   * owner, without cancelling, so the camera stays in single-shot autofocus and the new trigger sweeps; a scan that
   * loses it again fails. A shot in flight leaves the scan to its deadline.
   */
  private fun triggerLost(token: Long) {
    val current = scan ?: return
    val owner = policy.owner ?: return
    if (current.token != token || !pending(current)) return
    host.log("${owner.name.lowercase()} focus trigger lost")
    if (host.shotInFlight()) return
    if (!current.resent) {
      host.log("focus trigger resent")
      trigger(start(current.camera, owner, current.action, resent = true))
      return
    }
    host.log("${owner.name.lowercase()} focus trigger lost twice")
    settle(current, false)
  }
  /** Main thread: a frame showed scan [token]'s lock in single-shot autofocus; settles a lock CameraX already reported. */
  private fun lockFrameSeen(token: Long) {
    val target = unconfirmed ?: return
    if (target.token != token) return
    unconfirmed = null
    if (pending(target)) settle(target, true)
  }
  private fun start(camera: BoundCamera, owner: FocusOwner, action: FocusMeteringAction, resent: Boolean = false): Scan {
    unconfirmed = null
    deferredRelease = null
    deferredFailed = false
    val next = Scan(camera, policy.start(owner, host.uptimeNs()), action, resent)
    // Armed before the request is sent, so its trigger frame is watched for this scan.
    synchronized(frames) { frames.arm(next.token) }
    host.log("${owner.name.lowercase()} focus scan started")
    scan = next
    publish()
    scheduleDeadlines()
    return next
  }
  private fun pending(target: Scan): Boolean = !closed && scan === target && host.camera === target.camera &&
    policy.scanToken == target.token && policy.pending
  private fun lockFrameReported(target: Scan): Boolean = !host.confirmsLockFrames ||
    synchronized(frames) { frames.scan == target.token && frames.lockReported }
  private fun deferRelease(target: Scan, failed: Boolean) {
    deferredRelease = target
    deferredFailed = deferredFailed || failed
  }
  private fun trigger(target: Scan) {
    host.log("focus trigger sent")
    val future = try { target.camera.control.startFocusAndMetering(target.action) } catch (error: Exception) {
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
  /** A success counts once a frame shows the lock in single-shot autofocus; until then the scan stays pending, bounded by
   * its deadline, and a lost trigger is resent. A failed scanner lock is released, after the shot in flight if one is. */
  private fun settle(target: Scan, success: Boolean) {
    if (!pending(target)) return
    if (success && !lockFrameReported(target)) {
      if (unconfirmed !== target) host.log("${policy.owner?.name?.lowercase()} focus reported locked; awaiting its lock frame")
      unconfirmed = target
      return
    }
    if (unconfirmed === target) unconfirmed = null
    if (!policy.complete(target.token, success)) return
    publish()
    val owner = snapshot.owner
    host.log("${owner?.name?.lowercase()} focus settled success=$success")
    if (!success && owner != null && owner.scanners) {
      if (host.shotInFlight()) deferRelease(target, failed = true)
      else if (policy.releaseScannerLock()) {
        released()
        host.scannerLockFailed(owner)
      }
    }
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
    if (cancelled == null) {
      host.resumeContinuousFocus()
      return
    }
    val token = Any()
    pendingCancel = token
    cancelled.addListener({
      if (pendingCancel !== token) return@addListener
      pendingCancel = null
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
    closed = true; ++wakeRevision; scan = null; pendingCancel = null
    synchronized(frames) { frames.arm(0) }
  }
  private class Scan(val camera: BoundCamera, val token: Long, val action: FocusMeteringAction, val resent: Boolean)
}
