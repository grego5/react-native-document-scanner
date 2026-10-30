package com.margelo.nitro.documentscanner

import android.os.SystemClock
import androidx.camera.core.Camera
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.MeteringPoint
import com.margelo.nitro.documentscanner.processing.DocumentFocusAction
import com.margelo.nitro.documentscanner.processing.DocumentFocusPolicy
import com.margelo.nitro.documentscanner.processing.LiveFocusCandidate
import java.util.concurrent.Executor

internal enum class FocusOwner { TAP, DOCUMENT }

/**
 * Focus as everything else reads it. [generation] changes whenever a scan starts, settles or ends, and [changedNs] and
 * [changedRealtimeNs] are that moment on System.nanoTime() and elapsedRealtimeNanos(). [owner] is null while no owned
 * scan holds the lens: continuous autofocus, or a parked lens in `scanner` mode. [locked] is null while the scan scans.
 */
internal data class FocusSnapshot(
  val generation: Long,
  val owner: FocusOwner?,
  val locked: Boolean?,
  val heldUntilMs: Long,
  val changedNs: Long,
  val changedRealtimeNs: Long,
) {
  /** An owned scan, the document's or a tap's, holds a lock. */
  fun lockHeld(nowMs: Long): Boolean = owner != null && nowMs < heldUntilMs && locked == true

  /** An owned scan still holds the lens, scanning or locked; CameraX's own focus regions apply. */
  fun scanHolds(nowMs: Long): Boolean = owner != null && nowMs < heldUntilMs
}

/**
 * The one owner of focus lifecycle on the bound camera: taps, document scans the [DocumentFocusPolicy] asks for, their
 * retries, timeouts and locks, and when the lens returns to continuous autofocus or its parked distance. Every change
 * publishes a new [FocusSnapshot] to [Host.focusChanged]; nothing else writes focus state. While a shot is in flight
 * nothing new moves the lens: taps and retriggers are ignored, and scans that end wait for [afterCapture]. A capture
 * that is only waiting does not freeze focus. Main thread.
 */
internal class FocusController(private val host: Host) {
  interface Host {
    val camera: Camera?
    val mainExecutor: Executor
    /** A shot has been requested; its focus must not change until it settles. */
    fun shotInFlight(): Boolean
    /** Re-applies the Camera2 options, which depend on whether a scan holds the lens. */
    fun applyCaptureOptions()
    fun postDelayed(delayMs: Long, block: () -> Unit)
    fun log(message: String)
    fun focusChanged(snapshot: FocusSnapshot)
  }

  private val policy = DocumentFocusPolicy()
  private var scan: Scan? = null
  var snapshot = FocusSnapshot(0, null, null, 0, 0, 0)
    private set

  /**
   * A tap focuses and meters at [point] and takes priority for [TAP_MS], resetting document focus. Ignored while a shot
   * is in flight. The tap ends here rather than by CameraX's auto-cancel, so its end can wait for a shot.
   */
  fun tap(camera: Camera, point: MeteringPoint) {
    if (host.shotInFlight()) return
    start(camera, FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
      .disableAutoCancel().build(), FocusOwner.TAP)
  }

  /**
   * A detection result for document focus. [parked] says the lens parks between scans, so only a confirmed page starts
   * the first one; [point] maps the hint's page centre onto the preview, null when it falls outside.
   */
  fun onCandidate(hint: LiveFocusCandidate, parked: Boolean, point: (LiveFocusCandidate) -> MeteringPoint?) {
    val camera = host.camera ?: return
    val now = SystemClock.uptimeMillis()
    val tapHolds = scan?.let { it.owner == FocusOwner.TAP && now < it.heldUntilMs } == true
    // A capture waiting for focus relies on this policy; only a shot in flight holds the lens still.
    val blocked = host.shotInFlight() || tapHolds
    when (policy.observe(hint.quad, hint.trusted, hint.focusFailed, now, blocked, acquireTrusted = parked)) {
      DocumentFocusAction.NONE -> Unit
      DocumentFocusAction.RELEASE -> releaseDocument()
      DocumentFocusAction.START -> {
        val target = point(hint)
        if (target == null) {
          policy.settled(false, now)
          releaseDocument()
          return
        }
        host.log("document focus start trusted=${hint.trusted}")
        start(camera, FocusMeteringAction.Builder(target, FocusMeteringAction.FLAG_AF).disableAutoCancel().build(),
          FocusOwner.DOCUMENT)
      }
    }
  }

  /** Ends document focus, returning the lens to continuous autofocus or its parked distance; never ends a tap's. */
  fun releaseDocument() {
    if (host.shotInFlight()) return
    val ended = scan?.takeIf { it.owner == FocusOwner.DOCUMENT } ?: return
    scan = null
    // Published first: the capture options read the snapshot to park the lens again.
    publish()
    if (ended.camera === host.camera) {
      try { ended.camera.cameraControl.cancelFocusAndMetering() } catch (_: Exception) { }
      host.applyCaptureOptions()
    }
  }

