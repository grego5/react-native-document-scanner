package com.margelo.nitro.documentscanner

/**
 * Focus ownership, without hardware or a clock: who holds the lens, the current scan's token and result, the tap's
 * hold, and a generation that rises on every change. Without an owner, continuous autofocus runs. Times are monotonic
 * uptime nanoseconds. Main thread.
 */
internal class FocusPolicy {
  var generation = 0L
    private set
  var scanToken = 0L
    private set
  var owner: FocusOwner? = null
    private set
  /** A settled scan's result; null while it scans and after an unknown result. */
  var locked: Boolean? = null
    private set
  /** Until when a tap holds the lens; [Long.MAX_VALUE] for a capture or page lock, 0 without an owner. */
  var heldUntilNs = 0L
    private set
  /** The owner's scan has not settled. */
  var pending = false
    private set
  private var scanDeadlineNs = 0L

  /** Gives the lens to [owner] and returns the new scan's token. */
  fun start(owner: FocusOwner, nowNs: Long): Long {
    scanToken = ++generation
    this.owner = owner
    locked = null
    pending = true
    heldUntilNs = if (owner.timed) nowNs + TAP_HOLD_NS else Long.MAX_VALUE
    scanDeadlineNs = nowNs + SCAN_TIMEOUT_NS
    return scanToken
  }

  /** Settles scan [token]: true for a lock, false for a failure, null for an unknown result. */
  fun complete(token: Long, success: Boolean?): Boolean {
    if (owner == null || token != scanToken || !pending) return false
    pending = false
    locked = success
    ++generation
    return true
  }

  fun expireTap(token: Long, nowNs: Long, shot: Boolean): Boolean {
    if (shot || owner?.timed != true || token != scanToken || nowNs < heldUntilNs) return false
    release()
    return true
  }

  /** A shot finished: a tap past its hold and a capture's scan end; a page lock stays with its page. */
  fun afterCapture(nowNs: Long): Boolean = when (owner) {
    FocusOwner.TAP, FocusOwner.GUIDE -> expireTap(scanToken, nowNs, false)
    FocusOwner.CAPTURE -> { release(); true }
    FocusOwner.PAGE, null -> false
  }

  /** A settled guide lock becomes a page lock, keeping its scan; true when one held the lens. */
  fun adoptGuideLock(): Boolean {
    if (owner != FocusOwner.GUIDE || locked != true) return false
    owner = FocusOwner.PAGE
    heldUntilNs = Long.MAX_VALUE
    ++generation
    return true
  }

  /** Ends the scanner's own lock, a page or guide lock; true when one held the lens. */
  fun releaseScannerLock(): Boolean {
    if (owner?.scanners != true) return false
    release()
    return true
  }

  fun unbind() = release()

  fun tapDeadline(): Long? = heldUntilNs.takeIf { owner?.timed == true }
  fun scanDeadline(): Long? = scanDeadlineNs.takeIf { owner != null && owner?.timed == false && pending }

  private fun release() {
    owner = null
    locked = null
    heldUntilNs = 0
    pending = false
    ++generation
  }

  companion object {
    const val TAP_HOLD_NS = 5_000_000_000L
    /** A backstop for a scan whose result never arrives. CameraX settles every scan itself, unsuccessful after 5 s
     * (`triggerFocusTimeout` in its log), so this lies past it: at 2 s, dim-light scans on the SM-G998U were cut off
     * and rejected just as the page sharpened. */
    const val SCAN_TIMEOUT_NS = 6_000_000_000L
  }
}
