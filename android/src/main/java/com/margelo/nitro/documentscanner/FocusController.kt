package com.margelo.nitro.documentscanner

import android.os.SystemClock
import androidx.camera.core.Camera
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.MeteringPoint
import com.margelo.nitro.documentscanner.processing.DocumentFocusAction
import com.margelo.nitro.documentscanner.processing.NativeFocus
import com.margelo.nitro.documentscanner.processing.motionValues
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

/** Main-thread CameraX adapter. Shared policy owns scan/tap/document state and generations;
 * this class owns only hardware requests, callback attempts, wakeups and immutable published snapshots.
 */
internal class FocusController(private val host: Host) : AutoCloseable {
  interface Host {
    val camera: Camera?
    val mainExecutor: Executor
    fun shotInFlight(): Boolean
    fun applyCaptureOptions()
    fun postDelayed(delayMs: Long, block: () -> Unit)
    fun log(message: String)
    fun focusChanged(snapshot: FocusSnapshot)
    fun uptimeNs(): Long = SystemClock.uptimeMillis() * 1_000_000
  }
  private val handle = lazy { NativeFocus() }
  private val policy get() = handle.value
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
  fun onCandidate(hint: LiveFocusCandidate, parked: Boolean, point: (LiveFocusCandidate) -> MeteringPoint?) {
    if (closed) return
    val camera = host.camera ?: return
    when (DocumentFocusAction.entries[policy.observeNative(hint.quad?.motionValues(), hint.trusted, hint.focusFailed,
      host.uptimeNs(), host.shotInFlight(), parked)]) {
      DocumentFocusAction.NONE -> Unit
      DocumentFocusAction.RELEASE -> releaseDocument()
      DocumentFocusAction.START -> {
        val target = point(hint)
        if (target == null) {
          policy.documentSettledNative(0, host.uptimeNs())
          releaseDocument()
        } else {
          host.log("document focus start trusted=${hint.trusted}")
          start(camera, FocusMeteringAction.Builder(target, FocusMeteringAction.FLAG_AF).disableAutoCancel().build(),
            FocusOwner.DOCUMENT)
        }
      }
    }
    scheduleDeadlines()
  }
  fun releaseDocument() {
    if (closed) return
    if (policy.releaseDocumentNative(host.shotInFlight())) released()
    scheduleDeadlines()
  }
  fun resetPolicy(keepDocument: Boolean) {
    if (closed) return
    if (policy.resetNative(keepDocument, host.shotInFlight())) released()
    scheduleDeadlines()
  }
  fun afterCapture(documentWanted: Boolean) {
    if (closed) return
    if (policy.afterCaptureNative(host.uptimeNs(), documentWanted)) released()
    else if (policy.wakeNative(host.uptimeNs(), false) == DocumentFocusAction.RELEASE.ordinal) releaseDocument()
    scheduleDeadlines()
  }
  fun unbind() {
    if (closed) return
    scan = null
    ++wakeRevision
    policy.unbindNative()
    publish()
  }
  private fun start(camera: Camera, action: FocusMeteringAction, owner: FocusOwner) {
    val next = Scan(camera, policy.startNative(owner.ordinal, host.uptimeNs()))
    scan = next
    publish() // Options must see the new owner before touching the lens.
    host.applyCaptureOptions()
    scheduleDeadlines()
    trigger(next, action)
    // Android device workaround: repeat once after a passive scan. Eligibility stays with this hardware adapter.
    host.postDelayed(RETRIGGER_MS) {
      if (pending(next) && !host.shotInFlight()) {
        host.log("focus retriggered")
        trigger(next, action)
      }
    }
  }
  private fun pending(target: Scan): Boolean = !closed && scan === target && host.camera === target.camera &&
    policy.snapshotNative().let { it[4] == target.token && it[5] != 0L }
  private fun trigger(target: Scan, action: FocusMeteringAction) {
    val attempt = ++target.triggers
    val future = try { target.camera.cameraControl.startFocusAndMetering(action) } catch (_: Exception) {
      if (pending(target)) settle(target, false)
      return
    }
    future.addListener({
      if (pending(target) && target.triggers == attempt) {
        settle(target, try { future.get().isFocusSuccessful } catch (_: Exception) { false })
      }
    }, host.mainExecutor)
  }
  /** Null completion is supported by the shared policy; Android interprets its own CameraX result as known success/failure. */
  private fun settle(target: Scan, success: Boolean?) {
    if (!pending(target) || !policy.completeNative(target.token, success?.let { if (it) 1 else 0 } ?: -1, host.uptimeNs())) return
    publish()
    if (snapshot.owner == FocusOwner.DOCUMENT) {
      host.log("document focus settled success=$success")
      if (success != true) releaseDocument()
    }
    // Target loss may already be overdue when an asynchronous scan settles.
    if (policy.wakeNative(host.uptimeNs(), host.shotInFlight()) == DocumentFocusAction.RELEASE.ordinal) releaseDocument()
    scheduleDeadlines()
  }
  private fun released() {
    val ended = scan
    scan = null
    publish() // Options must see ownership end before parking or restoring continuous AF.
    if (ended != null && ended.camera === host.camera) {
      try { ended.camera.cameraControl.cancelFocusAndMetering() } catch (_: Exception) { }
      host.applyCaptureOptions()
    }
  }
  private fun publish() {
    val v = policy.snapshotNative()
    snapshot = FocusSnapshot(v[0], FocusOwner.entries.getOrNull(v[1].toInt()),
      when (v[2]) { 0L -> false; 1L -> true; else -> null },
      if (v[3] == Long.MAX_VALUE) Long.MAX_VALUE else v[3] / 1_000_000,
      System.nanoTime(), SystemClock.elapsedRealtimeNanos())
    host.focusChanged(snapshot)
  }
  private fun scheduleDeadlines() {
    val revision = ++wakeRevision
    if (closed) return
    val now = host.uptimeNs()
    val deadline = policy.deadlinesNative(now).withIndex().filter {
      it.value > 0 && (it.value > now || it.index == 1 || it.index == 0 && !host.shotInFlight())
    }.minOfOrNull { it.value } ?: return
    host.postDelayed((deadline - now).coerceAtLeast(0).let { (it + 999_999) / 1_000_000 }) {
      if (closed || revision != wakeRevision) return@postDelayed
      val current = scan
      val time = host.uptimeNs()
      val deadlines = policy.deadlinesNative(time)
      if (current != null && deadlines[0] != 0L && time >= deadlines[0] &&
        policy.expireTapNative(current.token, time, host.shotInFlight())) released()
      if (current != null && deadlines[1] != 0L && time >= deadlines[1] && pending(current)) settle(current, false)
      if (policy.wakeNative(time, host.shotInFlight()) == DocumentFocusAction.RELEASE.ordinal) releaseDocument()
      // Retry/sharpness deadlines wake readiness, but a fresh candidate must request the next hardware scan.
      host.focusChanged(snapshot)
      scheduleDeadlines()
    }
  }
  override fun close() {
    if (closed) return
    closed = true; ++wakeRevision; scan = null
    if (handle.isInitialized()) handle.value.close()
  }
  private class Scan(val camera: Camera, val token: Long) { var triggers = 0 }
  companion object {
    /** Android-only workaround, not a portable focus-policy deadline. */
    const val RETRIGGER_MS = 700L
  }
}