  /** Document focus settings changed: the policy starts afresh, and without [keepDocument] its scan ends. */
  fun resetPolicy(keepDocument: Boolean) {
    policy.reset()
    if (!keepDocument) releaseDocument()
  }

  /** After a capture: an expired tap, a failed document scan, or one no longer wanted, gives the lens back. */
  fun afterCapture(documentWanted: Boolean) {
    val current = scan ?: return
    if (current.owner == FocusOwner.TAP) {
      if (SystemClock.uptimeMillis() >= current.heldUntilMs) endTap(current)
      return
    }
    if (!documentWanted || current.locked == false) releaseDocument()
  }

  /** The camera was unbound, which cancels every scan. */
  fun unbind() {
    scan = null
    policy.reset()
    publish()
  }

  private fun start(camera: Camera, action: FocusMeteringAction, owner: FocusOwner) {
    val next = Scan(camera, if (owner == FocusOwner.TAP) SystemClock.uptimeMillis() + TAP_MS else Long.MAX_VALUE, owner)
    if (owner == FocusOwner.TAP) policy.reset()
    scan = next
    // Published first: the capture options read the snapshot to lift the centre area or parked distance, which would
    // override the scan, until it ends.
    publish()
    host.applyCaptureOptions()
    // A shot in flight keeps the lens; afterCapture ends the tap instead.
    if (owner == FocusOwner.TAP) host.postDelayed(TAP_MS) { if (!host.shotInFlight()) endTap(next) }
    if (owner == FocusOwner.DOCUMENT) host.postDelayed(SCAN_TIMEOUT_MS) {
      if (scan === next && next.locked == null) settle(next, false)
    }
    trigger(next, action)
    // Samsung's autofocus drops a trigger that lands in a passive scan and never reports a lock though the lens
    // focuses; one repeat after that scan has ended locks.
    host.postDelayed(RETRIGGER_MS) {
      if (scan === next && host.camera === camera && next.locked == null && !host.shotInFlight()) {
        host.log("focus retriggered")
        trigger(next, action)
      }
    }
  }

  private fun endTap(ended: Scan) {
    if (scan !== ended) return
    scan = null
    // Published first: the capture options read the snapshot to park the lens again.
    publish()
    if (ended.camera === host.camera) {
      try { ended.camera.cameraControl.cancelFocusAndMetering() } catch (_: Exception) { }
      host.applyCaptureOptions()
    }
  }

  /** Sends [action] for [target]; only the latest trigger's result settles it. */
  private fun trigger(target: Scan, action: FocusMeteringAction) {
    val attempt = ++target.triggers
    val future = try { target.camera.cameraControl.startFocusAndMetering(action) } catch (_: Exception) {
      if (scan === target) settle(target, false)
      return
    }
    future.addListener({
      // A cancel, by a newer scan, an unbind or the auto-cancel, is not a lock.
      if (scan === target && host.camera === target.camera && target.triggers == attempt && target.locked == null) {
        settle(target, try { future.get().isFocusSuccessful } catch (_: Exception) { false })
      }
    }, host.mainExecutor)
  }

  private fun settle(target: Scan, success: Boolean) {
    if (target.locked != null) return
    target.locked = success
    publish()
    if (target.owner == FocusOwner.DOCUMENT && scan === target && host.camera === target.camera) {
      policy.settled(success, SystemClock.uptimeMillis())
      host.log("document focus settled success=$success")
      if (!success) releaseDocument()
    }
  }

  private fun publish() {
    val current = scan
    snapshot = FocusSnapshot(snapshot.generation + 1, current?.owner, current?.locked, current?.heldUntilMs ?: 0,
      System.nanoTime(), SystemClock.elapsedRealtimeNanos())
    host.focusChanged(snapshot)
  }

  /** One owned autofocus scan. A tap's auto-cancels at [heldUntilMs]; a document scan holds until released. */
  private class Scan(val camera: Camera, val heldUntilMs: Long, val owner: FocusOwner) {
    var locked: Boolean? = null
    /** How many times the scan was triggered; an earlier trigger, canceled by a repeat, does not settle it. */
    var triggers = 0
  }

  companion object {
    const val TAP_MS = 5_000L
    /** A document scan unsettled this long fails, and the policy decides what follows. */
    const val SCAN_TIMEOUT_MS = 2_000L
    /** A scan unsettled this long is triggered once more; recorded passive scans ended within 400 ms of a dropped trigger. */
    const val RETRIGGER_MS = 700L
  }
}
